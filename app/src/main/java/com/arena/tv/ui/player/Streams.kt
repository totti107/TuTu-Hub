package com.arena.tv.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.arena.tv.data.Prefs
import okhttp3.OkHttpClient

/** Shared ExoPlayer setup for the full-screen player and the inline previews. */
@UnstableApi
object Streams {
    fun player(context: Context, live: Boolean, prefs: Prefs = Prefs(), softwareFirst: Boolean = false): ExoPlayer {
        val renderers = renderers(context, softwareFirst)
        return ExoPlayer.Builder(context, renderers).setLoadControl(loadControl(live, prefs.buffer)).build()
            .also { applyLanguages(it, prefs) }
    }

    /**
     * Decoders for picture, sound and subtitles. Subtitles in older formats (e.g. some captions and SRT inside
     * streams) are read by the "legacy" decoder: without it, they stop the whole video with "decoding failed".
     */
    /** Small preview players (Live TV preview, mini player): stopped before full screen starts so it gets the video decoder. */
    private val previews = java.util.Collections.newSetFromMap(java.util.WeakHashMap<ExoPlayer, Boolean>())
    fun registerPreview(p: ExoPlayer) { previews += p }
    fun stopPreviews() { previews.toList().forEach { runCatching { it.stop(); it.clearMediaItems() } }; stopShared() }

    // ---------------- One live player for the whole app ----------------
    // The preview box, the mini player and full screen all use the same player, so opening full screen just
    // makes the channel bigger (no second connection, no stutter) and Back shrinks it again without stopping.

    private var sharedPlayer: ExoPlayer? = null
    /** Channel the shared player is playing. */
    var sharedChannel: String? = null
    /** Full screen is open (previews must not stop the player when their screen goes to the background). */
    @Volatile var fullscreen = false
    /** Small screens showing the shared player right now. */
    var previewsShown = 0
    /** Last channel watched full screen: the Live TV preview follows it when you come back. */
    val nowPlaying = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun shared(context: Context, softwareFirst: Boolean = false): ExoPlayer = sharedPlayer
        ?: player(context.applicationContext, live = true, (context.applicationContext as com.arena.tv.ArenaApp).container.prefs.value, softwareFirst)
            .also { it.volume = 1f; sharedPlayer = it }

    /** Throws the shared player away (decoder trouble) and makes a new one. */
    fun resetShared(context: Context, softwareFirst: Boolean): ExoPlayer { releaseShared(); return shared(context, softwareFirst) }
    fun releaseShared() { runCatching { sharedPlayer?.release() }; sharedPlayer = null; sharedChannel = null }
    fun stopShared() { runCatching { sharedPlayer?.stop() }; sharedChannel = null }
    /** Is the shared player already on this channel (playing or loading)? */
    fun isPlaying(channelId: String): Boolean = sharedChannel == channelId &&
        sharedPlayer?.let { it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_BUFFERING } == true
    /** Nobody is showing the player any more (e.g. full screen closed with no preview behind it): stop streaming. */
    fun stopIfUnattended() { if (!fullscreen && previewsShown == 0) stopShared() }

    /** "video/avc · c2.mtk.avc.decoder" — which decoder failed, for the error message. */
    fun decoderDetail(e: Throwable?): String? {
        var c = e
        while (c != null) {
            if (c is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException)
                return listOfNotNull(c.mimeType, c.codecInfo?.name).joinToString(" · ").ifEmpty { null }
            if (c is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException) return c.codecInfo?.let { "${it.mimeType} · ${it.name}" }
            c = c.cause
        }
        return null
    }

    /** Software video decoders run on the TV's processor: fine on phones, too slow for HD on most TVs. */
    fun isSoftwareDecoder(name: String) = name.startsWith("c2.android.") || name.startsWith("OMX.google.") || name.contains("ffmpeg", true) ||
        name.contains(".sw.", true)

    /** "3840×2160 · HEVC 10-bit · HDR · 24 fps" for the video track (helps explain why a TV can't play a file). */
    fun videoDetail(p: androidx.media3.common.Player): String? {
        val f = p.currentTracks.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }?.let { g ->
            (0 until g.length).map { g.getTrackFormat(it) }.maxByOrNull { it.height }
        } ?: return null
        val codec = when {
            f.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || f.codecs?.startsWith("dv") == true -> "Dolby Vision"
            f.sampleMimeType == MimeTypes.VIDEO_H265 -> "HEVC" + if (f.codecs?.contains(".2.") == true || f.colorInfo?.colorTransfer in setOf(
                androidx.media3.common.C.COLOR_TRANSFER_ST2084, androidx.media3.common.C.COLOR_TRANSFER_HLG)) " 10-bit" else ""
            f.sampleMimeType == MimeTypes.VIDEO_H264 -> "H.264"
            f.sampleMimeType == MimeTypes.VIDEO_AV1 -> "AV1"
            else -> f.sampleMimeType?.substringAfter('/')?.uppercase()
        }
        val hdr = when (f.colorInfo?.colorTransfer) {
            androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"; androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"; else -> null
        }
        return listOfNotNull(f.width.takeIf { it > 0 }?.let { "${it}×${f.height}" }, codec, hdr,
            f.frameRate.takeIf { it > 0 }?.let { "${kotlin.math.round(it).toInt()} fps" }).joinToString(" · ").ifEmpty { null }
    }

    /** Decoders: hardware first normally; [softwareFirst] when the TV's hardware decoder refuses (slower but reliable). */
    fun renderers(context: Context, softwareFirst: Boolean = false): DefaultRenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildTextRenderers(context: Context, output: androidx.media3.exoplayer.text.TextOutput, outputLooper: android.os.Looper,
                                        extensionRendererMode: Int, out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>) {
            out.add(androidx.media3.exoplayer.text.TextRenderer(output, outputLooper).apply { experimentalSetLegacyDecodingEnabled(true) })
        }
    }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER).setEnableDecoderFallback(true).apply {
        if (softwareFirst) setMediaCodecSelector { mime, secure, tunneling ->
            androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { !it.softwareOnly }
        }
    }

    /** Settings → Playback → Buffer: low starts fastest, high rides out weak connections. */
    fun loadControl(live: Boolean, buffer: String): DefaultLoadControl {
        val (min, max, play, rebuffer) = when {
            live && buffer == "low" -> listOf(8_000, 30_000, 1_200, 2_500)
            live && buffer == "high" -> listOf(30_000, 90_000, 4_000, 8_000)
            live -> listOf(15_000, 50_000, 2_000, 4_000)
            buffer == "low" -> listOf(15_000, 60_000, 1_500, 3_000)
            buffer == "high" -> listOf(60_000, 240_000, 5_000, 10_000)
            else -> listOf(30_000, 120_000, 2_500, 5_000)
        }
        return DefaultLoadControl.Builder().setBufferDurationsMs(min, max, play, rebuffer).build()
    }

    /** Preferred audio / subtitle languages from Settings → Playback. */
    fun applyLanguages(p: ExoPlayer, prefs: Prefs) {
        val b = p.trackSelectionParameters.buildUpon()
        if (prefs.audioLang.isNotEmpty()) b.setPreferredAudioLanguage(prefs.audioLang)
        if (prefs.subtitleLang.isNotEmpty()) b.setPreferredTextLanguage(prefs.subtitleLang).setSelectUndeterminedTextLanguage(true)
        p.trackSelectionParameters = b.build()
    }

    fun subtitleScale(prefs: Prefs): Float = when (prefs.subtitleSize) {
        "small" -> 0.8f; "large" -> 1.3f; "huge" -> 1.6f; else -> 1f
    }

    /**
     * Settings → Playback → Match frame rate: switches the screen to a refresh rate that fits the video
     * (50 Hz for 25/50 fps sport, 24 Hz for films) so motion doesn't judder. The screen goes back when the player closes.
     */
    fun matchFrameRate(activity: android.app.Activity, fps: Float) {
        if (fps <= 1f || android.os.Build.VERSION.SDK_INT < 23) return
        val display = activity.windowManager.defaultDisplay ?: return
        val current = display.mode
        val same = display.supportedModes.filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
        fun fits(rate: Float): Boolean { val k = kotlin.math.round(rate / fps); return k >= 1 && kotlin.math.abs(rate - k * fps) < 0.05f * k }
        val best = same.filter { fits(it.refreshRate) }.minByOrNull { it.refreshRate } ?: return
        if (best.modeId == current.modeId) return
        activity.window.attributes = activity.window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }

    /**
     * Stream reader that also looks for the closed captions US / UK channels hide inside the video (CEA-608),
     * even when the stream doesn't announce them, so they can be shown or translated.
     */
    fun extractors(captions: Boolean = false): androidx.media3.extractor.DefaultExtractorsFactory {
        val f = androidx.media3.extractor.DefaultExtractorsFactory()
        // Only when subtitles are being translated: some TVs fail on channels whose hidden captions are damaged.
        if (captions) f.setTsSubtitleFormats(listOf(
            androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).build()))
        return f
    }

    fun source(context: Context, http: OkHttpClient, url: String, userAgent: String?, referrer: String?, forceHls: Boolean = false): MediaSource {
        val ds = OkHttpDataSource.Factory(http).setUserAgent(userAgent?.ifBlank { null } ?: "Mozilla/5.0 (Linux; Android) TuTuTV/1.0")
        referrer?.let { ds.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val item = MediaItem.Builder().setUri(Uri.parse(url)).apply {
            if (forceHls || url.contains("m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        return DefaultMediaSourceFactory(context).setDataSourceFactory(ds).createMediaSource(item)
    }
}
