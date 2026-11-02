package io.github.lucasclgibson.nimbus.data.source

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

data class M3uEntry(
    val name: String,
    val url: String,
    val attrs: Map<String, String>,
    val group: String,
    val userAgent: String,
    val referrer: String,
) {
    fun attr(vararg keys: String): String {
        for (k in keys) attrs[k]?.takeIf { it.isNotBlank() }?.let { return it.trim() }
        return ""
    }
}

/** Streaming M3U/M3U8 parser – never holds the whole (often 50 MB+) playlist in memory. */
object M3uParser {

    fun parse(input: InputStream, onHeader: (Map<String, String>) -> Unit, onEntry: (M3uEntry) -> Unit) {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), 64 * 1024)
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var group = ""
        var ua = ""
        var ref = ""
        var first = true
        while (true) {
            var line = reader.readLine() ?: break
            if (first) {
                line = line.removePrefix("﻿")
                first = false
            }
            line = line.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> onHeader(parseAttrs(line.substring(7)))
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val body = line.substringAfter(':', "")
                    val comma = firstUnquotedComma(body)
                    val head = if (comma >= 0) body.substring(0, comma) else body
                    attrs = parseAttrs(head)
                    name = if (comma >= 0) body.substring(comma + 1).trim() else ""
                    group = attrs["group-title"].orEmpty()
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    if (group.isBlank()) group = line.substring(8).trim()
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substring(11).trim()
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-user-agent" -> ua = value
                        "http-referrer", "http-referer" -> ref = value
                    }
                }
                line.startsWith("#") -> Unit // #KODIPROP, #EXTBYT and friends
                else -> {
                    var url = line
                    // Kodi style "url|User-Agent=x&Referer=y"
                    val pipe = url.indexOf('|')
                    if (pipe > 0) {
                        url.substring(pipe + 1).split('&').forEach { kv ->
                            val k = kv.substringBefore('=').trim().lowercase()
                            val v = java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                            when (k) {
                                "user-agent" -> ua = v
                                "referer", "referrer" -> ref = v
                            }
                        }
                        url = url.substring(0, pipe)
                    }
                    val title = name?.takeIf { it.isNotBlank() } ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() } ?: url
                    onEntry(M3uEntry(title, url, attrs, group.trim(), ua, ref))
                    name = null
                    attrs = emptyMap()
                    group = ""
                    ua = ""
                    ref = ""
                }
            }
        }
    }

    private fun firstUnquotedComma(s: String): Int {
        var quoted = false
        for (i in s.indices) {
            when (s[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return i
            }
        }
        return -1
    }

    private val attrRegex = Regex("""([A-Za-z0-9_\-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"']+))""")

    fun parseAttrs(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in attrRegex.findAll(s)) {
            val key = m.groupValues[1].lowercase()
            val value = m.groups[2]?.value ?: m.groups[3]?.value ?: m.groups[4]?.value ?: ""
            out[key] = value
        }
        return out
    }
}
