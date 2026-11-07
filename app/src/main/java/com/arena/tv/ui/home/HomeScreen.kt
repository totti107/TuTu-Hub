package com.arena.tv.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.arena.tv.data.ChannelEntity
import com.arena.tv.sports.Game
import com.arena.tv.ui.appViewModel
import com.arena.tv.ui.components.ArenaTopBar
import com.arena.tv.ui.components.IconAction
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import kotlinx.coroutines.delay

/** Where Home's tiles lead. */
class HomeActions(
    val live: (request: String?) -> Unit,
    val sports: () -> Unit,
    val movies: () -> Unit,
    val series: () -> Unit,
    val game: (Game) -> Unit,
    val vod: (String) -> Unit,
    val settings: () -> Unit,
)

/** Everything Home shows, gathered once so the tiles stay simple (and can be previewed with sample data). */
data class HomeData(
    val channels: Int = 0, val movies: Int = 0, val series: Int = 0, val favorites: Int = 0, val hasSource: Boolean = true,
    val logos: List<ChannelEntity> = emptyList(), val moviePosters: List<String> = emptyList(), val seriesPosters: List<String> = emptyList(),
    val resume: Pair<com.arena.tv.data.VodEntity, com.arena.tv.data.ProgressEntity>? = null, val games: List<Game> = emptyList(),
    val reload: () -> Unit = {}, val checkUpdates: () -> Unit = {},
) { val liveCount get() = games.count { it.isLive } }

private val TIPS = listOf(
    "Tap a live game to see which of your channels show it",
    "Hold OK on a channel to add it to your favorites",
    "Follow your teams in Settings → Sports to see their games first",
    "Channels stutter? Settings → Playback → Buffer size → High",
    "Moving to a new TV? Settings → Backup & restore",
    "Turn off categories you never watch in Settings → Playlists",
    "Odds can be shown as decimal or American in Settings → Sports",
)

private fun big(n: Int): String = when {
    n >= 10_000 -> "${n / 1000}K+"
    n >= 1_000 -> "%.1fK+".format(n / 1000.0).replace(".0K", "K")
    else -> "$n"
}

@Composable
fun HomeScreen(a: HomeActions) {
    val vm = appViewModel { HomeViewModel(it) }
    LaunchedEffect(Unit) { vm.pollScores() }
    val channels by vm.channels.collectAsStateWithLifecycle()
    val movies by vm.movies.collectAsStateWithLifecycle()
    val series by vm.series.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val hasSource by vm.hasSource.collectAsStateWithLifecycle()
    val logos by vm.logos.collectAsStateWithLifecycle()
    val mp by vm.moviePosters.collectAsStateWithLifecycle()
    val sp by vm.seriesPosters.collectAsStateWithLifecycle()
    val resume by vm.resume.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    HomeContent(HomeData(channels, movies, series, favorites, hasSource, logos, mp, sp, resume, games, { vm.reload() }, { vm.checkUpdates() }), a)
}

@Composable
fun HomeContent(d: HomeData, a: HomeActions) {
    val vm = d
    val wide = LocalWide.current
    val tip = remember { TIPS[(System.currentTimeMillis() / 86_400_000L % TIPS.size).toInt()] }
    Column(Modifier.fillMaxSize()) {
        ArenaTopBar(
            left = {
                Icon(Icons.Rounded.AutoAwesome, null, tint = ArenaColors.Accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Today's tip", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            },
            right = { IconAction(Icons.Rounded.Settings, "Settings", onClick = a.settings) },
        )
        Text(tip, color = ArenaColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = if (wide) 42.dp else 16.dp, end = 16.dp, bottom = 6.dp).offset(y = if (wide) (-12).dp else 0.dp))
        if (wide) WideHome(vm, a) else PhoneHome(vm, a)
    }
}

@Composable
private fun WideHome(vm: HomeData, a: HomeActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 20.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().weight(1.25f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LiveTile(vm, a, Modifier.weight(1.55f).focusRequester(first))
            PosterTile("Movies", Icons.Rounded.Movie, vm.movies, vm.moviePosters, "Titles", a.movies, Modifier.weight(1.2f))
            PosterTile("Series", Icons.Rounded.Tv, vm.series, vm.seriesPosters, "Shows", a.series, Modifier.weight(1.2f))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GuideTile(a, Modifier.weight(1.25f))
            ScoreTile(vm, a, Modifier.weight(0.8f))
            FavoritesTile(vm, a, Modifier.weight(0.75f))
            ResumeTile(vm, a, Modifier.weight(1.15f))
            SportsTile(vm, a, Modifier.weight(0.9f))
        }
        Row(Modifier.fillMaxWidth().height(62.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            SmallTile("Playlists", "Manage playlists", Icons.AutoMirrored.Rounded.PlaylistPlay, a.settings, Modifier.weight(1f))
            SmallTile("Reload", "Channels & guide", Icons.Rounded.Refresh, vm.reload, Modifier.weight(1f))
            SmallTile("Updates", "Get the newest version", Icons.Rounded.SystemUpdate, vm.checkUpdates, Modifier.weight(1f))
            SmallTile("Settings", "Playback & backup", Icons.Rounded.Settings, a.settings, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PhoneHome(vm: HomeData, a: HomeActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LiveTile(vm, a, Modifier.fillMaxWidth().height(180.dp))
        Row(Modifier.height(170.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PosterTile("Movies", Icons.Rounded.Movie, vm.movies, vm.moviePosters, "Titles", a.movies, Modifier.weight(1f))
            PosterTile("Series", Icons.Rounded.Tv, vm.series, vm.seriesPosters, "Shows", a.series, Modifier.weight(1f))
        }
        Row(Modifier.height(130.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ScoreTile(vm, a, Modifier.weight(1f))
            SportsTile(vm, a, Modifier.weight(1f))
        }
        Row(Modifier.height(130.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GuideTile(a, Modifier.weight(1f))
            FavoritesTile(vm, a, Modifier.weight(1f))
        }
        ResumeTile(vm, a, Modifier.fillMaxWidth().height(130.dp))
        Row(Modifier.height(62.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallTile("Playlists", null, Icons.AutoMirrored.Rounded.PlaylistPlay, a.settings, Modifier.weight(1f))
            SmallTile("Reload", null, Icons.Rounded.Refresh, vm.reload, Modifier.weight(1f))
        }
        Row(Modifier.height(62.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallTile("Updates", null, Icons.Rounded.SystemUpdate, vm.checkUpdates, Modifier.weight(1f))
            SmallTile("Settings", null, Icons.Rounded.Settings, a.settings, Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------ tiles

private val TileShape = RoundedCornerShape(24.dp)

@Composable
private fun Tile(modifier: Modifier, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.fillMaxHeight().focusOutline(TileShape).clip(TileShape)
            .background(Brush.linearGradient(listOf(Color(0xFF1D1E22), Color(0xFF111214))))
            .border(1.dp, Color(0x16FFFFFF), TileShape)
            .clickable(onClick = onClick),
        content = content,
    )
}

@Composable
private fun IconBubble(icon: ImageVector, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(0x1FFFFFFF)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun LiveTile(vm: HomeData, a: HomeActions, modifier: Modifier) {
    val count = vm.channels; val logos = vm.logos; val hasSource = vm.hasSource
    Tile(modifier, { if (hasSource) a.live(null) else a.settings() }) {
        Row(Modifier.fillMaxSize().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(ArenaColors.Accent))
                    Text("  LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                }
                Column {
                    Text("Live TV", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(if (hasSource) "${big(count)} Channels" else "Add a playlist to start", color = ArenaColors.Muted, fontSize = 16.sp)
                }
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                    Text(if (hasSource) " Watch Now" else " Add playlist", color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
            if (logos.isNotEmpty()) LogoWall(logos, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun LogoWall(logos: List<ChannelEntity>, modifier: Modifier) {
    Column(modifier.rotate(-6f), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        logos.chunked(3).take(2).forEachIndexed { row, list ->
            Row(Modifier.offset(x = if (row == 1) (-18).dp else 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                list.forEach { ch ->
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A2B30)).padding(7.dp), contentAlignment = Alignment.Center) {
                        Logo(ch.logo, ch.name, 42.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PosterTile(
    title: String, icon: ImageVector, count: Int, posters: List<String>,
    unit: String, onClick: () -> Unit, modifier: Modifier,
) {
    Tile(modifier, onClick) {
        // Fanned posters on the right, like a hand of cards.
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight(0.82f).padding(end = 14.dp)) {
            posters.reversed().forEachIndexed { i, url ->
                val n = posters.size - 1 - i
                AsyncImage(url, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxHeight().aspectRatio(0.68f).offset(x = (-n * 26).dp).rotate(-n * 7f)
                        .clip(RoundedCornerShape(10.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp)))
            }
        }
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, 36.dp); Spacer(Modifier.width(10.dp))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.align(Alignment.BottomStart).background(Brush.horizontalGradient(listOf(Color(0xE6121315), Color.Transparent))).padding(18.dp)) {
            Text(if (count > 0) big(count) else "—", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text(if (count > 0) unit else "Not loaded", color = ArenaColors.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun GuideTile(a: HomeActions, modifier: Modifier) = Tile(modifier, { a.live("guide") }) {
    Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Rounded.CalendarMonth, 36.dp); Spacer(Modifier.width(10.dp))
            Text("TV Guide", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("What's on now and next on every channel", color = ArenaColors.Muted, fontSize = 14.sp)
    }
}

@Composable
private fun ScoreTile(vm: HomeData, a: HomeActions, modifier: Modifier) {
    val games = vm.games
    var i by remember { mutableIntStateOf(0) }
    LaunchedEffect(games.size) { i = 0; while (games.size > 1) { delay(6_000); i = (i + 1) % games.size } }
    val g = games.getOrNull(i)
    Tile(modifier, { if (g != null) a.game(g) else a.sports() }) {
        if (g == null) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.SportsSoccer, null, tint = ArenaColors.Muted)
                Text("No games right now", color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        } else AnimatedContent(g, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "score") { game ->
            Column(Modifier.fillMaxSize().padding(10.dp).clip(RoundedCornerShape(16.dp)).border(2.dp, Color.White, RoundedCornerShape(16.dp))
                .background(Color(0xFF0B0B0D)).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                ScoreLine(game.home.logo, game.home.abbreviation.ifBlank { game.home.shortName }, game.home.score, game.state != com.arena.tv.sports.GameState.PRE)
                ScoreLine(game.away.logo, game.away.abbreviation.ifBlank { game.away.shortName }, game.away.score, game.state != com.arena.tv.sports.GameState.PRE)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (game.isLive) Box(Modifier.size(7.dp).clip(CircleShape).background(ArenaColors.Accent))
                    Text(if (game.state == com.arena.tv.sports.GameState.PRE) " " + com.arena.tv.ui.components.Time.clock(game.startMillis) else " ${game.statusText}",
                        color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (games.size > 1) Text("${games.indexOf(game) + 1}/${games.size}", color = ArenaColors.Muted, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ScoreLine(logo: String?, name: String, score: String?, showScore: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(logo, name, 26.dp, round = true); Spacer(Modifier.width(8.dp))
        Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showScore) Text(score ?: "0", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
    }
}

@Composable
private fun FavoritesTile(vm: HomeData, a: HomeActions, modifier: Modifier) {
    val n = vm.favorites
    Tile(modifier, { a.live("favorites") }) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            IconBubble(Icons.Rounded.Star, 36.dp)
            Column {
                Text("Favorites", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (n == 0) "No favorites yet" else "$n channel${if (n == 1) "" else "s"}", color = ArenaColors.Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ResumeTile(vm: HomeData, a: HomeActions, modifier: Modifier) {
    val item = vm.resume
    Tile(modifier, { if (item != null) a.vod(item.first.key) else a.movies() }) {
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item?.first?.poster != null) {
                AsyncImage(item.first.poster, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxHeight().aspectRatio(0.68f).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp))
            } else { IconBubble(Icons.Rounded.History, 36.dp); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text("Continue watching", color = ArenaColors.Muted, fontSize = 12.sp)
                Text(item?.first?.name ?: "Nothing in progress", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                item?.second?.let { p ->
                    if (p.season != null) Text("S${p.season} · E${p.episode}", color = ArenaColors.Muted, fontSize = 12.sp)
                    LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = ArenaColors.Accent, trackColor = Color(0x33FFFFFF))
                }
            }
        }
    }
}

@Composable
private fun SportsTile(vm: HomeData, a: HomeActions, modifier: Modifier) {
    val live = vm.liveCount
    Tile(modifier, a.sports) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            IconBubble(Icons.Rounded.SportsSoccer, 36.dp)
            Column {
                Text("Sports center", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(if (live > 0) "$live live now" else "Scores, stats & channels", color = if (live > 0) ArenaColors.Accent else ArenaColors.Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SmallTile(title: String, desc: String?, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) = Tile(modifier, onClick) {
    Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBubble(icon, 34.dp); Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            desc?.let { Text(it, color = ArenaColors.Muted, fontSize = 12.sp, maxLines = 1) }
        }
    }
}
