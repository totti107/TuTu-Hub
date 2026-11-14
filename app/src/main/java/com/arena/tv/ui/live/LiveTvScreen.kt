package com.arena.tv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Hd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.ProgramEntity
import com.arena.tv.ui.appViewModel
import com.arena.tv.ui.components.ArenaTopBar
import com.arena.tv.ui.components.EmptyState
import com.arena.tv.ui.components.IconAction
import com.arena.tv.ui.components.LocalWide
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.components.PaneItem
import com.arena.tv.ui.components.PaneLabel
import com.arena.tv.ui.components.PillTabs
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.components.panel
import com.arena.tv.ui.player.PlayerActivity
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline

fun timeLeft(stop: Long, now: Long = System.currentTimeMillis()): String {
    val m = ((stop - now) / 60_000).coerceAtLeast(0)
    return if (m >= 60) "${m / 60} h ${m % 60} min left" else "$m min left"
}

@Composable
fun LiveTvScreen(onOpenSettings: () -> Unit) {
    val vm = appViewModel { LiveTvViewModel(it) }
    // Leaving Live TV (Home, Movies…) locks adult channels again.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.parental.lock() } }
    Box(Modifier.fillMaxSize()) {
        LiveTvContent(onOpenSettings)
        ChannelMenus(vm)
    }
}

@Composable
private fun LiveTvContent(onOpenSettings: () -> Unit) {
    val vm = appViewModel { LiveTvViewModel(it) }
    val tab by vm.tab.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val refresh by vm.refresh.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val hd by vm.hdOnly.collectAsStateWithLifecycle()
    val wide = LocalWide.current
    var searching by remember { mutableStateOf(false) }

    // Big screens: the tabs sit at the top of the categories pane (all the way left), panes use the full width.
    if (wide && settings?.hasSource != false && groups.isNotEmpty()) {
        val compactChrome = com.arena.tv.ui.components.LocalCompactChrome.current
        val header: @Composable () -> Unit = { PillTabs(listOf("Library", "EPG", if (compactChrome) "Sports" else "Sports Mode"), tab, compact = true) { vm.tab.value = it } }
        Column(Modifier.fillMaxSize()) {
            com.arena.tv.ui.components.PillStrip()
            Box(Modifier.weight(1f)) { when (tab) {
                1 -> EpgWide(vm, header)
                2 -> {
                    val hub = appViewModel(key = "live-hub") { com.arena.tv.ui.sports.SportsHubViewModel(it) }
                    val mc = appViewModel(key = "live-mc") { com.arena.tv.ui.sports.MatchCentreViewModel(it) }
                    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { hub.poll() } }
                    com.arena.tv.ui.sports.SportsMode(hub, mc, header, onOpenGame = {})
                }
                else -> LibraryWide(vm, header, searching, { searching = !searching; if (!searching) vm.query.value = "" })
            } }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ArenaTopBar(
            left = { PillTabs(listOf("Library", "EPG", "Sports"), tab) { vm.tab.value = it } },
            right = {
                IconAction(Icons.Rounded.Search, "Search channels") { searching = !searching; if (!searching) vm.query.value = "" }
                IconAction(Icons.Rounded.Hd, "HD only", tint = if (hd) ArenaColors.Accent else ArenaColors.Text) { vm.hdOnly.value = !hd }
                IconAction(Icons.Rounded.Refresh, "Reload channels") { vm.refreshNow() }
            },
        )
        if (searching) {
            com.arena.tv.ui.components.TvTextField(query, { vm.query.value = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search all channels") }, singleLine = true, shape = RoundedCornerShape(14.dp),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconAction(Icons.Rounded.Close, "Clear") { vm.query.value = "" } })
        }
        when {
            settings?.hasSource == false ->
                EmptyState("No channels yet", "Add your M3U playlist or Xtream login to start watching.", action = "Add playlist", onAction = onOpenSettings)
            groups.isEmpty() -> EmptyState(
                if (refresh.running) "Loading channels…" else "No channels",
                refresh.message ?: refresh.error ?: "Pull your channel list from your provider.",
                action = if (refresh.running) null else "Refresh", onAction = { vm.refreshNow() },
            )
            tab == 1 -> Column { GroupChips(vm, sporty = false); EpgGrid(vm, Modifier.weight(1f)) }
            else -> Column { GroupChips(vm, sporty = tab == 2); ChannelList(vm, Modifier.weight(1f)) }
        }
    }
}

// ------------------------------------------------------------ categories

@Composable
private fun CategoryPane(vm: LiveTvViewModel, sporty: Boolean, modifier: Modifier, header: (@Composable () -> Unit)? = null) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val section by vm.section.collectAsStateWithLifecycle()
    val total by vm.total.collectAsStateWithLifecycle()
    val favs by vm.favCount.collectAsStateWithLifecycle()
    val recents by vm.recentCount.collectAsStateWithLifecycle()
    val gq by vm.groupQuery.collectAsStateWithLifecycle()
    val names by vm.playlistNames.collectAsStateWithLifecycle()
    val open by vm.unlocked.collectAsStateWithLifecycle()
    val settingsNow by vm.settings.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<Section?>(null) }
    pinFor?.let { target ->
        com.arena.tv.ui.components.PinDialog("Adult channels", creating = settingsNow?.parentalPin.isNullOrEmpty(), onDismiss = { pinFor = null },
            check = vm::checkPin) { vm.section.value = target; vm.query.value = ""; pinFor = null }
    }
    val shown = groups.filter { (!sporty || isSportyGroup(it.groupTitle)) && (gq.isBlank() || it.groupTitle.contains(gq, true)) }
    fun pick(s: Section) { vm.section.value = s; vm.query.value = "" }
    LazyColumn(modifier.panel(), contentPadding = PaddingValues(12.dp)) {
        header?.let { h -> item { Box(Modifier.padding(bottom = 10.dp)) { h() } } }
        item {
            com.arena.tv.ui.components.TvTextField(gq, { vm.groupQuery.value = it }, Modifier.fillMaxWidth().padding(bottom = 8.dp),
                placeholder = { Text("Search categories…") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        }
        if (!sporty) item { PaneItem("All Channels", total, section == Section.All, { pick(Section.All) }) }
        item { PaneLabel("My collection") }
        item { PaneItem("Favorites", favs, section == Section.Favorites, { pick(Section.Favorites) }) }
        if (!sporty) item { PaneItem("Recent", recents, section == Section.Recent, { pick(Section.Recent) }) }
        if (shown.map { it.playlistId }.distinct().size <= 1) item { PaneLabel(if (sporty) "Sports categories" else "Playlist categories") }
        // Several playlists: each gets its own labelled list, so switching one off clearly removes its categories.
        val multi = shown.map { it.playlistId }.distinct().size > 1
        shown.groupBy { it.playlistId }.forEach { (pid, list) ->
            if (multi) item(key = "pl-$pid") { PaneLabel(names[pid] ?: "Playlist") }
            items(list, key = { "${it.playlistId}|${it.groupTitle}" }) { g ->
                val sec = Section.Group(g.groupTitle, g.playlistId)
                // Adult categories are locked: the PIN is asked before they open.
                PaneItem(if (g.adult && !open) "🔒 ${g.groupTitle}" else g.groupTitle, g.count, section == sec,
                    { if (g.adult && !vm.parental.isUnlocked()) pinFor = sec else pick(sec) }, dim = g.adult && !open)
            }
        }
    }
}

@Composable
private fun GroupChips(vm: LiveTvViewModel, sporty: Boolean) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val section by vm.section.collectAsStateWithLifecycle()
    val settingsNow by vm.settings.collectAsStateWithLifecycle()
    var pinFor by remember { mutableStateOf<Section?>(null) }
    pinFor?.let { target ->
        com.arena.tv.ui.components.PinDialog("Adult channels", creating = settingsNow?.parentalPin.isNullOrEmpty(), onDismiss = { pinFor = null },
            check = vm::checkPin) { vm.section.value = target; vm.query.value = ""; pinFor = null }
    }
    val all: List<Pair<Section, String>> = buildList {
        add(Section.Favorites to "★ Favorites")
        if (!sporty) { add(Section.Recent to "Recent"); add(Section.All to "All") }
        val multi = groups.map { it.playlistId }.distinct().size > 1
        val names = vm.playlistNames.value
        groups.filter { !sporty || isSportyGroup(it.groupTitle) }.forEach {
            add(Section.Group(it.groupTitle, it.playlistId) to (if (it.adult) "🔒 " else "") + (if (multi) "${names[it.playlistId] ?: ""} · " else "") + it.groupTitle)
        }
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(all, key = { (s, label) -> if (s is Section.Group) s.playlistId + "|" + s.name else label }) { (s, label) ->
            FilterChip(modifier = Modifier.focusOutline(), selected = s == section, onClick = {
                val adult = s is Section.Group && groups.any { g -> g.adult && g.groupTitle == s.name && g.playlistId == s.playlistId }
                if (adult && !vm.parental.isUnlocked()) pinFor = s else { vm.section.value = s; vm.query.value = "" }
            }, label = { Text(label, maxLines = 1) })
        }
    }
}

// ------------------------------------------------------------ library mode

@Composable
private fun LibraryWide(vm: LiveTvViewModel, header: @Composable () -> Unit, searching: Boolean, toggleSearch: () -> Unit) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val hd by vm.hdOnly.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    // Categories far left, a compact channel column, and the widest pane for the preview + guide.
    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CategoryPane(vm, false, Modifier.weight(0.25f).fillMaxHeight(), header)
        Column(Modifier.weight(0.33f).fillMaxHeight().panel()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Icons.Rounded.Search, "Search channels", onClick = toggleSearch)
                IconAction(Icons.Rounded.Hd, "HD only", tint = if (hd) ArenaColors.Accent else ArenaColors.Text) { vm.hdOnly.value = !hd }
                IconAction(Icons.Rounded.Refresh, "Reload channels") { vm.refreshNow() }
            }
            if (searching) com.arena.tv.ui.components.TvTextField(query, { vm.query.value = it }, Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                placeholder = { Text("Search all channels") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            ChannelList(vm, Modifier.weight(1f).fillMaxWidth(), wide = true)
        }
        PreviewPane(vm, selected, Modifier.weight(0.42f).fillMaxHeight())
    }
}

@Composable
private fun ChannelList(vm: LiveTvViewModel, modifier: Modifier, wide: Boolean = false) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val guide by vm.guide.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun play(ch: ChannelEntity) = PlayerActivity.start(context, ch.id, channels.map { it.id }, null)
    if (channels.isEmpty()) { Box(modifier) { EmptyState("Nothing here", "Channels you favorite or watch show up here.") }; return }
    LaunchedEffect(channels.firstOrNull()?.id, wide) { if (wide && selected == null) channels.firstOrNull()?.let(vm::select) }
    // Scroll to the channel you were watching (or the selected one) and put the remote's focus on it.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val selectedFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(selected?.id, channels.size) {
        val i = channels.indexOfFirst { it.id == selected?.id }
        if (i >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == i }) listState.scrollToItem((i - 2).coerceAtLeast(0))
    }
    LaunchedEffect(vm.focusSelected.collectAsStateWithLifecycle().value) {
        if (vm.focusSelected.value) { kotlinx.coroutines.delay(150); runCatching { selectedFocus.requestFocus() }; vm.focusSelected.value = false }
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
            Box(if (selected?.id == ch.id) Modifier.focusRequester(selectedFocus) else Modifier) {
                ChannelCard(i + 1, ch, guide[ch.id], wide && selected?.id == ch.id, ch.id in favs,
                    onClick = { if (wide && selected?.id != ch.id) vm.select(ch) else play(ch) },
                    onFav = { vm.toggleFavorite(ch.id) }, onMenu = { vm.menuFor.value = ch })
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun ChannelCard(number: Int, ch: ChannelEntity, nn: NowNext?, selected: Boolean, fav: Boolean, onClick: () -> Unit, onFav: () -> Unit,
                         onMenu: (() -> Unit)? = null) {
    // Compact, like the reference: small round logo, one bold line for the name, the current show underneath when the guide has it.
    val now = nn?.now
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) ArenaColors.SurfaceHi else ArenaColors.Surface)
            .then(if (selected) Modifier.border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(16.dp)) else Modifier)
            // Hold OK (or the remote's Menu key) for Favorite / Hide.
            .onKeyEvent { e -> if (onMenu != null && e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Menu) { onMenu(); true } else false }
            .combinedClickable(onClick = onClick, onLongClick = onMenu).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("%02d".format(number), color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.width(24.dp))
        RingLogo(ch, now?.let { Time.progress(it.start, it.stop) }, 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (now != null) {
                Row {
                    Text(now.title, color = Color(0xCCFFFFFF), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, false))
                    Text("  ${timeLeft(now.stop)}", color = ArenaColors.Muted, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 1)
                }
            }
        }
        if (fav || selected) Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onFav), contentAlignment = Alignment.Center) {
            Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite", tint = if (fav) ArenaColors.Gold else Color(0x88FFFFFF), modifier = Modifier.size(16.dp))
        } else Spacer(Modifier.width(8.dp))
    }
}

/** Small round logo; the current show's progress is drawn as a thin ring around it. */
@Composable
private fun RingLogo(ch: ChannelEntity, progress: Float?, size: Dp) {
    Box(
        Modifier.size(size).drawBehind {
            val w = 2.dp.toPx()
            if (progress != null) {
                drawCircle(Color(0x33FFFFFF), style = Stroke(w))
                drawArc(Color.White, -90f, 360f * progress, false, Offset(w / 2, w / 2),
                    Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
        }.padding(3.dp).clip(CircleShape).background(Color(0xFF0E0E10)),
        contentAlignment = Alignment.Center,
    ) { Logo(ch.logo, ch.name, size - 12.dp) }
}

@Composable
private fun PreviewPane(vm: LiveTvViewModel, ch: ChannelEntity?, modifier: Modifier) {
    val schedule by vm.schedule.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    Column(modifier.panel().padding(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))) {
            PreviewPlayer(ch, Modifier.fillMaxSize())
            if (ch != null) Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(Color(0x88000000))
                .clickable { PlayerActivity.start(context, ch.id, channels.map { it.id }, null) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Fullscreen, "Full screen", tint = Color.White)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(ch?.name ?: "", fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        if (ch != null && schedule.isEmpty()) Text(if (ch.epgKey == null) "No TV guide for this channel" else "No schedule", color = ArenaColors.Muted)
        ScheduleList(ch?.id, schedule, now)
    }
}

// ------------------------------------------------------------ EPG

@Composable
private fun EpgWide(vm: LiveTvViewModel, header: @Composable () -> Unit) {
    val selected by vm.selected.collectAsStateWithLifecycle()
    val guide by vm.guide.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val c = com.arena.tv.ui.components.LocalCompactChrome.current
    Row(Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CategoryPane(vm, false, Modifier.weight(0.25f).fillMaxHeight(), header)
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(0.75f).fillMaxHeight()) {
            val topH = (maxHeight * 0.40f).coerceIn(160.dp, 300.dp)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().height(topH), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NowInfo(selected, selected?.let { guide[it.id] }, Modifier.weight(1.45f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight().panel().padding(10.dp)) {
                        PreviewPlayer(selected, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)))
                        selected?.let { ch ->
                            val f = ch.id in favs
                            Row(Modifier.align(Alignment.End).padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.toggleFavorite(ch.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (f) Icons.Rounded.Star else Icons.Rounded.StarBorder, null, tint = if (f) ArenaColors.Gold else Color.White, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp)); Text("Favorite", fontSize = if (c) 14.sp else 17.sp)
                            }
                        }
                    }
                }
                EpgGrid(vm, Modifier.weight(1f).fillMaxWidth().panel())
            }
        }
    }
}

/** Big "now on" card above the guide: channel, show, times, progress and description. */
@Composable
internal fun NowInfo(ch: ChannelEntity?, nn: NowNext?, modifier: Modifier) {
    val c = com.arena.tv.ui.components.LocalCompactChrome.current
    Column(modifier.panel().padding(if (c) 16.dp else 22.dp)) {
        if (ch == null) { Text("Pick a channel in the guide", color = ArenaColors.Muted); return }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(ch.logo, ch.name, if (c) 30.dp else 40.dp); Spacer(Modifier.width(12.dp))
            Text(ch.name, fontSize = if (c) 16.sp else 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(if (c) 6.dp else 10.dp))
        val now = nn?.now
        if (now == null) {
            Text("No guide information", fontSize = if (c) 20.sp else 26.sp, fontWeight = FontWeight.Bold, color = ArenaColors.Muted)
            Text("Your provider's TV guide doesn't cover this channel.", color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            return
        }
        Text(now.title, fontSize = if (c) 22.sp else 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${Time.clock(now.start)}   |   ${timeLeft(now.stop)}", fontSize = if (c) 13.sp else 16.sp)
            Spacer(Modifier.weight(1f))
            nn.next?.let { Text("${it.title}  ${Time.clock(it.start)}", color = ArenaColors.Muted, fontSize = if (c) 12.sp else 15.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp)) }
        }
        LinearProgressIndicator(progress = { Time.progress(now.start, now.stop) }, Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp).clip(CircleShape),
            color = Color.White, trackColor = Color(0x33FFFFFF), drawStopIndicator = {})
        now.description?.let { Text(it, color = ArenaColors.Muted, fontSize = if (c) 13.sp else 15.sp, maxLines = if (c) 2 else 3,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp)) }
    }
}

/** Channels down, time across. All rows share one horizontal scroll so the timeline stays aligned. */
@Composable
private fun EpgGrid(vm: LiveTvViewModel, modifier: Modifier) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val now = remember { System.currentTimeMillis() }
    val from = remember { now - (now % 1_800_000) - 3_600_000 }   // an hour before the current half hour
    val rows = channels.take(300)
    val programs by remember(rows.map { it.id }) { vm.gridPrograms(rows, from, from + 9 * 3600_000L) }.collectAsState(initial = emptyMap())
    EpgGridContent(rows, programs, selected, now, from, vm::select, modifier)
}

@Composable
internal fun EpgGridContent(rows: List<ChannelEntity>, programs: Map<String, List<ProgramEntity>>, selected: ChannelEntity?, now: Long, from: Long,
                            select: (ChannelEntity) -> Unit, modifier: Modifier) {
    val vm = object { fun select(ch: ChannelEntity) = select(ch) }
    val wide = LocalWide.current
    val c = com.arena.tv.ui.components.LocalCompactChrome.current
    val context = LocalContext.current
    val to = from + 9 * 3600_000L
    val dpPerMin = if (!wide) 3.2f else if (c) 3.8f else 4.6f
    val nameW = if (!wide) 130.dp else if (c) 210.dp else 300.dp
    val rowH = if (c || !wide) 54.dp else 64.dp
    val h = rememberScrollState()
    LaunchedEffect(Unit) { h.scrollTo((((now - from) / 60_000 - 30) * dpPerMin * context.resources.displayMetrics.density).toInt().coerceAtLeast(0)) }
    fun x(t: Long) = (((t - from) / 60_000f) * dpPerMin).dp
    fun play(ch: ChannelEntity) = PlayerActivity.start(context, ch.id, rows.map { it.id }, null)
    val scrolled = with(androidx.compose.ui.platform.LocalDensity.current) { h.value.toDp() }

    Column(modifier.padding(8.dp)) {
        // Header: "Today" + half-hour marks, with a marker at the current time.
        Row(Modifier.height(36.dp)) {
            Text("Today", Modifier.width(nameW).padding(start = 12.dp, top = 6.dp), fontSize = if (c) 17.sp else 20.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f).horizontalScroll(h)) {
                Box(Modifier.width(x(to)).fillMaxHeight()) {
                    var t = from
                    while (t < to) {
                        Text(Time.clock(t), color = ArenaColors.Muted, fontSize = if (c) 12.sp else 14.sp, modifier = Modifier.offset(x = x(t) + 4.dp, y = 4.dp))
                        Box(Modifier.offset(x = x(t), y = 24.dp).width(1.dp).height(8.dp).background(Color(0x44FFFFFF)))
                        t += 1_800_000L
                    }
                    Text("▼", color = Color.White, fontSize = 12.sp, modifier = Modifier.offset(x = x(now) - 5.dp, y = 20.dp))
                }
            }
        }
        if (rows.isEmpty()) { EmptyState("No channels", "Pick another category."); return }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(rows, key = { _, ch -> ch.id }) { i, ch ->
                val isSel = selected?.id == ch.id
                Row(Modifier.height(rowH)) {
                    Row(
                        Modifier.width(nameW).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) ArenaColors.SurfaceHi else ArenaColors.Surface)
                            .clickable { if (isSel) play(ch) else vm.select(ch) }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.width(24.dp))
                        Logo(ch.logo, ch.name, if (c) 24.dp else 30.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(ch.name, fontSize = if (c) 14.sp else 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(h)) {
                        Box(Modifier.width(x(to)).fillMaxHeight()) {
                            val list = programs[ch.epgKey].orEmpty()
                            if (list.isEmpty()) {
                                Box(Modifier.offset(x = x(now - 1_800_000)).width(x(now + 3 * 3600_000L) - x(now - 1_800_000)).fillMaxHeight()
                                    .clip(RoundedCornerShape(10.dp)).background(Color(0xFF111114)).clickable { if (isSel) play(ch) else vm.select(ch) }
                                    .padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                                    Text("No guide information", color = ArenaColors.Muted, fontSize = 13.sp)
                                }
                            }
                            list.forEach { p ->
                                val st = maxOf(p.start, from); val e = minOf(p.stop, to)
                                if (e <= st) return@forEach
                                val live = p.start <= now && p.stop > now
                                val past = p.stop <= now
                                Column(
                                    Modifier.offset(x = x(st)).width(x(e) - x(st) - 3.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                                        .background(if (live) ArenaColors.SurfaceHi else ArenaColors.Surface)
                                        .then(if (live) Modifier.border(if (isSel) 2.dp else 1.dp, if (isSel) Color.White else Color(0x66FFFFFF), RoundedCornerShape(10.dp)) else Modifier)
                                        .clickable { if (live && isSel) play(ch) else vm.select(ch) }
                                        // Keep the title readable when the show started left of the visible area.
                                        .padding(start = 10.dp + (scrolled - x(st)).coerceIn(0.dp, (x(e) - x(st) - 110.dp).coerceAtLeast(0.dp)), end = 10.dp, top = 4.dp, bottom = 4.dp),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(p.title, fontSize = if (c) 13.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (live) FontWeight.SemiBold else FontWeight.Medium, color = if (past) ArenaColors.Muted else ArenaColors.Text)
                                    if (x(e) - x(st) > 90.dp) Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (live) { Box(Modifier.size(6.dp).clip(CircleShape).background(ArenaColors.Accent)); Spacer(Modifier.width(5.dp)) }
                                        Text(Time.range(p.start, p.stop), color = ArenaColors.Muted, fontSize = if (c) 11.sp else 13.sp, maxLines = 1)
                                    }
                                }
                            }
                            // "Now" line
                            Box(Modifier.offset(x = x(now)).width(2.dp).fillMaxHeight().background(Color(0x99FFFFFF)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ScheduleList(channelId: String?, schedule: List<ProgramEntity>, now: Long,
    marks: Map<Long, String> = emptyMap(), onPick: ((ProgramEntity) -> Unit)? = null) {
        val listState = rememberLazyListState()
        val liveIndex = schedule.indexOfFirst { it.start <= now && it.stop > now }
        LaunchedEffect(channelId, schedule.size) { if (liveIndex > 1) listState.scrollToItem(liveIndex - 1) }
        LazyColumn(state = listState) {
            items(schedule, key = { it.rowId }) { p ->
                val live = p.start <= now && p.stop > now
                val past = p.stop <= now
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (live) ArenaColors.SurfaceHi else Color.Transparent)
                        .then(if (live) Modifier.drawBehind { drawRect(Color.White, size = Size(3.dp.toPx(), size.height)) } else Modifier)
                        .then(if (onPick != null && !past) Modifier.clickable { onPick(p) } else Modifier)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (past) Color(0x44FFFFFF) else Color(0xAAFFFFFF)))
                    Spacer(Modifier.width(12.dp))
                    Text(p.title, Modifier.weight(1f), color = if (past) ArenaColors.Muted else ArenaColors.Text,
                        fontWeight = if (live) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    marks[p.start]?.let { m -> Text(m, color = if (m.startsWith("●")) ArenaColors.Live else ArenaColors.Accent, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp)) }
                    if (live) Text("LIVE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 8.dp, vertical = 3.dp))
                    else Text(Time.clock(p.start), color = ArenaColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
}

/** Hold-OK menu on a channel, and the "Channel hidden · Undo" note. Shown by every Live TV layout. */
@Composable
internal fun ChannelMenus(vm: LiveTvViewModel) {
    val menu by vm.menuFor.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val hidden by vm.justHidden.collectAsStateWithLifecycle()
    menu?.let { ch ->
        val fav = ch.id in favs
        androidx.compose.material3.AlertDialog(onDismissRequest = { vm.menuFor.value = null }, containerColor = ArenaColors.Surface,
            title = { Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                // Holding OK opens this menu while the button is still down: the remote keeps repeating OK and then
                // releases it, which used to "click" the first row (Add to favorites). Ignore that same hold here;
                // only a fresh press of OK chooses a row. Taps on a phone don't send key events, so they're unaffected.
                var freshPress by remember(ch.id) { mutableStateOf(false) }
                Column(Modifier.onPreviewKeyEvent { e ->
                    val ok = e.key == androidx.compose.ui.input.key.Key.DirectionCenter || e.key == androidx.compose.ui.input.key.Key.Enter ||
                        e.key == androidx.compose.ui.input.key.Key.NumPadEnter
                    if (!ok) return@onPreviewKeyEvent false
                    if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                        if (e.nativeKeyEvent.repeatCount == 0) { freshPress = true; false } else !freshPress
                    } else !freshPress
                }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MenuRow(if (fav) "★  Remove from favorites" else "☆  Add to favorites") { vm.toggleFavorite(ch.id); vm.menuFor.value = null }
                    MenuRow("🚫  Hide this channel") { vm.hideChannel(ch) }
                    MenuRow("🗂  Hide category \"${ch.groupTitle}\"") { vm.hideCategory(ch) }
                    Text("Hidden things come back in Settings → Playlists (Hidden channels · Choose categories).",
                        color = ArenaColors.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.menuFor.value = null }, modifier = Modifier.focusOutline()) { Text("Cancel") } })
    }
    hidden?.let { ch ->
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xF0202125)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${ch.name} hidden", fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
                Spacer(Modifier.width(10.dp))
                androidx.compose.material3.Button(onClick = { vm.undoHide() }, modifier = Modifier.focusOutline()) { Text("Undo") }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    Text(label, fontSize = 17.sp, modifier = Modifier.fillMaxWidth().focusOutline(RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
        .background(ArenaColors.SurfaceHi).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp))
}
