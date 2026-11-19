package com.arena.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.app
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import kotlinx.coroutines.delay

/** A reminder that's due, shown on screen (TVs don't pop notifications up over the app). */
data class DueReminder(val channelId: String, val title: String, val text: String)

/** "Starting now: Southern Charm on Bravo — Watch / Dismiss", in the corner, over whatever is on screen. */
@Composable
fun ReminderPopup() {
    val context = LocalContext.current
    val app = context.app
    val due by app.dueReminder.collectAsState()
    val r = due ?: return
    val watch = remember(r) { FocusRequester() }
    LaunchedEffect(r) { runCatching { watch.requestFocus() }; delay(60_000); if (app.dueReminder.value == r) app.dueReminder.value = null }
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopEnd) {
        Column(Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xF2202125))
            .border(1.dp, ArenaColors.Gold, RoundedCornerShape(20.dp)).padding(18.dp)) {
            Text("🔔 ${r.title}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
            Text(r.text, color = ArenaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    app.dueReminder.value = null
                    com.arena.tv.ui.player.PlayerActivity.start(context, r.channelId, listOf(r.channelId), null)
                }, modifier = Modifier.focusRequester(watch).focusOutline()) { Text("▶ Watch") }
                OutlinedButton(onClick = { app.dueReminder.value = null }, modifier = Modifier.focusOutline()) { Text("Dismiss") }
            }
        }
    }
}
