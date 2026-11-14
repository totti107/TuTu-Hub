package com.tutu.prayertv

import android.content.Context
import org.json.JSONArray
import kotlin.random.Random

/** A saying of the Prophet ﷺ from Sahih Muslim: Arabic words of the Prophet, the full English narration, number and book. */
class Hadith(val number: String, val book: String, val arabic: String, val english: String)

/**
 * Bundled Sahih Muslim hadiths in a shuffled order that carries on where it left off, so every one comes up
 * before any repeats.
 */
class Hadiths(ctx: Context, private val prefs: Prefs) {
    private val all: List<Hadith> = try {
        val text = ctx.assets.open("hadith.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val arr = JSONArray(text)
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Hadith(o.getString("n"), o.getString("b"), o.getString("ar"), o.getString("en"))
        }
    } catch (_: Exception) { emptyList() }

    private val order: IntArray

    init {
        var seed = prefs.hadithSeed
        if (seed == 0L) { seed = Random.nextLong(1, Long.MAX_VALUE); prefs.hadithSeed = seed }
        order = all.indices.shuffled(Random(seed)).toIntArray()
    }

    val isEmpty get() = all.isEmpty()

    fun current(): Hadith? = if (all.isEmpty()) null else all[order[prefs.hadithPos.mod(all.size)]]

    fun next(): Hadith? { advance(1); return current() }
    fun previous(): Hadith? { advance(-1); return current() }

    private fun advance(by: Int) {
        if (all.isEmpty()) return
        var p = prefs.hadithPos + by
        if (p >= all.size) {
            // Went through them all: reshuffle for the next round.
            prefs.hadithSeed = Random.nextLong(1, Long.MAX_VALUE)
            val fresh = all.indices.shuffled(Random(prefs.hadithSeed)).toIntArray()
            fresh.copyInto(order)
            p = 0
        }
        if (p < 0) p = all.size - 1
        prefs.hadithPos = p
    }
}
