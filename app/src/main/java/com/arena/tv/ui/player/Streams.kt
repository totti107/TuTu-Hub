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
import okhttp3.OkHttpClient

/** Shared ExoPlayer setup for the full-screen player and the inline previews. */
@UnstableApi
object Streams {
    fun player(context: Context, live: Boolean): ExoPlayer {
        val load = if (live) DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_000, 4_000).build()
        else DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 120_000, 2_500, 5_000).build()
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers).setLoadControl(load).build()
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
