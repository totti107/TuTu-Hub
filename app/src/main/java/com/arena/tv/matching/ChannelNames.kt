package com.arena.tv.matching

/**
 * Cleans IPTV channel names so they can be compared with broadcaster names.
 * "UK| Sky Sports Main Event FHD ᴿᴬᵂ" -> "sky sports main event"
 * "AR: beIN SPORTS 1 HD (Backup)"      -> "bein sports 1"
 */
object ChannelNames {
    private val noiseTokens = setOf(
        "hd", "fhd", "uhd", "sd", "hq", "lq", "4k", "8k", "1080p", "720p", "1080", "720", "2160p",
        "hevc", "h264", "h265", "x265", "50fps", "60fps", "fps", "raw", "vip", "backup", "bk", "alt",
        "multi", "multiaudio", "audio", "premium", "live", "feed",
        "geo", "nogeo", "geoblocked",
        "low", "high", "mobile", "orig", "source", "direct", "dd", "dolby", "ac3", "eac3"
    )
    // Things like "[UK]", "(1080p)", "UK|", "|AR|", "UK:", "UK -", leading "#"
    private val bracketed = "[\\[(][^\\])]{0,20}[\\])]".toRegex()
    private val prefix = "^\\s*[|#*]*\\s*[A-Za-z]{2,4}\\s*[|:\\-–»]+\\s*".toRegex()
    private val superscripts = "[\\u1D00-\\u1DBF\\u02B0-\\u02FF\\u2070-\\u209F]".toRegex()

    /** Country tags: only dropped at the end ("beIN Sports 1 AR"); at the start they're handled by [prefix]. */
    private val countryTokens = setOf(
        "us", "uk", "ar", "ca", "fr", "es", "de", "it", "pt", "nl", "tr", "mena", "usa", "eng", "en", "arab", "arabic",
        "latino", "lat", "mx", "br", "au", "ie", "be", "ch", "pl", "gr", "ro", "ex", "yu", "se", "dk", "no", "fi", "in",
        "pk", "za", "nz", "ksa", "uae",
    )

    /** ESPN and guides abbreviate networks; expand to the names channels use. */
    private val networkAliases = mapOf(
        "usa net" to "usa network", "fs1" to "fox sports 1", "fs2" to "fox sports 2", "cbssn" to "cbs sports network",
        "nfl net" to "nfl network", "mlb net" to "mlb network", "nhl net" to "nhl network", "nbatv" to "nba tv",
        "espn deportes" to "espn deportes", "tsn" to "tsn", "sn" to "sportsnet", "sn1" to "sportsnet one",
        "tnt sports" to "tnt sports", "truetv" to "trutv", "bein" to "bein sports", "bein sport" to "bein sports",
        "sky sport" to "sky sport", "abc" to "abc", "peacock" to "peacock", "prime video" to "amazon prime",
    )

    fun expandNetwork(raw: String): String {
        val n = Text.normalize(raw)
        return networkAliases[n]?.let { Text.normalize(it) } ?: n
    }

    private val genericWords = setOf("sport", "sports", "tv", "channel", "network", "live", "plus", "one", "max")

    fun clean(raw: String): String {
        var s = raw.replace(superscripts, " ")
        repeat(2) { s = s.replace(prefix, "") }
        s = s.replace(bracketed, " ")
        val toks = Text.tokens(Text.normalize(s)).filter { it !in noiseTokens }.toMutableList()
        while (toks.size > 1 && toks.last() in countryTokens) toks.removeAt(toks.lastIndex)
        return toks.joinToString(" ")
    }

    /**
     * How well a channel name matches a broadcaster name.
     * 1.0 = same network and same channel number ("bein sports 1" vs "bein sports 1")
     * 0.7 = channel is part of the network, broadcaster didn't name a number ("sky sports" vs "sky sports main event")
     * 0.0 = no match (also when numbers disagree: "bein sports 1" vs "bein sports 2").
     */
    fun broadcasterMatch(channelClean: String, broadcasterClean: String): Double {
        if (channelClean.isEmpty() || broadcasterClean.isEmpty()) return 0.0
        if (channelClean == broadcasterClean) return 1.0
        val cTok = Text.tokens(channelClean)
        val bTok = Text.tokens(broadcasterClean)
        val bNum = bTok.filter { it.all(Char::isDigit) }
        val bWords = bTok.filter { !it.all(Char::isDigit) }
        if (bWords.isEmpty()) return 0.0
        // A lone generic word ("sport", "tv") is too weak to call a match.
        if (bWords.size == 1 && bNum.isEmpty() && bWords[0] in genericWords) return 0.0
        // all broadcaster words must appear, in order, in the channel name
        var from = 0
        for (w in bWords) {
            val i = cTok.subList(from, cTok.size).indexOf(w)
            if (i < 0) return 0.0
            from += i + 1
        }
        val cNum = cTok.filter { it.all(Char::isDigit) }
        return when {
            bNum.isNotEmpty() && cNum.isNotEmpty() -> if (bNum.first() == cNum.first()) 1.0 else 0.0
            bNum.isNotEmpty() -> 0.6 // channel "bein sports" (no number) vs "bein sports 1"
            cTok.size == bTok.size -> 1.0
            else -> 0.7
        }
    }
}
