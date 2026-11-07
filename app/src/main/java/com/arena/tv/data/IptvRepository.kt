package com.arena.tv.data

import com.arena.tv.matching.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream

enum class StepStatus { WAITING, RUNNING, DONE, SKIPPED, FAILED }

/** One line of the loading checklist: Sports center, Live TV, Movies, Series. */
data class LoadStep(val label: String, val status: StepStatus = StepStatus.WAITING, val detail: String? = null)

data class RefreshState(
    val running: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** Bumped after every step so caches (channel matches, lists) refresh. */
    val version: Int = 0,
    val steps: List<LoadStep> = emptyList(),
)

class IptvRepository(
    private val http: OkHttpClient,
    private val db: ArenaDb,
    private val settings: SettingsStore,
    private val vod: VodRepository,
    private val stalker: StalkerClient,
    private val cacheDir: java.io.File,
) {
    private val _state = MutableStateFlow(RefreshState())
    val state: StateFlow<RefreshState> = _state.asStateFlow()
    private val lock = Mutex()
    /** A problem that didn't stop the refresh (shown after it finishes). */
    private var softError: String? = null
    private val xtream = XtreamClient(http)

    private fun progress(msg: String) = _state.update { it.copy(running = true, message = msg, error = null) }

    /** Refresh on app start when the data is old. */
    suspend fun refreshIfStale() {
        val s = settings.current()
        if (!s.hasSource) return
        val now = System.currentTimeMillis()
        // 3.3.0 stores channels in the provider's category order: reload once after updating.
        if (settings.dataVersion() < 3) { settings.setDataVersion(3); refreshAll(); return }
        when {
            s.playlists.any { it.enabled && it.valid && now - it.lastRefresh > 24 * 3600_000L } -> refreshAll()
            s.playlists.any { it.enabled && it.valid && now - it.lastEpg > 8 * 3600_000L } -> refreshEpg()
        }
    }

    private fun step(i: Int, status: StepStatus, detail: String? = null) = _state.update { st ->
        st.copy(steps = st.steps.mapIndexed { n, x -> if (n == i) x.copy(status = status, detail = detail ?: x.detail) else x })
    }
    private fun bump() = _state.update { it.copy(version = it.version + 1) }

    /**
     * Reload in four steps, most useful first, each usable as soon as it's done:
     * 1. Sports center: channels + TV guide for sports categories (game matching works from here),
     * 2. Live TV: guide for the other visible categories,
     * 3. Movies, 4. Series (optional per playlist; they're the slowest part on big providers).
     */
    suspend fun refreshAll(onlyId: String? = null) = run {
        val s = settings.current()
        val keep = s.playlists.map { it.id }
        db.channels().clearOthers(keep); db.vod().clearOthers(keep); db.vod().clearOtherCategories(keep)
        val targets = s.playlists.filter { it.enabled && it.valid && (onlyId == null || it.id == onlyId) }
        if (targets.isEmpty()) throw IOException("Add a playlist in Settings first")
        _state.update { it.copy(steps = listOf(LoadStep("Sports center"), LoadStep("Live TV"), LoadStep("Movies"), LoadStep("Series"))) }

        // ---- 1. Sports center: channels, then the sports part of the guide ----
        step(0, StepStatus.RUNNING, "Channels")
        val errors = ArrayList<String>()
        val m3uVod = HashMap<String, Pair<List<ParsedChannel>, List<ParsedChannel>>>()
        for ((i, p) in targets.withIndex()) {
            try { importChannels(p, s.playlists.indexOf(p))?.let { m3uVod[p.id] = it } }
            catch (e: Exception) {
                errors += "${p.name}: ${e.message ?: e.javaClass.simpleName}"
                settings.updatePlaylist(p.id) { it.copy(error = e.message ?: "Failed to load") }
            }
            if (i < targets.size - 1) progress("Next playlist…")
        }
        if (errors.size == targets.size) { step(0, StepStatus.FAILED); throw IOException(errors.joinToString("\n")) }
        applyVisibility()
        val guide = GuideImport(settings.current())
        try {
            step(0, StepStatus.RUNNING, "Sports guide")
            guide.run(sportsPhase = true)
            step(0, StepStatus.DONE); bump()

            // ---- 2. Live TV: the rest of the visible channels ----
            step(1, StepStatus.RUNNING)
            guide.run(sportsPhase = false)
            step(1, StepStatus.DONE, guide.summary()); bump()
        } catch (e: Exception) {
            softError = "TV guide: ${e.message ?: e.javaClass.simpleName}"
            step(1, StepStatus.FAILED)
        } finally { guide.cleanup() }

        // ---- 3. Movies, 4. Series ----
        for ((stepIndex, kind) in listOf(2 to VodKind.MOVIE, 3 to VodKind.SERIES)) {
            val wanted = targets.filter { p -> p.type != SourceType.MAC && (if (kind == VodKind.MOVIE) p.loadMovies else p.loadSeries) }
            targets.filter { it !in wanted }.forEach { vod.clearKind(it.id, kind) }
            if (wanted.isEmpty()) { step(stepIndex, StepStatus.SKIPPED, "Off"); continue }
            step(stepIndex, StepStatus.RUNNING)
            var total = 0
            for (p in wanted) {
                try {
                    val n = if (p.type == SourceType.XTREAM) vod.importXtream(p, kind) { progress("${p.name}: ${it.replaceFirstChar { c -> c.lowercase() }}") }
                    else {
                        // M3U lists movies and episodes together; both are saved in the Movies step.
                        if (kind == VodKind.SERIES) continue
                        val (mv, ep) = m3uVod[p.id] ?: continue
                        progress("${p.name}: saving movies & series…")
                        vod.importM3u(p.id, if (p.loadMovies) mv else emptyList(), if (p.loadSeries) ep else emptyList()).first
                    }
                    total += n
                    settings.updatePlaylist(p.id) { if (kind == VodKind.MOVIE) it.copy(movieCount = n) else it.copy(seriesCount = n) }
                } catch (e: Exception) {
                    softError = listOfNotNull(softError, "${p.name} ${if (kind == VodKind.MOVIE) "movies" else "series"}: ${e.message ?: "failed"}").joinToString("\n")
                }
            }
            applyVisibility()
            step(stepIndex, StepStatus.DONE, "%,d".format(total)); bump()
        }
        if (errors.isNotEmpty()) softError = (listOfNotNull(softError) + errors).joinToString("\n")
    }

    /** Guide only (both guide steps). */
    suspend fun refreshEpg() = run {
        _state.update { it.copy(steps = listOf(LoadStep("Sports center"), LoadStep("Live TV"))) }
        val guide = GuideImport(settings.current())
        try {
            step(0, StepStatus.RUNNING, "Sports guide"); guide.run(sportsPhase = true); step(0, StepStatus.DONE); bump()
            step(1, StepStatus.RUNNING); guide.run(sportsPhase = false); step(1, StepStatus.DONE, guide.summary())
        } finally { guide.cleanup() }
    }

    /** Re-tidies every channel name after the Title Optimizer settings change (no download needed). */
    suspend fun reapplyTitles() = withContext(Dispatchers.IO) {
        val cfg = TitleOptimizer.config
        db.runInTransaction {
            db.channels().fillRawNamesBlocking()
            db.channels().rawNamesBlocking().forEach { r -> db.channels().renameBlocking(r.id, if (cfg.enabled) TitleOptimizer.clean(r.rawName, cfg) else r.rawName) }
        }
        _state.update { it.copy(version = it.version + 1) }
    }

    /** Only the sports channels' guide: quick, for the Sports center's "Refresh sports guide" button. */
    suspend fun refreshSportsGuide() = run {
        _state.update { it.copy(steps = listOf(LoadStep("Sports guide"))) }
        val guide = GuideImport(settings.current())
        try {
            step(0, StepStatus.RUNNING, "Sports channels"); guide.run(sportsPhase = true)
            step(0, StepStatus.DONE, guide.summary()); bump()
        } catch (e: Exception) { step(0, StepStatus.FAILED); throw e }
        finally { guide.cleanup() }
    }

    /** Re-apply which playlists / categories are shown (cheap, no download). */
    suspend fun applyVisibility() = withContext(Dispatchers.IO) {
        val s = settings.current()
        db.channels().applyVisibility(s.activeIds, s.hiddenGroups.toList(), s.hideAdult)
        db.vod().applyVisibility(s.activeIds)
        db.vod().applyCategoryVisibility(s.activeIds)
        _state.update { it.copy(version = it.version + 1) }
    }

    /** Remove a playlist and everything it imported. */
    suspend fun removePlaylist(id: String) = withContext(Dispatchers.IO) {
        settings.updatePlaylists { l -> l.filter { it.id != id } }
        db.channels().clearPlaylist(id)
        val keep = settings.current().playlists.map { it.id }
        db.vod().clearOthers(keep); db.vod().clearOtherCategories(keep)
        if (settings.current().activePlaylist == id) settings.setActivePlaylist("")
        applyVisibility()
    }

    private suspend fun run(block: suspend () -> Unit) {
        if (lock.isLocked) return
        lock.withLock {
            try {
                softError = null
                withContext(Dispatchers.IO) { block() }
                _state.update { it.copy(running = false, message = null, error = softError, version = it.version + 1) }
            } catch (e: Exception) {
                _state.update { it.copy(running = false, message = null, error = e.message ?: e.javaClass.simpleName, version = it.version + 1) }
            }
        }
    }

    private fun open(url: String, ua: String?): Pair<InputStream, () -> Unit> {
        val b = Request.Builder().url(url)
        b.header("User-Agent", ua?.ifBlank { null } ?: "TuTuTV/1.0 (Android)")
        val resp = http.newCall(b.build()).execute()
        if (!resp.isSuccessful) { resp.close(); throw IOException("HTTP ${resp.code} from ${url.substringBefore('?').take(60)}") }
        val raw = BufferedInputStream(resp.body!!.byteStream(), 64 * 1024)
        raw.mark(4)
        val b0 = raw.read(); val b1 = raw.read()
        raw.reset()
        val stream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw
        return stream to { resp.close() }
    }

    /** Channels of one playlist. @return the M3U's movies/episodes (saved later, in the Movies step). */
    private suspend fun importChannels(p: Playlist, index: Int): Pair<List<ParsedChannel>, List<ParsedChannel>>? {
        val label = p.name.ifBlank { "playlist" }
        progress("$label: downloading channel list…")
        val ua = p.ua
        var m3uVod: Pair<List<ParsedChannel>, List<ParsedChannel>>? = null
        val (channels, epgUrls, account) = when (p.type) {
            SourceType.XTREAM -> {
                val (list, acc) = xtream.load(p.server, p.user, p.pass, p.format, ua)
                Triple(list, emptyList(), acc)
            }
            SourceType.MAC -> {
                val (list, acc) = stalker.load(p)
                Triple(list, emptyList(), acc)
            }
            else -> {
                val (stream, close) = open(p.m3uUrl, ua)
                try {
                    val pl = M3uParser.parse(stream.bufferedReader(), liveOnly = false)
                    m3uVod = pl.movies to pl.episodes
                    Triple(pl.channels, pl.epgUrls, "")
                } finally { close() }
            }
        }
        if (channels.isEmpty() && m3uVod?.first.isNullOrEmpty()) throw IOException("The playlist has no channels")
        progress("$label: saving ${"%,d".format(channels.size)} channels…")
        val ids = M3uParser.ids(channels)
        val base = index * 1_000_000
        val entities = channels.mapIndexed { i, c ->
            ChannelEntity("${p.id}:${ids[i]}", p.id, true, base + i, TitleOptimizer.apply(c.name), c.logo, c.group, c.url, c.tvgId, c.tvgName, null, c.userAgent ?: ua, c.referrer, c.adult, rawName = c.name)
        }
        // Keep the guide links of channels that already had one, so Live TV keeps its guide while the new one loads.
        val oldKeys = db.channels().epgKeysOf(p.id).associate { it.id to it.epgKey }
        db.channels().replacePlaylist(p.id, entities.map { e -> oldKeys[e.id]?.let { e.copy(epgKey = it) } ?: e })
        settings.updatePlaylist(p.id) {
            it.copy(lastRefresh = System.currentTimeMillis(), advertisedEpg = epgUrls.joinToString(","), account = account.ifEmpty { it.account },
                channelCount = channels.size, error = null)
        }
        return m3uVod
    }

    /**
     * Imports the TV guides in two passes over the same downloaded file(s):
     * sports categories first (fast, Sports center usable right away), then the other visible channels.
     * The previous guide stays in place until each pass has replaced it.
     */
    private inner class GuideImport(private val s: AppSettings) {
        private val now = System.currentTimeMillis()
        private val keepFrom = now - 3 * 3600_000L
        private val keepTo = now + 36 * 3600_000L
        private val files = HashMap<String, java.io.File>()            // url -> downloaded copy
        private val insertedKeys = HashSet<String>()                    // guide channels stored in this import
        private var total = 0
        private var linked = 0
        private var lastError: String? = null
        private val cutoff = db.programs().maxRowId()                   // rows older than this belong to the previous guide

        fun summary() = "%,d channels with a guide".format(linked)

        suspend fun run(sportsPhase: Boolean) {
            ArenaDb.beginGuideImport(db)
            val newKeys = HashSet<String>()
            try {
                for (p in s.playlists.filter { it.enabled && it.valid }) {
                    if (!sportsPhase && p.guideSportsOnly) continue
                    val candidates = db.channels().epgCandidates(p.id).filter { c -> (com.arena.tv.matching.SportyGroups.isSporty(c.groupTitle) || com.arena.tv.matching.SportyGroups.isSporty(c.name)) == sportsPhase }
                    if (candidates.isEmpty()) continue
                    val mapping = if (p.type == SourceType.MAC && p.epgUrl.isBlank()) portalGuide(p, candidates, sportsPhase) else xmltvGuide(p, candidates, sportsPhase)
                    db.channels().addEpgKeys(mapping)
                    newKeys += mapping.values
                    linked += mapping.size
                    if (!sportsPhase) settings.updatePlaylist(p.id) { it.copy(lastEpg = System.currentTimeMillis()) }
                }
                // Drop the previous guide for the channels just refreshed (all of it after the last pass).
                if (sportsPhase) newKeys.toList().chunked(500).forEach { db.programs().deleteUpToFor(cutoff, it) }
                else db.programs().deleteUpTo(cutoff)
            } finally {
                progress(if (sportsPhase) "Indexing the sports guide…" else "Indexing the TV guide…")
                ArenaDb.endGuideImport(db)
            }
            if (total == 0 && lastError != null && !sportsPhase) throw IOException(lastError)
        }

        private suspend fun portalGuide(p: Playlist, candidates: List<EpgCandidate>, sportsPhase: Boolean): Map<String, String> {
            progress("${p.name}: downloading ${if (sportsPhase) "sports " else ""}TV guide…")
            return try {
                val guide = portalCache.getOrPut(p.id) { stalker.epg(p) }
                val mapping = HashMap<String, String>()
                for (c in candidates) {
                    val chId = db.channels().byId(c.id)?.url?.removePrefix(StalkerClient.SCHEME)?.substringBefore('|') ?: continue
                    val list = guide[chId].orEmpty().filter { it.stop > keepFrom && it.start < keepTo }
                    if (list.isEmpty()) continue
                    val key = "stk:${p.id}:$chId"
                    mapping[c.id] = key
                    if (insertedKeys.add(key)) list.chunked(2000).forEach { db.programs().insertAllBlocking(it); total += it.size }
                }
                mapping
            } catch (e: Exception) { lastError = "${p.name} TV guide: ${e.message}"; emptyMap() }
        }
        private val portalCache = HashMap<String, Map<String, List<ProgramEntity>>>()

        private fun download(url: String, ua: String?): java.io.File = files.getOrPut(url) {
            val f = java.io.File(cacheDir, "guide-${url.hashCode().toUInt()}.xml")
            val (stream, close) = open(url, ua)
            try { f.outputStream().buffered().use { out -> stream.copyTo(out, 64 * 1024) } } finally { close() }
            f
        }

        private suspend fun xmltvGuide(p: Playlist, candidates: List<EpgCandidate>, sportsPhase: Boolean): Map<String, String> {
            val mapping = HashMap<String, String>()
            val urls = p.effectiveEpgUrls
            for ((n, url) in urls.withIndex()) {
                val part = if (urls.size > 1) " ${n + 1}/${urls.size}" else ""
                val epgNames = HashMap<String, String>()
                val epgIds = HashSet<String>()
                var wanted: Set<String> = emptySet()
                val batch = ArrayList<ProgramEntity>(2000)
                fun flush() {
                    if (batch.isEmpty()) return
                    val copy = ArrayList(batch); batch.clear()
                    db.programs().insertAllBlocking(copy)
                    total += copy.size
                    if (total % 20000 < copy.size) progress("Reading ${if (sportsPhase) "sports " else ""}TV guide… ${total / 1000}k shows")
                }
                try {
                    if (!files.containsKey(url)) progress("${p.name}: downloading TV guide$part…")
                    val file = download(url, p.ua)
                    progress("${p.name}: reading ${if (sportsPhase) "sports " else ""}TV guide$part…")
                    file.inputStream().buffered(64 * 1024).use { stream ->
                        XmltvParser(
                            onChannel = { id, names ->
                                val key = id.lowercase()
                                epgIds += key
                                names.forEach { epgNames.putIfAbsent(normKey(it), key) }
                                epgNames.putIfAbsent(normKey(id), key)
                            },
                            onFirstProgram = {
                                for (c in candidates) {
                                    if (mapping.containsKey(c.id)) continue
                                    val key = c.tvgId?.lowercase()?.takeIf { it in epgIds }
                                        ?: c.tvgName?.let { epgNames[normKey(it)] }
                                        ?: epgNames[normKey(c.name)]
                                    if (key != null) mapping[c.id] = key
                                }
                                wanted = mapping.values.toHashSet().apply { removeAll(insertedKeys) }
                            },
                            onProgram = { pr ->
                                if (pr.epgKey in wanted && pr.stop > keepFrom && pr.start < keepTo) {
                                    batch += pr
                                    if (batch.size >= 2000) flush()
                                }
                            },
                        ).parse(stream)
                    }
                    flush()
                    insertedKeys += wanted
                } catch (e: Exception) {
                    lastError = "${p.name} TV guide: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            return mapping
        }

        fun cleanup() { files.values.forEach { it.delete() } }
    }

    /** Guide names and playlist names differ in decoration ("BBC One HD" vs "BBC One"). */
    private fun normKey(s: String) = com.arena.tv.matching.ChannelNames.clean(s).ifEmpty { Text.normalize(s) }
}
