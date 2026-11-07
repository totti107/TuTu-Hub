package com.arena.tv.ui.components

import java.text.DateFormat
import java.util.Date

object Time {
    fun clock(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    fun range(a: Long, b: Long) = "${clock(a)} – ${clock(b)}"
    fun progress(start: Long, stop: Long, now: Long = System.currentTimeMillis()): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}
