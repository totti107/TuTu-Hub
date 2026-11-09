package com.arena.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.MiniPlayback
import com.arena.tv.app
import com.arena.tv.data.ChannelEntity
import com.arena.tv.ui.live.PreviewPlayer
import com.arena.tv.ui.player.PlayerActivity
import com.arena.tv.ui.theme.ArenaColors

/**
 * TuTu's own picture-in-picture: the channel keeps playing in the corner while you browse.
 * It takes focus when it appears; the remote's Menu / Info / Play-Pause key jumps back to it.
 */
@Composable
fun MiniPlayer(mini: MiniPlayback, focus: FocusRequester, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val ch by produceState<ChannelEntity?>(null, mini.channelId) { value = context.app.db.channels().byId(mini.channelId) }
    LaunchedEffect(mini.channelId) { runCatching { focus.requestFocus() } }
    fun close() { context.app.miniPlayer.value = null }
    fun full() { close(); PlayerActivity.start(context, mini.channelId, mini.ids, mini.title) }
    Column(modifier.width(340.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF0101114))
        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(18.dp))) {
        PreviewPlayer(ch, Modifier.fillMaxWidth().height(191.dp))
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ch?.name ?: "", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp))
            MiniButton(Icons.Rounded.Fullscreen, "Full screen", Modifier.focusRequester(focus), ::full)
            MiniButton(Icons.Rounded.Close, "Close", Modifier, ::close)
        }
    }
}

@Composable
private fun MiniButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(if (focused) Color.White else Color(0x22FFFFFF))
        .onFocusChanged { focused = it.isFocused }.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(20.dp))
        if (focused) { Spacer(Modifier.width(4.dp)); Text(label, color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
}
