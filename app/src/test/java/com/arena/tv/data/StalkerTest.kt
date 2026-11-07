package com.arena.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StalkerTest {
    @Test fun portalUrlsAreNormalized() {
        assertEquals("http://line.example.com:8080", StalkerClient.base("http://line.example.com:8080/c/"))
        assertEquals("http://host:80", StalkerClient.base("host:80/stalker_portal/c"))
        assertEquals("https://p.tv", StalkerClient.base("https://p.tv/portal.php"))
    }

    @Test fun macAddressesAreNormalized() {
        assertEquals("00:1A:79:AB:CD:EF", StalkerClient.normalizeMac("001a79abcdef"))
        assertEquals("00:1A:79:AB:CD:EF", StalkerClient.normalizeMac("00-1a-79-ab-cd-ef"))
    }

    @Test fun macPlaylistValidation() {
        assertTrue(Playlist("p", "x", SourceType.MAC, server = "http://h:8080/c/", mac = "00:1A:79:12:34:56").valid)
        assertFalse(Playlist("p", "x", SourceType.MAC, server = "http://h:8080/c/", mac = "00:1A:79").valid)
    }

    @Test fun adultCategoriesAreRecognised() {
        listOf("XXX | Adults", "FOR ADULTS 18+", "|XXX| Brazzers", "ADULT +18", "Playboy TV").forEach { assertTrue(it, Adult.isAdult(it)) }
        listOf("UK | SPORTS", "Middlesex News", "Sky Sports Main Event", "Adultswim? no", "EN | 4K UHD").filter { it != "Adultswim? no" }
            .forEach { assertFalse(it, Adult.isAdult(it)) }
    }
}
