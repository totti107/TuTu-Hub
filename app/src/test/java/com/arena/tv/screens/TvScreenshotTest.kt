package com.arena.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.arena.tv.sports.EspnParser
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Leagues
import com.arena.tv.ui.components.LocalCompactChrome
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.PaneItem
import com.arena.tv.ui.components.PaneLabel
import com.arena.tv.ui.components.PillStrip
import com.arena.tv.ui.components.PillTabs
import com.arena.tv.ui.components.panel
import com.arena.tv.ui.sports.GameRows
import com.arena.tv.ui.sports.MatchHero
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.ArenaTheme
import org.junit.Rule
import org.junit.Test

/** 1080p TV at 2x (960 x 540 dp), like the user's Sony. */
class TvScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 1920, screenHeight = 1080, density = Density.XHIGH),
        maxPercentDifference = 0.1,
    )
    private fun res(n: String) = javaClass.classLoader!!.getResource("espn/$n")!!.readText()

    @Test fun sportsModeOnTv() {
        val soccer = EspnParser.parseScoreboard(res("soccer_scoreboard.json"), Leagues.byKey("soccer/uefa.champions")!!)
        val hero = soccer[0].copy(state = GameState.LIVE, statusText = "33'")
        val games = listOf(hero) + soccer.drop(1).map { it.copy(state = GameState.LIVE, statusText = "32'") }
        paparazzi.snapshot {
            ArenaTheme {
                CompositionLocalProvider(LocalWide provides true, LocalCompactChrome provides true) {
                    Column(Modifier.fillMaxSize().background(ArenaColors.Bg)) {
                        PillStrip()
                        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LazyColumn(Modifier.weight(0.24f).fillMaxHeight().panel(), contentPadding = PaddingValues(10.dp)) {
                                item { PillTabs(listOf("Library", "EPG", "Sports Mode"), 2, compact = true) {} }
                                item { PaneItem("All today", 229, true, {}) }
                                item { PaneItem("Live now", 99, false, {}) }
                                item { PaneLabel("All sports") }
                                items(listOf("Premier League", "LaLiga", "Serie A", "Bundesliga")) { PaneItem(it, 4, false, {}) }
                            }
                            BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight()) {
                                val heroH = (maxHeight * 0.46f).coerceIn(190.dp, 330.dp)
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MatchHero(hero, 23, Modifier.fillMaxWidth().height(heroH)) {}
                                    Column(Modifier.fillMaxWidth().weight(1f).panel()) { GameRows(games, hero) {} }
                                }
                            }
                            Column(Modifier.weight(0.34f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.fillMaxWidth().panel().padding(10.dp)) {
                                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1F4D2B)))
                                }
                                Column(Modifier.fillMaxWidth().weight(1f).panel().padding(14.dp)) { Text("Channels for Arsenal v Hull City", fontSize = 14.sp) }
                            }
                        }
                    }
                }
            }
        }
    }
}
