package com.arena.tv.ui.live

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.arena.tv.app
import com.arena.tv.data.ChannelEntity
import com.arena.tv.ui.player.Streams

/**
 * Small player for the preview box and the mini player. It shows the app's one shared live player, so opening
 * full screen continues the same stream (no reconnect) and coming back keeps it playing.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewPlayer(channel: ChannelEntity?, modifier: Modifier = Modifier, mini: Boolean = false) {
    val context = LocalContext.current
    val miniActive by context.app.miniPlayer.collectAsState()
    if (!mini && miniActive != null) {
        // The mini player has the stream; don't fight over it.
        Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Playing in the mini player", color = Color(0x99FFFFFF), fontSize = 14.sp)
        }
        return
    }
    val player = remember { Streams.shared(context) }
    var state by remember { mutableStateOf(if (channel != null && Streams.isPlaying(channel.id)) "ready" else "idle") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<PlayerView?>(null) }

    DisposableEffect(player) {
        Streams.previewsShown++
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) { state = when (s) { Player.STATE_BUFFERING -> "buffering"; Player.STATE_READY -> "ready"; else -> state } }
            override fun onPlayerError(error: PlaybackException) { state = "error" }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            Streams.previewsShown--
            // Left this screen without opening full screen: stop streaming shortly after.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Streams.stopIfUnattended() }, 1500)
        }
    }
    DisposableEffect(lifecycle, player) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                // Leaving the app stops the stream — but not when full screen took it over.
                Lifecycle.Event.ON_STOP -> if (!Streams.fullscreen) Streams.stopShared()
                Lifecycle.Event.ON_START -> if (player.mediaItemCount > 0 && player.playbackState == Player.STATE_IDLE && channel != null) {
                    player.prepare(); player.play(); Streams.sharedChannel = channel.id
                }
                // Back from full screen: show the picture here again.
                Lifecycle.Event.ON_RESUME -> view?.let { v -> v.player = null; v.player = player }
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    var alternate by remember(channel?.id) { mutableStateOf(false) }
    DisposableEffect(player, channel?.id) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (channel != null && !alternate && context.app.streams.hasAlternate(channel)) alternate = true
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(channel?.id, alternate) {
        if (channel == null) return@LaunchedEffect
        // Already on this channel (e.g. back from full screen): keep playing, no reconnect.
        if (!alternate && Streams.isPlaying(channel.id)) { state = if (player.playbackState == Player.STATE_READY) "ready" else "buffering"; return@LaunchedEffect }
        state = "buffering"
        val r = runCatching { context.app.streams.resolve(channel, alternate) }.getOrNull() ?: run { state = "error"; return@LaunchedEffect }
        player.setMediaSource(Streams.source(context, context.app.http, r.url, r.userAgent, channel.referrer, r.forceHls))
        Streams.sharedChannel = channel.id
        player.prepare(); player.play()
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        when (state) {
            "buffering" -> CircularProgressIndicator(color = Color.White)
            "error" -> Text("Channel unavailable", color = Color.White, fontSize = 14.sp)
            "idle" -> if (channel == null) Text("Pick a channel to preview", color = Color(0x99FFFFFF), fontSize = 14.sp)
        }
    }
}
