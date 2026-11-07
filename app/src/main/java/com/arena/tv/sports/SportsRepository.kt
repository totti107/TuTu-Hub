package com.arena.tv.sports

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Which day the hub shows, relative to today in the user's time zone. */
enum class DayOffset(val days: Int, val label: String) { YESTERDAY(-1, "Yesterday"), TODAY(0, "Today"), TOMORROW(1, "Tomorrow") }

class SportsRepository(private val api: SportsApi) {
    /** Last games seen, so the detail screen can open instantly. */
    val cache = java.util.concurrent.ConcurrentHashMap<String, Game>()

    private fun localDayBounds(offset: Int): Pair<Long, Long> {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        c.add(Calendar.DAY_OF_YEAR, offset)
        val start = c.timeInMillis
        c.add(Calendar.DAY_OF_YEAR, 1)
        return start to c.timeInMillis
    }

    /** ESPN groups games by US Eastern day; the user's local day can straddle two of those. */
    private fun espnDatesCovering(start: Long, end: Long): List<String> {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        return listOf(f.format(start), f.format(end - 1)).distinct()
    }

    /**
     * Games for a local day, live ones included even if they started the day before.
     * @return games + how many league requests failed (to show a hint when offline).
     */
    suspend fun games(leagues: List<League>, day: DayOffset, onlyLeagues: Set<String>? = null): Pair<List<Game>, Int> {
        val (start, end) = localDayBounds(day.days)
        val selected = leagues.filter { onlyLeagues == null || it.key in onlyLeagues }
        var failures = 0
        val all = ArrayList<Game>()
        for (date in espnDatesCovering(start, end)) {
            val (g, f) = api.scoreboards(selected, date)
            all += g; failures += f
        }
        val games = all.distinctBy { it.league.key + it.id }
            .filter {
                (it.startMillis in start until end) || (day == DayOffset.TODAY && it.isLive) ||
                    (!it.headToHead && it.startMillis < end && it.endMillis >= start) // golf tournaments run for days
            }
        games.forEach { cache[it.id] = it }
        runCatching { onGames?.invoke(games) }
        return games to (failures / maxOf(1, espnDatesCovering(start, end).size))
    }

    suspend fun details(game: Game) = api.details(game)

    /** One game by its league and id (used by reminders, when nothing is cached). */
    suspend fun fetchGame(leagueKey: String, id: String, startMillis: Long): Game? {
        val league = Leagues.byKey(leagueKey) ?: return null
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val g = api.scoreboard(league, f.format(startMillis)).firstOrNull { it.id == id }
        g?.let { cache[it.id] = it }
        return g
    }

    /** Called with every fresh scoreboard (reminders are scheduled from it). */
    @Volatile var onGames: ((List<Game>) -> Unit)? = null

    /** Re-reads one game's league scoreboard to get its fresh score / clock. */
    suspend fun refreshGame(game: Game): Game? {
        val f = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("America/New_York") }
        val g = api.scoreboard(game.league, f.format(game.startMillis)).firstOrNull { it.id == game.id }
        g?.let { cache[it.id] = it }
        return g
    }
}
