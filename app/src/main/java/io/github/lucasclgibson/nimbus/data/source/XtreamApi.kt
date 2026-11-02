package io.github.lucasclgibson.nimbus.data.source

import android.util.Base64
import android.util.JsonReader
import io.github.lucasclgibson.nimbus.core.await
import io.github.lucasclgibson.nimbus.core.requireOk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class XtreamException(message: String) : Exception(message)

data class XtreamAccount(
    val status: String,
    val expiresAt: Long,
    val maxConnections: Int,
    val activeConnections: Int,
    val formats: List<String>,
    val timezone: String,
)

data class EpgListing(val start: Long, val end: Long, val title: String, val description: String)

class XtreamApi(
    private val http: OkHttpClient,
    base: String,
    val username: String,
    val password: String,
    private val userAgent: String,
) {
    val base: String = normaliseBase(base)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun apiUrl(action: String? = null, extra: String = ""): String = buildString {
        append(base).append("/player_api.php?username=").append(enc(username))
        append("&password=").append(enc(password))
        if (action != null) append("&action=").append(action)
        append(extra)
    }

    fun xmltvUrl(): String = "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    fun liveUrl(streamId: String, ext: String) = "$base/live/$username/$password/$streamId.$ext"
    fun movieUrl(streamId: String, ext: String) = "$base/movie/$username/$password/$streamId.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: String, ext: String) = "$base/series/$username/$password/$id.${ext.ifBlank { "mp4" }}"

    /** Catch-up: start is expressed in the panel's own timezone. */
    fun timeshiftUrl(streamId: String, startMs: Long, durationMin: Int, zone: ZoneId): String {
        val start = TIMESHIFT_FMT.format(Instant.ofEpochMilli(startMs).atZone(zone))
        return "$base/timeshift/$username/$password/$durationMin/$start/$streamId.ts"
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", userAgent)
        .header("Accept", "*/*")
        .build()

    suspend fun <T> stream(url: String, background: Boolean = false, block: (JsonReader) -> T): T {
        val response = http.newCall(request(url)).await().requireOk()
        return withContext(if (background) io.github.lucasclgibson.nimbus.core.Background.dispatcher else Dispatchers.IO) {
            response.use { r ->
                val body = r.body ?: throw XtreamException("Empty response")
                JsonReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                    reader.isLenient = true
                    block(reader)
                }
            }
        }
    }

    suspend fun getText(url: String): String {
        val response = http.newCall(request(url)).await().requireOk()
        return withContext(Dispatchers.IO) { response.use { it.body?.string().orEmpty() } }
    }

    suspend fun login(): XtreamAccount {
        val text = getText(apiUrl())
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw XtreamException("The server didn't reply like an Xtream Codes panel – check the URL")
        val ui = root.optJSONObject("user_info")
            ?: throw XtreamException("Login rejected – check the username and password")
        if (ui.optString("auth") == "0") throw XtreamException("Login rejected – check the username and password")
        val status = ui.optString("status")
        if (status.isNotEmpty() && !status.equals("Active", ignoreCase = true)) {
            throw XtreamException("Account is $status")
        }
        val formats = ui.optJSONArray("allowed_output_formats")?.let { a ->
            (0 until a.length()).map { a.optString(it) }
        }.orEmpty()
        val si = root.optJSONObject("server_info")
        return XtreamAccount(
            status = status,
            expiresAt = ui.optString("exp_date").toLongOrNull()?.times(1000) ?: 0L,
            maxConnections = ui.optString("max_connections").toIntOrNull() ?: 0,
            activeConnections = ui.optString("active_cons").toIntOrNull() ?: 0,
            formats = formats,
            timezone = si?.optString("timezone").orEmpty(),
        )
    }

    suspend fun categories(action: String): List<Pair<String, String>> = stream(apiUrl(action)) { r ->
        val out = ArrayList<Pair<String, String>>()
        r.forEachInArray { m -> out += m.str("category_id") to m.str("category_name") }
        out
    }

    /** Big catalogue lists: parsed on low-priority threads so playback is never starved. */
    suspend fun forEachItem(action: String, block: (JMap) -> Unit) = stream(apiUrl(action), background = true) { r ->
        r.forEachInArray(block)
    }

    suspend fun vodInfo(vodId: String): JMap = stream(apiUrl("get_vod_info", "&vod_id=$vodId")) { r ->
        (r.readAny() as? Map<*, *>)?.let { @Suppress("UNCHECKED_CAST") (it as JMap) } ?: emptyMap()
    }

    suspend fun seriesInfo(seriesId: String): JMap = stream(apiUrl("get_series_info", "&series_id=$seriesId")) { r ->
        (r.readAny() as? Map<*, *>)?.let { @Suppress("UNCHECKED_CAST") (it as JMap) } ?: emptyMap()
    }

    /** Full per-channel EPG (past + future). Used when the XMLTV feed has nothing for a channel. */
    suspend fun channelEpg(streamId: String): List<EpgListing> =
        stream(apiUrl("get_simple_data_table", "&stream_id=$streamId")) { r ->
            val root = r.readAny() as? Map<*, *> ?: return@stream emptyList()
            @Suppress("UNCHECKED_CAST")
            val listings = (root as JMap).list("epg_listings")
            listings.mapNotNull { item ->
                @Suppress("UNCHECKED_CAST")
                val m = item as? JMap ?: return@mapNotNull null
                val start = m.long("start_timestamp") * 1000
                val end = m.long("stop_timestamp") * 1000
                if (start <= 0 || end <= start) return@mapNotNull null
                EpgListing(start, end, b64(m.str("title")), b64(m.str("description")))
            }
        }

    companion object {
        private val TIMESHIFT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm")

        fun normaliseBase(input: String): String {
            var s = input.trim()
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            s = s.substringBefore('?').trimEnd('/')
            for (suffix in listOf("/player_api.php", "/get.php", "/panel_api.php", "/xmltv.php")) {
                if (s.endsWith(suffix, ignoreCase = true)) s = s.dropLast(suffix.length)
            }
            return s.trimEnd('/')
        }

        private fun b64(s: String): String {
            if (s.isEmpty()) return s
            return runCatching { String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8) }.getOrDefault(s)
        }
    }
}
