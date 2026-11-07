package com.arena.tv.matching

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A team as the matcher sees it. */
data class MatchTeam(val names: List<String>, val abbreviation: String? = null)

/** A game we want to find on TV. Times are epoch millis. */
data class MatchGame(
    val id: String,
    val leagueKey: String,
    /** "soccer", "basketball", "football", "hockey", "baseball" */
    val sport: String = "soccer",
    val leagueNames: List<String>,
    val home: MatchTeam,
    val away: MatchTeam,
    val startMillis: Long,
    val isLive: Boolean,
    val isWomen: Boolean = false,
    /** Event names ("UFC Fight Night: Walker vs Cortes Acosta", "Sao Paulo Grand Prix"), matched like team names. */
    val eventNames: List<String> = emptyList(),
    /** Broadcasters the sports feed lists for this specific game. */
    val broadcasters: List<String> = emptyList(),
    /** Networks that usually hold rights to this league in the user's regions. */
    val rightsHolders: List<String> = emptyList(),
)

data class MatchChannel(val id: String, val name: String, val group: String, val epgKey: String?)

data class MatchProgram(
    val epgKey: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
)

/** What the user told us before: thumbs up / down per game, and how often a channel carried this league. */
data class MatchFeedback(
    val confirmed: Set<String> = emptySet(),
    val rejected: Set<String> = emptySet(),
    val leagueAffinity: Map<String, Int> = emptyMap(),
)

enum class Tier { CONFIRMED, STRONG, LIKELY, USUAL }

data class ChannelMatch(
    val channelId: String,
    val score: Int,
    val tier: Tier,
    val reasons: List<String>,
    val programTitle: String?,
    val programStart: Long?,
    val programStop: Long?,
)

/**
 * Pre-processed channels + TV guide. Build once (it's the expensive part), then match many games fast.
 */
class MatchIndex(channels: List<MatchChannel>, programs: List<MatchProgram>) {

    internal class Ch(val src: MatchChannel, val clean: String, val norm: String, val sportsy: Boolean)
    internal class Pr(val src: MatchProgram, val text: String, val titleNorm: String, val ch: List<Int>)

    internal val ch: List<Ch>
    internal val pr: List<Pr>
    internal val chByToken = HashMap<String, MutableList<Int>>()
    internal val prByToken = HashMap<String, MutableList<Int>>()

    init {
        val sportWords = setOf("sport", "sports", "spor", "deportes", "esporte", "esportes", "football", "soccer",
            "bein", "espn", "dazn", "tnt", "sky", "arena", "eurosport", "match", "premier", "nba", "nfl", "nhl", "mlb",
            "event", "events", "ppv", "league", "liga", "رياضه", "رياضية", "الرياضية")
        ch = channels.map { c ->
            val clean = ChannelNames.clean(c.name)
            val norm = Text.normalize(c.name)
            val groupNorm = Text.normalize(c.group)
            val sportsy = Text.tokens("$norm $groupNorm").any { it in sportWords }
            Ch(c, clean, norm, sportsy)
        }
        ch.forEachIndexed { i, c ->
            for (t in Text.tokens(c.norm).toSet()) chByToken.getOrPut(t) { mutableListOf() } += i
        }
        val chByEpg = HashMap<String, MutableList<Int>>()
        ch.forEachIndexed { i, c -> c.src.epgKey?.let { chByEpg.getOrPut(it.lowercase()) { mutableListOf() } += i } }
        pr = programs.mapNotNull { p ->
            val owners = chByEpg[p.epgKey.lowercase()] ?: return@mapNotNull null
            val titleNorm = Text.normalize(listOfNotNull(p.title, p.subtitle).joinToString(" "))
            val text = Text.normalize(listOfNotNull(p.title, p.subtitle, p.description?.take(240), p.category).joinToString(" "))
            Pr(p, text, titleNorm, owners)
        }
        pr.forEachIndexed { i, p ->
            for (t in Text.tokens(p.text).toSet()) prByToken.getOrPut(t) { mutableListOf() } += i
        }
    }

    val channelCount get() = ch.size
    val programCount get() = pr.size
}

object Matcher {
    private val separators = setOf("vs", "v", "x", "versus", "against", "contre", "contra", "gegen", "ضد", "مع", "و", "at")
    private val clubFiller = setOf("fc", "cf", "sc", "ac", "b", "ii", "u23", "u21", "u20", "u19", "u18", "u17",
        "women", "woman", "w", "femenino", "feminin", "feminine", "frauen", "ladies", "reserves", "youth", "academy")
    private val liveWords = setOf("live", "en vivo", "en directo", "en direct", "ao vivo", "diretta", "in diretta",
        "live coverage", "مباشر", "بث مباشر", "canli")
    private val replayWords = setOf("highlights", "replay", "rerun", "re run", "review", "classic", "classics",
        "rewind", "best of", "magazine", "repeat", "delayed", "ملخص", "اعاده", "recap", "condensed")
    private val previewWords = setOf("preview", "pre match", "pregame", "pre game", "build up", "countdown", "kick off show")
    private val womenWords = setOf("women", "womens", "woman", "femenino", "feminin", "feminine", "frauen", "ladies", "wsl", "nwsl", "wnba")
    private val youthWords = setOf("u23", "u21", "u20", "u19", "u18", "u17", "youth", "reserves", "academy")

    /** Channels dedicated to another sport ("Sky Sports F1") aren't offered from broadcaster hints. */
    private val otherSportWords = mapOf(
        "soccer" to setOf("f1", "golf", "cricket", "racing", "darts", "tennis", "nfl", "nba", "nhl", "mlb", "boxing", "ufc", "wwe",
            "rugby", "snooker", "cycling", "motogp", "nascar", "baseball", "basketball", "hockey", "redzone"),
        "basketball" to setOf("f1", "golf", "cricket", "racing", "darts", "tennis", "nfl", "nhl", "mlb", "football", "soccer", "boxing",
            "ufc", "wwe", "rugby", "laliga", "redzone", "golazo"),
        "football" to setOf("f1", "golf", "cricket", "racing", "darts", "tennis", "nba", "nhl", "mlb", "soccer", "boxing", "ufc", "wwe",
            "laliga", "golazo", "basketball"),
        "mma" to setOf("f1", "golf", "cricket", "racing", "darts", "tennis", "nfl", "nba", "nhl", "mlb", "football", "soccer", "laliga", "golazo", "rugby"),
        "tennis" to setOf("f1", "golf", "cricket", "racing", "darts", "nfl", "nba", "nhl", "mlb", "football", "soccer", "boxing", "ufc", "wwe", "laliga", "golazo", "rugby"),
        "golf" to setOf("f1", "cricket", "racing", "darts", "tennis", "nfl", "nba", "nhl", "mlb", "football", "soccer", "boxing", "ufc", "wwe", "laliga", "golazo", "rugby"),
        "racing" to setOf("golf", "cricket", "darts", "tennis", "nfl", "nba", "nhl", "mlb", "football", "soccer", "boxing", "ufc", "wwe", "laliga", "golazo", "rugby"),
        "rugby" to setOf("f1", "golf", "cricket", "racing", "darts", "tennis", "nfl", "nba", "nhl", "mlb", "boxing", "ufc", "wwe", "laliga", "golazo"),
        "hockey" to setOf("f1", "golf", "cricket", "racing", "darts", "tennis", "nba", "nfl", "mlb", "football", "soccer", "laliga", "golazo"),
        "baseball" to setOf("f1", "golf", "cricket", "racing", "darts", "tennis", "nba", "nfl", "nhl", "football", "soccer", "laliga", "golazo"),
    )

    /**
     * Matches event names. "MSC Cruises Sao Paulo Grand Prix" also matches "Sao Paulo Grand Prix";
     * a phrase must keep at least one distinctive word, so "Grand Prix" alone never counts.
     */
    private class EventMatcher(names: List<String>) {
        private val generic = setOf("championship", "championships", "grand", "prix", "open", "tournament", "classic", "series", "night",
            "fight", "cup", "world", "masters", "the", "of", "at", "vs", "v", "and", "tour", "invitational", "race", "card", "main", "event")
        val phrases: List<String> = names.flatMap { raw ->
            val parts = raw.split(':', '-', '|').map(Text::normalize).filter { it.isNotEmpty() } + Text.normalize(raw)
            parts.flatMap { n ->
                val t = Text.tokens(n)
                (0 until t.size).map { i -> t.subList(i, t.size).joinToString(" ") }
                    .filter { p -> Text.tokens(p).let { it.size >= 2 && it.any { w -> w !in generic && w.length >= 3 } } }
            }
        }.distinct()
        val anchors: Set<String> = phrases.flatMap { Text.tokens(it) }.filter { it !in generic && it.length >= 3 }.toSet()

        fun score(text: String): Double {
            var best = 0.0
            for (p in phrases) if (Text.containsPhrase(text, p)) best = maxOf(best, if (Text.tokens(p).size >= 3) 1.0 else 0.8)
            return best
        }
    }

    private class TeamMatcher(val aliases: List<TeamAliases.Alias>, val known: Set<String>) {
        val own = aliases.map { it.text }.toSet()
        val anchors: Set<String> = aliases.flatMap { a ->
            val t = Text.tokens(a.text); if (t.size == 1) t else t.filter { it.length >= 3 }
        }.toSet()

        /** 0 = not found, ~1 = clearly found. */
        fun score(text: String): Double {
            var best = 0.0
            val toks = Text.tokens(text)
            for (a in aliases) {
                if (!Text.containsPhrase(text, a.text)) continue
                if (a.strict && !isolatedSomewhere(toks, a.text)) continue
                best = max(best, if (a.strict) 0.85 else 1.0)
                if (best >= 1.0) return best
            }
            if (best > 0) return best
            // Fuzzy: typos / transliterations ("Atletico Madird", "Mönchengladbach" spelt differently)
            for (a in aliases) {
                if (a.strict || a.text.length < 6) continue
                val at = Text.tokens(a.text)
                if (at.size > toks.size) continue
                for (i in 0..toks.size - at.size) {
                    val window = toks.subList(i, i + at.size).joinToString(" ")
                    if (abs(window.length - a.text.length) > 3) continue
                    val sim = Text.jaroWinkler(window, a.text)
                    if (sim >= 0.93) best = max(best, 0.7)
                }
            }
            return best
        }

        private fun isolatedSomewhere(toks: List<String>, alias: String): Boolean {
            val at = Text.tokens(alias)
            outer@ for (i in 0..toks.size - at.size) {
                for (j in at.indices) if (toks[i + j] != at[j]) continue@outer
                val prev = toks.getOrNull(i - 1)
                val next = toks.getOrNull(i + at.size)
                val next2 = toks.getOrNull(i + at.size + 1)
                if (next != null && next in clubFiller) continue
                if (prev != null && "$prev $alias" in known && "$prev $alias" !in own) continue
                if (next != null && "$alias $next" in known && "$alias $next" !in own) continue
                if (next != null && next2 != null && "$alias $next $next2" in known && "$alias $next $next2" !in own) continue
                return true
            }
            return false
        }
    }

    /** Words too common to narrow a search ("united", "real", "city"...). They still count when scoring. */
    private val weakSearchWords = setOf("united", "city", "real", "club", "the", "and", "new", "san", "los", "las", "man", "sporting", "athletic",
        "inter", "saint", "town", "county", "rovers", "wanderers", "athletico", "deportivo", "football", "sports", "sport", "state", "university",
        "national", "national", "fight", "night", "grand", "prix", "open", "championship", "cup", "league", "team", "women")

    data class SearchTerms(val words: Set<String>, val broadcasterWords: Set<String>)

    /**
     * Words worth searching the TV guide / channel names for, so only a few hundred candidates
     * need full scoring instead of the whole guide.
     */
    fun searchTerms(game: MatchGame): SearchTerms {
        val words = HashSet<String>()
        for (a in TeamAliases.aliasesFor(game.home.names, game.home.abbreviation) + TeamAliases.aliasesFor(game.away.names, game.away.abbreviation)) {
            val t = Text.tokens(a.text)
            if (t.size == 1) { if (a.text.length >= 3 && (!a.strict || a.text.length >= 4)) words += a.text }
            else words += t.filter { it.length >= 3 }
        }
        words += EventMatcher(game.eventNames).anchors
        words.removeAll(weakSearchWords)
        val bw = (game.broadcasters + game.rightsHolders).mapNotNull { b ->
            Text.tokens(ChannelNames.clean(ChannelNames.expandNetwork(b))).firstOrNull { !it.all(Char::isDigit) && it.length >= 2 }
        }.toSet()
        return SearchTerms(words, bw)
    }

    /**
     * @param knownTeamNames normalized multi-word names of every team we know about today; used so that
     *        "Real Betis" is never read as "Real" (Madrid).
     */
    fun match(
        game: MatchGame,
        index: MatchIndex,
        feedback: MatchFeedback = MatchFeedback(),
        knownTeamNames: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): List<ChannelMatch> {
        val homeAliases = TeamAliases.aliasesFor(game.home.names, game.home.abbreviation)
        val awayAliases = TeamAliases.aliasesFor(game.away.names, game.away.abbreviation)
        val known = HashSet<String>(knownTeamNames)
        (homeAliases + awayAliases).forEach { if (it.text.contains(' ')) known += it.text }
        TeamAliases.allMultiWordNames.forEach { known += it }
        val home = TeamMatcher(homeAliases, known)
        val away = TeamMatcher(awayAliases, known)
        val event = EventMatcher(game.eventNames)
        val leagueNames = game.leagueNames.map(Text::normalize).filter { it.length >= 2 }.toSet()

        // When is the game on TV? Live: now. Upcoming: kick-off. Finished: around kick-off (replays rank low anyway).
        val ref = if (game.isLive) now else game.startMillis

        data class Acc(var score: Int = 0, val reasons: LinkedHashSet<String> = LinkedHashSet(), var prog: MatchProgram? = null)
        val acc = HashMap<Int, Acc>()

        fun textScore(text: String, isChannelName: Boolean, p: MatchProgram?): Pair<Int, List<String>> {
            val h = home.score(text)
            val a = away.score(text)
            val ev = event.score(text)
            if (h == 0.0 && a == 0.0 && ev == 0.0) return 0 to emptyList()
            val reasons = mutableListOf<String>()
            var s: Int
            if (ev > 0 && !(h > 0 && a > 0)) {
                s = (45 + 15 * ev).toInt() + if (h > 0 || a > 0) 8 else 0
                reasons += if (isChannelName) "Event in channel name" else "Event in TV guide"
            } else if (h > 0 && a > 0) {
                s = (50 + 10 * (h + a) / 2).toInt()
                reasons += if (isChannelName) "Both teams in channel name" else "Both teams in TV guide"
            } else {
                s = (22 * max(h, a)).toInt()
                reasons += if (isChannelName) "Team in channel name" else "One team in TV guide"
            }
            if (leagueNames.any { Text.containsPhrase(text, it) }) { s += 8; reasons += "League named" }
            if (liveWords.any { Text.containsPhrase(text, it) }) { s += 5; reasons += "Marked live" }
            if (replayWords.any { Text.containsPhrase(text, it) }) { s -= 45; reasons += "Looks like a replay" }
            if (previewWords.any { Text.containsPhrase(text, it) }) { s -= 15 }
            val tks = Text.tokens(text).toSet()
            if (!game.isWomen && womenWords.any { it in tks }) s -= 50
            if (game.isWomen && womenWords.none { it in tks } && isChannelName) s -= 10
            if (youthWords.any { it in tks }) s -= 50
            if (p != null) {
                val d = abs(p.start - game.startMillis)
                when {
                    d <= 20 * 60_000 -> { s += 12; reasons += "Starts at kick-off" }
                    d <= 45 * 60_000 -> { s += 6; reasons += "Starts near kick-off" }
                    p.start > game.startMillis + 2 * 3600_000 -> s -= 20 // starts long after the game began: likely a replay
                }
            }
            return s to reasons
        }

        // 1) TV guide: programmes on air at the reference time that mention a team
        val candidatePrograms = HashSet<Int>()
        for (t in home.anchors + away.anchors + event.anchors) index.prByToken[t]?.let(candidatePrograms::addAll)
        for (pi in candidatePrograms) {
            val p = index.pr[pi]
            val src = p.src
            if (src.start > ref + 15 * 60_000 || src.stop < ref + 5 * 60_000) continue
            val (s, r) = textScore(p.text, false, src)
            if (s <= 0) continue
            for (ci in p.ch) {
                val a = acc.getOrPut(ci) { Acc() }
                if (s > a.score) { a.score = s; a.prog = src; a.reasons.clear(); a.reasons += r }
            }
        }

        // 2) Event channels: providers often name channels "EPL 03: Arsenal vs Chelsea 20:00"
        val candidateChannels = HashSet<Int>()
        for (t in home.anchors + away.anchors + event.anchors) index.chByToken[t]?.let(candidateChannels::addAll)
        for (ci in candidateChannels) {
            val c = index.ch[ci]
            val (s, r) = textScore(c.norm, true, null)
            if (s < 40) continue // a club TV channel ("Chelsea TV") isn't the game
            val a = acc.getOrPut(ci) { Acc() }
            if (s > a.score) { a.score = s; a.reasons.clear(); a.reasons += r }
        }

        // 3) Broadcasters: the feed's per-game list (strong), then the league's usual rights holders (weak)
        val wrongSport = otherSportWords[game.sport] ?: emptySet()
        fun addBroadcasters(names: List<String>, strong: Boolean) {
            for (b in names) {
                val bc = ChannelNames.clean(ChannelNames.expandNetwork(b))
                val first = Text.tokens(bc).firstOrNull { !it.all(Char::isDigit) } ?: continue
                for (ci in index.chByToken[first] ?: continue) {
                    val c = index.ch[ci]
                    if (Text.tokens(c.clean).any { it in wrongSport } && wrongSport.none { it in Text.tokens(bc) }) continue
                    val m = ChannelNames.broadcasterMatch(c.clean, bc)
                    if (m <= 0.0) continue
                    val pts = if (strong) (45 * m).toInt() else (25 * m).toInt()
                    val a = acc.getOrPut(ci) { Acc() }
                    val label = if (strong) "Listed broadcaster: $b" else "Usually shows ${game.leagueNames.firstOrNull() ?: "this league"}"
                    // broadcaster evidence adds on top of guide evidence, but only once
                    if (a.reasons.none { it.startsWith("Listed broadcaster") || it.startsWith("Usually shows") }) {
                        a.score += pts; a.reasons += label
                    } else if (strong && a.reasons.any { it.startsWith("Usually shows") }) {
                        a.reasons.removeAll { it.startsWith("Usually shows") }
                        a.score += pts - 25; a.reasons += label
                    }
                }
            }
        }
        addBroadcasters(game.broadcasters, strong = true)
        addBroadcasters(game.rightsHolders, strong = false)

        // 4) The user's own feedback
        for (id in feedback.confirmed) {
            val ci = index.ch.indexOfFirst { it.src.id == id }
            if (ci < 0) continue
            val a = acc.getOrPut(ci) { Acc() }
            a.score = 1000
            val rest = a.reasons.toList(); a.reasons.clear(); a.reasons += "You confirmed this channel"; a.reasons += rest
        }
        for ((ci, a) in acc) {
            val n = feedback.leagueAffinity[index.ch[ci].src.id] ?: continue
            if (n > 0 && a.score < 1000) { a.score += min(20, n * 7); a.reasons += "Carried this league before" }
        }

        val cleanLen = HashMap<String, Int>()
        index.ch.forEach { cleanLen[it.src.id] = it.clean.length }
        return acc.asSequence()
            .filter { (ci, _) -> index.ch[ci].src.id !in feedback.rejected }
            .map { (ci, a) ->
                val c = index.ch[ci]
                val score = if (a.score >= 1000) 100 else (a.score + if (c.sportsy && a.score > 0) 2 else 0).coerceIn(0, 99)
                val tier = when {
                    a.score >= 1000 -> Tier.CONFIRMED
                    score >= 70 -> Tier.STRONG
                    score >= 40 -> Tier.LIKELY
                    else -> Tier.USUAL
                }
                ChannelMatch(c.src.id, score, tier, a.reasons.toList(), a.prog?.title, a.prog?.start, a.prog?.stop)
            }
            // The weakest tier is only for broadcaster hints ("usually shows this league"), not stray one-team mentions.
            .filter { it.score >= 40 || (it.score >= 15 && it.reasons.any { r -> r.startsWith("Usually shows") || r.startsWith("Listed broadcaster") }) }
            .sortedWith(compareByDescending<ChannelMatch> { it.score }.thenBy { cleanLen[it.channelId] ?: 0 })
            .toList()
    }
}
