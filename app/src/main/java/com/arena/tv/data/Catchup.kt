package com.arena.tv.data

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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A past programme that may be watched again. [ref] is the provider's own id when it needs one (MAC portals). */
data class CatchupItem(val title: String, val start: Long, val stop: Long, val description: String?, val ref: String? = null)

/**
 * Catch-up (TV archive): lists what aired on a channel in the last days and builds a link to watch it.
 * Xtream: /timeshift/ links in the server's time zone. MAC portals: their archive list + create_link.
 * M3U: the playlist's catchup / catchup-source attributes (default, append, shift, flussonic, xc).
 */
class Catchup(private val http: OkHttpClient, private val db: ArenaDb, private val settings: SettingsStore, private val stalker: StalkerClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val xtreamLive = Regex("""^(.*?)/live/([^/]+)/([^/]+)/(\d+)\.(ts|m3u8)""")

    /** Programmes that finished between [from] and [to] (and are inside the channel's archive window), newest last. */
    suspend fun programs(ch: ChannelEntity, from: Long, to: Long): List<CatchupItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val oldest = now - ch.catchupDays * 86_400_000L
        val lo = maxOf(from, oldest)
        val hi = minOf(to, now)
        if (hi <= lo) return@withContext emptyList()
        val fromProvider = runCatching {
            when {
                ch.catchup.startsWith("stk") -> stalkerPrograms(ch, lo, hi)
                ch.catchup.startsWith("xc|") -> xtreamPrograms(ch)
                else -> null
            }
        }.getOrNull()
        val list = fromProvider?.takeIf { it.isNotEmpty() } ?: guidePrograms(ch, lo, hi)
        list.filter { it.stop <= now + 60_000 && it.start >= oldest - 3_600_000 && it.stop > lo && it.start < hi }
            .distinctBy { it.start }.sortedBy { it.start }
    }

    private suspend fun guidePrograms(ch: ChannelEntity, from: Long, to: Long): List<CatchupItem> {
        val key = ch.epgKey ?: return emptyList()
        return db.programs().schedule(key, from, to).map { CatchupItem(it.title, it.start, it.stop, it.description) }
    }

    private suspend fun stalkerPrograms(ch: ChannelEntity, from: Long, to: Long): List<CatchupItem> {
        val p = settings.current().playlist(ch.playlistId) ?: return emptyList()
        val chId = ch.url.removePrefix(StalkerClient.SCHEME).substringBefore('|')
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val days = LinkedHashSet<String>()
        val c = Calendar.getInstance().apply { timeInMillis = from }
        while (c.timeInMillis <= to) { days += fmt.format(c.time); c.add(Calendar.DAY_OF_YEAR, 1) }
        days += fmt.format(to)
        return days.flatMap { d -> runCatching { stalker.archiveDay(p, chId, d) }.getOrDefault(emptyList()) }
            .filter { it.archived }
            .map { CatchupItem(it.title, it.start, it.stop, it.description, it.id) }
    }

    private fun xtreamPrograms(ch: ChannelEntity): List<CatchupItem> {
        val m = xtreamLive.find(ch.url) ?: return emptyList()
        val (base, user, pass, id) = m.destructured
        val body = getJson("$base/player_api.php?username=$user&password=$pass&action=get_simple_data_table&stream_id=$id", ch.userAgent)
        val list = ((body as? JsonObject)?.get("epg_listings") as? JsonArray).orEmpty()
        return list.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val start = o["start_timestamp"].str()?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            val stop = o["stop_timestamp"].str()?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
            CatchupItem(b64(o["title"].str()) ?: "Programme", start, stop, b64(o["description"].str())?.take(400))
        }
    }

    /** A link that plays [item] from its start. */
    suspend fun url(ch: ChannelEntity, item: CatchupItem): String = withContext(Dispatchers.IO) {
        val c = ch.catchup
        when {
            c.startsWith("stk") -> {
                val p = settings.current().playlist(ch.playlistId) ?: throw IOException("This channel's playlist was removed")
                val ref = item.ref ?: stalkerPrograms(ch, item.start - 60_000, item.stop).minByOrNull { kotlin.math.abs(it.start - item.start) }?.ref
                    ?: throw IOException("The portal has no recording of this programme")
                stalker.archiveLink(p, ref)
            }
            c.startsWith("xc|") -> xtreamUrl(ch.url, c.substringAfter('|'), item) ?: throw IOException("Catch-up isn't available for this channel")
            c.startsWith("m3u|") -> m3uUrl(ch.url, c, item)
            else -> throw IOException("This channel has no catch-up")
        }
    }

    private fun xtreamUrl(liveUrl: String, tz: String, item: CatchupItem): String? {
        val m = xtreamLive.find(liveUrl) ?: return null
        val (base, user, pass, id) = m.destructured
        val fmt = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = TimeZone.getTimeZone(tz) }
        val minutes = ((item.stop - item.start + 59_999) / 60_000).coerceAtLeast(1)
        return "$base/timeshift/$user/$pass/$minutes/${fmt.format(item.start)}/$id.ts"
    }

    private fun m3uUrl(url: String, spec: String, item: CatchupItem): String {
        val parts = spec.split('|', limit = 3)
        val mode = parts.getOrElse(1) { "shift" }
        val source = parts.getOrElse(2) { "" }
        val now = System.currentTimeMillis() / 1000
        val s = item.start / 1000
        val e = item.stop / 1000
        val dur = (e - s).coerceAtLeast(60)
        fun fill(t: String): String {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = item.start }
            fun two(v: Int) = v.toString().padStart(2, '0')
            var r = Regex("""\{duration:(\d+)\}""").replace(t) { (dur / it.groupValues[1].toLong().coerceAtLeast(1)).toString() }
            mapOf(
                "{utc}" to s, "\${start}" to s, "{start}" to s, "{utcend}" to e, "\${end}" to e, "{end}" to e,
                "{lutc}" to now, "\${now}" to now, "{now}" to now, "\${timestamp}" to now, "{timestamp}" to now,
                "{duration}" to dur, "\${duration}" to dur, "{offset}" to (now - s), "\${offset}" to (now - s),
            ).forEach { (k, v) -> r = r.replace(k, v.toString()) }
            return r.replace("{Y}", utc.get(Calendar.YEAR).toString()).replace("{m}", two(utc.get(Calendar.MONTH) + 1))
                .replace("{d}", two(utc.get(Calendar.DAY_OF_MONTH))).replace("{H}", two(utc.get(Calendar.HOUR_OF_DAY)))
                .replace("{M}", two(utc.get(Calendar.MINUTE))).replace("{S}", two(utc.get(Calendar.SECOND)))
        }
        val sep = if (url.contains('?')) "&" else "?"
        return when (mode) {
            "append" -> url + fill(source)
            "default" -> if (source.isNotBlank()) fill(source) else "$url${sep}utc=$s&lutc=$now"
            "xc" -> xtreamUrl(url, "UTC", item) ?: "$url${sep}utc=$s&lutc=$now"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> {
                val dir = url.substringBefore('?').substringBeforeLast('/')
                val last = url.substringBefore('?').substringAfterLast('/')
                if (last.endsWith(".m3u8")) "$dir/${last.removeSuffix(".m3u8")}-$s-$dur.m3u8" else "$dir/timeshift_abs-$s.ts"
            }
            else -> "$url${sep}utc=$s&lutc=$now"
        }
    }

    private fun getJson(url: String, ua: String?): JsonElement? {
        val b = Request.Builder().url(url)
        if (!ua.isNullOrBlank()) b.header("User-Agent", ua)
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) return null
            return runCatching { json.parseToJsonElement(r.body?.string() ?: return null) }.getOrNull()
        }
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun b64(v: String?): String? = v?.let { runCatching { String(android.util.Base64.decode(it, android.util.Base64.DEFAULT)) }.getOrDefault(it) }?.trim()
}
