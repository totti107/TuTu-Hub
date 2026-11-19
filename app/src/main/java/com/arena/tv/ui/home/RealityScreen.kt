package com.arena.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.tv.app
import com.arena.tv.data.CatchupItem
import com.arena.tv.data.ChannelEntity
import com.arena.tv.ui.components.Logo
import com.arena.tv.ui.components.Time
import com.arena.tv.ui.player.PlayerActivity
import com.arena.tv.ui.player.VodPlayerActivity
import com.arena.tv.ui.theme.ArenaColors
import com.arena.tv.ui.theme.focusOutline

/** The shows and channels the Reality TV hub looks for in the TV guide. */
object Reality {
    /** Searched in the TV guide (full-text). */
    val shows = listOf(
        "\"love island\"", "kardashian*", "kardashians", "housewives", "bachelor*", "bachelorette", "\"below deck\"", "vanderpump",
        "\"selling sunset\"", "\"selling the oc\"", "\"90 day\"", "\"married at first sight\"", "\"too hot to handle\"", "\"dance moms\"",
        "\"jersey shore\"", "\"teen mom\"", "\"say yes to the dress\"", "\"love is blind\"", "\"summer house\"", "\"southern charm\"",
        "\"the valley\"", "\"made in chelsea\"", "\"only way is essex\"", "towie", "\"geordie shore\"", "\"ex on the beach\"",
        "\"temptation island\"", "\"love after lockup\"", "\"basketball wives\"", "\"love and hip hop\"", "\"little women\"", "\"sister wives\"",
        "\"real world\"", "\"project runway\"", "\"top model\"", "botched", "\"winter house\"", "\"family karma\"", "\"real girlfriends\"",
        "\"bling empire\"", "\"perfect match\"", "\"the circle\"", "\"big brother\"", "\"love triangle\"", "\"marriage boot camp\"",
    )
    val match: String = shows.joinToString(" OR ")

    /** Plain words for finding these shows in the Movies / Series library by name. */
    val titles = listOf(
        "love island", "kardashian", "housewives", "bachelor", "below deck", "vanderpump", "selling sunset", "selling the oc", "90 day",
        "married at first sight", "too hot to handle", "dance moms", "jersey shore", "teen mom", "say yes to the dress", "love is blind",
        "summer house", "southern charm", "made in chelsea", "only way is essex", "geordie shore", "ex on the beach", "temptation island",
        "love after lockup", "basketball wives", "hip hop", "little women", "sister wives", "project runway", "top model", "botched",
        "winter house", "bling empire", "perfect match", "the circle", "big brother", "real world", "family karma",
        "marriage boot camp", "the kardashians", "love triangle", "real girlfriends", "dubai bling", "buying beverly hills", "the hills: new beginnings",
        "laguna beach", "flavor of love", "rock of love", "my super sweet 16", "catfish", "love & marriage",
    )

    /** Reality shows in the library: matching names, plus whole categories the provider calls "Reality". */
    suspend fun library(app: com.arena.tv.AppContainer, kind: Int): List<com.arena.tv.data.VodEntity> {
        val vod = app.db.vod()
        val byName = titles.flatMap { vod.search(kind, it) }
        val cats = vod.categoriesOnce(kind).filter { !it.adult && Regex("""(?i)reality|real ?tv|lifestyle|dating""").containsMatchIn(it.name) }.map { it.id }
        val byCat = if (cats.isEmpty()) emptyList() else vod.inCategories(kind, cats)
        return (byName + byCat).distinctBy { it.key }.distinctBy { it.name.lowercase().replace(Regex("""\W+"""), "") }
    }

    /** Channel names that are mostly reality / lifestyle. */
    private val channelWords = Regex("""(?i)(^|[^a-z])(bravo|e!|e! entertainment|tlc|lifetime|oxygen|we ?tv|mtv|own|itv ?2|itvbe|hayu|slice|discovery life|e entertainment)([^a-z]|$)""")
    fun isRealityChannel(name: String) = channelWords.containsMatchIn(name)
}

private data class RealityData(val nonstop: List<ShowOnChannel>, val now: List<ShowOnChannel>, val later: List<ShowOnChannel>, val earlier: List<ShowOnChannel>, val channels: List<ChannelEntity>,
    val series: List<com.arena.tv.data.VodEntity> = emptyList(), val movies: List<com.arena.tv.data.VodEntity> = emptyList())

/** Home → Reality TV: Love Island, the Kardashians, Housewives & co. — on now, later, and earlier (catch-up). */
@Composable
fun RealityScreen(onOpenVod: (String) -> Unit = {}) {
    val context = LocalContext.current
    val app = context.app
    val data by produceState<RealityData?>(null) {
        val now = System.currentTimeMillis()
        val progs = runCatching { app.db.programs().search(Reality.match, now - 24 * 3600_000L, now + 24 * 3600_000L, 3000) }.getOrDefault(emptyList())
        val chans = progs.map { it.epgKey }.distinct().chunked(500).flatMap { app.db.channels().safeByEpgKeys(it) }.groupBy { it.epgKey }
        // One card per showing: the same episode on "Bravo", "Bravo HD" and "Bravo East" shows once (prefer a catch-up channel).
        val shows = progs.mapNotNull { p -> chans[p.epgKey]?.let { list -> ShowOnChannel(p, list.firstOrNull { it.catchupDays > 0 } ?: list.first()) } }
            .groupBy { it.program.title.lowercase() + "|" + it.program.start }.values.map { g -> g.firstOrNull { it.channel.catchupDays > 0 } ?: g.first() }
        val channels = app.db.channels().safeOnce().filter { Reality.isRealityChannel(it.name) }.distinctBy { it.name.lowercase() }.take(60)
        // "24/7" channels loop one show all day: they get their own row instead of crowding "On now".
        val nonstopRe = Regex("""24\s*/\s*7|24-7""")
        fun nonstop(s: ShowOnChannel) = nonstopRe.containsMatchIn(s.channel.name) || nonstopRe.containsMatchIn(s.program.title) || nonstopRe.containsMatchIn(s.channel.groupTitle)
        val (loop, regular) = shows.partition(::nonstop)
        value = RealityData(
            nonstop = loop.filter { it.program.start <= now && it.program.stop > now }.distinctBy { it.channel.id }.sortedBy { it.channel.name.lowercase() },
            now = regular.filter { it.program.start <= now && it.program.stop > now }.sortedBy { it.program.start },
            later = regular.filter { it.program.start > now }.sortedBy { it.program.start }.take(60),
            earlier = regular.filter { it.program.stop <= now && it.channel.catchupDays > 0 }.sortedByDescending { it.program.start }.take(60),
            channels = channels,
            series = runCatching { Reality.library(app, com.arena.tv.data.VodKind.SERIES) }.getOrDefault(emptyList()),
        )
    }
    fun watchLive(ch: ChannelEntity) = PlayerActivity.start(context, ch.id, listOf(ch.id), null)
    var notice by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var sheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ShowOnChannel?>(null) }
    sheet?.let { s -> ShowActions(s.program.title, "${s.channel.name} · ${airedOrLater(s.program.start)}", null, liveActions(context, s) { notice = it }) { sheet = null } }
    // Search: the TV guide (last day → next two days) and the Series library.
    var query by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val results by produceState<Pair<List<ShowOnChannel>, List<com.arena.tv.data.VodEntity>>?>(null, query) {
        val q = query.trim()
        if (q.length < 2) { value = null; return@produceState }
        kotlinx.coroutines.delay(400)
        val now = System.currentTimeMillis()
        val words = q.lowercase().replace(Regex("""[^a-z0-9 ]+"""), " ").split(' ').filter { it.isNotBlank() }
        val match = words.joinToString(" ") { "$it*" }
        val progs = if (words.isEmpty()) emptyList() else runCatching { app.db.programs().search(match, now - 24 * 3600_000L, now + 48 * 3600_000L, 300) }.getOrDefault(emptyList())
        val chans = progs.map { it.epgKey }.distinct().chunked(500).flatMap { app.db.channels().safeByEpgKeys(it) }.groupBy { it.epgKey }
        val guide = progs.mapNotNull { p -> chans[p.epgKey]?.let { l -> ShowOnChannel(p, l.firstOrNull { it.catchupDays > 0 } ?: l.first()) } }
            .filter { it.program.stop > now || it.channel.catchupDays > 0 }
            .groupBy { it.program.title.lowercase() + "|" + it.program.start }.values.map { it.first() }
            .sortedWith(compareBy({ if (it.program.stop <= now) 1 else 0 }, { it.program.start }))
        value = guide to runCatching { app.db.vod().search(com.arena.tv.data.VodKind.SERIES, q) }.getOrDefault(emptyList())
    }

    val d = data
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp)) {
                Text("Reality TV 💅", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("Love Island, the Kardashians, Real Housewives, Below Deck and more — from your TV guide", color = ArenaColors.Muted, fontSize = 14.sp)
                com.arena.tv.ui.components.TvTextField(query, { query = it }, Modifier.padding(top = 12.dp).widthIn(max = 560.dp).fillMaxWidth(),
                    placeholder = { Text("Search a show — guide and Series library") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                notice?.let { Text(it, color = ArenaColors.Gold, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
            }
        }
        results?.let { (guide, series) ->
            val now = System.currentTimeMillis()
            if (guide.isEmpty() && series.isEmpty()) item { Text("Nothing found for \"${query.trim()}\".", color = ArenaColors.Muted, modifier = Modifier.padding(24.dp)) }
            item { ShowRail("In the TV guide", "${guide.size}", guide, { s ->
                when { s.program.start <= now && s.program.stop > now -> "● LIVE · until ${Time.clock(s.program.stop)}"
                    s.program.stop <= now -> "⏪ " + airedLabel(s.program.start); else -> airedOrLater(s.program.start) } }) { s ->
                if (s.program.stop <= now) VodPlayerActivity.startCatchup(context, s.channel.id, CatchupItem(s.program.title, s.program.start, s.program.stop, s.program.description))
                else sheet = s
            } }
            item { PosterRail("In your Series library", "${series.size}", series, onOpenVod) }
            return@LazyColumn
        }
        if (d == null) { item { CircularProgressIndicator(Modifier.padding(40.dp)) }; return@LazyColumn }
        if (d.nonstop.isEmpty() && d.now.isEmpty() && d.later.isEmpty() && d.earlier.isEmpty() && d.series.isEmpty()) item {
            Text("Nothing from these shows in your TV guide right now. Check the channels below, or reload the TV guide in Live TV.",
                color = ArenaColors.Muted, modifier = Modifier.padding(24.dp))
        }
        item { ShowRail("On now", null, d.now, { "● LIVE · until ${Time.clock(it.program.stop)}" }) { watchLive(it.channel) } }
        item { ShowRail("Watch again", "aired earlier · catch-up", d.earlier, { "⏪ " + airedLabel(it.program.start) }) { s ->
            VodPlayerActivity.startCatchup(context, s.channel.id, CatchupItem(s.program.title, s.program.start, s.program.stop, s.program.description))
        } }
        item { ShowRail("24/7 channels", "the same show, all day", d.nonstop, { "● Non-stop" }) { watchLive(it.channel) } }
        item { ShowRail("Coming up", "today and tomorrow · OK to watch or set a reminder", d.later, { airedOrLater(it.program.start) }) { sheet = it } }
        item { PosterRail("Series to binge", "${d.series.size} in your library", d.series, onOpenVod) }
        if (d.channels.isNotEmpty()) item {
            Column(Modifier.padding(top = 18.dp)) {
                Text("Reality channels", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(d.channels, key = { it.id }) { ch ->
                        Row(
                            Modifier.width(220.dp).clip(RoundedCornerShape(14.dp)).background(ArenaColors.Surface)
                                .focusOutline(RoundedCornerShape(14.dp)).clickable { watchLive(ch) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Logo(ch.logo, ch.name, 30.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(ch.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun airedOrLater(start: Long): String {
    val tomorrow = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1); set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0) }.timeInMillis
    return (if (start >= tomorrow) "Tomorrow " else "Today ") + Time.clock(start)
}

@Composable
private fun PosterRail(title: String, hint: String?, items: List<com.arena.tv.data.VodEntity>, onOpen: (String) -> Unit) {
    if (items.isEmpty()) return
    Column(Modifier.padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(start = 24.dp, bottom = 8.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            hint?.let { Text("  $it", color = ArenaColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 2.dp)) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.key }) { v -> com.arena.tv.ui.vod.PosterCard(v, null, false, Modifier.width(140.dp)) { onOpen(v.key) } }
        }
    }
}
