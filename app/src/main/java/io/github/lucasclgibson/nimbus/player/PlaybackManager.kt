package io.github.lucasclgibson.nimbus.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.unit.IntRect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import io.github.lucasclgibson.nimbus.core.HOUR
import io.github.lucasclgibson.nimbus.core.MINUTE
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.data.Prefs
import io.github.lucasclgibson.nimbus.data.db.ChannelEntity
import io.github.lucasclgibson.nimbus.data.db.HistoryEntity
import io.github.lucasclgibson.nimbus.data.repo.LibraryRepository
import io.github.lucasclgibson.nimbus.data.repo.LiveRepository
import io.github.lucasclgibson.nimbus.core.DAY
import io.github.lucasclgibson.nimbus.data.db.ProgrammeLite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Session {
    data class Live(val channel: ChannelEntity, val zapList: List<ChannelEntity>) : Session

    /**
     * Catch-up plays on through the channel's timeline like time-shifted TV: into the next programme (the
     * title follows what's on screen), as far as the archive goes; ⏩ to the live edge joins live.
     * [title], [progStart] and [progEnd] are the programme on screen.
     */
    data class Catchup(
        val channel: ChannelEntity,
        val zapList: List<ChannelEntity>,
        val title: String,
        val progStart: Long,
        val progEnd: Long,
        /** Wall-clock time the current stream URL starts at. */
        val streamStart: Long,
    ) : Session

    data class Vod(val item: VodItem) : Session
}

data class VodItem(
    val historyKey: String,
    val kind: Int,
    val itemKey: String,
    val parentKey: String,
    val title: String,
    val subtitle: String,
    val url: String,
    val image: String,
    val backdrop: String,
    val userAgent: String,
    val season: Int = 0,
    val episode: Int = 0,
    val startPositionMs: Long = 0,
    val next: VodItem? = null,
)

data class TrackOption(
    val label: String,
    val detail: String,
    val selected: Boolean,
    val groupIndex: Int,
    val trackIndex: Int,
    val type: Int,
)

/** Live numbers for the "Stream info" panel and the logs. */
data class StreamStats(
    val resolution: String = "",
    val fps: Float = 0f,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val bitrateKbps: Int = 0,
    val cushionMs: Long = 0,
    val bufferSpanMs: Long = 0,
    val droppedFrames: Int = 0,
    val stalls: Int = 0,
    val reconnects: Int = 0,
    val watchingMs: Long = 0,
)

data class PlayerState(
    val playing: Boolean = false,
    /** Keep showing the previous picture until the new one arrives (only when zapping channel to channel). */
    val keepFrame: Boolean = false,
    /** The user wants playback (not paused), even while buffering. */
    val playWhenReady: Boolean = true,
    /** False until the current stream has drawn its first picture. */
    val firstFrame: Boolean = false,
    /** Waiting (picture hidden) while the TV switches to the stream's refresh rate. */
    val preparing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val retrying: Boolean = false,
    val error: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val audioTracks: List<TrackOption> = emptyList(),
    val textTracks: List<TrackOption> = emptyList(),
)

/**
 * Owns the single ExoPlayer. Video renders into whichever on-screen "slot" is registered
 * (full screen player, the guide's mini TV, the home hero), so live TV carries on as you browse.
 */
class PlaybackManager(
    private val context: Context,
    private val live: LiveRepository,
    private val library: LibraryRepository,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    // Short read timeout: a live stream that silently stops sending is caught within seconds and
    // reconnected inside ReconnectingDataSource (VOD loads just retry from the same position).
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats
    @Volatile private var droppedFrames = 0
    /** When the last video frame was rendered (picture watchdog). */
    @Volatile private var lastFrameAt = 0L
    private val pictureLosses = ArrayDeque<Long>()
    @Volatile private var stalls = 0
    private var sessionStartedAt = 0L
    private var wasReady = false
    /** When you last jumped (seek, rewind, back to live): the refill that follows isn't a stall. */
    private var jumpedAt = 0L
    /** Last measured frame rate per live channel: lets the TV switch refresh rate the moment you tune. */
    private val fpsByChannel = HashMap<String, Float>()

    /**
     * Troubleshooting: how long the picture takes to appear and to start moving after a start or a jump
     * (logged as "picture: …"). A first frame that then sits still is the "frozen picture" to avoid.
     */
    private inner class PictureTimer {
        @Volatile private var since = 0L
        @Volatile private var what = ""
        private var frames = 0
        private var firstAt = 0L

        fun mark(label: String) {
            what = label
            frames = 0
            since = SystemClock.elapsedRealtime()
        }

        fun onFrame() {
            val s = since
            // The first picture is decoded (hidden) while waiting for the TV to switch: count from when it shows.
            if (s == 0L || holding) return
            val t = SystemClock.elapsedRealtime()
            if (++frames == 1) firstAt = t
            if (frames == 5) {
                since = 0
                val still = t - firstAt
                Log.i(TAG, "picture: $what – first frame after ${firstAt - s} ms, moving after ${t - s} ms" +
                    if (still > 1_000) " (FROZE for $still ms)" else "")
            }
        }
    }

    private val pictureTimer = PictureTimer()

    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
            droppedFrames += count
            Log.w(TAG, "dropped $count video frames")
        }
    }

    private fun resetStats() {
        droppedFrames = 0
        stalls = 0
        wasReady = false
        sessionStartedAt = SystemClock.elapsedRealtime()
    }

    private fun updateStats(p: ExoPlayer) {
        val v = p.videoFormat
        val a = p.audioFormat
        val rec = recording
        _stats.value = StreamStats(
            resolution = if (v != null && v.width > 0) "${v.width}×${v.height}" else "",
            fps = _videoFps.value,
            videoCodec = v?.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT).orEmpty(),
            audioCodec = a?.let { trackDetail(it, C.TRACK_TYPE_AUDIO) }.orEmpty(),
            bitrateKbps = (rec?.recentBitrate()?.times(8)?.div(1000))?.toInt() ?: (v?.bitrate?.takeIf { it > 0 }?.div(1000) ?: 0),
            cushionMs = p.totalBufferedDuration,
            bufferSpanMs = when {
                rec == null -> 0
                rec.isArchive -> (rec.recordedUpTo - catchupPosition()).coerceAtLeast(0)
                else -> now() - rec.oldestTime
            },
            droppedFrames = droppedFrames,
            stalls = stalls,
            reconnects = rec?.reconnectCount ?: 0,
            watchingMs = SystemClock.elapsedRealtime() - sessionStartedAt,
        )
    }

    /** Frame rate of the current video (0 until known). Drives refresh-rate matching. */
    private val _videoFps = MutableStateFlow(0f)
    val videoFps: StateFlow<Float> = _videoFps

    /** Measures frame rate from presentation timestamps when the stream doesn't declare it (typical for live TS). */
    private val fpsProbe = object : androidx.media3.exoplayer.video.VideoFrameMetadataListener {
        private val deltas = LongArray(96)
        private var count = 0
        private var last = -1L
        @Volatile var active = false

        fun reset() {
            count = 0
            last = -1L
            active = true
        }

        override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: Format, mediaFormat: android.media.MediaFormat?) {
            lastFrameAt = SystemClock.elapsedRealtime()
            pictureTimer.onFrame()
            if (!active) return
            if (last >= 0) {
                val d = presentationTimeUs - last
                if (d in 8_000..60_000) deltas[count++] = d
            }
            last = presentationTimeUs
            if (count == deltas.size) {
                active = false
                // Only for streams the sniffer can't read (HLS): rebuild timestamps from the steps.
                var t = 0L
                val times = LongArray(count + 1) { i -> if (i == 0) 0L else { t += deltas[i - 1]; t } }
                val fps = FrameRates.estimate(times) ?: return
                mainHandler.post { onFrameRateKnown(fps, "measured while playing") }
            }
        }
    }

    // ------------------------------------------------------------------ live buffer

    private val bufferDir = java.io.File(context.cacheDir, "live")
    @Volatile private var recording: LiveRecording? = null
    /** Where in the buffer the next live prepare should start (null = the live edge). */
    private var pendingLiveOffset: Long? = null

    /** Keeps Wi-Fi out of power-save while the live buffer records (ExoPlayer drops its own lock when paused). */
    @Suppress("DEPRECATION")
    private val wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
        ?.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "nimbus:live-buffer")
        ?.apply { setReferenceCounted(false) }

    /** The ring is allocated once and reused for every channel; freed when Nimbus goes to the background. */
    private var ring: RingStore? = null

    private fun ringStore(): RingStore? {
        ring?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        // A share of what's free, 64–192 MB: about 3–6 minutes of HD.
        val want = (mi.availMem * 3 / 10).coerceIn(64L shl 20, 192L shl 20)
        val store: RingStore? = MemoryRing.create(want) ?: run {
            // Older Android: a small file ring (still far less wear than a big one).
            bufferDir.mkdirs()
            val free = bufferDir.usableSpace
            if (free < (600L shl 20)) null else FileRing(java.io.File(bufferDir, "live.ring"), 256L shl 20)
        }
        val kind = when (store) {
            is MemoryRing -> "memory"
            is FileRing -> "file"
            else -> "none"
        }
        Log.i(TAG, "live buffer: $kind ${(store?.capacity ?: 0) shr 20} MB (avail ${mi.availMem shr 20} MB)")
        ring = store
        return store
    }

    private fun freeRing() {
        ring?.close()
        ring = null
    }

    /** The recording for this stream, (re)starting it if needed. Null if there's no memory for a buffer. */
    private fun recordingFor(src: Source): LiveRecording? {
        val r = recording
        if (r != null && r.url == src.url && r.healthy) return r
        r?.close() // cancels its connection first: one-connection accounts need the old stream gone
        recording = null
        val store = ringStore() ?: return null
        runCatching { wifiLock?.acquire() }
        return LiveRecording(store, liveHttp, src.url, src.userAgent, src.referrer).also { rec ->
            rec.onNewSegment = { seg ->
                // A reconnect or a clock jump: queue the new stretch as the next playlist item.
                mainHandler.post {
                    if (recording === rec && _session.value is Session.Live) {
                        Log.i(TAG, "live buffer: new segment at ${seg.offset shr 10} KB")
                        exo?.addMediaSource(segmentSource(rec, seg.offset, seg))
                    }
                }
            }
            rec.start()
            recording = rec
        }
    }

    private fun stopRecording() {
        recording?.close()
        recording = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
        pendingLiveOffset = null
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * The live recorder waits much longer than the player's client for data: with ~25 s cushioned, a
     * brief server pause is harmless, whereas a reconnect costs the seconds of TV missed meanwhile.
     */
    private val liveHttp: OkHttpClient by lazy { http.newBuilder().readTimeout(15, TimeUnit.SECONDS).build() }

    private fun segmentSource(rec: LiveRecording, from: Long, seg: Segment = rec.segmentFor(from)): MediaSource =
        ProgressiveMediaSource.Factory({ LiveRecordingDataSource(rec, from) }, sniffingExtractors())
            .setLoadErrorHandlingPolicy(loadErrorPolicy)
            .createMediaSource(MediaItem.Builder().setUri("nimbus://rec/${rec.startedAt}/$from").setTag(seg).build())

    /** Playlist from [from] onwards: one item per segment (connection / unbroken stretch of clock). */
    private fun segmentSources(rec: LiveRecording, from: Long, first: Segment = rec.segmentFor(from)): List<MediaSource> {
        val out = ArrayList<MediaSource>()
        out += segmentSource(rec, from, first)
        for (s in rec.segmentList()) if (s.offset > from) out += segmentSource(rec, s.offset, s)
        return out
    }

    /** How much broadcast time the buffer currently holds. */
    fun bufferSpanMs(): Long = recording?.let { now() - it.oldestTime } ?: 0L

    /** True when the current live channel is being buffered locally (pause/rewind work on any channel). */
    val hasLiveBuffer: Boolean get() = recording != null && _session.value is Session.Live

    /**
     * How far behind live the viewer has chosen to be: pauses add their length, rewinds set it,
     * "back to live" clears it. (Tracked explicitly – measuring stream latency is unreliable while the
     * TV re-syncs to a new refresh rate.)
     */
    @Volatile private var liveShift = 0L

    /** How far behind live we're watching because of a pause or rewind (0 when live). */
    fun liveDelayMs(): Long {
        if (_session.value !is Session.Live) return 0
        return liveShift + (_livePausedAt.value?.let { now() - it } ?: 0L)
    }

    /** Broadcast moment currently on screen for live TV. */
    fun livePosition(): Long = now() - liveDelayMs()

    /** Earliest moment rewind can reach without the provider's archive. */
    fun liveBufferStart(): Long = recording?.oldestTime ?: now()

    /** Jump to a moment of the live channel: from the local buffer, or the provider's archive if older. */
    fun seekLive(time: Long) {
        val s = _session.value as? Session.Live ?: return
        val rec = recording
        _livePausedAt.value = null
        if (rec != null && time >= rec.oldestTime + 1_000) {
            if (time >= now() - 4_000) return jumpLive()
            seekWithinBuffer(time, "live rewind")
            return
        }
        // Older than the buffer: the provider's archive – but only once it has that moment in full (streaming
        // the minutes it's still archiving freezes and stalls). Until then, as far back as the buffer goes.
        if (live.hasCatchup(s.channel) && time < archivedBy() - MINUTE) {
            timeshift(s.channel, s.zapList, time)
        } else if (rec != null) {
            seekWithinBuffer(time, "live rewind (as far back as the buffer goes)")
        }
    }

    /** Live TV: play from [time] in the local buffer (or its oldest part), starting on a complete picture. */
    private fun seekWithinBuffer(time: Long, label: String) {
        val rec = recording ?: return
        val src = current ?: return
        _livePausedAt.value = null
        val entry = rec.entryPoint(rec.offsetAt(maxOf(time, rec.oldestTime)))
        pendingLiveOffset = entry
        jumpedAt = SystemClock.elapsedRealtime()
        pictureTimer.mark(label)
        liveShift = (now() - rec.timeAt(entry)).coerceAtLeast(0)
        _state.update { it.copy(keepFrame = true, firstFrame = false) }
        prepare(src, 0)
    }

    fun jumpLive() {
        val rec = recording ?: return
        val src = current ?: return
        _livePausedAt.value = null
        liveShift = 0
        // The newest complete picture with a little stream after it (keyframes can be 10 s apart).
        pendingLiveOffset = rec.latestEntry(cushionMs = 1_500) ?: rec.offsetAt(now() - 2_500)
        jumpedAt = SystemClock.elapsedRealtime()
        pictureTimer.mark("back to live")
        _state.update { it.copy(keepFrame = true) }
        prepare(src, 0)
    }

    /** Wall-clock time live TV was paused at, while paused. */
    private val _livePausedAt = MutableStateFlow<Long?>(null)
    val livePausedAt: StateFlow<Long?> = _livePausedAt

    val resizeMode = MutableStateFlow(AspectRatioFrameLayout.RESIZE_MODE_FIT)

    private var exo: ExoPlayer? = null
    private var smooth: SmoothLoadControl? = null
    val player: ExoPlayer get() = exo ?: create().also { exo = it }

    // ------------------------------------------------------------------ video slot

    private val _slot = MutableStateFlow<IntRect?>(null)
    val slot: StateFlow<IntRect?> = _slot
    private var slotOwner: Any? = null
    private var stopJob: Job? = null
    /** Playback paused because nothing on screen can show it (or the app went to the background). */
    private var suspended = false
    private var inBackground = false

    fun claimSlot(owner: Any, rect: IntRect) {
        slotOwner = owner
        if (_slot.value != rect) _slot.value = rect
        stopJob?.cancel()
        if (suspended && !inBackground) resume()
    }

    fun releaseSlot(owner: Any) {
        if (slotOwner !== owner) return
        slotOwner = null
        _slot.value = null
        stopJob?.cancel()
        stopJob = scope.launch {
            delay(400) // allow the next screen to claim it
            if (slotOwner == null) suspendPlayback(clearVod = true)
        }
    }

    fun onAppBackground() {
        inBackground = true
        suspendPlayback(clearVod = false)
        freeRing()
    }

    fun onAppForeground() {
        inBackground = false
        if (suspended && slotOwner != null) resume()
    }

    private fun suspendPlayback(clearVod: Boolean) {
        val s = _session.value ?: return
        saveVodProgress()
        if (s is Session.Catchup) catchupPosition().takeIf { it > 0 }?.let { lastCatchupContent = it }
        retryJob?.cancel()
        exo?.stop()
        stopRecording()
        suspended = true
        _livePausedAt.value = null
        if (clearVod && s !is Session.Live) {
            _session.value = null
            suspended = false
            current = null
        }
    }

    private fun resume() {
        suspended = false
        when (val s = _session.value) {
            is Session.Live -> start(live.liveUrl(s.channel), live.userAgent(s.channel), s.channel.referrer, isLive = true)
            is Session.Vod -> {
                val pos = lastVodPosition
                start(s.item.url, s.item.userAgent, "", isLive = false, startPositionMs = pos)
            }
            is Session.Catchup -> playCatchup(s.channel, s.zapList, s.title, s.progStart, s.progEnd, lastCatchupContent.takeIf { it > 0 } ?: s.streamStart)
            null -> Unit
        }
    }

    // ------------------------------------------------------------------ public API

    fun playLive(channel: ChannelEntity, zapList: List<ChannelEntity>) {
        saveVodProgress()
        val prev = when (val s = _session.value) {
            is Session.Live -> s.channel
            is Session.Catchup -> s.channel
            else -> null
        }
        if (prev != null && prev.key != channel.key) prefs.previousChannelKey.value = prev.key
        val zapping = _session.value is Session.Live
        _livePausedAt.value = null
        liveShift = 0
        _session.value = Session.Live(channel, zapList.ifEmpty { listOf(channel) })
        // Known frame rate for this channel: switch the TV now, while the stream is still connecting.
        fpsByChannel[channel.key]?.let { _videoFps.value = it }
        _state.update { it.copy(keepFrame = zapping) }
        prefs.lastChannelKey.value = channel.key
        scope.launch(Dispatchers.IO) { library.channelWatched(channel) }
        start(live.liveUrl(channel), live.userAgent(channel), channel.referrer, isLive = true)
    }

    /** Channel up/down within the current list. Returns the new channel. */
    fun zap(delta: Int): ChannelEntity? {
        val (channel, list) = when (val s = _session.value) {
            is Session.Live -> s.channel to s.zapList
            is Session.Catchup -> s.channel to s.zapList
            else -> return null
        }
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.key == channel.key }.coerceAtLeast(0)
        val next = list[(idx + delta).mod(list.size)]
        playLive(next, list)
        return next
    }

    fun playCatchup(
        channel: ChannelEntity,
        zapList: List<ChannelEntity>,
        title: String,
        progStart: Long,
        progEnd: Long,
        from: Long = progStart,
    ) {
        saveVodProgress()
        _livePausedAt.value = null
        lastCatchupContent = from
        chainedAt = SystemClock.elapsedRealtime()
        loadCatchupGuide(channel)
        val first = archiveChunk(channel, from)
        if (first == null && from > archivedBy()) {
            // Too recent for the archive. Watching this channel live? Its buffer has it (or as much as there is).
            val s = _session.value as? Session.Live
            val rec = recording
            if (s != null && s.channel.key == channel.key && rec != null && !rec.isArchive) {
                Log.i(TAG, "catch-up: too recent for the archive – using the live buffer")
                seekWithinBuffer(from, "watch from start (live buffer)")
                return
            }
        }
        val store = if (first != null && !looksHls(first.first)) ringStore() else null
        if (first != null && store != null) {
            _session.value = Session.Catchup(channel, zapList, title, progStart, progEnd, from)
            startArchive(store, channel, from, resetRetries = true)
            return
        }
        playCatchupDirect(channel, zapList, title, progStart, progEnd, from)
    }

    /**
     * Catch-up streamed straight from the archive URL: HLS archives, archives that aren't MPEG-TS, no
     * memory for a buffer, or only the last few minutes (not fully archived yet).
     */
    private fun playCatchupDirect(
        channel: ChannelEntity,
        zapList: List<ChannelEntity>,
        title: String,
        progStart: Long,
        progEnd: Long,
        from: Long,
    ) {
        stopRecording()
        chainedAt = SystemClock.elapsedRealtime()
        val startAt = (from / MINUTE) * MINUTE
        // A long window, so playback runs on across programme boundaries (then the next window).
        val end = maxOf(progEnd, startAt + 2 * HOUR)
        val url = live.catchupUrl(channel, startAt, end)
        if (url == null) {
            _state.update { it.copy(error = "This channel doesn't support catch-up") }
            return
        }
        _session.value = Session.Catchup(channel, zapList, title, progStart, progEnd, startAt)
        start(url, live.userAgent(channel), channel.referrer, isLive = false)
    }

    // ------------------------------------------------------------------ catch-up via the recorder

    /** Programme time on screen in catch-up (kept by the watchdog; read by the recorder too). */
    @Volatile private var lastCatchupContent = 0L

    /**
     * The next archive request for programme from [from]: only what's fully archived (staying
     * [ARCHIVE_LAG] behind live), in pieces of up to [ARCHIVE_CHUNK], starting a few seconds early so it
     * overlaps what's recorded (that's where it's spliced in). Null when there's nothing to fetch yet / any more.
     */
    private fun archiveChunk(channel: ChannelEntity, from: Long, resuming: Boolean = false): Pair<String, Long>? {
        val start = ((if (resuming) from - RESUME_OVERLAP else from) / MINUTE) * MINUTE
        val limit = ((now() - ARCHIVE_LAG) / MINUTE) * MINUTE
        val ready = limit - from
        // Close behind live, more is archived every minute: fetch it in decent pieces unless the viewer
        // is close behind.
        val ahead = from - lastCatchupContent
        if (ready < 20_000 || (ready < EDGE_STEP && ahead > EDGE_CUSHION)) return null
        val end = minOf(start + ARCHIVE_CHUNK, limit)
        if (end - from < 2_000) return null
        val url = live.catchupUrl(channel, start, end) ?: return null
        return url to start
    }

    /** Programme before this moment is fully in the provider's archive (see [ARCHIVE_LAG]). */
    private fun archivedBy(): Long = ((now() - ARCHIVE_LAG) / MINUTE) * MINUTE - 20_000

    private fun startArchive(store: RingStore, channel: ChannelEntity, from: Long, resetRetries: Boolean) {
        retryJob?.cancel()
        if (resetRetries) retries = 0
        suspended = false
        stopRecording()
        val ua = live.userAgent(channel)
        // Runs on through the channel's timeline: never finished, it waits at the live edge for more.
        val plan = ArchivePlan(from, finished = { false }) { t, resuming -> archiveChunk(channel, t, resuming) }
        pictureTimer.mark("catch-up start")
        val rec = LiveRecording(store, liveHttp, "archive:${channel.key}:$from", ua, channel.referrer, plan)
        rec.onNewSegment = { seg ->
            mainHandler.post { if (recording === rec) exo?.addMediaSource(segmentSource(rec, seg.offset, seg)) }
        }
        runCatching { wifiLock?.acquire() }
        rec.start()
        recording = rec
        current = Source(rec.url, ua, channel.referrer, isLive = false, hls = false, archive = true)
        newStream(knownFps = fpsByChannel[channel.key] ?: 0f)
        fpsProbe.reset()
        resetStats()
        lastFrameAt = 0L
        _state.update { it.copy(error = null, retrying = false, ended = false, buffering = true, firstFrame = false, keepFrame = false) }
        val p = player
        smooth?.forCatchup(nearLiveEdge = false, recorded = true)
        if (p.playbackParameters.speed != 1f) p.setPlaybackSpeed(1f)
        p.setMediaSources(segmentSources(rec, 0), true)
        p.prepare()
        p.playWhenReady = !holding
        bufferingSince = SystemClock.elapsedRealtime()
        ensureWatchdog()
    }

    /** Carry on catch-up from the programme time on screen (after an error or an early end). */
    private fun restartArchive() {
        val s = _session.value as? Session.Catchup ?: return
        val from = catchupPosition().takeIf { it > s.progStart - 6 * HOUR } ?: lastCatchupContent
        val store = ringStore() ?: return
        Log.i(TAG, "catch-up: carrying on from ${java.time.Instant.ofEpochMilli(from)}")
        startArchive(store, s.channel, from, resetRetries = false)
    }

    // ------------------------------------------------------------------ pause / rewind live TV

    fun pauseLive() {
        if (_session.value !is Session.Live || _livePausedAt.value != null) return
        exo?.playWhenReady = false
        _livePausedAt.value = now()
    }

    /**
     * Short pauses resume from the player's own buffer (any channel, frame-exact). Longer pauses on
     * catch-up channels resume from the provider's archive where you left off – Sky-style time-shift.
     */
    fun resumeLive() {
        val s = _session.value as? Session.Live ?: return
        val since = _livePausedAt.value ?: return
        _livePausedAt.value = null
        val pausedFor = now() - since
        val fitsInBuffer = recording != null && since > (recording?.oldestTime ?: Long.MAX_VALUE) + 2_000
        if (fitsInBuffer || pausedFor <= BUFFERED_PAUSE_MS || !live.hasCatchup(s.channel)) {
            liveShift += pausedFor
            exo?.playWhenReady = true
            return
        }
        // Paused longer than the buffer holds: carry on from the provider's archive at the paused moment.
        timeshift(s.channel, s.zapList, since)
    }

    /** Watch a catch-up channel from a moment in the past, carrying on as live TV. */
    fun timeshift(channel: ChannelEntity, zapList: List<ChannelEntity>, from: Long) {
        scope.launch {
            val prog = live.programmeAt(channel, from)
            val start = prog?.startMs ?: (from - 30 * MINUTE)
            val end = prog?.endMs ?: (from + 30 * MINUTE)
            playCatchup(channel, zapList, prog?.title ?: channel.name, start, end, from)
        }
    }

    /** Catch-up seeking rebuilds the archive URL at the new time – works on every panel. */
    fun seekCatchupTo(absoluteMs: Long) {
        val s = _session.value as? Session.Catchup ?: return
        // The newest few minutes aren't fully archived yet: that close to now, just watch live.
        if (absoluteMs >= archivedBy()) {
            playLive(s.channel, s.zapList)
            return
        }
        // Already recorded (just watched, or fetched ahead): jump straight there.
        val rec = recording
        val point = if (rec != null && rec.isArchive && current?.archive == true) rec.seekPoint(absoluteMs) else null
        if (rec != null && point != null) {
            Log.i(TAG, "catch-up: seeking within the recording (keyframe ${absoluteMs - point.contentStart} ms before the target)")
            jumpedAt = SystemClock.elapsedRealtime()
            pictureTimer.mark("catch-up skip within the recording")
            lastCatchupContent = point.contentStart
            rec.restartReading(point.offset)
            val p = player
            // Start on the complete picture at or just before the target: the picture moves straight away.
            p.setMediaSources(segmentSources(rec, point.offset, point), true)
            p.prepare()
            p.playWhenReady = true
            bufferingSince = SystemClock.elapsedRealtime()
            return
        }
        val t = absoluteMs.coerceAtLeast(archiveStart(s.channel))
        val prog = guideAt(t)
        playCatchup(s.channel, s.zapList, prog?.title ?: s.channel.name, prog?.startMs ?: t, prog?.endMs ?: (t + 30 * MINUTE), t)
    }

    /** Programme time on screen in catch-up (each recorded segment knows where it starts). */
    fun catchupPosition(): Long {
        val s = _session.value as? Session.Catchup ?: return 0
        val p = exo ?: return s.streamStart
        val seg = p.currentMediaItem?.localConfiguration?.tag as? Segment
        return (seg?.contentStart ?: s.streamStart) + p.currentPosition
    }

    fun playVod(item: VodItem) {
        saveVodProgress()
        _session.value = Session.Vod(item)
        lastVodPosition = item.startPositionMs
        start(item.url, item.userAgent, "", isLive = false, startPositionMs = item.startPositionMs)
    }

    fun stop() {
        cancelHold()
        saveVodProgress()
        stopRecording()
        _livePausedAt.value = null
        retryJob?.cancel()
        exo?.stop()
        exo?.clearMediaItems()
        current = null
        suspended = false
        _session.value = null
        _state.value = PlayerState()
    }

    fun togglePause() {
        val p = exo ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            val s = _session.value
            if (s is Session.Catchup) {
                playCatchup(s.channel, s.zapList, s.title, s.progStart, s.progEnd, s.progStart)
                return
            }
            p.seekTo(0)
            p.play()
        } else {
            p.playWhenReady = !p.playWhenReady
        }
        if (!p.playWhenReady) saveVodProgress()
    }

    fun retryNow() {
        val src = current ?: return
        retries = 0
        if (src.archive) {
            _state.update { it.copy(error = null, retrying = true) }
            restartArchive()
            return
        }
        _state.update { it.copy(error = null, retrying = true) }
        if (_session.value is Session.Live) prepare(src, 0) else prepare(src, exo?.currentPosition ?: 0)
    }

    fun selectTrack(option: TrackOption?, type: Int) {
        val p = exo ?: return
        val builder = p.trackSelectionParameters.buildUpon()
        if (option == null) {
            builder.setTrackTypeDisabled(type, true).clearOverridesOfType(type)
            if (type == C.TRACK_TYPE_TEXT) prefs.subtitlesOn.value = false
        } else {
            val group = p.currentTracks.groups.getOrNull(option.groupIndex) ?: return
            builder.setTrackTypeDisabled(type, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            val format = group.getTrackFormat(option.trackIndex)
            if (type == C.TRACK_TYPE_TEXT) prefs.subtitlesOn.value = true
            if (type == C.TRACK_TYPE_AUDIO) format.language?.let { prefs.preferredAudio.value = it }
        }
        p.trackSelectionParameters = builder.build()
    }

    // ------------------------------------------------------------------ internals

    private data class Source(
        val url: String,
        val userAgent: String,
        val referrer: String,
        val isLive: Boolean,
        val hls: Boolean,
        val triedAlternate: Boolean = false,
        /** Catch-up through the recorder (restarts go through [restartArchive]). */
        val archive: Boolean = false,
    )

    private var current: Source? = null
    private var retries = 0
    private var retryJob: Job? = null
    private var bufferingSince = 0L
    private var lastVodPosition = 0L
    private var chainedAt = 0L
    @Volatile private var rolling = false
    private var watchdog: Job? = null

    private val extractors = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)

    /** Extractors that report the current stream's frame rate from its first frames (see [FrameRateSniffer]). */
    private fun sniffingExtractors(): FrameRateSniffer {
        val generation = sourceGeneration
        return FrameRateSniffer(extractors) { fps ->
            mainHandler.post { if (generation == sourceGeneration) onFrameRateKnown(fps, "from the stream") }
        }
    }

    private fun onFrameRateKnown(fps: Float, how: String) {
        fpsProbe.active = false
        (_session.value as? Session.Live)?.let { fpsByChannel[it.channel.key] = fps }
        if (abs(fps - _videoFps.value) < 0.01f) return
        Log.i(TAG, "frame rate ${"%.3f".format(fps)} fps ($how)")
        _videoFps.value = fps
    }

    // ------------------------------------------------------------------ refresh-rate switching at the start

    /**
     * Set by the activity: switches the TV to suit a frame rate (if needed and enabled), returning once the
     * TV is showing a picture again.
     */
    @Volatile var displaySwitcher: (suspend (Float) -> Unit)? = null

    /** Bumped for each new stream, so late reports from the previous one are ignored. */
    private var sourceGeneration = 0

    /**
     * A new stream waits, paused with its picture hidden, until the TV is at the right refresh rate: the
     * TV shows black for a second or two while it switches, and better that than a second into the film.
     */
    private var holding = false
    private var renderedWhileHolding = false
    private var gateJob: Job? = null

    private fun newStream(knownFps: Float) {
        sourceGeneration++
        _videoFps.value = knownFps
        gateJob?.cancel()
        val switcher = displaySwitcher
        holding = switcher != null
        renderedWhileHolding = false
        _state.update { it.copy(preparing = holding) }
        if (switcher == null) return
        val generation = sourceGeneration
        val since = SystemClock.elapsedRealtime()
        // Films may need a few seconds of frames to tell 23.976 from 24; TV is quick (and shouldn't wait).
        val wait = if (_session.value is Session.Live) LIVE_FPS_WAIT_MS else FPS_WAIT_MS
        gateJob = scope.launch {
            var fps: Float? = null
            try {
                fps = withTimeoutOrNull(wait) { _videoFps.first { it > 0f } }
                fps?.let { switcher(it) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "refresh-rate switch failed", e)
            } finally {
                // Never leave a stream paused: whatever happened, play (unless a newer stream took over).
                if (generation == sourceGeneration) releaseHold(SystemClock.elapsedRealtime() - since, fps)
            }
        }
    }

    private fun releaseHold(waitedMs: Long, fps: Float?) {
        if (!holding) return
        holding = false
        Log.i(TAG, "display ready after $waitedMs ms" + if (fps == null) " (frame rate not known yet)" else "")
        _state.update { it.copy(preparing = false, firstFrame = it.firstFrame || renderedWhileHolding) }
        if (!suspended) exo?.playWhenReady = true
    }

    private fun cancelHold() {
        gateJob?.cancel()
        gateJob = null
        holding = false
        _state.update { it.copy(preparing = false) }
    }

    /** Retry network hiccups inside the loader, but never 4xx answers – they don't change on retry. */
    private val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy(4) {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val e = loadErrorInfo.exception
            if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499 && e.responseCode != 408 && e.responseCode != 429) {
                return C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private fun start(url: String, userAgent: String, referrer: String, isLive: Boolean, startPositionMs: Long = 0) {
        retryJob?.cancel()
        retries = 0
        suspended = false
        pictureTimer.mark(when (_session.value) {
            is Session.Live -> "channel start"
            is Session.Catchup -> "catch-up start (streamed directly)"
            else -> "film/episode start"
        })
        if (url.isBlank()) {
            _state.update { it.copy(error = "No stream address for this item") }
            return
        }
        val src = Source(url, userAgent, referrer, isLive, looksHls(url))
        current = src
        newStream(knownFps = when (val s = _session.value) {
            is Session.Live -> fpsByChannel[s.channel.key] ?: 0f
            is Session.Catchup -> fpsByChannel[s.channel.key] ?: 0f
            else -> 0f
        })
        fpsProbe.reset()
        resetStats()
        lastFrameAt = 0L
        _state.update { it.copy(error = null, retrying = false, ended = false, buffering = true, firstFrame = false, keepFrame = isLive && it.keepFrame) }
        prepare(src, startPositionMs)
        ensureWatchdog()
    }

    private fun prepare(src: Source, positionMs: Long) {
        if (src.archive) {
            restartArchive()
            return
        }
        val p = player
        when (val s = _session.value) {
            is Session.Live -> smooth?.forLive()
            // The archive close to live arrives in bursts: wait for a deep cushion before playing.
            is Session.Catchup -> smooth?.forCatchup(nearLiveEdge = s.streamStart > now() - 6 * MINUTE)
            else -> smooth?.forVod()
        }
        if (p.playbackParameters.speed != 1f) p.setPlaybackSpeed(1f)
        val ds = OkHttpDataSource.Factory(http).setUserAgent(src.userAgent)
        if (src.referrer.isNotBlank()) ds.setDefaultRequestProperties(mapOf("Referer" to src.referrer))
        val item = MediaItem.Builder().setUri(src.url).apply {
            if (src.hls) setMimeType(MimeTypes.APPLICATION_M3U8)
            // HLS live: sit well back from the edge and never speed up to catch it (that drains the buffer).
            if (src.hls && src.isLive) setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(20_000).setMinPlaybackSpeed(0.97f).setMaxPlaybackSpeed(1f).build()
            )
        }.build()
        val rec = if (src.isLive && !src.hls) recordingFor(src) else null
        if (!src.isLive || src.hls) stopRecording()
        if (rec != null) {
            // Live TS through the local buffer: frame-exact pause, rewind, and seamless reconnects.
            val offset = rec.entryPoint(
                (pendingLiveOffset ?: if (rec.written == 0L) 0L else rec.offsetAt(now() - 2_500)).coerceIn(rec.oldestOffset, rec.written)
            )
            pendingLiveOffset = null
            p.setMediaSources(segmentSources(rec, offset), true)
            p.prepare()
            p.playWhenReady = !holding
            bufferingSince = SystemClock.elapsedRealtime()
            return
        }
        val source: MediaSource = when {
            src.hls -> HlsMediaSource.Factory(ds).setAllowChunklessPreparation(true).setLoadErrorHandlingPolicy(loadErrorPolicy).createMediaSource(item)
            else -> {
                // Live TS without a buffer: reconnect inside the data source so drops never reach the screen.
                val upstream = if (src.isLive) ReconnectingDataSource.Factory(ds) else ds
                ProgressiveMediaSource.Factory(upstream, sniffingExtractors()).setLoadErrorHandlingPolicy(loadErrorPolicy).createMediaSource(item)
            }
        }
        if (src.isLive) p.setMediaSource(source, true) else p.setMediaSource(source, positionMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = !holding
        bufferingSince = SystemClock.elapsedRealtime()
    }

    private fun looksHls(url: String): Boolean {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return path.endsWith(".m3u8") || path.endsWith(".m3u") || url.contains("m3u8", ignoreCase = true)
    }

    private fun create(): ExoPlayer {
        val large = prefs.bufferMode.value == 1
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val loadControl = SmoothLoadControl(large).also { smooth = it }
        val selector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(prefs.preferredAudio.value.ifBlank { "en" })
                    .setPreferredTextLanguage("en")
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !prefs.subtitlesOn.value)
            )
        }
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setTrackSelector(selector)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
            )
            // "Becoming noisy" means headphones unplugged on a phone. On a TV it fires whenever HDMI
            // re-syncs (refresh-rate switch, TV input change, soundbar waking) and would freeze playback.
            .setHandleAudioBecomingNoisy(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                addListener(listener)
                addAnalyticsListener(analytics)
                setVideoFrameMetadataListener(fpsProbe)
            }
    }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                jumpedAt = SystemClock.elapsedRealtime()
                if (reason == Player.DISCONTINUITY_REASON_SEEK) pictureTimer.mark("seek")
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The join to the next stretch of a recording (a reconnect or a timestamp jump).
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) pictureTimer.mark("join to the next part of the recording")
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val p = exo
            if (playbackState == Player.STATE_READY) wasReady = true
            val jumped = SystemClock.elapsedRealtime() - jumpedAt < 1_500
            if (playbackState == Player.STATE_BUFFERING && wasReady && p?.playWhenReady == true && !jumped) {
                stalls++
                Log.w(TAG, "stall #$stalls (cushion ${p.totalBufferedDuration}ms)")
            }
            _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING, ended = playbackState == Player.STATE_ENDED) }
            when (playbackState) {
                Player.STATE_READY -> {
                    retries = 0
                    bufferingSince = 0
                    _state.update { it.copy(retrying = false, error = null) }
                }
                Player.STATE_BUFFERING -> bufferingSince = SystemClock.elapsedRealtime()
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onTracksChanged(tracks: Tracks) {
            tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }?.let { g ->
                (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { i ->
                    val fr = g.getTrackFormat(i).frameRate
                    if (fr > 0f) {
                        fpsProbe.active = false
                        _videoFps.value = fr
                    }
                }
            }
            _state.update { it.copy(audioTracks = options(tracks, C.TRACK_TYPE_AUDIO), textTracks = options(tracks, C.TRACK_TYPE_TEXT)) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.update { it.copy(playWhenReady = playWhenReady) }
            // Never stay paused for a reason the viewer didn't choose (audio route glitches etc.).
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY && !suspended) {
                Log.w(TAG, "paused by an audio route change – resuming")
                exo?.playWhenReady = true
            }
        }

        override fun onRenderedFirstFrame() {
            // While waiting for the TV to switch, the first picture stays hidden (it would show, go black, come back).
            if (holding) renderedWhileHolding = true else _state.update { it.copy(firstFrame = true) }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
        }
    }

    private fun onEnded() {
        val src = current ?: return
        when (val s = _session.value) {
            // A live TS stream "ending" means the server dropped us: reconnect.
            is Session.Live -> scheduleRetry(src, "Reconnecting…")
            is Session.Vod -> saveVodProgress(finishedOverride = true)
            is Session.Catchup -> if (recording?.isArchive == true) {
                // The recording only finishes when the provider has no more archive (caught up with live).
                if (recording!!.completed) playLive(s.channel, s.zapList) else restartArchive()
            } else {
                val pos = s.streamStart + (exo?.currentPosition ?: 0)
                val playedFor = SystemClock.elapsedRealtime() - chainedAt
                // Archive stream ran out while still behind live: carry on from there. Close to live
                // (or a stream that ends straight away): join live.
                if (pos < now() - 90_000 && playedFor > 10_000) {
                    playCatchup(s.channel, s.zapList, s.title, s.progStart, s.progEnd, pos)
                } else {
                    playLive(s.channel, s.zapList)
                }
            }
            null -> Unit
        }
    }

    // ------------------------------------------------------------------ the catch-up channel's guide

    /** The catch-up channel's guide over its whole archive (sorted), for following programmes as playback moves. */
    private val _catchupGuide = MutableStateFlow<List<ProgrammeLite>>(emptyList())
    val catchupGuide: StateFlow<List<ProgrammeLite>> = _catchupGuide
    private var guideChannel: String? = null
    private var guideLoadedAt = 0L

    private fun loadCatchupGuide(channel: ChannelEntity) {
        if (guideChannel == channel.key && SystemClock.elapsedRealtime() - guideLoadedAt < 10 * MINUTE) return
        if (guideChannel != channel.key) _catchupGuide.value = emptyList()
        guideChannel = channel.key
        guideLoadedAt = SystemClock.elapsedRealtime()
        scope.launch {
            suspend fun load(c: ChannelEntity) = live.programmes(listOf(c), archiveStart(c) - 6 * HOUR, now() + 6 * HOUR)[c.key].orEmpty()
                .sortedBy { it.startMs }
            var list = load(channel)
            if (guideChannel == channel.key) _catchupGuide.value = list
            // The guide often starts later than the archive: the provider's per-channel listing has the past.
            val first = list.firstOrNull()?.startMs ?: now()
            if (first > archiveStart(channel) + 2 * HOUR && live.fetchArchiveEpg(channel, first) != null) {
                list = load(live.channel(channel.key) ?: channel)
                if (guideChannel == channel.key) _catchupGuide.value = list
            }
        }
    }

    /** The programme at [t] on the catch-up channel's timeline (null if none, or the guide isn't loaded yet). */
    fun guideAt(t: Long): ProgrammeLite? = _catchupGuide.value.programmeAt(t)

    /** Earliest moment the provider keeps for [channel]. */
    fun archiveStart(channel: ChannelEntity): Long = now() - channel.catchupDays.coerceAtLeast(1) * DAY + MINUTE

    /** Keep the programme shown for catch-up in step with the picture, whichever way it moves. */
    private fun followProgramme(s: Session.Catchup, position: Long) {
        fun apply(prog: ProgrammeLite?) {
            val cur = _session.value as? Session.Catchup ?: return
            if (cur.streamStart != s.streamStart) return
            // No guide here: half-hour slots under the channel's name.
            val slot = (position / (30 * MINUTE)) * (30 * MINUTE)
            val title = prog?.title ?: cur.channel.name
            val start = prog?.startMs ?: slot
            val end = prog?.endMs ?: (slot + 30 * MINUTE)
            if (title != cur.title || start != cur.progStart || end != cur.progEnd) {
                _session.value = cur.copy(title = title, progStart = start, progEnd = end)
            }
        }
        if (_catchupGuide.value.isNotEmpty()) return apply(guideAt(position))
        if (rolling) return
        rolling = true
        scope.launch {
            try {
                apply(live.programmeAt(s.channel, position))
            } finally {
                rolling = false
            }
        }
    }

    private fun handleError(e: PlaybackException) {
        val src = current ?: return
        Log.w(TAG, "playback error ${e.errorCodeName} for ${src.url.substringBefore('?')}", e)
        if (src.archive) {
            if (e.cause is LiveRecording.NotTsException) {
                val s = _session.value as? Session.Catchup ?: return
                Log.i(TAG, "catch-up archive isn't MPEG-TS – streaming it directly")
                playCatchupDirect(s.channel, s.zapList, s.title, s.progStart, s.progEnd, lastCatchupContent.takeIf { it > 0 } ?: s.streamStart)
                return
            }
            val code = (e.cause as? io.github.lucasclgibson.nimbus.core.HttpStatusException)?.code
            if ((code != null && code in 400..499 && code != 408) || retries >= 6) {
                _state.update { it.copy(retrying = false, error = describe(e, code)) }
                return
            }
            retries++
            _state.update { it.copy(retrying = true, error = null) }
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(min(500L * (1L shl min(retries, 4)), 8_000L))
                if (current === src) restartArchive()
            }
            return
        }
        val containerGuessWrong = e.errorCode in setOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        )
        if (containerGuessWrong && !src.triedAlternate) {
            val alt = src.copy(hls = !src.hls, triedAlternate = true)
            current = alt
            prepare(alt, exo?.currentPosition ?: 0)
            return
        }
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            exo?.seekToDefaultPosition()
            exo?.prepare()
            return
        }
        if (e.cause is LiveRecording.OverwrittenException) {
            // Paused longer than the buffer holds: carry on from the oldest part still buffered.
            recording?.let {
                pendingLiveOffset = it.oldestOffset
                liveShift = (now() - it.oldestTime).coerceAtLeast(0)
            }
            prepare(src, 0)
            return
        }
        val http = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            ?: (e.cause as? io.github.lucasclgibson.nimbus.core.HttpStatusException)?.code
        val permanent = http != null && http in 400..499 && http != 408 && http != 429
        val max = if (src.isLive) 30 else 6
        // Archive/VOD 4xx answers don't change on retry; live streams sometimes 404 briefly while switching.
        val giveUpAfter = if (src.isLive) 1 else 0
        if (retries < max && !(permanent && retries >= giveUpAfter)) {
            scheduleRetry(src, null)
        } else {
            _state.update { it.copy(retrying = false, error = describe(e, http)) }
        }
    }

    private fun scheduleRetry(src: Source, @Suppress("UNUSED_PARAMETER") message: String?) {
        retries++
        val delayMs = min(500L * (1L shl min(retries, 4)), 8_000L)
        _state.update { it.copy(retrying = true, error = null) }
        val position = if (src.isLive) 0 else (exo?.currentPosition ?: 0)
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            if (current == src) prepare(src, position)
        }
    }

    private fun describe(e: PlaybackException, http: Int?): String = when {
        http == 407 || http == 429 || http == 458 ->
            "Your provider says this account is already watching on another device. Stop the other stream, then try again."
        _session.value is Session.Catchup && (http == 404 || http == 400) ->
            "This programme isn't in the provider's catch-up archive (HTTP $http). Try one that aired more recently."
        http == 401 || http == 403 -> "The provider refused this stream (HTTP $http). Too many devices, or the account has expired?"
        http == 404 -> "This stream isn't available right now (HTTP 404)."
        http != null -> "The provider returned an error (HTTP $http)."
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Can't reach the stream – check the connection."
        e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The stream timed out."
        e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device can't decode the stream's video format."
        else -> "Playback failed (${e.errorCodeName})."
    }

    /** Detects frozen streams the player doesn't report (endless buffering) and reconnects; saves VOD progress. */
    private fun ensureWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            var tick = 0
            while (isActive) {
                delay(1000)
                tick++
                val p = exo ?: continue
                val src = current ?: continue
                if (suspended || !p.playWhenReady) continue
                if (p.playbackState == Player.STATE_BUFFERING && bufferingSince > 0) {
                    val stalled = SystemClock.elapsedRealtime() - bufferingSince
                    // Catch-up through the recorder: it reconnects by itself; only step in if it has given up or gone quiet.
                    val recorderBusy = src.archive && recording?.let { it.healthy && SystemClock.elapsedRealtime() - it.lastDataAt < 20_000 } == true
                    if (!recorderBusy && stalled > if (src.isLive) 9_000 else if (src.archive) 15_000 else 25_000) {
                        Log.w(TAG, "stall for ${stalled}ms – reconnecting")
                        pictureTimer.mark("reconnect after a stall")
                        bufferingSince = SystemClock.elapsedRealtime()
                        _state.update { it.copy(retrying = true) }
                        if (src.isLive) stopRecording()
                        prepare(src, if (src.isLive) 0 else p.currentPosition)
                    }
                }
                updateStats(p)
                if (tick % 30 == 0 && p.currentMediaItemIndex > 0 && recording != null) {
                    p.removeMediaItems(0, p.currentMediaItemIndex)
                }
                if (tick % 60 == 0 && p.isPlaying) {
                    val st = _stats.value
                    Log.i(TAG, "health: cushion=${st.cushionMs}ms dropped=${st.droppedFrames} stalls=${st.stalls} reconnects=${st.reconnects} " +
                        "${st.bitrateKbps}kbps speed=${p.playbackParameters.speed} span=${st.bufferSpanMs / 1000}s" +
                        (recording?.let { " · ${it.keyframeInfo()}" } ?: ""))
                }
                // Servers that don't send a start-up burst leave a thin cushion: play imperceptibly slower
                // (3%, pitch-corrected) until there's 10 s in hand, so a network hiccup never freezes the picture.
                // Catch-up counts what the recorder holds ahead too, and aims higher: an archive that only
                // trickles in at real-time speed needs the margin most.
                val rec = recording
                val recordedSession = rec != null && (_session.value is Session.Live || rec.isArchive)
                if (rec != null && recordedSession && p.isPlaying) {
                    val ahead = if (rec.isArchive) maxOf(p.totalBufferedDuration, rec.recordedUpTo - catchupPosition()) else p.totalBufferedDuration
                    val (low, high) = if (rec.isArchive) 8_000L to 20_000L else 5_000L to 10_000L
                    val speed = p.playbackParameters.speed
                    if (speed == 1f && ahead < low) p.setPlaybackSpeed(0.97f)
                    else if (speed < 1f && ahead >= high) p.setPlaybackSpeed(1f)
                } else if (p.playbackParameters.speed != 1f && !recordedSession) {
                    p.setPlaybackSpeed(1f)
                }
                // Picture watchdog: "playing" but no new frame for 8 s (a stream that's gone audio-only or
                // stuck) – reconnect. If it keeps happening, say so instead of showing a black screen.
                val hasVideo = p.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                val recorded = _session.value is Session.Live || recording?.isArchive == true
                if (recorded && p.isPlaying && hasVideo && lastFrameAt > 0 &&
                    SystemClock.elapsedRealtime() - lastFrameAt > 8_000
                ) {
                    val t = SystemClock.elapsedRealtime()
                    while (pictureLosses.isNotEmpty() && t - pictureLosses.first() > 120_000) pictureLosses.removeFirst()
                    pictureLosses.addLast(t)
                    lastFrameAt = t
                    if (pictureLosses.size >= 3) {
                        Log.w(TAG, "picture keeps stopping – giving up")
                        stopRecording()
                        p.stop()
                        _state.update { it.copy(error = "This channel isn't sending a picture at the moment. Try another channel, or come back to it later.") }
                    } else if (recording?.isArchive == true) {
                        Log.w(TAG, "no new picture for 8s – restarting catch-up from here")
                        restartArchive()
                    } else {
                        Log.w(TAG, "no new picture for 8s – reconnecting")
                        stopRecording()
                        prepare(src, 0)
                    }
                }
                if (_session.value !is Session.Live && p.isPlaying) lastVodPosition = p.currentPosition
                (_session.value as? Session.Catchup)?.let { s ->
                    val pos = catchupPosition()
                    if (p.isPlaying) lastCatchupContent = pos
                    if (p.isPlaying && (pos >= s.progEnd || pos < s.progStart)) followProgramme(s, pos)
                }
                if (tick % 10 == 0 && p.isPlaying) saveVodProgress()
            }
        }
    }

    private fun saveVodProgress(finishedOverride: Boolean = false) {
        val s = _session.value as? Session.Vod ?: return
        val p = exo ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        if (!finishedOverride && pos < 5_000) return
        lastVodPosition = pos
        val finished = finishedOverride || (dur > 0 && pos > dur * 0.93)
        val item = s.item
        scope.launch(Dispatchers.IO) {
            library.saveProgress(
                HistoryEntity(
                    key = item.historyKey, kind = item.kind, itemKey = item.itemKey, parentKey = item.parentKey,
                    title = item.title, subtitle = item.subtitle, image = item.image, backdrop = item.backdrop,
                    positionMs = if (finished) 0 else pos, durationMs = dur, updatedAt = now(), finished = finished,
                    season = item.season, episode = item.episode,
                )
            )
        }
    }

    private fun options(tracks: Tracks, type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti, true)) continue
                val f = g.getTrackFormat(ti)
                out += TrackOption(trackLabel(f, out.size + 1), trackDetail(f, type), g.isTrackSelected(ti), gi, ti, type)
            }
        }
        return out
    }

    private fun trackLabel(f: Format, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.UK).replaceFirstChar { c -> c.uppercase() }
        }
        return f.label?.takeIf { it.isNotBlank() } ?: lang ?: "Track $n (unlabelled)"
    }

    private fun trackDetail(f: Format, type: Int): String {
        if (type == C.TRACK_TYPE_TEXT) {
            val codec = "${f.codecs.orEmpty()} ${f.sampleMimeType.orEmpty()} ${f.containerMimeType.orEmpty()}".lowercase(Locale.ROOT)
            val kind = when {
                "subrip" in codec || "srt" in codec -> "SRT"
                "ssa" in codec || "ass" in codec -> "SSA"
                "pgs" in codec -> "Picture (PGS)"
                "dvb" in codec -> "DVB"
                "vtt" in codec -> "WebVTT"
                "608" in codec || "708" in codec -> "Closed captions"
                "ttml" in codec || "dfxp" in codec -> "TTML"
                else -> ""
            }
            val forced = if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) "Forced" else ""
            return listOf(kind, forced).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        if (type != C.TRACK_TYPE_AUDIO) return f.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT).orEmpty()
        val codec = when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "DTS"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L2 -> "MPEG"
            else -> f.sampleMimeType?.substringAfter('/')?.uppercase(Locale.ROOT).orEmpty()
        }
        val channels = when (f.channelCount) {
            1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"
            Format.NO_VALUE -> ""
            else -> "${f.channelCount}ch"
        }
        return listOf(codec, channels).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    companion object {
        private const val TAG = "NimbusPlayer"
        /** How long a new stream waits for its frame rate before playing anyway. */
        private const val FPS_WAIT_MS = 4_000L
        private const val LIVE_FPS_WAIT_MS = 2_500L
        /** Catch-up stays this far behind live: the newest minutes may still be being archived. */
        private const val ARCHIVE_LAG = 3 * MINUTE
        /** Largest single archive request. */
        private const val ARCHIVE_CHUNK = 60 * MINUTE
        /** Each archive request starts this much before what's recorded, to splice it in. */
        private const val RESUME_OVERLAP = 5_000L
        /** Behind live: wait until this much more is archived before fetching it... */
        private const val EDGE_STEP = 5 * MINUTE
        /** ...unless less than this is recorded ahead of the viewer. */
        private const val EDGE_CUSHION = 3 * MINUTE
        /** Roughly what the live buffer holds; longer pauses resume from the archive instead. */
        private const val BUFFERED_PAUSE_MS = 35_000L
    }
}

/** The programme at [t] in a list sorted by start time (binary search), or null. */
fun List<ProgrammeLite>.programmeAt(t: Long): ProgrammeLite? {
    var lo = 0
    var hi = size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val p = this[mid]
        when {
            t < p.startMs -> hi = mid - 1
            t >= p.endMs -> lo = mid + 1
            else -> return p
        }
    }
    return null
}
