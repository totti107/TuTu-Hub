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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.player.PlayerActivity
import com.arena.tv.ui.player.VodPlayerActivity
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline

/** The shows and channels the Reality TV hub looks for in the TV guide. */
object Reality {
    /** Searched in the TV guide (full-text). */
    val shows = listOf(
        "\"love island\"", "kardashian*", "kardashians", "housewives", "bachelor*", "bachelorette", "\"below deck\"", "vanderpump",
        "\"selling sunset\"", "\"selling the oc\"", "\"90 day\"", "\"married at first sight\"", "\"too hot to handle\"", "\"dance moms\"",
        "\"jersey shore\"", "\"teen mom\"", "\"say yes to the dress\"", "\"love is blind\"", "\"summer house\"", "\"southern charm\"",
        "\"the valley\"", "\"made in chelsea\"", "\"only way is essex\"", "towie", "\"geordie shore\"", "\"ex on the beach\"",
        "\"temptation island\"", "\"love after lockup\"", "\"basketball wives\"", "\"love and hip hop\"", "\"little women\"", "\"sister wives\"",
        "\"real world\"", "\"project runway\"", "\"top model\"", "botched", "\"winter house\"", "\"family karma\"", "\"real girlfriends\"",
        "\"bling empire\"", "\"perfect match\"", "\"the circle\"", "\"big brother\"", "\"love triangle\"", "\"marriage boot camp\"",
    )
    val match: String = shows.joinToString(" OR ")

    /** Channel names that are mostly reality / lifestyle. */
    private val channelWords = Regex("""(?i)(^|[^a-z])(bravo|e!|e! entertainment|tlc|lifetime|oxygen|we ?tv|mtv|own|itv ?2|itvbe|hayu|slice|discovery life|e entertainment)([^a-z]|$)""")
    fun isRealityChannel(name: String) = channelWords.containsMatchIn(name)
}

private data class RealityData(val now: List<ShowOnChannel>, val later: List<ShowOnChannel>, val earlier: List<ShowOnChannel>, val channels: List<ChannelEntity>)

/** Home → Reality TV: Love Island, the Kardashians, Housewives & co. — on now, later, and earlier (catch-up). */
@Composable
fun RealityScreen() {
    val context = LocalContext.current
    val app = context.app
    val data by produceState<RealityData?>(null) {
        val now = System.currentTimeMillis()
        val progs = runCatching { app.db.programs().search(Reality.match, now - 24 * 3600_000L, now + 24 * 3600_000L, 3000) }.getOrDefault(emptyList())
        val chans = progs.map { it.epgKey }.distinct().chunked(500).flatMap { app.db.channels().safeByEpgKeys(it) }.groupBy { it.epgKey }
        // One card per showing: the same episode on "Bravo", "Bravo HD" and "Bravo East" shows once (prefer a catch-up channel).
        val shows = progs.mapNotNull { p -> chans[p.epgKey]?.let { list -> ShowOnChannel(p, list.firstOrNull { it.catchupDays > 0 } ?: list.first()) } }
            .groupBy { it.program.title.lowercase() + "|" + it.program.start }.values.map { g -> g.firstOrNull { it.channel.catchupDays > 0 } ?: g.first() }
        val channels = app.db.channels().safeOnce().filter { Reality.isRealityChannel(it.name) }.distinctBy { it.name.lowercase() }.take(60)
        value = RealityData(
            now = shows.filter { it.program.start <= now && it.program.stop > now }.sortedBy { it.program.start },
            later = shows.filter { it.program.start > now }.sortedBy { it.program.start }.take(60),
            earlier = shows.filter { it.program.stop <= now && it.channel.catchupDays > 0 }.sortedByDescending { it.program.start }.take(60),
            channels = channels,
        )
    }
    fun watchLive(ch: ChannelEntity) = PlayerActivity.start(context, ch.id, listOf(ch.id), null)

    val d = data
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp)) {
                Text("Reality TV 💅", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Love Island, the Kardashians, Real Housewives, Below Deck and more — from your TV guide", color = ArenaColors.Muted, fontSize = 14.sp)
            }
        }
        if (d == null) { item { CircularProgressIndicator(Modifier.padding(40.dp)) }; return@LazyColumn }
        if (d.now.isEmpty() && d.later.isEmpty() && d.earlier.isEmpty()) item {
            Text("Nothing from these shows in your TV guide right now. Check the channels below, or reload the TV guide in Live TV.",
                color = ArenaColors.Muted, modifier = Modifier.padding(24.dp))
        }
        item { ShowRail("On now", null, d.now, { "● LIVE · until ${Time.clock(it.program.stop)}" }) { watchLive(it.channel) } }
        item { ShowRail("Watch again", "aired earlier · catch-up", d.earlier, { "⏪ " + airedLabel(it.program.start) }) { s ->
            VodPlayerActivity.startCatchup(context, s.channel.id, CatchupItem(s.program.title, s.program.start, s.program.stop, s.program.description))
        } }
        item { ShowRail("Coming up", "today and tomorrow", d.later, { airedOrLater(it.program.start) }) { watchLive(it.channel) } }
        if (d.channels.isNotEmpty()) item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("Reality channels", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(d.channels, key = { it.id }) { ch ->
                        Row(
                            Modifier.width(220.dp).clip(RoundedCornerShape(14.dp)).background(ArenaColors.Surface)
                                .focusOutline(RoundedCornerShape(14.dp)).clickable { watchLive(ch) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Logo(ch.logo, ch.name, 30.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(ch.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun airedOrLater(start: Long): String {
    val tomorrow = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
    return (if (start >= tomorrow) "Tomorrow " else "Today ") + Time.clock(start)
}
