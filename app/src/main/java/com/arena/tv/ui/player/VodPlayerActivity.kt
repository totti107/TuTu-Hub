package com.arena.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
    private val ui = mutableStateOf(VodUi())
    private val lastInput = androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())
    private lateinit var subs: TranslatedSubtitles
    private var textOffAfterError = false
    private var decoderRetried = false
    private var currentSource: androidx.media3.exoplayer.source.MediaSource? = null
    private var softwareDecoders = false
    private val playerRef = mutableStateOf<ExoPlayer?>(null)
    private lateinit var events: Player.Listener
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
        Streams.stopPreviews()
        player = Streams.player(this, live = false, app.prefs.value)
        subs = TranslatedSubtitles(lifecycleScope, player, app).also { player.addListener(it) }
        playerRef.value = player
        events = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (app.prefs.value.matchFrameRate) player.videoFormat?.frameRate?.let { Streams.matchFrameRate(this@VodPlayerActivity, it) }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui.value = ui.value.copy(playing = isPlaying)
                if (isPlaying) { scrobble("start"); scrobbling = true } else if (scrobbling && player.playbackState != Player.STATE_ENDED) scrobble("pause")
                if (!isPlaying) saveProgress()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { saveProgress(); scrobble("stop"); scrobbling = false; next()?.let(::playEpisode) ?: finish() }
            }
            override fun onPlayerError(e: PlaybackException) {
                val failed = (e as? androidx.media3.exoplayer.ExoPlaybackException)
                    ?.takeIf { it.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER }?.let { runCatching { player.getRendererType(it.rendererIndex) }.getOrNull() }
                val subtitleTrouble = failed == androidx.media3.common.C.TRACK_TYPE_TEXT ||
                    (e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED && !textOffAfterError && player.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSelected })
                if (subtitleTrouble && !textOffAfterError) {
                    // The subtitles can't be read: carry on without them, from the same spot.
                    textOffAfterError = true
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
                    player.prepare(); player.play()
                    android.widget.Toast.makeText(this@VodPlayerActivity, "These subtitles couldn't be read, so they're off for this title", android.widget.Toast.LENGTH_LONG).show()
                    return
                }
                // The TV's picture / sound decoder refused: retry once, then switch to the software decoders.
                val decoding = e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED || e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                if (decoding && failed != androidx.media3.common.C.TRACK_TYPE_TEXT) {
                    if (!decoderRetried) {
                        decoderRetried = true; Streams.stopPreviews()
                        lifecycleScope.launch { delay(800); player.prepare(); player.play() }
                        return
                    }
                    if (!softwareDecoders) { softwareDecoders = true; rebuildWithSoftware(); return }
                }
                error.value = "Can't play this title (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})" + when (failed) {
                    androidx.media3.common.C.TRACK_TYPE_VIDEO -> ": this device doesn't support its video format."
                    androidx.media3.common.C.TRACK_TYPE_AUDIO -> ": this device doesn't support its audio format. Try another audio track (Audio button)."
                    else -> "."
                } + ((Streams.videoDetail(player) ?: Streams.decoderDetail(e))?.let { "\n$it" } ?: "")
            }
        }
        player.addListener(events)
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
                val st = ui.value
                val prefs by app.prefs.collectAsState()
                val translating = prefs.subtitleTranslate.isNotEmpty()
                val touchTime = lastInput.longValue
                // Controls hide 5 seconds after the last button press (not while a panel is open or paused).
                LaunchedEffect(touchTime, st.controls, st.panel, st.playing) {
                    if (st.controls && st.panel == null && st.playing) { delay(5000); ui.value = ui.value.copy(controls = false) }
                }
                Box(Modifier.fillMaxSize().background(Color.Black)
                    .pointerInput(Unit) { detectTapGestures(onTap = { ui.value = ui.value.copy(controls = !ui.value.controls); lastInput.longValue = System.currentTimeMillis() }) }) {
                    AndroidView(factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            player = this@VodPlayerActivity.player
                            useController = false
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            subtitleView?.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Streams.subtitleScale(app.prefs.value))
                        }
                    }, update = {
                        playerRef.value?.let { p -> if (it.player !== p) it.player = p }
                        it.subtitleView?.visibility = if (translating) android.view.View.GONE else android.view.View.VISIBLE
                        it.resizeMode = when (st.resize) { 1 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM; 2 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT }
                    }, modifier = Modifier.fillMaxSize())

                    if (!st.controls && prefs.clockAlways) CornerClock(Modifier.align(Alignment.TopEnd))
                    if (translating) TranslatedSubtitleText(subs.text.value, app.translator.status.collectAsState().value, Streams.subtitleScale(prefs), st.controls)
                    AnimatedVisibility(st.controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                        VodControls(playerRef.value ?: player, st, title.value, subtitle.value, hasNext = next() != null,
                            onBack = { finish() }, onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                            onSeek = { d -> player.seekTo((player.currentPosition + d).coerceAtLeast(0)) }, onRestart = { player.seekTo(0); player.play() },
                            onNext = { next()?.let(::playEpisode) }, onPanel = { p -> ui.value = ui.value.copy(panel = p, controls = true) },
                            onResize = { ui.value = ui.value.copy(resize = (ui.value.resize + 1) % 3) },
                            touched = { lastInput.longValue = System.currentTimeMillis() })
                    }
                    VodPanel(app, playerRef.value ?: player, st.panel, onClose = { ui.value = ui.value.copy(panel = null) },
                        onPick = { type, o -> TrackTools.select(player, type, o); ui.value = ui.value.copy(panel = null) }) { lang -> setTranslate(lang) }
                    nextUp.value?.let { n ->
                        if (!st.controls) Box(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 40.dp)) {
                            WhiteButton("Next: E${n.number}", { playEpisode(n) }, icon = Icons.Rounded.SkipNext)
                        }
                    }
                    error.value?.let {
                        Column(Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xE6111214)).padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(it, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                WhiteButton("Play in another app", { openElsewhere() })
                                com.arena.tv.ui.components.GhostButton("Back", { finish() })
                            }
                            Text("VLC and similar apps have their own decoders and can play formats some TVs can't.", color = Color(0x99FFFFFF),
                                fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        }
    }

    /** Hands the title to another video app (VLC, MX Player…). */
    private fun openElsewhere() {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").putExtra("title", title.value)
            .putExtra("position", player.currentPosition)
        runCatching { startActivity(Intent.createChooser(i, "Play ${title.value} with")) }
            .onFailure { android.widget.Toast.makeText(this, "No other video app installed — VLC is free on the Play Store", android.widget.Toast.LENGTH_LONG).show() }
    }

    /** Same title, same spot, with the software decoders (when the TV's hardware decoder refuses). */
    private fun rebuildWithSoftware() {
        val item = player.currentMediaItem ?: return
        val source = currentSource
        val pos = player.currentPosition
        player.removeListener(events); player.removeListener(subs); player.release()
        player = Streams.player(this, live = false, app.prefs.value, softwareFirst = true)
        subs = TranslatedSubtitles(lifecycleScope, player, app).also { player.addListener(it) }
        player.addListener(events)
        playerRef.value = player
        if (source != null) player.setMediaSource(source) else player.setMediaItem(item)
        player.prepare(); player.seekTo(pos); player.play()
        android.widget.Toast.makeText(this, "Using the software decoder for this title", android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Subtitles panel: translate into a language ("" = off), remembered for next time. */
    private fun setTranslate(lang: String) = lifecycleScope.launch {
        app.settings.updatePrefs { it.copy(subtitleTranslate = lang) }
        app.prefs.value = app.prefs.value.copy(subtitleTranslate = lang)
        if (lang.isNotEmpty()) {
            app.translator.download(lang)
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build()
            subs.ensureTextTrack()
        } else subs.text.value = null
        ui.value = ui.value.copy(panel = null)
    }

    /** Remote: Back closes the panel / controls first; left / right seek when the controls are hidden. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        lastInput.longValue = System.currentTimeMillis()
        val st = ui.value
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_BACK -> when {
                    st.panel != null -> { ui.value = st.copy(panel = null); return true }
                    st.controls -> { ui.value = st.copy(controls = false); return true }
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (player.isPlaying) player.pause() else player.play(); ui.value = ui.value.copy(controls = true); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { player.seekTo(player.currentPosition + 30_000); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { player.seekTo((player.currentPosition - 30_000).coerceAtLeast(0)); return true }
                android.view.KeyEvent.KEYCODE_CAPTIONS -> { ui.value = st.copy(panel = "subs", controls = true); return true }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (!st.controls) { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); ui.value = st.copy(controls = true); return true }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (!st.controls) { player.seekTo(player.currentPosition + 10_000); ui.value = st.copy(controls = true); return true }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (!st.controls) { ui.value = st.copy(controls = true); return true }
            }
        }
        return super.dispatchKeyEvent(event)
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
        currentSource = Streams.source(this, app.http, url, ua, null)
        player.setMediaSource(currentSource!!)
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
