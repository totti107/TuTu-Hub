package com.arena.tv.sports

import com.arena.tv.AppContainer
import kotlinx.coroutines.flow.first

/**
 * Picks the games to show in the corner while watching (Settings → Sports → Scores while watching):
 * games you pinned, live games of teams you follow, and the live game on the current channel.
 * The full scoreboard is fetched every few minutes; in between only the leagues of the games on screen.
 */
class ScoreTicker(private val c: AppContainer) {
    private var all: List<Game> = emptyList()
    private var lastFull = 0L

    suspend fun load(channelId: String?): List<Game> {
        val s = c.settings.current()
        val p = s.prefs
        if (!p.scorePinned && !p.scoreTeams && !p.scoreThisChannel) return emptyList()
        val now = System.currentTimeMillis()
        val pinned = p.pinnedGames.filter { (it.substringAfter('@').toLongOrNull() ?: 0) > now - 24 * 3600_000L }.map { it.substringBefore('@') }.toSet()
        val leagues = Leagues.all.filter { it.key in s.enabledLeagues }
        val onScreen = shown(all, p, pinned, emptySet(), channelId)
        all = if (now - lastFull > 180_000 || all.isEmpty() || onScreen.isEmpty()) {
            lastFull = now
            c.sports.games(leagues, DayOffset.TODAY).first
        } else {
            val fresh = c.sports.games(leagues, DayOffset.TODAY, onScreen.map { it.league.key }.toSet()).first.associateBy { it.id }
            all.map { fresh[it.id] ?: it }
        }
        val teams = c.db.user().teams().first().map { it.key }.toSet()
        // Live games showing on this channel (only worked out when that option is on).
        val here = if (p.scoreThisChannel && channelId != null) all.filter { it.isLive }.take(40).filter { g ->
            runCatching { c.finder.find(g, emptyList()) }.getOrDefault(emptyList()).any { it.channelId == channelId && it.score >= 40 }
        }.map { it.id }.toSet() else emptySet()
        return shown(all, p, pinned, teams, channelId, here)
    }

    private fun shown(games: List<Game>, p: com.arena.tv.data.Prefs, pinned: Set<String>, teams: Set<String>, channelId: String?, here: Set<String> = emptySet()): List<Game> {
        val now = System.currentTimeMillis()
        fun mine(g: Game) = g.home.key(g.league.sport) in teams || g.away.key(g.league.sport) in teams
        return games.filter { g ->
            (p.scorePinned && g.id in pinned && (g.isLive || g.state == GameState.POST && g.startMillis > now - 5 * 3600_000L)) ||
                (p.scoreTeams && g.isLive && mine(g)) ||
                (g.id in here)
        }.sortedWith(compareByDescending<Game> { it.id in here }.thenByDescending { it.id in pinned }.thenBy { it.startMillis }).take(4)
    }
}
