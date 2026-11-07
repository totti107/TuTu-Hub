package com.arena.tv.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Tiny web server that lets the user type playlist details on their phone instead of with a TV remote.
 * The TV shows a QR code with http://<tv-ip>:<port>/<token>; the phone (same Wi-Fi) opens a form and submits it.
 * Runs only while the dialog is open; the random token keeps other devices on the network out.
 */
class PhoneEntryServer(private val onReceived: (Playlist) -> Unit) {
    private var server: ServerSocket? = null
    private val token = buildString { val r = SecureRandom(); repeat(8) { append("abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)]) } }
    var url: String? = null
        private set

    /** @return the URL to put in the QR code, or null when there's no local network address. */
    fun start(): String? {
        val ip = localIp() ?: return null
        val s = ServerSocket(0)
        server = s
        url = "http://$ip:${s.localPort}/$token"
        thread(isDaemon = true, name = "phone-entry") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
        return url
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val input = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
        val requestLine = input.readLine() ?: return
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        var length = 0
        while (true) {
            val h = input.readLine() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        if (path.trimEnd('/') != "/$token") return respond(c, 404, "<h3>Not found</h3>")
        if (method == "POST") {
            val buf = CharArray(length.coerceAtMost(20_000))
            var read = 0
            while (read < buf.size) { val n = input.read(buf, read, buf.size - read); if (n < 0) break; read += n }
            val form = String(buf, 0, read).split('&').filter { it.contains('=') }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8").trim() }
            val type = when (form["type"]) { "m3u" -> SourceType.M3U; "mac" -> SourceType.MAC; else -> SourceType.XTREAM }
            val p = Playlist(
                id = "", name = form["name"].orEmpty().ifBlank { "My playlist" }, type = type,
                m3uUrl = form["m3u"].orEmpty(), server = if (type == SourceType.MAC) form["portal"].orEmpty() else form["server"].orEmpty(), mac = form["mac"].orEmpty(), user = form["user"].orEmpty(), pass = form["pass"].orEmpty(),
                epgUrl = form["epg"].orEmpty(), userAgent = form["ua"].orEmpty(),
            )
            if (!p.valid) return respond(c, 200, page("Something's missing — fill in the server and username, an M3U link starting with http, or a portal URL and a 12-digit MAC address.", form))
            onReceived(p)
            return respond(c, 200, """<div class="ok">✓ Sent to your TV</div><p>Check the TV screen to save the playlist. You can close this page.</p>""")
        }
        respond(c, 200, page(null, emptyMap()))
    }

    private fun esc(s: String?) = (s ?: "").replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    private fun page(error: String?, f: Map<String, String>): String = """
        <h2>Add a playlist to TuTu TV</h2>
        ${error?.let { "<p class=\"err\">$it</p>" } ?: ""}
        <form method="post">
          <label>Name</label><input name="name" value="${esc(f["name"])}" placeholder="My provider">
          <div class="tabs">
            <label><input type="radio" name="type" value="xtream" ${if (f["type"] != "m3u") "checked" else ""} onclick="sw()"> Xtream login</label>
            <label><input type="radio" name="type" value="m3u" ${if (f["type"] == "m3u") "checked" else ""} onclick="sw()"> M3U link</label>
            <label><input type="radio" name="type" value="mac" ${if (f["type"] == "mac") "checked" else ""} onclick="sw()"> MAC portal</label>
          </div>
          <div id="x">
            <label>Server</label><input name="server" value="${esc(f["server"])}" placeholder="http://host:port" autocapitalize="off" autocorrect="off" inputmode="url">
            <label>Username</label><input name="user" value="${esc(f["user"])}" autocapitalize="off" autocorrect="off">
            <label>Password</label><input name="pass" value="${esc(f["pass"])}" autocapitalize="off" autocorrect="off">
          </div>
          <div id="m">
            <label>M3U URL</label><input name="m3u" value="${esc(f["m3u"])}" placeholder="http://…/get.php?…" autocapitalize="off" autocorrect="off" inputmode="url">
          </div>
          <div id="p">
            <label>Portal URL</label><input name="portal" value="${esc(f["portal"])}" placeholder="http://host:port/c/" autocapitalize="off" autocorrect="off" inputmode="url">
            <label>MAC address</label><input name="mac" value="${esc(f["mac"] ?: "00:1A:79:")}" autocapitalize="characters" autocorrect="off">
          </div>
          <details><summary>Advanced</summary>
            <label>TV guide (XMLTV) URL</label><input name="epg" value="${esc(f["epg"])}" autocapitalize="off" inputmode="url">
            <label>User-Agent</label><input name="ua" value="${esc(f["ua"])}" autocapitalize="off">
          </details>
          <button type="submit">Send to TV</button>
        </form>
        <script>function sw(){var t=document.querySelector('input[name=type]:checked').value;document.getElementById('x').style.display=t=='xtream'?'block':'none';document.getElementById('m').style.display=t=='m3u'?'block':'none';document.getElementById('p').style.display=t=='mac'?'block':'none'}sw()</script>
    """.trimIndent()

    private fun respond(c: Socket, code: Int, body: String) {
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>TuTu TV</title><style>
            body{font-family:system-ui,-apple-system,sans-serif;background:#060607;color:#f3f3f4;margin:0;padding:20px;max-width:520px}
            h2{margin:4px 0 16px}label{display:block;color:#8e9097;font-size:14px;margin:14px 0 6px}
            input[name]{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #34353a;background:#17181b;color:#fff;font-size:16px}
            .tabs{display:flex;flex-wrap:wrap;gap:16px;margin-top:16px}.tabs label{color:#f3f3f4;font-size:16px;margin:0}
            button{margin-top:22px;width:100%;padding:16px;border:0;border-radius:12px;background:#fff;color:#000;font-size:17px;font-weight:600}
            summary{margin-top:18px;color:#8e9097}.err{color:#ef4444}.ok{font-size:28px;color:#22c55e;margin:30px 0 10px}
            </style></head><body>$body</body></html>"""
        val bytes = html.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "200 OK" else "404 Not Found"
        c.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { n -> when { n.name.startsWith("wlan") -> 0; n.name.startsWith("eth") -> 1; else -> 2 } }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
