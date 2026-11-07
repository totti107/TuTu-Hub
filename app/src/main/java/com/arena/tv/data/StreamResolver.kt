package com.arena.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ResolvedStream(val url: String, val userAgent: String?, val forceHls: Boolean)

/**
 * Turns a stored channel into something playable:
 * - MAC portal channels get a fresh link from the portal,
 * - with [alternate] = true, Xtream live streams switch between MPEG-TS and HLS ("Auto" stream type).
 */
class StreamResolver(private val settings: SettingsStore, private val stalker: StalkerClient) {
    private val xtreamLive = Regex("""/live/[^/]+/[^/]+/\d+\.(ts|m3u8)(\?.*)?$""")

    suspend fun resolve(ch: ChannelEntity, alternate: Boolean): ResolvedStream = withContext(Dispatchers.IO) {
        if (ch.url.startsWith(StalkerClient.SCHEME)) {
            val p = settings.current().playlist(ch.playlistId) ?: throw java.io.IOException("This channel's playlist was removed")
            return@withContext ResolvedStream(stalker.resolve(p, ch.url), ch.userAgent ?: StalkerClient.UA, false)
        }
        var url = ch.url
        if (alternate && xtreamLive.containsMatchIn(url)) {
            url = if (url.contains(".ts")) url.replace(Regex("""\.ts(\?|$)"""), ".m3u8$1") else url.replace(Regex("""\.m3u8(\?|$)"""), ".ts$1")
        }
        ResolvedStream(url, ch.userAgent, alternate && url.contains(".m3u8"))
    }

    /** Whether trying the other format is worth it after a failure. */
    fun hasAlternate(ch: ChannelEntity): Boolean = xtreamLive.containsMatchIn(ch.url)
}
