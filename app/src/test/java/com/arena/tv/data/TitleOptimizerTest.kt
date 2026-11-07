package com.arena.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleOptimizerTest {
    private val on = TitleOptimizer.Config(enabled = true)

    @Test fun stripsQualityTagsInAnyWrapping() {
        assertEquals("Sky Sports Main Event", TitleOptimizer.clean("Sky Sports Main Event FHD HEVC", on))
        assertEquals("beIN SPORTS 1", TitleOptimizer.clean("beIN SPORTS 1 (4K) ⚽", on))
        assertEquals("MBC 2", TitleOptimizer.clean("MBC 2 [HD] VIP", on))
        assertEquals("TNT Sports 1", TitleOptimizer.clean("TNT Sports 1 |UHD|", on))
        assertEquals("Canal+ Sport", TitleOptimizer.clean("Canal+ Sport ᴴᴰ", on))
    }

    @Test fun keepsWordsThatOnlyContainATag() {
        // "HD" inside a word, or names that are only tags, stay as they are.
        assertEquals("HDTV Classics", TitleOptimizer.clean("HDTV Classics", on))
        assertEquals("Shadow", TitleOptimizer.clean("Shadow", on))
        assertEquals("4K", TitleOptimizer.clean("4K", on))
    }

    @Test fun countryPrefixesOnlyWhenAsked() {
        assertEquals("|UK| Sky Sports News", TitleOptimizer.clean("|UK| Sky Sports News HD", on))
        val c = on.copy(country = true)
        assertEquals("Sky Sports News", TitleOptimizer.clean("|UK| Sky Sports News HD", c))
        assertEquals("MBC 2", TitleOptimizer.clean("AR: MBC 2", c))
        assertEquals("Movie Night", TitleOptimizer.clean("[EN] Movie Night", c))
        assertEquals("BBC One", TitleOptimizer.clean("BBC One", c))
    }

    @Test fun offMeansUntouched() {
        assertEquals("Sky Sports FHD", TitleOptimizer.clean("Sky Sports FHD", TitleOptimizer.Config(enabled = false)))
    }

    @Test fun customWords() {
        val c = on.copy(words = listOf("Backup", "+1"))
        assertEquals("ESPN", TitleOptimizer.clean("ESPN Backup", c))
        assertEquals("Channel 4", TitleOptimizer.clean("Channel 4 +1", c))
    }
}
