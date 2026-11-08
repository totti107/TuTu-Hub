package com.arena.tv.ui.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arena.tv.AppContainer
import com.arena.tv.data.FavoriteTeamEntity
import com.arena.tv.sports.DayOffset
import com.arena.tv.sports.Game
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Leagues
import com.arena.tv.sports.Sport
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HubState(
    val day: DayOffset = DayOffset.TODAY,
    val sport: Sport? = null,
    val liveOnly: Boolean = false,
    val tab: Int = 0,
    val mine: Boolean = false,
    val country: String? = null,
    val query: String = "",
    val loading: Boolean = true,
    val games: List<Game> = emptyList(),
    val error: String? = null,
    val channelCounts: Map<String, Int> = emptyMap(),
    val updatedAt: Long = 0,
)

class SportsHubViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state.asStateFlow()

    val favoriteTeams: StateFlow<List<FavoriteTeamEntity>> =
        c.db.user().teams().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hasSource = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var countsJob: Job? = null
    private var lastFull = 0L

    fun setDay(d: DayOffset) {
        if (d == _state.value.day) return
        _state.update { it.copy(day = d, games = emptyList(), loading = true, channelCounts = emptyMap()) }
        viewModelScope.launch { refresh(full = true) }
    }
    fun setSport(s: Sport?) = _state.update { it.copy(sport = s) }
    fun toggleLiveOnly() = _state.update { it.copy(liveOnly = !it.liveOnly) }
    fun setTab(t: Int) = _state.update { it.copy(tab = t) }
    /** For you layout: spotlight, rows or list (Settings → Sports, or the layout button). */
    val layout = c.settings.flow.map { it.prefs.sportsLayout }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, c.prefs.value.sportsLayout)
    fun setLayout(l: String) = viewModelScope.launch { c.settings.updatePrefs { it.copy(sportsLayout = l) } }
    fun setMine(m: Boolean) = _state.update { it.copy(mine = m) }
    fun setCountry(c: String?) = _state.update { it.copy(country = c) }
    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    /** Called while the screen is visible: live games refresh every 20s, the rest every 2 minutes. */
    suspend fun poll() {
        while (true) {
            val now = System.currentTimeMillis()
            val anyLive = _state.value.games.any { it.isLive }
            val full = !anyLive || now - lastFull > 120_000 || _state.value.games.isEmpty()
            refresh(full)
            delay(if (anyLive || _state.value.games.any { it.state == GameState.PRE && it.startMillis - now < 10 * 60_000 }) 20_000 else 90_000)
        }
    }

    fun manualRefresh() { viewModelScope.launch { refresh(full = true) } }

    private suspend fun refresh(full: Boolean) {
        val s = c.settings.current()
        val enabled = Leagues.all.filter { it.key in s.enabledLeagues && (s.followedSports.isEmpty() || it.sport.espn in s.followedSports) }
        val st = _state.value
        if (enabled.isEmpty()) {
            _state.update { it.copy(loading = false, games = emptyList(), error = "No leagues selected. Pick some in Settings.") }
            return
        }
        try {
            val onlyLive = if (!full) st.games.filter { it.isLive }.map { it.league.key }.toSet() else null
            val (fresh, failures) = c.sports.games(enabled, st.day, onlyLive)
            val merged = if (onlyLive == null) fresh else {
                val byId = fresh.associateBy { it.id }
                st.games.map { byId[it.id] ?: it }
            }
            if (onlyLive == null) lastFull = System.currentTimeMillis()
            val order = Leagues.all.withIndex().associate { it.value.key to it.index }
            val sorted = merged.sortedWith(compareBy<Game>({ order[it.league.key] ?: 999 }, { it.startMillis }))
            c.finder.rememberTeams(sorted)
            _state.update {
                it.copy(
                    loading = false, games = sorted, updatedAt = System.currentTimeMillis(),
                    error = when {
                        sorted.isEmpty() && failures > 0 -> "Couldn't load scores. Check your internet connection."
                        failures > 0 -> "Some leagues didn't load."
                        else -> null
                    },
                )
            }
            if (full) updateCounts(sorted)
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = "Couldn't load scores: ${e.message ?: "network error"}") }
        }
    }

    private fun updateCounts(games: List<Game>) {
        countsJob?.cancel()
        countsJob = viewModelScope.launch {
            // Most useful first: followed teams, live games, then the soonest kick-offs. Cards fill in as each is done.
            val favs = favoriteTeams.value.map { it.key }.toSet()
            val now = System.currentTimeMillis()
            val ordered = games.filter { it.state != GameState.POST }.sortedWith(compareBy<Game>(
                { !(it.home.key(it.league.sport) in favs || it.away.key(it.league.sport) in favs) },
                { !it.isLive },
                { kotlin.math.abs(it.startMillis - now) },
            ))
            _state.update { st -> st.copy(channelCounts = st.channelCounts + ordered.mapNotNull { g -> c.finder.cached(g.id)?.let { g.id to it.count { m -> m.score >= 40 } } }) }
            runCatching { c.finder.strongCounts(ordered) { partial -> _state.update { it.copy(channelCounts = it.channelCounts + partial) } } }
        }
    }

    fun toggleFavoriteTeam(key: String, name: String, logo: String?, isFav: Boolean) = viewModelScope.launch {
        if (isFav) c.db.user().removeTeam(key) else c.db.user().addTeam(FavoriteTeamEntity(key, name, logo))
    }
}
