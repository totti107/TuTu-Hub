package com.arena.tv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * MAC-address login ("Stalker" / Ministra middleware used by MAG boxes).
 * The portal identifies the device by its MAC; streams need a fresh link from create_link each time they play.
 */
/** How the app introduces itself to a portal: a MAG model, its browser id and firmware. */
data class Box(val model: String, val ua: String, val image: String, val imageVersion: String, val hw: String)

class StalkerClient(private val http: OkHttpClient) {
    companion object {
        const val UA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
        const val SCHEME = "stalker:"

        /**
         * Box models the app can introduce itself as. Some portals only allow certain models or recent firmware
         * ("old firmware", "Your STB is not supported"), so login tries them in turn.
         */
        val BOXES = listOf(
            Box("MAG250", UA, "0.2.18-r23-250", "218", "1.7-BD-00"),
            Box("MAG254", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3", "0.2.18-r22-pub-254", "218", "2.6-IB-00"),
            Box("MAG322", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 1c4c56f Mobile Safari/533.3", "0.2.18-r24-322", "218", "1.4-AB-00"),
            Box("MAG324", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 1c4c56f Mobile Safari/533.3", "0.2.18-r24-324", "218", "1.2-BD-00"),
            Box("MAG424", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 1c4c56f Mobile Safari/533.3", "0.2.18-r24-424", "218", "1.0-BD-00"),
            Box("MAG351", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3", "0.2.18-r21-351", "218", "1.1-BD-00"),
            Box("MAG256", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3", "0.2.18-r22-pub-256", "218", "1.4-BB-00"),
        )

        /** "http://host:port/c/" or "host:port" -> "http://host:port" */
        fun base(portal: String): String {
            var s = portal.trim()
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            s = s.trimEnd('/')
            for (suffix in listOf("/c", "/stalker_portal/c", "/stalker_portal", "/portal.php", "/server/load.php")) if (s.endsWith(suffix)) s = s.removeSuffix(suffix)
            return s
        }

        fun normalizeMac(mac: String): String {
            val hex = mac.uppercase().filter { it.isDigit() || it in 'A'..'F' }
            return if (hex.length == 12) hex.chunked(2).joinToString(":") else mac.trim().uppercase()
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    /** endpoint = the portal's API address; rawCookie = MAC sent as "00:1A:.." (some portals) instead of "00%3A1A..". */
    private data class Session(val endpoint: String, val token: String, val at: Long, val rawCookie: Boolean, val box: Box = BOXES[0])
    /** The box model each portal accepted last time, tried first. */
    private val boxFor = ConcurrentHashMap<String, Box>()
    private val sessions = ConcurrentHashMap<String, Session>()

    private fun JsonElement?.o(k: String) = (this as? JsonObject)?.get(k)
    private fun JsonElement?.s(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
    private fun String.enc() = URLEncoder.encode(this, "UTF-8")

    /** The portal answered, but not with portal data (web page, error text...). */
    private class NotPortal(val snippet: String) : IOException("The portal answered with something else: \"$snippet\"")
    /** The portal sent us elsewhere instead of answering. */
    private class Moved(val location: String) : IOException("redirected to $location")

    /** Login requests don't follow redirects silently: portals redirect unknown requests to their web page. */
    private val noRedirect: OkHttpClient by lazy { http.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    private fun request(p: Playlist, url: String, token: String?, rawCookie: Boolean, client: OkHttpClient = http, box: Box = BOXES[0]): JsonElement {
        val mac = normalizeMac(p.mac)
        val tz = java.util.TimeZone.getDefault().id
        val cookie = if (rawCookie) "mac=$mac; stb_lang=en; timezone=$tz" else "mac=${mac.enc()}; stb_lang=en; timezone=${tz.enc()}"
        val b = Request.Builder().url(url)
            .header("User-Agent", p.ua ?: box.ua)
            .header("X-User-Agent", "Model: ${box.model}; Link: Ethernet")
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", cookie)
            .header("Referer", "${base(p.server)}/c/")
        if (token != null) b.header("Authorization", "Bearer $token")
        client.newCall(b.build()).execute().use { r ->
            if (r.isRedirect) r.header("Location")?.let { throw Moved(r.request.url.resolve(it)?.toString() ?: it) }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body?.string().orEmpty()
            // Some portals put a byte-order mark or junk before the JSON.
            val start = body.indexOfFirst { it == '{' || it == '[' }
            val clean = if (start > 0) body.substring(start) else body.trimStart('\uFEFF', ' ', '\n', '\r')
            return runCatching { json.parseToJsonElement(clean) }.getOrElse {
                throw NotPortal(body.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "empty reply" })
            }
        }
    }

    /** API addresses to try, the one the user typed first when it looks like one. */
    private fun endpoints(p: Playlist): List<String> {
        val typed = p.server.trim().let { if (it.startsWith("http")) it else "http://$it" }.substringBefore('?')
        val root = base(p.server)
        return buildList {
            if (typed.endsWith(".php")) add(typed)
            add("$root/portal.php")
            add("$root/stalker_portal/server/load.php")
            add("$root/server/load.php")
            add("$root/c/portal.php")
            add("$root/stalker_portal/portal.php")
        }.distinct()
    }

    /**
     * Reads the portal's own web page (the one a MAG box opens) and its script to find where its API really lives.
     * Portals that answer every unknown address with that page need this.
     */
    private fun discover(p: Playlist): List<String> {
        val root = base(p.server)
        val typed = p.server.trim().let { if (it.startsWith("http")) it else "http://$it" }.substringBefore('?')
        val pages = listOf(typed.trimEnd('/') + "/", "$root/c/", "$root/stalker_portal/c/").filter { !it.endsWith(".php/") }.distinct()
        val found = LinkedHashSet<String>()
        val phpRef = Regex("""["'(]((?:https?://[^"'\s)]+|[\w./-]*?)(?:portal|load)\.php)""")
        for (page in pages) {
            val (url, html) = fetchText(p, page) ?: continue
            if (!html.contains("<") && !html.contains("var ")) continue
            // A MAG box works out the API address from the page's own folder: /<path>/c/ -> /<path>/server/load.php
            val folder = url.toHttpUrlOrNull()?.let { u ->
                val path = u.encodedPath.substringBeforeLast("/c/", "").ifEmpty { u.encodedPath.substringBeforeLast('/') }
                "${u.scheme}://${u.host}:${u.port}$path"
            } ?: root
            found += "$folder/server/load.php"; found += "$folder/portal.php"
            val scripts = Regex("""src=["']([^"']+\.js[^"']*)["']""").findAll(html).map { it.groupValues[1] }
                .filter { "xpcom" in it || "common" in it || "portal" in it }.take(3).toList()
            val texts = listOf(url to html) + scripts.mapNotNull { src -> url.toHttpUrlOrNull()?.resolve(src)?.toString()?.let { fetchText(p, it) } }
            for ((at, text) in texts) for (m in phpRef.findAll(text)) {
                val ref = m.groupValues[1]
                when {
                    ref.startsWith("http") -> found += ref
                    ref.startsWith("/") -> found += root.substringBefore("://") + "://" + root.substringAfter("://").substringBefore('/') + ref
                    else -> { found += "$folder/${ref.removePrefix("./").removePrefix("../")}"; at.toHttpUrlOrNull()?.resolve(ref)?.let { found += it.toString() } }
                }
            }
            if (found.size > 2) break
        }
        return found.filter { it.toHttpUrlOrNull() != null }
    }

    /** Fetches a page as a browser on the box would (redirects followed); returns its final address and text. */
    private fun fetchText(p: Playlist, url: String): Pair<String, String>? = runCatching {
        val r = Request.Builder().url(url).header("User-Agent", p.ua ?: UA).header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Cookie", "mac=${normalizeMac(p.mac).enc()}; stb_lang=en").build()
        http.newCall(r).execute().use { if (it.isSuccessful) it.request.url.toString() to (it.body?.string()?.take(600_000) ?: "") else null }
    }.getOrNull()

    /** What a MAG250 sends with get_profile. The ids are derived from the MAC, so they stay the same every time. */
    private fun profileQuery(p: Playlist, full: Boolean, box: Box = BOXES[0]): String {
        val ver = "ImageDescription: ${box.image}; ImageDate: Fri Jan 15 15:20:44 EET 2021; PORTAL version: 5.6.6; " +
            "API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c"
        val base = "hd=1&ver=${ver.enc().replace("+", "%20")}&num_banks=2&stb_type=${box.model}&image_version=${box.imageVersion}" +
            "&video_out=hdmi&hw_version=${box.hw}&not_valid_token=0&auth_second_step=1"
        if (!full) return "$base&sn="
        val mac = normalizeMac(p.mac)
        fun hash(alg: String, t: String) = java.security.MessageDigest.getInstance(alg).digest(t.toByteArray()).joinToString("") { "%02X".format(it) }
        val sn = hash("MD5", mac).take(13)
        val id = hash("SHA-256", sn)
        val id2 = hash("SHA-256", mac)
        val sig = hash("SHA-256", sn + mac)
        val metrics = """{"mac":"$mac","sn":"$sn","model":"${box.model}","type":"STB","uid":"$id","random":""}""".enc()
        return "$base&sn=$sn&device_id=$id&device_id2=$id2&signature=$sig&client_type=STB&metrics=$metrics&api_signature=262&prehash=&timestamp=${System.currentTimeMillis() / 1000}"
    }

    /** Handshake + profile: tries each address and both MAC cookie styles; explains what each one answered if all fail. */
    private fun session(p: Playlist, fresh: Boolean = false): Session {
        sessions[p.id]?.let { if (!fresh && System.currentTimeMillis() - it.at < 30 * 60_000) return it }
        val attempts = ArrayList<String>()
        val queue = ArrayDeque(endpoints(p))
        val tried = HashSet<String>()
        var discovered = false
        var refusal: String? = null
        while (true) {
            if (queue.isEmpty() && !discovered) { discovered = true; queue.addAll(discover(p).filter { it !in tried }) }
            val endpoint = queue.removeFirstOrNull() ?: break
            if (!tried.add(endpoint)) continue
            val name = endpoint.substringAfter("://").substringAfter('/')
            for (raw in listOf(false, true)) try {
                // Simple login first; strict portals also want the box's serial / device ids (as a real MAG sends).
                val remembered = boxFor[p.id]
                val tries = (listOfNotNull(remembered?.let { it to true }) + listOf(BOXES[0] to false, BOXES[0] to true) + BOXES.drop(1).map { it to true }).distinct()
                for ((box, full) in tries) {
                    val hs = request(p, "$endpoint?type=stb&action=handshake&token=&prehash=0&JsHttpRequest=1-xml", null, raw, noRedirect, box)
                    val token = hs.o("js").o("token").s() ?: hs.o("js").s() ?: hs.o("token").s()
                    if (token == null) { attempts += "$name: no login token"; break }
                    val profile = request(p, "$endpoint?type=stb&action=get_profile&${profileQuery(p, full, box)}&JsHttpRequest=1-xml", token, raw, noRedirect, box)
                    val js = profile.o("js")
                    val status = js.o("status").s()
                    val msg = js.o("msg").s()?.replace(Regex("<[^>]*>"), " ")?.trim()
                    // Check the portal really lets us in before loading anything.
                    val denied = runCatching { request(p, "$endpoint?type=itv&action=get_genres&JsHttpRequest=1-xml", token, raw, noRedirect, box); null }
                        .exceptionOrNull()?.takeIf { it is NotPortal || it is Moved }
                    if (denied == null && status != "1" && status != "2" && js.o("blocked").s() != "1")
                        return Session(endpoint, token, System.currentTimeMillis(), raw, box).also { sessions[p.id] = it; boxFor[p.id] = box }
                    refusal = when {
                        js.o("blocked").s() == "1" -> "The portal has blocked MAC ${normalizeMac(p.mac)}" + (msg?.let { ": $it" } ?: ".")
                        status == "2" -> "This portal also wants a username and password for MAC ${normalizeMac(p.mac)}" + (msg?.let { ": $it" } ?: ".")
                        msg != null -> "The portal says: \"$msg\""
                        else -> "The portal let the app connect but refused access (\"${(denied as? NotPortal)?.snippet ?: "status $status"}\")."
                    }
                }
            } catch (e: Moved) {
                // Same API on another address (https, another host or port): follow it. A web page: note it and move on.
                val target = e.location.substringBefore('?')
                if (target.endsWith(".php") && target !in tried) queue.addFirst(target)
                else if (!target.endsWith(".php")) attempts += "$name: sent to ${e.location.substringAfter("://").take(50)}"
                break
            } catch (e: NotPortal) {
                attempts += "$name: \"${e.snippet.take(40)}\""
                break // same address answers the same way with the other cookie style
            } catch (e: IOException) {
                if (e.message?.startsWith("The portal rejected") == true) throw e
                attempts += "$name: ${e.message}"
                if (e.message?.startsWith("HTTP 404") == true) break
            }
        }
        val summary = attempts.distinct()
        refusal?.let {
            if (Regex("(?i)firmware|not supported|stb type|model").containsMatchIn(it))
                throw IOException("$it The portal only accepts certain set-top box models, and none of the ${BOXES.size} the app can pretend to be (${BOXES.joinToString { b -> b.model }}) was allowed. Ask your provider which box models this portal accepts.")
            throw IOException("$it Usually this means the MAC isn't activated on this portal yet, or the provider locks it to one device (ask them to activate MAC ${normalizeMac(p.mac)} or reset it).")
        }
        val shown = if (summary.size > 4) summary.take(2) + summary.takeLast(2) else summary
        val onlyPages = attempts.isNotEmpty() && attempts.all { "\"" in it || "sent to" in it }
        throw IOException("Couldn't log in to the portal (tried ${tried.size} addresses). " + shown.joinToString("; ") + ". " +
            if (onlyPages) "The server only shows its web page: check the MAC is activated on this portal and the URL is the exact one your provider gave."
            else "Check the portal URL (usually ends in /c/) and the MAC address.")
    }

    private fun call(p: Playlist, query: String): JsonElement {
        val s = session(p)
        return try { request(p, "${s.endpoint}?$query&JsHttpRequest=1-xml", s.token, s.rawCookie, box = s.box) }
        catch (e: IOException) { val n = session(p, fresh = true); request(p, "${n.endpoint}?$query&JsHttpRequest=1-xml", n.token, n.rawCookie, box = n.box) }
    }

    /** Live channels with categories. Stream URLs are stored as "stalker:<cmd>" and resolved at play time. */
    fun load(p: Playlist): Pair<List<ParsedChannel>, String> {
        val genreList = call(p, "type=itv&action=get_genres").o("js").arr()
        val genres = genreList.associate { (it.o("id").s() ?: "") to (it.o("title").s() ?: "Other") }
        val adultGenres = genreList.filter { it.o("censored").s() == "1" || Adult.isAdult(it.o("title").s()) }.mapNotNull { it.o("id").s() }.toSet()
        val genreOrder = genres.keys.withIndex().associate { (i, k) -> k to i }
        // Portal's own order: genres as listed, channels by number.
        val data = call(p, "type=itv&action=get_all_channels").o("js").let { it.o("data") ?: it }.arr()
            .sortedWith(compareBy({ genreOrder[it.o("tv_genre_id").s()] ?: Int.MAX_VALUE }, { it.o("number").s()?.toIntOrNull() ?: Int.MAX_VALUE }))
        val channels = data.mapNotNull { c ->
            val cmd = c.o("cmd").s() ?: return@mapNotNull null
            ParsedChannel(
                name = c.o("name").s()?.trim() ?: return@mapNotNull null,
                url = SCHEME + (c.o("id").s() ?: "") + "|" + cmd,
                group = genres[c.o("tv_genre_id").s()] ?: "Other",
                logo = c.o("logo").s()?.let { if (it.startsWith("http")) it else "${base(p.server)}/stalker_portal/misc/logos/320/$it" },
                tvgId = c.o("xmltv_id").s(), tvgName = c.o("name").s(), userAgent = p.ua ?: session(p).box.ua, referrer = null,
                adult = c.o("censored").s() == "1" || c.o("tv_genre_id").s() in adultGenres,
            )
        }
        val account = runCatching {
            val info = call(p, "type=account_info&action=get_main_info").o("js")
            listOfNotNull(info.o("phone").s()?.let { "Expires $it" }, info.o("tariff_plan").s()).joinToString(" · ")
        }.getOrDefault("")
        return channels to account.ifEmpty { "MAC ${normalizeMac(p.mac)}" }
    }

    /** A playable URL for a "stalker:<id>|<cmd>" channel. */
    fun resolve(p: Playlist, stored: String): String {
        val cmd = stored.removePrefix(SCHEME).substringAfter('|')
        val link = runCatching { call(p, "type=itv&action=create_link&cmd=${cmd.enc()}&series=&forced_storage=undefined&disable_ad=0&download=0").o("js").o("cmd").s() }
            .getOrNull() ?: cmd
        return link.substringAfter(' ').trim().takeIf { it.startsWith("http") } ?: link.trim()
    }

    /** Guide for the next day: channel id -> programmes (when the portal provides it). */
    fun epg(p: Playlist): Map<String, List<ProgramEntity>> {
        val data = call(p, "type=itv&action=get_epg_info&period=24").o("js").o("data") as? JsonObject ?: return emptyMap()
        return data.mapValues { (chId, list) ->
            list.arr().mapNotNull { e ->
                val start = e.o("start_timestamp").s()?.toLongOrNull()?.times(1000) ?: return@mapNotNull null
                val stop = e.o("stop_timestamp").s()?.toLongOrNull()?.times(1000) ?: (start + 3600_000)
                ProgramEntity(epgKey = "stk:${p.id}:$chId", start = start, stop = stop, title = e.o("name").s() ?: return@mapNotNull null,
                    subtitle = null, description = e.o("descr").s()?.take(400), category = e.o("category").s())
            }
        }
    }
}
