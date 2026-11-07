package com.arena.tv.ui.sports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arena.tv.AppContainer
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.FeedbackEntity
import com.arena.tv.matching.Tier
import com.arena.tv.sports.Game
import com.arena.tv.sports.GameDetails
import com.arena.tv.sports.GameState
import com.arena.tv.ui.live.isSportyGroup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Left-pane filter of Sports Mode. */
sealed interface SportsFilter {
    data object AllToday : SportsFilter
    data object LiveNow : SportsFilter
    data object SportsChannels : SportsFilter
    data class League(val key: String) : SportsFilter
}

/** Selection, details and "where to watch" for the Sports Mode screen. Games come from [SportsHubViewModel]. */
@OptIn(ExperimentalCoroutinesApi::class)
class MatchCentreViewModel(private val c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow<SportsFilter>(SportsFilter.AllToday)
    val selected = MutableStateFlow<Game?>(null)
    val details = MutableStateFlow<GameDetails?>(null)
    val watch = MutableStateFlow(WatchState())
    val preview = MutableStateFlow<ChannelEntity?>(null)
    val tab = MutableStateFlow(0)
    val sportsChannels = MutableStateFlow<List<ChannelEntity>>(emptyList())
    val decimalOdds = c.settings.flow.map { it.decimalOdds }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, true)
    fun setDecimalOdds(d: Boolean) = viewModelScope.launch { c.settings.setDecimalOdds(d) }
    private var detailsJob: Job? = null
    /** The user picked a channel to preview, so don't auto-switch it. */
    private var manualPreview = false

    init {
        viewModelScope.launch {
            // Re-match only when the selected game or the channel data changes (not on every score refresh).
            combine(selected.map { it?.id }.distinctUntilChanged(), c.iptv.state.map { it.version }.distinctUntilChanged()) { id, v -> id to v }
                .map { selected.value to it.second }
                .flatMapLatest { (g, _) -> if (g == null) flowOf(null to emptyList()) else c.db.user().feedbackFor(g.id).let { f -> combine(flowOf(g), f) { a, b -> a to b } } }
                .collectLatest { (g, fb) -> if (g != null) rematch(g, fb) else watch.value = WatchState(searching = false) }
        }
    }

    fun select(g: Game) {
        if (selected.value?.id == g.id) { selected.value = g; return } // score refresh
        selected.value = g
        details.value = null
        manualPreview = false
        if (tab.value > 0 && !g.headToHead && tab.value > 1) tab.value = 0
        detailsJob?.cancel()
        detailsJob = viewModelScope.launch {
            while (true) {
                if (g.headToHead && g.league.sport.let { it != com.arena.tv.sports.Sport.MMA && it != com.arena.tv.sports.Sport.TENNIS }) {
                    runCatching { c.sports.details(selected.value ?: g) }.onSuccess { details.value = it }
                }
                delay(if ((selected.value ?: g).state == GameState.LIVE) 20_000 else 120_000)
            }
        }
    }

    private suspend fun rematch(g: Game, fb: List<FeedbackEntity>) {
        watch.value = watch.value.copy(searching = true)
        val matches = runCatching { c.finder.find(g, fb) }.getOrDefault(emptyList()).take(30)
        val chans = c.db.channels().byIds(matches.map { it.channelId }).associateBy { it.id }
        val rows = matches.mapNotNull { m -> chans[m.channelId]?.let { WatchRow(it, m, fb.firstOrNull { f -> f.channelId == it.id }?.verdict, null) } }
        watch.value = WatchState(searching = false, rows = rows, channelCount = c.db.channels().count(),
            hasGuide = c.settings.current().let { it.lastEpgRefresh > 0 && it.anyGuide },
            loadingNote = c.iptv.state.value.takeIf { it.running }?.let { it.message ?: "Loading" })
        if (!manualPreview) preview.value = rows.firstOrNull { it.match.tier == Tier.CONFIRMED || it.match.tier == Tier.STRONG }?.channel
    }

    fun previewChannel(ch: ChannelEntity) { manualPreview = true; preview.value = ch }

    fun loadSportsChannels() = viewModelScope.launch {
        val sporty = c.db.channels().groups().first().filter { isSportyGroup(it.groupTitle) }.take(40)
        sportsChannels.value = sporty.flatMap { c.db.channels().inGroupOnce(it.playlistId, it.groupTitle) }.take(1500)
    }

    fun confirm(ch: ChannelEntity) = verdict(ch, 1)
    fun reject(ch: ChannelEntity) = verdict(ch, -1)
    fun clear(ch: ChannelEntity) = viewModelScope.launch { selected.value?.let { c.db.user().clearFeedback(it.id, ch.id) } }
    private fun verdict(ch: ChannelEntity, v: Int) = viewModelScope.launch {
        val g = selected.value ?: return@launch
        c.db.user().setFeedback(FeedbackEntity(g.id, ch.id, g.league.key, v, System.currentTimeMillis()))
    }
    suspend fun searchChannels(q: String): List<ChannelEntity> =
        if (q.isBlank()) c.db.channels().favoritesOnce() else c.db.channels().searchOnce(q.trim())
}
