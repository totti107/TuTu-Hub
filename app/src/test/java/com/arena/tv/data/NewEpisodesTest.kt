package com.arena.tv.data

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NewEpisodesTest {
    private val ne = NewEpisodes(OkHttpClient())

    @Test fun parsesTvSchedule() {
        val js = Json.parseToJsonElement("""[{"id":1,"name":"Reunion Part 1","season":14,"number":20,"type":"regular","airstamp":"2026-11-19T21:00:00-05:00",
            "show":{"id":77,"name":"The Real Housewives of Atlanta","type":"Reality","genres":["Drama"],"weight":88,
            "network":{"name":"Bravo","country":{"code":"US"}},"webChannel":null,"image":{"medium":"http://img/m.jpg"}}}]""")
        val e = ne.parse(js, embedded = false).single()
        assertEquals("The Real Housewives of Atlanta", e.show)
        assertEquals("Bravo", e.network); assertNull(e.streaming)
        assertEquals("S14 E20", e.code); assertEquals("US", e.country)
        assertEquals(1795140000000L, e.airstamp)
    }

    @Test fun parsesWebScheduleWithEmbeddedShow() {
        val js = Json.parseToJsonElement("""[{"id":2,"name":"Pilot","season":1,"number":1,"type":"regular","airstamp":"2026-11-19T08:00:00+00:00",
            "_embedded":{"show":{"id":9,"name":"Nocturne","type":"Scripted","genres":[],"weight":70,"network":null,
            "webChannel":{"name":"Netflix","country":null},"image":null}}}]""")
        val e = ne.parse(js, embedded = true).single()
        assertEquals("Netflix", e.streaming); assertNull(e.network); assertEquals(true, e.premiere)
    }
}
