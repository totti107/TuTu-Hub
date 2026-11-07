package com.arena.tv.data

import com.arena.tv.matching.ChannelMatch
import com.arena.tv.matching.MatchChannel
import com.arena.tv.matching.MatchFeedback
import com.arena.tv.matching.MatchGame
import com.arena.tv.matching.MatchIndex
import com.arena.tv.matching.MatchProgram
import com.arena.tv.matching.MatchTeam
import com.arena.tv.matching.Matcher
import com.arena.tv.matching.Text
import com.arena.tv.sports.Game
import com.arena.tv.sports.Region
import com.arena.tv.sports.Team
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds which of the user's channels show a game.
 *
 * Instead of loading the whole TV guide (hundreds of thousands of shows on big playlists, minutes on a TV box),
 * each game asks the database for the few shows / channels that mention its teams, event or broadcasters
 * (full-text search), then scores only those. A game takes milliseconds; results are cached for a few minutes.
 */
class ChannelFinder(
    private val db: ArenaDb,
    private val repo: IptvRepository,
    private val settings: SettingsStore,
) {
    @Volatile var knownTeamNames: Set<String> = emptySet()

    private data class Cached(val at: Long, val version: Int, val feedbackKey: Int, val result: List<ChannelMatch>)
    private val cache = ConcurrentHashMap<String, Cached>()

    /** Remember every team on today's scoreboard; used to avoid "Real" (Madrid) matching "Real Betis". */
    fun rememberTeams(games: List<Game>) {
        knownTeamNames = games.flatMap { listOf(it.home, it.away) }
            .flatMap { listOf(it.displayName, it.shortName) }
            .map(Text::normalize).filter { it.contains(' ') }.toSet()
    }

    /** Last result for a game if we have one (used to show counts instantly). */
    fun cached(gameId: String): List<ChannelMatch>? = cache[gameId]?.result

    suspend fun find(game: Game, feedback: List<FeedbackEntity>): List<ChannelMatch> = withContext(Dispatchers.IO) {
        val version = repo.state.value.version
        val fbKey = feedback.hashCode()
        cache[game.id]?.let { c ->
            if (c.version == version && c.feedbackKey == fbKey && System.currentTimeMillis() - c.at < 5 * 60_000) return@withContext c.result
        }
        val s = settings.current()
        val mg = toMatchGame(game, s.regions)
        val fb = MatchFeedback(
            confirmed = feedback.filter { it.verdict > 0 }.map { it.channelId }.toSet(),
            rejected = feedback.filter { it.verdict < 0 }.map { it.channelId }.toSet(),
            leagueAffinity = db.user().affinity(game.league.key).associate { it.channelId to it.n },
        )
        val idx = candidates(mg, game, fb.confirmed)
        val result = withContext(Dispatchers.Default) { Matcher.match(mg, idx, fb, knownTeamNames) }
        cache[game.id] = Cached(System.currentTimeMillis(), version, fbKey, result)
        result
    }

    /** Small index with just the shows / channels that could be relevant to this game. */
    private suspend fun candidates(mg: MatchGame, game: Game, confirmed: Set<String>): MatchIndex {
        val terms = Matcher.searchTerms(mg)
        val now = System.currentTimeMillis()
        val ref = if (game.isLive) now else game.startMillis
        val programs = if (terms.words.isEmpty()) emptyList() else runCatching {
            val q = arabicVariants(terms.words).take(32).joinToString(" OR ") { "\"$it\"" }
            db.programs().search(q, minOf(now, ref) - 3 * 3600_000L, maxOf(now, ref) + 6 * 3600_000L, 4000)
        }.getOrDefault(emptyList())
        val channels = LinkedHashMap<String, ChannelLite>()
        programs.map { it.epgKey }.distinct().chunked(500).forEach { keys -> db.channels().liteByEpgKeys(keys).forEach { channels[it.id] = it } }
        // Event channels named after the game, and the broadcasters' channels.
        (terms.words.sortedByDescending { it.length }.take(8) + terms.broadcasterWords).forEach { w ->
            db.channels().liteByName(w, 400).forEach { channels[it.id] = it }
        }
        if (confirmed.isNotEmpty()) db.channels().liteByIds(confirmed.toList()).forEach { channels[it.id] = it }
        return MatchIndex(
            channels.values.map { MatchChannel(it.id, it.name, it.groupTitle, it.epgKey) },
            programs.map { MatchProgram(it.epgKey, it.start, it.stop, it.title, it.subtitle, it.description, it.category) },
        )
    }

    /** Guides write Arabic with hamza forms our normalizer folds away; search both spellings. */
    private fun arabicVariants(words: Set<String>): List<String> = words.flatMap { w ->
        if (w.firstOrNull() == 'ا') listOf(w, "أ" + w.drop(1), "إ" + w.drop(1)) else if (w.endsWith("ه")) listOf(w, w.dropLast(1) + "ة") else listOf(w)
    }.distinct()

    /** Good-match counts for the game cards, computed one game at a time in priority order; [onEach] gets partial results. */
    suspend fun strongCounts(games: List<Game>, onEach: (Map<String, Int>) -> Unit) {
        if (games.isEmpty() || db.channels().count() == 0) return
        val out = LinkedHashMap<String, Int>()
        for (g in games) {
            val matches = runCatching { find(g, emptyList()) }.getOrDefault(emptyList())
            out[g.id] = matches.count { it.score >= 40 }
            onEach(HashMap(out))
        }
    }

    private fun Team.matchTeam() = MatchTeam(listOf(displayName, shortName, nickname).filter { it.isNotBlank() }.distinct(), abbreviation)
    private val noTeam = MatchTeam(emptyList())

    private fun toMatchGame(g: Game, regions: Set<Region>): MatchGame {
        val rights = g.league.rights.filterKeys { regions.isEmpty() || it in regions }.values.flatten().distinct()
        return MatchGame(
            id = g.id,
            leagueKey = g.league.key,
            sport = g.league.sport.espn,
            leagueNames = (listOf(g.league.name, g.league.shortName) + g.league.aliases).distinct(),
            home = if (g.headToHead) g.home.matchTeam() else noTeam,
            away = if (g.headToHead) g.away.matchTeam() else noTeam,
            eventNames = listOfNotNull(g.title),
            startMillis = g.startMillis,
            isLive = g.isLive,
            isWomen = g.league.isWomen,
            broadcasters = g.broadcasts,
            rightsHolders = rights,
        )
    }
}
