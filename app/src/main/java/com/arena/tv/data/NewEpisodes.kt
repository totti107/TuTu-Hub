package com.arena.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/** One new episode airing or released today. [network] is a TV channel (Bravo, HBO…), [streaming] a service (Netflix, Apple TV+…). */
data class NewEpisode(
    val showId: Long, val show: String, val title: String?, val season: Int?, val number: Int?, val airstamp: Long?,
    val network: String?, val streaming: String?, val country: String?, val image: String?, val type: String?, val genres: List<String>,
    val popularity: Int,
) {
    val code: String get() = listOfNotNull(season?.let { "S$it" }, number?.let { "E$it" }).joinToString(" ")
    val premiere: Boolean get() = number == 1
}

/**
 * Today's new episodes from TVmaze (free, no key): TV networks for the chosen countries plus streaming services.
 * News, talk shows, sports and game shows are left out; the rest is sorted by how popular the show is.
 */
class NewEpisodes(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var cache: Pair<String, List<NewEpisode>>? = null
    private val skipTypes = setOf("news", "talk show", "sports", "game show", "panel show", "award show")

    suspend fun today(countries: List<String>): List<NewEpisode> = withContext(Dispatchers.IO) {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val key = date + countries.joinToString()
        cache?.takeIf { it.first == key }?.let { return@withContext it.second }
        val list = coroutineScope {
            val tv = countries.map { c -> async { runCatching { parse(get("https://api.tvmaze.com/schedule?country=$c&date=$date"), embedded = false) }.getOrDefault(emptyList()) } }
            val web = async { runCatching { parse(get("https://api.tvmaze.com/schedule/web?date=$date"), embedded = true) }.getOrDefault(emptyList()) }
            (tv + web).awaitAll().flatten()
        }
        val result = list.filter { (it.type?.lowercase() ?: "") !in skipTypes }
            // Streaming services list each episode of a season drop separately: keep one card per show.
            .groupBy { it.showId }.values.map { eps -> eps.minWith(compareBy({ it.season ?: 0 }, { it.number ?: 0 })).let { first ->
                if (eps.size > 1) first.copy(title = "${eps.size} new episodes") else first } }
            .sortedByDescending { it.popularity }
        if (result.isNotEmpty()) cache = key to result
        result
    }

    private fun get(url: String): JsonElement? {
        http.newCall(Request.Builder().url(url).header("User-Agent", "TuTuTV").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            return json.parseToJsonElement(r.body?.string() ?: return null)
        }
    }

    /** TVmaze schedule JSON → episodes. The web schedule nests the show under _embedded. */
    fun parse(root: JsonElement?, embedded: Boolean): List<NewEpisode> = (root as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        if (o.str("type") == "insignificant_special") return@mapNotNull null
        val show = (if (embedded) (o["_embedded"] as? JsonObject)?.get("show") else o["show"]) as? JsonObject ?: return@mapNotNull null
        val net = show["network"] as? JsonObject
        val web = show["webChannel"] as? JsonObject
        NewEpisode(
            showId = show.str("id")?.toLongOrNull() ?: return@mapNotNull null,
            show = show.str("name") ?: return@mapNotNull null,
            title = o.str("name"),
            season = o.str("season")?.toIntOrNull(), number = o.str("number")?.toIntOrNull(),
            airstamp = o.str("airstamp")?.let { runCatching { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US).parse(it)?.time }.getOrNull() },
            network = net?.str("name"), streaming = if (net == null) web?.str("name") else null,
            country = ((net?.get("country") ?: web?.get("country")) as? JsonObject)?.str("code"),
            image = (show["image"] as? JsonObject)?.let { it.str("medium") ?: it.str("original") },
            type = show.str("type"),
            genres = (show["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            popularity = show.str("weight")?.toIntOrNull() ?: 0,
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
}
