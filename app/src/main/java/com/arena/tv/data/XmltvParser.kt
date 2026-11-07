package com.arena.tv.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone

/**
 * Streaming XMLTV parser. Guides can be hundreds of MB, so nothing is held in memory:
 * channels are reported first, then programmes are pushed to [onProgram] one by one.
 */
class XmltvParser(
    private val onChannel: (id: String, names: List<String>) -> Unit,
    private val onFirstProgram: () -> Unit,
    private val onProgram: (ProgramEntity) -> Unit,
) {
    fun parse(input: InputStream) {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var sawProgram = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> readChannel(p)
                    "programme" -> {
                        if (!sawProgram) { sawProgram = true; onFirstProgram() }
                        readProgramme(p)?.let(onProgram)
                    }
                }
            }
            ev = p.next()
        }
        if (!sawProgram) onFirstProgram()
    }

    private fun readChannel(p: XmlPullParser) {
        val id = p.getAttributeValue(null, "id") ?: return
        val names = ArrayList<String>(2)
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == depth)) break
            if (ev == XmlPullParser.START_TAG && p.name == "display-name") names += p.nextText().trim()
        }
        onChannel(id, names)
    }

    private fun readProgramme(p: XmlPullParser): ProgramEntity? {
        val ch = p.getAttributeValue(null, "channel")
        val start = parseTime(p.getAttributeValue(null, "start"))
        val stop = parseTime(p.getAttributeValue(null, "stop"))
        var title: String? = null
        var sub: String? = null
        var desc: String? = null
        var cat: String? = null
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.depth == depth)) break
            if (ev == XmlPullParser.START_TAG) when (p.name) {
                "title" -> if (title == null) title = p.nextText().trim()
                "sub-title" -> if (sub == null) sub = p.nextText().trim()
                "desc" -> if (desc == null) desc = p.nextText().trim().take(400)
                "category" -> if (cat == null) cat = p.nextText().trim()
            }
        }
        if (ch == null || start == null || title.isNullOrEmpty()) return null
        return ProgramEntity(epgKey = ch.lowercase(), start = start, stop = stop ?: (start + 3600_000),
            title = title!!, subtitle = sub?.ifEmpty { null }, description = desc?.ifEmpty { null }, category = cat?.ifEmpty { null })
    }

    companion object {
        /** "20261105193000 +0100" -> epoch millis. Offset is optional (UTC assumed). */
        fun parseTime(s: String?): Long? {
            if (s == null || s.length < 12) return null
            return try {
                val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                c.clear()
                c.set(s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(),
                    s.substring(8, 10).toInt(), s.substring(10, 12).toInt(), if (s.length >= 14 && s[12].isDigit()) s.substring(12, 14).toInt() else 0)
                var t = c.timeInMillis
                val off = s.substringAfter(' ', "").trim()
                if (off.length >= 5 && (off[0] == '+' || off[0] == '-')) {
                    val mins = off.substring(1, 3).toInt() * 60 + off.substring(3, 5).toInt()
                    t -= (if (off[0] == '+') 1 else -1) * mins * 60_000L
                }
                t
            } catch (_: Exception) { null }
        }
    }
}
