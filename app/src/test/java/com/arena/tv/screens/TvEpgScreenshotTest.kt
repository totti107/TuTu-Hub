package com.arena.tv.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.ProgramEntity
import com.arena.tv.ui.components.LocalCompactChrome
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.PaneItem
import com.arena.tv.ui.components.PillStrip
import com.arena.tv.ui.components.PillTabs
import com.arena.tv.ui.components.panel
import com.arena.tv.ui.live.EpgGridContent
import com.arena.tv.ui.live.NowInfo
import com.arena.tv.ui.live.NowNext
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.ArenaTheme
import org.junit.Rule
import org.junit.Test

class TvEpgScreenshotTest {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(orientation = ScreenOrientation.LANDSCAPE, screenWidth = 1920, screenHeight = 1080, density = Density.XHIGH),
        maxPercentDifference = 0.1,
    )

    @Test fun epgOnTv() {
        val now = System.currentTimeMillis()
        val from = now - (now % 1_800_000) - 3_600_000
        fun ch(i: Int, n: String, key: String?) = ChannelEntity("$i", "p1", true, i, n, null, "EN | 4K UHD", "http://x", null, null, key, null, null)
        val rows = listOf(ch(1, "UK: TNT Sports Ultimate 4K", "tnt"), ch(2, "UK: Sky Sports Main Event", "skyme"), ch(3, "UK: Sky Sports F1 UHD", "f1"),
            ch(4, "NFL Game Pass 01: Jaguars", null), ch(5, "UK: Sky Sports UHD 1", "uhd1"))
        var id = 0L
        fun p(key: String, title: String, startMin: Int, len: Int) = ProgramEntity(++id, key, now + startMin * 60_000L, now + (startMin + len) * 60_000L, title, null,
            "The best bits from TNT Sports over the last week, including exclusive interviews and so much more.", null)
        val programs = listOf(
            p("tnt", "TNT Sports Ultimate Reload: Rugby", -15, 21), p("tnt", "This is TNT Sports Ultimate", 6, 120),
            p("skyme", "Formula 1: Sao Paulo Grand Prix", -50, 95), p("skyme", "Soccer Saturday", 45, 60), p("skyme", "Saturday Night Football", 105, 165),
            p("f1", "Formula 1: Sao Paulo Grand Prix: 3rd Practice", -65, 110), p("f1", "F1 Classic Races", 45, 60),
            p("uhd1", "This is Ultra HD", -40, 135),
        ).groupBy { it.epgKey }
        paparazzi.snapshot {
            ArenaTheme {
                CompositionLocalProvider(LocalWide provides true, LocalCompactChrome provides true) {
                    Column(Modifier.fillMaxSize().background(ArenaColors.Bg)) {
                        PillStrip()
                        Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Column(Modifier.weight(0.25f).fillMaxHeight().panel().padding(12.dp)) {
                                PillTabs(listOf("Library", "EPG", "Sports"), 1, compact = true) {}
                                PaneItem("All Channels", 17732, false, {}); PaneItem("Favorites", 1, false, {}); PaneItem("EN | 4K UHD 🏆", 38, true, {})
                            }
                            Column(Modifier.weight(0.75f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth().height(200.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    NowInfo(rows[0], NowNext(programs["tnt"]!![0], programs["tnt"]!![1]), Modifier.weight(1.45f).fillMaxHeight())
                                    Column(Modifier.weight(1f).fillMaxHeight().panel().padding(10.dp)) {
                                        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF1F4D2B)))
                                    }
                                }
                                EpgGridContent(rows, programs, rows[1], now, from, {}, Modifier.weight(1f).fillMaxWidth().panel())
                            }
                        }
                    }
                }
            }
        }
    }
}
