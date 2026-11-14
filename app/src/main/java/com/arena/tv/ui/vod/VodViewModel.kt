package com.arena.tv.ui.vod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arena.tv.AppContainer
import com.arena.tv.data.CategoryCount
import com.arena.tv.data.ProgressEntity
import com.arena.tv.data.VodDetails
import com.arena.tv.data.VodEntity
import com.arena.tv.data.VodFavoriteEntity
import com.arena.tv.data.VodKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VodSort(val label: String) { LIBRARY("Library order"), NEWEST("Recently added"), TOP("Top rated"), AZ("A–Z") }

enum class YearFilter(val label: String, val min: Int?, val max: Int?) {
    ANY("Any year", null, null), Y2020("2020s", 2020, 2029), Y2010("2010s", 2010, 2019), Y2000("2000s", 2000, 2009),
    Y1990("1990s", 1990, 1999), OLDER("Older", null, 1989),
}

/** A titled row on the Spotlight page. */
data class VodRow(val title: String, val items: List<VodEntity>, val progress: Map<String, ProgressEntity> = emptyMap())

data class SpotlightState(val hero: List<VodEntity> = emptyList(), val rows: List<VodRow> = emptyList(), val loading: Boolean = true)

const val CAT_ALL = "__all"
const val CAT_FAV = "__fav"
const val CAT_CONTINUE = "__continue"
const val CAT_WATCHLIST = "__watchlist"

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class VodViewModel(private val c: AppContainer, val kind: Int) : ViewModel() {
    val tab = MutableStateFlow(0)
    val category = MutableStateFlow(CAT_ALL)
    val sort = MutableStateFlow(VodSort.LIBRARY)
    val year = MutableStateFlow(YearFilter.ANY)
    val query = MutableStateFlow("")
    val catQuery = MutableStateFlow("")

    val categories: StateFlow<List<CategoryCount>> = c.db.vod().categories(kind)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val total = c.db.vod().count(kind).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val favoriteKeys = c.db.vod().favoriteKeys().map { it.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val trakt = c.trakt.state
    val refresh = c.iptv.state
    /** Adult categories need the PIN (shared with adult channels). */
    val unlocked = c.parental.unlocked
    fun isUnlocked() = c.parental.isUnlocked()
    fun lockAdult() = c.parental.lock()
    suspend fun checkPin(pin: String) = c.parental.tryUnlock(pin)
    val settings = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val continueRaw = c.db.vod().continueWatching()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---------------- Library grid (paged) ----------------
    private val _items = MutableStateFlow<List<VodEntity>>(emptyList())
    val items: StateFlow<List<VodEntity>> = _items.asStateFlow()
    private val _end = MutableStateFlow(false)
    val reachedEnd: StateFlow<Boolean> = _end.asStateFlow()
    private var loadJob: Job? = null
    private val pageSize = 240

    private var wasAdult = false

    init {
        // Leaving an adult category locks adult titles again; a locked adult category falls back to "All".
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(category, categories, c.parental.unlocked) { id, cats, _ -> id to cats }.collect { (id, cats) ->
                val adult = cats.any { it.adult && it.id == id }
                if (!adult && wasAdult) c.parental.lock()
                wasAdult = adult
                if (adult && !c.parental.isUnlocked()) { wasAdult = false; category.value = CAT_ALL }
            }
        }
        viewModelScope.launch {
            combine(category, sort, year, query.debounce(250), total) { a, b, y, q, _ -> listOf(a, b, y, q) }
                .distinctUntilChanged().collect { reload() }
        }
        viewModelScope.launch { refresh.map { it.version }.distinctUntilChanged().collect { buildSpotlight() } }
        viewModelScope.launch { trakt.map { it.watchlist.size to it.lastSync }.distinctUntilChanged().collect { buildSpotlight() } }
        viewModelScope.launch { continueRaw.collect { buildSpotlight() } }
    }

    private fun reload() { _items.value = emptyList(); _end.value = false; loadMore() }

    fun loadMore() {
        if (loadJob?.isActive == true || _end.value) return
        loadJob = viewModelScope.launch {
            val offset = _items.value.size
            val q = query.value.trim()
            val cat = category.value
            val page: List<VodEntity> = when {
                q.isNotEmpty() -> if (offset == 0) c.db.vod().search(kind, q) else emptyList()
                cat == CAT_FAV -> if (offset == 0) favoritesNow() else emptyList()
                cat == CAT_CONTINUE -> if (offset == 0) continueItems().first else emptyList()
                cat == CAT_WATCHLIST -> if (offset == 0) c.trakt.watchlistInLibrary().filter { it.kind == kind } else emptyList()
                else -> {
                    val cid = cat.takeIf { it != CAT_ALL }
                    val y = year.value
                    when (sort.value) {
                        VodSort.LIBRARY -> c.db.vod().page(kind, cid, y.min, y.max, pageSize, offset)
                        VodSort.NEWEST -> c.db.vod().pageNewest(kind, cid, y.min, y.max, pageSize, offset)
                        VodSort.TOP -> c.db.vod().pageTopRated(kind, cid, y.min, y.max, pageSize, offset)
                        VodSort.AZ -> c.db.vod().pageAz(kind, cid, y.min, y.max, pageSize, offset)
                    }
                }
            }
            if (page.size < pageSize) _end.value = true
            _items.update { it + page }
        }
    }

    private suspend fun favoritesNow(): List<VodEntity> {
        val keys = favoriteKeys.value.toList()
        return if (keys.isEmpty()) emptyList() else c.db.vod().byKeys(keys).filter { it.kind == kind }
    }

    private suspend fun continueItems(): Pair<List<VodEntity>, Map<String, ProgressEntity>> {
        val p = continueRaw.value.filter { !it.finished || it.episode != null }
        val map = c.db.vod().byKeys(p.map { it.vodKey }).filter { it.kind == kind }.associateBy { it.key }
        val list = p.mapNotNull { map[it.vodKey] }.distinctBy { it.key }
        return list to p.associateBy { it.vodKey }
    }

    // ---------------- Spotlight ----------------
    private val _spot = MutableStateFlow(SpotlightState())
    val spotlight: StateFlow<SpotlightState> = _spot.asStateFlow()
    private var spotJob: Job? = null

    private fun buildSpotlight() {
        spotJob?.cancel()
        spotJob = viewModelScope.launch {
            val dao = c.db.vod()
            val since = System.currentTimeMillis() - 400L * 86400_000
            val hero = dao.spotlightCandidates(kind, since, 8).ifEmpty { dao.newest(kind, 8).filter { it.poster != null } }.take(6)
            val rows = ArrayList<VodRow>()
            val (cont, prog) = continueItems()
            if (cont.isNotEmpty()) rows += VodRow("Continue watching", cont, prog)
            val wl = runCatching { c.trakt.watchlistInLibrary().filter { it.kind == kind } }.getOrDefault(emptyList())
            if (wl.isNotEmpty()) rows += VodRow("Your Trakt watchlist", wl)
            val favs = favoritesNow()
            if (favs.isNotEmpty()) rows += VodRow("My collection", favs)
            val newest = dao.newest(kind, 30)
            if (newest.isNotEmpty()) rows += VodRow("Recently added", newest)
            for (cat in categories.value.ifEmpty { dao.categoriesOnce(kind) }.filter { !it.adult }.take(10)) {
                val list = dao.newestIn(kind, cat.id, 24)
                if (list.size >= 4) rows += VodRow(cat.name, list)
            }
            _spot.value = SpotlightState(hero, rows, loading = false)
        }
    }

    fun toggleFavorite(v: VodEntity) = viewModelScope.launch {
        if (v.key in favoriteKeys.value) c.db.vod().removeFavorite(v.key) else c.db.vod().addFavorite(VodFavoriteEntity(v.key, System.currentTimeMillis()))
        buildSpotlight()
    }

    suspend fun details(v: VodEntity): VodDetails = c.vod.details(v)

    fun isWatched(v: VodEntity) = c.trakt.isWatched(v)

    val heroDetails = MutableStateFlow<Map<String, VodDetails>>(emptyMap())
    fun loadHeroDetails(v: VodEntity) = viewModelScope.launch {
        if (heroDetails.value.containsKey(v.key)) return@launch
        val d = runCatching { c.vod.details(v) }.getOrNull() ?: return@launch
        heroDetails.update { it + (v.key to d) }
    }

    fun refreshAll() { com.arena.tv.data.LibraryUpdates.start(c.appContext) }
    val library = c.iptv.library
}
