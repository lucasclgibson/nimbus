package io.github.lucasclgibson.nimbus.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * For live MPEG-TS over HTTP: when the server drops the connection (or the stream "ends"), quietly
 * reopen the same URL and keep feeding bytes. The TS extractor resyncs on the next packets, and the
 * player plays through its buffer meanwhile, so short drops never reach the screen.
 * Gives up (and lets the player's own retry logic take over) after repeated failures in a short window.
 */
class ReconnectingDataSource(private val upstream: DataSource) : DataSource {
    private var spec: DataSpec? = null
    private var opened = false
    private val reconnects = ArrayDeque<Long>()

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        upstream.open(dataSpec)
        opened = true
        return C.LENGTH_UNSET.toLong() // live: read until we give up
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        while (true) {
            val result = try {
                upstream.read(buffer, offset, length)
            } catch (e: IOException) {
                if (Thread.currentThread().isInterrupted) throw e
                if (!reconnect(e.message)) throw e
                continue
            }
            if (result != C.RESULT_END_OF_INPUT) return result
            if (!reconnect("end of stream")) return C.RESULT_END_OF_INPUT
        }
    }

    private fun reconnect(reason: String?): Boolean {
        val s = spec ?: return false
        val now = SystemClock.elapsedRealtime()
        while (reconnects.isNotEmpty() && now - reconnects.first() > WINDOW_MS) reconnects.removeFirst()
        if (reconnects.size >= MAX_IN_WINDOW) return false
        reconnects.addLast(now)
        Log.i(TAG, "live stream dropped ($reason) – reconnecting (${reconnects.size}/$MAX_IN_WINDOW)")
        runCatching { upstream.close() }
        // Brief pause so a struggling server isn't hammered; the player's buffer covers it.
        try {
            Thread.sleep((150L * reconnects.size).coerceAtMost(1_000))
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return try {
            upstream.open(s.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
            true
        } catch (e: IOException) {
            Log.w(TAG, "reconnect failed: ${e.message}")
            false
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (opened) {
            opened = false
            upstream.close()
        }
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ReconnectingDataSource(upstream.createDataSource())
    }

    companion object {
        private const val TAG = "NimbusReconnect"
        private const val WINDOW_MS = 60_000L
        private const val MAX_IN_WINDOW = 6
    }
}
