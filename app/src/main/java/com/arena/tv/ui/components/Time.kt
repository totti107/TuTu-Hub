package com.arena.tv.ui.components

import java.text.DateFormat
import java.util.Date

object Time {
    /** "system", "12" or "24" (Settings → Browsing). */
    @Volatile var clockPref: String = "system"
    fun clock(millis: Long): String = when (clockPref) {
        "12" -> java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(Date(millis))
        "24" -> java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(Date(millis))
        else -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    }
    fun range(a: Long, b: Long) = "${clock(a)} – ${clock(b)}"
    fun progress(start: Long, stop: Long, now: Long = System.currentTimeMillis()): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}
