package com.arena.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.ProgramEntity
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import com.arena.tv.app
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.launch

/** A programme on a channel, as shown on the Catch-up and Reality TV screens. */
data class ShowOnChannel(val program: ProgramEntity, val channel: ChannelEntity)

/** "2 h ago", "Yesterday 9:30 p.m." … for shows that already aired. */
fun airedLabel(start: Long, now: Long = System.currentTimeMillis()): String {
    val mins = (now - start) / 60_000
    val today = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
    return when {
        mins < 60 -> "$mins min ago"
        start >= today -> "Today ${Time.clock(start)}"
        start >= today - 86_400_000L -> "Yesterday ${Time.clock(start)}"
        else -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(start)) + " " + Time.clock(start)
    }
}

/** Section title + a row of show cards. Nothing is drawn when [shows] is empty. */
@Composable
fun ShowRail(title: String, hint: String?, shows: List<ShowOnChannel>, badge: (ShowOnChannel) -> String, onClick: (ShowOnChannel) -> Unit) {
    if (shows.isEmpty()) return
    Column(Modifier.padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            hint?.let { Text("  $it", color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp)) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(shows, key = { "${it.channel.id}|${it.program.start}" }) { s -> ShowCard(s, badge(s)) { onClick(s) } }
        }
    }
}

@Composable
private fun ShowCard(s: ShowOnChannel, badge: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val live = s.program.start <= now && s.program.stop > now
    Column(
        Modifier.width(250.dp).height(132.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(18.dp))
            .background(if (focused) Color.White else ArenaColors.Surface)
            .border(1.dp, if (focused) Color.White else ArenaColors.Outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(14.dp),
    ) {
        val fg = if (focused) Color.Black else ArenaColors.Text
        val muted = if (focused) Color(0xFF444444) else ArenaColors.Muted
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(s.channel.logo, s.channel.name, 26.dp)
            Spacer(Modifier.width(8.dp))
            Text(s.channel.name, color = muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (live) Box(Modifier.size(8.dp).clip(CircleShape).background(ArenaColors.Live))
        }
        Spacer(Modifier.height(8.dp))
        Text(s.program.title, color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
        Spacer(Modifier.weight(1f))
        Text(badge, color = if (focused) Color.Black else if (live) ArenaColors.Live else ArenaColors.Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** One choice in a [ShowActions] sheet. */
data class ShowAction(val label: String, val onClick: () -> Unit)

/** What to do with a show: watch now, remind me, open in the library… (remote and touch friendly). */
@Composable
fun ShowActions(title: String, subtitle: String?, note: String?, actions: List<ShowAction>, onDismiss: () -> Unit) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss, containerColor = ArenaColors.Surface,
        title = {
            Column {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = ArenaColors.Muted, fontSize = 14.sp) }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEachIndexed { i, a ->
                    var focused by remember { mutableStateOf(false) }
                    Text(a.label, color = if (focused) Color.Black else ArenaColors.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(12.dp))
                            .background(if (focused) Color.White else ArenaColors.SurfaceHi).clickable { onDismiss(); a.onClick() }
                            .padding(horizontal = 16.dp, vertical = 14.dp))
                }
                note?.let { Text(it, color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss, modifier = Modifier.focusOutline()) { Text("Close") } })
}

/** Watch / remind choices for a show on one of your channels. */
fun liveActions(context: android.content.Context, s: ShowOnChannel, done: (String) -> Unit): List<ShowAction> {
    val now = System.currentTimeMillis()
    val p = s.program
    val app = context.app
    return buildList {
        add(ShowAction(if (p.start <= now) "▶  Watch now on ${s.channel.name}" else "▶  Watch ${s.channel.name} now (airs ${Time.clock(p.start)})") {
            com.arena.tv.ui.player.PlayerActivity.start(context, s.channel.id, listOf(s.channel.id), null)
        })
        if (p.start > now + 60_000) add(ShowAction("🔔  Remind me at ${Time.clock(p.start)}") {
            kotlinx.coroutines.MainScope().launch {
                runCatching { app.recordings.plan(s.channel, p.title, p.start, p.stop, "remind") }
                    .onSuccess { done("🔔 Reminder set: ${p.title} at ${Time.clock(p.start)} on ${s.channel.name}") }
                    .onFailure { done("Couldn't set the reminder") }
            }
        })
        if (p.stop <= now && s.channel.catchupDays > 0) add(ShowAction("⏪  Watch from the start (catch-up)") {
            com.arena.tv.ui.player.VodPlayerActivity.startCatchup(context, s.channel.id, com.arena.tv.data.CatchupItem(p.title, p.start, p.stop, p.description))
        })
    }
}
