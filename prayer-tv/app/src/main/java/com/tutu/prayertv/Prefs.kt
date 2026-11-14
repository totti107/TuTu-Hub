package com.tutu.prayertv

import android.content.Context

/** A calculation method: Aladhan's id plus the angles used when the TV has to calculate offline. */
data class Method(val id: Int, val name: String, val short: String, val fajr: Double, val isha: Double, val ishaMinutes: Int = 0)

val METHODS = listOf(
    Method(2, "Islamic Society of North America (ISNA)", "ISNA", 15.0, 15.0),
    Method(3, "Muslim World League", "MWL", 18.0, 17.0),
    Method(15, "Moonsighting Committee Worldwide", "Moonsighting", 18.0, 18.0),
    Method(4, "Umm al-Qura, Makkah", "Umm al-Qura", 18.5, 0.0, 90),
    Method(5, "Egyptian General Authority of Survey", "Egyptian", 19.5, 17.5),
    Method(1, "University of Islamic Sciences, Karachi", "Karachi", 18.0, 18.0),
    Method(8, "Gulf Region", "Gulf", 19.5, 0.0, 90),
    Method(13, "Diyanet İşleri Başkanlığı, Turkey", "Diyanet", 18.0, 17.0),
)

fun methodById(id: Int): Method = METHODS.firstOrNull { it.id == id } ?: METHODS[0]

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("prayer", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    var city: String
        get() = str("city", "London")
        set(v) = put("city", v)
    var state: String
        get() = str("state", "Ontario")
        set(v) = put("state", v)
    var country: String
        get() = str("country", "Canada")
        set(v) = put("country", v)
    var lat: Double
        get() = str("lat", "42.9849").toDoubleOrNull() ?: 42.9849
        set(v) = put("lat", v.toString())
    var lng: Double
        get() = str("lng", "-81.2453").toDoubleOrNull() ?: -81.2453
        set(v) = put("lng", v.toString())
    var tz: String
        get() = str("tz", "America/Toronto")
        set(v) = put("tz", v)

    var method: Int
        get() = p.getInt("method", 2)
        set(v) = p.edit().putInt("method", v).apply()
    /** 0 = standard (Shafi'i, Maliki, Hanbali), 1 = Hanafi. */
    var school: Int
        get() = p.getInt("school", 0)
        set(v) = p.edit().putInt("school", v).apply()
    var hadithSeconds: Int
        get() = p.getInt("hadithSeconds", 60)
        set(v) = p.edit().putInt("hadithSeconds", v).apply()
    var use24h: Boolean
        get() = p.getBoolean("use24h", true)
        set(v) = p.edit().putBoolean("use24h", v).apply()

    var hadithSeed: Long
        get() = p.getLong("hadithSeed", 0L)
        set(v) = p.edit().putLong("hadithSeed", v).apply()
    var hadithPos: Int
        get() = p.getInt("hadithPos", 0)
        set(v) = p.edit().putInt("hadithPos", v).apply()

    val placeLabel: String
        get() = listOf(city, state).filter { it.isNotBlank() }.joinToString(", ")
}
