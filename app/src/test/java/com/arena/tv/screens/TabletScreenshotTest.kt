package com.arena.tv.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.ProgramEntity
import com.arena.tv.data.VodDetails
import com.arena.tv.data.VodEntity
import com.arena.tv.data.VodKind
import com.arena.tv.sports.EspnParser
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Leagues
import com.arena.tv.ui.components.ArenaTopBar
import com.arena.tv.ui.components.DropChip
import com.arena.tv.ui.components.IconAction
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.PaneItem
import com.arena.tv.ui.components.PaneLabel
import com.arena.tv.ui.components.PillTabs
import com.arena.tv.ui.components.TabDivider
import com.arena.tv.ui.components.ToggleLabel
import com.arena.tv.ui.components.panel
import com.arena.tv.ui.live.ChannelCard
import com.arena.tv.ui.live.NowNext
import com.arena.tv.ui.live.ScheduleList
import com.arena.tv.ui.sports.ForYou
import com.arena.tv.ui.sports.GameRows
import com.arena.tv.ui.sports.MatchHero
import com.arena.tv.ui.sports.SportSheet
import com.arena.tv.ui.components.BrandPill
import androidx.compose.ui.Alignment
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.ArenaTheme
import com.arena.tv.ui.vod.HeroContent
import com.arena.tv.ui.vod.PosterCard
import org.junit.Rule
import org.junit.Test

/** Tablet / TV layouts (run: ./gradlew :app:recordPaparazziDebug). */
class TabletScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.NEXUS_10.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2560, screenHeight = 1600),
        maxPercentDifference = 0.1,
    )
    private fun res(n: String) = javaClass.classLoader!!.getResource("espn/$n")!!.readText()
    private val now = System.currentTimeMillis()

    @Composable
    private fun Wide(content: @Composable () -> Unit) = ArenaTheme {
        CompositionLocalProvider(LocalWide provides true) {
            Box(Modifier.fillMaxSize().background(ArenaColors.Bg)) { content() }
        }
    }

    @Test fun sportsForYou() {
        val soccer = EspnParser.parseScoreboard(res("soccer_scoreboard.json"), Leagues.byKey("soccer/uefa.champions")!!)
        val nba = EspnParser.parseScoreboard(res("nba_scoreboard.json"), Leagues.byKey("basketball/nba")!!)
        val live = soccer[0].copy(state = GameState.LIVE, statusText = "67'")
        val games = listOf(live) + soccer.drop(1).map { it.copy(state = GameState.PRE, startMillis = now + 3600_000) } + nba
        paparazzi.snapshot {
            Wide {
                Column {
                    ArenaTopBar(
                        left = { PillTabs(listOf("For you", "Scores", "Following"), 0) {}; TabDivider(); ToggleLabel("Mine", false) {} },
                        right = {
                            IconAction(Icons.Rounded.Search, "") {}
                            DropChip("Soccer", listOf(1 to "Soccer"), 1) {}
                            DropChip("Today", listOf(1 to "Today"), 1) {}
                            DropChip("All countries", listOf(1 to "All"), 1) {}
                            IconAction(Icons.Rounded.Refresh, "") {}
                        },
                    )
                    ForYou(games, emptySet(), mapOf(live.id to 3, soccer[1].id to 2, soccer[2].id to 0)) {}
                }
            }
        }
    }

    private fun ch(i: Int, name: String) = ChannelEntity("$i", "p1", true, i, name, null, "EN | 4K UHD", "http://x", null, null, "k$i", null, null)
    private fun prog(key: String, title: String, start: Long, mins: Int) = ProgramEntity(start, key, start, start + mins * 60_000L, title, null, "Featuring all the action.", null)

    @Test fun liveLibrary() {
        val channels = listOf("UK: TNT Sports Ultimate 4K UHD", "UK: Sky Sports Main Event UHD", "UK: Sky Sports F1 UHD", "UK: Sky Sports UHD 1 (Events Only)",
            "US: BTN 4K UHD", "US: CBS 4K UHD (Event Only)").mapIndexed { i, n -> ch(i, n) }
        val nn = channels.associate { it.id to NowNext(prog(it.epgKey!!, "Live coverage on ${it.name.substringAfter(": ").take(14)}", now - 40 * 60_000, 120),
            prog(it.epgKey!!, "Sky Sports News", now + 80 * 60_000, 60)) }
        val schedule = listOf("Mexico City Grand Prix: Highlights", "Jamie Chadwick Realises an F1 Dream", "The Best Of Monaco 2026",
            "The Best Of Barcelona 2026", "The Best Of Austria 2026", "The Best Of Britain 2026", "The Best of Hungary 2026", "The Best Of Australia 2026")
            .mapIndexed { i, t -> prog("k2", "Formula 1: $t", now - (4 - i) * 30 * 60_000L - 10 * 60_000, 30) }
        paparazzi.snapshot {
            Wide {
                Column(Modifier.fillMaxSize()) {
                    com.arena.tv.ui.components.PillStrip()
                    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LazyColumn(Modifier.weight(0.25f).fillMaxHeight().panel(), contentPadding = PaddingValues(12.dp)) {
                            item { Box(Modifier.padding(bottom = 10.dp)) { PillTabs(listOf("Library", "EPG", "Sports Mode"), 0, compact = true) {} } }
                            item { PaneItem("All Channels", 27842, false, {}) }
                            item { PaneLabel("My collection") }
                            item { PaneItem("Favorites", 0, false, {}) }
                            item { PaneItem("Recent", 8, false, {}) }
                            item { PaneLabel("Playlist categories") }
                            items(listOf("EN | 4K UHD 🏆" to 38, "INTL | 4K UHD 🏆" to 92, "Live Pay-Per View 🎟" to 40, "NFL Game Pass 🏈" to 16)) { (n, c) ->
                                PaneItem(n, c, n.startsWith("EN"), {})
                            }
                        }
                        Column(Modifier.weight(0.33f).fillMaxHeight().panel()) {
                            Row(Modifier.padding(start = 8.dp, top = 6.dp)) { IconAction(Icons.Rounded.Search, "") {}; IconAction(Icons.Rounded.Hd, "") {}; IconAction(Icons.Rounded.Refresh, "") {} }
                            LazyColumn(contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(channels) { i, c -> ChannelCard(i + 1, c, nn[c.id], i == 2, false, {}, {}) }
                            }
                        }
                        Column(Modifier.weight(0.42f).fillMaxHeight().panel().padding(12.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF3A1010)))
                            Spacer(Modifier.height(12.dp))
                            Text("UK: Sky Sports F1 UHD", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                            ScheduleList("2", schedule, now)
                        }
                    }
                }
            }
        }
    }

    @Test fun sportsMode() {
        val soccer = EspnParser.parseScoreboard(res("soccer_scoreboard.json"), Leagues.byKey("soccer/uefa.champions")!!)
        val nba = EspnParser.parseScoreboard(res("nba_scoreboard.json"), Leagues.byKey("basketball/nba")!!)
        val ufc = EspnParser.parseScoreboard(res("mma_scoreboard.json"), Leagues.byKey("mma/ufc")!!)
        val hero = soccer[1].copy(state = GameState.PRE, startMillis = now + 8 * 60_000 + 22_000)
        val games = listOf(hero) + soccer.drop(2).map { it.copy(state = GameState.PRE, startMillis = now + 3600_000) } + ufc + nba + soccer[0]
        paparazzi.snapshot {
            Wide {
                Column(Modifier.fillMaxSize()) {
                    com.arena.tv.ui.components.PillStrip()
                    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LazyColumn(Modifier.weight(0.24f).fillMaxHeight().panel(), contentPadding = PaddingValues(10.dp)) {
                            item { PillTabs(listOf("Library", "EPG", "Sports Mode"), 2, compact = true) {} }
                            item { Spacer(Modifier.height(10.dp)) }
                            item { PaneItem("All today", 67, true, {}) }
                            item { PaneItem("Live now", 0, false, {}) }
                            item { PaneItem("Sports channels", null, false, {}) }
                            item { PaneLabel("All sports") }
                            items(listOf("Premier League", "LaLiga", "Champions League", "Bundesliga", "UFC", "NBA")) { PaneItem(it, 1, false, {}) }
                        }
                        Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MatchHero(hero, null, Modifier.fillMaxWidth().height(330.dp)) {}
                            Column(Modifier.fillMaxWidth().weight(1f).panel()) {
                                Row(Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
                                    .background(Color(0xFF141417)).padding(horizontal = 14.dp, vertical = 4.dp)) {
                                    listOf("Score", "Lineups", "Stats", "Events", "Table").forEachIndexed { i, l ->
                                        Text(l, fontSize = 18.sp, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Medium,
                                            color = if (i == 0) Color.White else ArenaColors.Muted, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                                    }
                                }
                                GameRows(games, hero) {}
                            }
                        }
                        Column(Modifier.weight(0.34f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.fillMaxWidth().panel().padding(10.dp)) {
                                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black), Alignment.Center) {
                                    Text("No signal", fontSize = 20.sp)
                                }
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                                    IconAction(Icons.Rounded.Search, "") {}
                                    Text("All sports ▾", fontSize = 17.sp, modifier = Modifier.padding(8.dp))
                                    DropChip("Today", listOf(1 to "Today"), 1) {}
                                    IconAction(Icons.Rounded.Refresh, "") {}
                                }
                            }
                            Column(Modifier.fillMaxWidth().weight(1f).panel().padding(14.dp)) {
                                Text("Channels for ${hero.headline}", color = ArenaColors.Muted, fontSize = 16.sp, maxLines = 1)
                                Text("Not found on your channels. Pick one and the app will remember it.", fontSize = 17.sp, modifier = Modifier.padding(vertical = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun sportSheet() {
        val soccer = EspnParser.parseScoreboard(res("soccer_scoreboard.json"), Leagues.byKey("soccer/uefa.champions")!!)
        val ufc = EspnParser.parseScoreboard(res("mma_scoreboard.json"), Leagues.byKey("mma/ufc")!!)
        paparazzi.snapshot { Wide { SportSheet(soccer + ufc, com.arena.tv.sports.Sport.SOCCER, {}) {} } }
    }

    private fun vod(i: Int, name: String, rating: Double?, year: Int?) =
        VodEntity("m:$i", "p1", true, VodKind.MOVIE, "$i", i, name, null, "1", rating, year, now, "mkv", null, null, null, "Drama", null)

    @Test fun moviesSpotlight() {
        val hero = vod(1, "4K: Ishqa'n De Lekhe (2026)", 9.6, 2026)
        val d = VodDetails("A love story.", "Gurnam Bhullar, Isha Malviya, Rana Ranbir", null, "Drama", 2026, 8580, 10.0, null, null, "abcdefghijk", null)
        val row = listOf("10 Cent Pistol", "10 Kilos in the Trunk", "10 Minutes Gone", "10,000 BC", "10.0 Earthquake", "100 Bullets", "100 Below Zero")
            .mapIndexed { i, n -> vod(i + 2, n, listOf(8.0, 7.0, 10.0, 9.0, 6.0)[i % 5], 2014 + i) }
        paparazzi.snapshot {
            Wide {
                Column {
                    Box(Modifier.fillMaxWidth().height(560.dp)) {
                        HeroContent(hero, d, false, {}, {}, {}, {})
                        Box(Modifier.padding(start = 0.dp)) {
                            ArenaTopBar(left = { PillTabs(listOf("Spotlight", "Library"), 0) {} },
                                right = { IconAction(Icons.Rounded.Search, "") {}; DropChip("Year", listOf(1 to "Year"), 1) {} })
                        }
                    }
                    Text("My Collections", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp, 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(row) { PosterCard(it, null, it.key == "m:3", Modifier.width(200.dp)) {} }
                    }
                }
            }
        }
    }

    @Test fun moviesLibrary() {
        val items = (0 until 12).map { vod(it, listOf("10 Cent Pistol", "10 Kilos in the Trunk", "10 Minutes Gone", "10,000 BC")[it % 4], 6.0 + it % 5, 2010 + it) }
        paparazzi.snapshot {
            Wide {
                Column {
                    ArenaTopBar(left = { PillTabs(listOf("Spotlight", "Library"), 1) {}; DropChip("Sort: Library order", listOf(1 to "x"), 1) {} },
                        right = { IconAction(Icons.Rounded.Search, "") {}; DropChip("Year", listOf(1 to "Year"), 1) {} })
                    Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LazyColumn(Modifier.width(330.dp).fillMaxHeight().panel(), contentPadding = PaddingValues(12.dp)) {
                            item { PaneItem("All Movies", 138304, false, {}) }
                            item { PaneLabel("My collections") }
                            item { PaneItem("Favorites", null, false, {}) }
                            item { PaneItem("Continue watching", null, false, {}) }
                            item { PaneLabel("Categories") }
                            items(listOf("Action", "Comedy", "Documentary", "Drama", "Family")) { PaneItem(it, null, it == "Action", {}) }
                        }
                        LazyVerticalGrid(GridCells.Adaptive(200.dp), Modifier.weight(1f).fillMaxHeight().panel(), contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(items) { PosterCard(it, null, false, Modifier.fillMaxWidth()) {} }
                        }
                    }
                }
            }
        }
    }
}
