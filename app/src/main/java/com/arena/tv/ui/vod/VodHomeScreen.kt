package com.arena.tv.ui.vod

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.arena.tv.data.ProgressEntity
import com.arena.tv.data.VodDetails
import com.arena.tv.data.VodEntity
import com.arena.tv.data.VodKind
import com.arena.tv.ui.appViewModel
import com.arena.tv.ui.components.ArenaTopBar
import com.arena.tv.ui.components.DropChip
import com.arena.tv.ui.components.EmptyState
import com.arena.tv.ui.components.GhostButton
import com.arena.tv.ui.components.IconAction
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.PaneItem
import com.arena.tv.ui.components.PaneLabel
import com.arena.tv.ui.components.PillTabs
import com.arena.tv.ui.components.WhiteButton
import com.arena.tv.ui.components.panel
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline
import kotlinx.coroutines.delay

fun openTrailer(context: Context, youtubeId: String) {
    val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$youtubeId"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$youtubeId"))
    runCatching { context.startActivity(app) }.onFailure { runCatching { context.startActivity(web) } }
}

fun formatDuration(secs: Int?): String? = secs?.takeIf { it > 0 }?.let { s ->
    val h = s / 3600; val m = (s % 3600) / 60
    if (h > 0) "${h}h ${m}m" else "${m}m"
}

@Composable
fun VodHomeScreen(kind: Int, onOpen: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel(key = "vod-$kind") { VodViewModel(it, kind) }
    val tab by vm.tab.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refresh by vm.library.collectAsStateWithLifecycle()
    val year by vm.year.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var searching by remember { mutableStateOf(false) }
    val noun = if (kind == VodKind.MOVIE) "movies" else "series"

    Column(Modifier.fillMaxSize()) {
        ArenaTopBar(
            left = {
                PillTabs(listOf("Spotlight", "Library"), tab) { vm.tab.value = it }
                if (tab == 1) DropChip("Sort: ${sort.label}", VodSort.entries.map { it to it.label }, sort) { vm.sort.value = it }
            },
            right = {
                IconAction(Icons.Rounded.Search, "Search") { searching = !searching; if (!searching) vm.query.value = "" else vm.tab.value = 1 }
                DropChip(if (year == YearFilter.ANY) "Year" else year.label, YearFilter.entries.map { it to it.label }, year) { vm.year.value = it; vm.tab.value = 1 }
            },
        )
        if (searching) {
            com.arena.tv.ui.components.TvTextField(query, { vm.query.value = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search $noun") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconAction(Icons.Rounded.Close, "Clear") { vm.query.value = "" } })
        }
        when {
            settings?.hasSource == false -> EmptyState("No $noun yet", "Add your provider in Settings.", action = "Open settings", onAction = onOpenSettings)
            total == 0 && settings?.playlists?.none { if (kind == VodKind.MOVIE) it.loadMovies else it.loadSeries } == true ->
                EmptyState("${noun.replaceFirstChar { it.uppercase() }} are turned off", "Turn them on in Settings → Edit playlist. They load after Sports and Live TV.",
                    action = "Open settings", onAction = onOpenSettings)
            total == 0 -> EmptyState(if (refresh.running) "Loading $noun…" else "No $noun",
                refresh.message ?: "Your provider didn't list any $noun. Try \"Update movies & series\" in Settings → Playlists.",
                action = if (refresh.running) null else "Reload", onAction = { vm.refreshAll() })
            tab == 0 -> Spotlight(vm, onOpen)
            else -> Library(vm, onOpen)
        }
    }
}

// ------------------------------------------------------------ Spotlight

@Composable
private fun Spotlight(vm: VodViewModel, onOpen: (String) -> Unit) {
    val spot by vm.spotlight.collectAsStateWithLifecycle()
    val wide = LocalWide.current
    if (spot.loading) { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (spot.hero.isNotEmpty()) item { HeroPager(vm, spot.hero, onOpen) }
        spot.rows.forEach { row ->
            item(key = row.title) {
                Column(Modifier.padding(top = 16.dp)) {
                    Text(row.title, fontSize = if (wide) 24.sp else 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(row.items, key = { row.title + it.key }) { v ->
                            PosterCard(v, row.progress[v.key], vm.isWatched(v), Modifier.width(if (wide) 150.dp else 120.dp)) { onOpen(v.key) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroPager(vm: VodViewModel, hero: List<VodEntity>, onOpen: (String) -> Unit) {
    val wide = LocalWide.current
    val pager = rememberPagerState { hero.size }
    val details by vm.heroDetails.collectAsStateWithLifecycle()
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(pager.currentPage) { vm.loadHeroDetails(hero[pager.currentPage]) }
    LaunchedEffect(hero.size) { while (hero.size > 1) { delay(9000); pager.animateScrollToPage((pager.currentPage + 1) % hero.size) } }
    Box(Modifier.fillMaxWidth().height(if (wide) 560.dp else 460.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val v = hero[page]
            HeroContent(v, details[v.key], v.key in favs, onPlay = { onOpen(v.key) }, onFav = { vm.toggleFavorite(v) },
                onTrailer = { t -> openTrailer(context, t) }, onInfo = { onOpen(v.key) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HeroContent(v: VodEntity, d: VodDetails?, fav: Boolean, onPlay: () -> Unit, onFav: () -> Unit, onTrailer: (String) -> Unit, onInfo: () -> Unit) {
    val wide = LocalWide.current
    Box(Modifier.fillMaxSize()) {
        AsyncImage(d?.backdrop ?: v.backdrop ?: v.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            alignment = if (wide) Alignment.CenterEnd else Alignment.TopCenter)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF0000000), 0.55f to Color(0x99000000), 1f to Color(0x11000000))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to ArenaColors.Bg)))
        Column(Modifier.align(Alignment.BottomStart).padding(start = if (wide) 56.dp else 18.dp, bottom = if (wide) 70.dp else 40.dp, end = 18.dp)
            .widthIn(max = 720.dp)) {
            Text(v.name, fontSize = if (wide) 54.sp else 32.sp, fontWeight = FontWeight.Bold, lineHeight = if (wide) 58.sp else 36.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
            Spacer(Modifier.height(14.dp))
            MetaChips(v, d)
            d?.cast?.let {
                Spacer(Modifier.height(12.dp))
                Text(it.split(',').take(3).joinToString(" · ") { s -> s.trim() }, color = Color(0xAAFFFFFF), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                WhiteButton(if (v.kind == VodKind.MOVIE) "Play" else "Episodes", onPlay, icon = Icons.Rounded.PlayArrow)
                GhostButton("Favorite", onFav, icon = if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    iconTint = if (fav) ArenaColors.Gold else Color.White)
                d?.trailer?.let { t -> GhostButton("Trailer", { onTrailer(t) }) }
                GhostButton("More info", onInfo)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetaChips(v: VodEntity, d: VodDetails?) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        formatDuration(d?.durationSecs)?.let { Chip(it) }
        (d?.year ?: v.year)?.let { Chip("$it") }
        (d?.genre ?: v.genre)?.split(',', '/')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { Chip(it) }
        (d?.rating ?: v.rating)?.let { Chip("★ ${"%.1f".format(it)}", highlight = true) }
    }
}

@Composable
private fun Chip(text: String, highlight: Boolean = false) {
    Text(text, color = if (highlight) Color.Black else Color(0xDDFFFFFF), fontSize = 16.sp, fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (highlight) ArenaColors.Gold else Color(0x33FFFFFF)).padding(horizontal = 12.dp, vertical = 5.dp))
}

// ------------------------------------------------------------ Library

@Composable
private fun Library(vm: VodViewModel, onOpen: (String) -> Unit) {
    val wide = LocalWide.current
    if (wide) {
        Row(Modifier.fillMaxSize().padding(start = 4.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Slim category list (the count sits right next to the name) so the grid gets the room.
            CategoryPane(vm, Modifier.width(if (com.arena.tv.ui.components.LocalCompactChrome.current) 236.dp else 270.dp).fillMaxHeight())
            Grid(vm, onOpen, Modifier.weight(1f).fillMaxHeight().panel())
        }
    } else {
        Column {
            CategoryChips(vm)
            Grid(vm, onOpen, Modifier.weight(1f))
        }
    }
}

@Composable
private fun CategoryPane(vm: VodViewModel, modifier: Modifier) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val cat by vm.category.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val cq by vm.catQuery.collectAsStateWithLifecycle()
    val trakt by vm.trakt.collectAsStateWithLifecycle()
    val noun = if (vm.kind == VodKind.MOVIE) "Movies" else "Series"
    val open by vm.unlocked.collectAsStateWithLifecycle()
    val settingsNow by vm.settings.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<String?>(null) }
    fun pick(id: String) { vm.category.value = id; vm.query.value = "" }
    pinFor?.let { target ->
        com.arena.tv.ui.components.PinDialog("Adult ${noun.lowercase()}", creating = settingsNow?.parentalPin.isNullOrEmpty(), onDismiss = { pinFor = null },
            check = { vm.checkPin(it) }) { pick(target); pinFor = null }
    }
    LazyColumn(modifier.panel(), contentPadding = PaddingValues(8.dp)) {
        item {
            com.arena.tv.ui.components.TvTextField(cq, { vm.catQuery.value = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("Search in categories") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        }
        item { PaneItem("All $noun", total, cat == CAT_ALL, { pick(CAT_ALL) }) }
        item { PaneLabel("My collections") }
        item { PaneItem("Favorites", null, cat == CAT_FAV, { pick(CAT_FAV) }) }
        item { PaneItem("Continue watching", null, cat == CAT_CONTINUE, { pick(CAT_CONTINUE) }) }
        if (trakt.connected) item { PaneItem("Trakt watchlist", null, cat == CAT_WATCHLIST, { pick(CAT_WATCHLIST) }) }
        item { PaneLabel("Categories") }
        items(cats.filter { cq.isBlank() || it.name.contains(cq, true) }, key = { it.key }) { c ->
            // Adult categories are locked: the PIN is asked before they open.
            PaneItem(if (c.adult && !open) "🔒 ${c.name}" else c.name, c.count, cat == c.id,
                { if (c.adult && !vm.isUnlocked()) pinFor = c.id else pick(c.id) }, dim = c.adult && !open)
        }
    }
}

@Composable
private fun CategoryChips(vm: VodViewModel) {
    val cats by vm.categories.collectAsStateWithLifecycle()
    val cat by vm.category.collectAsStateWithLifecycle()
    val trakt by vm.trakt.collectAsStateWithLifecycle()
    val open by vm.unlocked.collectAsStateWithLifecycle()
    val all = buildList {
        add(CAT_ALL to "All"); add(CAT_FAV to "★ Favorites"); add(CAT_CONTINUE to "Continue")
        if (trakt.connected) add(CAT_WATCHLIST to "Watchlist")
        cats.forEach { add(it.id to if (it.adult && !open) "🔒 ${it.name}" else it.name) }
    }
    val adultIds = cats.filter { it.adult }.map { it.id }.toSet()
    val settingsNow by vm.settings.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<String?>(null) }
    pinFor?.let { target ->
        com.arena.tv.ui.components.PinDialog("Adult titles", creating = settingsNow?.parentalPin.isNullOrEmpty(), onDismiss = { pinFor = null },
            check = { vm.checkPin(it) }) { vm.category.value = target; vm.query.value = ""; pinFor = null }
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(all, key = { it.first }) { (id, label) ->
            FilterChip(modifier = Modifier.focusOutline(), selected = cat == id, onClick = { if (id in adultIds && !vm.isUnlocked()) pinFor = id else { vm.category.value = id; vm.query.value = "" } }, label = { Text(label, maxLines = 1) })
        }
    }
}

@Composable
private fun Grid(vm: VodViewModel, onOpen: (String) -> Unit, modifier: Modifier) {
    val items by vm.items.collectAsStateWithLifecycle()
    val end by vm.reachedEnd.collectAsStateWithLifecycle()
    val wide = LocalWide.current
    val state = rememberLazyGridState()
    val nearEnd by remember { derivedStateOf { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > state.layoutInfo.totalItemsCount - 30 } }
    LaunchedEffect(state) { snapshotFlow { nearEnd }.collect { if (it) vm.loadMore() } }
    if (items.isEmpty()) {
        Box(modifier) { if (end) EmptyState("Nothing here", "Try another category or filter.") else Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() } }
        return
    }
    // Smaller posters: about 4 across on a TV next to the category list.
    LazyVerticalGrid(GridCells.Adaptive(if (wide) 140.dp else 104.dp), modifier, state = state,
        contentPadding = PaddingValues(if (wide) 14.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(if (wide) 14.dp else 10.dp)) {
        items(items, key = { it.key }) { v -> PosterCard(v, null, vm.isWatched(v), Modifier.fillMaxWidth()) { onOpen(v.key) } }
    }
}

/** Poster with rating badge and the title over a dark gradient, like the library grid in the reference. */
@Composable
fun PosterCard(v: VodEntity, progress: ProgressEntity?, watched: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val wide = LocalWide.current
    Box(modifier.aspectRatio(2f / 3f).focusOutline(RoundedCornerShape(if (wide) 14.dp else 10.dp)).clip(RoundedCornerShape(if (wide) 14.dp else 10.dp)).background(ArenaColors.Surface).clickable(onClick = onClick)) {
        AsyncImage(v.poster, v.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0xE6000000))))
        v.rating?.let { r ->
            Text("★ ${(r / 2).let { if (it % 1.0 == 0.0) "${it.toInt()}" else "%.1f".format(it) }}/5", color = Color.White, fontSize = if (wide) 12.sp else 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(7.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 6.dp, vertical = 2.dp))
        }
        if (watched) Icon(Icons.Rounded.CheckCircle, "Watched", tint = ArenaColors.Accent,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(if (wide) 24.dp else 18.dp).clip(CircleShape).background(Color.Black))
        Column(Modifier.align(Alignment.BottomStart).padding(if (wide) 10.dp else 8.dp)) {
            Text(v.name + (v.year?.takeIf { !v.name.contains("$it") }?.let { " ($it)" } ?: ""), color = Color.White,
                fontSize = if (wide) 14.sp else 12.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = if (wide) 17.sp else 15.sp)
            progress?.let { p ->
                if (p.season != null) Text("S${p.season} E${p.episode}", color = Color(0xAAFFFFFF), fontSize = 11.sp)
                Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(3.dp).clip(CircleShape).background(Color(0x44FFFFFF))) {
                    Box(Modifier.fillMaxWidth(p.fraction).fillMaxHeight().background(ArenaColors.Accent))
                }
            }
        }
    }
}
