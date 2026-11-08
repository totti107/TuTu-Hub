package com.arena.tv.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewSportsTest {
    private fun res(n: String) = javaClass.classLoader!!.getResource("espn/$n")!!.readText()

    @Test fun cricketPanel() {
        val league = Leagues.byKey("cricket/all")!!
        val games = EspnParser.parseCricketPanel(res("cricket_panel.json"), league)
        assertTrue(games.size >= 10)
        val g = games.first { it.id == "1561255" }
        assertEquals("Arabian Aces", g.home.displayName)
        assertEquals("114/1", g.home.score)                 // overs / target moved out of the score
        assertTrue(g.note!!.startsWith("Abu Dhabi T10"))     // series name
        assertTrue(g.statusDetail.contains("won by"))        // result line
        assertEquals("cricket/18522", g.apiLeague)           // details come from the series
    }

    @Test fun wrestlingWeeklyShows() {
        val wwe = Leagues.byKey("wrestling/wwe")!!
        // Monday 9 Nov 2026, noon in New York.
        val monday = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York")).apply { set(2026, 10, 9, 12, 0, 0) }.timeInMillis
        val raw = WrestlingSchedule.games(wwe, monday).first { it.title == "WWE Raw" && it.id.endsWith("20261109") }
        val et = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York")).apply { timeInMillis = raw.startMillis }
        assertEquals(20, et.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(3 * 3600_000L, raw.endMillis - raw.startMillis)
        assertFalse(raw.headToHead)
        assertTrue(WrestlingSchedule.games(Leagues.byKey("wrestling/aew")!!, monday).none { it.title!!.startsWith("WWE") })
    }
}
