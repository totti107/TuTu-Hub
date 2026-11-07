package com.arena.tv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EspnParserTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource("espn/$name")!!.readText()
    private val ucl = Leagues.byKey("soccer/uefa.champions")!!
    private val nba = Leagues.byKey("basketball/nba")!!

    @Test fun soccerScoreboard() {
        val games = EspnParser.parseScoreboard(res("soccer_scoreboard.json"), ucl)
        assertEquals(3, games.size)
        val g = games.first()
        assertEquals("AEK Athens", g.home.displayName)
        assertEquals("Real Madrid", g.away.displayName)
        assertEquals(GameState.POST, g.state)
        assertTrue(g.startMillis > 0)
        assertTrue(g.home.logo!!.startsWith("https://"))
    }

    @Test fun soccerSummary() {
        val d = EspnParser.parseSummary(res("soccer_summary.json"), "887")
        val poss = d.stats.first()
        assertEquals("Possession", poss.label)
        assertEquals("30.4%", poss.home)
        assertTrue(d.events.any { it.kind == EventKind.GOAL && it.isHome == true })
        assertTrue(d.broadcasts.contains("Paramount+"))
    }

    @Test fun nbaScoreboardAndSummary() {
        val games = EspnParser.parseScoreboard(res("nba_scoreboard.json"), nba)
        val g = games.first()
        assertEquals("Detroit Pistons", g.home.displayName)
        assertTrue(g.broadcasts.contains("ESPN"))
        assertEquals(4, g.home.periodScores.size)
        val d = EspnParser.parseSummary(res("nba_summary.json"), g.home.id)
        assertTrue(d.stats.any { it.label == "FG" })
    }

    @Test fun ufcCardHeadlinesMainEvent() {
        val g = EspnParser.parseScoreboard(res("mma_scoreboard.json"), Leagues.byKey("mma/ufc")!!).single()
        assertTrue(g.title!!.startsWith("UFC"))
        assertEquals("Johnny Walker", g.home.displayName)
        assertTrue(g.home.isAthlete)
        assertTrue(g.bouts.size >= 10)
        assertEquals("Johnny Walker", g.bouts.first().a)
    }

    @Test fun golfIsALeaderboard() {
        val g = EspnParser.parseScoreboard(res("golf_scoreboard.json"), Leagues.byKey("golf/pga")!!).single()
        assertTrue(!g.headToHead)
        assertEquals("Chris Kirk", g.field.first().name)
        assertEquals("-9", g.field.first().score)
        assertTrue(g.endMillis > g.startMillis)
    }

    @Test fun tennisMatchesAndF1Sessions() {
        val t = EspnParser.parseScoreboard(res("tennis_scoreboard.json"), Leagues.byKey("tennis/atp")!!)
        assertTrue(t.size >= 2)
        val m = t.first()
        assertEquals("Pablo Carreno Busta", m.home.displayName)
        assertEquals("2", m.away.score) // Bellucci won in two sets
        val f1 = EspnParser.parseScoreboard(res("f1_scoreboard.json"), Leagues.byKey("racing/f1")!!)
        assertEquals(5, f1.size)
        assertEquals("Race", f1.last().note)
        assertTrue(f1.last().title!!.contains("Grand Prix"))
    }

    @Test fun soccerLineupsAndTable() {
        val d = EspnParser.parseSummary(res("soccer_summary_full.json"), "887")
        assertTrue(d.homeLineup.count { it.starter } >= 11)
        assertTrue(d.table.size >= 10)
        assertEquals(1, d.table.first().rank)
    }

    @Test fun oddsAreParsedAndConverted() {
        val g = EspnParser.parseScoreboard(res("laliga_odds_scoreboard.json"), Leagues.byKey("soccer/esp.1")!!).first()
        val o = g.odds!!
        assertEquals("DraftKings", o.provider)
        assertTrue(o.home != null && o.draw != null && o.away != null)
        assertEquals("2.50", Odds.format(150, decimal = true))
        assertEquals("1.50", Odds.format(-200, decimal = true))
        assertEquals("+150", Odds.format(150, decimal = false))
        assertEquals("-200", Odds.format(-200, decimal = false))
        assertEquals(40, Odds.implied(150))
    }

    @Test fun matchAnalysisFromSummary() {
        val d = EspnParser.parseSummary(res("soccer_summary_full.json"), "887")
        assertEquals(5, d.homeForm.size)
        assertTrue(d.homeForm.all { it.result in setOf("W", "D", "L") })
        assertTrue(d.awayForm.isNotEmpty())
        assertTrue(d.leaders.any { it.isHome } && d.leaders.any { !it.isHome })
        assertTrue(d.h2h.isNotEmpty())
        assertTrue(d.odds != null && d.odds!!.away!! < 0) // Real Madrid were favourites
    }
}
