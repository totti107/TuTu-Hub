package com.arena.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arena.tv.AppContainer
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.ProgressEntity
import com.arena.tv.data.VodEntity
import com.arena.tv.data.VodKind
import com.arena.tv.sports.DayOffset
import com.arena.tv.sports.Game
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Leagues
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Data for the Home dashboard: counts, artwork, what's live and what you were watching. */
class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)

    val channels = c.db.channels().countFlow().state(0)
    val movies = c.db.vod().count(VodKind.MOVIE).state(0)
    val series = c.db.vod().count(VodKind.SERIES).state(0)
    val favorites = c.db.channels().favoriteCount().state(0)
    val hasSource = c.settings.flow.map { it.hasSource }.state(true)

    val logos = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val moviePosters = MutableStateFlow<List<String>>(emptyList())
    val seriesPosters = MutableStateFlow<List<String>>(emptyList())

    /** The title you were last watching, with how far you got. */
    val resume: StateFlow<Pair<VodEntity, ProgressEntity>?> = MutableStateFlow(null)

    /** Live games first, then the next ones today, then today's results. */
    val games = MutableStateFlow<List<Game>>(emptyList())
    val liveCount = games.map { g -> g.count { it.isLive } }.state(0)

    init {
        // Artwork reloads whenever the channel / title counts change (after a playlist loads).
        viewModelScope.launch {
            channels.collect {
                val favs = c.db.channels().favoritesOnce().filter { !it.logo.isNullOrBlank() }
                logos.value = (favs + c.db.channels().withLogos(24)).distinctBy { it.logo }.take(8)
            }
        }
        viewModelScope.launch {
            movies.collect { moviePosters.value = c.db.vod().newest(VodKind.MOVIE, 12).mapNotNull { it.poster?.takeIf(String::isNotBlank) }.take(3) }
        }
        viewModelScope.launch {
            series.collect { seriesPosters.value = c.db.vod().newest(VodKind.SERIES, 12).mapNotNull { it.poster?.takeIf(String::isNotBlank) }.take(3) }
        }
        viewModelScope.launch {
            c.db.vod().continueWatching().map { it.firstOrNull { p -> !p.finished } }.distinctUntilChanged().collect { p ->
                (resume as MutableStateFlow).value = p?.let { pr -> c.db.vod().byKey(pr.vodKey)?.let { it to pr } }
            }
        }
    }

    /** Keeps the score tile fresh while Home is on screen. */
    suspend fun pollScores() {
        while (true) {
            runCatching {
                val s = c.settings.current()
                val leagues = Leagues.all.filter { it.key in s.enabledLeagues && (s.followedSports.isEmpty() || it.sport.espn in s.followedSports) }
                if (leagues.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val all = c.sports.games(leagues, DayOffset.TODAY).first
                    val teams = c.db.user().teams().first().map { it.key }.toSet()
                    fun mine(g: Game) = g.home.key(g.league.sport) in teams || g.away.key(g.league.sport) in teams
                    val live = all.filter { it.isLive && it.headToHead }.sortedByDescending { mine(it) }
                    val next = all.filter { it.state == GameState.PRE && it.headToHead && it.startMillis > now }.sortedBy { it.startMillis }
                    val done = all.filter { it.state == GameState.POST && it.headToHead }.sortedByDescending { it.startMillis }
                    games.value = (if (live.isNotEmpty()) live else next.take(6).ifEmpty { done.take(6) }).take(12)
                }
            }
            delay(if (games.value.any { it.isLive }) 30_000 else 120_000)
        }
    }

    fun checkUpdates() = c.updater.check(manual = true)
    fun reload() = viewModelScope.launch { c.iptv.refreshAll() }
}
