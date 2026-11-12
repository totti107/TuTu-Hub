package com.arena.tv.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arena.tv.AppContainer
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.FavoriteChannelEntity
import com.arena.tv.data.GroupCount
import com.arena.tv.data.ProgramEntity
import com.arena.tv.matching.Text
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Section {
    data object All : Section
    data object Favorites : Section
    data object Recent : Section
    data class Group(val name: String, val playlistId: String) : Section
}

data class NowNext(val now: ProgramEntity?, val next: ProgramEntity?)

fun isSportyGroup(name: String): Boolean = com.arena.tv.matching.SportyGroups.isSporty(name)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveTvViewModel(private val c: AppContainer) : ViewModel() {
    val groups: StateFlow<List<GroupCount>> = c.db.channels().groups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val total = c.db.channels().countFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val favCount = c.db.channels().favoriteCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val recentCount = c.db.channels().recentCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val tab = MutableStateFlow(if (c.prefs.value.liveView == "guide") 1 else 0) // 0 library, 1 EPG, 2 sports
    val section = MutableStateFlow<Section?>(null)
    val query = MutableStateFlow("")
    val groupQuery = MutableStateFlow("")
    val hdOnly = MutableStateFlow(false)
    val selected = MutableStateFlow<ChannelEntity?>(null)

    val favoriteIds: StateFlow<Set<String>> = c.db.user().favoriteIds().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val refresh = c.iptv.state
    /** Playlist names, to label each playlist's categories when several are shown. */
    val playlistNames: StateFlow<Map<String, String>> = c.settings.flow.map { st -> st.playlists.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Back from full screen, or coming back to Live TV later (e.g. from Home): open the channel you were
        // watching — its category, selected and previewed, with the remote on it.
        viewModelScope.launch {
            com.arena.tv.ui.player.Streams.lastWatched?.let { id -> c.db.channels().byId(id)?.let { goTo(it) } }
            com.arena.tv.ui.player.Streams.nowPlaying.collect { id ->
                if (id != null) {
                    c.db.channels().byId(id)?.let { goTo(it) }
                    com.arena.tv.ui.player.Streams.nowPlaying.value = null
                }
            }
        }
        // Shortcuts from the Home screen ("Favorites", "TV Guide").
        viewModelScope.launch {
            c.liveRequest.collect { r ->
                when (r) {
                    "guide" -> tab.value = 1
                    "favorites" -> { tab.value = 0; section.value = Section.Favorites }
                    "library" -> tab.value = 0
                }
                if (r != null) c.liveRequest.value = null
            }
        }
        viewModelScope.launch {
            combine(groups, favoriteIds, tab) { g, f, t -> Triple(g, f, t) }.collect { (g, f, t) ->
                val cur = section.value
                val sporty = g.filter { isSportyGroup(it.groupTitle) }
                val valid = when (cur) {
                    null -> false
                    is Section.Group -> g.any { it.groupTitle == cur.name && it.playlistId == cur.playlistId } && (t != 2 || isSportyGroup(cur.name))
                    else -> t != 2 || cur == Section.Favorites
                }
                if (!valid) section.value = when {
                    t == 2 -> sporty.firstOrNull()?.let { Section.Group(it.groupTitle, it.playlistId) } ?: Section.All
                    f.isNotEmpty() -> Section.Favorites
                    else -> g.firstOrNull { !it.adult }?.let { Section.Group(it.groupTitle, it.playlistId) } ?: Section.All
                }
            }
        }
    }

    private val hdWords = setOf("hd", "fhd", "uhd", "4k", "1080p", "1080", "hevc")

    val unlocked = c.parental.unlocked
    val parental = c.parental
    suspend fun checkPin(pin: String) = c.parental.tryUnlock(pin)

    val channels: StateFlow<List<ChannelEntity>> = combine(section, query.debounce(250), hdOnly) { s, q, hd -> Triple(s, q, hd) }
        .flatMapLatest { (s, q, hd) ->
            val base: Flow<List<ChannelEntity>> = when {
                q.isNotBlank() -> c.db.channels().search(q.trim())
                s is Section.Favorites -> c.db.channels().favorites()
                s is Section.Recent -> c.db.channels().recents()
                s is Section.Group -> c.db.channels().inGroup(s.playlistId, s.name)
                s is Section.All -> c.db.channels().all()
                else -> flow { emit(emptyList()) }
            }
            val filtered = if (!hd) base else base.map { l -> l.filter { ch -> Text.tokens(Text.normalize(ch.rawName.ifEmpty { ch.name })).any { it in hdWords } } }
            // Adult channels only appear once the PIN has been entered.
            combine(filtered, c.parental.unlocked) { l, open -> if (open) l else l.filter { !it.adult } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val ticker: Flow<Long> = flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }

    /** Now / next for the visible channels, refreshed every minute. */
    val guide: StateFlow<Map<String, NowNext>> = combine(channels, ticker) { ch, now -> ch to now }
        .mapLatest { (ch, now) ->
            val keys = ch.take(3000).mapNotNull { it.epgKey }.distinct()
            if (keys.isEmpty()) return@mapLatest emptyMap()
            val progs = keys.chunked(500).flatMap { c.db.programs().forKeys(it, now, now + 6 * 3600_000L) }.groupBy { it.epgKey }
            ch.mapNotNull { channel ->
                val list = channel.epgKey?.let { progs[it] } ?: return@mapNotNull null
                val cur = list.firstOrNull { it.start <= now && it.stop > now }
                val next = list.firstOrNull { it.start >= (cur?.stop ?: now) }
                channel.id to NowNext(cur, next)
            }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Today's schedule for the selected channel (right-hand pane). */
    val schedule: StateFlow<List<ProgramEntity>> = combine(selected, ticker) { s, now -> s to now }
        .mapLatest { (s, now) ->
            val key = s?.epgKey ?: return@mapLatest emptyList()
            c.db.programs().schedule(key, now - 3 * 3600_000L, now + 18 * 3600_000L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** EPG grid: programmes for the first channels of the section across the visible window. */
    fun gridPrograms(channels: List<ChannelEntity>, from: Long, to: Long): Flow<Map<String, List<ProgramEntity>>> = flow {
        val keys = channels.mapNotNull { it.epgKey }.distinct()
        if (keys.isEmpty()) { emit(emptyMap()); return@flow }
        emit(keys.chunked(500).flatMap { c.db.programs().forKeys(it, from, to) }.groupBy { it.epgKey })
    }

    fun select(ch: ChannelEntity) { selected.value = ch }
    // ---------------- Hold-OK menu: favorite / hide ----------------
    /** The channel whose menu is open. */
    val menuFor = MutableStateFlow<ChannelEntity?>(null)
    /** Just hidden (for the "Undo" note). */
    val justHidden = MutableStateFlow<ChannelEntity?>(null)
    private var undoJob: kotlinx.coroutines.Job? = null

    fun hideChannel(ch: ChannelEntity) = viewModelScope.launch {
        menuFor.value = null
        c.settings.setHiddenChannels(c.settings.current().hiddenChannels + ch.id)
        c.iptv.applyVisibility()
        justHidden.value = ch
        undoJob?.cancel(); undoJob = viewModelScope.launch { kotlinx.coroutines.delay(8000); justHidden.value = null }
    }
    fun undoHide() = viewModelScope.launch {
        val ch = justHidden.value ?: return@launch
        justHidden.value = null
        c.settings.setHiddenChannels(c.settings.current().hiddenChannels - ch.id)
        c.iptv.applyVisibility()
    }
    fun hideCategory(ch: ChannelEntity) = viewModelScope.launch {
        menuFor.value = null
        c.settings.setHiddenGroups(c.settings.current().hiddenGroups + "${ch.playlistId}|${ch.groupTitle}")
        c.iptv.applyVisibility()
    }

    /** Ask the channel list to put the remote's focus on the selected channel. */
    val focusSelected = MutableStateFlow(false)
    private fun goTo(ch: ChannelEntity) {
        if (tab.value == 2) tab.value = 0
        val sec = section.value
        // Keep All / Favorites / Recent if the channel is in there; otherwise open its own category.
        if (sec !is Section.All && sec !is Section.Favorites && sec !is Section.Recent) section.value = Section.Group(ch.groupTitle, ch.playlistId)
        selected.value = ch
        focusSelected.value = true
    }

    fun toggleFavorite(id: String) = viewModelScope.launch {
        if (id in favoriteIds.value) c.db.user().removeFavorite(id)
        else c.db.user().addFavorite(FavoriteChannelEntity(id, System.currentTimeMillis()))
    }

    fun refreshNow() = viewModelScope.launch { c.iptv.refreshAll() }
}
