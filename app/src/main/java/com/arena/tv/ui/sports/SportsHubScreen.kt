package com.arena.tv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.arena.tv.sports.DayOffset
import com.arena.tv.sports.Leagues
import com.arena.tv.matching.Text
import com.arena.tv.ui.components.ArenaTopBar
import com.arena.tv.ui.components.DropChip
import com.arena.tv.ui.components.IconAction
import com.arena.tv.ui.components.PillTabs
import com.arena.tv.ui.components.TabDivider
import com.arena.tv.ui.components.ToggleLabel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arena.tv.sports.Game
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Sport
import com.arena.tv.ui.appViewModel
import com.arena.tv.ui.components.EmptyState
import com.arena.tv.ui.components.LiveDot
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.components.SectionHeader
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.theme.ArenaColors

@Composable
fun SportsHubScreen(onOpenGame: (Game) -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { SportsHubViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val favTeams by vm.favoriteTeams.collectAsStateWithLifecycle()
    val settings by vm.hasSource.collectAsStateWithLifecycle()
    val layout by vm.layout.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, state.day) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.poll() } }

    val favKeys = remember(favTeams) { favTeams.map { it.key }.toSet() }
    val sportsPresent = remember(state.games) { state.games.map { it.league.sport }.distinct() }
    val countries = remember(state.games) { state.games.map { Leagues.country(it.league) }.distinct().sorted() }
    var searching by remember { mutableStateOf(false) }
    val q = Text.normalize(state.query)
    val visible = state.games.filter { g ->
        (state.sport == null || g.league.sport == state.sport) &&
            (!state.liveOnly || g.isLive) &&
            (state.country == null || Leagues.country(g.league) == state.country) &&
            (!state.mine || (state.channelCounts[g.id] ?: 0) > 0) &&
            (q.isEmpty() || Text.normalize("${g.home.displayName} ${g.away.displayName} ${g.league.name}").contains(q))
    }
    val following = visible.filter { it.home.key(it.league.sport) in favKeys || it.away.key(it.league.sport) in favKeys }
    val wide = com.arena.tv.ui.components.LocalWide.current
    val compactChrome = com.arena.tv.ui.components.LocalCompactChrome.current
    var sportSheet by remember { mutableStateOf(false) }
    if (sportSheet) SportSheet(state.games, state.sport, { sportSheet = false }) { vm.setSport(it); sportSheet = false }

    // Big screens: "Scores" is the three-pane Sports Mode.
    if (wide && state.tab == 1) {
        val mc = appViewModel(key = "hub-mc") { MatchCentreViewModel(it) }
        Column(Modifier.fillMaxSize()) {
        com.arena.tv.ui.components.PillStrip()
        SportsMode(vm, mc, header = {
            Row(verticalAlignment = Alignment.CenterVertically) { PillTabs(listOf("For you", "Scores", "Following"), state.tab, compact = true) { vm.setTab(it) } }
        }, onOpenGame = onOpenGame)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ArenaTopBar(
            left = {
                PillTabs(listOf("For you", "Scores", "Following"), state.tab) { vm.setTab(it) }
                TabDivider()
                ToggleLabel("Mine", state.mine, vm::setMine)
            },
            right = {
                IconAction(Icons.Rounded.Search, "Search") { searching = !searching; if (!searching) vm.setQuery("") }
                Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { sportSheet = true }.padding(8.dp)) {
                    Text("${state.sport?.label ?: if (compactChrome) "Sports" else "All sports"} ▾", fontSize = if (compactChrome) 15.sp else 16.sp, fontWeight = FontWeight.Medium)
                }
                DropChip(state.day.label, DayOffset.entries.map { it to it.label }, state.day, vm::setDay)
                DropChip(state.country ?: if (compactChrome) "Countries" else "All countries", listOf<Pair<String?, String>>(null to "All countries") + countries.map { it to it },
                    state.country, vm::setCountry)
                SportsGuideButton()
                if (state.tab == 0) SportsLayoutButton(layout) { vm.setLayout(it) }
                IconAction(Icons.Rounded.Refresh, "Refresh", onClick = vm::manualRefresh)
            },
        )
        if (searching) {
            OutlinedTextField(state.query, vm::setQuery, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search teams or leagues") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) })
        }
        if (settings != null && settings?.hasSource == false) SetupBanner(onOpenSettings)

        when {
            state.loading && state.games.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            state.tab == 2 && following.isEmpty() -> EmptyState("No games for teams you follow",
                "Open any game and tap ☆ under a team to follow it.", icon = Icons.Rounded.SportsSoccer)
            visible.isEmpty() -> EmptyState(
                title = if (state.mine) "None of your channels show these games" else "No games",
                body = state.error ?: if (state.mine) "Turn off \"Mine\" to see every game." else "No games in your leagues for this day.",
                icon = Icons.Rounded.SportsSoccer,
                action = "Retry", onAction = vm::manualRefresh,
            )
            state.tab == 0 && layout == "list" -> GameList(visible, favKeys, state, onOpenGame)
            state.tab == 0 -> ForYou(visible, favKeys, state.channelCounts, onOpenGame, spotlight = layout == "spotlight")
            state.tab == 1 -> GameList(visible, favKeys, state, onOpenGame)
            else -> GameList(following, favKeys, state, onOpenGame)
        }
    }
}

@Composable
private fun SetupBanner(onOpenSettings: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(16.dp, 8.dp).clip(RoundedCornerShape(12.dp))
            .background(ArenaColors.AccentDim.copy(alpha = 0.5f)).clickable(onClick = onOpenSettings).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Tv, null, tint = ArenaColors.Accent)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Add your IPTV playlist", fontWeight = FontWeight.SemiBold)
            Text("Then tap any game to see which of your channels shows it.", color = ArenaColors.Muted, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun GameList(games: List<Game>, favKeys: Set<String>, state: HubState, onOpenGame: (Game) -> Unit) {
    val mine = games.filter { it.home.key(it.league.sport) in favKeys || it.away.key(it.league.sport) in favKeys }
    val live = games.filter { it.isLive && it !in mine }
    val rest = games.filter { !it.isLive && it !in mine }
    val byLeague = rest.groupBy { it.league }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        state.error?.let { err -> item { Text(err, color = ArenaColors.Gold, fontSize = 12.sp, modifier = Modifier.padding(16.dp, 4.dp)) } }
        if (mine.isNotEmpty()) {
            item { SectionHeader("★ My teams") }
            items(mine, key = { "m" + it.id }) { GameCard(it, state.channelCounts[it.id], true, onOpenGame) }
        }
        if (live.isNotEmpty()) {
            item { SectionHeader("Live now") { LiveDot() } }
            items(live, key = { "l" + it.id }) { GameCard(it, state.channelCounts[it.id], true, onOpenGame) }
        }
        byLeague.forEach { (league, list) ->
            item(key = "h" + league.key) { SectionHeader("${league.sport.emoji}  ${league.name}") }
            items(list, key = { "g" + it.id }) { GameCard(it, state.channelCounts[it.id], false, onOpenGame) }
        }
    }
}

@Composable
fun GameCard(g: Game, channels: Int?, showLeague: Boolean, onClick: (Game) -> Unit) {
    val liveBorder = if (g.isLive) Modifier.border(1.dp, ArenaColors.Live.copy(alpha = 0.35f), RoundedCornerShape(14.dp)) else Modifier
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp)).then(liveBorder)
            .background(ArenaColors.Surface)
            .clickable { onClick(g) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showLeague) {
                Text(g.league.shortName, color = ArenaColors.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("  ·  ", color = ArenaColors.Muted, fontSize = 11.sp)
            }
            StatusText(g)
            Spacer(Modifier.weight(1f))
            if (channels != null && channels > 0) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(ArenaColors.AccentDim).padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Tv, null, tint = ArenaColors.Accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("$channels", color = ArenaColors.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // Soccer lists the home team first; US sports list the away team first ("Lakers at Celtics").
        val homeFirst = g.league.sport == Sport.SOCCER
        TeamRow(g, home = homeFirst)
        Spacer(Modifier.height(4.dp))
        TeamRow(g, home = !homeFirst)
    }
}

@Composable
private fun StatusText(g: Game) {
    when (g.state) {
        GameState.LIVE -> Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(); Spacer(Modifier.width(6.dp))
            Text(g.statusText, color = ArenaColors.Live, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        GameState.PRE -> Text(
            if (g.statusText.contains("Postponed", true) || g.statusText.contains("Canceled", true)) g.statusText else Time.clock(g.startMillis),
            color = ArenaColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
        GameState.POST -> Text(g.statusText, color = ArenaColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TeamRow(g: Game, home: Boolean) {
    val t = if (home) g.home else g.away
    val other = if (home) g.away else g.home
    val dim = g.state == GameState.POST && !t.winner && other.winner
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(t.logo, t.displayName, 26.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            t.displayName, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (dim) ArenaColors.Muted else ArenaColors.Text,
            fontWeight = if (t.winner) FontWeight.Bold else FontWeight.Medium, fontSize = 15.sp,
        )
        t.record?.takeIf { g.state == GameState.PRE }?.let {
            Text(it, color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
        }
        if (g.state != GameState.PRE) {
            Text(
                t.score ?: "-", modifier = Modifier.widthIn(min = 28.dp),
                fontWeight = FontWeight.Black, fontSize = 18.sp,
                color = if (dim) ArenaColors.Muted else ArenaColors.Text,
            )
        }
    }
}
