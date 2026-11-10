package com.arena.tv.ui.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
    fun player(context: Context, live: Boolean, prefs: Prefs = Prefs()): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers).setLoadControl(loadControl(live, prefs.buffer)).build()
            .also { applyLanguages(it, prefs) }
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
    fun extractors() = androidx.media3.extractor.DefaultExtractorsFactory().setTsSubtitleFormats(listOf(
        androidx.media3.common.Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).build()))

    fun source(context: Context, http: OkHttpClient, url: String, userAgent: String?, referrer: String?, forceHls: Boolean = false): MediaSource {
        val ds = OkHttpDataSource.Factory(http).setUserAgent(userAgent?.ifBlank { null } ?: "Mozilla/5.0 (Linux; Android) TuTuTV/1.0")
        referrer?.let { ds.setDefaultRequestProperties(mapOf("Referer" to it)) }
        val item = MediaItem.Builder().setUri(Uri.parse(url)).apply {
            if (forceHls || url.contains("m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        return DefaultMediaSourceFactory(context, extractors()).setDataSourceFactory(ds).createMediaSource(item)
    }
}
