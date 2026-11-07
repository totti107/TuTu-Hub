package com.arena.tv.ui.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arena.tv.AppContainer
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.FavoriteTeamEntity
import com.arena.tv.data.FeedbackEntity
import com.arena.tv.matching.ChannelMatch
import com.arena.tv.sports.Game
import com.arena.tv.sports.GameDetails
import com.arena.tv.sports.GameState
import com.arena.tv.sports.Team
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WatchRow(val channel: ChannelEntity, val match: ChannelMatch, val verdict: Int?, val nowTitle: String?)

data class WatchState(
    val searching: Boolean = true,
    val rows: List<WatchRow> = emptyList(),
    val rejected: List<ChannelEntity> = emptyList(),
    val channelCount: Int = 0,
    val hasGuide: Boolean = false,
    /** Set while the playlist / guide is still loading: results will improve when it's done. */
    val loadingNote: String? = null,
)

class GameDetailViewModel(private val c: AppContainer, private val gameId: String) : ViewModel() {
    private val _game = MutableStateFlow(c.sports.cache[gameId])
    val game: StateFlow<Game?> = _game.asStateFlow()

    private val _details = MutableStateFlow<GameDetails?>(null)
    val details: StateFlow<GameDetails?> = _details.asStateFlow()

    private val _watch = MutableStateFlow(WatchState())
    val watch: StateFlow<WatchState> = _watch.asStateFlow()

    val favoriteTeams: StateFlow<Set<String>> = c.db.user().teams().map { l -> l.map { it.key }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Re-match whenever feedback or the channel data changes.
        viewModelScope.launch {
            combine(c.db.user().feedbackFor(gameId), c.iptv.state.map { it.version }.distinctUntilChanged()) { fb, _ -> fb }
                .collectLatest { fb -> rematch(fb) }
        }
    }

    private suspend fun rematch(fb: List<FeedbackEntity>) {
        val g = _game.value ?: return
        _watch.update { it.copy(searching = true) }
        val matches = runCatching { c.finder.find(g, fb) }.getOrDefault(emptyList()).take(40)
        val chans = c.db.channels().byIds(matches.map { it.channelId } + fb.filter { it.verdict < 0 }.map { it.channelId })
            .associateBy { it.id }
        val verdicts = fb.associate { it.channelId to it.verdict }
        val now = System.currentTimeMillis()
        val nowTitles = chans.values.mapNotNull { ch -> ch.epgKey?.let { ch.id to it } }.let { pairs ->
            if (pairs.isEmpty()) emptyMap() else {
                val progs = c.db.programs().forKeys(pairs.map { it.second }.distinct(), now, now + 1)
                val byKey = progs.associateBy { it.epgKey }
                pairs.associate { (id, k) -> id to byKey[k]?.title }
            }
        }
        val rows = matches.mapNotNull { m -> chans[m.channelId]?.let { WatchRow(it, m, verdicts[it.id], nowTitles[it.id]) } }
        val count = c.db.channels().count()
        _watch.value = WatchState(
            searching = false,
            rows = rows,
            rejected = fb.filter { it.verdict < 0 }.mapNotNull { chans[it.channelId] },
            channelCount = count,
            hasGuide = c.settings.current().let { it.lastEpgRefresh > 0 && it.anyGuide },
            loadingNote = c.iptv.state.value.takeIf { it.running }?.message ?: if (c.iptv.state.value.running) "Loading" else null,
        )
    }

    /** Keeps score, clock and stats fresh while the screen is visible. */
    suspend fun poll() {
        var first = true
        while (true) {
            val g = _game.value ?: return
            if (!first || _details.value == null) {
                runCatching { c.sports.details(g) }.onSuccess { _details.value = it }
            }
            if (!first) {
                runCatching { c.sports.refreshGame(g) }.getOrNull()?.let { _game.value = it }
            }
            first = false
            delay(if (g.state == GameState.LIVE) 20_000 else if (g.state == GameState.PRE) 60_000 else 300_000)
        }
    }

    fun setDecimalOdds(d: Boolean) = viewModelScope.launch { c.settings.setDecimalOdds(d) }

    fun confirm(ch: ChannelEntity) = setVerdict(ch, 1)
    fun reject(ch: ChannelEntity) = setVerdict(ch, -1)
    fun clearVerdict(ch: ChannelEntity) = viewModelScope.launch { c.db.user().clearFeedback(gameId, ch.id) }

    private fun setVerdict(ch: ChannelEntity, v: Int) = viewModelScope.launch {
        val g = _game.value ?: return@launch
        c.db.user().setFeedback(FeedbackEntity(gameId, ch.id, g.league.key, v, System.currentTimeMillis()))
    }

    suspend fun searchChannels(q: String): List<ChannelEntity> =
        if (q.isBlank()) c.db.channels().favoritesOnce() else c.db.channels().searchOnce(q.trim())

    fun toggleTeam(t: Team, isFav: Boolean) = viewModelScope.launch {
        val g = _game.value ?: return@launch
        val key = t.key(g.league.sport)
        if (isFav) c.db.user().removeTeam(key) else c.db.user().addTeam(FavoriteTeamEntity(key, t.displayName, t.logo))
    }
}
