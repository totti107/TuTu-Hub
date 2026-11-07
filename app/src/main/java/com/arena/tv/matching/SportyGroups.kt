package com.arena.tv.matching

/** Recognises channel categories that carry sport ("UK | SPORTS", "beIN", "PPV", "NFL Game Pass"...). */
object SportyGroups {
    private val words = setOf("sport", "sports", "spor", "deportes", "football", "soccer", "nfl", "nba", "nhl", "mlb", "ppv", "ufc",
        "boxing", "wwe", "f1", "racing", "cricket", "golf", "tennis", "bein", "espn", "dazn", "event", "events", "live events",
        "game pass", "league", "liga", "رياضه", "رياضية", "ncaa", "ncaaf", "rugby", "eurosport", "tnt", "sky sports", "match",
        "fight", "mma", "motorsport", "nascar", "afl", "ssc", "arena", "supersport", "fox sports", "tsn", "sportsnet")

    fun isSporty(group: String): Boolean {
        val n = Text.normalize(group)
        val t = Text.tokens(n)
        return t.any { it in words } || words.any { it.contains(' ') && Text.containsPhrase(n, it) }
    }
}
