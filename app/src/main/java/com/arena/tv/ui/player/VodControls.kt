package com.arena.tv.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.theme.ArenaColors
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures

/** What the movie player overlay shows. */
data class VodUi(val controls: Boolean = true, val panel: String? = null, val resize: Int = 0, val playing: Boolean = true, val notice: String? = null)

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Movie / episode controls: title, seek bar, and the same button dock as live TV (with CC and audio). */
@Composable
fun VodControls(
    player: Player, ui: VodUi, title: String, subtitle: String?, hasNext: Boolean,
    onBack: () -> Unit, onTogglePlay: () -> Unit, onSeek: (Long) -> Unit, onRestart: () -> Unit, onNext: () -> Unit,
    onPanel: (String) -> Unit, onResize: () -> Unit, touched: () -> Unit, onCycleSubs: () -> Unit = {},
) {
    var pos by remember { mutableLongStateOf(player.currentPosition) }
    var dur by remember { mutableLongStateOf(player.duration) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { pos = player.currentPosition; dur = player.duration; now = System.currentTimeMillis(); delay(500) } }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    var label by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = Color(0xB3FFFFFF), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(Time.clock(now), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000), Color(0xF2000000))))
            .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 20.dp)) {
            // Seek bar. Phone: drag (or tap) anywhere on it. Remote: select it, hold ◀ / ▶ to scrub (it speeds up), OK or a pause jumps there.
            var barFocused by remember { mutableStateOf(false) }
            var scrub by remember { mutableStateOf<Long?>(null) }
            var barW by remember { mutableIntStateOf(1) }
            var dragging by remember { mutableStateOf(false) }
            fun commit() { scrub?.let { onSeek(it - player.currentPosition) }; scrub = null }
            // Remote: jump once the arrows have been left alone for a moment.
            LaunchedEffect(scrub, dragging) { if (scrub != null && !dragging) { delay(900); commit() } }
            fun at(x: Float): Long = if (dur > 0) ((x / barW).coerceIn(0f, 1f) * dur).toLong() else 0L
            val shown = scrub ?: pos
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock(shown), color = if (scrub != null) ArenaColors.Accent else Color.White, fontSize = 14.sp, fontWeight = if (scrub != null) FontWeight.Bold else FontWeight.Normal)
                Box(Modifier.weight(1f).padding(horizontal = 14.dp).height(36.dp)
                    .onSizeChanged { barW = it.width.coerceAtLeast(1) }
                    .onFocusChanged { barFocused = it.isFocused; if (it.isFocused) { label = "Hold ◀ ▶ to move · OK to jump there"; touched() } else if (scrub != null) commit() }
                    .onKeyEvent { e ->
                        val left = e.key == Key.DirectionLeft; val right = e.key == Key.DirectionRight
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        when {
                            e.type == KeyEventType.KeyDown && (left || right) && dur > 0 -> {
                                // Longer holds move faster: 10 s, then 30 s, then 1 min, then 2% of the title per step.
                                val r = e.nativeKeyEvent.repeatCount
                                val step = when { r < 3 -> 10_000L; r < 10 -> 30_000L; r < 20 -> 60_000L; else -> maxOf(60_000L, dur / 50) }
                                scrub = ((scrub ?: player.currentPosition) + if (right) step else -step).coerceIn(0, dur)
                                touched(); true
                            }
                            ok && scrub != null -> { if (e.type == KeyEventType.KeyUp) commit(); touched(); true }
                            else -> false
                        }
                    }
                    .pointerInput(dur) {
                        detectTapGestures { o -> if (dur > 0) { onSeek(at(o.x) - player.currentPosition); touched() } }
                    }
                    .pointerInput(dur) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> dragging = true; scrub = at(o.x); touched() },
                            onDragEnd = { dragging = false; commit(); touched() },
                            onDragCancel = { dragging = false; scrub = null },
                        ) { change, _ -> scrub = at(change.position.x); touched() }
                    }
                    .focusable(), contentAlignment = Alignment.CenterStart) {
                    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    val target = if (dur > 0) (shown.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    val active = barFocused || scrub != null
                    Box(Modifier.fillMaxWidth().height(if (active) 6.dp else 4.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(if (active) Color(0x99FFFFFF) else Color.White))
                        if (scrub != null) Box(Modifier.fillMaxHeight().fillMaxWidth(target).background(ArenaColors.Accent))
                    }
                    if (active) Box(Modifier.fillMaxWidth(target), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.size(if (scrub != null) 20.dp else 16.dp).clip(CircleShape).background(Color.White))
                    }
                }
                Text(if (dur > 0) "-" + clock(dur - shown) else "--:--", color = Color.White, fontSize = 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label ?: " ", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
                Row(Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xD9141518)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(22.dp)).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    fun t(f: () -> Unit): () -> Unit = { touched(); f() }
                    val focus: (String) -> Unit = { label = it; touched() }
                    Dock(Icons.Rounded.Replay, "Start over", focus, onClick = t(onRestart))
                    Dock(Icons.Rounded.Replay10, "Back 10 seconds", focus, onClick = t { onSeek(-10_000) })
                    Dock(if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (ui.playing) "Pause" else "Play", focus, primary = true,
                        modifier = Modifier.focusRequester(playFocus), onClick = t(onTogglePlay))
                    Dock(Icons.Rounded.Forward10, "Forward 10 seconds", focus, onClick = t { onSeek(10_000) })
                    if (hasNext) Dock(Icons.Rounded.SkipNext, "Next episode", focus, onClick = t(onNext))
                    DockDivider()
                    DockText("CC", "Subtitles: press to switch", focus, onClick = t(onCycleSubs))
                    Dock(Icons.Rounded.Tune, "Subtitle options & AI translation", focus, onClick = t { onPanel("subs") })
                    Dock(Icons.Rounded.GraphicEq, "Audio track", focus, onClick = t { onPanel("audio") })
                    Dock(Icons.Rounded.AspectRatio, "Zoom", focus, onClick = t(onResize))
                }
            }
        }
    }
}

/** Side panel: subtitles (with translation) or audio tracks. */
@Composable
fun VodPanel(app: com.arena.tv.AppContainer, player: Player, panel: String?, onClose: () -> Unit, onPick: (Int, TrackOption?) -> Unit, onTranslate: (String) -> Unit) {
    AnimatedVisibility(panel != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.width(380.dp).fillMaxHeight().background(Color(0xF00B0B0D)).padding(vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (panel == "audio") "Audio" else "Subtitles", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                    Text("Close", color = ArenaColors.Accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClose).padding(8.dp))
                }
                if (panel == "subs") {
                    val options = remember(panel) { TrackTools.list(player, C.TRACK_TYPE_TEXT) }
                    SubsList(app, options, "This title", { onPick(C.TRACK_TYPE_TEXT, it) }, onTranslate)
                } else if (panel == "audio") {
                    val options = remember(panel) { TrackTools.list(player, C.TRACK_TYPE_AUDIO) }
                    LazyColumn {
                        item { PanelRow("Automatic", selected = false) { onPick(C.TRACK_TYPE_AUDIO, null) } }
                        if (options.isEmpty()) item { Text("This title has only one audio track.", color = ArenaColors.Muted, modifier = Modifier.padding(16.dp)) }
                        items(options) { o -> PanelRow(o.label, selected = o.selected) { onPick(C.TRACK_TYPE_AUDIO, o) } }
                    }
                }
            }
        }
    }
}
