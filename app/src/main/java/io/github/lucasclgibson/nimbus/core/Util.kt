package io.github.lucasclgibson.nimbus.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val MINUTE = 60_000L
const val HOUR = 60 * MINUTE
const val DAY = 24 * HOUR

fun now(): Long = System.currentTimeMillis()

private val zone: ZoneId get() = ZoneId.systemDefault()
private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
private val longDayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

fun timeOf(ms: Long): String = hhmm.format(Instant.ofEpochMilli(ms).atZone(zone))

fun dayLabel(ms: Long, long: Boolean = false): String {
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> (if (long) longDayFmt else dayFmt).format(d)
    }
}

/** "Today 20:00 – 21:00" */
fun slotLabel(start: Long, end: Long): String = "${dayLabel(start)} ${timeOf(start)} – ${timeOf(end)}"

fun floorTo(ms: Long, step: Long): Long {
    val offset = zone.rules.getOffset(Instant.ofEpochMilli(ms)).totalSeconds * 1000L
    return Math.floorDiv(ms + offset, step) * step - offset
}

fun startOfDay(ms: Long): Long =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

fun durationLabel(ms: Long): String {
    val totalMin = (ms / MINUTE).toInt()
    if (totalMin <= 0) return ""
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}

fun clockLabel(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Loose channel-name normalisation used to match playlist channels against XMLTV display names. */
fun normaliseChannelName(name: String): String {
    var s = name.lowercase(Locale.ROOT)
    s = s.replace(Regex("^\\s*[\\[(][^\\])]{1,8}[\\])]\\s*[:|\\-]?\\s*"), "") // "[UK] ", "(UK) | "
    s = s.replace(Regex("^\\s*[a-z]{2,3}\\s*[:|\\-]\\s*"), "") // "UK: ", "UK | "
    s = s.replace(Regex("\\b(fhd|uhd|hd|sd|4k|hevc|h265|1080p|720p|50fps|60fps|backup|raw)\\b"), "")
    s = s.replace("&", "and").replace("+", "plus")
    return s.filter { it.isLetterOrDigit() }
}

private val yearRegex = Regex("""[\(\[]((19|20)\d{2})[\)\]]""")

/** Pull a release year out of a title like "Dune (2021)". */
fun yearFromTitle(title: String): Int = yearRegex.find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0

/** Strip provider noise from VOD titles: "EN | Dune (2021) [4K]" -> "Dune". */
fun cleanTitle(title: String): String {
    var s = title.trim()
    // Provider tags are upper-case codes: "EN | ", "4K-D+ - ", "[UK] ", "NF: " (never "X-Men - …").
    s = s.replace(Regex("^\\s*[\\[(][A-Z0-9+\\- ]{1,8}[\\])]\\s*[|:\\-–]?\\s*"), "")
    s = s.replace(Regex("^\\s*[A-Z0-9+]{1,4}(?:-[A-Z0-9+]{1,5}){0,3}\\s*[|:\\-–]\\s+"), "")
    s = s.replace(yearRegex, "")
    s = s.replace(Regex("\\[[^\\]]*\\]"), "")
    s = s.replace(Regex("\\s{2,}"), " ")
    return s.trim().trimEnd('-', '|', ':').trim().ifEmpty { title.trim() }
}

fun String.ifBlankNull(): String? = ifBlank { null }

/** Providers insert fake channels like "#### UK SPORTS ####" as section dividers; they aren't playable. */
fun isSeparatorChannel(name: String): Boolean {
    val t = name.trim()
    if (t.length < 2) return true
    val first2 = t.take(2)
    return first2.all { it in "#=*~_-|•●▬■★<>+" } && first2[0] == first2[1]
}

/** "UK: BBC One HD" -> "BBC One HD" for tight spaces (guide rows, banners). */
fun displayChannelName(name: String): String =
    name.replace(Regex("^\\s*[A-Z]{2,3}\\s*[:|]\\s*"), "").trim().ifEmpty { name.trim() }

/** Rewrite TMDB image URLs to a sensible size so posters don't download at 2000px. */
fun tmdbSized(url: String, size: String): String {
    if (!url.contains("image.tmdb.org/t/p/")) return url
    return url.replace(Regex("/t/p/[^/]+/"), "/t/p/$size/")
}
