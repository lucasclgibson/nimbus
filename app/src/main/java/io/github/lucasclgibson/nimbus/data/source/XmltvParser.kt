package io.github.lucasclgibson.nimbus.data.source

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

class XmltvProgramme(
    @JvmField var channel: String = "",
    @JvmField var start: Long = 0,
    @JvmField var stop: Long = 0,
    @JvmField var title: String = "",
    @JvmField var subtitle: String = "",
    @JvmField var description: String = "",
    @JvmField var category: String = "",
    @JvmField var episode: String = "",
    @JvmField var icon: String = "",
)

interface XmltvSink {
    fun channel(id: String, names: List<String>, icon: String)
    fun wants(channelId: String): Boolean
    fun programme(p: XmltvProgramme)
}

/** Streaming XMLTV reader. Handles gzip transparently and tolerates truncated/dirty feeds. */
object XmltvParser {

    fun maybeGunzip(input: InputStream): InputStream {
        val buffered = if (input.markSupported()) input else BufferedInputStream(input, 64 * 1024)
        buffered.mark(4)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024) else buffered
    }

    /** @return number of programmes delivered to the sink */
    fun parse(raw: InputStream, sink: XmltvSink): Int {
        val input = maybeGunzip(raw)
        val parser = Xml.newPullParser()
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        var count = 0
        val p = XmltvProgramme()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "channel" -> readChannel(parser, sink)
                        "programme" -> {
                            val ch = parser.getAttributeValue(null, "channel").orEmpty()
                            if (sink.wants(ch)) {
                                if (readProgramme(parser, ch, p)) {
                                    sink.programme(p)
                                    count++
                                }
                            } else {
                                skip(parser)
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // Truncated or malformed feeds are common; keep whatever parsed cleanly.
            if (count == 0) throw e
        }
        return count
    }

    private fun readChannel(parser: XmlPullParser, sink: XmltvSink) {
        val id = parser.getAttributeValue(null, "id").orEmpty()
        val names = ArrayList<String>(2)
        var icon = ""
        val depth = parser.depth
        while (true) {
            val e = parser.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && parser.depth == depth) break
            if (e == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "display-name" -> names += readText(parser)
                    "icon" -> icon = parser.getAttributeValue(null, "src").orEmpty()
                }
            }
        }
        if (id.isNotEmpty()) sink.channel(id, names, icon)
    }

    private fun readProgramme(parser: XmlPullParser, channel: String, p: XmltvProgramme): Boolean {
        p.channel = channel
        p.start = parseTime(parser.getAttributeValue(null, "start"))
        p.stop = parseTime(parser.getAttributeValue(null, "stop"))
        p.title = ""; p.subtitle = ""; p.description = ""; p.category = ""; p.episode = ""; p.icon = ""
        var episodeOnscreen = ""
        val depth = parser.depth
        while (true) {
            val e = parser.next()
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && parser.depth == depth) break
            if (e == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> if (p.title.isEmpty()) p.title = readText(parser)
                    "sub-title" -> if (p.subtitle.isEmpty()) p.subtitle = readText(parser)
                    "desc" -> if (p.description.isEmpty()) p.description = readText(parser)
                    "category" -> if (p.category.isEmpty()) p.category = readText(parser)
                    "episode-num" -> {
                        val system = parser.getAttributeValue(null, "system")
                        val text = readText(parser)
                        if (system == "onscreen") episodeOnscreen = text
                        else if (system == "xmltv_ns" && p.episode.isEmpty()) p.episode = xmltvNs(text)
                    }
                    "icon" -> if (p.icon.isEmpty()) p.icon = parser.getAttributeValue(null, "src").orEmpty()
                }
            }
        }
        if (episodeOnscreen.isNotEmpty()) p.episode = episodeOnscreen
        return p.start > 0 && p.stop > p.start
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        while (true) {
            val e = parser.next()
            if (e == XmlPullParser.TEXT || e == XmlPullParser.CDSECT) sb.append(parser.text)
            if (e == XmlPullParser.END_DOCUMENT) break
            if (e == XmlPullParser.END_TAG && parser.depth == depth) break
        }
        return sb.toString().trim()
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** xmltv_ns "1.4.0/1" (zero-based) -> "S2 E5" */
    private fun xmltvNs(s: String): String {
        val parts = s.split('.')
        val season = parts.getOrNull(0)?.substringBefore('/')?.trim()?.toIntOrNull()
        val ep = parts.getOrNull(1)?.substringBefore('/')?.trim()?.toIntOrNull()
        return when {
            season != null && ep != null -> "S${season + 1} E${ep + 1}"
            ep != null -> "E${ep + 1}"
            else -> ""
        }
    }

    /** "20240101120000 +0100" (seconds and offset optional) -> epoch ms, or 0. */
    fun parseTime(s: String?): Long {
        if (s == null || s.length < 12) return 0
        return try {
            var i = 0
            fun num(n: Int): Int {
                var v = 0
                repeat(n) {
                    val c = s[i++]
                    if (c !in '0'..'9') throw NumberFormatException()
                    v = v * 10 + (c - '0')
                }
                return v
            }
            val y = num(4); val mo = num(2); val d = num(2); val h = num(2); val mi = num(2)
            val sec = if (s.length >= 14 && s[12].isDigit() && s[13].isDigit()) num(2) else 0
            while (i < s.length && s[i] == ' ') i++
            var offset = 0
            if (i < s.length && (s[i] == '+' || s[i] == '-')) {
                val sign = if (s[i] == '+') 1 else -1
                i++
                val oh = num(2)
                if (i < s.length && s[i] == ':') i++
                val om = if (i + 2 <= s.length) num(2) else 0
                offset = sign * (oh * 3600 + om * 60)
            }
            val days = daysFromCivil(y, mo, d)
            (days * 86400L + h * 3600L + mi * 60L + sec - offset) * 1000L
        } catch (e: Exception) {
            0
        }
    }

    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }
}
