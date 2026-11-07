package com.arena.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.arena.tv.data.ChannelEntity
import com.arena.tv.matching.ChannelMatch
import com.arena.tv.matching.Tier
import com.arena.tv.sports.EspnParser
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Leagues
import com.arena.tv.ui.sports.GameList
import com.arena.tv.ui.sports.HubState
import com.arena.tv.ui.sports.ScoreHeader
import com.arena.tv.ui.sports.StatsTab
import com.arena.tv.ui.sports.WatchActions
import com.arena.tv.ui.sports.WatchRow
import com.arena.tv.ui.sports.WatchState
import com.arena.tv.ui.sports.WatchTab
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.ArenaTheme
import org.junit.Rule
import org.junit.Test

/** Renders key screens with sample data (run: ./gradlew :app:recordPaparazziDebug). */
class ScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, maxPercentDifference = 0.1)

    private fun res(n: String) = javaClass.classLoader!!.getResource("espn/$n")!!.readText()
    private val soccer = EspnParser.parseScoreboard(res("soccer_scoreboard.json"), Leagues.byKey("soccer/uefa.champions")!!)
    private val nba = EspnParser.parseScoreboard(res("nba_scoreboard.json"), Leagues.byKey("basketball/nba")!!)
    private val liveGame = soccer[0].copy(state = GameState.LIVE, statusText = "67'")
    private val games = listOf(liveGame) + soccer.drop(1).map { it.copy(state = GameState.PRE, startMillis = System.currentTimeMillis() + 3600_000) } + nba

    @Test fun sportsHub() = paparazzi.snapshot {
        ArenaTheme {
            Column(Modifier.fillMaxSize().background(ArenaColors.Bg)) {
                GameList(games, setOf(), HubState(games = games, loading = false, channelCounts = mapOf(liveGame.id to 3, soccer[1].id to 2))) {}
            }
        }
    }

    private fun ch(id: String, name: String, group: String) = ChannelEntity(id, "p1", true, 0, name, null, group, "http://x", null, null, null, null, null)
    private val now = System.currentTimeMillis()
    private val watch = WatchState(
        searching = false, channelCount = 4200, hasGuide = true,
        rows = listOf(
            WatchRow(ch("1", "AR| beIN SPORTS 1 FHD", "Arabic Sports"),
                ChannelMatch("1", 100, Tier.CONFIRMED, listOf("You confirmed this channel", "Both teams in TV guide"), "دوري أبطال أوروبا: أيك أثينا × ريال مدريد", now - 3600_000, now + 1800_000), 1, null),
            WatchRow(ch("2", "UK: TNT Sports 1 HD", "UK Sports"),
                ChannelMatch("2", 96, Tier.STRONG, listOf("Both teams in TV guide", "Starts at kick-off", "Usually shows UCL"), "Live UEFA Champions League: AEK v Real Madrid", now - 3600_000, now + 1800_000), null, null),
            WatchRow(ch("3", "EVENT 04 | AEK Athens vs Real Madrid", "Live Events"),
                ChannelMatch("3", 72, Tier.STRONG, listOf("Both teams in channel name"), null, null, null), null, null),
            WatchRow(ch("4", "US: Paramount+ 02", "USA Events"),
                ChannelMatch("4", 47, Tier.LIKELY, listOf("Listed broadcaster: Paramount+"), null, null, null), null, "UEFA Champions League"),
            WatchRow(ch("5", "UK: TNT Sports 2 HD", "UK Sports"),
                ChannelMatch("5", 27, Tier.USUAL, listOf("Usually shows UEFA Champions League"), null, null, null), null, "Premiership Rugby"),
        ),
    )

    @Test fun gameDetailWatch() = paparazzi.snapshot {
        ArenaTheme {
            Column(Modifier.fillMaxSize().background(ArenaColors.Bg)) {
                Spacer(Modifier.height(8.dp))
                ScoreHeader(liveGame, null, emptySet()) { _, _ -> }
                TabRow(selectedTabIndex = 0, containerColor = ArenaColors.Bg) {
                    listOf("Watch (3)", "Stats", "Timeline").forEachIndexed { i, t -> Tab(selected = i == 0, onClick = {}, text = { Text(t) }) }
                }
                WatchTab(liveGame, watch, true, {}, WatchActions({}, {}, {}), {}, {})
            }
        }
    }

    @Test fun gameDetailStats() = paparazzi.snapshot {
        val d = EspnParser.parseSummary(res("soccer_summary.json"), soccer[0].home.id)
        ArenaTheme {
            Column(Modifier.fillMaxSize().background(ArenaColors.Bg)) {
                Spacer(Modifier.height(8.dp))
                ScoreHeader(soccer[0], d, setOf("soccer:${soccer[0].away.id}")) { _, _ -> }
                StatsTab(soccer[0], d)
            }
        }
    }

    @Test fun nbaStats() = paparazzi.snapshot {
        val d = EspnParser.parseSummary(res("nba_summary.json"), nba[0].home.id)
        ArenaTheme {
            Column(Modifier.fillMaxSize().background(ArenaColors.Bg)) {
                ScoreHeader(nba[0], d, emptySet()) { _, _ -> }
                StatsTab(nba[0], d)
            }
        }
    }
}
