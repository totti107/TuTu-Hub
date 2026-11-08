package com.arena.tv.ui.vod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.arena.tv.AppContainer
import com.arena.tv.data.EpisodeEntity
import com.arena.tv.data.ProgressEntity
import com.arena.tv.data.VodDetails
import com.arena.tv.data.VodEntity
import com.arena.tv.data.VodFavoriteEntity
import com.arena.tv.data.VodKind
import com.arena.tv.ui.appViewModel
import com.arena.tv.ui.components.EmptyState
import com.arena.tv.ui.components.GhostButton
import com.arena.tv.ui.components.IconAction
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.WhiteButton
import com.arena.tv.ui.player.VodPlayerActivity
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VodDetailViewModel(private val c: AppContainer, val key: String) : ViewModel() {
    val item = MutableStateFlow<VodEntity?>(null)
    val details = MutableStateFlow<VodDetails?>(null)
    val episodes = MutableStateFlow<List<EpisodeEntity>?>(null)
    val season = MutableStateFlow<Int?>(null)
    val progress: StateFlow<Map<String, ProgressEntity>> = c.db.vod().progressFor(key).map { l -> l.associateBy { it.key } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val favorite = c.db.vod().favoriteKeys().map { key in it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val trakt = c.trakt.state
    /** "You might also like": newest titles from the same category. */
    val related = MutableStateFlow<List<VodEntity>>(emptyList())

    init {
        viewModelScope.launch {
            val v = c.db.vod().byKey(key) ?: return@launch
            item.value = v
            related.value = runCatching { c.db.vod().newestIn(v.kind, v.categoryId, 24).filter { it.key != key && it.poster != null }.take(16) }.getOrDefault(emptyList())
            details.value = c.vod.details(v)
            item.value = c.db.vod().byKey(key) ?: v
            if (v.kind == VodKind.SERIES) {
                val eps = runCatching { c.vod.episodes(v) }.getOrDefault(emptyList())
                episodes.value = eps
                // Start on the season of the last watched episode, else the first.
                val last = progress.value.values.maxByOrNull { it.updatedAt }
                season.value = last?.season ?: eps.firstOrNull()?.season
            }
        }
    }

    fun toggleFavorite() = viewModelScope.launch {
        if (favorite.value) c.db.vod().removeFavorite(key) else c.db.vod().addFavorite(VodFavoriteEntity(key, System.currentTimeMillis()))
    }
    fun toggleWatchlist() { val v = item.value ?: return; c.trakt.setWatchlist(v, !c.trakt.inWatchlist(v)) }
    fun inWatchlist() = item.value?.let { c.trakt.inWatchlist(it) } ?: false
    fun watchedEpisodes(): Set<String> = item.value?.let { c.trakt.watchedEpisodes(it) }.orEmpty()
    fun isWatched() = item.value?.let { c.trakt.isWatched(it) } ?: false
    fun startOver() = viewModelScope.launch { c.db.vod().clearProgress(key) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VodDetailScreen(key: String, onBack: () -> Unit, onOpen: (String) -> Unit = {}) {
    val vm = appViewModel(key = "vod-detail-$key") { VodDetailViewModel(it, key) }
    val v by vm.item.collectAsStateWithLifecycle()
    val d by vm.details.collectAsStateWithLifecycle()
    val eps by vm.episodes.collectAsStateWithLifecycle()
    val season by vm.season.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val related by vm.related.collectAsStateWithLifecycle()
    val fav by vm.favorite.collectAsStateWithLifecycle()
    val trakt by vm.trakt.collectAsStateWithLifecycle()
    val wide = LocalWide.current
    val context = LocalContext.current
    val item = v ?: run { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    val isSeries = item.kind == VodKind.SERIES
    val watchedEps = remember(trakt.lastSync, item.key) { vm.watchedEpisodes() }

    // What "Play" does: resume the movie, or the next episode to watch.
    val movieProgress = progress[item.key]
    val nextEpisode: EpisodeEntity? = eps?.let { list ->
        val last = progress.values.filter { it.episode != null }.maxByOrNull { it.updatedAt }
        val lastEp = last?.let { p -> list.firstOrNull { it.key == p.key } }
        when {
            lastEp == null -> list.firstOrNull { "${it.season}:${it.number}" !in watchedEps } ?: list.firstOrNull()
            last.finished -> list.getOrNull(list.indexOf(lastEp) + 1) ?: lastEp
            else -> lastEp
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(if (wide) 440.dp else 420.dp)) {
                AsyncImage(d?.backdrop ?: item.backdrop ?: d?.poster ?: item.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                    alignment = if (wide) Alignment.CenterEnd else Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF2000000), 0.6f to Color(0xAA000000), 1f to Color(0x22000000))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to ArenaColors.Bg)))
                IconAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = onBack)
                Row(Modifier.align(Alignment.BottomStart).padding(start = if (wide) 56.dp else 16.dp, end = 16.dp, bottom = 24.dp)) {
                    if (wide) {
                        AsyncImage(d?.poster ?: item.poster, null, Modifier.width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(32.dp))
                    }
                    Column(Modifier.widthIn(max = 760.dp).align(Alignment.Bottom)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.name, fontSize = if (wide) 34.sp else 28.sp, fontWeight = FontWeight.Bold, lineHeight = if (wide) 38.sp else 32.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                            if (vm.isWatched()) Icon(Icons.Rounded.CheckCircle, "Watched", tint = ArenaColors.Accent, modifier = Modifier.padding(start = 10.dp).size(28.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        MetaChips(item, d)
                        d?.cast?.let {
                            Spacer(Modifier.height(10.dp))
                            Text(it.split(',').take(4).joinToString(" · ") { s -> s.trim() }, color = Color(0xAAFFFFFF), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(16.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (!isSeries) {
                                val resume = movieProgress?.takeIf { !it.finished && it.positionMs > 60_000 }
                                WhiteButton(if (resume != null) "Resume ${clock(resume.positionMs)}" else "Play",
                                    { VodPlayerActivity.start(context, item.key, null) }, icon = Icons.Rounded.PlayArrow)
                                if (resume != null) GhostButton("Start over", { vm.startOver(); VodPlayerActivity.start(context, item.key, null) }, icon = Icons.Rounded.Replay)
                            } else nextEpisode?.let { e ->
                                WhiteButton("${if (progress.containsKey(e.key)) "Resume" else "Play"} S${e.season} E${e.number}",
                                    { VodPlayerActivity.start(context, item.key, e.key) }, icon = Icons.Rounded.PlayArrow)
                            }
                            GhostButton("Favorite", vm::toggleFavorite, icon = if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                iconTint = if (fav) ArenaColors.Gold else Color.White)
                            d?.trailer?.let { t -> GhostButton("Trailer", { openTrailer(context, t) }) }
                            if (trakt.connected) {
                                val inWl = remember(trakt.watchlist) { vm.inWatchlist() }
                                GhostButton(if (inWl) "In watchlist" else "Watchlist", vm::toggleWatchlist,
                                    icon = if (inWl) Icons.Rounded.BookmarkAdded else Icons.Rounded.BookmarkAdd)
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = if (wide) 56.dp else 16.dp).widthIn(max = 1000.dp)) {
                (d?.plot ?: item.plot)?.let { Text(it, color = Color(0xDDFFFFFF), fontSize = if (wide) 18.sp else 15.sp, lineHeight = if (wide) 26.sp else 21.sp) }
                d?.director?.let { Text("Director: $it", color = ArenaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp)) }
                d?.genre?.let { Text("Genre: $it", color = ArenaColors.Muted, fontSize = 14.sp) }
            }
        }
        if (!isSeries && related.isNotEmpty()) {
            item {
                Text("You might also like", fontSize = if (wide) 22.sp else 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = if (wide) 56.dp else 16.dp, top = 24.dp, bottom = 10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = if (wide) 52.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(related, key = { it.key }) { r -> PosterCard(r, null, false, Modifier.width(if (wide) 140.dp else 110.dp)) { onOpen(r.key) } }
                }
            }
        }
        if (isSeries) {
            val list = eps
            if (list == null) item { Box(Modifier.fillMaxWidth().padding(40.dp), Alignment.Center) { CircularProgressIndicator() } }
            else if (list.isEmpty()) item { EmptyState("No episodes", "Your provider didn't list episodes for this series.") }
            else {
                val seasons = list.map { it.season }.distinct().sorted()
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = if (wide) 52.dp else 12.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s -> FilterChip(modifier = Modifier.focusOutline(), selected = s == season, onClick = { vm.season.value = s }, label = { Text("Season $s") }) }
                    }
                }
                items(list.filter { it.season == (season ?: seasons.first()) }, key = { it.key }) { e ->
                    EpisodeRow(e, progress[e.key], "${e.season}:${e.number}" in watchedEps, item) { VodPlayerActivity.start(context, item.key, e.key) }
                }
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun EpisodeRow(e: EpisodeEntity, p: ProgressEntity?, traktWatched: Boolean, series: VodEntity, onPlay: () -> Unit) {
    val wide = LocalWide.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (wide) 52.dp else 12.dp, vertical = 5.dp).clip(RoundedCornerShape(16.dp))
            .background(ArenaColors.Surface).clickable(onClick = onPlay).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(if (wide) 220.dp else 132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(ArenaColors.SurfaceHi)) {
            AsyncImage(e.image ?: series.backdrop ?: series.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp).clip(CircleShape).background(Color(0x88000000)))
            p?.let {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
                    Box(Modifier.fillMaxWidth(it.fraction).fillMaxHeight().background(ArenaColors.Accent))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${e.number}. ${e.title}", fontWeight = FontWeight.SemiBold, fontSize = if (wide) 18.sp else 15.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                if (traktWatched || p?.finished == true) Icon(Icons.Rounded.CheckCircle, "Watched", tint = ArenaColors.Accent, modifier = Modifier.padding(start = 6.dp).size(18.dp))
            }
            formatDuration(e.durationSecs)?.let { Text(it, color = ArenaColors.Muted, fontSize = 13.sp) }
            e.plot?.let { Text(it, color = ArenaColors.Muted, fontSize = 13.sp, maxLines = if (wide) 3 else 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

