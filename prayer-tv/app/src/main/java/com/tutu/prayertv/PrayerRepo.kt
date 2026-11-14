package com.tutu.prayertv

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One day's six times (Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha) as epoch millis. */
class Day(
    val y: Int, val m: Int, val d: Int,
    val times: LongArray,
    val hijri: String,
    val online: Boolean,
    val fetchedAt: Long,
)

class Place(val lat: Double, val lng: Double, val tz: String)

/** Prayer times from Aladhan (cached per month), or calculated on the TV when offline. All calls block. */
class PrayerRepo(ctx: Context, private val prefs: Prefs) {
    private val cache = ctx.applicationContext.getSharedPreferences("cache", Context.MODE_PRIVATE)

    fun tz(): TimeZone = TimeZone.getTimeZone(prefs.tz)

    fun load(y: Int, m: Int, d: Int): Day {
        val key = "m|$y-$m|${prefs.lat}|${prefs.lng}|${prefs.method}|${prefs.school}"
        var json = cache.getString(key, null)
        var at = cache.getLong("at|$key", 0L)
        if (json == null) {
            json = try { get(calendarUrl(y, m)) } catch (_: Exception) { null }
            if (json != null && isOk(json)) {
                at = System.currentTimeMillis()
                val e = cache.edit()
                // Keep only a few months.
                val old = cache.all.keys.filter { it.startsWith("m|") }.sorted()
                if (old.size > 3) old.take(old.size - 3).forEach { e.remove(it); e.remove("at|$it") }
                e.putString(key, json).putLong("at|$key", at).apply()
            } else json = null
        }
        if (json != null) {
            try { return parse(json, y, m, d, at) } catch (_: Exception) { }
        }
        return calculate(y, m, d)
    }

    private fun isOk(json: String) = try { JSONObject(json).optInt("code") == 200 } catch (_: Exception) { false }

    private fun calendarUrl(y: Int, m: Int) =
        "https://api.aladhan.com/v1/calendar/$y/$m?latitude=${prefs.lat}&longitude=${prefs.lng}" +
            "&method=${prefs.method}&school=${prefs.school}"

    private fun parse(json: String, y: Int, m: Int, d: Int, at: Long): Day {
        val data = JSONObject(json).getJSONArray("data")
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val date = o.getJSONObject("date")
            val greg = date.getJSONObject("gregorian")
            if (greg.getString("day").toInt() != d) continue
            val t = o.getJSONObject("timings")
            val zone = o.optJSONObject("meta")?.optString("timezone")?.takeIf { it.isNotBlank() }
            val tz = if (zone != null) TimeZone.getTimeZone(zone) else tz()
            val times = listOf("Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha").map { name ->
                val hm = t.getString(name).trim().take(5).split(":")
                Calendar.getInstance(tz).apply {
                    clear(); set(y, m - 1, d, hm[0].toInt(), hm[1].toInt(), 0)
                }.timeInMillis
            }.toLongArray()
            val h = date.getJSONObject("hijri")
            val hijri = "${h.getString("day").toInt()} ${h.getJSONObject("month").getString("en")} ${h.getString("year")} AH"
            return Day(y, m, d, times, hijri, true, at)
        }
        throw IllegalStateException("day not found")
    }

    private fun calculate(y: Int, m: Int, d: Int): Day {
        val times = PrayerCalc.times(y, m, d, prefs.lat, prefs.lng, tz(), methodById(prefs.method), prefs.school == 1)
        return Day(y, m, d, times, localHijri(y, m, d), false, System.currentTimeMillis())
    }

    private fun localHijri(y: Int, m: Int, d: Int): String {
        if (Build.VERSION.SDK_INT < 24) return ""
        return try {
            val g = Calendar.getInstance(tz()).apply { clear(); set(y, m - 1, d, 12, 0, 0) }
            val ic = android.icu.util.IslamicCalendar()
            ic.calculationType = android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
            ic.timeInMillis = g.timeInMillis
            val month = HIJRI_MONTHS[ic.get(android.icu.util.Calendar.MONTH).coerceIn(0, 11)]
            "${ic.get(android.icu.util.Calendar.DAY_OF_MONTH)} $month ${ic.get(android.icu.util.Calendar.YEAR)} AH"
        } catch (_: Throwable) { "" }
    }

    /** Looks a place up through Aladhan; returns its coordinates and time zone. */
    fun resolve(city: String, state: String, country: String): Place {
        fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
        var url = "https://api.aladhan.com/v1/timingsByCity?city=${enc(city)}&country=${enc(country)}&method=${prefs.method}"
        if (state.isNotBlank()) url += "&state=${enc(state)}"
        val meta = JSONObject(get(url)).getJSONObject("data").getJSONObject("meta")
        return Place(meta.getDouble("latitude"), meta.getDouble("longitude"), meta.getString("timezone"))
    }

    fun clearCache() { cache.edit().clear().apply() }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "PrayerTV/1.0 (Android TV)")
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    companion object {
        val HIJRI_MONTHS = listOf(
            "Muḥarram", "Ṣafar", "Rabīʿ al-awwal", "Rabīʿ al-thānī", "Jumādá al-ūlá", "Jumādá al-ākhirah",
            "Rajab", "Shaʿbān", "Ramaḍān", "Shawwāl", "Dhū al-Qaʿdah", "Dhū al-Ḥijjah",
        )
        val LOCALE: Locale = Locale.ENGLISH
    }
}
