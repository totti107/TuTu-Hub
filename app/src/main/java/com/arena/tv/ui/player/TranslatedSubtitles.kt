package com.arena.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import com.arena.tv.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Playback → "Translate subtitles": catches each subtitle line from the stream (movie subtitles or a channel's
 * closed captions), translates it on the device and shows it instead of the original.
 */
class TranslatedSubtitles(private val scope: CoroutineScope, private val player: Player, private val c: AppContainer) : Player.Listener {
    /** The translated line on screen (null = nothing). */
    val text = mutableStateOf<String?>(null)
    private var job: Job? = null
    private var trackLang: String? = null

    val target: String get() = c.prefs.value.subtitleTranslate
    val active: Boolean get() = target.isNotEmpty()

    override fun onCues(cueGroup: CueGroup) {
        if (!active) { text.value = null; return }
        val raw = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n").trim()
        job?.cancel()
        if (raw.isEmpty()) { text.value = null; return }
        val to = target
        job = scope.launch {
            // A slow first download shows the original meanwhile.
            val out = runCatching { withTimeout(4_000) { c.translator.translate(raw, trackLang, to) } }.getOrNull()
            text.value = out ?: raw
        }
    }

    override fun onTracksChanged(tracks: Tracks) {
        trackLang = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.let { g ->
            (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it).language }
        }
        if (active) ensureTextTrack(tracks)
    }

    /** Translation needs subtitles switched on: picks the stream's first subtitle / caption track (unless you turned them off). */
    fun ensureTextTrack(tracks: Tracks = player.currentTracks) {
        val params = player.trackSelectionParameters
        if (C.TRACK_TYPE_TEXT in params.disabledTrackTypes) return
        if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }) return
        fun textBased(m: String?) = m != null && m !in setOf(androidx.media3.common.MimeTypes.APPLICATION_PGS, androidx.media3.common.MimeTypes.APPLICATION_VOBSUB,
            androidx.media3.common.MimeTypes.APPLICATION_DVBSUBS)
        val g = tracks.groups.firstOrNull { gr -> gr.type == C.TRACK_TYPE_TEXT && gr.isSupported &&
            (0 until gr.length).any { gr.isTrackSupported(it) && textBased(gr.getTrackFormat(it).sampleMimeType ?: gr.getTrackFormat(it).codecs) } } ?: return
        val i = (0 until g.length).firstOrNull { g.isTrackSupported(it) && textBased(g.getTrackFormat(it).sampleMimeType ?: g.getTrackFormat(it).codecs) } ?: return
        player.trackSelectionParameters = params.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
    }
}

/** The translated line, styled like normal subtitles; moves up while the controls are showing. */
@Composable
fun TranslatedSubtitleText(text: String?, status: String?, scale: Float, raised: Boolean) {
    Box(Modifier.fillMaxSize().padding(bottom = if (raised) 230.dp else 48.dp, start = 48.dp, end = 48.dp), contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            status?.let {
                Text("🌐 $it", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(8.dp))
                    .background(Color(0xAA000000)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            if (!text.isNullOrBlank()) Text(text, color = Color.White, fontSize = (24 * scale).sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                lineHeight = (30 * scale).sp, modifier = Modifier.widthIn(max = 900.dp).clip(RoundedCornerShape(10.dp))
                    .background(Color(0xB3000000)).padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}
