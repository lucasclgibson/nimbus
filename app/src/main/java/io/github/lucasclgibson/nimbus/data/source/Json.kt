package io.github.lucasclgibson.nimbus.data.source

import android.util.JsonReader
import android.util.JsonToken

/**
 * Lenient JSON helpers. IPTV panels are wildly inconsistent ("1" vs 1 vs true, [] for empty objects,
 * strings where arrays are expected), so everything scalar is read as a String and coerced on use.
 */
typealias JMap = Map<String, Any?>

fun JsonReader.readAny(): Any? = when (peek()) {
    JsonToken.BEGIN_OBJECT -> readMap()
    JsonToken.BEGIN_ARRAY -> {
        val list = ArrayList<Any?>()
        beginArray()
        while (hasNext()) list.add(readAny())
        endArray()
        list
    }
    JsonToken.NULL -> { nextNull(); null }
    JsonToken.BOOLEAN -> nextBoolean().toString()
    JsonToken.NUMBER, JsonToken.STRING -> nextString()
    else -> { skipValue(); null }
}

fun JsonReader.readMap(): JMap {
    val map = HashMap<String, Any?>()
    beginObject()
    while (hasNext()) {
        val name = nextName()
        map[name] = readAny()
    }
    endObject()
    return map
}

/** Stream the elements of a top-level array (or tolerate an object/garbage) one at a time. */
inline fun JsonReader.forEachInArray(block: (JMap) -> Unit) {
    when (peek()) {
        JsonToken.BEGIN_ARRAY -> {
            beginArray()
            while (hasNext()) {
                if (peek() == JsonToken.BEGIN_OBJECT) block(readMap()) else skipValue()
            }
            endArray()
        }
        JsonToken.BEGIN_OBJECT -> {
            // Some panels return {"0": {...}, "1": {...}} instead of an array.
            beginObject()
            while (hasNext()) {
                nextName()
                if (peek() == JsonToken.BEGIN_OBJECT) block(readMap()) else skipValue()
            }
            endObject()
        }
        else -> skipValue()
    }
}

fun JMap.str(key: String): String = when (val v = this[key]) {
    is String -> v
    is List<*> -> v.firstOrNull() as? String ?: ""
    null -> ""
    else -> v.toString()
}

fun JMap.int(key: String): Int = str(key).trim().let { s ->
    s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt() ?: 0
}

fun JMap.long(key: String): Long = str(key).trim().let { s ->
    s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() ?: 0L
}

fun JMap.double(key: String): Double = str(key).trim().replace(',', '.').toDoubleOrNull() ?: 0.0

fun JMap.bool(key: String): Boolean = str(key).let { it == "1" || it.equals("true", true) }

@Suppress("UNCHECKED_CAST")
fun JMap.map(key: String): JMap? = this[key] as? Map<String, Any?>

fun JMap.list(key: String): List<Any?> = when (val v = this[key]) {
    is List<*> -> v
    is Map<*, *> -> v.values.toList()
    null -> emptyList()
    else -> listOf(v)
}

fun JMap.strings(key: String): List<String> = list(key).mapNotNull { (it as? String)?.takeIf { s -> s.isNotBlank() } }
