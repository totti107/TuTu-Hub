package com.arena.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.arena.tv.data.VodKind
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.LocalSectionNav
import com.arena.tv.ui.components.SectionNav
import com.arena.tv.ui.live.LiveTvScreen
import com.arena.tv.ui.settings.SettingsScreen
import com.arena.tv.ui.sports.GameDetailScreen
import com.arena.tv.ui.sports.SportsHubScreen
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.ArenaTheme
import com.arena.tv.ui.vod.VodDetailScreen
import com.arena.tv.ui.vod.VodHomeScreen
import kotlinx.coroutines.launch

private enum class Section(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Rounded.Home),
    SPORTS("sports", "Sports", Icons.Rounded.SportsSoccer),
    LIVE("live", "Live TV", Icons.Rounded.LiveTv),
    MOVIES("movies", "Movies", Icons.Rounded.Movie),
    SERIES("series", "Series", Icons.Rounded.VideoLibrary),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings),
}

private var startRoute = "home"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(ArenaColors.Bg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(ArenaColors.Bg.toArgb()),
        )
        lifecycleScope.launch { app.iptv.refreshIfStale() }
        app.trakt.syncIfStale()
        if (savedInstanceState == null) app.updater.check()
        // The start screen is a setting; read it before the first frame.
        val prefs = kotlinx.coroutines.runBlocking { app.settings.current().prefs }
        app.prefs.value = prefs
        startRoute = Section.entries.firstOrNull { it.route == prefs.startScreen }?.route ?: Section.HOME.route
        setContent { ArenaTheme { ArenaRoot(); com.arena.tv.ui.components.UpdateDialog(app.updater) } }
    }
}

@Composable
private fun ArenaRoot() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val wide = LocalConfiguration.current.screenWidthDp >= 840
    val current = Section.entries.firstOrNull { it.route == route }

    fun go(s: Section) = nav.navigate(s.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    val sectionNav = SectionNav(Section.entries.map { it.label to it.icon }, (current ?: Section.HOME).ordinal) { go(Section.entries[it]) }
    val compactChrome = wide && LocalConfiguration.current.screenWidthDp < 1150
    val context = androidx.compose.ui.platform.LocalContext.current
    val decimalOdds by remember { context.app.settings.flow.map { it.decimalOdds } }.collectAsState(initial = true)
    CompositionLocalProvider(LocalWide provides wide, LocalSectionNav provides sectionNav, com.arena.tv.ui.components.LocalCompactChrome provides compactChrome,
        com.arena.tv.ui.sports.LocalDecimalOdds provides decimalOdds) {
        if (wide) {
            // No side bar on big screens: the panes get the full width; the top pill switches section.
            Box(Modifier.fillMaxSize().background(ArenaColors.Bg).systemBarsPadding()) { Graph(nav) { go(Section.SETTINGS) } }
        } else {
            Scaffold(
                containerColor = ArenaColors.Bg,
                bottomBar = {
                    if (current != null) NavigationBar(containerColor = ArenaColors.Panel, tonalElevation = 0.dp) {
                        Section.entries.filter { it != Section.SETTINGS }.forEach { s ->
                            NavigationBarItem(
                                selected = s == current, onClick = { go(s) },
                                icon = { Icon(s.icon, null) }, label = { Text(s.label, fontSize = 11.sp, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.Black, indicatorColor = Color.White,
                                    selectedTextColor = Color.White, unselectedIconColor = ArenaColors.Muted, unselectedTextColor = ArenaColors.Muted),
                            )
                        }
                    }
                },
            ) { pad -> Box(Modifier.padding(pad)) { Graph(nav) { go(Section.SETTINGS) } } }
        }
    }
}

@Composable
private fun Graph(nav: NavHostController, openSettings: () -> Unit) {
    NavHost(nav, startDestination = startRoute) {
        composable(Section.HOME.route) {
            fun open(s: Section) = nav.navigate(s.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true; restoreState = true
            }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            com.arena.tv.ui.home.HomeScreen(com.arena.tv.ui.home.HomeActions(
                live = { r -> ctx.app.liveRequest.value = r; open(Section.LIVE) },
                sports = { open(Section.SPORTS) }, movies = { open(Section.MOVIES) }, series = { open(Section.SERIES) },
                game = { nav.navigate("game/${it.id}") }, vod = { nav.navigate("vod/$it") }, settings = openSettings,
                recordings = { ctx.app.settingsPage.value = "RECORDING"; openSettings() },
                planner = { ctx.app.settingsPage.value = "PLANNER"; openSettings() },
            ))
        }
        composable(Section.SPORTS.route) {
            SportsHubScreen(onOpenGame = { nav.navigate("game/${it.id}") }, onOpenSettings = openSettings)
        }
        composable("game/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            GameDetailScreen(e.arguments?.getString("id") ?: "", onBack = { nav.popBackStack() }, onOpenSettings = openSettings)
        }
        composable(Section.LIVE.route) { LiveTvScreen(onOpenSettings = openSettings) }
        composable(Section.MOVIES.route) { VodHomeScreen(VodKind.MOVIE, onOpen = { nav.navigate("vod/$it") }, onOpenSettings = openSettings) }
        composable(Section.SERIES.route) { VodHomeScreen(VodKind.SERIES, onOpen = { nav.navigate("vod/$it") }, onOpenSettings = openSettings) }
        composable("vod/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { e ->
            VodDetailScreen(e.arguments?.getString("key") ?: "", onBack = { nav.popBackStack() }, onOpen = { nav.navigate("vod/$it") })
        }
        composable(Section.SETTINGS.route) { SettingsScreen() }
    }
}
