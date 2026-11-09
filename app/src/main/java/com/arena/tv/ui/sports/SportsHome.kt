package com.arena.tv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.sports.Game
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Sport
import com.arena.tv.sports.Team
import com.arena.tv.ui.components.GhostButton
import com.arena.tv.ui.components.LiveDot
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.theme.ArenaColors
import kotlinx.coroutines.delay

/** Decimal (true) or American odds, provided from the settings at the top of the app. */
val LocalDecimalOdds = androidx.compose.runtime.staticCompositionLocalOf { true }

/** Featured games first: followed teams, then live, then about to start, then just finished. */
fun featured(games: List<Game>, favKeys: Set<String>, counts: Map<String, Int>): List<Game> {
    val now = System.currentTimeMillis()
    fun rank(g: Game): Int {
        var r = 0
        if (g.home.key(g.league.sport) in favKeys || g.away.key(g.league.sport) in favKeys) r += 1000
        r += when (g.state) {
            GameState.LIVE -> 500
            GameState.PRE -> if (g.startMillis - now < 3 * 3600_000) 300 else 100
            GameState.POST -> if (now - g.startMillis < 4 * 3600_000) 200 else 0
        }
        r += (counts[g.id] ?: 0).coerceAtMost(5) * 20
        r -= com.arena.tv.sports.Leagues.all.indexOfFirst { it.key == g.league.key }.coerceAtLeast(0)
        return r
    }
    return games.filter { !it.undecided }.sortedByDescending(::rank).take(6)
}

@Composable
fun ForYou(games: List<Game>, favKeys: Set<String>, counts: Map<String, Int>, spotlight: Boolean = true, onOpen: (Game) -> Unit) {
    val wide = LocalWide.current
    val hero = featured(games, favKeys, counts)
    // Rows layout: games in the Featured row aren't repeated below. Undecided matches (players "TBD") go last.
    val shown = if (spotlight) emptySet() else hero.map { it.id }.toSet()
    val rest = games.filter { it.id !in shown }
    val live = rest.filter { it.isLive }
    val upcoming = rest.filter { it.state == GameState.PRE }.sortedWith(compareBy<Game> { it.undecided }.thenBy { it.startMillis })
    val finished = rest.filter { it.state == GameState.POST }.sortedByDescending { it.startMillis }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        // "Spotlight" layout: the big banner. "Rows": the same featured games as a normal row of cards.
        if (hero.isNotEmpty()) item { if (spotlight) HeroCarousel(hero, counts, onOpen) else CardRow("Featured", hero, counts, onOpen) }
        if (live.isNotEmpty()) item { CardRow("Live", live, counts, onOpen) }
        if (upcoming.isNotEmpty()) item { CardRow("Upcoming", upcoming, counts, onOpen) }
        if (finished.isNotEmpty()) item { CardRow("Finished", finished, counts, onOpen) }
        if (!wide) item { Spacer(Modifier.height(8.dp)) }
    }
}

private fun teamColor(t: Team, fallback: Color): Color =
    t.color?.takeIf { it.length == 6 }?.let { runCatching { Color(("FF$it").toLong(16)) }.getOrNull() } ?: fallback

@Composable
private fun HeroCarousel(games: List<Game>, counts: Map<String, Int>, onOpen: (Game) -> Unit) {
    val wide = LocalWide.current
    val pager = rememberPagerState { games.size }
    LaunchedEffect(pager, games.size) {
        while (games.size > 1) { delay(8000); pager.animateScrollToPage((pager.currentPage + 1) % games.size) }
    }
    Box(Modifier.fillMaxWidth().height(if (wide) 420.dp else 330.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page -> Hero(games[page], counts[games[page].id], onOpen) }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(games.size) { i ->
                Box(Modifier.height(5.dp).width(if (i == pager.currentPage) 22.dp else 6.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) Color.White else Color(0x66FFFFFF)))
            }
        }
    }
}

@Composable
private fun Hero(g: Game, channels: Int?, onOpen: (Game) -> Unit) {
    val wide = LocalWide.current
    val homeFirst = g.league.sport == Sport.SOCCER
    val left = if (homeFirst) g.home else g.away
    val right = if (homeFirst) g.away else g.home
    val lc = teamColor(left, Color(0xFF1E3A8A)); val rc = teamColor(right, Color(0xFF7F1D1D))
    Box(
        Modifier.fillMaxSize()
            .background(Brush.horizontalGradient(listOf(lc.copy(alpha = 0.35f), Color(0xFF0B0B0D), rc.copy(alpha = 0.30f))))
            .background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0x00000000), ArenaColors.Bg)))
            .clickable { onOpen(g) },
    ) {
        Column(Modifier.fillMaxSize().padding(top = if (wide) 22.dp else 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val line = g.note?.takeIf { it.contains(g.league.name, true) } ?: listOfNotNull(g.league.name, g.note).joinToString("  ·  ")
            Text("${g.league.sport.emoji}  $line",
                color = Color(0xCCFFFFFF), fontSize = if (wide) 17.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(if (wide) 22.dp else 14.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = if (wide) 80.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroTeam(left, Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    if (g.state == GameState.PRE) {
                        Text(Time.clock(g.startMillis), fontSize = if (wide) 56.sp else 38.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text(if (g.startMillis - System.currentTimeMillis() < 86400_000) "Kick-off" else g.statusDetail, color = Color(0xCCFFFFFF), fontSize = 16.sp)
                    } else {
                        Text("${left.score ?: 0} – ${right.score ?: 0}", fontSize = if (wide) 72.sp else 46.sp, fontWeight = FontWeight.Bold,
                            color = Color.White, maxLines = 1, softWrap = false)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (g.isLive) { LiveDot(); Spacer(Modifier.width(6.dp)) }
                            Text(if (g.state == GameState.POST && g.statusText in setOf("FT", "Final")) "Full time" else g.statusText,
                                color = if (g.isLive) ArenaColors.Live else Color(0xDDFFFFFF), fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                HeroTeam(right, Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            if (g.state != GameState.POST) {
                Text(
                    when {
                        channels == null -> "Finding it on your channels…"
                        channels == 0 -> "Not found on your channels yet"
                        else -> "On $channels of your channels"
                    },
                    color = if ((channels ?: 0) > 0) ArenaColors.Accent else Color(0xCCFFFFFF), fontSize = 16.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
            GhostButton("Match centre", { onOpen(g) })
            Spacer(Modifier.height(if (wide) 44.dp else 30.dp))
        }
    }
}

@Composable
private fun HeroTeam(t: Team, modifier: Modifier) {
    val wide = LocalWide.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Logo(t.logo, t.displayName, if (wide) 118.dp else 72.dp)
        Spacer(Modifier.height(10.dp))
        Text(t.shortName, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = if (wide) 26.sp else 17.sp,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun CardRow(title: String, games: List<Game>, counts: Map<String, Int>, onOpen: (Game) -> Unit) {
    val wide = LocalWide.current
    Column(Modifier.padding(top = 18.dp)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (title == "Live") { LiveDot(); Spacer(Modifier.width(8.dp)) }
            Text(title, fontSize = if (wide) 24.sp else 19.sp, fontWeight = FontWeight.Bold)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(games, key = { title + it.id }) { g -> ScoreCard(g, counts[g.id], onOpen, Modifier.width(if (wide) 360.dp else 290.dp)) }
        }
    }
}

@Composable
fun ScoreCard(g: Game, channels: Int?, onOpen: (Game) -> Unit, modifier: Modifier = Modifier) {
    val homeFirst = g.league.sport == Sport.SOCCER
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(ArenaColors.Surface).clickable { onOpen(g) }
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (g.state) {
                GameState.LIVE -> { LiveDot(); Spacer(Modifier.width(6.dp)); Text(g.statusText, color = ArenaColors.Live, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                GameState.PRE -> Text(Time.clock(g.startMillis), color = ArenaColors.Muted, fontSize = 15.sp)
                GameState.POST -> Text(if (g.statusText == "Final") "FT" else g.statusText, color = ArenaColors.Muted, fontSize = 15.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("${g.league.sport.emoji} ${g.league.shortName.takeIf { it.length > 4 } ?: g.league.name}",
                color = ArenaColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        CardTeam(if (homeFirst) g.home else g.away, g)
        Spacer(Modifier.height(8.dp))
        CardTeam(if (homeFirst) g.away else g.home, g)
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = ArenaColors.SurfaceHi)
        if ((channels ?: 0) > 0 && g.state != GameState.POST) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tv, null, tint = ArenaColors.Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("On $channels of your channels", color = ArenaColors.Accent, fontSize = 14.sp)
            }
        } else {
            Text(if (g.state == GameState.POST) "Score only" else if (channels == null) "Checking channels…" else "No channel found", color = ArenaColors.Muted, fontSize = 14.sp)
        }
        if (g.state == GameState.PRE) g.odds?.let { o ->
            Text("Odds  ${oddsLine(o, LocalDecimalOdds.current)}", color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun CardTeam(t: Team, g: Game) {
    val other = if (t === g.home) g.away else g.home
    val dim = g.state == GameState.POST && !t.winner && other.winner
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(t.logo, t.displayName, 28.dp)
        Spacer(Modifier.width(12.dp))
        Text(t.displayName, Modifier.weight(1f), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (dim) ArenaColors.Muted else ArenaColors.Text, fontWeight = FontWeight.Medium)
        if (g.state != GameState.PRE) Text(t.score ?: "-", fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
            color = if (dim) ArenaColors.Muted else ArenaColors.Text)
    }
}

/** The Sports center's layout choice: a small button in the top bar. */
@Composable
fun SportsLayoutButton(current: String, onPick: (String) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        com.arena.tv.ui.components.IconAction(androidx.compose.material.icons.Icons.Rounded.Dashboard, "Layout") { open = true }
        androidx.compose.material3.DropdownMenu(open, { open = false }, containerColor = ArenaColors.Surface) {
            listOf("spotlight" to "Spotlight · big featured game", "rows" to "Rows · cards only", "list" to "List · compact").forEach { (v, label) ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { onPick(v); open = false },
                    trailingIcon = if (v == current) ({ androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.Check, null, tint = ArenaColors.Accent) }) else null)
            }
        }
    }
}
