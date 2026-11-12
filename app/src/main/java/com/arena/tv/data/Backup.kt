package com.arena.tv.data

import com.arena.tv.sports.Region
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/** Everything worth carrying to another device: playlists, choices and favorites. Not the PIN or Trakt login. */
@Serializable
data class Backup(
    val app: String = "TuTu",
    val version: Int = 1,
    val createdAt: Long = 0,
    val playlists: List<Playlist> = emptyList(),
    val activePlaylist: String = "",
    val hiddenGroups: List<String> = emptyList(),
    val hiddenChannels: List<String> = emptyList(),
    val hiddenVodCats: List<String> = emptyList(),
    val regions: List<String> = emptyList(),
    val enabledLeagues: List<String> = emptyList(),
    val followedSports: List<String> = emptyList(),
    val hideAdult: Boolean = false,
    val decimalOdds: Boolean = true,
    val prefs: Prefs = Prefs(),
    val favoriteChannels: List<String> = emptyList(),
    val favoriteTeams: List<BackupTeam> = emptyList(),
    val favoriteTitles: List<String> = emptyList(),
)

@Serializable
data class BackupTeam(val key: String, val name: String, val logo: String? = null)

class BackupManager(private val settings: SettingsStore, private val db: ArenaDb) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    suspend fun export(): String {
        val s = settings.current()
        val b = Backup(
            createdAt = System.currentTimeMillis(),
            playlists = s.playlists.map { it.copy(error = null) },
            activePlaylist = s.activePlaylist,
            hiddenGroups = s.hiddenGroups.sorted(),
            hiddenChannels = s.hiddenChannels.sorted(),
            hiddenVodCats = s.hiddenVodCats.sorted(),
            regions = s.regions.map { it.name },
            enabledLeagues = s.enabledLeagues.sorted(),
            followedSports = s.followedSports.sorted(),
            hideAdult = s.hideAdult,
            decimalOdds = s.decimalOdds,
            prefs = s.prefs,
            favoriteChannels = db.user().favoriteIds().first(),
            favoriteTeams = db.user().teams().first().map { BackupTeam(it.key, it.name, it.logo) },
            favoriteTitles = db.vod().favoriteKeys().first(),
        )
        return json.encodeToString(Backup.serializer(), b)
    }

    /** Reads a backup; throws with a readable message if the file isn't one. */
    fun parse(text: String): Backup {
        val b = runCatching { json.decodeFromString(Backup.serializer(), text.trim().removePrefix("﻿")) }
            .getOrElse { throw IllegalArgumentException("That file isn't a TuTu backup.") }
        require(b.app == "TuTu") { "That file isn't a TuTu backup." }
        return b
    }

    /** Applies a backup. Playlists are replaced; favorites are added to the ones already here. */
    suspend fun restore(b: Backup) {
        settings.updatePlaylists { b.playlists.map { it.copy(lastRefresh = 0, lastEpg = 0, error = null) } }
        settings.setActivePlaylist(b.activePlaylist)
        settings.setHiddenGroups(b.hiddenGroups.toSet())
        settings.setHiddenChannels(b.hiddenChannels.toSet())
        settings.setHiddenVodCats(b.hiddenVodCats.toSet())
        settings.setRegions(b.regions.mapNotNull { r -> runCatching { Region.valueOf(r) }.getOrNull() }.toSet())
        if (b.enabledLeagues.isNotEmpty()) settings.setLeagues(b.enabledLeagues.toSet())
        settings.setFollowedSports(b.followedSports.toSet())
        settings.setHideAdult(b.hideAdult)
        settings.setDecimalOdds(b.decimalOdds)
        settings.updatePrefs { b.prefs }
        val now = System.currentTimeMillis()
        b.favoriteChannels.forEach { db.user().addFavorite(FavoriteChannelEntity(it, now)) }
        b.favoriteTeams.forEach { db.user().addTeam(FavoriteTeamEntity(it.key, it.name, it.logo)) }
        b.favoriteTitles.forEach { db.vod().addFavorite(VodFavoriteEntity(it, now)) }
    }
}

/**
 * For TVs without a file manager: the TV shows a QR code, the phone (same Wi-Fi) opens a page to
 * download this TV's backup or send a backup file to it. Runs only while the dialog is open.
 */
class BackupServer(private val exportJson: () -> String, private val onReceived: (String) -> String?) {
    private var server: ServerSocket? = null
    private val token = buildString { val r = SecureRandom(); repeat(8) { append("abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)]) } }

    fun start(): String? {
        val ip = LocalNetwork.ip() ?: return null
        val s = ServerSocket(0)
        server = s
        thread(isDaemon = true, name = "backup-server") {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
        return "http://$ip:${s.localPort}/$token"
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun handle(c: Socket) {
        c.soTimeout = 20_000
        val input = BufferedInputStream(c.getInputStream())
        fun line(): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read(); if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
            }
        }
        val request = line() ?: return
        val method = request.substringBefore(' ')
        val path = request.substringAfter(' ').substringBefore(' ').trimEnd('/')
        var length = 0
        while (true) {
            val h = line() ?: break
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:", true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        when {
            path == "/$token" -> respond(c, "text/html", page())
            path == "/$token/tutu-backup.json" -> respond(c, "application/json", exportJson(), download = "tutu-backup.json")
            path == "/$token/restore" && method == "POST" -> {
                val bytes = ByteArray(length.coerceIn(0, 5_000_000))
                var read = 0
                while (read < bytes.size) { val n = input.read(bytes, read, bytes.size - read); if (n < 0) break; read += n }
                val error = onReceived(String(bytes, 0, read, Charsets.UTF_8))
                respond(c, "text/plain", error ?: "OK")
            }
            else -> respond(c, "text/html", "<h3>Not found</h3>", code = 404)
        }
    }

    private fun page() = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>TuTu backup</title><style>
        body{font-family:system-ui,-apple-system,sans-serif;background:#060607;color:#f3f3f4;margin:0;padding:20px;max-width:520px}
        a.b,button,label.b{display:block;box-sizing:border-box;text-align:center;margin-top:16px;width:100%;padding:16px;border:0;border-radius:12px;background:#fff;color:#000;font-size:17px;font-weight:600;text-decoration:none}
        label.b{background:#17181b;color:#fff;border:1px solid #34353a}p{color:#8e9097}#s{margin-top:18px;font-size:18px}
        </style></head><body>
        <h2>TuTu backup</h2>
        <p>Save this TV's playlists and settings to your phone, or send a backup from your phone to this TV.</p>
        <a class="b" href="/$token/tutu-backup.json" download="tutu-backup.json">⬇ Download backup</a>
        <label class="b">⬆ Send a backup to the TV<input id="f" type="file" accept=".json,application/json" hidden></label>
        <div id="s"></div>
        <script>
        document.getElementById('f').onchange=function(e){var file=e.target.files[0];if(!file)return;var r=new FileReader();
        r.onload=function(){fetch('/$token/restore',{method:'POST',headers:{'Content-Type':'application/json'},body:r.result}).then(function(x){return x.text()})
        .then(function(t){document.getElementById('s').innerHTML=t=='OK'?'<span style="color:#22c55e">✓ Sent. Check the TV screen.</span>':'<span style="color:#ef4444">'+t+'</span>'})};r.readAsText(file)}
        </script></body></html>"""

    private fun respond(c: Socket, type: String, body: String, code: Int = 200, download: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "200 OK" else "404 Not Found"
        val extra = download?.let { "Content-Disposition: attachment; filename=\"$it\"\r\n" } ?: ""
        c.getOutputStream().apply {
            write("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\n${extra}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }
}

/** This device's address on the home network (for the phone QR pages). */
object LocalNetwork {
    fun ip(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { n -> when { n.name.startsWith("wlan") -> 0; n.name.startsWith("eth") -> 1; else -> 2 } }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()
}
