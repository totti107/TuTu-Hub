package com.arena.tv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.text.DateFormat
import java.util.Date

/** Xtream Codes API: logs in and lists live channels with their categories. */
class XtreamClient(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        /** "host:8080" / "http://host:8080/" / "http://host/get.php?..." -> "http://host:8080" */
        fun base(server: String): String {
            var s = server.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            val schemeEnd = s.indexOf("://") + 3
            val pathStart = s.indexOf('/', schemeEnd)
            return if (pathStart > 0) s.substring(0, pathStart) else s
        }
    }

    private fun get(url: String, ua: String?): JsonElement {
        val b = Request.Builder().url(url)
        if (!ua.isNullOrBlank()) b.header("User-Agent", ua)
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server answered HTTP ${r.code}")
            val body = r.body?.string() ?: throw IOException("Empty response")
            return runCatching { json.parseToJsonElement(body) }.getOrElse { throw IOException("Server didn't return Xtream data. Check the URL.") }
        }
    }

    private val JsonElement?.s: String?
        get() = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    /** Returns channels + a one-line account summary. Throws with a readable message on bad login. */
    fun load(server: String, user: String, pass: String, format: String, ua: String?): Pair<List<ParsedChannel>, String> {
        val b = base(server)
        val q = "username=${URLEncoder.encode(user, "UTF-8")}&password=${URLEncoder.encode(pass, "UTF-8")}"
        val api = "$b/player_api.php?$q"
        val login = get(api, ua) as? JsonObject ?: throw IOException("Unexpected login response")
        val info = login["user_info"] as? JsonObject
        if (info == null || info["auth"].s == "0") throw IOException("Login failed: wrong username or password")
        val status = info["status"].s ?: "?"
        val exp = info["exp_date"].s?.toLongOrNull()?.let { DateFormat.getDateInstance().format(Date(it * 1000)) }
        val account = buildString {
            append("Status: $status")
            if (exp != null) append(" · Expires $exp")
            info["max_connections"].s?.let { append(" · $it connection(s)") }
        }
        val catObjs = (get("$api&action=get_live_categories", ua) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val cats = catObjs.mapNotNull { c -> (c["category_id"].s ?: return@mapNotNull null) to (c["category_name"].s ?: "Other") }.toMap()
        val adultCats = catObjs.filter { it["is_adult"].s == "1" || Adult.isAdult(it["category_name"].s) }.mapNotNull { it["category_id"].s }.toSet()
        val streams = (get("$api&action=get_live_streams", ua) as? JsonArray).orEmpty().toMutableList()
        // Many panels leave some categories (usually adult ones) out of the full list: ask for those one by one.
        val present = streams.mapNotNull { (it as? JsonObject)?.get("category_id")?.s }.toSet()
        cats.keys.filter { it !in present }.take(60).forEach { id ->
            runCatching { (get("$api&action=get_live_streams&category_id=$id", ua) as? JsonArray).orEmpty() }.getOrNull()?.let { streams += it }
        }
        val ext = if (format == "m3u8") "m3u8" else "ts" // "auto" starts with TS; the player falls back to HLS
        val user64 = URLEncoder.encode(user, "UTF-8")
        val pass64 = URLEncoder.encode(pass, "UTF-8")
        // Keep the provider's order: categories as get_live_categories lists them, channels by their number inside each.
        val catOrder = cats.keys.withIndex().associate { (i, k) -> k to i }
        val ordered = streams.mapNotNull { it as? JsonObject }.withIndex().sortedWith(compareBy(
            { (_, o) -> catOrder[o["category_id"].s] ?: Int.MAX_VALUE },
            { (_, o) -> o["num"].s?.toDoubleOrNull() ?: Double.MAX_VALUE },
            { (i, _) -> i },
        )).map { it.value }
        val channels = ordered.mapNotNull { o ->
            val id = o["stream_id"].s ?: return@mapNotNull null
            ParsedChannel(
                name = o["name"].s?.trim() ?: "Channel $id",
                url = "$b/live/$user64/$pass64/$id.$ext",
                group = cats[o["category_id"].s] ?: "Other",
                logo = o["stream_icon"].s?.takeIf { it.startsWith("http") },
                tvgId = o["epg_channel_id"].s,
                tvgName = o["name"].s,
                userAgent = ua,
                referrer = null,
                adult = o["is_adult"].s == "1" || o["category_id"].s in adultCats,
            )
        }
        return channels to account
    }
}
