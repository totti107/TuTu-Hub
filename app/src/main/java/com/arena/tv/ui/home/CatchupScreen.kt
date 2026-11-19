package com.arena.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.app
import com.arena.tv.data.CatchupItem
import com.arena.tv.data.ChannelEntity
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.live.CatchupDialog
import com.arena.tv.ui.player.VodPlayerActivity
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import kotlinx.coroutines.flow.first

private data class CatchupHome(val favorites: List<ShowOnChannel>, val recent: List<ShowOnChannel>, val channels: Map<String, List<ChannelEntity>>)

/** Home → Catch-up: what aired today on your channels, and every channel that has catch-up. */
@Composable
fun CatchupScreen() {
    val context = LocalContext.current
    val app = context.app
    var open by remember { mutableStateOf<ChannelEntity?>(null) }
    val data by produceState<CatchupHome?>(null) {
        val now = System.currentTimeMillis()
        val all = app.db.channels().catchupChannels()
        val ids = all.map { it.id }.toSet()
        fun aired(chs: List<ChannelEntity>): suspend () -> List<ShowOnChannel> = {
            val byKey = chs.filter { it.epgKey != null }.groupBy { it.epgKey!! }
            if (byKey.isEmpty()) emptyList() else byKey.keys.toList().chunked(500).flatMap { app.db.programs().forKeys(it, now - 24 * 3600_000L, now) }
                .filter { it.stop <= now }
                .mapNotNull { p -> byKey[p.epgKey]?.firstOrNull()?.let { ShowOnChannel(p, it) } }
                .sortedByDescending { it.program.start }.distinctBy { it.program.title + it.channel.id }.take(40)
        }
        val favs = app.db.channels().favoritesOnce().filter { it.id in ids }
        val recent = app.db.channels().recents().first().filter { it.id in ids && favs.none { f -> f.id == it.id } }
        value = CatchupHome(aired(favs)(), aired(recent)(), all.groupBy { it.groupTitle })
    }
    open?.let { ch -> CatchupDialog(app, ch) { open = null } }
    fun play(s: ShowOnChannel) = VodPlayerActivity.startCatchup(context, s.channel.id, CatchupItem(s.program.title, s.program.start, s.program.stop, s.program.description))

    val d = data
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, null, tint = ArenaColors.Blue, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Catch-up", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("Watch shows that already aired, from the start", color = ArenaColors.Muted, fontSize = 14.sp)
                }
            }
        }
        if (d == null) { item { CircularProgressIndicator(Modifier.padding(40.dp)) }; return@LazyColumn }
        if (d.channels.isEmpty()) item {
            Text("None of your channels has catch-up yet. If your provider offers it, press Reload in Live TV so the app can see it.",
                color = ArenaColors.Muted, modifier = Modifier.padding(24.dp))
        }
        item { ShowRail("From your favorites", "aired in the last day", d.favorites, { airedLabel(it.program.start) }, ::play) }
        item { ShowRail("Channels you watched", null, d.recent, { airedLabel(it.program.start) }, ::play) }
        if (d.channels.isNotEmpty()) item {
            Text("All catch-up channels", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 4.dp))
        }
        d.channels.forEach { (group, chs) ->
            item(key = "g-$group") {
                Column(Modifier.padding(top = 10.dp)) {
                    Text("$group · ${chs.size}", color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 24.dp, bottom = 6.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(chs, key = { it.id }) { ch ->
                            Row(
                                Modifier.width(230.dp).clip(RoundedCornerShape(14.dp)).background(ArenaColors.Surface)
                                    .focusOutline(RoundedCornerShape(14.dp)).clickable { open = ch }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Logo(ch.logo, ch.name, 30.dp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ch.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                                    Text("${ch.catchupDays} day${if (ch.catchupDays == 1) "" else "s"} back", color = ArenaColors.Muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
