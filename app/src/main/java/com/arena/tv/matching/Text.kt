package com.arena.tv.matching

import java.text.Normalizer

/** Text helpers shared by the matcher. Everything is compared in a normalized form. */
object Text {
    private val diacritics = "\\p{InCombiningDiacriticalMarks}+".toRegex()
    private val nonWord = "[^\\p{L}\\p{Nd}]+".toRegex()
    private val spaces = "\\s+".toRegex()

    /** Lowercase, strip accents, turn punctuation into spaces. "Atlético-Madrid FC" -> "atletico madrid fc". */
    fun normalize(input: String): String {
        if (input.isEmpty()) return ""
        var s = input.lowercase()
            .replace("ß", "ss").replace("ø", "o").replace("æ", "ae")
            .replace("œ", "oe").replace("ı", "i").replace("ł", "l").replace("đ", "d")
            .replace("&", " and ").replace("+", " plus ")
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(diacritics, "")
        // Arabic: drop tashkeel and unify alef / ya / ta marbuta forms.
        s = s.replace("[\\u064B-\\u0652\\u0640]".toRegex(), "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace('ى', 'ي').replace('ة', 'ه')
        return s.replace(nonWord, " ").replace(spaces, " ").trim()
    }

    fun tokens(normalized: String): List<String> =
        if (normalized.isEmpty()) emptyList() else normalized.split(' ')

    /** True when [phrase] appears in [text] on word boundaries. Both must be normalized. */
    fun containsPhrase(text: String, phrase: String): Boolean {
        if (phrase.isEmpty() || text.length < phrase.length) return false
        var idx = text.indexOf(phrase)
        while (idx >= 0) {
            val before = idx == 0 || text[idx - 1] == ' '
            val end = idx + phrase.length
            val after = end == text.length || text[end] == ' '
            if (before && after) return true
            idx = text.indexOf(phrase, idx + 1)
        }
        return false
    }

    /** Jaro-Winkler similarity in [0,1]. */
    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val range = (maxOf(a.length, b.length) / 2 - 1).coerceAtLeast(0)
        val aM = BooleanArray(a.length)
        val bM = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            val lo = maxOf(0, i - range)
            val hi = minOf(i + range + 1, b.length)
            for (j in lo until hi) {
                if (bM[j] || a[i] != b[j]) continue
                aM[i] = true; bM[j] = true; matches++; break
            }
        }
        if (matches == 0) return 0.0
        var t = 0
        var k = 0
        for (i in a.indices) {
            if (!aM[i]) continue
            while (!bM[k]) k++
            if (a[i] != b[k]) t++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - t / 2.0) / m) / 3.0
        var prefix = 0
        while (prefix < minOf(4, a.length, b.length) && a[prefix] == b[prefix]) prefix++
        return jaro + prefix * 0.1 * (1 - jaro)
    }
}
