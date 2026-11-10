package com.arena.tv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.arena.tv.data.ChannelEntity
import com.arena.tv.matching.Tier
import com.arena.tv.sports.EventKind
import com.arena.tv.sports.Game
import com.arena.tv.sports.GameDetails
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Sport
import com.arena.tv.sports.Team
import com.arena.tv.ui.appViewModel
import com.arena.tv.ui.components.EmptyState
import com.arena.tv.ui.components.LiveDot
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.components.Pill
import com.arena.tv.ui.components.SectionHeader
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.player.PlayerActivity
import com.arena.tv.ui.theme.ArenaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(gameId: String, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel(key = "game-$gameId") { GameDetailViewModel(it, gameId) }
    val game by vm.game.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val watch by vm.watch.collectAsStateWithLifecycle()
    val favs by vm.favoriteTeams.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val g = game ?: run {
        EmptyState("Game not found", "Go back and pick it again.", action = "Back", onAction = onBack); return
    }

    fun play(ch: ChannelEntity) {
        val zap = watch.rows.filter { it.match.tier != Tier.USUAL }.map { it.channel.id }.ifEmpty { watch.rows.map { it.channel.id } }
        PlayerActivity.start(context, ch.id, if (ch.id in zap) zap else listOf(ch.id) + zap, "${g.home.shortName} vs ${g.away.shortName}")
    }

    val decimal = settings?.decimalOdds ?: true
    val channelsLabel = "Channels" + watch.rows.count { it.match.tier != Tier.USUAL }.let { if (it > 0) " ($it)" else "" }
    val tabs: List<String> = when {
        !g.headToHead -> listOf(channelsLabel, if (g.league.sport == Sport.GOLF) "Leaderboard" else "Sessions")
        g.league.sport == Sport.MMA -> listOf(channelsLabel, "Fight card", "Odds")
        g.league.sport == Sport.TENNIS -> listOf(channelsLabel, "Odds")
        g.league.sport == Sport.SOCCER -> listOf(channelsLabel, "Stats", "Lineups", "Events", "Players", "Table", "Head to head", "Odds")
        else -> listOf(channelsLabel, "Stats", "Events", "Players", "Table", "Head to head", "Odds")
    }
    val t = tab.coerceIn(0, tabs.size - 1)
    val content: @Composable () -> Unit = {
        when (tabs[t].substringBefore(" (")) {
            "Channels" -> WatchTab(g, watch, settings?.hasSource == true, ::play, WatchActions(vm::confirm, vm::reject, vm::clearVerdict), onOpenSettings) { showPicker = true }
            "Stats" -> StatsTab(g, details)
            "Lineups" -> Lineups(g, details)
            "Events" -> TimelineTab(g, details)
            "Players" -> PlayersTab(g, details)
            "Table" -> LeagueTable(g, details)
            "Head to head" -> HeadToHeadTab(g, details)
            "Odds" -> OddsTab(g, details, decimal, vm::setDecimalOdds)
            "Leaderboard", "Sessions" -> FieldList(g)
            "Fight card" -> FightCard(g)
        }
    }
    val best = watch.rows.firstOrNull { it.match.tier == Tier.CONFIRMED || it.match.tier == Tier.STRONG }

    if (com.arena.tv.ui.components.LocalWide.current) {
        // Match centre: score + actions on the left, tabs on the right.
        Column(Modifier.fillMaxSize()) {
            com.arena.tv.ui.components.PillStrip()
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.38f).fillMaxSize().verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                        Text(g.league.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    }
                    ScoreHeader(g, details, favs, vm::toggleTeam)
                    best?.let { b ->
                        com.arena.tv.ui.components.WhiteButton("Watch on ${b.channel.name}", { play(b.channel) }, Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            icon = Icons.Rounded.PlayArrow)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (g.state != GameState.POST) PinButton(vm, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
                    if (g.headToHead) listOf(g.home, g.away).forEach { team ->
                        val isFav = team.key(g.league.sport) in favs
                        OutlinedButton(onClick = { vm.toggleTeam(team, isFav) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(10.dp)) {
                            Logo(team.logo, team.displayName, 24.dp); Spacer(Modifier.width(10.dp))
                            Text(if (isFav) "Following ${team.shortName}" else "Follow ${team.shortName}", Modifier.weight(1f))
                            Icon(if (isFav) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, tint = if (isFav) ArenaColors.Gold else ArenaColors.Muted)
                        }
                    }
                    if (g.state == GameState.PRE) g.odds?.let { o ->
                        Text("Odds  ${oddsLine(o, decimal)}", color = ArenaColors.Muted, modifier = Modifier.padding(16.dp))
                    }
                }
                Column(Modifier.weight(0.62f).fillMaxSize().clip(RoundedCornerShape(22.dp)).background(ArenaColors.Panel)) {
                    Box(Modifier.fillMaxWidth().padding(10.dp)) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            com.arena.tv.ui.components.PillTabs(tabs, t, compact = true) { tab = it }
                        }
                    }
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    } else Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text(g.league.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
        }
        ScoreHeader(g, details, favs, vm::toggleTeam)
        if (g.state != GameState.POST) PinButton(vm, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
        ScrollableTabRow(selectedTabIndex = t, containerColor = ArenaColors.Bg, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, label -> Tab(selected = t == i, onClick = { tab = i }, text = { Text(label) }) }
        }
        content()
    }

    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }, containerColor = ArenaColors.Surface) {
            ChannelPicker(vm::searchChannels) { ch -> vm.confirm(ch); showPicker = false }
        }
    }
}

@Composable
private fun PinButton(vm: GameDetailViewModel, modifier: Modifier) {
    val pinned by vm.pinned.collectAsStateWithLifecycle()
    OutlinedButton(onClick = { vm.togglePin() }, modifier = modifier, shape = RoundedCornerShape(10.dp)) {
        Icon(Icons.Rounded.PushPin, null, tint = if (pinned) ArenaColors.Accent else ArenaColors.Muted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(if (pinned) "Score pinned while you watch" else "Pin score while you watch", Modifier.weight(1f))
    }
}

@Composable
internal fun ScoreHeader(g: Game, d: GameDetails?, favs: Set<String>, onFav: (Team, Boolean) -> Unit) {
    val left = if (g.league.sport == Sport.SOCCER) g.home else g.away
    val right = if (g.league.sport == Sport.SOCCER) g.away else g.home
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp))
            .background(ArenaColors.Surface).padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TeamBlock(left, g, favs, onFav, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(150.dp)) {
                if (g.state == GameState.PRE) {
                    Text(Time.clock(g.startMillis), fontSize = 26.sp, fontWeight = FontWeight.Black)
                    Text(if (g.startMillis - System.currentTimeMillis() < 86400_000) "Kick-off" else g.statusDetail,
                        color = ArenaColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                } else {
                    val score = "${left.score ?: 0} – ${right.score ?: 0}"
                    Text(score, fontSize = if (score.length > 7) 26.sp else 32.sp, fontWeight = FontWeight.Black, maxLines = 1, softWrap = false)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (g.isLive) { LiveDot(); Spacer(Modifier.width(6.dp)) }
                        Text(g.statusText, color = if (g.isLive) ArenaColors.Live else ArenaColors.Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
            TeamBlock(right, g, favs, onFav, Modifier.weight(1f))
        }
        val sub = listOfNotNull(g.note, g.venue).joinToString(" · ")
        if (sub.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(sub, color = ArenaColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
        // Goal scorers under the score, like a TV graphic (soccer only).
        if (g.league.sport == Sport.SOCCER && d != null) {
            val goals = d.events.filter { it.kind in setOf(EventKind.GOAL, EventKind.PENALTY_GOAL, EventKind.OWN_GOAL) }
            if (goals.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    val leftIsHome = true
                    Column(Modifier.weight(1f)) {
                        goals.filter { it.isHome == leftIsHome }.forEach { Text("⚽ ${it.text.substringBefore(" ·")} ${it.clock}", fontSize = 11.sp, color = ArenaColors.Muted, maxLines = 1) }
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        goals.filter { it.isHome == !leftIsHome }.forEach { Text("${it.text.substringBefore(" ·")} ${it.clock} ⚽", fontSize = 11.sp, color = ArenaColors.Muted, maxLines = 1) }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun TeamBlock(t: Team, g: Game, favs: Set<String>, onFav: (Team, Boolean) -> Unit, modifier: Modifier) {
    val isFav = t.key(g.league.sport) in favs
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(t.logo, t.displayName, 56.dp)
        Spacer(Modifier.height(6.dp))
        Text(t.shortName, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, fontSize = 14.sp)
        t.record?.let { Text(it, color = ArenaColors.Muted, fontSize = 11.sp) }
        Icon(
            if (isFav) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (isFav) "Unfollow" else "Follow",
            tint = if (isFav) ArenaColors.Gold else ArenaColors.Muted,
            modifier = Modifier.padding(top = 2.dp).size(22.dp).clip(RoundedCornerShape(50)).clickable { onFav(t, isFav) },
        )
    }
}

// ---------------- Watch tab ----------------

class WatchActions(
    val confirm: (ChannelEntity) -> Unit,
    val reject: (ChannelEntity) -> Unit,
    val clear: (ChannelEntity) -> Unit,
)

@Composable
internal fun WatchTab(
    g: Game, w: WatchState, hasSource: Boolean, play: (ChannelEntity) -> Unit,
    vm: WatchActions, onOpenSettings: () -> Unit, onPick: () -> Unit,
) {
    if (!hasSource) {
        EmptyState("Add your IPTV playlist", "Once your channels are loaded, this tab lists the ones showing this game.",
            action = "Open settings", onAction = onOpenSettings)
        return
    }
    if (w.searching && w.rows.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Spacer(Modifier.height(12.dp))
                Text("Searching your channels…", color = ArenaColors.Muted)
            }
        }
        return
    }
    val strong = w.rows.filter { it.match.tier == Tier.CONFIRMED || it.match.tier == Tier.STRONG }
    val likely = w.rows.filter { it.match.tier == Tier.LIKELY }
    val usual = w.rows.filter { it.match.tier == Tier.USUAL }
    var showAllUsual by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        w.loadingNote?.let { note -> item {
            Text("Your playlist is still loading ($note). Results will fill in when it's done.",
                color = ArenaColors.Gold, fontSize = 13.sp, modifier = Modifier.padding(16.dp, 10.dp, 16.dp, 0.dp))
        } }
        if (g.broadcasts.isNotEmpty()) item {
            Text("Official TV: ${g.broadcasts.joinToString(", ")}", color = ArenaColors.Muted, fontSize = 12.sp,
                modifier = Modifier.padding(16.dp, 10.dp, 16.dp, 0.dp))
        }
        if (strong.isEmpty() && likely.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Tv, null, tint = ArenaColors.Muted, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(8.dp))
                Text("No channel found showing this game yet", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(
                    when {
                        w.channelCount == 0 -> "Your channel list is empty. Refresh it in Settings."
                        !w.hasGuide -> "Your provider has no TV guide, so I can only guess from channel names and broadcasters. Add a guide (EPG) URL in Settings for much better results."
                        g.state == GameState.PRE && g.startMillis - System.currentTimeMillis() > 36 * 3600_000L -> "The TV guide only covers the next day or two."
                        else -> "Your TV guide doesn't list it. If you find it, tap \"Pick a channel\" — the app will remember."
                    },
                    color = ArenaColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                )
            }
        }
        rows("Showing this game", strong, play, vm)
        rows("Probably showing it", likely, play, vm)
        if (usual.isNotEmpty()) {
            rows("Usually shows ${g.league.shortName}", if (showAllUsual) usual else usual.take(5), play, vm)
            if (usual.size > 5 && !showAllUsual) item {
                TextButton(onClick = { showAllUsual = true }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Show ${usual.size - 5} more") }
            }
        }
        item {
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Pick a channel")
            }
        }
        if (w.rejected.isNotEmpty()) {
            item { SectionHeader("Hidden by you") }
            items(w.rejected, key = { "r" + it.id }) { ch ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(ch.logo, ch.name, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(ch.name, Modifier.weight(1f), color = ArenaColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { vm.clear(ch) }) { Icon(Icons.AutoMirrored.Rounded.Undo, "Undo") }
                }
            }
        }
    }
}

private fun LazyListScope.rows(title: String, list: List<WatchRow>, play: (ChannelEntity) -> Unit, vm: WatchActions) {
    if (list.isEmpty()) return
    item(key = "h$title") { SectionHeader(title) }
    items(list, key = { title + it.channel.id }) { MatchRowView(it, play, vm) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchRowView(r: WatchRow, play: (ChannelEntity) -> Unit, vm: WatchActions) {
    val m = r.match
    val color = when (m.tier) {
        Tier.CONFIRMED -> ArenaColors.Accent
        Tier.STRONG -> ArenaColors.Accent
        Tier.LIKELY -> ArenaColors.Gold
        Tier.USUAL -> ArenaColors.Muted
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(ArenaColors.Surface).clickable { play(r.channel) }.padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(4.dp).height(48.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(10.dp))
        Logo(r.channel.logo, r.channel.name, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.channel.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val prog = m.programTitle ?: r.nowTitle
            if (prog != null) {
                Text(
                    prog + (m.programStart?.let { "  ·  ${Time.range(it, m.programStop ?: it)}" } ?: ""),
                    color = ArenaColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(r.channel.groupTitle, color = ArenaColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (m.tier == Tier.CONFIRMED) Pill("✓ Confirmed", ArenaColors.AccentDim, textColor = ArenaColors.Accent)
                else Pill("${m.score}%", color.copy(alpha = 0.18f), textColor = color)
                m.reasons.filter { !it.startsWith("You confirmed") }.take(2).forEach {
                    Pill(it, ArenaColors.SurfaceHi, textColor = ArenaColors.Muted)
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.PlayArrow, "Play", tint = ArenaColors.Accent, modifier = Modifier.size(30.dp))
            Row {
                if (r.verdict == 1) {
                    SmallIcon(Icons.AutoMirrored.Rounded.Undo, "Undo") { vm.clear(r.channel) }
                } else {
                    SmallIcon(Icons.Rounded.ThumbUp, "Right channel") { vm.confirm(r.channel) }
                    SmallIcon(Icons.Rounded.ThumbDown, "Wrong channel") { vm.reject(r.channel) }
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = ArenaColors.Muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
internal fun ChannelPicker(search: suspend (String) -> List<ChannelEntity>, onPick: (ChannelEntity) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    LaunchedEffect(q) { kotlinx.coroutines.delay(200); results = search(q) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
        Text("Which channel shows this game?", style = MaterialTheme.typography.titleMedium)
        Text("It'll be pinned to the top, and channels you pick help future matches in this league.", color = ArenaColors.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        com.arena.tv.ui.components.TvTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search channels") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true,
            trailingIcon = { if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "Clear") } })
        if (q.isBlank()) Text("Your favorites", color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(results, key = { it.id }) { ch ->
                Row(Modifier.fillMaxWidth().clickable { onPick(ch) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Logo(ch.logo, ch.name, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ch.groupTitle, color = ArenaColors.Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Icon(Icons.Rounded.Check, null, tint = ArenaColors.Accent)
                }
            }
        }
    }
}

// ---------------- Stats tab ----------------

@Composable
internal fun StatsTab(g: Game, d: GameDetails?) {
    if (d == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    val homeLeft = g.league.sport == Sport.SOCCER
    val left = if (homeLeft) g.home else g.away
    val right = if (homeLeft) g.away else g.home
    val leftLines = if (homeLeft) d.homePeriodScores.ifEmpty { g.home.periodScores } else d.awayPeriodScores.ifEmpty { g.away.periodScores }
    val rightLines = if (homeLeft) d.awayPeriodScores.ifEmpty { g.away.periodScores } else d.homePeriodScores.ifEmpty { g.home.periodScores }
    if (d.stats.isEmpty() && leftLines.isEmpty()) {
        EmptyState("No stats yet", if (g.state == GameState.PRE) "Stats appear once the game starts." else "This league doesn't publish team stats.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        if (g.league.sport != Sport.SOCCER && leftLines.isNotEmpty()) item {
            PeriodTable(left, right, leftLines, rightLines, g.league.sport)
            Spacer(Modifier.height(16.dp))
        }
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Logo(left.logo, left.displayName, 22.dp); Spacer(Modifier.weight(1f)); Logo(right.logo, right.displayName, 22.dp)
            }
        }
        items(d.stats) { s ->
            val lv = if (homeLeft) s.home else s.away
            val rv = if (homeLeft) s.away else s.home
            val leftShare = s.homeShare?.let { if (homeLeft) it else 1 - it }
            Column(Modifier.padding(vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(lv, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                    Text(s.label, color = ArenaColors.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Text(rv, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                }
                if (leftShare != null) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().height(5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        val l = leftShare.coerceIn(0.02f, 0.98f)
                        Box(Modifier.weight(l).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                            .background(if (l >= 0.5f) ArenaColors.Accent else ArenaColors.Outline))
                        Box(Modifier.weight(1 - l).fillMaxHeight().clip(RoundedCornerShape(3.dp))
                            .background(if (l < 0.5f) ArenaColors.Blue else ArenaColors.Outline))
                    }
                }
            }
        }
    }
}

@Composable
internal fun PeriodTable(left: Team, right: Team, l: List<String>, r: List<String>, sport: Sport) {
    val n = maxOf(l.size, r.size)
    val label = when (sport) { Sport.BASKETBALL, Sport.FOOTBALL -> "Q"; Sport.HOCKEY -> "P"; else -> "" }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ArenaColors.Surface).padding(12.dp)) {
        @Composable
        fun line(name: String, vals: List<String>, total: String?, header: Boolean = false) {
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(name, Modifier.weight(1f), color = if (header) ArenaColors.Muted else ArenaColors.Text, fontSize = 13.sp, maxLines = 1)
                for (i in 0 until n) Text(vals.getOrNull(i) ?: "", Modifier.width(30.dp), textAlign = TextAlign.Center,
                    color = if (header) ArenaColors.Muted else ArenaColors.Text, fontSize = 13.sp)
                Text(total ?: "", Modifier.width(36.dp), textAlign = TextAlign.End, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    color = if (header) ArenaColors.Muted else ArenaColors.Text)
            }
        }
        line("", (1..n).map { if (sport == Sport.BASEBALL) "$it" else if (it > 4 && label == "Q") "OT" else "$label$it" }, "T", header = true)
        line(left.abbreviation.ifBlank { left.shortName }, l, left.score)
        line(right.abbreviation.ifBlank { right.shortName }, r, right.score)
    }
}

// ---------------- Timeline tab ----------------

@Composable
internal fun TimelineTab(g: Game, d: GameDetails?) {
    if (d == null) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    if (d.events.isEmpty()) {
        EmptyState("Nothing yet", if (g.state == GameState.PRE) "Goals, cards and scoring plays show up here during the game." else "No key events were published.")
        return
    }
    val homeLeft = g.league.sport == Sport.SOCCER
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        items(d.events.asReversed()) { e ->
            val icon = when (e.kind) {
                EventKind.GOAL, EventKind.PENALTY_GOAL -> "⚽"
                EventKind.OWN_GOAL -> "⚽"
                EventKind.YELLOW -> "🟨"
                EventKind.RED -> "🟥"
                EventKind.SUB -> "🔁"
                EventKind.SCORE -> g.league.sport.emoji
                EventKind.OTHER -> "•"
            }
            val onLeft = e.isHome == null || e.isHome == homeLeft
            val important = e.kind in setOf(EventKind.GOAL, EventKind.PENALTY_GOAL, EventKind.OWN_GOAL, EventKind.RED, EventKind.SCORE)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (onLeft) Arrangement.Start else Arrangement.End) {
                if (onLeft) {
                    Text(e.clock, color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.width(48.dp))
                    Text(icon); Spacer(Modifier.width(8.dp))
                    Text(e.text, fontWeight = if (important) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp,
                        color = if (important) ArenaColors.Text else ArenaColors.Muted, modifier = Modifier.weight(1f, fill = false))
                } else {
                    Text(e.text, fontWeight = if (important) FontWeight.SemiBold else FontWeight.Normal, fontSize = 14.sp, textAlign = TextAlign.End,
                        color = if (important) ArenaColors.Text else ArenaColors.Muted, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp)); Text(icon)
                    Text(e.clock, color = ArenaColors.Muted, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                }
            }
        }
    }
}

