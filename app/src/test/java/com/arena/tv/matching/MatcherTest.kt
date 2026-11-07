package com.arena.tv.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    private val h = 3600_000L
    private val kickoff = 1_800_000_000_000L
    private val now = kickoff + 30 * 60_000

    private fun game(
        home: List<String>, away: List<String>, league: String = "LaLiga",
        broadcasters: List<String> = emptyList(), rights: List<String> = emptyList(),
        homeAbbr: String? = null, awayAbbr: String? = null,
    ) = MatchGame("g1", "esp.1", "soccer", listOf(league, "La Liga"), MatchTeam(home, homeAbbr), MatchTeam(away, awayAbbr),
        kickoff, isLive = true, broadcasters = broadcasters, rightsHolders = rights)

    private fun prog(key: String, title: String, start: Long = kickoff - 5 * 60_000, len: Long = 2 * h, desc: String? = null) =
        MatchProgram(key, start, start + len, title, description = desc)

    private val channels = listOf(
        MatchChannel("bein1", "AR| beIN SPORTS 1 FHD", "ARABIC SPORTS", "bein1.ar"),
        MatchChannel("bein2", "AR| beIN SPORTS 2 FHD", "ARABIC SPORTS", "bein2.ar"),
        MatchChannel("laliga", "UK: LaLigaTV HD", "UK SPORTS", "laligatv.uk"),
        MatchChannel("premier", "UK: Premier Sports 1 HD", "UK SPORTS", "premier1.uk"),
        MatchChannel("espnplus", "US: ESPN+ 03", "USA EVENTS", null),
        MatchChannel("event5", "LaLiga 05: Real Madrid vs Barcelona @ 21:00", "EVENTS", null),
        MatchChannel("movie", "Movies 24/7", "MOVIES", "movies.uk"),
        MatchChannel("betis", "ES: M+ LaLiga 2", "SPAIN", "mlaliga2.es"),
    )

    @Test fun normalizeStripsAccentsAndPunctuation() {
        assertEquals("atletico madrid fc", Text.normalize("Atlético-Madrid FC"))
        assertEquals("borussia monchengladbach", Text.normalize("Borussia Mönchengladbach"))
    }

    @Test fun channelNameCleanup() {
        assertEquals("bein sports 1", ChannelNames.clean("AR| beIN SPORTS 1 FHD"))
        assertEquals("sky sports main event", ChannelNames.clean("UK: Sky Sports Main Event HD (Backup)"))
        assertEquals(1.0, ChannelNames.broadcasterMatch("bein sports 1", "bein sports 1"), 0.0)
        assertEquals(0.0, ChannelNames.broadcasterMatch("bein sports 2", "bein sports 1"), 0.0)
        assertTrue(ChannelNames.broadcasterMatch("sky sports main event", "sky sports") > 0.5)
        assertEquals(0.0, ChannelNames.broadcasterMatch("sky sport 1", "sport"), 0.0)
    }

    @Test fun guideTitleWithAbbreviatedTeamsMatchesStrongly() {
        val idx = MatchIndex(channels, listOf(
            prog("bein1.ar", "LaLiga: R. Madrid – FC Barcelona (Live)"),
            prog("bein2.ar", "Serie A: Juventus v Napoli"),
            prog("movies.uk", "The Godfather"),
        ))
        val res = Matcher.match(game(listOf("Real Madrid", "Real Madrid"), listOf("Barcelona")), idx, now = now)
        val top = res.first()
        assertTrue("event channel or bein1 first, got ${res.map { it.channelId }}", top.channelId in setOf("bein1", "event5"))
        val bein = res.first { it.channelId == "bein1" }
        assertEquals(Tier.STRONG, bein.tier)
        assertTrue(res.none { it.channelId == "bein2" || it.channelId == "movie" })
    }

    @Test fun eventChannelNameMatches() {
        val idx = MatchIndex(channels, emptyList())
        val res = Matcher.match(game(listOf("Real Madrid"), listOf("Barcelona")), idx, now = now)
        assertEquals("event5", res.first().channelId)
        assertTrue(res.first().score >= 50)
    }

    @Test fun realBetisIsNotRealMadrid() {
        val idx = MatchIndex(channels, listOf(prog("mlaliga2.es", "Real Betis vs Barcelona")))
        val res = Matcher.match(game(listOf("Real Madrid"), listOf("Barcelona")), idx, now = now)
        val m = res.firstOrNull { it.channelId == "betis" }
        assertTrue("Betis game should not look like Madrid game: $m", m == null || m.tier == Tier.USUAL)
    }

    @Test fun replaysAndOtherTimesRankLow() {
        val idx = MatchIndex(channels, listOf(
            prog("bein1.ar", "Real Madrid v Barcelona - Highlights"),
            prog("bein2.ar", "Real Madrid v Barcelona", start = kickoff + 26 * h), // tomorrow's replay
        ))
        val res = Matcher.match(game(listOf("Real Madrid"), listOf("Barcelona")), idx, now = now)
        assertTrue(res.none { it.channelId == "bein2" })
        assertTrue(res.none { it.channelId == "bein1" && it.tier == Tier.STRONG })
    }

    @Test fun womenGameDoesNotMatchMenGame() {
        val idx = MatchIndex(channels, listOf(prog("bein1.ar", "Real Madrid Women vs Barcelona Women")))
        val res = Matcher.match(game(listOf("Real Madrid"), listOf("Barcelona")), idx, now = now)
        assertTrue(res.none { it.channelId == "bein1" && it.tier != Tier.USUAL })
    }

    @Test fun broadcasterListFindsNumberedChannel() {
        val idx = MatchIndex(channels, emptyList())
        val res = Matcher.match(game(listOf("Sevilla"), listOf("Valencia"), broadcasters = listOf("beIN SPORTS 1")), idx, now = now)
        assertEquals("bein1", res.first().channelId)
        assertEquals(Tier.LIKELY, res.first().tier)
        assertFalse(res.any { it.channelId == "bein2" })
    }

    @Test fun rightsHoldersAreAWeakHint() {
        val idx = MatchIndex(channels, emptyList())
        val res = Matcher.match(game(listOf("Sevilla"), listOf("Valencia"), rights = listOf("Premier Sports", "LaLigaTV")), idx, now = now)
        assertTrue(res.isNotEmpty())
        assertTrue(res.all { it.tier == Tier.USUAL })
    }

    @Test fun arabicGuideMatches() {
        val idx = MatchIndex(channels, listOf(prog("bein1.ar", "الدوري الإسباني: ريال مدريد × برشلونة مباشر")))
        val res = Matcher.match(game(listOf("Real Madrid"), listOf("Barcelona")), idx, now = now)
        assertEquals(Tier.STRONG, res.first { it.channelId == "bein1" }.tier)
    }

    @Test fun nbaShortNamesMatch() {
        val ch = listOf(MatchChannel("tnt", "US: TNT HD", "USA", "tnt.us"))
        val idx = MatchIndex(ch, listOf(prog("tnt.us", "NBA Basketball: Lakers at Celtics")))
        val g = MatchGame("n1", "nba", "basketball", listOf("NBA"),
            MatchTeam(listOf("Boston Celtics", "Celtics", "Boston"), "BOS"),
            MatchTeam(listOf("Los Angeles Lakers", "Lakers", "Los Angeles"), "LAL"),
            kickoff, true)
        val res = Matcher.match(g, idx, now = now)
        assertEquals(Tier.STRONG, res.first().tier)
    }

    @Test fun feedbackPinsAndHides() {
        val idx = MatchIndex(channels, listOf(prog("bein1.ar", "Real Madrid v Barcelona")))
        val res = Matcher.match(game(listOf("Real Madrid"), listOf("Barcelona")), idx,
            MatchFeedback(confirmed = setOf("movie"), rejected = setOf("bein1")), now = now)
        assertEquals("movie", res.first().channelId)
        assertEquals(Tier.CONFIRMED, res.first().tier)
        assertTrue(res.none { it.channelId == "bein1" })
    }

    @Test fun espnAbbreviationsAndWrongSportChannels() {
        val ch = listOf(
            MatchChannel("usa", "US: USA Network", "USA", null),
            MatchChannel("skyf1", "UK: Sky Sports F1 HD", "UK", null),
            MatchChannel("skyme", "UK: Sky Sports Main Event", "UK", null),
        )
        val idx = MatchIndex(ch, emptyList())
        val res = Matcher.match(game(listOf("Everton"), listOf("Chelsea"), broadcasters = listOf("USA Net"), rights = listOf("Sky Sports")), idx, now = now)
        assertEquals("usa", res.first().channelId)
        assertTrue(res.none { it.channelId == "skyf1" })
        assertTrue(res.any { it.channelId == "skyme" })
    }

    @Test fun strayOneTeamMentionsAreHidden() {
        val idx = MatchIndex(channels, listOf(prog("movies.uk", "Barcelona: A Travel Guide")))
        val res = Matcher.match(game(listOf("Real Madrid"), listOf("Barcelona")), idx, now = now)
        assertTrue(res.none { it.channelId == "movie" })
    }

    @Test fun eventNamesMatchForFieldSports() {
        val ch = listOf(MatchChannel("f1", "UK: Sky Sports F1 HD", "UK", "skyf1"), MatchChannel("golf", "UK: Sky Sports Golf", "UK", "golf"))
        val idx = MatchIndex(ch, listOf(prog("skyf1", "Live F1: The Sao Paulo Grand Prix"), prog("golf", "Live PGA Tour Golf: The RSM Classic")))
        val g = MatchGame("r", "racing/f1", "racing", listOf("Formula 1"), MatchTeam(emptyList()), MatchTeam(emptyList()), kickoff, true,
            eventNames = listOf("MSC Cruises São Paulo Grand Prix"))
        val res = Matcher.match(g, idx, now = now)
        assertEquals("f1", res.first().channelId)
        assertEquals(Tier.STRONG, res.first().tier)
        assertTrue(res.none { it.channelId == "golf" })
    }

    @Test fun fightersMatchBySurname() {
        val ch = listOf(MatchChannel("ppv", "PPV 03: UFC Fight Night - Walker vs Cortes Acosta", "PPV", null))
        val idx = MatchIndex(ch, emptyList())
        val g = MatchGame("u", "mma/ufc", "mma", listOf("UFC"),
            MatchTeam(listOf("Johnny Walker", "J. Walker", "Walker")), MatchTeam(listOf("Waldo Cortes Acosta", "W. Cortes Acosta", "Acosta")),
            kickoff, true, eventNames = listOf("UFC Fight Night: Cortes Acosta vs. Walker"))
        val res = Matcher.match(g, idx, now = now)
        assertEquals("ppv", res.first().channelId)
        assertTrue(res.first().score >= 60)
    }

    @Test fun searchTermsAreDistinctiveWords() {
        val g = MatchGame("n", "nba", "basketball", listOf("NBA"),
            MatchTeam(listOf("Philadelphia 76ers", "76ers"), "PHI"), MatchTeam(listOf("Cleveland Cavaliers", "Cavaliers"), "CLE"),
            kickoff, false, broadcasters = listOf("NBC"))
        val t = Matcher.searchTerms(g)
        assertTrue("76ers" in t.words && "sixers" in t.words && "cavaliers" in t.words && "cleveland" in t.words)
        assertTrue("nbc" in t.broadcasterWords)
        val u = Matcher.searchTerms(game(listOf("Manchester United", "Man United"), listOf("Manchester City", "Man City")))
        assertFalse("united" in u.words || "city" in u.words || "man" in u.words)
        assertTrue("manchester" in u.words)
    }
}
