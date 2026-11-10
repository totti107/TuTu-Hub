package com.arena.tv.ui.player

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.foundation.border
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.arena.tv.app
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.ProgramEntity
import com.arena.tv.ui.theme.focusOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import com.arena.tv.sports.Game
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.theme.ArenaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Full-screen live player with the control bar (quality badges, now/next, and the row of buttons). */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(activity: PlayerActivity, player: ExoPlayer, state: PlayerUi, zapIds: List<String>) {
    val lastTouch = activity.lastInput.longValue
    fun poke() { activity.showControls(true); activity.lastInput.longValue = System.currentTimeMillis() }

    LaunchedEffect(state.channel?.id) { poke() }
    val prefs by activity.app.prefs.collectAsState()
    val translating = prefs.subtitleTranslate.isNotEmpty()
    val translateStatus by activity.app.translator.status.collectAsState()
    // Hide the controls 5 seconds after the last button press or move (not while a panel is open).
    LaunchedEffect(lastTouch, state.controls, state.panel) {
        if (state.controls && state.panel == null) { delay(5000); activity.showControls(false) }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { if (state.controls) activity.showControls(false) else poke() }, onDoubleTap = { activity.resize() }) }
            .pointerInput(zapIds.size) {
                var total = 0f
                detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (total < -120) activity.next() else if (total > 120) activity.prev() }) { _, dy -> total += dy }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(false)
                    subtitleView?.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Streams.subtitleScale(ctx.app.prefs.value))
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                }
            },
            update = {
                // Translated subtitles are drawn by TuTu, so the originals are hidden.
                it.subtitleView?.visibility = if (translating) android.view.View.GONE else android.view.View.VISIBLE
                it.resizeMode = when (state.resize) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (translating && !state.inPip) TranslatedSubtitleText(activity.subs?.text?.value, translateStatus, Streams.subtitleScale(prefs), state.controls)

        if (state.buffering && state.error == null) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                if (state.retrying && !state.inPip) { Spacer(Modifier.height(8.dp)); Text("Reconnecting…", color = Color.White, fontSize = 13.sp) }
            }
        }
        state.error?.let { err ->
            Column(Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xCC111827)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(err, color = Color.White, textAlign = TextAlign.Center)
                if (!state.inPip) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = activity::retry) { Text("Retry") }
                    if (state.total > 1) OutlinedButton(onClick = activity::next) { Text("Next channel") }
                }
            }
        }

        if (!state.inPip && !state.controls && LocalContext.current.app.prefs.value.clockAlways) CornerClock(Modifier.align(Alignment.TopEnd))
        if (!state.inPip && !state.controls && state.panel == null && state.scores.isNotEmpty())
            ScoreOverlay(state.scores, state.goals, LocalContext.current.app.prefs.value.scoreStyle == "compact", Modifier.align(Alignment.TopStart))
        if (!state.inPip) {
            AnimatedVisibility(state.controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Controls(activity, state) { activity.lastInput.longValue = System.currentTimeMillis() }
            }
            AnimatedVisibility(state.panel != null, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
                modifier = Modifier.align(Alignment.CenterEnd)) {
                Panel(activity, state, zapIds)
            }
        }
    }
}

// ------------------------------------------------------------ overlay

@Composable
private fun Controls(a: PlayerActivity, s: PlayerUi, touched: () -> Unit) {
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); now = System.currentTimeMillis() } }

    Box(Modifier.fillMaxSize()) {
        // Top: brand + quality badges, clock on the right.
        Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent))).padding(horizontal = 28.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("tutu", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Box(Modifier.padding(start = 2.dp, bottom = 14.dp).size(7.dp).clip(CircleShape).background(ArenaColors.Accent))
            Spacer(Modifier.width(18.dp))
            s.badges.forEach { b -> Badge(b) }
            s.sleepAt?.let { Badge("☾ ${((it - now) / 60_000).coerceAtLeast(0) + 1} min") }
            Spacer(Modifier.weight(1f))
            Text(Time.clock(now), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }

        // Bottom: now / up next, progress, channel + the button dock.
        var label by remember { mutableStateOf<String?>(null) }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000), Color(0xF2000000))))
            .padding(start = 32.dp, end = 32.dp, top = 56.dp, bottom = 20.dp)) {
            val cur = s.now
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("NOW", color = ArenaColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Text(cleanTitle(cur?.title) ?: s.channel?.name ?: "", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    cur?.let { Text("Started at ${Time.clock(it.start)}", color = Color(0xB3FFFFFF), fontSize = 14.sp) }
                }
                Spacer(Modifier.width(24.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 440.dp)) {
                    s.next?.let {
                        Text("UP NEXT", color = Color(0x99FFFFFF), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Text(cleanTitle(it.title) ?: "", color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    cur?.let { Text("Ends ${Time.clock(it.stop)}", color = Color(0xB3FFFFFF), fontSize = 14.sp) }
                }
            }
            Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp).height(4.dp).clip(CircleShape).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(cur?.let { Time.progress(it.start, it.stop) } ?: 0f).clip(CircleShape).background(Color.White))
            }

            Row(verticalAlignment = Alignment.Bottom) {
                // Channel
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    s.channel?.let { Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x1FFFFFFF)).padding(5.dp)) { Logo(it.logo, it.name, 38.dp) } }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.widthIn(max = 260.dp)) {
                        Text("${s.index + 1}  ${s.channel?.name ?: ""}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = a::goLive)) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.behindLive == null) ArenaColors.Live else Color(0x99FFFFFF)))
                            Text(if (s.behindLive == null) "  LIVE" else "  -${s.behindLive}s · go live", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Spacer(Modifier.width(20.dp))
                // Button dock, with the focused button's name above it.
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label ?: " ", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
                    Row(
                        Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xD9141518)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(22.dp))
                            .horizontalScroll(rememberScrollState()).padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        fun t(f: () -> Unit): () -> Unit = { touched(); f() }
                        val focus: (String) -> Unit = { label = it; touched() }
                        Dock(Icons.Rounded.Replay10, "Back 10 seconds", focus, enabled = s.seekable, onClick = t { a.seekBy(-10_000) })
                        Dock(if (s.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (s.playing) "Pause" else "Play", focus, primary = true,
                            modifier = Modifier.focusRequester(playFocus), onClick = t(a::togglePlay))
                        Dock(Icons.Rounded.Forward10, "Forward 10 seconds", focus, enabled = s.seekable, onClick = t { a.seekBy(10_000) })
                        DockDivider()
                        Dock(Icons.Rounded.FastRewind, "Previous channel", focus, onClick = t(a::lastChannel))
                        Dock(Icons.AutoMirrored.Rounded.List, "Channels", focus, onClick = t { a.setPanel("channels") })
                        DockText("EPG", "TV guide", focus, onClick = t { a.setPanel("epg") })
                        Dock(Icons.Rounded.EmojiEvents, "Live scores", focus, onClick = t { a.setPanel("scores") })
                        Dock(if (s.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (s.favorite) "Remove from favorites" else "Add to favorites", focus,
                            tint = if (s.favorite) ArenaColors.Gold else Color.White, onClick = t(a::favorite))
                        Dock(Icons.Rounded.FiberManualRecord, if (s.recording) "Stop recording" else "Record", focus,
                            tint = if (s.recording) ArenaColors.Live else Color.White, onClick = t { a.toggleRecord() })
                        Dock(Icons.Rounded.History, "Recently watched", focus, onClick = t { a.setPanel("recent") })
                        DockDivider()
                        DockText("CC", "Subtitles", focus, onClick = t { a.setPanel("subs") })
                        Dock(Icons.Rounded.GraphicEq, "Audio track", focus, onClick = t { a.setPanel("audio") })
                        DockText(s.badges.firstOrNull { it in setOf("4K", "FHD", "HD", "SD") } ?: "Auto", "Video quality", focus, onClick = t { a.setPanel("quality") })
                        Dock(Icons.Rounded.AspectRatio, "Zoom", focus, onClick = t(a::resize))
                        DockDivider()
                        Dock(Icons.Rounded.Cast, if (s.casting != null) "Casting to ${s.casting}" else "Cast", focus,
                            tint = if (s.casting != null) ArenaColors.Accent else Color.White, onClick = t(a::cast))
                        Dock(Icons.Rounded.PictureInPictureAlt, "Mini player", focus, onClick = t(a::pip))
                        Dock(Icons.Rounded.Bedtime, "Sleep timer", focus, tint = if (s.sleepAt != null) ArenaColors.Accent else Color.White, onClick = t { a.setPanel("sleep") })
                    }
                }
            }
        }
    }
}

/** Guide titles sometimes carry "ᴸᶦᵛᵉ" / "ᴺᵉʷ" tags: shown as plain text instead. */
private fun cleanTitle(t: String?): String? = t?.replace(Regex("""\s*[\u1D2C-\u1D6A\u1D9C-\u1DBF\u02B0-\u02FF\u2070-\u209F]{2,}\s*"""), " ")?.trim()?.ifEmpty { t }

@Composable
internal fun DockDivider() = Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(28.dp).background(Color(0x33FFFFFF)))

@Composable
internal fun Dock(icon: ImageVector, desc: String, onFocus: (String) -> Unit, modifier: Modifier = Modifier, primary: Boolean = false,
                 enabled: Boolean = true, tint: Color = Color.White, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when { focused -> Color.White; primary -> Color(0x33FFFFFF); else -> Color.Transparent }
    Box(
        modifier.size(if (primary) 54.dp else 48.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus(desc) }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = when { focused -> Color.Black; !enabled -> Color(0x55FFFFFF); else -> tint }, modifier = Modifier.size(if (primary) 30.dp else 26.dp))
    }
}

@Composable
internal fun DockText(text: String, desc: String, onFocus: (String) -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(Modifier.height(48.dp).widthIn(min = 48.dp).clip(RoundedCornerShape(16.dp)).background(if (focused) Color.White else Color.Transparent)
        .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus(desc) }
        .clickable(onClick = onClick).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (focused) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
private fun Badge(text: String) {
    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(end = 8.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x55FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp))
}



// ------------------------------------------------------------ side panels

@Composable
private fun Panel(a: PlayerActivity, s: PlayerUi, ids: List<String>) {
    val title = when (s.panel) {
        "channels" -> "Channels"; "epg" -> "TV guide"; "recent" -> "Recently watched"; "scores" -> "Live scores"
        "audio" -> "Audio"; "subs" -> "Subtitles"; "quality" -> "Video quality"; "sleep" -> "Sleep timer"; "cast" -> "Cast"; else -> ""
    }
    Column(Modifier.width(380.dp).fillMaxHeight().background(Color(0xF00B0B0D)).padding(vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
            Text("Close", color = ArenaColors.Accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { a.setPanel(null) }.padding(8.dp))
        }
        when (s.panel) {
            "channels" -> ChannelsPanel(a, ids, s.index)
            "recent" -> RecentPanel(a)
            "epg" -> EpgPanel(a, s.channel)
            "scores" -> ScoresPanel(a)
            "audio" -> TrackPanel(a, C.TRACK_TYPE_AUDIO, autoLabel = "Automatic")
            "subs" -> SubsPanel(a)
            "quality" -> TrackPanel(a, C.TRACK_TYPE_VIDEO, autoLabel = "Auto (best for your connection)")
            "sleep" -> SleepPanel(a, s.sleepAt)
            "cast" -> Column {
                PanelRow(if (s.casting != null) "Stop casting to ${s.casting}" else "Chromecast / Google TV",
                    "Plays this channel on a TV with Chromecast built in") { a.castToDevice() }
                PanelRow("Open in another app", "VLC, Web Video Caster, MX Player… (they can cast too)") { a.openElsewhere() }
                Text("Some providers only allow their streams on one device or need special settings; if a channel won't cast, try another app.",
                    color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Composable
internal fun PanelRow(label: String, sub: String? = null, selected: Boolean = false, logo: ChannelEntity? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(12.dp))
        .background(if (selected) ArenaColors.AccentDim else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        logo?.let { Logo(it.logo, it.name, 32.dp); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, color = ArenaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (selected) Text("✓", color = ArenaColors.Accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ChannelsPanel(a: PlayerActivity, ids: List<String>, current: Int) {
    val channels by produceState<List<ChannelEntity>>(emptyList(), ids) {
        val map = a.app.db.channels().byIds(ids.take(900)).associateBy { it.id }
        value = ids.mapNotNull { map[it] }
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    LazyColumn(state = state) {
        itemsIndexed(channels, key = { _, c -> c.id }) { i, ch -> PanelRow("${i + 1}  ${ch.name}", selected = i == current, logo = ch) { a.pick(ids.indexOf(ch.id)) } }
    }
}

@Composable
private fun RecentPanel(a: PlayerActivity) {
    val recents by produceState<List<ChannelEntity>>(emptyList()) { value = a.app.db.channels().recents().first() }
    LazyColumn { items(recents, key = { it.id }) { ch -> PanelRow(ch.name, ch.groupTitle, logo = ch) { a.playChannel(ch.id) } } }
}

@Composable
private fun EpgPanel(a: PlayerActivity, ch: ChannelEntity?) {
    val now = System.currentTimeMillis()
    val schedule by produceState<List<ProgramEntity>>(emptyList(), ch?.id) {
        value = ch?.epgKey?.let { a.app.db.programs().schedule(it, now - 3 * 3600_000L, now + 18 * 3600_000L) } ?: emptyList()
    }
    if (ch?.epgKey == null) { Text("No TV guide for this channel.", color = ArenaColors.Muted, modifier = Modifier.padding(16.dp)); return }
    // Tap a show: record it or get a reminder (Planner).
    val planned by a.app.recordings.items.collectAsState()
    val marks = planned.filter { it.channelId == ch.id && it.status in setOf("scheduled", "recording") }
        .associate { r -> (if (r.kind == "record") r.start + a.app.prefs.value.planEarly * 60_000L else r.start) to if (r.kind == "record") "● REC" else "⏰" }
    var picked by remember { mutableStateOf<ProgramEntity?>(null) }
    Box(Modifier.padding(horizontal = 8.dp)) { com.arena.tv.ui.live.ScheduleList(ch.id, schedule, now, marks) { picked = it } }
    picked?.let { p ->
        val live = p.start <= System.currentTimeMillis()
        AlertDialog(onDismissRequest = { picked = null }, containerColor = ArenaColors.Surface,
            title = { Text(p.title) },
            text = { Text("${Time.range(p.start, p.stop)} on ${ch.name}", color = ArenaColors.Muted) },
            confirmButton = { Button(onClick = { a.planShow(p, "record"); picked = null }, modifier = Modifier.focusOutline()) { Text(if (live) "Record from now" else "Record") } },
            dismissButton = { if (!live) TextButton(onClick = { a.planShow(p, "remind"); picked = null }, modifier = Modifier.focusOutline()) { Text("Remind me") } })
    }
}

@Composable
private fun ScoresPanel(a: PlayerActivity) {
    // Live games right now; picking one switches to the best channel showing it.
    val games by produceState<List<Game>?>(null) {
        val cached = a.app.sports.cache.values.filter { it.isLive }
        value = cached.ifEmpty {
            val s = a.app.settings.current()
            runCatching { a.app.sports.games(com.arena.tv.sports.Leagues.all.filter { it.key in s.enabledLeagues }, com.arena.tv.sports.DayOffset.TODAY).first }
                .getOrDefault(emptyList()).filter { it.isLive }
        }.sortedBy { com.arena.tv.sports.Leagues.all.indexOf(it.league) }
    }
    when {
        games == null -> Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { CircularProgressIndicator() }
        games!!.isEmpty() -> Text("No live games right now.", color = ArenaColors.Muted, modifier = Modifier.padding(16.dp))
        else -> LazyColumn {
            items(games!!, key = { it.id }) { g ->
                val score = if (g.headToHead) "${g.home.shortName} ${g.home.score ?: 0} – ${g.away.score ?: 0} ${g.away.shortName}" else g.title ?: g.league.name
                PanelRow(score, "${g.league.sport.emoji} ${g.league.shortName} · ${g.statusText}") { a.watchGame(g) }
            }
        }
    }
}

@Composable
private fun TrackPanel(a: PlayerActivity, type: Int, autoLabel: String) {
    val options = remember(type) { a.tracks(type) }
    LazyColumn {
        item { PanelRow(autoLabel, selected = type == C.TRACK_TYPE_TEXT && options.none { it.selected }) { a.selectTrack(type, null) } }
        if (options.isEmpty()) item { Text("This stream has only one option.", color = ArenaColors.Muted, modifier = Modifier.padding(16.dp)) }
        items(options) { o -> PanelRow(o.label, selected = o.selected) { a.selectTrack(type, o) } }
    }
}

@Composable
private fun SleepPanel(a: PlayerActivity, at: Long?) {
    LazyColumn {
        item { PanelRow("Off", selected = at == null) { a.setSleep(null); a.setPanel(null) } }
        items(listOf(15, 30, 45, 60, 90, 120)) { m -> PanelRow("$m minutes") { a.setSleep(m); a.setPanel(null) } }
    }
}


/** Settings → Playback → "Clock always visible": the time in the corner while you watch. */
@Composable
fun CornerClock(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); now = System.currentTimeMillis() } }
    Text(Time.clock(now), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(18.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x88000000)).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Live scores in the top-left corner while watching. A goal lights the score up green. */
@Composable
fun ScoreOverlay(games: List<Game>, goals: Set<String>, compact: Boolean, modifier: Modifier = Modifier) {
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(games.size, compact) { i = 0; while (compact && games.size > 1) { delay(7_000); i = (i + 1) % games.size } }
    val shown = if (compact) listOfNotNull(games.getOrNull(i % maxOf(1, games.size))) else games
    Column(modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { g -> ScorePill(g, g.id in goals) }
    }
}

@Composable
private fun ScorePill(g: Game, goal: Boolean) {
    val bg = if (goal) ArenaColors.Accent else Color(0xD90B0B0D)
    val fg = if (goal) Color.Black else Color.White
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (g.headToHead) {
            com.arena.tv.ui.components.Logo(g.home.logo, g.home.abbreviation, 22.dp, round = true)
            Text(" ${g.home.abbreviation.ifBlank { g.home.shortName }}", color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("  ${g.home.score ?: 0} – ${g.away.score ?: 0}  ", color = fg, fontWeight = FontWeight.Black, fontSize = 16.sp)
            Text("${g.away.abbreviation.ifBlank { g.away.shortName }} ", color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            com.arena.tv.ui.components.Logo(g.away.logo, g.away.abbreviation, 22.dp, round = true)
        } else Text(g.title ?: g.league.shortName, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1)
        Text(if (goal) "  GOAL" else "  ${if (g.state == com.arena.tv.sports.GameState.POST) "FT" else g.statusText}",
            color = if (goal) Color.Black else if (g.isLive) ArenaColors.Accent else Color(0xAAFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** CC panel: the stream's subtitle tracks, plus on-device AI translation into your language. */
@Composable
private fun SubsPanel(a: PlayerActivity) {
    val options = remember { a.tracks(C.TRACK_TYPE_TEXT) }
    SubsList(a.app, options, "This channel", { a.selectTrack(C.TRACK_TYPE_TEXT, it) }) { a.setTranslate(it) }
}

/** Subtitle tracks + AI translation choices (live channels and movies share it). */
@Composable
internal fun SubsList(app: com.arena.tv.AppContainer, options: List<TrackOption>, what: String, onPick: (TrackOption?) -> Unit, onTranslate: (String) -> Unit) {
    val prefs by app.prefs.collectAsState()
    val downloading by app.translator.downloading.collectAsState()
    LazyColumn {
        item {
            Text("SUBTITLES", color = ArenaColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 4.dp))
        }
        if (options.isEmpty()) item {
            Text(if (prefs.subtitleTranslate.isNotEmpty()) "$what has no subtitles or captions, so there's nothing to translate here."
                else "$what has no subtitles or captions.", color = ArenaColors.Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        } else {
            item { PanelRow("Off", selected = options.none { it.selected }) { onPick(null) } }
            items(options) { o -> PanelRow(o.label, selected = o.selected) { onPick(o) } }
        }
        item {
            Text("TRANSLATE (AI, ON THIS DEVICE)", color = ArenaColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
        }
        downloading?.let { d -> item {
            Text("⬇ Downloading ${com.arena.tv.data.SubtitleTranslator.name(d)}… (one time)", color = ArenaColors.Accent, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        } }
        item { PanelRow("Don't translate", selected = prefs.subtitleTranslate.isEmpty()) { onTranslate("") } }
        items(com.arena.tv.data.SubtitleTranslator.LANGS) { (code, name) ->
            PanelRow("Translate to $name", selected = prefs.subtitleTranslate == code) { onTranslate(code) }
        }
    }
}
