package com.arena.tv.sports

enum class Sport(val label: String, val espn: String, val emoji: String) {
    SOCCER("Soccer", "soccer", "⚽"),
    BASKETBALL("Basketball", "basketball", "🏀"),
    FOOTBALL("Football", "football", "🏈"),
    HOCKEY("Hockey", "hockey", "🏒"),
    BASEBALL("Baseball", "baseball", "⚾"),
    MMA("MMA / UFC", "mma", "🥊"),
    TENNIS("Tennis", "tennis", "🎾"),
    GOLF("Golf", "golf", "⛳"),
    RACING("Motorsport", "racing", "🏎"),
    RUGBY("Rugby", "rugby", "🏉"),
    AUSSIE("Aussie Rules", "australian-football", "🏉"),
    CRICKET("Cricket", "cricket", "🏏"),
    WRESTLING("Wrestling", "wrestling", "🤼"),
}

enum class Region(val label: String) {
    UK("UK & Ireland"), US("USA"), CA("Canada"), MENA("Middle East & N. Africa"), ES("Spain"), FR("France"),
    DE("Germany"), IT("Italy"), PT("Portugal"), NL("Netherlands"), AU("Australia"), LATAM("Latin America"),
}

data class League(
    val sport: Sport,
    val espnId: String,
    val name: String,
    val shortName: String,
    /** Other ways TV guides write the league name. */
    val aliases: List<String> = emptyList(),
    /** Networks that usually hold the rights, per region. A hint only — it changes every few seasons. */
    val rights: Map<Region, List<String>> = emptyMap(),
    val defaultOn: Boolean = true,
    val isWomen: Boolean = false,
) {
    val key: String get() = "${sport.espn}/$espnId"
}

data class Team(
    val id: String,
    val displayName: String,
    val shortName: String,
    val abbreviation: String,
    val nickname: String,
    val location: String,
    val logo: String?,
    val color: String?,
    val score: String?,
    val winner: Boolean,
    val periodScores: List<String> = emptyList(),
    val record: String? = null,
    /** A person (fighter, tennis player) rather than a club. */
    val isAthlete: Boolean = false,
) {
    /** "sport:id", stable key for favorites. */
    fun key(sport: Sport) = "${sport.espn}:$id"
}

enum class GameState { PRE, LIVE, POST }

data class Game(
    val id: String,
    val league: League,
    val startMillis: Long,
    val state: GameState,
    /** "67'", "HT", "Q3 4:12", "Final". */
    val statusText: String,
    val statusDetail: String,
    val period: Int,
    val home: Team,
    val away: Team,
    val broadcasts: List<String>,
    val venue: String?,
    val note: String?,
    /** Event name for fight cards, golf tournaments, Grands Prix ("UFC 310", "Masters Tournament"). */
    val title: String? = null,
    val leagueLogo: String? = null,
    /** False for golf / motorsport: a field of players, shown as a leaderboard. */
    val headToHead: Boolean = true,
    /** Golf leaderboard or race sessions. */
    val field: List<FieldRow> = emptyList(),
    /** Other fights on the card. */
    val bouts: List<Bout> = emptyList(),
    /** Multi-day events (golf tournaments) end later than they start. */
    val endMillis: Long = startMillis,
    val odds: Odds? = null,
    /** ESPN league path for match details when it differs from the league's (cricket: the series). */
    val apiLeague: String? = null,
) {
    val isLive get() = state == GameState.LIVE
    /** Players / teams not known yet (a later round of a tournament). */
    val undecided: Boolean get() = headToHead && (home.displayName in TBD_NAMES || away.displayName in TBD_NAMES)

    /** Headline for lists: "Home v Away" or the event name. */
    val headline: String get() = if (headToHead) "${home.displayName} v ${away.displayName}" else title ?: league.name
}

/**
 * Betting odds as published in the scores feed (American format internally: +150 / -200).
 * The feed's bookmaker is shown with them (currently DraftKings via ESPN).
 */
data class Odds(
    val provider: String,
    val home: Int?, val draw: Int?, val away: Int?,
    val homeOpen: Int? = null, val drawOpen: Int? = null, val awayOpen: Int? = null,
    val total: Double? = null, val over: Int? = null, val under: Int? = null,
    val spread: String? = null,
) {
    companion object {
        fun parse(s: String?): Int? {
            if (s.isNullOrBlank()) return null
            if (s.equals("EVEN", true) || s.equals("EV", true)) return 100
            return s.replace("+", "").trim().toDoubleOrNull()?.toInt()
        }
        fun decimal(american: Int): Double = if (american > 0) 1 + american / 100.0 else 1 + 100.0 / -american
        fun format(american: Int?, decimal: Boolean): String = when {
            american == null -> "–"
            decimal -> "%.2f".format(java.util.Locale.US, decimal(american))
            american > 0 -> "+$american"
            else -> "$american"
        }
        /** Implied chance in %, from the odds. */
        fun implied(american: Int?): Int? = american?.let { (100 / decimal(it)).toInt() }
    }
}

data class FormGame(val result: String, val opponent: String, val opponentLogo: String?, val score: String, val home: Boolean, val date: Long, val competition: String?)
data class LeaderRow(val isHome: Boolean, val category: String, val player: String, val value: String, val headshot: String?)
data class H2HGame(val date: Long, val homeName: String, val awayName: String, val homeScore: String, val awayScore: String, val competition: String?)

data class FieldRow(val position: String, val name: String, val score: String, val flag: String? = null, val detail: String? = null)

data class Bout(val a: String, val b: String, val winner: Int?, val weightClass: String?, val result: String?)

data class LineupPlayer(val number: String, val name: String, val position: String, val starter: Boolean)

data class TableRow(val rank: Int, val team: String, val teamId: String?, val played: String, val goalDiff: String, val points: String)

data class StatLine(val label: String, val home: String, val away: String, val homeShare: Float?)

enum class EventKind { GOAL, OWN_GOAL, PENALTY_GOAL, YELLOW, RED, SUB, SCORE, OTHER }

data class GameEvent(val clock: String, val kind: EventKind, val text: String, val isHome: Boolean?)

data class GameDetails(
    val stats: List<StatLine>,
    val events: List<GameEvent>,
    val broadcasts: List<String>,
    val homePeriodScores: List<String> = emptyList(),
    val awayPeriodScores: List<String> = emptyList(),
    val homeLineup: List<LineupPlayer> = emptyList(),
    val awayLineup: List<LineupPlayer> = emptyList(),
    val homeFormation: String? = null,
    val awayFormation: String? = null,
    val table: List<TableRow> = emptyList(),
    val tableTitle: String? = null,
    val homeForm: List<FormGame> = emptyList(),
    val awayForm: List<FormGame> = emptyList(),
    val leaders: List<LeaderRow> = emptyList(),
    val h2h: List<H2HGame> = emptyList(),
    val h2hSummary: String? = null,
    val odds: Odds? = null,
    val attendance: String? = null,
)

/** How the feed writes a player or team that isn't decided yet. */
val TBD_NAMES = setOf("TBD", "?", "TBA", "To be decided", "To be announced")
