package com.arena.tv.data

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder
import java.security.MessageDigest

data class VodDetails(
    val plot: String?,
    val cast: String?,
    val director: String?,
    val genre: String?,
    val year: Int?,
    val durationSecs: Int?,
    val rating: Double?,
    val backdrop: String?,
    val poster: String?,
    /** YouTube video id */
    val trailer: String?,
    val tmdb: String?,
)

/** Movies & series: import from Xtream (streamed, catalogs can have 100k+ titles) or M3U; details on demand. */
class VodRepository(private val http: OkHttpClient, private val db: ArenaDb, private val settings: SettingsStore) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val detailsCache = HashMap<String, VodDetails>()

    private fun String.enc() = URLEncoder.encode(this, "UTF-8")
    private suspend fun playlistOf(v: VodEntity): Playlist? = settings.current().playlist(v.playlistId)

    private fun request(url: String, ua: String?) = Request.Builder().url(url).apply {
        if (!ua.isNullOrBlank()) header("User-Agent", ua)
    }.build()

    // ---------------- Import ----------------

    /** Xtream: categories + full movie and series lists. */
    /** Removes a playlist's movies or series (the user switched them off). */
    fun clearKind(pid: String, kind: Int) = db.runInTransaction { db.vod().clearKindBlocking(pid, kind); db.vod().clearCategoriesBlocking(pid, kind) }

    /** Imports one kind (movies or series) from Xtream. @return how many titles */
    fun importXtream(pl: Playlist, kind: Int, progress: (String) -> Unit): Int = importXtreamKinds(pl, setOf(kind), progress).let { if (kind == VodKind.MOVIE) it.first else it.second }

    /** @return movie count to series count */
    private fun importXtreamKinds(pl: Playlist, kinds: Set<Int>, progress: (String) -> Unit): Pair<Int, Int> {
        val ua = pl.ua
        val api = pl.apiUrl
        val pid = pl.id
        val counts = IntArray(2)
        for ((kind, catAction, listAction, label) in listOf(
            Quad(VodKind.MOVIE, "get_vod_categories", "get_vod_streams", "movies"),
            Quad(VodKind.SERIES, "get_series_categories", "get_series", "series"),
        )) {
            if (kind !in kinds) continue
            progress("Loading $label…")
            val cats = runCatching { getJson("$api&action=$catAction", ua) as? JsonArray }.getOrNull().orEmpty()
            val catEntities = cats.mapIndexedNotNull { i, c ->
                val o = c as? JsonObject ?: return@mapIndexedNotNull null
                val id = o.s("category_id") ?: return@mapIndexedNotNull null
                VodCategoryEntity("$pid:${prefix(kind)}c:$id", kind, "$pid:$id", o.s("category_name") ?: "Other", i, pid)
            }
            val dao = db.vod()
            db.runInTransaction {
                dao.clearKindBlocking(pid, kind); dao.clearCategoriesBlocking(pid, kind)
                dao.insertCategoriesBlocking(catEntities)
            }
            var n = 0
            val batch = ArrayList<VodEntity>(1000)
            http.newCall(request("$api&action=$listAction", ua)).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Provider answered HTTP ${r.code} for $label")
                JsonReader(InputStreamReader(r.body!!.byteStream(), Charsets.UTF_8)).use { reader ->
                    reader.isLenient = true
                    if (reader.peek() != JsonToken.BEGIN_ARRAY) return@use
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val m = readObject(reader)
                        toEntity(pid, kind, m, n)?.let { batch += it; n++ }
                        if (batch.size >= 1000) {
                            dao.insertBlocking(batch); batch.clear()
                            progress("Loading $label… ${n / 1000}k")
                        }
                    }
                    reader.endArray()
                }
            }
            if (batch.isNotEmpty()) dao.insertBlocking(batch)
            counts[kind] = n
        }
        return counts[0] to counts[1]
    }

    private data class Quad(val a: Int, val b: String, val c: String, val d: String)

    private fun prefix(kind: Int) = if (kind == VodKind.MOVIE) "m" else "s"

    /** Reads one flat JSON object; nested values become strings (arrays keep their first string). */
    private fun readObject(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>(24)
        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); return m }
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            when (r.peek()) {
                JsonToken.STRING -> m[name] = r.nextString()
                JsonToken.NUMBER -> m[name] = r.nextString()
                JsonToken.BOOLEAN -> m[name] = r.nextBoolean().toString()
                JsonToken.NULL -> r.nextNull()
                JsonToken.BEGIN_ARRAY -> {
                    r.beginArray()
                    var first: String? = null
                    while (r.hasNext()) {
                        if (first == null && r.peek() == JsonToken.STRING) first = r.nextString() else r.skipValue()
                    }
                    r.endArray()
                    first?.let { m[name] = it }
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    private val yearInName = Regex("""[(\[\s-](19\d{2}|20\d{2})[)\]]?\s*$""")

    private fun toEntity(pid: String, kind: Int, m: Map<String, String>, position: Int): VodEntity? {
        val id = (if (kind == VodKind.MOVIE) m["stream_id"] else m["series_id"]) ?: return null
        val name = m["name"]?.trim()?.ifEmpty { null } ?: return null
        val rating = m["rating"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: m["rating_5based"]?.toDoubleOrNull()?.takeIf { it > 0 }?.times(2)
        val year = m["year"]?.take(4)?.toIntOrNull()
            ?: (m["releaseDate"] ?: m["releasedate"] ?: m["release_date"])?.take(4)?.toIntOrNull()
            ?: yearInName.find(name)?.groupValues?.get(1)?.toIntOrNull()
        val added = (m["added"] ?: m["last_modified"])?.toLongOrNull()?.times(1000) ?: 0L
        return VodEntity(
            key = "$pid:${prefix(kind)}:$id", playlistId = pid, kind = kind, providerId = id, position = position, name = name,
            poster = (m["stream_icon"] ?: m["cover"])?.takeIf { it.startsWith("http") },
            categoryId = "$pid:${m["category_id"] ?: "0"}", rating = rating, year = year?.takeIf { it in 1900..2100 }, added = added,
            ext = m["container_extension"], url = null,
            tmdb = (m["tmdb"] ?: m["tmdb_id"])?.takeIf { it.isNotBlank() && it != "0" },
            plot = m["plot"]?.ifBlank { null }, genre = m["genre"]?.ifBlank { null },
            backdrop = m["backdrop_path"]?.takeIf { it.startsWith("http") },
        )
    }

    /** M3U: movies are listed directly; series are grouped from "Name S01E02" entries. */
    fun importM3u(pid: String, movies: List<ParsedChannel>, episodes: List<ParsedChannel>): Pair<Int, Int> {
        val dao = db.vod()
        val md = MessageDigest.getInstance("SHA-1")
        fun hash(s: String) = md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(14)
        val movieCats = movies.map { it.group }.distinct()
        val movieCatIds = movieCats.withIndex().associate { (i, g) -> g to "$pid:$i" }
        val movieEntities = movies.mapIndexed { i, c ->
            val year = yearInName.find(c.name)?.groupValues?.get(1)?.toIntOrNull()
            VodEntity("$pid:m:${hash(c.url)}", pid, true, VodKind.MOVIE, hash(c.url), i, c.name, c.logo, movieCatIds[c.group]!!, null, year,
                0L, c.url.substringAfterLast('.', "").take(4).ifBlank { null }, c.url, null, null, null, null)
        }
        // Series
        data class Ep(val series: String, val season: Int, val number: Int, val c: ParsedChannel)
        val eps = episodes.mapNotNull { c ->
            val m = M3uParser.episodeRegex.find(c.name) ?: return@mapNotNull null
            Ep(m.groupValues[1].trim().trimEnd('-', '|', ':').trim().ifEmpty { c.name }, m.groupValues[2].toInt(), m.groupValues[3].toInt(), c)
        }
        val seriesCats = eps.map { it.c.group }.distinct()
        val seriesCatIds = seriesCats.withIndex().associate { (i, g) -> g to "$pid:$i" }
        val grouped = eps.groupBy { it.series + "|" + it.c.group }
        val seriesEntities = ArrayList<VodEntity>()
        val episodeEntities = ArrayList<EpisodeEntity>()
        grouped.entries.forEachIndexed { i, (k, list) ->
            val key = "$pid:s:${hash(k)}"
            val first = list.first()
            seriesEntities += VodEntity(key, pid, true, VodKind.SERIES, hash(k), i, first.series, first.c.logo, seriesCatIds[first.c.group]!!,
                null, yearInName.find(first.series)?.groupValues?.get(1)?.toIntOrNull(), 0L, null, null, null, null, null, null)
            list.forEach { e ->
                episodeEntities += EpisodeEntity("$pid:e:${hash(e.c.url)}", key, e.season, e.number,
                    e.c.name.removePrefix(e.series).trim(' ', '-', '|'), e.c.url, null, e.c.logo, null)
            }
        }
        db.runInTransaction {
            dao.clearKindBlocking(pid, VodKind.MOVIE); dao.clearCategoriesBlocking(pid, VodKind.MOVIE)
            dao.clearKindBlocking(pid, VodKind.SERIES); dao.clearCategoriesBlocking(pid, VodKind.SERIES)
            dao.insertCategoriesBlocking(movieCats.mapIndexed { i, g -> VodCategoryEntity("$pid:mc:$i", VodKind.MOVIE, "$pid:$i", g, i, pid) })
            dao.insertCategoriesBlocking(seriesCats.mapIndexed { i, g -> VodCategoryEntity("$pid:sc:$i", VodKind.SERIES, "$pid:$i", g, i, pid) })
            movieEntities.chunked(1000).forEach(dao::insertBlocking)
            seriesEntities.chunked(1000).forEach(dao::insertBlocking)
            episodeEntities.chunked(1000).forEach(dao::insertEpisodesBlocking)
        }
        return movieEntities.size to seriesEntities.size
    }

    // ---------------- Playback URLs ----------------

    suspend fun movieUrl(v: VodEntity): String {
        v.url?.let { return it }
        val p = playlistOf(v) ?: throw IOException("This title's playlist was removed")
        return "${p.base}/movie/${p.user.enc()}/${p.pass.enc()}/${v.providerId}.${v.ext ?: "mp4"}"
    }

    suspend fun userAgent(v: VodEntity): String? = playlistOf(v)?.ua

    // ---------------- Details ----------------

    private fun getJson(url: String, ua: String?): JsonElement {
        http.newCall(request(url, ua)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return json.parseToJsonElement(r.body!!.string())
        }
    }

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonObject.firstString(k: String): String? = when (val v = this[k]) {
        is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        else -> null
    }

    private fun trailerId(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return Regex("""(?:v=|youtu\.be/|embed/)([\w-]{11})""").find(raw)?.groupValues?.get(1) ?: raw.takeIf { it.length == 11 }
    }

    private fun infoToDetails(info: JsonObject, fallback: VodEntity): VodDetails {
        val dur = info.s("duration_secs")?.toIntOrNull() ?: info.s("episode_run_time")?.toIntOrNull()?.times(60)
            ?: info.s("duration")?.split(':')?.let { p -> if (p.size == 3) (p[0].toIntOrNull() ?: 0) * 3600 + (p[1].toIntOrNull() ?: 0) * 60 else null }
        return VodDetails(
            plot = info.s("plot") ?: info.s("description") ?: fallback.plot,
            cast = info.s("cast") ?: info.s("actors"),
            director = info.s("director"),
            genre = info.s("genre") ?: fallback.genre,
            year = (info.s("releasedate") ?: info.s("releaseDate") ?: info.s("release_date"))?.take(4)?.toIntOrNull() ?: fallback.year,
            durationSecs = dur,
            rating = info.s("rating")?.toDoubleOrNull()?.takeIf { it > 0 } ?: fallback.rating,
            backdrop = info.firstString("backdrop_path")?.takeIf { it.startsWith("http") } ?: fallback.backdrop,
            poster = info.s("cover_big") ?: info.s("movie_image") ?: info.s("cover") ?: fallback.poster,
            trailer = trailerId(info.s("youtube_trailer") ?: info.s("trailer")),
            tmdb = info.s("tmdb_id") ?: info.s("tmdb") ?: fallback.tmdb,
        )
    }

    /** Movie or series details (Xtream). M3U entries only have what the playlist carried. */
    suspend fun details(v: VodEntity): VodDetails = withContext(Dispatchers.IO) {
        detailsCache[v.key]?.let { return@withContext it }
        val s = playlistOf(v)
        val fallback = VodDetails(v.plot, null, null, v.genre, v.year, null, v.rating, v.backdrop, v.poster, null, v.tmdb)
        if (s == null || s.type != SourceType.XTREAM || v.url != null) return@withContext fallback
        val d = runCatching {
            val action = if (v.kind == VodKind.MOVIE) "get_vod_info&vod_id=${v.providerId}" else "get_series_info&series_id=${v.providerId}"
            val root = getJson("${s.apiUrl}&action=$action", s.ua).obj() ?: return@runCatching fallback
            val info = root["info"].obj() ?: return@runCatching fallback
            val d = infoToDetails(info, v)
            if (v.kind == VodKind.SERIES) saveEpisodes(v, root, s)
            db.vod().enrich(v.key, d.plot, d.genre, d.backdrop, d.tmdb, d.year, d.rating)
            d
        }.getOrElse { fallback }
        detailsCache[v.key] = d
        d
    }

    private suspend fun saveEpisodes(series: VodEntity, root: JsonObject, s: Playlist) {
        val base = s.base
        val out = ArrayList<EpisodeEntity>()
        fun add(e: JsonElement, seasonHint: Int?) {
            val o = e.obj() ?: return
            val id = o.s("id") ?: return
            val info = o["info"].obj()
            val season = o.s("season")?.toIntOrNull() ?: seasonHint ?: 1
            out += EpisodeEntity(
                key = "${series.playlistId}:e:$id", seriesKey = series.key, season = season,
                number = o.s("episode_num")?.toIntOrNull() ?: (out.count { it.season == season } + 1),
                title = o.s("title") ?: "Episode",
                url = "$base/series/${s.user.enc()}/${s.pass.enc()}/$id.${o.s("container_extension") ?: "mp4"}",
                plot = info?.s("plot"), image = info?.s("movie_image")?.takeIf { it.startsWith("http") },
                durationSecs = info?.s("duration_secs")?.toIntOrNull(),
            )
        }
        when (val eps = root["episodes"]) {
            is JsonObject -> eps.forEach { (k, list) -> (list as? JsonArray)?.forEach { add(it, k.toIntOrNull()) } }
            is JsonArray -> eps.forEach { list -> (list as? JsonArray)?.forEach { add(it, null) } ?: add(list, null) }
            else -> Unit
        }
        if (out.isNotEmpty()) db.vod().replaceEpisodes(series.key, out)
    }

    suspend fun episodes(series: VodEntity): List<EpisodeEntity> {
        // Refresh from the provider once per app run (new episodes), otherwise use what's stored.
        if (!detailsCache.containsKey(series.key)) details(series)
        return db.vod().episodes(series.key)
    }
}
