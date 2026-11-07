package com.arena.tv.data

/**
 * Settings → Title Optimizer: strips clutter from channel, movie and series names
 * ("|UK| Sky Sports Main Event FHD HEVC" → "Sky Sports Main Event").
 * Matching ignores case and catches wrapped forms like (HD), [4K] or |FHD|.
 */
object TitleOptimizer {
    val DEFAULT_WORDS = listOf(
        "4K", "UHD", "FHD", "HD", "SD", "HQ", "LQ", "HEVC", "H265", "H264", "H.265", "H.264", "x265", "x264",
        "HDR", "HDR10", "DV", "10bit", "8bit", "1080p", "720p", "480p", "2160p", "50fps", "60fps", "VIP", "RAW", "MULTI", "MULTISUB",
        "FHD+", "HD+", "ᴴᴰ", "ᶠᴴᴰ", "ᵁᴴᴰ", "ᴿᴬᵂ", "ᴸᴵⱽᴱ",
    )

    data class Config(val enabled: Boolean = false, val words: List<String> = DEFAULT_WORDS, val country: Boolean = false, val emoji: Boolean = true)

    /** Current settings (kept in sync with Prefs by the app). */
    @Volatile var config = Config()

    fun configOf(p: Prefs) = Config(p.titleOptimizer, p.titleWords ?: DEFAULT_WORDS, p.titleCountry, p.titleEmoji)

    private val countryPrefix = Regex("""^\s*[|\[(]?\s*[A-Za-z]{2,3}(?:[-/][A-Za-z]{2,3})?\s*[|\]):]+\s*""")
    private val emojiRe = Regex("""[\p{So}\p{Sk}\x{1F1E6}-\x{1F1FF}\x{FE0F}\x{200D}]|[\uD83C-\uDBFF][\uDC00-\uDFFF]""")
    private val emptyWrap = Regex("""[\[({]\s*[\])}]|\|\s*\|""")
    // Leftover separators at the ends; a leading "|" only when it isn't wrapping a tag like "|UK|".
    private val edges = Regex("""^(?:[\s:•·\-_/]|\|\s)+|[\s|:•·\-_/]+$""")
    private val spaces = Regex("""\s{2,}""")
    private val cache = java.util.concurrent.ConcurrentHashMap<List<String>, Regex>()

    private fun wordsRegex(words: List<String>): Regex = cache.getOrPut(words) {
        val alt = words.filter { it.isNotBlank() }.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it.trim()) }
        // A whole word, optionally wrapped in (), [] or ||.
        Regex("""(?i)[\[(|]?\s*(?<![\p{L}\p{N}])(?:$alt)(?![\p{L}\p{N}])\s*[\])|]?""")
    }

    fun clean(name: String, c: Config = config): String {
        if (!c.enabled || name.isBlank()) return name
        var s = name
        if (c.emoji) s = s.replace(emojiRe, " ")
        if (c.country) s = s.replace(countryPrefix, "")
        if (c.words.isNotEmpty()) s = s.replace(wordsRegex(c.words), " ")
        repeat(2) { s = s.replace(emptyWrap, " ") }
        s = s.replace(spaces, " ").replace(edges, "").trim()
        return s.ifBlank { name.trim() }
    }

    /** Clean only if switched on (for imports). */
    fun apply(name: String): String = if (config.enabled) clean(name) else name
}
