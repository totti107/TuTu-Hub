package com.arena.tv.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import com.arena.tv.app
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.FavoriteChannelEntity
import com.arena.tv.data.ProgramEntity
import com.arena.tv.data.RecentChannelEntity
import com.arena.tv.ui.theme.ArenaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlayerUi(
    val channel: ChannelEntity? = null,
    val index: Int = 0,
    val total: Int = 0,
    val now: ProgramEntity? = null,
    val next: ProgramEntity? = null,
    val buffering: Boolean = true,
    val error: String? = null,
    val retrying: Boolean = false,
    val favorite: Boolean = false,
    val resize: Int = 0,
    val inPip: Boolean = false,
    val title: String? = null,
    val videoInfo: String? = null,
    /** "FHD", "50 fps", "HDR", "Dolby Atmos"... */
    val badges: List<String> = emptyList(),
    val playing: Boolean = true,
    val seekable: Boolean = false,
    /** Seconds behind live (null when at the live edge or unknown). */
    val behindLive: Int? = null,
    val controls: Boolean = true,
    /** Open side panel: channels, epg, recent, scores, audio, subs, quality, sleep. */
    val panel: String? = null,
    val sleepAt: Long? = null,
)

/** One choice in the audio / subtitle / quality panels. groupIndex -1 = Auto (or Off for subtitles). */
data class TrackOption(val label: String, val groupIndex: Int, val trackIndex: Int, val selected: Boolean)

@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_LIST = "list"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, channelId: String, zapList: List<String>, title: String?) {
            val list = ArrayList(zapList.ifEmpty { listOf(channelId) }.take(2000))
            context.startActivity(Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ID, channelId).putStringArrayListExtra(EXTRA_LIST, list).putExtra(EXTRA_TITLE, title))
        }
    }

    private var player: ExoPlayer? = null
    private var ids: List<String> = emptyList()
    private var index = 0
    private var retries = 0
    private var triedHls = false
    /** Tried the other stream format (TS <-> HLS) for this channel ("Auto"). */
    private var triedAlt = false
    private var playJob: Job? = null
    private var lastIndex: Int? = null
    private var sleepJob: Job? = null
    private var guideJob: Job? = null
    private var retryJob: Job? = null
    val ui = mutableStateOf(PlayerUi())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.prefs.value.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        createPlayer()
        handleIntent(intent)
        setContent {
            ArenaTheme {
                PlayerScreen(activity = this, player = player!!, state = ui.value, zapIds = ids)
            }
        }
        // Keeps "LIVE / -7s" up to date while the controls are showing.
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                val p = player ?: continue
                if (!ui.value.controls) continue
                val off = if (p.isCurrentMediaItemLive) p.currentLiveOffset else androidx.media3.common.C.TIME_UNSET
                val behind = if (off == androidx.media3.common.C.TIME_UNSET || off < 15_000) null else ((off - 5_000) / 1000).toInt()
                if (behind != ui.value.behindLive) ui.value = ui.value.copy(behindLive = behind)
            }
        }
    }

    // ---------------- controls used by the overlay ----------------
    fun close() = finish()
    fun next() = zap(+1)
    fun prev() = zap(-1)
    fun retry() { retries = 0; load() }
    fun pick(i: Int) { if (i != index) lastIndex = index; index = i; retries = 0; load(); setPanel(null) }
    fun lastChannel() { lastIndex?.let { pick(it) } }
    fun favorite() = toggleFavorite()
    fun resize() { ui.value = ui.value.copy(resize = (ui.value.resize + 1) % 3) }
    fun pip() = enterPip()
    fun showControls(show: Boolean) { ui.value = ui.value.copy(controls = show, panel = if (show) ui.value.panel else null) }
    fun setPanel(panel: String?) { ui.value = ui.value.copy(panel = panel, controls = true) }
    fun togglePlay() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekBy(ms: Long) {
        val p = player ?: return
        if (!p.isCurrentMediaItemSeekable) return
        p.seekTo((p.currentPosition + ms).coerceAtLeast(0))
    }
    /** Plays any channel (recent list): it's added to the zap list if needed. */
    fun playChannel(id: String) {
        val i = ids.indexOf(id)
        if (i >= 0) { pick(i); return }
        lastIndex = index
        ids = ids + id
        index = ids.size - 1
        retries = 0; load(); setPanel(null)
    }

    /** From the live scores panel: switch to the best channel showing the game. */
    fun watchGame(g: com.arena.tv.sports.Game) = lifecycleScope.launch {
        val best = runCatching { app.finder.find(g, emptyList()) }.getOrDefault(emptyList()).firstOrNull { it.score >= 40 }
        if (best == null) { android.widget.Toast.makeText(this@PlayerActivity, "Not found on your channels", android.widget.Toast.LENGTH_SHORT).show(); return@launch }
        playChannel(best.channelId)
    }

    fun goLive() { player?.seekToDefaultPosition(); player?.play() }

    fun setSleep(minutes: Int?) {
        sleepJob?.cancel()
        if (minutes == null) { ui.value = ui.value.copy(sleepAt = null); return }
        val at = System.currentTimeMillis() + minutes * 60_000L
        ui.value = ui.value.copy(sleepAt = at)
        sleepJob = lifecycleScope.launch { delay(minutes * 60_000L); finish() }
    }

    /** Audio, subtitle or video choices from the stream. */
    fun tracks(type: Int): List<TrackOption> {
        val p = player ?: return emptyList()
        val out = ArrayList<TrackOption>()
        p.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val label = when (type) {
                    androidx.media3.common.C.TRACK_TYPE_VIDEO -> listOfNotNull(f.height.takeIf { it > 0 }?.let { "${it}p" },
                        f.frameRate.takeIf { it > 0 }?.let { "${it.toInt()} fps" }, f.bitrate.takeIf { it > 0 }?.let { "${it / 1000} kbps" }).joinToString(" · ")
                    else -> listOfNotNull(f.label, f.language?.let { java.util.Locale(it).displayLanguage.ifBlank { it } },
                        audioName(f).takeIf { type == androidx.media3.common.C.TRACK_TYPE_AUDIO }).joinToString(" · ").ifBlank { "Track ${out.size + 1}" }
                }
                out += TrackOption(label, gi, ti, g.isTrackSelected(ti))
            }
        }
        return out
    }

    fun selectTrack(type: Int, option: TrackOption?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        when {
            option == null && type == androidx.media3.common.C.TRACK_TYPE_TEXT -> b.setTrackTypeDisabled(type, true)
            option == null || option.groupIndex < 0 -> b.setTrackTypeDisabled(type, false).clearOverridesOfType(type)
            else -> {
                val g = p.currentTracks.groups[option.groupIndex]
                b.setTrackTypeDisabled(type, false).setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, option.trackIndex))
            }
        }
        p.trackSelectionParameters = b.build()
        setPanel(null)
    }

    private fun audioName(f: androidx.media3.common.Format): String? = when {
        f.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_E_AC3_JOC || f.codecs?.contains("ec+3") == true -> "Dolby Atmos"
        f.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        f.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_E_AC3 || f.sampleMimeType == androidx.media3.common.MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        f.channelCount >= 6 -> "5.1"
        f.channelCount == 2 -> "Stereo"
        else -> null
    }

    /** Quality badges like "FHD · 50 fps · HDR · Dolby Atmos". */
    private fun badges(): List<String> {
        val p = player ?: return emptyList()
        val v = p.videoFormat
        val out = ArrayList<String>()
        val h = v?.height?.takeIf { it > 0 } ?: p.videoSize.height
        when {
            h >= 2000 -> out += "4K"
            h >= 1000 -> out += "FHD"
            h >= 700 -> out += "HD"
            h > 0 -> out += "SD"
        }
        v?.frameRate?.takeIf { it > 1 }?.let { out += "${kotlin.math.round(it).toInt()} fps" }
        when (v?.colorInfo?.colorTransfer) {
            androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> out += "HDR10"
            androidx.media3.common.C.COLOR_TRANSFER_HLG -> out += "HLG"
        }
        p.audioFormat?.let { audioName(it) }?.takeIf { it != "Stereo" }?.let { out += it }
        return out
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return finish()
        ids = intent.getStringArrayListExtra(EXTRA_LIST)?.takeIf { it.isNotEmpty() } ?: listOf(id)
        index = ids.indexOf(id).coerceAtLeast(0)
        ui.value = ui.value.copy(title = intent.getStringExtra(EXTRA_TITLE))
        load()
    }

    private fun createPlayer() {
        val prefs = app.prefs.value
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setLoadControl(Streams.loadControl(live = true, buffer = prefs.buffer))
            .build().apply {
                playWhenReady = true
                Streams.applyLanguages(this, prefs)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        ui.value = ui.value.copy(buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE && ui.value.error == null)
                        if (state == Player.STATE_READY) { retries = 0; ui.value = ui.value.copy(error = null, retrying = false) }
                        if (state == Player.STATE_ENDED) scheduleRetry("Stream ended") // live streams shouldn't end: reconnect
                    }
                    override fun onPlayerError(error: PlaybackException) = handleError(error)
                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.height > 0) ui.value = ui.value.copy(videoInfo = "${videoSize.height}p", badges = badges())
                    }
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        if (app.prefs.value.matchFrameRate) player?.videoFormat?.frameRate?.let { Streams.matchFrameRate(this@PlayerActivity, it) }
                        ui.value = ui.value.copy(badges = badges(), seekable = player?.isCurrentMediaItemSeekable == true)
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { ui.value = ui.value.copy(playing = isPlaying) }
                })
            }
    }

    private fun mediaSourceFactory(ch: ChannelEntity): DefaultMediaSourceFactory {
        val ds = OkHttpDataSource.Factory(app.http)
            .setUserAgent(ch.userAgent?.ifBlank { null } ?: "Mozilla/5.0 (Linux; Android) TuTuTV/1.0")
        ch.referrer?.let { ds.setDefaultRequestProperties(mapOf("Referer" to it)) }
        return DefaultMediaSourceFactory(this).setDataSourceFactory(ds)
    }

    private fun load() {
        val id = ids.getOrNull(index) ?: return
        retryJob?.cancel()
        lifecycleScope.launch {
            val ch = app.db.channels().byId(id) ?: run {
                ui.value = ui.value.copy(error = "Channel no longer in your list", buffering = false); return@launch
            }
            triedHls = false
            triedAlt = false
            play(ch, forceHls = false)
            val fav = id in app.db.user().favoriteIds().first()
            ui.value = ui.value.copy(channel = ch, index = index, total = ids.size, favorite = fav, error = null, now = null, next = null, videoInfo = null)
            app.db.user().touchRecent(RecentChannelEntity(id, System.currentTimeMillis()))
            startGuide(ch)
        }
    }

    private fun play(ch: ChannelEntity, forceHls: Boolean) {
        val p = player ?: return
        ui.value = ui.value.copy(buffering = true)
        playJob?.cancel()
        playJob = lifecycleScope.launch {
            // MAC portals need a fresh link every time; "Auto" may switch TS <-> HLS.
            val r = runCatching { app.streams.resolve(ch, triedAlt) }.getOrElse {
                ui.value = ui.value.copy(error = it.message ?: "Couldn't get the stream from the portal", buffering = false); return@launch
            }
            val item = MediaItem.Builder().setUri(Uri.parse(r.url)).apply {
                if (forceHls || r.forceHls || r.url.contains("m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8)
            }.build()
            p.setMediaSource(mediaSourceFactory(ch.copy(userAgent = r.userAgent)).createMediaSource(item))
            p.prepare()
            p.play()
        }
    }

    private fun startGuide(ch: ChannelEntity) {
        guideJob?.cancel()
        val key = ch.epgKey ?: return
        guideJob = lifecycleScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val list = app.db.programs().upcoming(key, now, 2)
                val cur = list.firstOrNull { it.start <= now }
                ui.value = ui.value.copy(now = cur, next = list.firstOrNull { it !== cur })
                delay(60_000)
            }
        }
    }

    private fun handleError(e: PlaybackException) {
        val ch = ui.value.channel
        // Some providers serve HLS from URLs without an .m3u8 extension: try again as HLS once.
        if (ch != null && !triedHls && hasCause<UnrecognizedInputFormatException>(e)) {
            triedHls = true
            play(ch, forceHls = true)
            return
        }
        // "Auto" stream type: if one format fails, try the other once (TS <-> HLS).
        if (ch != null && !triedAlt && app.streams.hasAlternate(ch) && e.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            triedAlt = true
            play(ch, forceHls = false)
            return
        }
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player?.seekToDefaultPosition(); player?.prepare(); return
        }
        val msg = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                val code = (e.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                when (code) {
                    401, 403 -> "Provider refused the stream ($code). Your account may be busy on another device or expired."
                    404 -> "Channel is offline (404)."
                    else -> "Provider error${code?.let { " ($it)" } ?: ""}."
                }
            }
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the stream. Check your connection."
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "This device can't decode this stream."
            else -> "Playback failed (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')})."
        }
        scheduleRetry(msg)
    }

    private inline fun <reified T : Throwable> hasCause(e: Throwable): Boolean {
        var c: Throwable? = e
        while (c != null) { if (c is T) return true; c = c.cause }
        return false
    }

    private fun scheduleRetry(msg: String) {
        if (retries >= 3) { ui.value = ui.value.copy(error = msg, retrying = false, buffering = false); return }
        retries++
        ui.value = ui.value.copy(retrying = true, error = null, buffering = true)
        retryJob?.cancel()
        retryJob = lifecycleScope.launch {
            delay(1500L * retries)
            ui.value.channel?.let { play(it, forceHls = triedHls) }
        }
    }

    private fun zap(delta: Int) {
        if (ids.size <= 1) return
        lastIndex = index
        index = (index + delta + ids.size) % ids.size
        retries = 0
        load()
    }

    private fun toggleFavorite() {
        val ch = ui.value.channel ?: return
        lifecycleScope.launch {
            if (ui.value.favorite) app.db.user().removeFavorite(ch.id) else app.db.user().addFavorite(FavoriteChannelEntity(ch.id, System.currentTimeMillis()))
            ui.value = ui.value.copy(favorite = !ui.value.favorite)
        }
    }

    // ---- TV remote ----
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val st = ui.value
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { zap(-1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { zap(+1); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
                KeyEvent.KEYCODE_BACK -> when {
                    st.panel != null -> { setPanel(null); return true }
                    st.controls -> { showControls(false); return true }
                }
                // Controls hidden: up/down change channel, OK / left / right bring the controls up.
                KeyEvent.KEYCODE_DPAD_UP -> if (!st.controls) { zap(-1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (!st.controls) { zap(+1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (!st.controls) { showControls(true); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- Picture in picture ----
    private fun pipSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enterPip() {
        if (!pipSupported()) return
        val p = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
        runCatching { enterPictureInPictureMode(p) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        ui.value = ui.value.copy(inPip = isInPictureInPictureMode)
    }

    override fun onStart() {
        super.onStart()
        player?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() }
    }

    override fun onStop() {
        super.onStop()
        // Leaving PiP (closing the small window) also lands here: stop streaming to save data / the connection slot.
        player?.pause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) player?.stop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

}
