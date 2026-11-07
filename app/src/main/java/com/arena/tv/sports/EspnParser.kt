package com.arena.tv.sports

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Lenient parser for ESPN's public scoreboard / summary JSON. Missing fields never crash. */
object EspnParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
    private operator fun JsonElement?.get(i: Int): JsonElement? = (this as? JsonArray)?.getOrNull(i)
    private val JsonElement?.arr: List<JsonElement> get() = (this as? JsonArray) ?: emptyList()
    private val JsonElement?.str: String?
        get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }
    private val JsonElement?.bool: Boolean get() = str == "true"

    fun parseDate(s: String?): Long {
        if (s == null) return 0
        for (p in listOf("yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")) {
            try {
                val f = SimpleDateFormat(p, Locale.US); f.timeZone = TimeZone.getTimeZone("UTC")
                return f.parse(s)!!.time
            } catch (_: Exception) { }
        }
        return 0
    }

    fun parseScoreboard(body: String, league: League): List<Game> {
        val root = json.parseToJsonElement(body)
        val logo = root["leagues"][0]["logos"][0]["href"].str
        return root["events"].arr.flatMap { e ->
            runCatching {
                when (league.sport) {
                    Sport.TENNIS -> parseTennis(e, league)
                    Sport.MMA -> listOfNotNull(parseFightCard(e, league))
                    Sport.GOLF -> listOfNotNull(parseGolf(e, league))
                    Sport.RACING -> parseRace(e, league)
                    else -> listOfNotNull(parseEvent(e, league))
                }
            }.getOrDefault(emptyList())
        }.map { it.copy(leagueLogo = logo) }
    }

    private fun stateOf(type: JsonElement?) = when (type["state"].str) {
        "in" -> GameState.LIVE
        "post" -> GameState.POST
        else -> GameState.PRE
    }

    private fun athlete(c: JsonElement): Team {
        val a = c["athlete"]
        val name = a["displayName"].str ?: a["fullName"].str ?: "?"
        return Team(
            id = c["id"].str ?: a["id"].str ?: name, displayName = name, shortName = a["shortName"].str ?: name,
            abbreviation = "", nickname = name.substringAfterLast(' '), location = "",
            logo = a["flag"]["href"].str ?: a["headshot"]["href"].str, color = null,
            score = null, winner = c["winner"].bool, periodScores = c["linescores"].arr.mapNotNull { it["value"].str?.substringBefore(".0") },
            record = c["records"][0]["summary"].str, isAthlete = true,
        )
    }

    /** One game per tennis match (tournament → groupings → matches). */
    private fun parseTennis(e: JsonElement, league: League): List<Game> {
        val tournament = e["name"].str ?: league.name
        val groups = e["groupings"].arr.ifEmpty { listOf(e) }
        return groups.flatMap { g ->
            val draw = g["grouping"]["displayName"].str
            g["competitions"].arr.mapNotNull { c ->
                val cs = c["competitors"].arr
                if (cs.size < 2) return@mapNotNull null
                val h = cs.firstOrNull { it["homeAway"].str == "home" } ?: cs[0]
                val a = cs.firstOrNull { it !== h } ?: return@mapNotNull null
                val type = c["status"]["type"]
                val state = stateOf(type)
                val home = athlete(h); val away = athlete(a)
                // Score shown as sets won, period scores are the set scores.
                fun sets(x: Team, y: Team) = x.periodScores.zip(y.periodScores).count { (p, q) -> (p.toIntOrNull() ?: 0) > (q.toIntOrNull() ?: 0) }.toString()
                Game(
                    id = c["id"].str ?: return@mapNotNull null, league = league, startMillis = parseDate(c["date"].str ?: c["startDate"].str),
                    state = state, statusText = if (state == GameState.LIVE) type["shortDetail"].str ?: "Live" else type["shortDetail"].str ?: "",
                    statusDetail = type["detail"].str ?: "", period = 0,
                    home = home.copy(score = if (state == GameState.PRE) null else sets(home, away)),
                    away = away.copy(score = if (state == GameState.PRE) null else sets(away, home)),
                    broadcasts = c["broadcasts"].arr.flatMap { b -> b["names"].arr.mapNotNull { it.str } },
                    venue = c["venue"]["fullName"].str,
                    note = listOfNotNull(draw ?: c["type"]["text"].str, c["round"]["displayName"].str).joinToString(" · ").ifEmpty { null },
                    title = tournament,
                )
            }
        }
    }

    /** A fight card becomes one game: the main event (last bout) is the headline, all bouts listed. */
    private fun parseFightCard(e: JsonElement, league: League): Game? {
        val comps = e["competitions"].arr
        val main = comps.lastOrNull() ?: return null
        val cs = main["competitors"].arr
        if (cs.size < 2) return null
        val bouts = comps.asReversed().mapNotNull { c ->
            val f = c["competitors"].arr
            if (f.size < 2) return@mapNotNull null
            val w = f.indexOfFirst { it["winner"].bool }.takeIf { it >= 0 }
            Bout(f[0]["athlete"]["displayName"].str ?: "?", f[1]["athlete"]["displayName"].str ?: "?", w,
                c["type"]["abbreviation"].str, c["status"]["result"]["displayName"].str ?: c["status"]["type"]["shortDetail"].str?.takeIf { w != null })
        }
        val type = e["status"]["type"]
        val state = stateOf(type)
        return Game(
            id = e["id"].str ?: return null, league = league,
            startMillis = parseDate(main["date"].str ?: e["date"].str), state = state,
            statusText = if (state == GameState.LIVE) "Live" else type["shortDetail"].str ?: "",
            statusDetail = type["detail"].str ?: "", period = 0,
            home = athlete(cs[0]), away = athlete(cs[1]),
            broadcasts = comps.flatMap { c -> c["broadcasts"].arr.flatMap { b -> b["names"].arr.mapNotNull { it.str } } }.distinct(),
            venue = main["venue"]["fullName"].str, note = main["type"]["abbreviation"].str?.let { "Main event · $it" },
            title = e["name"].str, bouts = bouts,
        )
    }

    /** Golf tournament: a leaderboard instead of two teams. */
    private fun parseGolf(e: JsonElement, league: League): Game? {
        val c = e["competitions"][0] ?: return null
        val type = e["status"]["type"].takeIf { it != null } ?: c["status"]["type"]
        val rows = c["competitors"].arr.sortedBy { it["order"].str?.toIntOrNull() ?: 999 }.take(15).mapIndexed { i, p ->
            val rounds = p["linescores"].arr.mapNotNull { it["value"].str?.substringBefore(".0") }.filter { it != "0" }
            FieldRow((i + 1).toString(), p["athlete"]["displayName"].str ?: "?", p["score"].str ?: "-", p["athlete"]["flag"]["href"].str,
                rounds.joinToString(" "))
        }
        val placeholder = Team("", league.name, league.shortName, "", "", "", null, null, null, false)
        return Game(
            id = e["id"].str ?: return null, league = league, startMillis = parseDate(e["date"].str), endMillis = parseDate(e["endDate"].str).takeIf { it > 0 } ?: parseDate(e["date"].str),
            state = stateOf(type), statusText = c["status"]["type"]["shortDetail"].str ?: type["shortDetail"].str ?: type["description"].str ?: "",
            statusDetail = type["detail"].str ?: "", period = c["status"]["period"].str?.toIntOrNull() ?: 0,
            home = placeholder, away = placeholder,
            broadcasts = c["broadcasts"].arr.flatMap { b -> b["names"].arr.mapNotNull { it.str } },
            venue = null, note = rows.firstOrNull()?.let { "Leader: ${it.name} ${it.score}" },
            title = e["name"].str, headToHead = false, field = rows,
        )
    }

    /** Motorsport: one game per session (practice, qualifying, race) so each shows on its own day. */
    private fun parseRace(e: JsonElement, league: League): List<Game> {
        val sessions = e["competitions"].arr
        val names = mapOf("FP1" to "Practice 1", "FP2" to "Practice 2", "FP3" to "Practice 3", "Qual" to "Qualifying", "SS" to "Sprint Qualifying",
            "Sprint" to "Sprint", "Race" to "Race")
        val schedule = sessions.map { s ->
            val ab = s["type"]["abbreviation"].str ?: "Session"
            FieldRow("", names[ab] ?: ab, s["status"]["type"]["shortDetail"].str ?: "", null, s["date"].str)
        }
        val placeholder = Team("", league.name, league.shortName, "", "", "", null, null, null, false)
        return sessions.mapIndexedNotNull { i, s ->
            val ab = s["type"]["abbreviation"].str ?: "Session"
            val type = s["status"]["type"]
            val state = stateOf(type)
            Game(
                id = (s["id"].str ?: "${e["id"].str}-$i"), league = league, startMillis = parseDate(s["date"].str), state = state,
                statusText = if (state == GameState.LIVE) "Live" else type["shortDetail"].str ?: "", statusDetail = type["detail"].str ?: "", period = 0,
                home = placeholder, away = placeholder,
                broadcasts = s["broadcasts"].arr.flatMap { b -> b["names"].arr.mapNotNull { it.str } },
                venue = e["circuit"]["fullName"].str, note = names[ab] ?: ab,
                title = e["name"].str, headToHead = false, field = schedule,
            )
        }
    }

    private fun parseEvent(e: JsonElement, league: League): Game? {
        val comp = e["competitions"][0] ?: return null
        val competitors = comp["competitors"].arr
        val homeJ = competitors.firstOrNull { it["homeAway"].str == "home" } ?: competitors.getOrNull(0) ?: return null
        val awayJ = competitors.firstOrNull { it["homeAway"].str == "away" } ?: competitors.getOrNull(1) ?: return null
        val status = e["status"] ?: comp["status"]
        val type = status["type"]
        val state = when (type["state"].str) {
            "in" -> GameState.LIVE
            "post" -> GameState.POST
            else -> GameState.PRE
        }
        val broadcasts = LinkedHashSet<String>()
        comp["broadcasts"].arr.forEach { b -> b["names"].arr.mapNotNullTo(broadcasts) { it.str } }
        comp["geoBroadcasts"].arr.forEach { b -> b["media"]["shortName"].str?.let(broadcasts::add) }
        val statusText = when (state) {
            GameState.LIVE -> {
                val sd = type["shortDetail"].str
                if (league.sport == Sport.SOCCER) {
                    when (type["name"].str) {
                        "STATUS_HALFTIME" -> "HT"
                        "STATUS_END_OF_REGULATION", "STATUS_END_OF_EXTRATIME" -> sd ?: "ET"
                        else -> status["displayClock"].str ?: sd ?: "Live"
                    }
                } else sd ?: "Live"
            }
            GameState.POST -> type["shortDetail"].str ?: "Final"
            GameState.PRE -> type["shortDetail"].str ?: ""
        }
        return Game(
            id = e["id"].str ?: return null,
            league = league,
            startMillis = parseDate(e["date"].str ?: comp["date"].str),
            state = state,
            statusText = statusText,
            statusDetail = type["detail"].str ?: statusText,
            period = status["period"].str?.toIntOrNull() ?: 0,
            home = parseTeam(homeJ),
            away = parseTeam(awayJ),
            broadcasts = broadcasts.toList(),
            venue = comp["venue"]["fullName"].str ?: comp["venue"]["displayName"].str ?: e["venue"]["displayName"].str,
            note = comp["notes"][0]["headline"].str ?: comp["altGameNote"].str,
            odds = parseOdds(comp["odds"][0]),
        )
    }

    /** Odds block from a scoreboard competition or a summary's pickcenter. */
    private fun parseOdds(o: JsonElement?): Odds? {
        if (o == null) return null
        val ml = o["moneyline"]
        fun cur(side: String) = Odds.parse(ml[side]["current"]["odds"].str) ?: Odds.parse(ml[side]["close"]["odds"].str)
        fun open(side: String) = Odds.parse(ml[side]["open"]["odds"].str)
        val home = cur("home") ?: o["homeTeamOdds"]["moneyLine"].str?.let(Odds::parse)
        val away = cur("away") ?: o["awayTeamOdds"]["moneyLine"].str?.let(Odds::parse)
        val draw = cur("draw") ?: o["drawOdds"]["moneyLine"].str?.let(Odds::parse)
        if (home == null && away == null) return null
        val total = o["total"]
        return Odds(
            provider = o["provider"]["displayName"].str ?: o["provider"]["name"].str ?: "Sportsbook",
            home = home, draw = draw, away = away, homeOpen = open("home"), drawOpen = open("draw"), awayOpen = open("away"),
            total = (total["over"]["current"]["line"].str?.drop(1)?.toDoubleOrNull()) ?: o["overUnder"].str?.toDoubleOrNull(),
            over = Odds.parse(total["over"]["current"]["odds"].str) ?: o["overOdds"].str?.let(Odds::parse),
            under = Odds.parse(total["under"]["current"]["odds"].str) ?: o["underOdds"].str?.let(Odds::parse),
            spread = o["details"].str,
        )
    }

    private fun parseTeam(c: JsonElement): Team {
        val t = c["team"]
        val logo = t["logo"].str ?: t["logos"][0]["href"].str
        return Team(
            id = t["id"].str ?: c["id"].str ?: "",
            displayName = t["displayName"].str ?: t["name"].str ?: "?",
            shortName = t["shortDisplayName"].str ?: t["displayName"].str ?: "?",
            abbreviation = t["abbreviation"].str ?: "",
            nickname = t["name"].str ?: "",
            location = t["location"].str ?: "",
            logo = logo,
            color = t["color"].str,
            score = c["score"].str,
            winner = c["winner"].bool,
            periodScores = c["linescores"].arr.mapNotNull { it["displayValue"].str ?: it["value"].str?.substringBefore(".0") },
            record = c["records"][0]["summary"].str,
        )
    }

    fun parseSummary(body: String, homeTeamId: String): GameDetails {
        val root = json.parseToJsonElement(body)
        val teams = root["boxscore"]["teams"].arr
        val homeT = teams.firstOrNull { it["team"]["id"].str == homeTeamId } ?: teams.getOrNull(1)
        val awayT = teams.firstOrNull { it !== homeT }
        val stats = parseStats(homeT, awayT)

        val events = mutableListOf<GameEvent>()
        root["keyEvents"].arr.forEach { k ->
            val typeText = (k["type"]["type"].str ?: k["type"]["text"].str ?: "").lowercase()
            val kind = when {
                k["ownGoal"].bool || typeText.contains("own") -> EventKind.OWN_GOAL
                typeText.contains("penalty") && (k["scoringPlay"].bool || typeText.contains("goal")) -> EventKind.PENALTY_GOAL
                k["scoringPlay"].bool || typeText.contains("goal") -> EventKind.GOAL
                typeText.contains("red") -> EventKind.RED
                typeText.contains("yellow") -> EventKind.YELLOW
                typeText.contains("substitution") -> EventKind.SUB
                else -> null
            } ?: return@forEach
            if (k["shootout"].bool) return@forEach
            val who = k["participants"].arr.mapNotNull { it["athlete"]["displayName"].str }
            val text = when (kind) {
                EventKind.SUB -> if (who.size >= 2) "${who[0]} ↔ ${who[1]}" else who.firstOrNull() ?: "Substitution"
                EventKind.GOAL, EventKind.PENALTY_GOAL, EventKind.OWN_GOAL ->
                    buildString {
                        append(who.firstOrNull() ?: k["shortText"].str ?: "Goal")
                        if (kind == EventKind.PENALTY_GOAL) append(" (pen)")
                        if (kind == EventKind.OWN_GOAL) append(" (OG)")
                        if (who.size >= 2 && kind == EventKind.GOAL) append(" · assist ${who[1]}")
                    }
                else -> who.firstOrNull() ?: k["shortText"].str ?: ""
            }
            val teamId = k["team"]["id"].str
            events += GameEvent(k["clock"]["displayValue"].str ?: "", kind, text, teamId?.let { it == homeTeamId })
        }
        if (events.isEmpty()) {
            root["scoringPlays"].arr.forEach { p ->
                val period = p["period"]["number"].str
                val clock = listOfNotNull(period?.let { "P$it" }, p["clock"]["displayValue"].str).joinToString(" ")
                val score = listOfNotNull(p["awayScore"].str, p["homeScore"].str).joinToString("-")
                events += GameEvent(clock, EventKind.SCORE, (p["text"].str ?: "") + if (score.isNotEmpty()) "  ($score)" else "",
                    p["team"]["id"].str?.let { it == homeTeamId })
            }
        }
        val broadcasts = root["broadcasts"].arr.mapNotNull { b -> b["media"]["shortName"].str ?: b["media"]["name"].str }

        val hc = root["header"]["competitions"][0]["competitors"].arr
        val hHome = hc.firstOrNull { it["homeAway"].str == "home" }
        val hAway = hc.firstOrNull { it["homeAway"].str == "away" }
        fun lines(c: JsonElement?) = c["linescores"].arr.mapNotNull { it["displayValue"].str }

        // Lineups (soccer)
        val rosters = root["rosters"].arr
        val hRoster = rosters.firstOrNull { it["team"]["id"].str == homeTeamId } ?: rosters.firstOrNull { it["homeAway"].str == "home" }
        val aRoster = rosters.firstOrNull { it !== hRoster }
        fun lineup(r: JsonElement?) = r["roster"].arr.mapNotNull { p ->
            val name = p["athlete"]["displayName"].str ?: return@mapNotNull null
            LineupPlayer(p["jersey"].str ?: "", name, p["position"]["abbreviation"].str ?: "", p["starter"].bool)
        }.sortedByDescending { it.starter }

        // League table
        val group = root["standings"]["groups"][0]
        val table = group["standings"]["entries"].arr.mapIndexedNotNull { i, en ->
            val stats = en["stats"].arr.associate { (it["abbreviation"].str ?: it["name"].str ?: "") to (it["displayValue"].str ?: "") }
            TableRow(i + 1, en["team"].str ?: en["team"]["displayName"].str ?: return@mapIndexedNotNull null, en["id"].str,
                stats["GP"] ?: "", stats["GD"] ?: stats["PD"] ?: "", stats["P"] ?: stats["PTS"] ?: stats["W"] ?: "")
        }
        // Recent form (last five games per team)
        fun form(teamId: String?): List<FormGame> = root["lastFiveGames"].arr.firstOrNull { it["team"]["id"].str == teamId }?.get("events").arr.mapNotNull { e ->
            FormGame(e["gameResult"].str ?: return@mapNotNull null, e["opponent"]["displayName"].str ?: "?", e["opponent"]["logo"].str,
                e["score"].str ?: "", e["atVs"].str == "vs", parseDate(e["gameDate"].str), e["leagueName"].str)
        }
        val awayId = hAway["team"]["id"].str ?: awayT["team"]["id"].str
        // Top players per team
        val leaders = root["leaders"].arr.flatMap { t ->
            val isHome = t["team"]["id"].str == homeTeamId
            t["leaders"].arr.mapNotNull { cat ->
                val top = cat["leaders"][0] ?: return@mapNotNull null
                LeaderRow(isHome, cat["displayName"].str ?: cat["name"].str ?: "", top["athlete"]["displayName"].str ?: return@mapNotNull null,
                    top["displayValue"].str ?: "", top["athlete"]["headshot"]["href"].str)
            }
        }
        // Head to head
        val series = root["seasonseries"].arr.firstOrNull { it["type"].str == "head-to-head" } ?: root["seasonseries"][0]
        val h2h = series["events"].arr.mapNotNull { e ->
            val cs = e["competitors"].arr
            val h = cs.firstOrNull { it["homeAway"].str == "home" } ?: cs.getOrNull(0) ?: return@mapNotNull null
            val a = cs.firstOrNull { it !== h } ?: return@mapNotNull null
            H2HGame(parseDate(e["date"].str), h["team"]["displayName"].str ?: "?", a["team"]["displayName"].str ?: "?",
                h["score"].str ?: "", a["score"].str ?: "", series["title"].str)
        }.sortedByDescending { it.date }
        val odds = parseOdds(root["pickcenter"][0] ?: root["odds"][0])
        return GameDetails(stats, events, broadcasts.distinct(), lines(hHome), lines(hAway),
            lineup(hRoster), lineup(aRoster), hRoster["formation"].str, aRoster["formation"].str, table, group["header"].str ?: root["standings"]["header"].str,
            homeForm = form(homeTeamId), awayForm = form(awayId), leaders = leaders, h2h = h2h,
            h2hSummary = series["shortSummary"].str?.let { s -> listOfNotNull(s, series["seriesScore"].str?.takeIf { it.isNotBlank() }).joinToString(" · ") },
            odds = odds, attendance = root["gameInfo"]["attendance"].str?.let { a -> a.toIntOrNull()?.let { "%,d".format(it) } ?: a })
    }

    // Order of the stats that matter most; anything else follows in feed order.
    private val preferred = listOf(
        "possessionPct", "totalShots", "shotsOnTarget", "wonCorners", "foulsCommitted", "offsides", "yellowCards", "redCards", "saves",
        "accuratePasses", "totalPasses", "passPct", "totalTackles", "interceptions",
        "fieldGoalsMade-fieldGoalsAttempted", "fieldGoalPct", "threePointFieldGoalsMade-threePointFieldGoalsAttempted", "threePointFieldGoalPct",
        "freeThrowsMade-freeThrowsAttempted", "totalRebounds", "assists", "steals", "blocks", "turnovers", "fastBreakPoints", "pointsInPaint", "largestLead",
        "totalYards", "netPassingYards", "rushingYards", "firstDowns", "thirdDownEff", "turnovers", "possessionTime",
        "shotsTotal", "powerPlayGoals", "hits", "faceoffsWon", "blockedShots", "takeaways", "giveaways",
    )
    private val skip = setOf("appearances", "teamTurnovers", "totalTechnicalFouls", "flagrantFouls", "penaltyKickShots", "penaltyKickGoals",
        "shotAssists", "goalAssists", "totalGoals", "totalTurnovers", "leadChanges", "leadPercentage", "technicalFouls")

    private fun parseStats(homeT: JsonElement?, awayT: JsonElement?): List<StatLine> {
        if (homeT == null || awayT == null) return emptyList()
        data class S(val name: String, val label: String, val value: String)
        fun read(t: JsonElement) = t["statistics"].arr.mapNotNull {
            val name = it["name"].str ?: return@mapNotNull null
            S(name, it["label"].str ?: it["abbreviation"].str ?: name, it["displayValue"].str ?: return@mapNotNull null)
        }
        val h = read(homeT).associateBy { it.name }
        val a = read(awayT).associateBy { it.name }
        val names = (preferred.filter { it in h } + h.keys.filter { it !in preferred }).distinct().filter { it !in skip && it in a }
        return names.map { n ->
            val hs = h.getValue(n); val aS = a.getValue(n)
            val isPct = n.endsWith("Pct") || n.endsWith("Percentage")
            fun fmt(v: String): String {
                val d = v.toDoubleOrNull() ?: return v
                return if (isPct && d <= 1.0 && n != "possessionPct") "${(d * 100).toInt()}%"
                else if (isPct) "${if (d % 1.0 == 0.0) d.toInt().toString() else "%.1f".format(Locale.US, d)}%"
                else v
            }
            val hv = leadingNumber(hs.value); val av = leadingNumber(aS.value)
            val share = if (hv != null && av != null && hv + av > 0) (hv / (hv + av)).toFloat() else null
            StatLine(prettyLabel(hs.label), fmt(hs.value), fmt(aS.value), share)
        }.take(24)
    }

    private fun leadingNumber(v: String): Double? =
        Regex("^-?\\d+(\\.\\d+)?").find(v.trim())?.value?.toDoubleOrNull()

    private fun prettyLabel(l: String): String =
        if (l.length > 3 && l == l.uppercase()) l.lowercase().replaceFirstChar { it.uppercase() } else l
}
