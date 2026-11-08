package com.arena.tv.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Live scores, stats and per-game broadcasters from ESPN's public site API (no key needed). */
class SportsApi(private val http: OkHttpClient) {
    private val base = "https://site.api.espn.com/apis/site/v2/sports"

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body?.string() ?: throw IOException("Empty response")
        }
    }

    /** @param date yyyyMMdd in the user's local day, or null for ESPN's "current" day. */
    suspend fun scoreboard(league: League, date: String?): List<Game> = withContext(Dispatchers.IO) {
        when (league.sport) {
            // One feed with every current cricket series (dates don't apply).
            Sport.CRICKET -> EspnParser.parseCricketPanel(get("$base/cricket/scorepanel"), league)
            Sport.WRESTLING -> WrestlingSchedule.games(league, date?.let {
                runCatching { java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).parse(it)!!.time + 12 * 3600_000L }.getOrNull() } ?: System.currentTimeMillis())
            else -> {
                val url = "$base/${league.key}/scoreboard" + (date?.let { "?dates=$it" } ?: "")
                EspnParser.parseScoreboard(get(url), league)
            }
        }
    }

    /** Fetches many leagues in parallel. Failed leagues are skipped; returns (games, failures). */
    suspend fun scoreboards(leagues: List<League>, date: String?): Pair<List<Game>, Int> = coroutineScope {
        val results = leagues.map { l -> async { runCatching { scoreboard(l, date) } } }.awaitAll()
        results.flatMap { it.getOrDefault(emptyList()) } to results.count { it.isFailure }
    }

    /** All teams of a league, for "follow a team" search. */
    suspend fun teams(league: League): List<Team> = withContext(Dispatchers.IO) {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(get("$base/${league.key}/teams"))
        fun kotlinx.serialization.json.JsonElement?.o(k: String) = (this as? kotlinx.serialization.json.JsonObject)?.get(k)
        fun kotlinx.serialization.json.JsonElement?.a() = (this as? kotlinx.serialization.json.JsonArray) ?: emptyList()
        fun kotlinx.serialization.json.JsonElement?.s() = (this as? kotlinx.serialization.json.JsonPrimitive)?.content
        root.o("sports").a().firstOrNull().o("leagues").a().firstOrNull().o("teams").a().mapNotNull { w ->
            val t = w.o("team") ?: return@mapNotNull null
            val name = t.o("displayName").s() ?: return@mapNotNull null
            Team(t.o("id").s() ?: return@mapNotNull null, name, t.o("shortDisplayName").s() ?: name, t.o("abbreviation").s() ?: "", "", "",
                t.o("logos").a().firstOrNull().o("href").s(), t.o("color").s(), null, false)
        }
    }

    suspend fun details(game: Game): GameDetails = withContext(Dispatchers.IO) {
        if (game.league.sport == Sport.WRESTLING) return@withContext GameDetails(emptyList(), emptyList(), game.broadcasts)
        EspnParser.parseSummary(get("$base/${game.apiLeague ?: game.league.key}/summary?event=${game.id}"), game.home.id)
    }
}
