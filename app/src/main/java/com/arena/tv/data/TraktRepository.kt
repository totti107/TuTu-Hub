package com.arena.tv.data

import com.arena.tv.matching.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** What we tell Trakt the user is watching. */
sealed interface TraktItem {
    data class Movie(val title: String, val year: Int?, val tmdb: String?) : TraktItem
    data class Episode(val showTitle: String, val showYear: Int?, val showTmdb: String?, val season: Int, val number: Int) : TraktItem
}

data class TraktListItem(val isMovie: Boolean, val title: String, val year: Int?, val tmdb: String?)

data class DeviceLogin(val userCode: String, val url: String, val expiresAt: Long)

data class TraktState(
    val connected: Boolean = false,
    val username: String = "",
    val login: DeviceLogin? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val lastSync: Long = 0,
    val watchedMovies: Set<String> = emptySet(),          // tmdb ids and "title|year" keys
    val watchedShows: Set<String> = emptySet(),
    val watchedEpisodes: Map<String, Set<String>> = emptyMap(), // show key -> "s:e"
    val watchlist: List<TraktListItem> = emptyList(),
)

/**
 * Trakt.tv: device-code sign-in (works on TV remotes), scrobbling, watched history and watchlist.
 * Needs the user's own Trakt API app (Client ID + Secret) — see Settings.
 */
class TraktRepository(private val http: OkHttpClient, private val db: ArenaDb, private val settings: SettingsStore) {
    private val api = "https://api.trakt.tv"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(TraktState())
    val state: StateFlow<TraktState> = _state.asStateFlow()
    private var loginJob: Job? = null

    init {
        scope.launch {
            val t = settings.current().trakt
            _state.update { it.copy(connected = t.connected, username = t.username) }
        }
    }

    // ---------------- HTTP ----------------

    private fun call(method: String, path: String, body: JsonElement?, auth: TraktAuth, token: Boolean = true): Pair<Int, JsonElement?> {
        val b = Request.Builder().url("$api$path")
            .header("Content-Type", "application/json")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", auth.clientId)
            .header("User-Agent", "TuTuTV/1.0")
        if (token && auth.accessToken.isNotBlank()) b.header("Authorization", "Bearer ${auth.accessToken}")
        val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
        b.method(method, rb ?: if (method == "POST") "".toRequestBody() else null)
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            return r.code to text.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        }
    }

    /** Valid auth, refreshing the token when it's about to expire. */
    private suspend fun auth(): TraktAuth? {
        var t = settings.current().trakt
        if (!t.connected) return null
        if (t.expiresAt - System.currentTimeMillis() < 2 * 24 * 3600_000L && t.refreshToken.isNotBlank()) {
            val (code, res) = call("POST", "/oauth/token", buildJsonObject {
                put("refresh_token", t.refreshToken); put("client_id", t.clientId); put("client_secret", t.clientSecret)
                put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob"); put("grant_type", "refresh_token")
            }, t, token = false)
            val o = res as? JsonObject
            if (code == 200 && o != null) {
                t = t.copy(accessToken = o.s("access_token") ?: t.accessToken, refreshToken = o.s("refresh_token") ?: t.refreshToken,
                    expiresAt = System.currentTimeMillis() + (o.s("expires_in")?.toLongOrNull() ?: 0) * 1000)
                settings.saveTrakt(t)
            } else if (code == 401 || code == 400) {
                disconnectLocal("Trakt sign-in expired. Connect again.")
                return null
            }
        }
        return t
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.o() = this as? JsonObject

    // ---------------- Sign in ----------------

    fun startLogin(clientId: String, clientSecret: String) {
        loginJob?.cancel()
        loginJob = scope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val base = settings.current().trakt.copy(clientId = clientId.trim(), clientSecret = clientSecret.trim())
            settings.saveTrakt(base)
            try {
                val (code, res) = call("POST", "/oauth/device/code", buildJsonObject { put("client_id", base.clientId) }, base, token = false)
                val o = res.o() ?: throw IOException(if (code == 403 || code == 401) "Trakt rejected the Client ID" else "Trakt error ($code)")
                val deviceCode = o.s("device_code") ?: throw IOException("Trakt error")
                val interval = (o.s("interval")?.toLongOrNull() ?: 5) * 1000
                val expires = System.currentTimeMillis() + (o.s("expires_in")?.toLongOrNull() ?: 600) * 1000
                _state.update { it.copy(busy = false, login = DeviceLogin(o.s("user_code") ?: "", o.s("verification_url") ?: "https://trakt.tv/activate", expires)) }
                while (System.currentTimeMillis() < expires) {
                    delay(interval)
                    val (c, r) = call("POST", "/oauth/device/token", buildJsonObject {
                        put("code", deviceCode); put("client_id", base.clientId); put("client_secret", base.clientSecret)
                    }, base, token = false)
                    when (c) {
                        200 -> {
                            val t = r.o() ?: break
                            var auth = base.copy(accessToken = t.s("access_token") ?: "", refreshToken = t.s("refresh_token") ?: "",
                                expiresAt = System.currentTimeMillis() + (t.s("expires_in")?.toLongOrNull() ?: 0) * 1000)
                            val (_, me) = call("GET", "/users/settings", null, auth)
                            auth = auth.copy(username = me.o()?.get("user").o()?.s("username") ?: "")
                            settings.saveTrakt(auth)
                            _state.update { it.copy(connected = true, username = auth.username, login = null, error = null) }
                            sync(force = true)
                            return@launch
                        }
                        400 -> Unit // still waiting for the user
                        429 -> delay(interval)
                        418 -> throw IOException("You declined the request on Trakt")
                        404, 409, 410 -> throw IOException("The code expired or was already used. Try again.")
                        else -> throw IOException("Trakt error ($c)")
                    }
                }
                throw IOException("The code expired. Try again.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, login = null, error = e.message) }
            }
        }
    }

    fun cancelLogin() { loginJob?.cancel(); _state.update { it.copy(login = null, busy = false) } }

    fun disconnect() = scope.launch {
        val t = settings.current().trakt
        runCatching { call("POST", "/oauth/revoke", buildJsonObject { put("token", t.accessToken); put("client_id", t.clientId); put("client_secret", t.clientSecret) }, t) }
        disconnectLocal(null)
    }

    private suspend fun disconnectLocal(msg: String?) {
        val t = settings.current().trakt
        settings.saveTrakt(TraktAuth(clientId = t.clientId, clientSecret = t.clientSecret))
        _state.value = TraktState(error = msg)
    }

    // ---------------- Sync ----------------

    fun syncIfStale() = scope.launch { if (System.currentTimeMillis() - _state.value.lastSync > 30 * 60_000) sync(false) }

    suspend fun sync(force: Boolean) = withContext(Dispatchers.IO) {
        val a = auth() ?: return@withContext
        if (!force && System.currentTimeMillis() - _state.value.lastSync < 60_000) return@withContext
        runCatching {
            val movies = HashSet<String>()
            (call("GET", "/sync/watched/movies", null, a).second as? JsonArray)?.forEach { e ->
                e.o()?.get("movie").o()?.let { m -> movies += keys(m) }
            }
            val shows = HashSet<String>()
            val episodes = HashMap<String, MutableSet<String>>()
            (call("GET", "/sync/watched/shows", null, a).second as? JsonArray)?.forEach { e ->
                val show = e.o()?.get("show").o() ?: return@forEach
                shows += keys(show)
                val set = HashSet<String>()
                (e.o()?.get("seasons") as? JsonArray)?.forEach { s ->
                    val sn = s.o()?.s("number") ?: return@forEach
                    (s.o()?.get("episodes") as? JsonArray)?.forEach { ep -> ep.o()?.s("number")?.let { set += "$sn:$it" } }
                }
                keys(show).forEach { k -> episodes.getOrPut(k) { HashSet() } += set }
            }
            val list = ArrayList<TraktListItem>()
            (call("GET", "/sync/watchlist?sort_by=added&sort_how=desc", null, a).second as? JsonArray)?.forEach { e ->
                val o = e.o() ?: return@forEach
                val isMovie = o.s("type") == "movie"
                val item = o[if (isMovie) "movie" else "show"].o() ?: return@forEach
                list += TraktListItem(isMovie, item.s("title") ?: return@forEach, item.s("year")?.toIntOrNull(), item["ids"].o()?.s("tmdb"))
            }
            _state.update { it.copy(watchedMovies = movies, watchedShows = shows, watchedEpisodes = episodes, watchlist = list, lastSync = System.currentTimeMillis(), error = null) }
        }.onFailure { e -> _state.update { it.copy(error = "Trakt sync failed: ${e.message}") } }
    }

    private fun keys(m: JsonObject): Set<String> = buildSet {
        m["ids"].o()?.s("tmdb")?.let { add("tmdb:$it") }
        m.s("title")?.let { add(titleKey(it, m.s("year")?.toIntOrNull())) }
    }

    /** Provider titles often carry junk ("4K: Name (2026)"), so compare on a cleaned title + year. */
    fun titleKey(title: String, year: Int?): String {
        val t = Text.normalize(title.substringAfter(": ", title).replace(Regex("""\(\d{4}\)|\[.*?]"""), ""))
            .split(' ').filter { it !in setOf("4k", "uhd", "hd", "fhd", "multi", "sub", "dub", "vostfr", "the") }.joinToString(" ")
        return "t:$t|${year ?: ""}"
    }

    fun isWatched(v: VodEntity): Boolean {
        val s = _state.value
        if (!s.connected) return false
        val set = if (v.kind == VodKind.MOVIE) s.watchedMovies else s.watchedShows
        return (v.tmdb != null && "tmdb:${v.tmdb}" in set) || titleKey(v.name, v.year) in set
    }

    fun watchedEpisodes(series: VodEntity): Set<String> {
        val s = _state.value
        return (series.tmdb?.let { s.watchedEpisodes["tmdb:$it"] } ?: s.watchedEpisodes[titleKey(series.name, series.year)]).orEmpty()
    }

    /** Watchlist titles that exist in the user's provider library. */
    suspend fun watchlistInLibrary(): List<VodEntity> {
        val list = _state.value.watchlist
        if (list.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, VodEntity>()
        for (kind in listOf(VodKind.MOVIE, VodKind.SERIES)) {
            val wanted = list.filter { it.isMovie == (kind == VodKind.MOVIE) }
            if (wanted.isEmpty()) continue
            db.vod().byTmdb(kind, wanted.mapNotNull { it.tmdb }).forEach { out[it.key] = it }
            val keys = wanted.associateBy { titleKey(it.title, it.year) }
            val keysNoYear = wanted.associateBy { titleKey(it.title, null) }
            for (t in db.vod().titles(kind)) {
                if (out.containsKey(t.key)) continue
                if (titleKey(t.name, t.year) in keys || (t.year == null && titleKey(t.name, null) in keysNoYear)) {
                    db.vod().byKey(t.key)?.let { out[t.key] = it }
                }
            }
        }
        return out.values.toList()
    }

    fun inWatchlist(v: VodEntity): Boolean {
        val k = titleKey(v.name, v.year)
        return _state.value.watchlist.any { it.isMovie == (v.kind == VodKind.MOVIE) && ((v.tmdb != null && it.tmdb == v.tmdb) || titleKey(it.title, it.year) == k) }
    }

    fun setWatchlist(v: VodEntity, add: Boolean) = scope.launch {
        val a = auth() ?: return@launch
        val item = itemJson(v.kind == VodKind.MOVIE, v.name, v.year, v.tmdb)
        val body = buildJsonObject { put(if (v.kind == VodKind.MOVIE) "movies" else "shows", JsonArray(listOf(item))) }
        call("POST", if (add) "/sync/watchlist" else "/sync/watchlist/remove", body, a)
        sync(force = true)
    }

    private fun itemJson(isMovie: Boolean, title: String, year: Int?, tmdb: String?): JsonObject = buildJsonObject {
        if (tmdb != null) putJsonObject("ids") { put("tmdb", tmdb.toIntOrNull() ?: 0) }
        else {
            put("title", title.substringAfter(": ", title).replace(Regex("""\(\d{4}\)"""), "").trim())
            year?.let { put("year", it) }
        }
    }

    // ---------------- Scrobble ----------------

    /** action: "start", "pause" or "stop". progress 0..100. Trakt marks it watched when stopped above 80%. */
    fun scrobble(action: String, item: TraktItem, progress: Float) = scope.launch {
        val a = auth() ?: return@launch
        val body = buildJsonObject {
            when (item) {
                is TraktItem.Movie -> put("movie", itemJson(true, item.title, item.year, item.tmdb))
                is TraktItem.Episode -> {
                    put("show", itemJson(false, item.showTitle, item.showYear, item.showTmdb))
                    putJsonObject("episode") { put("season", item.season); put("number", item.number) }
                }
            }
            put("progress", progress.coerceIn(0f, 100f).toDouble())
        }
        runCatching { call("POST", "/scrobble/$action", body, a) }
        if (action == "stop" && progress >= 80f) sync(force = true)
    }
}
