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

class PhoneLandscapeScreenshotTest {
    /** Phone in landscape (wide layout, ~890dp): the top bar must not hide anything under the pill. */
    @get:Rule val phoneLandscape = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 2000, screenHeight = 900, density = com.android.resources.Density.create(360)),
        maxPercentDifference = 0.1,
    )



    private fun ch(i: Int, name: String) = ChannelEntity("$i", "p1", true, i, name, null, "USA | NFHS NETWORK", "http://x", null, null, null, null, null)

    @Composable
    private fun Wide(content: @Composable () -> Unit) = ArenaTheme {
        CompositionLocalProvider(LocalWide provides true, com.arena.tv.ui.components.LocalCompactChrome provides true,
            com.arena.tv.ui.components.LocalSectionNav provides com.arena.tv.ui.components.SectionNav(listOf("Sports" to androidx.compose.material.icons.Icons.Rounded.Search), 0) {}) {
            Box(Modifier.fillMaxSize().background(ArenaColors.Bg)) { content() }
        }
    }

    @Test fun phoneLandscapeTopBarAndCards() {
        val channels = (1..8).map { ch(it, "NFHS Network 0$it: NO EVENT") }
        phoneLandscape.snapshot {
            Wide {
                Column {
                    ArenaTopBar(
                        left = { PillTabs(listOf("For you", "Scores", "Following"), 0) {}; TabDivider(); ToggleLabel("Mine", false) {} },
                        right = {
                            IconAction(Icons.Rounded.Search, "") {}
                            Text("Sports ▾", fontSize = 15.sp, modifier = Modifier.padding(8.dp))
                            DropChip("Today", listOf(1 to "Today"), 1) {}
                            DropChip("Countries", listOf(1 to "All"), 1) {}
                            IconAction(Icons.Rounded.Refresh, "") {}
                        },
                    )
                    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(0.25f).fillMaxHeight().panel())
                        LazyColumn(Modifier.weight(0.33f).fillMaxHeight().panel(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(channels) { i, c -> ChannelCard(i + 1, c, null, i == 0, false, {}, {}) }
                        }
                        Box(Modifier.weight(0.42f).fillMaxHeight().panel())
                    }
                }
            }
        }
    }

}
