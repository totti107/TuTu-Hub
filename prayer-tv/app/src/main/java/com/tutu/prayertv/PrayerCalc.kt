package com.tutu.prayertv

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Offline prayer-time calculation (the classic PrayTimes algorithm), used only when Aladhan can't be reached.
 * Returns epoch millis for Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha.
 */
object PrayerCalc {
    private fun rad(d: Double) = d * Math.PI / 180.0
    private fun deg(r: Double) = r * 180.0 / Math.PI
    private fun dsin(d: Double) = sin(rad(d))
    private fun dcos(d: Double) = cos(rad(d))
    private fun dtan(d: Double) = tan(rad(d))
    private fun darcsin(x: Double) = deg(asin(x))
    private fun darccos(x: Double) = deg(acos(x.coerceIn(-1.0, 1.0)))
    private fun darctan2(y: Double, x: Double) = deg(atan2(y, x))
    private fun darccot(x: Double) = deg(atan(1.0 / x))
    private fun fix(a: Double, b: Double): Double { val r = a - b * floor(a / b); return if (r < 0) r + b else r }

    private fun julian(y: Int, m: Int, d: Int): Double {
        var yy = y; var mm = m
        if (mm <= 2) { yy -= 1; mm += 12 }
        val a = floor(yy / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (yy + 4716)) + floor(30.6001 * (mm + 1)) + d + b - 1524.5
    }

    /** Returns (declination, equation of time) for a julian date. */
    private fun sun(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0)
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = fix(darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0, 24.0)
        val eqt = q / 15.0 - ra
        val decl = darcsin(dsin(e) * dsin(l))
        return decl to eqt
    }

    fun times(y: Int, m: Int, d: Int, lat: Double, lng: Double, tz: TimeZone, method: Method, hanafi: Boolean): LongArray {
        val jDate = julian(y, m, d) - lng / (15 * 24.0)
        fun midDay(t: Double): Double = fix(12 - sun(jDate + t / 24.0).second, 24.0)
        fun angleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sun(jDate + t / 24.0).first
            val noon = midDay(t)
            val tt = darccos((-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))) / 15.0
            return noon + if (ccw) -tt else tt
        }
        fun asrTime(factor: Double, t: Double): Double {
            val decl = sun(jDate + t / 24.0).first
            val angle = -darccot(factor + dtan(abs(lat - decl)))
            return angleTime(angle, t, false)
        }

        val fajr = angleTime(method.fajr, 5.0, true)
        val sunrise = angleTime(0.833, 6.0, true)
        val dhuhr = midDay(12.0)
        val asr = asrTime(if (hanafi) 2.0 else 1.0, 13.0)
        val maghrib = angleTime(0.833, 18.0, false)
        val isha = if (method.ishaMinutes > 0) maghrib + method.ishaMinutes / 60.0 else angleTime(method.isha, 18.0, false)

        val cal = Calendar.getInstance(tz).apply { clear(); set(y, m - 1, d, 12, 0, 0) }
        val offsetHours = tz.getOffset(cal.timeInMillis) / 3_600_000.0
        val adjust = offsetHours - lng / 15.0

        return doubleArrayOf(fajr, sunrise, dhuhr, asr, maghrib, isha).map { h ->
            val local = h + adjust
            val minutes = Math.round(local * 60).toInt()
            Calendar.getInstance(tz).apply {
                clear(); set(y, m - 1, d, 0, 0, 0); add(Calendar.MINUTE, minutes)
            }.timeInMillis
        }.toLongArray()
    }
}
