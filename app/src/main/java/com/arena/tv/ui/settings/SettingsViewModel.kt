package com.arena.tv.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arena.tv.AppContainer
import com.arena.tv.data.FavoriteTeamEntity
import com.arena.tv.data.PhoneEntryServer
import com.arena.tv.data.Playlist
import com.arena.tv.data.PlaylistGroup
import com.arena.tv.sports.League
import com.arena.tv.sports.Leagues
import com.arena.tv.sports.Region
import com.arena.tv.sports.Sport
import com.arena.tv.sports.SportsApi
import com.arena.tv.sports.Team
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class TeamResult(val team: Team, val league: League)

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val refresh = c.iptv.state
    val channelCount = c.db.channels().countFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val programCount = c.db.programs().countFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val teams = c.db.user().teams().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val trakt = c.trakt.state

    // ---------------- Playlists ----------------
    fun savePlaylist(p: Playlist) = viewModelScope.launch {
        val id = p.id.ifBlank { "p" + UUID.randomUUID().toString().take(6) }
        c.settings.updatePlaylists { l -> if (l.any { it.id == id }) l.map { if (it.id == id) p.copy(id = id, lastRefresh = 0) else it } else l + p.copy(id = id) }
        c.iptv.refreshAll(onlyId = id)
    }
    fun removePlaylist(id: String) = viewModelScope.launch { c.iptv.removePlaylist(id) }
    fun setEnabled(id: String, on: Boolean) = viewModelScope.launch {
        c.settings.updatePlaylist(id) { it.copy(enabled = on) }
        if (on && (settings.value?.playlist(id)?.lastRefresh ?: 0) == 0L) c.iptv.refreshAll(onlyId = id) else c.iptv.applyVisibility()
    }
    fun setActive(id: String) = viewModelScope.launch { c.settings.setActivePlaylist(id); c.iptv.applyVisibility() }
    fun reload(id: String?) = viewModelScope.launch { c.iptv.refreshAll(onlyId = id) }
    fun refreshGuide() = viewModelScope.launch { c.iptv.refreshEpg() }

    // ---------------- Playback & browsing ----------------
    fun updatePrefs(f: (com.arena.tv.data.Prefs) -> com.arena.tv.data.Prefs) { viewModelScope.launch { c.settings.updatePrefs(f) } }

    // ---------------- Backup & restore ----------------
    /** Result of the last backup action, shown under the buttons. */
    val backupMessage = MutableStateFlow<String?>(null)
    /** A backup waiting for the user to confirm it replaces this device's playlists. */
    val pendingRestore = MutableStateFlow<com.arena.tv.data.Backup?>(null)
    val backupUrl = MutableStateFlow<String?>(null)
    private var backupServer: com.arena.tv.data.BackupServer? = null

    fun exportTo(context: android.content.Context, uri: android.net.Uri) = viewModelScope.launch {
        backupMessage.value = runCatching {
            val text = c.backup.export()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
            }
            "✓ Backup saved."
        }.getOrElse { "Couldn't save the backup: ${it.message}" }
    }

    fun importFrom(context: android.content.Context, uri: android.net.Uri) = viewModelScope.launch {
        runCatching {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            }
            pendingRestore.value = c.backup.parse(text)
        }.onFailure { backupMessage.value = it.message ?: "Couldn't read that file." }
    }

    fun confirmRestore() = viewModelScope.launch {
        val b = pendingRestore.value ?: return@launch
        pendingRestore.value = null
        runCatching { c.backup.restore(b) }
            .onSuccess { backupMessage.value = "✓ Restored ${b.playlists.size} playlist(s). Loading channels…"; stopBackupServer(); c.iptv.refreshAll(onlyId = null) }
            .onFailure { backupMessage.value = "Couldn't restore: ${it.message}" }
    }

    fun startBackupServer() {
        stopBackupServer()
        val s = com.arena.tv.data.BackupServer(
            exportJson = { kotlinx.coroutines.runBlocking { c.backup.export() } },
            onReceived = { text -> runCatching { pendingRestore.value = c.backup.parse(text); null }.getOrElse { it.message ?: "Not a TuTu backup." } },
        )
        backupServer = s
        backupUrl.value = runCatching { s.start() }.getOrNull() ?: ""
    }
    fun stopBackupServer() { backupServer?.stop(); backupServer = null; backupUrl.value = null }

    // Phone entry (QR)
    val phoneDraft = MutableStateFlow<Playlist?>(null)
    val phoneUrl = MutableStateFlow<String?>(null)
    private var server: PhoneEntryServer? = null
    fun startPhoneEntry() {
        stopPhoneEntry()
        val s = PhoneEntryServer { p -> phoneDraft.value = p }
        server = s
        phoneUrl.value = s.start() ?: ""
    }
    fun stopPhoneEntry() { server?.stop(); server = null; phoneUrl.value = null; phoneDraft.value = null }
    override fun onCleared() { stopPhoneEntry(); stopBackupServer() }

    // ---------------- Channel categories ----------------
    val allGroups: StateFlow<List<PlaylistGroup>> = c.settings.flow.map { it.playlists.filter { p -> p.enabled }.map { p -> p.id } }
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(emptyList()) else c.db.channels().allGroups(ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setGroupHidden(key: String, hidden: Boolean) = viewModelScope.launch {
        val cur = c.settings.current().hiddenGroups
        c.settings.setHiddenGroups(if (hidden) cur + key else cur - key)
        c.iptv.applyVisibility()
    }
    fun setAllHidden(keys: List<String>, hidden: Boolean) = viewModelScope.launch {
        val cur = c.settings.current().hiddenGroups
        c.settings.setHiddenGroups(if (hidden) cur + keys else cur - keys.toSet())
        c.iptv.applyVisibility()
    }

    // ---------------- Sports & teams ----------------
    fun toggleSport(s: Sport) = viewModelScope.launch {
        val cur = c.settings.current().followedSports.ifEmpty { Sport.entries.map { it.espn }.toSet() }
        val next = if (s.espn in cur) cur - s.espn else cur + s.espn
        c.settings.setFollowedSports(if (next.size == Sport.entries.size) emptySet() else next)
    }
    fun setHideAdult(h: Boolean) = viewModelScope.launch { c.settings.setHideAdult(h); c.iptv.applyVisibility() }
    fun lockNow() = c.parental.lock()
    suspend fun checkPin(pin: String) = c.parental.tryUnlock(pin)
    fun resetPin(newPin: String) = viewModelScope.launch { c.settings.setPin(newPin) }
    fun setDecimalOdds(d: Boolean) = viewModelScope.launch { c.settings.setDecimalOdds(d) }
    fun toggleRegion(r: Region) = viewModelScope.launch {
        val cur = c.settings.current().regions
        c.settings.setRegions(if (r in cur) cur - r else cur + r)
    }
    fun toggleLeague(key: String) = viewModelScope.launch {
        val cur = c.settings.current().enabledLeagues
        c.settings.setLeagues(if (key in cur) cur - key else cur + key)
    }
    fun removeTeam(key: String) = viewModelScope.launch { c.db.user().removeTeam(key) }
    fun followTeam(r: TeamResult) = viewModelScope.launch {
        c.db.user().addTeam(FavoriteTeamEntity(r.team.key(r.league.sport), r.team.displayName, r.team.logo))
    }

    private val teamCache = HashMap<String, List<Team>>()
    private val api = SportsApi(c.http)
    val teamResults = MutableStateFlow<List<TeamResult>>(emptyList())
    val teamLoading = MutableStateFlow(false)

    /** Searches teams across the enabled team-sport leagues (loaded once, then filtered locally). */
    fun searchTeams(q: String) = viewModelScope.launch {
        val s = c.settings.current()
        val teamSports = setOf(Sport.SOCCER, Sport.BASKETBALL, Sport.FOOTBALL, Sport.HOCKEY, Sport.BASEBALL, Sport.RUGBY, Sport.AUSSIE)
        val leagues = Leagues.all.filter { it.sport in teamSports && it.key in s.enabledLeagues &&
            (s.followedSports.isEmpty() || it.sport.espn in s.followedSports) && !it.espnId.contains("college") }
        val missing = leagues.filter { it.key !in teamCache }
        if (missing.isNotEmpty()) {
            teamLoading.value = true
            missing.map { l -> async { l.key to runCatching { api.teams(l) }.getOrDefault(emptyList()) } }.awaitAll()
                .forEach { (k, v) -> teamCache[k] = v }
            teamLoading.value = false
        }
        val needle = com.arena.tv.matching.Text.normalize(q)
        teamResults.value = leagues.flatMap { l -> teamCache[l.key].orEmpty().map { TeamResult(it, l) } }
            .filter { needle.isEmpty() || com.arena.tv.matching.Text.normalize(it.team.displayName).contains(needle) }
            .distinctBy { it.team.key(it.league.sport) }
            .take(60)
    }

    // ---------------- Trakt ----------------
    fun traktConnect(id: String, secret: String) = c.trakt.startLogin(id, secret)
    fun traktCancel() = c.trakt.cancelLogin()
    fun traktDisconnect() = c.trakt.disconnect()
    fun traktSync() = viewModelScope.launch { c.trakt.sync(force = true) }
}
