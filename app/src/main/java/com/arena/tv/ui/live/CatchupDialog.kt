package com.arena.tv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arena.tv.AppContainer
import com.arena.tv.data.CatchupItem
import com.arena.tv.data.ChannelEntity
import com.arena.tv.ui.player.VodPlayerActivity
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import com.arena.tv.ui.components.Time
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Small "catch-up" mark next to a channel's name. */
@Composable
fun CatchupMark(ch: ChannelEntity, modifier: Modifier = Modifier) {
    if (ch.catchupDays > 0) Icon(Icons.Rounded.History, "Catch-up", tint = ArenaColors.Blue, modifier = modifier.size(15.dp))
}

/** Pick a day, then a programme that already aired, and watch it from the start. */
@Composable
fun CatchupDialog(app: AppContainer, ch: ChannelEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val days = remember(ch.id) {
        val fmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
        (0 until ch.catchupDays.coerceIn(1, 14)).map { back ->
            val c = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -back); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val label = when (back) { 0 -> "Today"; 1 -> "Yesterday"; else -> fmt.format(c.time) }
            Triple(label, c.timeInMillis, c.timeInMillis + 86_400_000L)
        }
    }
    var day by remember(ch.id) { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<CatchupItem>?>(null) }
    LaunchedEffect(ch.id, day) {
        items = null
        val (_, from, to) = days[day]
        items = runCatching { app.catchup.programs(ch, from, to) }.getOrDefault(emptyList())
    }
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(items) {
        val l = items ?: return@LaunchedEffect
        // Today: start near the end (the latest programmes); other days: from the morning.
        if (l.isNotEmpty() && day == 0) listState.scrollToItem((l.size - 1).coerceAtLeast(0))
        runCatching { firstFocus.requestFocus() }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(0.92f).fillMaxHeight(0.86f).clip(RoundedCornerShape(24.dp))
                    .background(ArenaColors.Surface).border(1.dp, ArenaColors.Outline, RoundedCornerShape(24.dp)).padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.History, null, tint = ArenaColors.Blue, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Catch-up · ${ch.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Watch something that already aired (last ${ch.catchupDays} day${if (ch.catchupDays == 1) "" else "s"})",
                            color = ArenaColors.Muted, fontSize = 13.sp)
                    }
                    Text("Close", color = ArenaColors.Muted, modifier = Modifier.clip(RoundedCornerShape(10.dp)).focusOutline().clickable(onClick = onDismiss).padding(10.dp))
                }
                LazyRow(contentPadding = PaddingValues(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(days) { i, (label, _, _) ->
                        FilterChip(selected = i == day, onClick = { day = i }, label = { Text(label) }, modifier = Modifier.focusOutline())
                    }
                }
                val l = items
                when {
                    l == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    l.isEmpty() -> Text(
                        if (ch.epgKey == null && !ch.catchup.startsWith("stk") && !ch.catchup.startsWith("xc")) "This channel has catch-up, but no TV guide to list its programmes."
                        else "Nothing recorded for this day.", color = ArenaColors.Muted, modifier = Modifier.padding(16.dp))
                    else -> LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(l, key = { _, it -> it.start }) { i, p ->
                            val target = if (day == 0) l.lastIndex else 0
                            ProgrammeRow(p, if (i == target) Modifier.focusRequester(firstFocus) else Modifier) {
                                onDismiss(); VodPlayerActivity.startCatchup(context, ch.id, p)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgrammeRow(p: CatchupItem, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color.White else ArenaColors.SurfaceHi).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (focused) Color.Black else ArenaColors.Text
        Text("${Time.clock(p.start)} – ${Time.clock(p.stop)}", color = if (focused) Color.Black else ArenaColors.Muted, fontSize = 14.sp, modifier = Modifier.width(120.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, color = fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            p.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = if (focused) Color(0xFF333333) else ArenaColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Rounded.PlayArrow, "Watch", tint = if (focused) Color.Black else ArenaColors.Blue)
    }
}
