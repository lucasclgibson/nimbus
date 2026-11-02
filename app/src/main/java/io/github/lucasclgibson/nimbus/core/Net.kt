package io.github.lucasclgibson.nimbus.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Most IPTV panels accept VLC; some block unknown agents outright. Overridable per playlist. */
const val DEFAULT_USER_AGENT = "VLC/3.0.21 LibVLC/3.0.21"

val USER_AGENT_PRESETS = listOf(
    "Default (VLC)" to "",
    "TiviMate" to "TiviMate/5.1.6 (Android 11)",
    "IPTV Smarters" to "IPTVSmartersPlayer",
    "Chrome" to "Mozilla/5.0 (Linux; Android 11; AFTKRT) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
    "Kodi" to "Kodi/21.0 (Linux; Android 11.0) Android/11.0.0 Sys_CPU/aarch64",
)

fun buildHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .followSslRedirects(true)
    .retryOnConnectionFailure(true)
    .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
    .dispatcher(Dispatcher().apply { maxRequestsPerHost = 8 })
    .build()

/** Cancellable suspending execute. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, r, _ -> r.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

class HttpStatusException(val code: Int, message: String) : IOException(message)

fun Response.requireOk(): Response {
    if (!isSuccessful) {
        close()
        val hint = when (code) {
            401 -> "access denied – check the username and password"
            403 -> "the provider refused the connection. Check the server address; some providers also block certain apps or networks (try another user agent in the playlist settings, or check your VPN)"
            404 -> "not found – check the URL"
            in 500..599 -> "the provider's server is having problems"
            else -> "unexpected response"
        }
        throw HttpStatusException(code, "HTTP $code: $hint")
    }
    return this
}

private val URL_IN_TEXT = Regex("""https?://\S+""")

/** Never put raw URLs on screen: Xtream URLs carry the username and password. */
fun scrubUrls(text: String): String = URL_IN_TEXT.replace(text) { m ->
    runCatching { java.net.URI(m.value.trimEnd('.', ',', ')')).host }.getOrNull()?.let { "the server ($it)" } ?: "the server"
}

fun friendlyError(t: Throwable): String = when (t) {
    is HttpStatusException -> t.message ?: "HTTP ${t.code}"
    is java.net.UnknownHostException -> "Can't find the server – check the address"
    is java.net.SocketTimeoutException -> "The server took too long to respond"
    is java.net.ConnectException -> "Couldn't connect to the server"
    is javax.net.ssl.SSLException -> "Secure connection failed"
    is java.io.EOFException -> "The connection was interrupted – try again"
    else -> {
        val msg = t.message.orEmpty()
        when {
            msg.contains("unexpected end of stream", ignoreCase = true) -> "The connection was interrupted – try again"
            msg.isBlank() -> t.javaClass.simpleName
            else -> scrubUrls(msg)
        }
    }
}
