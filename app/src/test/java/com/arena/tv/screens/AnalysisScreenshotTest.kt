package com.arena.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.arena.tv.sports.EspnParser
import com.arena.tv.sports.Leagues
import com.arena.tv.ui.sports.HeadToHeadTab
import com.arena.tv.ui.sports.OddsTab
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.ArenaTheme
import org.junit.Rule
import org.junit.Test

class AnalysisScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)
    private fun res(n: String) = javaClass.classLoader!!.getResource("espn/$n")!!.readText()
    private val game = EspnParser.parseScoreboard(res("soccer_scoreboard.json"), Leagues.byKey("soccer/uefa.champions")!!).first()
    private val details = EspnParser.parseSummary(res("soccer_summary_full.json"), "887")

    @Test fun odds() = paparazzi.snapshot { ArenaTheme { Box(Modifier.fillMaxSize().background(ArenaColors.Bg)) { OddsTab(game, details, true) {} } } }
    @Test fun headToHead() = paparazzi.snapshot { ArenaTheme { Box(Modifier.fillMaxSize().background(ArenaColors.Bg)) { HeadToHeadTab(game, details) } } }
}
