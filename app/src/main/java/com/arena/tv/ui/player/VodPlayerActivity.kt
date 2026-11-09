package com.arena.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.arena.tv.app
import com.arena.tv.data.EpisodeEntity
import com.arena.tv.data.ProgressEntity
import com.arena.tv.data.TraktItem
import com.arena.tv.data.VodEntity
import com.arena.tv.ui.components.WhiteButton
import com.arena.tv.ui.theme.ArenaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Player for movies and episodes: seek bar, audio / subtitle tracks, resume, next episode, Trakt scrobbling. */
@UnstableApi
class VodPlayerActivity : ComponentActivity() {
    companion object {
        fun startFile(context: Context, path: String, title: String, subtitle: String?) {
            context.startActivity(Intent(context, VodPlayerActivity::class.java).putExtra("file", path).putExtra("title", title).putExtra("subtitle", subtitle))
        }
        fun start(context: Context, vodKey: String, episodeKey: String?) {
            context.startActivity(Intent(context, VodPlayerActivity::class.java).putExtra("vod", vodKey).putExtra("ep", episodeKey))
        }
    }

    private lateinit var player: ExoPlayer
    private var vod: VodEntity? = null
    private var episode: EpisodeEntity? = null
    private var episodes: List<EpisodeEntity> = emptyList()
    private var saveJob: Job? = null
    private var scrobbling = false
    private val title = mutableStateOf("")
    private val subtitle = mutableStateOf<String?>(null)
    private val controlsVisible = mutableStateOf(true)
    private val nextUp = mutableStateOf<EpisodeEntity?>(null)
    private val error = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.prefs.value.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        player = Streams.player(this, live = false, app.prefs.value)
        val subs = TranslatedSubtitles(lifecycleScope, player, app).also { player.addListener(it) }
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (app.prefs.value.matchFrameRate) player.videoFormat?.frameRate?.let { Streams.matchFrameRate(this@VodPlayerActivity, it) }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) { scrobble("start"); scrobbling = true } else if (scrobbling && player.playbackState != Player.STATE_ENDED) scrobble("pause")
                if (!isPlaying) saveProgress()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { saveProgress(); scrobble("stop"); scrobbling = false; next()?.let(::playEpisode) ?: finish() }
            }
            override fun onPlayerError(e: PlaybackException) {
                error.value = "Can't play this title (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})."
            }
        })
        val file = intent.getStringExtra("file")
        if (file != null) {
            // A recording saved on this device.
            title.value = intent.getStringExtra("title") ?: "Recording"
            subtitle.value = intent.getStringExtra("subtitle")
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(file))))
            player.prepare(); player.play()
        } else {
        val vodKey = intent.getStringExtra("vod") ?: return finish()
            val epKey = intent.getStringExtra("ep")
            lifecycleScope.launch {
                val v = app.db.vod().byKey(vodKey) ?: return@launch finish()
                vod = v
                if (epKey != null) {
                    episodes = app.vod.episodes(v)
                    episodes.firstOrNull { it.key == epKey }?.let(::playEpisode) ?: finish()
                } else {
                    title.value = v.name
                    subtitle.value = v.year?.toString()
                    prepare(app.vod.movieUrl(v), v.key)
                }
            }
        }
        startProgressLoop()

        setContent {
            ArenaTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            player = this@VodPlayerActivity.player
                            setShowSubtitleButton(true)
                            setShowNextButton(false); setShowPreviousButton(false)
                            setShowFastForwardButton(true); setShowRewindButton(true)
                            controllerShowTimeoutMs = 3500
                            if (subs.active) subtitleView?.visibility = android.view.View.GONE
                            subtitleView?.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Streams.subtitleScale(app.prefs.value))
                            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsVisible.value = v == android.view.View.VISIBLE })
                        }
                    }, modifier = Modifier.fillMaxSize())

                    if (!controlsVisible.value && app.prefs.value.clockAlways) CornerClock(Modifier.align(Alignment.TopEnd))
                    if (subs.active) TranslatedSubtitleText(subs.text.value, app.translator.status.collectAsState().value,
                        Streams.subtitleScale(app.prefs.value), controlsVisible.value)
                    AnimatedVisibility(controlsVisible.value, enter = fadeIn(), exit = fadeOut()) {
                        Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                            Column {
                                Text(title.value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                                subtitle.value?.let { Text(it, color = Color(0xAAFFFFFF), fontSize = 13.sp, maxLines = 1) }
                            }
                        }
                    }
                    nextUp.value?.let { n ->
                        Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 90.dp)) {
                            WhiteButton("Next: E${n.number}", { playEpisode(n) }, icon = Icons.Rounded.SkipNext)
                        }
                    }
                    error.value?.let {
                        Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp))
                            .background(Color(0xCC111111)).padding(20.dp))
                    }
                }
            }
        }
    }

    private fun next(): EpisodeEntity? = episode?.let { e -> episodes.getOrNull(episodes.indexOf(e) + 1) }

    private fun playEpisode(e: EpisodeEntity) {
        if (episode != null && scrobbling) { saveProgress(); scrobble("stop"); scrobbling = false }
        episode = e
        nextUp.value = null
        title.value = vod?.name ?: ""
        subtitle.value = "S${e.season} E${e.number} · ${e.title}"
        lifecycleScope.launch { prepare(e.url, e.key) }
    }

    private suspend fun prepare(url: String, progressKey: String) {
        error.value = null
        val ua = vod?.let { app.vod.userAgent(it) }
        player.setMediaSource(Streams.source(this, app.http, url, ua, null))
        val saved = app.db.vod().progress(progressKey)
        player.prepare()
        if (saved != null && !saved.finished && saved.positionMs > 30_000) player.seekTo(saved.positionMs - 5_000)
        player.play()
    }

    private fun startProgressLoop() {
        saveJob = lifecycleScope.launch {
            while (true) {
                delay(10_000)
                if (player.isPlaying) saveProgress()
                val d = player.duration
                nextUp.value = if (episode != null && d > 0 && player.currentPosition > d * 0.93) next() else null
            }
        }
    }

    private fun saveProgress() {
        val v = vod ?: return
        val d = player.duration.takeIf { it > 0 } ?: return
        val pos = player.currentPosition
        val e = episode
        val entity = ProgressEntity(key = e?.key ?: v.key, vodKey = v.key, positionMs = pos, durationMs = d, updatedAt = System.currentTimeMillis(),
            label = e?.let { "S${it.season} E${it.number}" } ?: v.name, season = e?.season, episode = e?.number)
        lifecycleScope.launch { app.db.vod().saveProgress(entity) }
    }

    private fun scrobble(action: String) {
        val v = vod ?: return
        val d = player.duration.takeIf { it > 0 } ?: return
        val pct = player.currentPosition * 100f / d
        val e = episode
        val item = if (e == null) TraktItem.Movie(v.name, v.year, v.tmdb)
        else TraktItem.Episode(v.name, v.year, v.tmdb, e.season, e.number)
        app.trakt.scrobble(action, item, if (action == "stop" && player.playbackState == Player.STATE_ENDED) 100f else pct)
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        if (scrobbling) { scrobble("stop"); scrobbling = false }
        player.pause()
    }

    override fun onDestroy() {
        saveJob?.cancel()
        player.release()
        super.onDestroy()
    }
}
