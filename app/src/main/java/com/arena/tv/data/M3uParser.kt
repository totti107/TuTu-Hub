package com.arena.tv.data

import java.io.BufferedReader
import java.security.MessageDigest

data class ParsedChannel(
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    val tvgName: String?,
    val userAgent: String?,
    val referrer: String?,
    /** Adult content: locked behind the parental PIN. */
    val adult: Boolean = false,
)

/** Recognises adult categories / channels by name. */
object Adult {
    private val re = Regex("""(?i)(^|[^a-z0-9])(xxx|adults?|18\s*\+|\+\s*18|porno?|erotic[a]?|playboy|hustler|brazzers|redlight|red light|dorcel|penthouse|vivid|private|for adults)([^a-z0-9]|$)""")
    fun isAdult(text: String?): Boolean = text != null && re.containsMatchIn(text)
}

data class ParsedPlaylist(
    val channels: List<ParsedChannel>,
    val epgUrls: List<String>,
    val movies: List<ParsedChannel> = emptyList(),
    val episodes: List<ParsedChannel> = emptyList(),
)

/** Streaming M3U / M3U8 (extended) playlist parser. Handles the IPTV-specific attributes and header tricks. */
object M3uParser {
    private val attr = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")
    private val vodExt = Regex("""\.(mp4|mkv|avi|mov|wmv|flv|m4v)(\?|$)""", RegexOption.IGNORE_CASE)

    fun parse(reader: BufferedReader, liveOnly: Boolean = true): ParsedPlaylist {
        val out = ArrayList<ParsedChannel>(4096)
        val movies = ArrayList<ParsedChannel>()
        val episodes = ArrayList<ParsedChannel>()
        val epg = LinkedHashSet<String>()
        var info: String? = null
        var group: String? = null
        var ua: String? = null
        var ref: String? = null
        reader.lineSequence().forEach { raw ->
            val line = raw.trim().removePrefix("﻿")
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U", true) -> {
                    attr.findAll(line).forEach { m ->
                        val k = m.groupValues[1].lowercase()
                        if (k == "url-tvg" || k == "x-tvg-url" || k == "tvg-url")
                            m.groupValues[2].split(',').map { it.trim() }.filter { it.startsWith("http") }.forEach(epg::add)
                    }
                }
                line.startsWith("#EXTINF", true) -> { info = line; group = null; ua = null; ref = null }
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val opt = line.substringAfter(':')
                    when {
                        opt.startsWith("http-user-agent=", true) -> ua = opt.substringAfter('=')
                        opt.startsWith("http-referrer=", true) || opt.startsWith("http-referer=", true) -> ref = opt.substringAfter('=')
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val i = info ?: return@forEach
                    info = null
                    var url = line
                    var lineUa = ua
                    var lineRef = ref
                    // Kodi style: http://host/stream.ts|User-Agent=xyz&Referer=abc
                    if (url.contains('|')) {
                        val headers = url.substringAfter('|')
                        url = url.substringBefore('|')
                        headers.split('&').forEach { h ->
                            val k = h.substringBefore('=').lowercase()
                            val v = java.net.URLDecoder.decode(h.substringAfter('=', ""), "UTF-8")
                            if (k == "user-agent") lineUa = v
                            if (k == "referer" || k == "referrer") lineRef = v
                        }
                    }
                    val isSeries = url.contains("/series/")
                    val isMovie = !isSeries && (vodExt.containsMatchIn(url) || url.contains("/movie/"))
                    val attrs = HashMap<String, String>()
                    attr.findAll(i).forEach { m -> attrs[m.groupValues[1].lowercase()] = m.groupValues[2] }
                    // Name is the text after the last comma that isn't inside quotes.
                    val name = lastCommaName(i).ifBlank { attrs["tvg-name"] ?: url }
                    val parsed = ParsedChannel(
                        name = name.trim(),
                        url = url,
                        group = (attrs["group-title"] ?: group ?: "Other").ifBlank { "Other" }.trim(),
                        logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") },
                        tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() },
                        userAgent = lineUa ?: attrs["user-agent"],
                        referrer = lineRef,
                    ).let { it.copy(adult = Adult.isAdult(it.group) || attrs["parent-code"] != null) }
                    when {
                        isSeries || (isMovie && episodeRegex.containsMatchIn(parsed.name)) -> if (!liveOnly) episodes += parsed
                        isMovie -> if (!liveOnly) movies += parsed
                        else -> out += parsed
                    }
                }
            }
        }
        return ParsedPlaylist(out, epg.toList(), movies, episodes)
    }

    /** "Show Name S01E02 ..." / "Show Name - S1 E2" */
    val episodeRegex = Regex("""^(.*?)[\s._\-|]*[Ss](\d{1,2})\s*[Ee](\d{1,3})""")

    private fun lastCommaName(info: String): String {
        var inQuotes = false
        var last = -1
        for (i in info.indices) {
            when (info[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) last = i
            }
        }
        return if (last >= 0) info.substring(last + 1) else ""
    }

    /** Stable id: same channel keeps its favorites after a refresh, even if the provider rotates stream tokens. */
    fun ids(channels: List<ParsedChannel>): List<String> {
        val seen = HashMap<String, Int>()
        val md = MessageDigest.getInstance("SHA-1")
        return channels.map { c ->
            val base = "${c.name}|${c.group}"
            val n = seen.merge(base, 1, Int::plus)!!
            md.reset()
            md.digest("$base|$n".toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
