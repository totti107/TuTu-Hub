package com.arena.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.Prefs
import com.arena.tv.data.ProgressEntity
import com.arena.tv.data.VodEntity
import com.arena.tv.sports.EspnParser
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Leagues
import com.arena.tv.ui.components.ArenaTopBar
import com.arena.tv.ui.components.LocalCompactChrome
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.PillTabs
import com.arena.tv.ui.home.HomeActions
import com.arena.tv.ui.home.HomeContent
import com.arena.tv.ui.home.HomeData
import com.arena.tv.ui.settings.SettingsMenu
import com.arena.tv.ui.settings.SettingsPage
import com.arena.tv.ui.settings.playbackItems
import com.arena.tv.ui.settings.titleItems
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.ArenaTheme
import org.junit.Rule
import org.junit.Test

/** The Home dashboard and the new Settings layout on a 1080p TV. */
class HomeScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 1920, screenHeight = 1080, density = Density.XHIGH),
        maxPercentDifference = 0.1,
    )
    private fun res(n: String) = javaClass.classLoader!!.getResource("espn/$n")!!.readText()
    private val actions = HomeActions({}, {}, {}, {}, {}, {}, {})

    private fun sample(): HomeData {
        val games = EspnParser.parseScoreboard(res("soccer_scoreboard.json"), Leagues.byKey("soccer/uefa.champions")!!)
            .map { it.copy(state = GameState.LIVE, statusText = "67'") }
        val logos = listOf("Sky Sports", "beIN 1", "TNT Sports", "DAZN", "ESPN", "SSC 1", "Canal+", "BBC One").mapIndexed { i, n ->
            ChannelEntity(id = "c$i", playlistId = "p", position = i, name = n, logo = "", groupTitle = "Sports", url = "", tvgId = null,
                tvgName = null, epgKey = null, userAgent = null, referrer = null)
        }
        val film = VodEntity(key = "m:1", playlistId = "p", kind = 0, providerId = "1", position = 0, name = "The Long Night Shift", poster = null,
            categoryId = "1", rating = 7.4, year = 2024, added = 0, ext = null, url = null, tmdb = null, plot = null, genre = null, backdrop = null)
        return HomeData(channels = 27_412, movies = 263_118, series = 34_207, favorites = 12, logos = logos, games = games,
            resume = film to ProgressEntity("m:1", "m:1", 2_400_000, 6_000_000, 0, "The Long Night Shift"), version = "3.10.1", latest = "3.11.0")
    }

    @Test fun homeOnTv() {
        paparazzi.snapshot {
            ArenaTheme {
                CompositionLocalProvider(LocalWide provides true, LocalCompactChrome provides true) {
                    Column(Modifier.fillMaxSize().background(ArenaColors.Bg)) { HomeContent(sample(), actions) }
                }
            }
        }
    }

    @Test fun scoresWhileWatching() {
        val games = EspnParser.parseScoreboard(res("soccer_scoreboard.json"), Leagues.byKey("soccer/uefa.champions")!!)
            .take(3).map { it.copy(state = GameState.LIVE, statusText = "67'") }
        paparazzi.snapshot {
            ArenaTheme {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF2B4A2E))) {
                    com.arena.tv.ui.player.ScoreOverlay(games, setOf(games[1].id), compact = false)
                }
            }
        }
    }

    @Test fun titleOptimizerOnTv() {
        paparazzi.snapshot {
            ArenaTheme {
                CompositionLocalProvider(LocalWide provides true, LocalCompactChrome provides true) {
                    Column(Modifier.fillMaxSize().background(ArenaColors.Bg)) {
                        Row(Modifier.fillMaxSize()) {
                            SettingsMenu(SettingsPage.TITLES, true, Modifier.width(270.dp).fillMaxHeight()) {}
                            LazyColumn(Modifier.weight(1f).fillMaxHeight()) { titleItems(Prefs(titleOptimizer = true, titleCountry = true)) {} }
                        }
                    }
                }
            }
        }
    }

    @Test fun settingsPlaybackOnTv() {
        paparazzi.snapshot {
            ArenaTheme {
                CompositionLocalProvider(LocalWide provides true, LocalCompactChrome provides true) {
                    Column(Modifier.fillMaxSize().background(ArenaColors.Bg)) {
                        ArenaTopBar(left = { PillTabs(listOf("Settings"), 0) {} })
                        Row(Modifier.fillMaxSize()) {
                            SettingsMenu(SettingsPage.PLAYBACK, true, Modifier.width(270.dp).fillMaxHeight()) {}
                            LazyColumn(Modifier.weight(1f).fillMaxHeight()) { playbackItems(Prefs(buffer = "high", subtitleLang = "en"), {}) }
                        }
                    }
                }
            }
        }
    }
}
