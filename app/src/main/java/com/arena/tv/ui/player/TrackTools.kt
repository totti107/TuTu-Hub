package com.arena.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride

/** Lists and picks audio / subtitle tracks (used by the movie player). */
object TrackTools {
    fun audioName(f: Format): String? = when {
        f.sampleMimeType == MimeTypes.AUDIO_E_AC3_JOC || f.codecs?.contains("ec+3") == true -> "Dolby Atmos"
        f.sampleMimeType == MimeTypes.AUDIO_AC4 -> "Dolby AC-4"
        f.sampleMimeType == MimeTypes.AUDIO_E_AC3 || f.sampleMimeType == MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        f.channelCount >= 6 -> "5.1"
        f.channelCount == 2 -> "Stereo"
        else -> null
    }

    fun list(p: Player, type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        p.currentTracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val label = listOfNotNull(f.label, f.language?.let { java.util.Locale(it).displayLanguage.ifBlank { it } },
                    audioName(f).takeIf { type == C.TRACK_TYPE_AUDIO }).distinct().joinToString(" · ").ifBlank { "Track ${out.size + 1}" }
                out += TrackOption(label, gi, ti, g.isTrackSelected(ti))
            }
        }
        return out
    }

    /** null = Off for subtitles / Auto for audio. */
    fun select(p: Player, type: Int, option: TrackOption?) {
        val b = p.trackSelectionParameters.buildUpon()
        when {
            option == null && type == C.TRACK_TYPE_TEXT -> b.setTrackTypeDisabled(type, true)
            option == null || option.groupIndex < 0 -> b.setTrackTypeDisabled(type, false).clearOverridesOfType(type)
            else -> {
                val g = p.currentTracks.groups[option.groupIndex]
                b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, option.trackIndex))
            }
        }
        p.trackSelectionParameters = b.build()
    }
}
