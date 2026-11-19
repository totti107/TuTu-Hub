package com.arena.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.arena.tv.app
import com.arena.tv.data.ChannelEntity
import com.arena.tv.data.NewEpisode
import com.arena.tv.data.VodEntity
import com.arena.tv.data.VodKind
import com.arena.tv.sports.Region
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.player.PlayerActivity
import com.arena.tv.ui.theme.ArenaColors
import kotlinx.coroutines.flow.first

/** A new episode today, with where you can watch it. */
private data class NewItem(val ep: NewEpisode, val channel: ChannelEntity?, val series: VodEntity?, val yours: Boolean, val live: ShowOnChannel? = null) {
    /** When it airs, in this device's time zone: from your TV guide when found there, else from the schedule. */
    val time: Long? get() = live?.program?.start ?: ep.airstamp
}

private data class NewToday(val yours: List<NewItem>, val library: List<NewItem>, val onChannels: List<NewItem>, val streaming: List<NewItem>, val other: List<NewItem>, val failed: Boolean)

private fun words(s: String): List<String> = com.arena.tv.matching.ChannelNames.clean(s).split(' ').filter { it.isNotBlank() }

/** The best playlist channel for a TV network name ("Bravo" → "US: Bravo East HD"). */
private fun channelFor(network: String, channels: List<Pair<ChannelEntity, List<String>>>, country: String?): ChannelEntity? {
    val want = words(network).ifEmpty { return null }
    fun contains(have: List<String>) = (0..have.size - want.size).any { i -> have.subList(i, i + want.size) == want }
    return channels.filter { (_, w) -> w.size >= want.size && contains(w) }.maxByOrNull { (ch, w) ->
        val n = ch.name.lowercase()
        var score = 0
        if (country == "US" && (n.startsWith("us") || " usa" in n)) score += 4
        if (country == "GB" && (n.startsWith("uk") || " uk" in n)) score += 4
        if (country == "CA" && (n.startsWith("ca") || " ca" in n)) score += 4
        if ("east" in w) score += 2
        if ("hd" in w || "fhd" in w) score += 1
        if ("west" in w || "+1" in n || "4k" in n) score -= 2
        score - (w.size - want.size) // fewer extra words is a closer match
    }?.first
}

private fun norm(s: String) = s.lowercase().replace(Regex("""^(4k|uhd|hd|fhd)\s*[:|-]\s*"""), "").replace(Regex("""\(\d{4}\)"""), "").replace(Regex("""[^a-z0-9]+"""), " ").trim()

/** Home → New today: episodes airing or released today, and where you can watch them. */
@Composable
fun NewTodayScreen(onOpenVod: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.app
    var notice by remember { mutableStateOf<String?>(null) }
    val data by produceState<NewToday?>(null) {
        val s = app.settings.current()
        val countries = s.regions.mapNotNull { when (it) { Region.US -> "US"; Region.UK -> "GB"; Region.CA -> "CA"; Region.AU -> "AU"; else -> null } }.ifEmpty { listOf("US") }
        val eps = runCatching { app.newEpisodes.today(countries) }.getOrDefault(emptyList())
        val channels = app.db.channels().safeOnce().map { it to words(it.name) }
        val watching = (app.db.vod().continueWatching().first().map { it.vodKey } + app.db.vod().favoriteKeys().first()).toSet()
        // Series library by tidy name ("4K: The Kardashians (2022)" → "the kardashians"); regular copies before 4K ones.
        val library = app.db.vod().allOf(VodKind.SERIES).sortedBy { if (Regex("""(?i)4k|uhd""").containsMatchIn(it.name)) 1 else 0 }
            .groupBy { norm(it.name) }
        val items = eps.take(400).map { e ->
            val series = library[norm(e.show)]?.firstOrNull()
            val ch = e.network?.let { channelFor(it, channels, e.country) }
            NewItem(e, ch, series, series != null && series.key in watching)
        }
            .toMutableList()
        // Find the actual showing in your TV guide (channel + time) for the shows that matter most.
        val now = System.currentTimeMillis()
        suspend fun findLive(i: NewItem): ShowOnChannel? {
            val words = norm(i.ep.show).split(' ').filter { it.length > 1 }.take(6).ifEmpty { return null }
            val match = words.joinToString(" ")
            val progs = runCatching { app.db.programs().search(match, now - 2 * 3600_000L, now + 30 * 3600_000L, 40) }.getOrDefault(emptyList())
                .filter { norm(it.title) == norm(i.ep.show) || norm(it.title).startsWith(norm(i.ep.show) + " ") }
                .filter { it.stop > now }
            if (progs.isEmpty()) return null
            val chans = app.db.channels().safeByEpgKeys(progs.map { it.epgKey }.distinct()).groupBy { it.epgKey }
            return progs.sortedBy { it.start }.firstNotNullOfOrNull { p ->
                val list = chans[p.epgKey] ?: return@firstNotNullOfOrNull null
                val ch = list.firstOrNull { c -> i.channel != null && c.id == i.channel.id } ?: list.first()
                ShowOnChannel(p, ch)
            }
        }
        for (k in items.indices) {
            val i = items[k]
            if (i.ep.streaming == null && (i.series != null || i.channel != null || k < 120)) findLive(i)?.let { items[k] = i.copy(live = it, channel = it.channel) }
        }
        val used = HashSet<Long>()
        fun List<NewItem>.claim() = filter { used.add(it.ep.showId) }
        val yours = items.filter { it.yours }.claim()
        val inLibrary = items.filter { it.series != null }.claim()
        val onChannels = items.filter { it.channel != null }.sortedBy { it.time ?: Long.MAX_VALUE }.claim()
        val streaming = items.filter { it.ep.streaming != null }.claim().take(60)
        val other = items.claim().take(60)
        value = NewToday(yours, inLibrary, onChannels, streaming, other, eps.isEmpty())
    }
    var sheet by remember { mutableStateOf<NewItem?>(null) }
    fun open(i: NewItem) { sheet = i }
    sheet?.let { i ->
        val e = i.ep
        val actions = buildList {
            i.live?.let { addAll(liveActions(context, it) { msg -> notice = msg }) }
            if (i.live == null && i.channel != null) add(ShowAction("▶  Watch ${i.channel.name}") { PlayerActivity.start(context, i.channel.id, listOf(i.channel.id), null) })
            i.series?.let { v -> add(ShowAction("📚  Open in your Series library") { onOpenVod(v.key) }) }
        }
        val note = when {
            actions.isEmpty() && e.streaming != null -> "This one is on ${e.streaming}. It isn't in your playlist yet — providers usually add new episodes to Series within a day or two."
            actions.isEmpty() -> "Not on your channels or in your library yet. Providers usually add new episodes to Series within a day or two."
            i.series != null && i.live == null -> "The newest episode can take a day or two to appear in your library."
            else -> null
        }
        ShowActions(e.show, listOfNotNull(e.code.ifBlank { null }, e.title).joinToString(" · ") + (i.time?.let { " · ${dayTime(it)}" } ?: ""), note, actions) { sheet = null }
    }

    val d = data
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp)) {
                Text("New today 🆕", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("New episodes airing or released today, and where to watch them", color = ArenaColors.Muted, fontSize = 14.sp)
                notice?.let { Text(it, color = ArenaColors.Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        if (d == null) { item { CircularProgressIndicator(Modifier.padding(40.dp)) }; return@LazyColumn }
        if (d.failed) item { Text("Couldn't load today's schedule. Check the internet connection and open this again.", color = ArenaColors.Muted, modifier = Modifier.padding(24.dp)) }
        item { NewRail("From shows you watch", null, d.yours, ::open) }
        item { NewRail("In your Series library", "may take a day to appear", d.library, ::open) }
        item { NewRail("On your channels", "airing today", d.onChannels, ::open) }
        item { NewRail("Streaming today", "Netflix, Disney+, Apple TV+…", d.streaming, ::open) }
        item { NewRail("More new episodes", null, d.other, ::open) }
        item { Text("Schedule from TVmaze.com", color = ArenaColors.Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 24.dp, top = 20.dp)) }
    }
}

@Composable
private fun NewRail(title: String, hint: String?, items: List<NewItem>, onClick: (NewItem) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("  ${hint?.let { "$it · " } ?: ""}${items.size}", color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.ep.showId }) { i -> NewCard(i) { onClick(i) } }
        }
    }
}

@Composable
private fun NewCard(i: NewItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val e = i.ep
    Row(
        Modifier.width(330.dp).height(132.dp).onFocusChanged { focused = it.isFocused }.clip(RoundedCornerShape(18.dp))
            .background(if (focused) Color.White else ArenaColors.Surface)
            .border(1.dp, if (focused) Color.White else ArenaColors.Outline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
    ) {
        Box(Modifier.width(88.dp).fillMaxHeight().background(ArenaColors.SurfaceHi)) {
            AsyncImage(e.image, e.show, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        val fg = if (focused) Color.Black else ArenaColors.Text
        val muted = if (focused) Color(0xFF444444) else ArenaColors.Muted
        Column(Modifier.weight(1f).padding(12.dp)) {
            Text(e.show, color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(e.code.ifBlank { null }, e.title).joinToString(" · "), color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.premiere) Text("★ Season premiere", color = if (focused) Color.Black else ArenaColors.Gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            val now = System.currentTimeMillis()
            val liveNow = i.live?.let { it.program.start <= now && it.program.stop > now } == true
            val where = when {
                i.live != null -> (if (liveNow) "● LIVE now · " else "📺 ${dayTime(i.live.program.start)} · ") + i.live.channel.name
                i.channel != null -> "📺 ${i.channel.name}" + (e.airstamp?.let { " · ${dayTime(it)}" } ?: "")
                e.streaming != null -> "▶ ${e.streaming}" + (e.airstamp?.let { " · out ${dayTime(it)}" } ?: "")
                e.network != null -> "📺 ${e.network}" + (e.airstamp?.let { " · ${dayTime(it)}" } ?: "")
                else -> ""
            }
            if (i.series != null) Text("✓ In your library", color = if (focused) Color.Black else ArenaColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (where.isNotEmpty()) Text(where, color = if (focused) Color.Black else if (liveNow) ArenaColors.Live else ArenaColors.Blue,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "9:00 p.m." today, "Tomorrow 1:00 a.m.", in this device's time zone. */
private fun dayTime(t: Long): String {
    val c = java.util.Calendar.getInstance(); val today = c.get(java.util.Calendar.DAY_OF_YEAR)
    c.timeInMillis = t
    return when (c.get(java.util.Calendar.DAY_OF_YEAR) - today) { 0 -> Time.clock(t); 1 -> "Tomorrow ${Time.clock(t)}"; -1 -> "Yesterday ${Time.clock(t)}"; else -> Time.clock(t) }
}
