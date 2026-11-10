package com.arena.tv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arena.tv.data.ChannelEntity
import com.arena.tv.matching.Text
import com.arena.tv.matching.Tier
import com.arena.tv.sports.DayOffset
import com.arena.tv.sports.Game
import com.arena.tv.sports.GameDetails
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Leagues
import com.arena.tv.sports.Sport
import com.arena.tv.ui.components.BrandPill
import com.arena.tv.ui.components.DropChip
import com.arena.tv.ui.components.IconAction
import com.arena.tv.ui.components.LiveDot
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.components.PaneItem
import com.arena.tv.ui.components.PaneLabel
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.components.panel
import com.arena.tv.ui.live.ChannelCard
import com.arena.tv.ui.live.PreviewPlayer
import com.arena.tv.ui.player.PlayerActivity
import com.arena.tv.ui.theme.ArenaColors
import kotlinx.coroutines.delay

/**
 * Sports Mode (tablets / TVs): leagues on the left, the selected match with Score / Lineups / Stats / Events / Table
 * in the middle, and a live preview with the channels showing it on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SportsMode(hub: SportsHubViewModel, mc: MatchCentreViewModel, header: @Composable () -> Unit, onOpenGame: (Game) -> Unit) {
    val state by hub.state.collectAsStateWithLifecycle()
    val favTeams by hub.favoriteTeams.collectAsStateWithLifecycle()
    val filter by mc.filter.collectAsStateWithLifecycle()
    val selected by mc.selected.collectAsStateWithLifecycle()
    val preview by mc.preview.collectAsStateWithLifecycle()
    var leagueQuery by remember { mutableStateOf("") }
    val compact = com.arena.tv.ui.components.LocalCompactChrome.current
    var sportSheet by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val q = Text.normalize(state.query)
    val bySport = state.games.filter { state.sport == null || it.league.sport == state.sport }
        .filter { q.isEmpty() || Text.normalize(it.headline + " " + it.league.name).contains(q) }
    val shown = when (val f = filter) {
        SportsFilter.LiveNow -> bySport.filter { it.isLive }
        is SportsFilter.League -> bySport.filter { it.league.key == f.key }
        else -> bySport
    }
    val favKeys = favTeams.map { it.key }.toSet()
    // Keep a match selected: the followed / live / next one by default; refresh it when scores update.
    LaunchedEffect(shown) {
        val cur = selected?.let { s -> state.games.firstOrNull { it.id == s.id } }
        when {
            cur != null -> mc.select(cur)
            shown.isNotEmpty() -> mc.select(featured(shown, favKeys, state.channelCounts).first())
        }
    }
    LaunchedEffect(filter) { if (filter == SportsFilter.SportsChannels) mc.loadSportsChannels() }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---------------- left: leagues ----------------
            Column(Modifier.weight(0.24f).fillMaxHeight().panel().padding(10.dp)) {
                header()
                com.arena.tv.ui.components.TvTextField(leagueQuery, { leagueQuery = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    placeholder = { Text("Search leagues") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                val leagues = bySport.groupBy { it.league }.entries
                    .filter { leagueQuery.isBlank() || it.key.name.contains(leagueQuery, true) }
                    .sortedBy { e -> Leagues.all.indexOf(e.key) }
                LazyColumn {
                    item { PaneItem("All today", bySport.size, filter == SportsFilter.AllToday, { mc.filter.value = SportsFilter.AllToday }) }
                    item { PaneItem("Live now", bySport.count { it.isLive }, filter == SportsFilter.LiveNow, { mc.filter.value = SportsFilter.LiveNow }) }
                    item { PaneItem("Sports channels", null, filter == SportsFilter.SportsChannels, { mc.filter.value = SportsFilter.SportsChannels }) }
                    item { PaneLabel(state.sport?.label ?: "All sports") }
                    items(leagues.toList(), key = { it.key.key }) { (league, games) ->
                        val sel = filter == SportsFilter.League(league.key)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (sel) ArenaColors.SurfaceHi else Color.Transparent)
                                .clickable { mc.filter.value = SportsFilter.League(league.key) }.padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Logo(games.firstNotNullOfOrNull { it.leagueLogo }, league.name, if (compact) 20.dp else 28.dp, round = true)
                            Spacer(Modifier.width(10.dp))
                            Text(league.name, Modifier.weight(1f), fontSize = if (compact) 14.sp else 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${games.size}", color = ArenaColors.Muted, fontSize = 14.sp)
                        }
                    }
                }
            }

            // ---------------- centre: match ----------------
            Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (filter == SportsFilter.SportsChannels) {
                    val chans by mc.sportsChannels.collectAsStateWithLifecycle()
                    LazyColumn(Modifier.fillMaxSize().panel(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (chans.isEmpty()) item { Text("No sports categories found in your playlists.", color = ArenaColors.Muted, modifier = Modifier.padding(16.dp)) }
                        items(chans.size) { i ->
                            val ch = chans[i]
                            ChannelCard(i + 1, ch, null, preview?.id == ch.id, false,
                                onClick = { if (preview?.id == ch.id) PlayerActivity.start(context, ch.id, chans.map { it.id }, null) else mc.previewChannel(ch) }, onFav = {})
                        }
                    }
                } else {
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        // The match card takes ~45% of the height so the list below always has room.
                        val heroH = (maxHeight * 0.46f).coerceIn(190.dp, 330.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MatchHero(selected, state.channelCounts[selected?.id], Modifier.fillMaxWidth().height(heroH)) { selected?.let(onOpenGame) }
                            MatchPanel(mc, shown, selected, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                }
            }

            // ---------------- right: preview + channels ----------------
            Column(Modifier.weight(0.34f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.fillMaxWidth().panel().padding(10.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp))) {
                        PreviewPlayer(preview, Modifier.fillMaxSize())
                        if (preview == null) Text("No signal", color = Color.White, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
                        preview?.let { ch ->
                            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(38.dp).clip(CircleShape).background(Color(0x88000000))
                                .clickable { PlayerActivity.start(context, ch.id, (mc.watch.value.rows.map { it.channel.id }).ifEmpty { listOf(ch.id) }, selected?.headline) },
                                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Fullscreen, "Full screen", tint = Color.White) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        IconAction(Icons.Rounded.Search, "Search") { searching = !searching; if (!searching) hub.setQuery("") }
                        Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { sportSheet = true }.padding(8.dp)) {
                            Text("${state.sport?.label ?: "All sports"} ▾", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        }
                        DropChip(state.day.label, DayOffset.entries.map { it to it.label }, state.day, hub::setDay)
                        SportsGuideButton()
                        IconAction(Icons.Rounded.Refresh, "Refresh", onClick = hub::manualRefresh)
                    }
                    if (searching) com.arena.tv.ui.components.TvTextField(state.query, hub::setQuery, Modifier.fillMaxWidth(), placeholder = { Text("Team, player or event") },
                        singleLine = true, shape = RoundedCornerShape(14.dp))
                }
                ChannelsPane(mc, selected, preview, Modifier.fillMaxWidth().weight(1f), onPick = { picker = true }) { ch ->
                    if (preview?.id == ch.id) PlayerActivity.start(context, ch.id, mc.watch.value.rows.map { it.channel.id }, selected?.headline) else mc.previewChannel(ch)
                }
            }
        }
    }

    if (sportSheet) SportSheet(state.games, state.sport, onDismiss = { sportSheet = false }) { hub.setSport(it); mc.filter.value = SportsFilter.AllToday; sportSheet = false }
    if (picker) ModalBottomSheet(onDismissRequest = { picker = false }, containerColor = ArenaColors.Surface) {
        ChannelPicker(mc::searchChannels) { ch -> mc.confirm(ch); mc.previewChannel(ch); picker = false }
    }
}

// ------------------------------------------------------------ hero

@Composable
internal fun MatchHero(g: Game?, channels: Int?, modifier: Modifier, onOpen: () -> Unit) {
    val c = com.arena.tv.ui.components.LocalCompactChrome.current
    if (g == null) { Box(modifier.panel(), Alignment.Center) { Text("No games", color = ArenaColors.Muted) }; return }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(g.id) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val homeFirst = g.league.sport == Sport.SOCCER || !g.headToHead
    val left = if (homeFirst) g.home else g.away
    val right = if (homeFirst) g.away else g.home
    fun color(hex: String?, fb: Color) = hex?.takeIf { it.length == 6 }?.let { runCatching { Color(("FF$it").toLong(16)) }.getOrNull() } ?: fb
    Box(modifier.panel().background(Brush.horizontalGradient(listOf(color(left.color, Color(0xFF1F3A2A)).copy(alpha = 0.16f), Color(0x00000000),
        color(right.color, Color(0xFF3A1F1F)).copy(alpha = 0.14f)))).clickable(onClick = onOpen)) {
        Text(when (g.state) { GameState.PRE -> "Upcoming"; GameState.LIVE -> "Live"; GameState.POST -> "Finished" },
            fontWeight = FontWeight.SemiBold, fontSize = if (c) 15.sp else 19.sp, color = if (g.isLive) ArenaColors.Live else Color.White,
            modifier = Modifier.padding(if (c) 12.dp else 20.dp))
        Column(Modifier.fillMaxSize().padding(top = if (c) 14.dp else 56.dp, bottom = if (c) 10.dp else 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(listOfNotNull(g.title?.takeIf { g.headToHead && it != g.league.name }, g.note).joinToString(" · ").ifEmpty { g.venue ?: "" },
                color = Color(0xBBFFFFFF), fontSize = if (c) 13.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 70.dp))
            Spacer(Modifier.height(if (c) 8.dp else 16.dp))
            if (g.headToHead) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeroSide(left, Modifier.weight(1f))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (g.state == GameState.PRE) {
                            Text(Time.clock(g.startMillis), fontSize = if (c) 28.sp else 40.sp, fontWeight = FontWeight.Bold)
                            Text(countdown(g.startMillis - now), color = Color(0xBBFFFFFF), fontSize = if (c) 13.sp else 16.sp)
                        } else {
                            Text("${left.score ?: 0} – ${right.score ?: 0}", fontSize = if (c) 34.sp else 48.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (g.isLive) { LiveDot(); Spacer(Modifier.width(6.dp)) }
                                Text(if (g.statusText in setOf("FT", "Final")) "Full time" else g.statusText, color = if (g.isLive) ArenaColors.Live else Color(0xBBFFFFFF), fontSize = if (c) 13.sp else 16.sp)
                            }
                        }
                    }
                    HeroSide(right, Modifier.weight(1f))
                }
            } else {
                Text(g.title ?: g.league.name, fontSize = if (c) 20.sp else 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2,
                    modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(8.dp))
                Text(if (g.state == GameState.PRE) "${Time.clock(g.startMillis)} · ${countdown(g.startMillis - now)}" else g.statusText,
                    color = if (g.isLive) ArenaColors.Live else Color(0xBBFFFFFF), fontSize = 17.sp)
                g.field.firstOrNull()?.takeIf { g.league.sport == Sport.GOLF }?.let {
                    Text("Leader: ${it.name}  ${it.score}", fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(g.leagueLogo, g.league.name, if (c) 18.dp else 26.dp, round = true)
                Spacer(Modifier.width(8.dp))
                Text(g.league.name, fontSize = if (c) 13.sp else 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if ((channels ?: 0) > 0) Text("  ·  On $channels channels", color = ArenaColors.Accent, fontSize = if (c) 12.sp else 15.sp, maxLines = 1)
                if (g.state == GameState.PRE) g.odds?.let { Text("  ·  ${oddsLine(it, LocalDecimalOdds.current)}", color = ArenaColors.Muted, fontSize = if (c) 12.sp else 15.sp, maxLines = 1) }
            }
        }
    }
}

private fun countdown(ms: Long): String {
    if (ms <= 0) return "Starting"
    val s = ms / 1000
    val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return when {
        d > 0 -> "Starts in ${d}d ${h}h"
        h > 0 -> "Starts in ${h} h ${m} min"
        else -> "Starts in $m min $sec s"
    }
}

@Composable
private fun HeroSide(t: com.arena.tv.sports.Team, modifier: Modifier) {
    val c = com.arena.tv.ui.components.LocalCompactChrome.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(t.logo, t.displayName, if (c) 52.dp else 84.dp, round = t.isAthlete)
        Spacer(Modifier.height(if (c) 4.dp else 8.dp))
        Text(t.displayName, fontWeight = FontWeight.SemiBold, fontSize = if (c) 15.sp else 20.sp, textAlign = TextAlign.Center, maxLines = if (c) 1 else 2,
            overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------ centre panel

@Composable
private fun MatchPanel(mc: MatchCentreViewModel, games: List<Game>, selected: Game?, modifier: Modifier) {
    val tab by mc.tab.collectAsStateWithLifecycle()
    val d by mc.details.collectAsStateWithLifecycle()
    val tabs = when {
        selected == null -> listOf("Score")
        !selected.headToHead -> listOf("Score", if (selected.league.sport == Sport.GOLF) "Leaderboard" else "Sessions")
        selected.league.sport == Sport.MMA -> listOf("Score", "Fight card")
        selected.league.sport == Sport.TENNIS -> listOf("Score")
        else -> listOf("Score", "Lineups", "Stats", "Events", "Players", "Table", "H2H", "Odds")
    }
    val decimal by mc.decimalOdds.collectAsStateWithLifecycle()
    val t = tab.coerceAtMost(tabs.size - 1)
    Column(modifier.panel()) {
        Row(Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
            .background(Color(0xFF141417)).horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp)) {
            tabs.forEachIndexed { i, label ->
                Text(label, fontSize = 16.sp, fontWeight = if (i == t) FontWeight.Bold else FontWeight.Medium,
                    color = if (i == t) Color.White else ArenaColors.Muted,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { mc.tab.value = i }.padding(horizontal = 11.dp, vertical = 10.dp))
            }
        }
        Box(Modifier.fillMaxSize()) {
            when (tabs[t]) {
                "Score" -> GameRows(games, selected, mc::select)
                "Lineups" -> selected?.let { Lineups(it, d) }
                "Stats" -> selected?.let { StatsTab(it, d) }
                "Events" -> selected?.let { TimelineTab(it, d) }
                "Table" -> selected?.let { LeagueTable(it, d) }
                "Players" -> selected?.let { PlayersTab(it, d) }
                "H2H" -> selected?.let { HeadToHeadTab(it, d) }
                "Odds" -> selected?.let { OddsTab(it, d, decimal, mc::setDecimalOdds) }
                "Leaderboard", "Sessions" -> selected?.let { FieldList(it) }
                "Fight card" -> selected?.let { FightCard(it) }
            }
        }
    }
}

@Composable
internal fun GameRows(games: List<Game>, selected: Game?, onSelect: (Game) -> Unit) {
    val live = games.filter { it.isLive }
    val later = games.filter { it.state == GameState.PRE }.sortedBy { it.startMillis }
    val done = games.filter { it.state == GameState.POST }.sortedByDescending { it.startMillis }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Live now" to live, "Later today" to later, "Finished" to done).forEach { (title, list) ->
            if (list.isEmpty()) return@forEach
            item(key = "h$title") { Text(title, color = ArenaColors.Muted, fontSize = 17.sp, modifier = Modifier.padding(start = 8.dp, top = 6.dp)) }
            items(list, key = { "$title${it.id}" }) { g -> GameRow(g, g.id == selected?.id) { onSelect(g) } }
        }
        if (games.isEmpty()) item { Text("No games for this filter.", color = ArenaColors.Muted, modifier = Modifier.padding(16.dp)) }
    }
}

@Composable
private fun GameRow(g: Game, selected: Boolean, onClick: () -> Unit) {
    val c = com.arena.tv.ui.components.LocalCompactChrome.current
    val fs = if (c) 14.sp else 17.sp
    val homeFirst = g.league.sport == Sport.SOCCER
    val l = if (homeFirst) g.home else g.away
    val r = if (homeFirst) g.away else g.home
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ArenaColors.Surface)
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = if (c) 10.dp else 14.dp, vertical = if (c) 6.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pill = when (g.state) {
            GameState.PRE -> Time.clock(g.startMillis)
            GameState.LIVE -> if (g.headToHead) "${l.score ?: 0} - ${r.score ?: 0}" else "LIVE"
            GameState.POST -> if (g.headToHead) "${l.score ?: 0} - ${r.score ?: 0}" else "FT"
        }
        if (g.headToHead) {
            Text(l.displayName, Modifier.weight(1f), textAlign = TextAlign.End, fontSize = fs, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (g.state == GameState.POST && !l.winner && r.winner) ArenaColors.Muted else ArenaColors.Text)
            Spacer(Modifier.width(8.dp)); Logo(l.logo, l.displayName, if (c) 20.dp else 26.dp, round = l.isAthlete); Spacer(Modifier.width(8.dp))
        } else {
            Text(g.title ?: g.league.name, Modifier.weight(1f), fontSize = fs, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(pill, fontSize = if (c) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold, color = if (g.isLive) ArenaColors.Live else ArenaColors.Text,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ArenaColors.SurfaceHi).padding(horizontal = 10.dp, vertical = 4.dp))
            if (g.isLive && g.headToHead) Text(g.statusText, color = ArenaColors.Live, fontSize = 11.sp)
        }
        if (g.headToHead) {
            Spacer(Modifier.width(8.dp)); Logo(r.logo, r.displayName, if (c) 20.dp else 26.dp, round = r.isAthlete); Spacer(Modifier.width(8.dp))
            Text(r.displayName, Modifier.weight(1f), fontSize = fs, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (g.state == GameState.POST && !r.winner && l.winner) ArenaColors.Muted else ArenaColors.Text)
        } else {
            Spacer(Modifier.width(8.dp))
            Text(g.note ?: "", Modifier.weight(0.6f), color = ArenaColors.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Logo(g.leagueLogo, g.league.name, 22.dp, round = true)
    }
}

@Composable
internal fun Lineups(g: Game, d: GameDetails?) {
    if (d == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    if (d.homeLineup.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Lineups appear about an hour before kick-off.", color = ArenaColors.Muted) }; return }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Row {
                Text("${g.home.shortName}  ${d.homeFormation ?: ""}", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("${d.awayFormation ?: ""}  ${g.away.shortName}", Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            }
        }
        val rows = maxOf(d.homeLineup.size, d.awayLineup.size)
        items(rows) { i ->
            val h = d.homeLineup.getOrNull(i); val a = d.awayLineup.getOrNull(i)
            val subsStart = i == d.homeLineup.count { it.starter }
            if (subsStart) Text("Substitutes", color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(Modifier.padding(vertical = 4.dp)) {
                Text(h?.let { "${it.number}  ${it.name}" } ?: "", Modifier.weight(1f), color = if (h?.starter == true) ArenaColors.Text else ArenaColors.Muted, maxLines = 1)
                Text(a?.let { "${it.name}  ${it.number}" } ?: "", Modifier.weight(1f), textAlign = TextAlign.End, color = if (a?.starter == true) ArenaColors.Text else ArenaColors.Muted, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun LeagueTable(g: Game, d: GameDetails?) {
    if (d == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    if (d.table.isEmpty()) { Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No table for this competition.", color = ArenaColors.Muted) }; return }
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        item {
            Row(Modifier.padding(bottom = 6.dp)) {
                Text(d.tableTitle ?: "#", Modifier.weight(1f), color = ArenaColors.Muted, fontSize = 13.sp)
                listOf("P", "GD", "Pts").forEach { Text(it, Modifier.width(48.dp), color = ArenaColors.Muted, fontSize = 13.sp, textAlign = TextAlign.End) }
            }
        }
        items(d.table) { r ->
            val mine = r.teamId == g.home.id || r.teamId == g.away.id
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (mine) ArenaColors.SurfaceHi else Color.Transparent).padding(vertical = 6.dp, horizontal = 6.dp)) {
                Text("${r.rank}", Modifier.width(30.dp), color = ArenaColors.Muted)
                Text(r.team, Modifier.weight(1f), fontWeight = if (mine) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.played, Modifier.width(48.dp), textAlign = TextAlign.End)
                Text(r.goalDiff, Modifier.width(48.dp), textAlign = TextAlign.End)
                Text(r.points, Modifier.width(48.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun FieldList(g: Game) {
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(g.field) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (r.position.isNotEmpty()) Text(r.position, Modifier.width(34.dp), color = ArenaColors.Muted)
                r.flag?.let { Logo(it, r.name, 20.dp, round = true); Spacer(Modifier.width(10.dp)) }
                Text(r.name, Modifier.weight(1f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (g.league.sport == Sport.GOLF) r.detail?.let { Text(it, color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 12.dp)) }
                Text(if (g.league.sport == Sport.RACING) (r.detail?.let { Time.clock(com.arena.tv.sports.EspnParser.parseDate(it)) } ?: r.score) else r.score,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
internal fun FightCard(g: Game) {
    LazyColumn(contentPadding = PaddingValues(16.dp)) {
        items(g.bouts) { b ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(ArenaColors.Surface).padding(12.dp)) {
                b.weightClass?.let { Text(it, color = ArenaColors.Muted, fontSize = 12.sp) }
                Row {
                    Text(b.a + if (b.winner == 0) "  ✓" else "", Modifier.weight(1f), fontWeight = if (b.winner == 0) FontWeight.Bold else FontWeight.Medium)
                    Text("vs", color = ArenaColors.Muted, modifier = Modifier.padding(horizontal = 8.dp))
                    Text((if (b.winner == 1) "✓  " else "") + b.b, Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = if (b.winner == 1) FontWeight.Bold else FontWeight.Medium)
                }
                b.result?.let { Text(it, color = ArenaColors.Muted, fontSize = 12.sp) }
            }
        }
    }
}

// ------------------------------------------------------------ right: channels

@Composable
private fun ChannelsPane(mc: MatchCentreViewModel, g: Game?, preview: ChannelEntity?, modifier: Modifier, onPick: () -> Unit, onChannel: (ChannelEntity) -> Unit) {
    val w by mc.watch.collectAsStateWithLifecycle()
    Column(modifier.panel().padding(14.dp)) {
        Text(g?.let { "Channels for ${it.headline}" } ?: "Channels", color = ArenaColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        w.loadingNote?.let { Text("Playlist still loading ($it) — results will fill in.", color = ArenaColors.Gold, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp)) }
        val rows = w.rows.filter { it.match.tier != Tier.USUAL }.ifEmpty { w.rows.take(6) }
        when {
            g == null -> Unit
            w.searching && w.rows.isEmpty() -> Box(Modifier.fillMaxWidth().padding(20.dp), Alignment.Center) { CircularProgressIndicator() }
            rows.isEmpty() -> {
                Text("Not found on your channels. Pick one and the app will remember it.", fontSize = 17.sp, modifier = Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = onPick) { Text("Pick a channel") }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.channel.id }) { r ->
                    val sel = preview?.id == r.channel.id
                    val color = when (r.match.tier) { Tier.CONFIRMED, Tier.STRONG -> ArenaColors.Accent; Tier.LIKELY -> ArenaColors.Gold; Tier.USUAL -> ArenaColors.Muted }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sel) ArenaColors.SurfaceHi else ArenaColors.Surface)
                            .then(if (sel) Modifier.border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(12.dp)) else Modifier)
                            .clickable { onChannel(r.channel) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Logo(r.channel.logo, r.channel.name, if (com.arena.tv.ui.components.LocalCompactChrome.current) 28.dp else 38.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.match.programTitle ?: r.match.reasons.firstOrNull() ?: "", color = ArenaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (r.match.tier == Tier.CONFIRMED) "✓" else "${r.match.score}%", color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        if (r.verdict == null) {
                            Box(Modifier.size(30.dp).clip(CircleShape).clickable { mc.confirm(r.channel) }, Alignment.Center) { Icon(Icons.Rounded.Check, "Right channel", tint = ArenaColors.Muted, modifier = Modifier.size(16.dp)) }
                            Box(Modifier.size(30.dp).clip(CircleShape).clickable { mc.reject(r.channel) }, Alignment.Center) { Icon(Icons.Rounded.Close, "Wrong channel", tint = ArenaColors.Muted, modifier = Modifier.size(16.dp)) }
                        }
                    }
                }
                item { Text("Tap a channel to preview it, tap again for full screen.", color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
                item { OutlinedButton(onClick = onPick, modifier = Modifier.padding(top = 4.dp)) { Text("Pick another channel") } }
            }
        }
    }
}

// ------------------------------------------------------------ sport sheet

/** "Sport" chooser with how many events each sport has today. */
@Composable
fun SportSheet(games: List<Game>, current: Sport?, onDismiss: () -> Unit, onPick: (Sport?) -> Unit) {
    val counts = games.groupingBy { it.league.sport }.eachCount()
    val sports = Sport.entries.sortedByDescending { counts[it] ?: 0 }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF26272B), Color(0xFF141416)))).padding(18.dp)) {
            Text("Sport", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
            LazyColumn {
                item { SportOption("All sports", "${games.size} today", current == null) { onPick(null) } }
                items(sports) { s ->
                    val n = counts[s] ?: 0
                    SportOption("${s.emoji}  ${s.label}", if (n == 0) "None today" else "$n today", current == s, dim = n == 0) { onPick(s) }
                }
            }
        }
    }
}

@Composable
private fun SportOption(label: String, count: String, selected: Boolean, dim: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x33FFFFFF) else Color.Transparent)
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier)
            .clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), fontSize = 19.sp, color = if (dim) ArenaColors.Muted else Color.White)
        Text(count, color = ArenaColors.Muted, fontSize = 16.sp)
    }
}
