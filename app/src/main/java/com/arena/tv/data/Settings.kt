package com.arena.tv.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arena.tv.sports.Leagues
import com.arena.tv.sports.Region
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock

@kotlinx.serialization.Serializable
enum class SourceType { NONE, M3U, XTREAM, MAC }

/** One IPTV provider. Several can be added, shown one at a time or combined. */
@kotlinx.serialization.Serializable
data class Playlist(
    val id: String,
    val name: String,
    val type: SourceType = SourceType.XTREAM,
    val m3uUrl: String = "",
    val server: String = "",
    val user: String = "",
    val pass: String = "",
    /** MAC address for portal (Stalker / MAG) logins. */
    val mac: String = "",
    /** "auto", "ts" or "m3u8" */
    val format: String = "auto",
    /** Optional guide URL(s) overriding the provider's, separated by commas. */
    val epgUrl: String = "",
    val userAgent: String = "",
    val enabled: Boolean = true,
    /** Movies and series are optional: big providers list hundreds of thousands, which makes loading slow. */
    val loadMovies: Boolean = true,
    val loadSeries: Boolean = true,
    /** Only fetch the TV guide for sports categories (fastest; enough for the Sports center). */
    val guideSportsOnly: Boolean = false,
    val lastRefresh: Long = 0,
    val lastEpg: Long = 0,
    /** When movies & series were last updated (they update on their own schedule). */
    val lastVod: Long = 0,
    /** Guide URL(s) the M3U advertised (url-tvg). */
    val advertisedEpg: String = "",
    val account: String = "",
    val channelCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val error: String? = null,
) {
    val valid get() = when (type) {
        SourceType.NONE -> false
        SourceType.M3U -> m3uUrl.startsWith("http")
        SourceType.XTREAM -> server.isNotBlank() && user.isNotBlank()
        SourceType.MAC -> server.isNotBlank() && mac.filter { it.isLetterOrDigit() }.length == 12
    }
    val ua: String? get() = userAgent.ifBlank { null }
    val base: String get() = XtreamClient.base(server)
    val apiUrl: String get() = "$base/player_api.php?username=${user.enc()}&password=${pass.enc()}"
    val effectiveEpgUrls: List<String>
        get() {
            // Extra guide links are added to the provider's own guide (MAC portals use them instead of the portal's).
            val manual = epgUrl.split(',', '\n', ' ').map { it.trim() }.filter { it.startsWith("http") }
            val provider = if (type == SourceType.XTREAM && server.isNotBlank()) listOf("$base/xmltv.php?username=${user.enc()}&password=${pass.enc()}")
                else advertisedEpg.split(',', '\n').map { it.trim() }.filter { it.startsWith("http") }
            return (provider + manual).distinct()
        }
}

/** Playback and browsing options (Settings → Playback / Browsing). */
@kotlinx.serialization.Serializable
data class Prefs(
    /** Screen the app opens on: home, sports, live, movies, series. */
    val startScreen: String = "home",
    /** Stream buffer: low (starts fastest), balanced, high (smoothest on weak connections). */
    val buffer: String = "balanced",
    /** Preferred audio language ("" = the stream's default). */
    val audioLang: String = "",
    /** Preferred subtitle language ("" = off unless the stream forces them). */
    val subtitleLang: String = "",
    /** Look for closed captions hidden inside live channels (off: it broke playback on some TVs). */
    val hiddenCaptions: Boolean = false,
    /** Translate subtitles on the device into this language ("" = off). */
    val subtitleTranslate: String = "",
    /** small, normal, large, huge */
    val subtitleSize: String = "normal",
    val keepScreenOn: Boolean = true,
    /** Show the time on screen while watching, not only with the controls. */
    val clockAlways: Boolean = false,
    /** Switch the TV's refresh rate to the video's frame rate (removes judder). */
    val matchFrameRate: Boolean = false,
    /** Live TV opens in: library or guide. */
    val liveView: String = "library",
    /** system, 12 or 24 */
    val clock: String = "system",
    // ---- Scores while watching (Settings → Sports) ----
    /** Games you pinned from a match page. */
    val scorePinned: Boolean = true,
    /** Live games of the teams you follow. */
    val scoreTeams: Boolean = true,
    /** The live game on the channel you're watching. */
    val scoreThisChannel: Boolean = false,
    /** rows (one line per game) or compact (one at a time). */
    val scoreStyle: String = "rows",
    /** Pinned games as "gameId@startMillis" (old ones are dropped automatically). */
    val pinnedGames: List<String> = emptyList(),
    /** Movies & series update: daily (overnight), 3days, weekly or manual. */
    val vodSchedule: String = "daily",
    /** Sports center "For you" layout: spotlight (big featured game), rows (featured row, no banner) or list (compact list). */
    val sportsLayout: String = "rows",
    // ---- Your teams ----
    /** A reminder 10 minutes before your teams play. */
    val remindTeams: Boolean = true,
    /** The final score when your team's game ends. */
    val finalScores: Boolean = true,
    // ---- Title Optimizer ----
    val titleOptimizer: Boolean = false,
    /** Words to remove (null = the default list). */
    val titleWords: List<String>? = null,
    /** Also remove country prefixes like "UK:", "|AR|", "[EN]". */
    val titleCountry: Boolean = false,
    /** Also remove emojis and symbols. */
    val titleEmoji: Boolean = true,
    // ---- Recording ----
    /** Folder recordings go to ("" = the app's own storage). */
    val recFolder: String = "",
    /** channel_date_title, date_channel_title or title_date */
    val recPattern: String = "channel_date_title",
    /** Delete finished recordings after this many days (0 = keep until deleted). */
    val recKeepDays: Int = 0,
    val recMax: Int = 1,
    // ---- Planner ----
    /** Start scheduled recordings this many minutes early. */
    val planEarly: Int = 0,
    /** Keep recording this many minutes after the scheduled end. */
    val planLate: Int = 0,
    /** Reminders go off this many minutes before a show starts. */
    val planRemind: Int = 5,
)

data class TraktAuth(
    val clientId: String = "",
    val clientSecret: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val username: String = "",
) {
    val connected get() = accessToken.isNotBlank()
}

data class AppSettings(
    val playlists: List<Playlist> = emptyList(),
    /** "" = combine every enabled playlist; otherwise the id of the one playlist to show. */
    val activePlaylist: String = "",
    /** "playlistId|group" of channel categories the user turned off. */
    val hiddenGroups: Set<String> = emptySet(),
    val regions: Set<Region> = emptySet(),
    val enabledLeagues: Set<String> = Leagues.defaultEnabledKeys,
    /** Sports shown in the Sports section (espn names). Empty = all. */
    val followedSports: Set<String> = emptySet(),
    /** Parental PIN for adult channels ("" = not set yet). */
    val parentalPin: String = "",
    /** Hide adult categories completely (instead of showing them locked). */
    val hideAdult: Boolean = false,
    /** Odds as decimal (2.50) or American (+150). */
    val decimalOdds: Boolean = true,
    val trakt: TraktAuth = TraktAuth(),
    val prefs: Prefs = Prefs(),
) {
    val hasSource get() = playlists.any { it.valid && it.enabled }
    /** Playlists whose channels are shown right now. */
    val activeIds: List<String>
        get() = playlists.filter { it.enabled && it.valid }.map { it.id }
            .let { ids -> if (activePlaylist.isNotEmpty() && activePlaylist in ids) listOf(activePlaylist) else ids }
    val lastRefresh: Long get() = playlists.filter { it.enabled }.minOfOrNull { it.lastRefresh } ?: 0
    val lastEpgRefresh: Long get() = playlists.filter { it.enabled }.minOfOrNull { it.lastEpg } ?: 0
    val anyGuide: Boolean get() = playlists.any { it.enabled && it.effectiveEpgUrls.isNotEmpty() }
    fun playlist(id: String) = playlists.firstOrNull { it.id == id }
}

private fun String.enc() = java.net.URLEncoder.encode(this, "UTF-8")

private val Context.dataStore by preferencesDataStore("settings")

class SettingsStore(private val context: Context) {
    private object K {
        val source = stringPreferencesKey("source")
        val m3u = stringPreferencesKey("m3u")
        val xs = stringPreferencesKey("xs")
        val xu = stringPreferencesKey("xu")
        val xp = stringPreferencesKey("xp")
        val xf = stringPreferencesKey("xf")
        val epg = stringPreferencesKey("epg")
        val ua = stringPreferencesKey("ua")
        val regions = stringSetPreferencesKey("regions")
        val leagues = stringSetPreferencesKey("leagues")
        val lastPl = longPreferencesKey("lastPl")
        val lastEpg = longPreferencesKey("lastEpg")
        val plEpg = stringPreferencesKey("plEpg")
        val account = stringPreferencesKey("account")
        val tId = stringPreferencesKey("trakt_id")
        val tSecret = stringPreferencesKey("trakt_secret")
        val tAccess = stringPreferencesKey("trakt_access")
        val tRefresh = stringPreferencesKey("trakt_refresh")
        val tExpires = longPreferencesKey("trakt_expires")
        val tUser = stringPreferencesKey("trakt_user")
        val playlists = stringPreferencesKey("playlists")
        val active = stringPreferencesKey("active_playlist")
        val hidden = stringSetPreferencesKey("hidden_groups")
        val sports = stringSetPreferencesKey("followed_sports")
        val dataVersion = longPreferencesKey("data_version")
        val oddsAmerican = stringPreferencesKey("odds_format")
        val pin = stringPreferencesKey("parental_pin")
        val hideAdult = stringPreferencesKey("hide_adult")
        val prefs = stringPreferencesKey("prefs")
        val leaguesV2 = stringPreferencesKey("leagues_v2")
        val sportsV2 = stringPreferencesKey("sports_v2")
    }
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p -> read(p).also { flowHasSource = it.hasSource } }

    suspend fun current(): AppSettings = flow.first()
    /** Last known "is there a playlist" (for quick checks in click handlers). */
    @Volatile var flowHasSource: Boolean = true
        internal set

    private fun read(p: Preferences): AppSettings {
        var lists = p[K.playlists]?.let { runCatching { json.decodeFromString<List<Playlist>>(it) }.getOrNull() } ?: emptyList()
        // Version 2 stored a single provider: turn it into the first playlist.
        if (lists.isEmpty()) {
            val type = runCatching { SourceType.valueOf(p[K.source] ?: "NONE") }.getOrDefault(SourceType.NONE)
            if (type != SourceType.NONE) lists = listOf(Playlist(
                id = "p1", name = "My playlist", type = type, m3uUrl = p[K.m3u] ?: "", server = p[K.xs] ?: "", user = p[K.xu] ?: "",
                pass = p[K.xp] ?: "", format = p[K.xf] ?: "ts", epgUrl = p[K.epg] ?: "", userAgent = p[K.ua] ?: "",
                advertisedEpg = p[K.plEpg] ?: "", account = p[K.account] ?: "",
            ))
        }
        return AppSettings(
            playlists = lists,
            activePlaylist = p[K.active] ?: "",
            hiddenGroups = p[K.hidden] ?: emptySet(),
            regions = p[K.regions]?.mapNotNull { runCatching { Region.valueOf(it) }.getOrNull() }?.toSet() ?: emptySet(),
            // Leagues added in 3.11 (cricket, WWE, AEW) start switched on, also for people who already picked leagues.
            enabledLeagues = p[K.leagues]?.let { if (p[K.leaguesV2] == null) it + setOf("cricket/all", "wrestling/wwe", "wrestling/aew") else it }
                ?: Leagues.defaultEnabledKeys,
            followedSports = (p[K.sports] ?: emptySet()).let { if (it.isNotEmpty() && p[K.sportsV2] == null) it + setOf("cricket", "wrestling") else it },
            decimalOdds = p[K.oddsAmerican] != "american",
            parentalPin = p[K.pin] ?: "",
            hideAdult = p[K.hideAdult] == "1",
            prefs = p[K.prefs]?.let { runCatching { json.decodeFromString<Prefs>(it) }.getOrNull() } ?: Prefs(),
            trakt = TraktAuth(p[K.tId] ?: "", p[K.tSecret] ?: "", p[K.tAccess] ?: "", p[K.tRefresh] ?: "", p[K.tExpires] ?: 0, p[K.tUser] ?: ""),
        )
    }

    private val editLock = kotlinx.coroutines.sync.Mutex()

    /** Read-modify-write of the playlist list (safe against concurrent updates). */
    suspend fun updatePlaylists(f: (List<Playlist>) -> List<Playlist>) = editLock.withLock {
        context.dataStore.edit { p ->
            val cur = read(p).playlists
            p[K.playlists] = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Playlist.serializer()), f(cur))
        }
    }

    suspend fun updatePlaylist(id: String, f: (Playlist) -> Playlist) = updatePlaylists { l -> l.map { if (it.id == id) f(it) else it } }

    /** Bumped when stored channel data must be re-imported after an app update. */
    suspend fun dataVersion(): Long = context.dataStore.data.first()[K.dataVersion] ?: 0
    suspend fun setDataVersion(v: Long) = context.dataStore.edit { it[K.dataVersion] = v }

    suspend fun updatePrefs(f: (Prefs) -> Prefs) = editLock.withLock {
        context.dataStore.edit { p ->
            val cur = p[K.prefs]?.let { runCatching { json.decodeFromString<Prefs>(it) }.getOrNull() } ?: Prefs()
            p[K.prefs] = json.encodeToString(Prefs.serializer(), f(cur))
        }
    }

    suspend fun setActivePlaylist(id: String) = context.dataStore.edit { it[K.active] = id }
    suspend fun setHiddenGroups(h: Set<String>) = context.dataStore.edit { it[K.hidden] = h }
    suspend fun setPin(pin: String) = context.dataStore.edit { it[K.pin] = pin }
    suspend fun setHideAdult(hide: Boolean) = context.dataStore.edit { it[K.hideAdult] = if (hide) "1" else "0" }
    suspend fun setDecimalOdds(decimal: Boolean) = context.dataStore.edit { it[K.oddsAmerican] = if (decimal) "decimal" else "american" }
    suspend fun setFollowedSports(s: Set<String>) = context.dataStore.edit { it[K.sports] = s; it[K.sportsV2] = "1" }

    suspend fun setRegions(r: Set<Region>) = context.dataStore.edit { it[K.regions] = r.map { x -> x.name }.toSet() }
    suspend fun setLeagues(l: Set<String>) = context.dataStore.edit { it[K.leagues] = l; it[K.leaguesV2] = "1" }
    suspend fun saveTrakt(t: TraktAuth) = context.dataStore.edit {
        it[K.tId] = t.clientId.trim(); it[K.tSecret] = t.clientSecret.trim(); it[K.tAccess] = t.accessToken
        it[K.tRefresh] = t.refreshToken; it[K.tExpires] = t.expiresAt; it[K.tUser] = t.username
    }
}
