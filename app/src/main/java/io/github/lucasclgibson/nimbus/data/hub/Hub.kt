package io.github.lucasclgibson.nimbus.data.hub

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import io.github.lucasclgibson.nimbus.BuildConfig
import io.github.lucasclgibson.nimbus.MainActivity
import io.github.lucasclgibson.nimbus.NimbusApp
import io.github.lucasclgibson.nimbus.core.await
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.data.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

/**
 * The household "Nimbus hub" on the home network (see tools/hub): found automatically over mDNS
 * (_nimbus._tcp), or set by hand in Settings.
 */
class HubClient(private val context: Context, private val http: OkHttpClient, private val prefs: Prefs) {

    /** Base URL like http://192.168.0.67:8787/nimbus, or null if no hub is around. */
    suspend fun base(): String? {
        prefs.hubUrl.value.takeIf { it.isNotBlank() }?.let { return it.trimEnd('/') }
        prefs.hubFound.value.takeIf { it.isNotBlank() }?.let { cached ->
            if (reachable(cached)) return cached
        }
        val found = discover() ?: return null
        prefs.hubFound.value = found
        return found
    }

    private suspend fun reachable(base: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("$base/version.json").head().build()).await().use { it.code < 500 }
        }.getOrDefault(false)
    }

    private suspend fun discover(): String? = withTimeoutOrNull(8_000) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val result = CompletableDeferred<String?>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { result.complete(null) }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val addr = info.host ?: return
                        val host = if (addr is java.net.Inet6Address) {
                            // Link-local IPv6 needs a zone id OkHttp can't use: prefer the IPv4 answer.
                            if (addr.isLinkLocalAddress) return
                            "[" + addr.hostAddress.substringBefore('%') + "]"
                        } else addr.hostAddress
                        result.complete("http://$host:${info.port}/nimbus")
                    }
                })
            }
        }
        nsd.discoverServices("_nimbus._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            result.await()
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }.also { if (it != null) Log.i(TAG, "hub found at $it") }

    // Bodies are read on IO threads: callers may be on the main thread.
    suspend fun getJson(path: String): JSONObject? = withContext(Dispatchers.IO) {
        val base = base() ?: return@withContext null
        runCatching {
            http.newCall(Request.Builder().url("$base/$path").build()).await().use { r ->
                if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty())
            }
        }.onFailure { Log.w(TAG, "GET $path failed: ${it.message}") }.getOrNull()
    }

    suspend fun putJson(path: String, body: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        val base = base() ?: return@withContext null
        runCatching {
            val req = Request.Builder().url("$base/$path")
                .put(body.toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(req).await().use { r -> if (!r.isSuccessful) null else JSONObject(r.body?.string().orEmpty()) }
        }.onFailure { Log.w(TAG, "PUT $path failed: ${it.message}") }.getOrNull()
    }

    suspend fun download(path: String, to: File, progress: (Float) -> Unit): Boolean {
        val base = base() ?: return false
        return runCatching {
            http.newCall(Request.Builder().url("$base/$path").build()).await().use { r ->
                if (!r.isSuccessful) return@use false
                val body = r.body ?: return@use false
                val total = body.contentLength()
                withContext(Dispatchers.IO) {
                    to.outputStream().use { out ->
                        val input = body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress(done.toFloat() / total)
                        }
                    }
                }
                true
            }
        }.getOrDefault(false)
    }

    companion object {
        const val TAG = "NimbusHub"
    }
}

// ============================================================================================ updates

data class UpdateInfo(val versionCode: Int, val versionName: String, val apk: String, val notes: String)

/** Self-update from the hub: check, download, and hand the APK to the system installer. */
class Updater(private val context: Context, private val hub: HubClient) {
    private val _available = MutableStateFlow<UpdateInfo?>(null)
    val available: StateFlow<UpdateInfo?> = _available

    /** null = idle, 0..1 = downloading, 2 = waiting for the installer. */
    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var lastCheck = 0L

    suspend fun check(force: Boolean = false): UpdateInfo? {
        if (!force && now() - lastCheck < 60 * 60 * 1000L) return _available.value
        val o = hub.getJson("version.json") ?: run {
            Log.i(HubClient.TAG, "update check: no answer from the hub")
            return null
        }
        lastCheck = now() // only a real answer counts: a hub that was down gets asked again next time
        val info = UpdateInfo(o.optInt("versionCode"), o.optString("versionName"), o.optString("apk"), o.optString("notes"))
        _available.value = if (info.versionCode > BuildConfig.VERSION_CODE && info.apk.isNotBlank()) info else null
        Log.i(HubClient.TAG, "update check: hub has ${info.versionCode}, this is ${BuildConfig.VERSION_CODE}")
        return _available.value
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Settings screen for "Install unknown apps", if the TV exposes it. */
    fun permissionIntent(): Intent? {
        if (Build.VERSION.SDK_INT < 26) return null
        val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (i.resolveActivity(context.packageManager) != null) i else null
    }

    suspend fun install() {
        val info = _available.value ?: return
        _message.value = null
        val file = File(context.cacheDir, "update.apk")
        _progress.value = 0f
        val ok = hub.download(info.apk.trimStart('/').removePrefix("nimbus/"), file) { _progress.value = it }
        if (!ok) {
            _progress.value = null
            _message.value = "Download failed – try again"
            return
        }
        _progress.value = 2f
        withContext(Dispatchers.IO) {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("nimbus.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out, 256 * 1024) }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, id, Intent(context, InstallReceiver::class.java), flags)
                session.commit(pi.intentSender)
            }
        }
    }

    fun onInstallFailed(msg: String) {
        _progress.value = null
        _message.value = msg
    }
}

/** Receives the installer's status: asks the viewer to confirm, or reports failure. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // MY_PACKAGE_REPLACED relaunches us
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                Log.w(HubClient.TAG, "install failed: $msg")
                (context.applicationContext as? NimbusApp)?.graph?.updater?.onInstallFailed("Update didn't install: $msg")
            }
        }
    }
}

/** After an update installs, come straight back to Nimbus. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
