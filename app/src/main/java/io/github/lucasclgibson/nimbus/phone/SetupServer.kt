package io.github.lucasclgibson.nimbus.phone

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Tiny LAN-only web form so long URLs and passwords can be typed on a phone instead of with the remote.
 * Runs only while the "Add from phone" screen is open.
 */
class SetupServer(private val scope: CoroutineScope) {

    data class Submission(
        val type: String,
        val name: String,
        val url: String,
        val username: String,
        val password: String,
        val epgUrl: String,
        val tmdbKey: String,
    )

    private val _submissions = MutableSharedFlow<Submission>(extraBufferCapacity = 4)
    val submissions: SharedFlow<Submission> = _submissions

    private var server: ServerSocket? = null
    private var job: Job? = null

    /** @return the address to show on screen, or null if no network */
    fun start(): String? {
        val ip = localIp() ?: return null
        if (server == null) {
            val socket = runCatching { ServerSocket(PORT) }.getOrElse { ServerSocket(0) }
            server = socket
            job = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) { runCatching { handle(client) }.onFailure { Log.w(TAG, "request failed", it) } }
                }
            }
        }
        return "http://$ip:${server!!.localPort}"
    }

    fun stop() {
        job?.cancel()
        runCatching { server?.close() }
        server = null
        job = null
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readLine(input) ?: return
            var contentLength = 0
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()
            val out = s.getOutputStream()
            if (method == "POST" && path.startsWith("/submit")) {
                val body = ByteArray(contentLength.coerceIn(0, 64 * 1024))
                var read = 0
                while (read < body.size) {
                    val n = input.read(body, read, body.size - read)
                    if (n < 0) break
                    read += n
                }
                val form = parseForm(String(body, 0, read, Charsets.UTF_8))
                val sub = Submission(
                    type = form["type"].orEmpty().ifBlank { "xtream" },
                    name = form["name"].orEmpty().trim(),
                    url = form["url"].orEmpty().trim(),
                    username = form["username"].orEmpty().trim(),
                    password = form["password"].orEmpty(),
                    epgUrl = form["epg"].orEmpty().trim(),
                    tmdbKey = form["tmdb"].orEmpty().trim(),
                )
                val ok = sub.url.isNotBlank() || sub.tmdbKey.isNotBlank()
                if (ok) _submissions.tryEmit(sub)
                respond(out, if (ok) DONE_PAGE else FORM_PAGE.replace("<!--msg-->", "<p class=err>Please fill in the server / playlist address.</p>"))
            } else {
                respond(out, FORM_PAGE)
            }
        }
    }

    private fun respond(out: java.io.OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8").trimEnd('\r')
            buf.write(b)
            if (buf.size() > 16 * 1024) return buf.toString("UTF-8")
        }
    }

    private fun parseForm(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate { kv ->
        val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
        val v = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
        k to v
    }

    companion object {
        private const val TAG = "NimbusSetup"
        const val PORT = 8484

        fun localIp(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") && !it.name.startsWith("ppp") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        private const val STYLE = """
            *{box-sizing:border-box}body{margin:0;font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:linear-gradient(160deg,#0b1a3f,#02040a 70%);color:#fff;min-height:100vh}
            main{max-width:520px;margin:0 auto;padding:28px 20px 60px}h1{font-size:30px;margin:0 0 4px;background:linear-gradient(90deg,#78c8ff,#fff,#aa96ff);-webkit-background-clip:text;color:transparent}
            p{color:#b4c0d8;line-height:1.5}.tabs{display:flex;gap:8px;margin:22px 0 14px}.tabs label{flex:1;text-align:center;padding:12px;border-radius:12px;background:#ffffff14;cursor:pointer;font-weight:600}
            input[type=radio]{display:none}#t1:checked~.tabs label[for=t1],#t2:checked~.tabs label[for=t2]{background:#fff;color:#0a1022}
            .f{display:none}#t1:checked~.x{display:block}#t2:checked~.m{display:block}
            label.l{display:block;font-size:13px;color:#b4c0d8;margin:14px 0 6px}input[type=text],input[type=password],input[type=url]{width:100%;padding:14px;border-radius:12px;border:1px solid #ffffff26;background:#0e1830;color:#fff;font-size:16px}
            button{margin-top:22px;width:100%;padding:16px;border:0;border-radius:14px;background:linear-gradient(90deg,#2e8bff,#7a5cff);color:#fff;font-size:17px;font-weight:700}
            .card{background:#ffffff0d;border:1px solid #ffffff1a;border-radius:16px;padding:16px;margin-top:22px}.err{color:#ff8a9a}small{color:#7a88a6}
        """

        val FORM_PAGE = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>Nimbus setup</title><style>$STYLE</style></head>
<body><main><h1>nimbus</h1><p>Add a playlist to your Fire TV. Details are sent straight to the TV on your home network.</p><!--msg-->
<form method=post action=/submit>
<input type=radio name=type id=t1 value=xtream checked><input type=radio name=type id=t2 value=m3u>
<div class=tabs><label for=t1>Xtream Codes</label><label for=t2>M3U URL</label></div>
<label class=l>Name</label><input type=text name=name placeholder="My TV">
<div class="f x"><label class=l>Server address</label><input type=url name=url_x placeholder="http://provider.example:8080" autocapitalize=none>
<label class=l>Username</label><input type=text name=username autocapitalize=none autocorrect=off>
<label class=l>Password</label><input type=password name=password></div>
<div class="f m"><label class=l>Playlist URL (M3U)</label><input type=url name=url_m placeholder="http://.../get.php?...type=m3u_plus" autocapitalize=none></div>
<label class=l>Extra EPG / XMLTV URL <small>(optional)</small></label><input type=url name=epg autocapitalize=none>
<div class=card><label class=l style="margin-top:0">TMDB API key <small>(optional – adds reviews, cast photos and trailers)</small></label><input type=text name=tmdb autocapitalize=none autocorrect=off></div>
<input type=hidden name=url id=u><button onclick="var x=document.getElementById('t1').checked;document.getElementById('u').value=document.querySelector(x?'[name=url_x]':'[name=url_m]').value">Send to TV</button>
</form></main></body></html>"""

        val DONE_PAGE = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1"><title>Nimbus setup</title><style>$STYLE</style></head>
<body><main><h1>Sent ✓</h1><p>Your TV is loading the playlist now. You can close this page.</p><p><a href=/ style="color:#78c8ff">Add another</a></p></main></body></html>"""
    }
}
