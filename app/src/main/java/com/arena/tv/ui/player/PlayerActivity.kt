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
    /** Scores shown in the corner (Settings → Sports → Scores while watching). */
    val scores: List<com.arena.tv.sports.Game> = emptyList(),
    /** Games whose score just changed (highlighted for a few seconds). */
    val goals: Set<String> = emptySet(),
    /** This channel is being recorded. */
    val recording: Boolean = false,
    /** Name of the Chromecast / Google TV it's playing on. */
    val casting: String? = null,
    /** How the stream arrives: "TS" or "HLS" (shown as a badge and in Stream info). */
    val format: String? = null,
)

/** One choice in the audio / subtitle / quality panels. groupIndex -1 = Auto (or Off for subtitles). */
data class TrackOption(val label: String, val groupIndex: Int, val trackIndex: Int, val selected: Boolean)

@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_LIST = "list"
        private const val EXTRA_TITLE = "title"

        /** For notifications: opens the player on one channel. */
        fun intent(context: Context, channelId: String, title: String?): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_ID, channelId).putStringArrayListExtra(EXTRA_LIST, arrayListOf(channelId)).putExtra(EXTRA_TITLE, title)

        fun start(context: Context, channelId: String, zapList: List<String>, title: String?) {
            val list = ArrayList(zapList.ifEmpty { listOf(channelId) }.take(2000))
            context.startActivity(Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ID, channelId).putStringArrayListExtra(EXTRA_LIST, list).putExtra(EXTRA_TITLE, title))
        }
    }

    private var player: ExoPlayer? = null
    /** On-device translated subtitles (Playback → Translate subtitles). */
    var subs: TranslatedSubtitles? = null
        private set
    private var ids: List<String> = emptyList()
    private var index = 0
    private var retries = 0
    private var triedHls = false
    /** Tried the other stream format (TS <-> HLS) for this channel ("Auto"). */
    private var triedAlt = false
    /** Channels whose captions couldn't be read: played without looking for them. */
    private val badCaptions = HashSet<String>()
    /** Subtitles were switched off only because of one channel's bad captions: switched back on for the next channel. */
    private var captionsOffByUs = false
    private var playJob: Job? = null
    private var lastIndex: Int? = null
    private var sleepJob: Job? = null
    private var guideJob: Job? = null
    private var retryJob: Job? = null
    val ui = mutableStateOf(PlayerUi())
    /** Last remote / touch input: the controls hide 5 seconds after it. */
    val lastInput = androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (app.prefs.value.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Full screen takes over from the mini player (one stream at a time).
        app.miniPlayer.value = null
        createPlayer()
        subs = TranslatedSubtitles(lifecycleScope, player!!, app).also { player!!.addListener(it) }
        handleIntent(intent)
        setContent {
            ArenaTheme {
                PlayerScreen(activity = this, player = player!!, state = ui.value, zapIds = ids)
            }
        }
        // Red record button while this channel is being recorded.
        lifecycleScope.launch {
            app.recordings.items.collect { l ->
                val rec = l.any { it.status == "recording" && it.channelId == ui.value.channel?.id }
                if (rec != ui.value.recording) ui.value = ui.value.copy(recording = rec)
            }
        }
        // Scores in the corner: pinned games, your teams, the game on this channel.
        lifecycleScope.launch {
            val ticker = com.arena.tv.sports.ScoreTicker(app)
            val last = HashMap<String, String>()
            var clear: Job? = null
            while (true) {
                val list = runCatching { ticker.load(ui.value.channel?.id) }.getOrDefault(ui.value.scores)
                val changed = list.filter { g -> val k = "${g.home.score}-${g.away.score}"; val prev = last.put(g.id, k); prev != null && prev != k }.map { it.id }.toSet()
                ui.value = ui.value.copy(scores = list, goals = if (changed.isEmpty()) ui.value.goals else changed)
                if (changed.isNotEmpty()) { clear?.cancel(); clear = launch { delay(15_000); ui.value = ui.value.copy(goals = emptySet()) } }
                delay(if (list.any { it.isLive }) 30_000 else 90_000)
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

    /** Record button: records this channel until the current show ends (2 hours without a guide); again to stop. */
    fun toggleRecord() = lifecycleScope.launch {
        val ch = ui.value.channel ?: return@launch
        val running = app.recordings.items.value.firstOrNull { it.status == "recording" && it.channelId == ch.id }
        if (running != null) { app.recordings.cancel(running.id); toast("Recording stopped · saved in Settings → Recording"); return@launch }
        val now = ui.value.now
        val late = app.prefs.value.planLate * 60_000L
        app.recordings.recordNow(ch, now?.title ?: ch.name, (now?.stop?.plus(late)) ?: (System.currentTimeMillis() + 2 * 3600_000L))
        toast(if (now != null) "Recording until ${com.arena.tv.ui.components.Time.clock(now.stop + late)}" else "Recording for 2 hours")
    }

    /** From the guide panel: record or get reminded about a show. */
    fun planShow(p: ProgramEntity, kind: String) = lifecycleScope.launch {
        val ch = ui.value.channel ?: return@launch
        if (kind == "record" && p.start <= System.currentTimeMillis()) { app.recordings.recordNow(ch, p.title, p.stop + app.prefs.value.planLate * 60_000L); toast("Recording ${p.title}"); return@launch }
        app.recordings.plan(ch, p.title, p.start, p.stop, kind)
        toast(if (kind == "record") "Will record ${p.title}" else "We'll remind you before ${p.title}")
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    fun resize() { ui.value = ui.value.copy(resize = (ui.value.resize + 1) % 3) }
    /** Mini player: keeps this channel playing in a small window inside TuTu while you browse. */
    fun pip() {
        val ch = ui.value.channel ?: return
        app.miniPlayer.value = com.arena.tv.MiniPlayback(ch.id, ids, intent.getStringExtra(EXTRA_TITLE))
        finish()
    }

    // ---------------- Casting ----------------

    private val castContext by lazy { runCatching { com.google.android.gms.cast.framework.CastContext.getSharedInstance(this) }.getOrNull() }
    private val castListener = object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
        override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, id: String) = castStarted(session)
        override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) = castStarted(session)
        override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) = castEnded()
        override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) { castEnded(); toast("Couldn't connect to the Cast device") }
        override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {}
        override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
        override fun onSessionResuming(session: com.google.android.gms.cast.framework.CastSession, id: String) {}
        override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {}
        override fun onSessionSuspended(session: com.google.android.gms.cast.framework.CastSession, reason: Int) {}
    }

    /** Cast button: a small panel with Chromecast and "open in another app". */
    fun cast() = setPanel("cast")

    /** Chromecast / Google TV: Android's device picker, or stop casting if already casting. */
    fun castToDevice() {
        val ctx = castContext ?: run { toast("Casting isn't available on this device (Google Play services needed). Try \"Open in another app\"."); return }
        val session = ctx.sessionManager.currentCastSession
        if (session != null && session.isConnected) { ctx.sessionManager.endCurrentSession(true); setPanel(null); return }
        val selector = ctx.mergedSelector ?: return
        setPanel(null)
        runCatching {
            androidx.mediarouter.app.MediaRouteChooserDialog(android.view.ContextThemeWrapper(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog)).apply {
                routeSelector = selector
            }.show()
        }.onFailure { toast("Couldn't open the Cast device list") }
    }

    private fun castStarted(session: com.google.android.gms.cast.framework.CastSession) {
        ui.value = ui.value.copy(casting = session.castDevice?.friendlyName ?: "TV")
        player?.pause()
        ui.value.channel?.let { castChannel(it) }
    }

    private fun castEnded() {
        if (ui.value.casting == null) return
        ui.value = ui.value.copy(casting = null)
        ui.value.channel?.let { play(it, forceHls = false) }
    }

    /** Sends the channel to the Cast device (HLS when the provider offers it: Chromecasts play that best). */
    private fun castChannel(ch: ChannelEntity) = lifecycleScope.launch {
        val session = castContext?.sessionManager?.currentCastSession ?: return@launch
        val r = runCatching {
            val first = app.streams.resolve(ch, false)
            if (!first.url.contains(".m3u8", true) && app.streams.hasAlternate(ch)) app.streams.resolve(ch, true) else first
        }.getOrElse { toast("Couldn't get the stream: ${it.message}"); return@launch }
        val hls = r.forceHls || r.url.contains(".m3u8", true)
        val meta = com.google.android.gms.cast.MediaMetadata(com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, ui.value.now?.title ?: ch.name)
            putString(com.google.android.gms.cast.MediaMetadata.KEY_SUBTITLE, ch.name)
            ch.logo?.takeIf { it.startsWith("http") }?.let { addImage(com.google.android.gms.common.images.WebImage(android.net.Uri.parse(it))) }
        }
        val info = com.google.android.gms.cast.MediaInfo.Builder(r.url).setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(if (hls) "application/x-mpegURL" else "video/mp2t").setMetadata(meta).build()
        session.remoteMediaClient?.load(com.google.android.gms.cast.MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
        toast("Casting ${ch.name} to ${session.castDevice?.friendlyName ?: "your TV"}")
    }

    /** Opens the stream in another app (VLC, Web Video Caster, MX Player…), which can also cast it. */
    fun openElsewhere() = lifecycleScope.launch {
        val ch = ui.value.channel ?: return@launch
        val r = runCatching { app.streams.resolve(ch, false) }.getOrElse { toast("Couldn't get the stream: ${it.message}"); return@launch }
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(r.url), "video/*").putExtra("title", ch.name)
        runCatching { startActivity(Intent.createChooser(i, "Open ${ch.name} with")); setPanel(null) }
            .onFailure { toast("No other video app installed") }
    }
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

    /** Stream info: switch between TS and HLS for this channel (when the provider offers both). */
    fun switchFormat() {
        val ch = ui.value.channel ?: return
        if (!app.streams.hasAlternate(ch)) { toast("This channel only comes in one format"); return }
        triedAlt = !triedAlt; retries = 0
        ui.value = ui.value.copy(error = null)
        play(ch, forceHls = false)
        setPanel(null)
    }
    fun canSwitchFormat() = ui.value.channel?.let { app.streams.hasAlternate(it) } == true

    /** Details for the Stream info panel. */
    fun streamInfo(): List<Pair<String, String>> {
        val p = player ?: return emptyList()
        val v = p.videoFormat; val au = p.audioFormat
        val hasCaptions = p.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
        return listOfNotNull(
            "Format" to when (ui.value.format) {
                "HLS" -> "HLS (.m3u8) · small pieces, smoother on weak Wi-Fi"
                "TS" -> "TS (MPEG-TS) · one continuous stream, starts fast"
                else -> ui.value.format ?: "—"
            },
            v?.let { "Video" to listOfNotNull(it.height.takeIf { h -> h > 0 }?.let { h -> "${it.width}×$h" },
                it.frameRate.takeIf { f -> f > 0 }?.let { f -> "${kotlin.math.round(f).toInt()} fps" },
                it.sampleMimeType?.substringAfter('/')?.uppercase()?.replace("AVC", "H.264")?.replace("HEVC", "H.265")).joinToString(" · ") },
            au?.let { "Audio" to listOfNotNull(audioName(it) ?: "${it.channelCount} ch", it.sampleMimeType?.substringAfter('/')?.uppercase(),
                it.language?.let { l -> java.util.Locale(l).displayLanguage }).joinToString(" · ") },
            (v?.bitrate?.takeIf { it > 0 } ?: v?.averageBitrate?.takeIf { it > 0 })?.let { "Bitrate" to "%.1f Mbps".format(it / 1_000_000.0) },
            "Captions" to if (hasCaptions) "Yes — can be shown or translated (CC)" else "None on this channel",
            "Buffered" to "${(p.totalBufferedDuration / 1000).coerceAtLeast(0)} s ahead",
            ui.value.channel?.url?.let { u -> "Server" to (Uri.parse(u.removePrefix("stalker:")).host ?: "Portal") },
        )
    }

    /** CC panel: translate subtitles into a language ("" = off). Remembered for next time. */
    fun setTranslate(lang: String) = lifecycleScope.launch {
        app.settings.updatePrefs { it.copy(subtitleTranslate = lang) }
        app.prefs.value = app.prefs.value.copy(subtitleTranslate = lang)
        if (lang.isNotEmpty()) {
            app.translator.download(lang)
            // Make sure subtitles are on (translation needs them).
            player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build() }
            subs?.ensureTextTrack()
        } else subs?.text?.value = null
        setPanel(null)
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
        val captions = app.prefs.value.subtitleTranslate.isNotEmpty() && ch.id !in badCaptions
        return DefaultMediaSourceFactory(this, Streams.extractors(captions)).setDataSourceFactory(ds)
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
            if (captionsOffByUs && id !in badCaptions) {
                captionsOffByUs = false
                player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build() }
            }
            play(ch, forceHls = false)
            val fav = id in app.db.user().favoriteIds().first()
            ui.value = ui.value.copy(channel = ch, index = index, total = ids.size, favorite = fav, error = null, now = null, next = null, videoInfo = null)
            app.db.user().touchRecent(RecentChannelEntity(id, System.currentTimeMillis()))
            startGuide(ch)
        }
    }

    private fun play(ch: ChannelEntity, forceHls: Boolean) {
        val p = player ?: return
        // While casting, changing channel changes what the Cast device plays.
        if (ui.value.casting != null) { castChannel(ch); return }
        ui.value = ui.value.copy(buffering = true)
        playJob?.cancel()
        playJob = lifecycleScope.launch {
            // MAC portals need a fresh link every time; "Auto" may switch TS <-> HLS.
            val r = runCatching { app.streams.resolve(ch, triedAlt) }.getOrElse {
                ui.value = ui.value.copy(error = it.message ?: "Couldn't get the stream from the portal", buffering = false); return@launch
            }
            val hls = forceHls || r.forceHls || r.url.contains("m3u8", true)
            ui.value = ui.value.copy(format = when {
                hls -> "HLS"
                r.url.substringBefore('?').endsWith(".mp4", true) -> "MP4"
                else -> "TS"
            })
            val item = MediaItem.Builder().setUri(Uri.parse(r.url)).apply {
                if (hls) setMimeType(MimeTypes.APPLICATION_M3U8)
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
        // The subtitle / caption part failed (not the picture or sound): carry on without subtitles.
        val failedType = (e as? androidx.media3.exoplayer.ExoPlaybackException)
            ?.takeIf { it.type == androidx.media3.exoplayer.ExoPlaybackException.TYPE_RENDERER }
            ?.let { runCatching { player?.getRendererType(it.rendererIndex) }.getOrNull() }
        val decoding = e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED || e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        if (ch != null && (failedType == androidx.media3.common.C.TRACK_TYPE_TEXT || (decoding && ch.id !in badCaptions && app.prefs.value.subtitleTranslate.isNotEmpty()))) {
            badCaptions += ch.id
            captionsOffByUs = true
            player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build() }
            toast("This channel's captions couldn't be read, so subtitles are off for it")
            play(ch, forceHls = triedHls)
            return
        }
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
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                "This device can't decode this stream" + when (failedType) {
                    androidx.media3.common.C.TRACK_TYPE_VIDEO -> " (picture: the TV doesn't support this video format)."
                    androidx.media3.common.C.TRACK_TYPE_AUDIO -> " (sound: the TV doesn't support this audio format)."
                    else -> "."
                }
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
        lastInput.longValue = System.currentTimeMillis()
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

    override fun onResume() {
        super.onResume()
        castContext?.sessionManager?.addSessionManagerListener(castListener, com.google.android.gms.cast.framework.CastSession::class.java)
    }

    override fun onPause() {
        castContext?.sessionManager?.removeSessionManagerListener(castListener, com.google.android.gms.cast.framework.CastSession::class.java)
        super.onPause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

}
