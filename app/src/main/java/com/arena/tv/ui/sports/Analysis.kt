package com.arena.tv.ui.sports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.sports.FormGame
import com.arena.tv.sports.Game
import com.arena.tv.sports.GameDetails
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Odds
import com.arena.tv.sports.Sport
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import java.text.DateFormat
import java.util.Date

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text(text, color = ArenaColors.Muted, textAlign = TextAlign.Center) }

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(ArenaColors.Surface).padding(14.dp), content = content)
}

// ------------------------------------------------------------ odds

/** 1 / X / 2 with implied chances, over/under and how the price moved since opening. */
@Composable
fun OddsTab(g: Game, d: GameDetails?, decimal: Boolean, onFormat: (Boolean) -> Unit) {
    val o = d?.odds ?: g.odds
    if (o == null) { if (d == null) Loading() else Empty("No odds published for this game."); return }
    val draw = g.league.sport == Sport.SOCCER && o.draw != null
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (g.state == GameState.LIVE) "Live odds" else if (g.state == GameState.POST) "Closing odds" else "Match odds",
                    fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                FilterChip(modifier = Modifier.focusOutline(), selected = decimal, onClick = { onFormat(true) }, label = { Text("Decimal") })
                Spacer(Modifier.width(6.dp))
                FilterChip(modifier = Modifier.focusOutline(), selected = !decimal, onClick = { onFormat(false) }, label = { Text("American") })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OddsBox(g.home.shortName, "1", o.home, o.homeOpen, decimal, Modifier.weight(1f))
                if (draw) OddsBox("Draw", "X", o.draw, o.drawOpen, decimal, Modifier.weight(1f))
                OddsBox(g.away.shortName, "2", o.away, o.awayOpen, decimal, Modifier.weight(1f))
            }
        }
        if (o.total != null) item {
            Card {
                Text("Total ${if (g.league.sport == Sport.SOCCER) "goals" else "points"} ${o.total}", fontWeight = FontWeight.SemiBold)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OddsBox("Over", "O", o.over, null, decimal, Modifier.weight(1f))
                    OddsBox("Under", "U", o.under, null, decimal, Modifier.weight(1f))
                }
            }
        }
        o.spread?.takeIf { it.isNotBlank() }?.let { item { Text("Line: $it", color = ArenaColors.Muted) } }
        item {
            Text("Odds from ${o.provider}, via the ESPN scores feed. For information only; they change often.",
                color = ArenaColors.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OddsBox(label: String, mark: String, value: Int?, open: Int?, decimal: Boolean, modifier: Modifier) {
    val fav = value != null && value < 0
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(ArenaColors.Surface)
            .then(if (fav) Modifier.border(1.dp, ArenaColors.Accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp)) else Modifier)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("$mark  ·  $label", color = ArenaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(Odds.format(value, decimal), fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        Odds.implied(value)?.let { Text("$it% chance", color = ArenaColors.Muted, fontSize = 12.sp) }
        if (value != null && open != null && open != value) {
            // Lower decimal price = more likely now.
            val shorter = Odds.decimal(value) < Odds.decimal(open)
            Text((if (shorter) "▼ " else "▲ ") + "from ${Odds.format(open, decimal)}", fontSize = 12.sp,
                color = if (shorter) ArenaColors.Accent else ArenaColors.Live)
        }
    }
}

/** One-line odds for cards: "1.85 · 3.40 · 4.20". */
fun oddsLine(o: Odds?, decimal: Boolean): String? = o?.let {
    listOfNotNull(it.home, it.draw, it.away).joinToString("  ·  ") { v -> Odds.format(v, decimal) }
}

// ------------------------------------------------------------ form + head to head

@Composable
fun FormDots(form: List<FormGame>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        form.take(5).forEach { f ->
            val c = when (f.result) { "W" -> ArenaColors.Accent; "L" -> ArenaColors.Live; else -> ArenaColors.Muted }
            Box(Modifier.size(22.dp).clip(CircleShape).background(c.copy(alpha = 0.22f)), Alignment.Center) {
                Text(f.result, color = c, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun HeadToHeadTab(g: Game, d: GameDetails?) {
    if (d == null) { Loading(); return }
    if (d.homeForm.isEmpty() && d.h2h.isEmpty()) { Empty("No form or head-to-head data for this game."); return }
    val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (d.homeForm.isNotEmpty() || d.awayForm.isNotEmpty()) item {
            Card {
                Text("Form (last 5)", fontWeight = FontWeight.SemiBold)
                listOf(g.home to d.homeForm, g.away to d.awayForm).forEach { (t, form) ->
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Logo(t.logo, t.displayName, 24.dp); Spacer(Modifier.width(10.dp))
                        Text(t.shortName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        FormDots(form)
                    }
                }
            }
        }
        listOf(g.home to d.homeForm, g.away to d.awayForm).forEach { (t, form) ->
            if (form.isEmpty()) return@forEach
            item(key = "f" + t.id) {
                Card {
                    Text("${t.shortName} — recent games", fontWeight = FontWeight.SemiBold)
                    form.forEach { f ->
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FormDots(listOf(f)); Spacer(Modifier.width(10.dp))
                            Text((if (f.home) "vs " else "at ") + f.opponent, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.score, fontWeight = FontWeight.Bold)
                            Text("  " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(f.date)), color = ArenaColors.Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        if (d.h2h.isNotEmpty()) item {
            Card {
                Text("Head to head" + (d.h2hSummary?.let { " · $it" } ?: ""), fontWeight = FontWeight.SemiBold)
                d.h2h.forEach { h ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(df.format(Date(h.date)), color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                        Text(h.homeName, Modifier.weight(1f), textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("  ${h.homeScore} – ${h.awayScore}  ", fontWeight = FontWeight.Bold)
                        Text(h.awayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ players

@Composable
fun PlayersTab(g: Game, d: GameDetails?) {
    if (d == null) { Loading(); return }
    if (d.leaders.isEmpty()) { Empty(if (g.state == GameState.PRE) "Top players appear once the game starts." else "No player stats for this game."); return }
    val cats = d.leaders.map { it.category }.distinct()
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row {
                Text(g.home.shortName, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text(g.away.shortName, Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
            }
        }
        items(cats) { cat ->
            val h = d.leaders.firstOrNull { it.category == cat && it.isHome }
            val a = d.leaders.firstOrNull { it.category == cat && !it.isHome }
            Card {
                Text(cat, color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        h?.let { Logo(it.headshot, it.player, 34.dp, round = true); Spacer(Modifier.width(8.dp))
                            Column { Text(it.player, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(it.value, fontWeight = FontWeight.Bold, fontSize = 18.sp) } }
                    }
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        a?.let { Column(horizontalAlignment = Alignment.End) { Text(it.player, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(it.value, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
                            Spacer(Modifier.width(8.dp)); Logo(it.headshot, it.player, 34.dp, round = true) }
                    }
                }
            }
        }
    }
}

