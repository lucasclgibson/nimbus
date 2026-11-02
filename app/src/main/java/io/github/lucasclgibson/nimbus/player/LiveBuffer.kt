package io.github.lucasclgibson.nimbus.player

import android.net.Uri
import android.os.Build
import android.os.SharedMemory
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import io.github.lucasclgibson.nimbus.core.HttpStatusException
import io.github.lucasclgibson.nimbus.core.now
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/** Byte storage for the live buffer ring. Positions are 0 until [capacity]; calls never cross the end. */
interface RingStore {
    val capacity: Long
    fun write(pos: Long, b: ByteArray, off: Int, len: Int)
    fun read(pos: Long, b: ByteArray, off: Int, len: Int)
    fun close()
}

/**
 * RAM ring (shared memory, outside the Java heap). Pages are only committed as they're written, and it
 * never touches flash storage – recording live TV continuously to the stick's flash would wear it out.
 */
class MemoryRing private constructor(private val shm: SharedMemory, private val map: ByteBuffer) : RingStore {
    override val capacity: Long = map.capacity().toLong()

    override fun write(pos: Long, b: ByteArray, off: Int, len: Int) {
        val d = map.duplicate()
        d.position(pos.toInt())
        d.put(b, off, len)
    }

    override fun read(pos: Long, b: ByteArray, off: Int, len: Int) {
        val d = map.duplicate()
        d.position(pos.toInt())
        d.get(b, off, len)
    }

    override fun close() {
        runCatching { SharedMemory.unmap(map) }
        runCatching { shm.close() }
    }

    companion object {
        /** Largest ring we can get up to [bytes], or null (old Android / no memory). */
        fun create(bytes: Long): MemoryRing? {
            if (Build.VERSION.SDK_INT < 27) return null
            var size = bytes
            while (size >= 32L shl 20) {
                try {
                    val shm = SharedMemory.create("nimbus-live", size.toInt())
                    return MemoryRing(shm, shm.mapReadWrite())
                } catch (e: Throwable) {
                    Log.w("NimbusBuffer", "couldn't map ${size shr 20} MB: ${e.message}")
                    size /= 2
                }
            }
            return null
        }
    }
}

/** Fallback ring on storage (older devices only). Positional I/O so the writer and readers don't collide. */
class FileRing(private val file: File, override val capacity: Long) : RingStore {
    private val raf = RandomAccessFile(file, "rw")
    private val channel: FileChannel = raf.channel

    override fun write(pos: Long, b: ByteArray, off: Int, len: Int) {
        val buf = ByteBuffer.wrap(b, off, len)
        var p = pos
        while (buf.hasRemaining()) p += channel.write(buf, p)
    }

    override fun read(pos: Long, b: ByteArray, off: Int, len: Int) {
        val buf = ByteBuffer.wrap(b, off, len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = channel.read(buf, p)
            if (n < 0) throw IOException("short read")
            p += n
        }
    }

    override fun close() {
        runCatching { raf.close() }
        file.delete()
    }
}

/** A stretch of the recording with continuous timestamps; each is played as its own playlist item. */
class Segment(val offset: Long, contentStart: Long) {
    /** Programme time at [offset] (catch-up). Firmed up when the first bytes arrive. */
    @Volatile var contentStart: Long = contentStart
        internal set
    /** The stream map (PAT + PMT) in force in this segment – a provider can switch codec between connections. */
    @Volatile var map: ByteArray? = null
        internal set
}

/**
 * Catch-up as a recording: where to fetch programme from time `from` – the archive URL and the
 * (minute-aligned) time that URL starts at – or null when there's nothing to fetch right now. When
 * there's nothing to fetch, the recording ends if [finished] says so, else waits for more to be archived.
 */
class ArchivePlan(
    val start: Long,
    val finished: (from: Long) -> Boolean,
    val chunk: (from: Long, resuming: Boolean) -> Pair<String, Long>?,
)

/**
 * The stream recorder behind live TV and catch-up. The stream is read into a RAM ring continuously
 * (so the server never sees an idle connection) and playback reads from the ring.
 *
 * Live: the ring rolls over (pause/rewind within it); drops reconnect quietly behind the buffer.
 * Catch-up ([archive] set): the archive is fetched in fully-recorded chunks; if a chunk ends or the
 * connection drops, the next request starts a little earlier and is spliced in where the recording got
 * to – byte-exact when the server sends the same data again (the stream carries on as if nothing
 * happened), otherwise by timestamp. Writing waits for the reader, so nothing unplayed is overwritten.
 *
 * In both modes the MPEG-TS clock is followed: wherever it jumps (provider restarted its source, archive
 * pieces stitched together) a new segment starts, so the player never sees a timestamp jump.
 */
class LiveRecording(
    private val store: RingStore,
    private val http: OkHttpClient,
    val url: String,
    private val userAgent: String,
    private val referrer: String,
    private val archive: ArchivePlan? = null,
) {
    private val lock = Object()
    private val capacity = store.capacity
    val isArchive: Boolean get() = archive != null

    /** Bytes received (logical, ever-growing; the ring wraps at [capacity]). */
    @Volatile var written = 0L
        private set
    @Volatile var finished = false
        private set
    /** Catch-up: finished because the plan ran out (end of programme / caught up with live). */
    @Volatile var completed = false
        private set
    @Volatile var error: IOException? = null
        private set
    @Volatile var reconnectCount = 0
        private set
    @Volatile var jumpCount = 0
        private set
    /** Times the stream fell out of step with its packets mid-way and was realigned. */
    @Volatile var resyncs = 0
        private set
    /** Bytes thrown away while realigning. */
    @Volatile var lostBytes = 0L
        private set
    /** For the health log: video stream, codec and keyframes. */
    fun keyframeInfo(): String = synchronized(lock) {
        val n = seekCount
        val span = if (n >= 2) seekTimes[(seekStart + n - 1) % SEEK_POINTS] - seekTimes[seekStart] else 0
        "video pid ${keys.videoPid} type 0x${Integer.toHexString(keys.streamType)}, $n keyframes" +
            if (n >= 2) " every ${"%.1f".format(span / 1000.0 / (n - 1))} s" else ""
    }

    /** Waits until there's data at [offset] (or the recording has stopped). */
    fun awaitData(offset: Long) = synchronized(lock) {
        while ((written <= offset || !started) && !finished && !closed) {
            try {
                lock.wait(100)
            } catch (e: InterruptedException) {
                throw InterruptedIOException()
            }
        }
    }

    /**
     * The stream map (PAT + PMT packets) to put in front of playback that starts mid-recording: a player
     * only reads a stream once it has seen the map, and some providers send it rarely – a keyframe that
     * arrives before the map is wasted, leaving the picture frozen until the next one.
     */
    fun streamMap(offset: Long): ByteArray? = segmentFor(offset).map ?: keys.psi

    /** Catch-up responses spliced in byte-exactly (seamless). */
    @Volatile var resumedExactly = 0
        private set

    private val segments = ArrayList<Segment>().apply { add(Segment(0, archive?.start ?: now())) }

    /** Called (on the recording thread) when a new segment's first bytes arrive. */
    @Volatile var onNewSegment: ((Segment) -> Unit)? = null

    fun segmentList(): List<Segment> = synchronized(lock) { ArrayList(segments) }

    /** Offset where the segment containing [offset] ends, or null if it's still being recorded. */
    fun segmentEndAfter(offset: Long): Long? = synchronized(lock) {
        for (s in segments) if (s.offset > offset) return s.offset
        null
    }

    fun segmentFor(offset: Long): Segment = synchronized(lock) {
        var found = segments[0]
        for (s in segments) if (s.offset <= offset) found = s else break
        found
    }

    @Volatile private var closed = false
    @Volatile private var call: Call? = null
    private var thread: Thread? = null
    private var limitWaits = 0

    /** Furthest position the player has read (catch-up: writing never overtakes it). */
    @Volatile private var readPos = 0L
    /** Bumped when the player jumps elsewhere in the recording; reads by older sources no longer count. */
    @Volatile var readGeneration = 0
        private set

    // Keyframes (where playback can start): offset and time – programme time in catch-up, stream clock time
    // in live TV.
    private val keys = TsKeyframes()
    private val seekTimes = LongArray(SEEK_POINTS)
    private val seekOffsets = LongArray(SEEK_POINTS)
    private var seekCount = 0
    private var seekStart = 0

    // Segment bookkeeping for the recording thread.
    private var pendingSegment = false
    private var pendingContentStart = 0L

    // The recording's clock (recording thread): follows the timestamps of what's been recorded, across
    // responses; [clockBase] is the programme time of its first timestamp.
    private val clock = TsTimeline()
    private var clockBase = 0L
    private var videoPid = -1

    // The last packets recorded, to find the exact place to carry on in a repeat of the same data.
    private val tail = ByteArray(TAIL_PACKETS * Ts.PACKET)
    private var tailLen = 0

    /** Catch-up: programme time reached by what's been recorded. */
    @Volatile var recordedUpTo: Long = archive?.start ?: 0L
        private set

    // (wall-clock time, logical offset) samples, for mapping "a moment of broadcast" to a byte position.
    private val times = LongArray(INDEX_SIZE)
    private val offsets = LongArray(INDEX_SIZE)
    private var indexCount = 0
    private var indexStart = 0
    private var lastSample = 0L

    val startedAt: Long = now()

    fun start() {
        thread = Thread({ run() }, if (archive != null) "nimbus-archive" else "nimbus-live-buffer").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    private fun isConnectionLimit(e: IOException): Boolean = when {
        e is HttpStatusException -> e.code == 407 || e.code == 429 || e.code == 458
        else -> e.message?.contains("407") == true
    }

    private fun run() {
        try {
            if (archive != null) runArchive(archive) else runLive()
        } finally {
            finished = true
            synchronized(lock) { lock.notifyAll() }
        }
    }

    // ------------------------------------------------------------------ live

    /**
     * Pull the live stream into the ring, reconnecting quietly if the server drops us. OkHttp directly, so
     * [close] can cancel the connection instantly – many accounts allow one stream at a time.
     */
    private fun runLive() {
        val reconnects = ArrayDeque<Long>()
        var lastError: IOException? = null
        addSample(now(), 0)
        while (!closed) {
            try {
                fetch(url, contentBase = now(), skipUntil = Long.MIN_VALUE)
            } catch (e: IOException) {
                if (closed) break
                if (isConnectionLimit(e)) {
                    if (!waitForConnectionLimit()) break
                    continue
                }
                // A channel that refuses us outright won't change its mind: report it straight away.
                if (e is HttpStatusException && e.code in 400..499 && e.code != 408) {
                    error = e
                    break
                }
                lastError = e
            }
            if (closed) break
            val t = SystemClock.elapsedRealtime()
            while (reconnects.isNotEmpty() && t - reconnects.first() > 60_000) reconnects.removeFirst()
            if (reconnects.size >= 6) {
                error = lastError ?: IOException("The channel keeps dropping")
                break
            }
            reconnects.addLast(t)
            reconnectCount++
            Log.i(TAG, "live stream dropped (${lastError?.message ?: "ended"}) – reconnecting (${reconnects.size}/6)")
            lastError = null
            try {
                Thread.sleep(150L * reconnects.size)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    // ------------------------------------------------------------------ catch-up

    private fun runArchive(plan: ArchivePlan) {
        var failures = 0
        while (!closed) {
            val target = recordedUpTo
            val next = plan.chunk(target, written > 0)
            if (next == null) {
                if (plan.finished(target)) {
                    completed = true
                    Log.i(TAG, "archive: complete at ${hhmmss(target)}")
                    break
                }
                // Up with what's archived so far: wait for more.
                try {
                    Thread.sleep(5_000)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }
            val (chunkUrl, chunkStart) = next
            val before = recordedUpTo
            var problem: IOException? = null
            val t0 = SystemClock.elapsedRealtime()
            val w0 = written
            try {
                fetch(chunkUrl, contentBase = chunkStart, skipUntil = target)
            } catch (e: NotTsException) {
                error = e
                break
            } catch (e: IOException) {
                if (closed) break
                if (isConnectionLimit(e)) {
                    if (!waitForConnectionLimit()) break
                    continue
                }
                problem = e
            }
            if (closed) break
            if (!started && written > 0) commit("response ended early")
            val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
            Log.i(
                TAG, "archive: response from ${hhmmss(chunkStart)} gave ${(written - w0) shr 10} KB in ${"%.1f".format(secs)} s, " +
                    "programme now ${hhmmss(recordedUpTo)}, joins $counterFixes, jumps $jumpCount, realigned $resyncs ($lostBytes bytes)" +
                    (problem?.let { " (${it.message ?: it.javaClass.simpleName})" } ?: "")
            )
            if (recordedUpTo > before + 1_000) {
                failures = 0
                if (problem != null) reconnectCount++
                continue
            }
            // No progress from this request.
            failures++
            val http = (problem as? HttpStatusException)?.code
            if (http != null && http in 400..499 && http != 408 && written > 0) {
                // Asked past the end of what's archived (e.g. right after the newest recording): stop here.
                completed = true
                break
            }
            if (failures >= 4) {
                error = problem ?: IOException("The archive isn't delivering this programme")
                break
            }
            reconnectCount++
            try {
                Thread.sleep(1_000L * failures)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    // ------------------------------------------------------------------ shared

    private fun waitForConnectionLimit(): Boolean {
        // "Too many connections" (407/429/458 on Xtream panels): the panel hasn't let go of our
        // previous connection yet. Give it a few seconds rather than hammering it.
        if (++limitWaits > 4) {
            error = HttpStatusException(458, "connection limit")
            return false
        }
        Log.i(TAG, "provider says the connection is still in use – waiting (${limitWaits}/4)")
        return try {
            Thread.sleep(2_500L * limitWaits)
            true
        } catch (ie: InterruptedException) {
            false
        }
    }

    /**
     * One HTTP response into the ring. [contentBase] is the programme time the response starts at.
     *
     * Live: each connection is a new segment. Catch-up: the first response starts recording at programme
     * time [skipUntil]; later ones (a new chunk, or a retry after a drop) overlap what's recorded and are
     * spliced in – exactly, if the server repeats the same bytes; else just after the last frame we have
     * (same timestamps); else at [skipUntil] by programme time.
     */
    private enum class Phase { FIND_ENTRY, SPLICE, RECORD }

    private fun fetch(reqUrl: String, contentBase: Long, skipUntil: Long) {
        val request = Request.Builder().url(reqUrl).header("User-Agent", userAgent)
            .apply { if (referrer.isNotBlank()) header("Referer", referrer) }
            .build()
        val c = http.newCall(request)
        call = c
        c.execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code}")
            val input = resp.body?.byteStream() ?: throw IOException("empty response")
            limitWaits = 0
            val resuming = archive != null && written > 0
            var phase = when {
                resuming -> Phase.SPLICE
                archive != null -> Phase.FIND_ENTRY
                else -> Phase.RECORD
            }
            // Programme time in this response until we're recording for good (it may contain jumps itself).
            val here = TsTimeline()
            var hereBase = contentBase
            var sameClock: Boolean? = null
            var matched = 0
            val tailPackets = tailLen / Ts.PACKET
            val overlapTicks = (skipUntil - contentBase + 30_000).coerceAtLeast(30_000) * 90
            // A slow server would keep you waiting while we read up to the exact start: after a few seconds,
            // start at the keyframe we have (a little early). Splices must be exact, so no limit there.
            val deadline = SystemClock.elapsedRealtime() + FIRST_SKIP_BUDGET
            // Catch-up's first response records from each keyframe before the start time, provisionally (the
            // player can't read it yet), so playback begins on a complete picture at or just before it.
            var provisional = false
            var confirmed = false
            var isTs: Boolean? = null
            val buf = ByteArray(Ts.PACKET * 700)
            var have = 0
            if (phase == Phase.RECORD) beginStretch(now())
            while (!closed) {
                val n = input.read(buf, have, buf.size - have)
                if (n < 0) break
                if (n == 0) continue
                have += n
                lastDataAt = SystemClock.elapsedRealtime()
                if (isTs == null) {
                    if (have < 4 * Ts.PACKET) continue
                    val first = Ts.findSync(buf, 0, have)
                    if (first >= 0) {
                        isTs = true
                        if (first > 0) Log.i(TAG, "stream starts $first bytes before its first packet (${Ts.hex(buf, 0, min(have, 12))})")
                    } else if (have >= 64 * 1024) {
                        Log.i(TAG, "stream isn't MPEG-TS (${Ts.hex(buf, 0, 12)})")
                        if (archive != null) throw NotTsException()
                        isTs = false
                    } else {
                        continue
                    }
                }
                if (isTs == false) {
                    emit(buf, 0, have)
                    have = 0
                    continue
                }
                var from = 0
                var p = 0
                while (p + Ts.PACKET <= have) {
                    if (buf[p] != Ts.SYNC) {
                        // Out of step with the packets (a stream that starts mid-packet, or a join in the
                        // server's data that isn't on a packet boundary): find the next real packet start.
                        if (phase == Phase.RECORD) record(buf, from, p - from)
                        val q = Ts.findSync(buf, p, have)
                        if (q < 0) {
                            // None confirmed in what we have: keep just enough to find it with more data.
                            val keep = min(have - p, 2 * Ts.PACKET)
                            lostBytes += have - p - keep
                            p = have - keep
                            from = p
                            break
                        }
                        lostBytes += q - p
                        resyncs++
                        p = q
                        from = q
                        continue
                    }
                    val ts = Ts.pesTimestamp(buf, p)
                    val pid = if (ts >= 0) Ts.pid(buf, p) else -1
                    var at = 0L
                    if (ts >= 0 && (phase != Phase.RECORD || provisional)) {
                        if (!here.check(pid, ts)) {
                            hereBase += here.elapsedMs
                            here.reset()
                            here.check(pid, ts)
                        }
                        at = hereBase + here.offsetMs(ts)
                    }
                    if (phase == Phase.SPLICE) {
                        keys.feed(buf, p, -1)
                        // 1. The same bytes again: carry on straight after the last packet we recorded.
                        if (tailPackets > 0) {
                            if (Ts.samePacket(buf, p, tail, matched * Ts.PACKET)) {
                                if (++matched == tailPackets) {
                                    phase = Phase.RECORD
                                    from = p + Ts.PACKET
                                    resumedExactly++
                                    Log.i(TAG, "archive: carried on exactly where the last response stopped")
                                    p += Ts.PACKET
                                    continue
                                }
                            } else {
                                matched = if (Ts.samePacket(buf, p, tail, 0)) 1 else 0
                            }
                        }
                        if (ts >= 0) {
                            if (sameClock == null) {
                                val prev = clock.lastTs(pid)
                                if (prev != null) sameClock = Ts.delta(ts, prev) in -overlapTicks..(10L * 90_000)
                            }
                            val prev = clock.lastTs(pid)
                            if (sameClock == true) {
                                // 2. Same timestamps, different bytes: start at the first frame after ours.
                                if ((videoPid < 0 || pid == videoPid) && prev != null && Ts.delta(ts, prev) > 0) {
                                    phase = Phase.RECORD
                                    from = p
                                    newSegment(clockBase + clock.offsetMs(ts))
                                    Log.i(TAG, "archive: spliced in by timestamp")
                                }
                            } else if (at >= skipUntil) {
                                // 3. Unrelated timestamps: start at the programme time we've got to.
                                phase = Phase.RECORD
                                from = p
                                beginStretch(at)
                                Log.i(TAG, "archive: spliced in by programme time")
                            }
                        }
                        if (phase == Phase.SPLICE) {
                            p += Ts.PACKET
                            continue
                        }
                    } else if (phase == Phase.FIND_ENTRY) {
                        keys.feed(buf, p, -1)
                        if (ts >= 0) {
                            // Each video access unit is a candidate start; the keyframe finder confirms or rejects it.
                            val candidate = keys.videoPid >= 0 && pid == keys.videoPid
                            val noVideo = keys.mapKnown && keys.videoPid < 0 && at >= skipUntil
                            val late = at >= skipUntil + ENTRY_GIVE_UP
                            if (candidate || noVideo || late) {
                                phase = Phase.RECORD
                                from = p
                                beginStretch(at)
                                provisional = candidate && !late
                                confirmed = false
                                if (!provisional) commit(if (noVideo) "no video" else "no keyframe found")
                            }
                        }
                        if (phase == Phase.FIND_ENTRY) {
                            p += Ts.PACKET
                            continue
                        }
                    }
                    if (ts >= 0) {
                        if (videoPid < 0 && Ts.startsVideo(buf, p)) videoPid = pid
                        val prevTs = clock.lastTs(pid)
                        if (!clock.check(pid, ts)) {
                            val jumpMs = if (prevTs != null) Ts.delta(ts, prevTs) / 90 else 0
                            if (provisional) {
                                // The clock jumped before we've started: look for a keyframe again.
                                discardProvisional()
                                provisional = false
                                phase = Phase.FIND_ENTRY
                                from = p
                                continue
                            }
                            // Timestamp jump: everything from here is a new stretch; programme time carries on.
                            record(buf, from, p - from)
                            from = p
                            beginStretch(clockBase + clock.elapsedMs)
                            clock.check(pid, ts)
                            jumpCount++
                            Log.i(TAG, "stream clock jumped ${if (jumpMs > 0) "+" else ""}${"%.1f".format(jumpMs / 1000.0)} s on pid $pid${if (pid == keys.videoPid) " (video)" else ""} – new segment")
                        } else if (provisional && confirmed && (at >= skipUntil || SystemClock.elapsedRealtime() > deadline || written > capacity / 4)) {
                            provisional = false
                            commit(when {
                                at < skipUntil -> "server is slow"
                                clockBase <= skipUntil -> "keyframe ${(skipUntil - clockBase) / 1000} s before the start time"
                                else -> "first keyframe, ${(clockBase - skipUntil) / 1000} s after the start time"
                            })
                        }
                    }
                    val verdict = keys.feed(buf, p, written + (p - from))
                    if (verdict == TsKeyframes.KEY) {
                        var keyOffset = keys.decidedOffset
                        val keyAt = clockBase + clock.offsetMs(keys.decidedTs)
                        if (provisional && keyOffset > 0 && (!confirmed || keyAt <= skipUntil)) {
                            // A (later) start point, still not past where we're going: start there instead.
                            record(buf, from, p - from)
                            from = p
                            dropBefore(keyOffset)
                            keyOffset = 0
                            clockBase += clock.rebase(keys.decidedTs)
                            synchronized(lock) { segments[0].contentStart = clockBase }
                            confirmed = true
                        } else if (provisional && keyOffset == 0L) {
                            confirmed = true
                        }
                        addSeekPoint(clockBase + clock.offsetMs(keys.decidedTs), keyOffset)
                    }
                    if (provisional && !confirmed && keys.judging != 0L) {
                        // The candidate isn't a start point: look again – from the next packet if it was judged on
                        // its own first packet, else from this one (it may start the next access unit).
                        val atCandidate = written + (p - from) == 0L
                        discardProvisional()
                        provisional = false
                        phase = Phase.FIND_ENTRY
                        if (atCandidate) p += Ts.PACKET
                        from = p
                        continue
                    }
                    if (keys.mapChanges != mapChangesSeen) {
                        val changed = mapChangesSeen > 0
                        mapChangesSeen = keys.mapChanges
                        Log.i(TAG, "video: pid ${keys.videoPid}, ${TsKeyframes.codecName(keys.streamType)}" + if (changed) " – changed, new segment" else "")
                        if (changed) {
                            if (provisional) {
                                discardProvisional()
                                provisional = false
                                phase = Phase.FIND_ENTRY
                                p += Ts.PACKET
                                from = p
                                continue
                            }
                            // A different video stream (providers switch source/codec): it needs its own player
                            // item with its own stream map. The new map's packet starts it; programme time carries on.
                            record(buf, from, p - from)
                            from = p
                            beginStretch(clockBase + clock.elapsedMs)
                            jumpCount++
                        }
                    }
                    keys.psi?.let { m -> synchronized(lock) { segments.last().let { if (it.map == null) it.map = m } } }
                    p += Ts.PACKET
                }
                if (phase == Phase.RECORD) {
                    record(buf, from, p - from)
                    if (archive != null) {
                        val reached = clockBase + clock.elapsedMs
                        if (reached > recordedUpTo) recordedUpTo = reached
                    }
                }
                System.arraycopy(buf, p, buf, 0, have - p)
                have -= p
            }
        }
    }

    /**
     * Provisional data only (nothing read yet, all of it in the ring without wrapping): keep what's from
     * [cut] on, moved to the front.
     */
    private fun dropBefore(cut: Long) {
        if (cut <= 0) return
        val n = (written - cut).toInt()
        if (n > 0) {
            val tmp = ByteArray(n)
            store.read(cut, tmp, 0, n)
            store.write(0, tmp, 0, n)
        }
        synchronized(lock) {
            written = n.coerceAtLeast(0).toLong()
            var k = 0
            for (i in 0 until seekCount) {
                val o = seekOffsets[(seekStart + i) % SEEK_POINTS]
                if (o >= cut) {
                    seekOffsets[k] = o - cut
                    seekTimes[k] = seekTimes[(seekStart + i) % SEEK_POINTS]
                    k++
                }
            }
            seekStart = 0
            seekCount = k
        }
    }

    /** Readers may read (catch-up: once the start has been settled on). */
    @Volatile private var started = archive == null

    private fun commit(why: String) {
        if (started) return
        synchronized(lock) {
            started = true
            lock.notifyAll()
        }
        Log.i(TAG, "archive: playing from ${hhmmss(clockBase)} ($why)")
    }

    private var mapChangesSeen = 0

    /** Forget a provisional start (catch-up's first response, before anyone has read it). */
    private fun discardProvisional() {
        synchronized(lock) {
            written = 0
            seekCount = 0
            seekStart = 0
            segments[0].map = null
        }
        tailLen = 0
        lastCc.fill(-1)
        lastCcIn.fill(-1)
        counterFixes = 0
        pendingSegment = false
        recordedUpTo = archive?.start ?: 0L
    }

    /**
     * Whole TS packets into the ring. Catch-up also remembers the last packets as the server sent them (to
     * find them again in the next response) and renumbers continuity counters so they always run on:
     * archives are stitched from separately recorded files, and a counter that happens to repeat at a
     * join makes the player throw away a packet as a "duplicate" – a corrupt keyframe and seconds of
     * broken picture.
     */
    private fun record(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (archive != null) {
            remember(b, off, len)
            var p = off
            while (p < off + len) {
                renumber(b, p)
                p += Ts.PACKET
            }
        }
        emit(b, off, len)
    }

    private fun remember(b: ByteArray, off: Int, len: Int) {
        if (len >= tail.size) {
            System.arraycopy(b, off + len - tail.size, tail, 0, tail.size)
            tailLen = tail.size
        } else {
            val keep = min(tailLen, tail.size - len)
            System.arraycopy(tail, tailLen - keep, tail, 0, keep)
            System.arraycopy(b, off, tail, keep, len)
            tailLen = keep + len
        }
    }

    private val lastCc = IntArray(0x2000) { -1 }

    /** Counters were renumbered (each is a join in the server's data). */
    @Volatile var counterFixes = 0
        private set

    private fun renumber(b: ByteArray, p: Int) {
        val flags = b[p + 3].toInt()
        if (flags and 0x10 == 0) return // no payload: players don't count these
        val pid = Ts.pid(b, p)
        if (pid == 0x1FFF) return
        val cc = flags and 0x0F
        val prev = lastCc[pid]
        val want = if (prev < 0) cc else (prev + 1) and 0x0F
        lastCc[pid] = want
        if (want != cc) {
            if (prev >= 0 && (lastCcIn[pid] + 1) and 0x0F != cc) counterFixes++
            b[p + 3] = ((flags and 0xF0) or want).toByte()
        }
        lastCcIn[pid] = cc
    }

    private val lastCcIn = IntArray(0x2000) { -1 }

    /** A new stretch of timestamps, starting at programme time [contentStart], in a new segment. */
    private fun beginStretch(contentStart: Long) {
        clock.reset()
        clockBase = contentStart
        newSegment(contentStart)
    }

    private fun newSegment(contentStart: Long) {
        pendingSegment = true
        pendingContentStart = contentStart
    }

    private fun emit(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (pendingSegment) {
            pendingSegment = false
            val seg = synchronized(lock) {
                if (written == 0L) {
                    // The first segment is already queued for playback: just firm up where it starts.
                    segments[0].contentStart = pendingContentStart
                    null
                } else {
                    Segment(written, pendingContentStart).also { segments += it }
                }
            }
            if (seg != null) onNewSegment?.invoke(seg)
        }
        var off2 = off
        var left = len
        while (left > 0 && !closed) {
            val chunk = min(left, 64 * 1024)
            awaitSpace(chunk)
            if (closed) return
            write(b, off2, chunk)
            off2 += chunk
            left -= chunk
        }
        if (archive == null) {
            val t = SystemClock.elapsedRealtime()
            if (t - lastSample >= 500) {
                lastSample = t
                addSample(now(), written)
            }
        }
    }

    /**
     * Catch-up never overwrites what the player hasn't read yet, and reads ahead at most [AHEAD_SHARE] of
     * the ring: the rest keeps what was just watched, for jumping back without asking the server again.
     */
    private fun awaitSpace(len: Int) {
        if (archive == null) return
        val ahead = min(capacity * AHEAD_SHARE / 100, capacity - 2 * safety)
        synchronized(lock) {
            while (!closed && written + len - readPos > ahead) {
                try {
                    lock.wait(250)
                } catch (e: InterruptedException) {
                    return
                }
            }
        }
    }

    fun noteRead(position: Long, generation: Int) {
        if (generation != readGeneration || position <= readPos) return
        readPos = position
        if (archive != null) synchronized(lock) { lock.notifyAll() }
    }

    /** The player is about to read from [offset] (a jump within the recording). */
    fun restartReading(offset: Long) = synchronized(lock) {
        readGeneration++
        readPos = offset
        lock.notifyAll()
    }

    private fun addSeekPoint(time: Long, offset: Long) = synchronized(lock) {
        val i = (seekStart + seekCount) % SEEK_POINTS
        seekTimes[i] = time
        seekOffsets[i] = offset
        if (seekCount < SEEK_POINTS) seekCount++ else seekStart = (seekStart + 1) % SEEK_POINTS
    }

    /**
     * Where to start playing to show what's at [offset]: the keyframe at or just before it (starting
     * elsewhere leaves the picture frozen until the next one – up to 10 s on some channels), or [offset]
     * itself if there's no keyframe close enough before it.
     */
    fun entryPoint(offset: Long): Long = synchronized(lock) {
        if (seekCount == 0) return offset
        // Last keyframe at or before the offset.
        var lo = 0
        var hi = seekCount - 1
        if (seekOffsets[seekStart] <= offset) {
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (seekOffsets[(seekStart + mid) % SEEK_POINTS] <= offset) lo = mid else hi = mid - 1
            }
            val k = seekOffsets[(seekStart + lo) % SEEK_POINTS]
            if (k >= oldestOffset && offset - k <= MAX_LEAD_BYTES) return k
        }
        // Gone from the buffer (or too far back): the first keyframe after it instead.
        for (i in 0 until seekCount) {
            val k = seekOffsets[(seekStart + i) % SEEK_POINTS]
            if (k >= offset && k >= oldestOffset) return if (k - offset <= MAX_LEAD_BYTES) k else offset
        }
        offset
    }

    /** "Back to live": the newest keyframe that has at least [cushionMs] of stream after it, or null. */
    fun latestEntry(cushionMs: Long): Long? = synchronized(lock) {
        val latest = now() - cushionMs
        for (i in seekCount - 1 downTo 0) {
            val k = seekOffsets[(seekStart + i) % SEEK_POINTS]
            if (k < oldestOffset) break
            val t = timeAt(k)
            if (t <= latest) return if (latest - t <= 15_000) k else null
        }
        null
    }

    /**
     * Catch-up: where to start playing to watch programme time [t] from what's recorded – the keyframe at
     * or just before it, as a segment starting there – or null if it isn't in the ring.
     */
    fun seekPoint(t: Long): Segment? = synchronized(lock) {
        if (archive == null || seekCount == 0 || t > recordedUpTo - 2_000) return null
        if (seekTimes[seekStart] > t) return null
        var lo = 0
        var hi = seekCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (seekTimes[(seekStart + mid) % SEEK_POINTS] <= t) lo = mid else hi = mid - 1
        }
        val k = (seekStart + lo) % SEEK_POINTS
        // Too far before the target (a gap in the points), or already overwritten.
        if (t - seekTimes[k] > 15_000 || seekOffsets[k] < oldestOffset) return null
        Segment(seekOffsets[k], seekTimes[k])
    }

    /** When data last arrived (elapsedRealtime). */
    @Volatile var lastDataAt = SystemClock.elapsedRealtime()
        private set

    private fun write(b: ByteArray, off: Int, n: Int) {
        lastDataAt = SystemClock.elapsedRealtime()
        var done = 0
        while (done < n) {
            val pos = (written + done) % capacity
            val chunk = min((n - done).toLong(), capacity - pos).toInt()
            store.write(pos, b, off + done, chunk)
            done += chunk
        }
        synchronized(lock) {
            written += n
            lock.notifyAll()
        }
    }

    private fun addSample(time: Long, offset: Long) = synchronized(lock) {
        val i = (indexStart + indexCount) % INDEX_SIZE
        times[i] = time
        offsets[i] = offset
        if (indexCount < INDEX_SIZE) indexCount++ else indexStart = (indexStart + 1) % INDEX_SIZE
    }

    private val safety: Long get() = min(8L shl 20, capacity / 8)

    /** Oldest byte still in the ring (with a margin so a reader never races the writer). */
    val oldestOffset: Long get() = max(0, written - capacity + safety)

    /** Logical offset of the broadcast moment [time] (live; clamped to what's buffered). */
    fun offsetAt(time: Long): Long = synchronized(lock) {
        if (indexCount == 0) return 0
        var lo = 0
        var hi = indexCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (times[(indexStart + mid) % INDEX_SIZE] <= time) lo = mid else hi = mid - 1
        }
        offsets[(indexStart + lo) % INDEX_SIZE].coerceIn(oldestOffset, written)
    }

    /** Broadcast moment of a logical offset (live; approximate, from the samples). */
    fun timeAt(offset: Long): Long = synchronized(lock) {
        if (indexCount == 0) return startedAt
        var lo = 0
        var hi = indexCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (offsets[(indexStart + mid) % INDEX_SIZE] <= offset) lo = mid else hi = mid - 1
        }
        times[(indexStart + lo) % INDEX_SIZE]
    }

    /** Earliest broadcast moment still in the buffer (live). */
    val oldestTime: Long get() = timeAt(oldestOffset)

    /** Bytes per second over roughly the last [windowMs] (live; for stream info). */
    fun recentBitrate(windowMs: Long = 10_000): Long = synchronized(lock) {
        if (indexCount < 2) return 0
        val last = (indexStart + indexCount - 1) % INDEX_SIZE
        val t1 = times[last]
        var k = indexCount - 1
        while (k > 0 && t1 - times[(indexStart + k) % INDEX_SIZE] < windowMs) k--
        val first = (indexStart + k) % INDEX_SIZE
        val dt = t1 - times[first]
        if (dt <= 0) 0 else (offsets[last] - offsets[first]) * 1000 / dt
    }

    /** Blocking read at [position]. */
    fun read(position: Long, b: ByteArray, off: Int, len: Int): Int {
        synchronized(lock) {
            while ((written <= position || !started) && !finished && !closed) {
                try {
                    lock.wait(100)
                } catch (e: InterruptedException) {
                    throw InterruptedIOException()
                }
            }
        }
        if (closed) return C.RESULT_END_OF_INPUT
        val w = written
        if (position >= w) {
            error?.let { throw it }
            return C.RESULT_END_OF_INPUT
        }
        if (position < w - capacity + safety / 2) throw OverwrittenException()
        val n = min(len.toLong(), w - position).toInt()
        var done = 0
        while (done < n) {
            val pos = (position + done) % capacity
            val chunk = min((n - done).toLong(), capacity - pos).toInt()
            store.read(pos, b, off + done, chunk)
            done += chunk
        }
        return n
    }

    /** Stops recording. The ring store itself is owned (and reused) by the caller. */
    fun close() {
        closed = true
        call?.cancel()
        thread?.interrupt()
        synchronized(lock) { lock.notifyAll() }
    }

    val healthy: Boolean get() = !closed && error == null && !finished

    class OverwrittenException : IOException("Paused for longer than the live buffer holds")

    /** The archive isn't plain MPEG-TS, so it can't be recorded – it's played directly instead. */
    class NotTsException : IOException("The archive isn't an MPEG-TS stream")

    companion object {
        private const val TAG = "NimbusBuffer"
        private const val INDEX_SIZE = 4 * 60 * 60 * 2 // 4 h of half-second samples
        private const val TAIL_PACKETS = 8
        private const val FIRST_SKIP_BUDGET = 3_000L
        /** Catch-up start: give up waiting for a keyframe this far past the start time. */
        private const val ENTRY_GIVE_UP = 15_000L
        /** Entry points further than this from where you asked (in bytes) aren't used. */
        private const val MAX_LEAD_BYTES = 32L shl 20
        private const val SEEK_POINTS = 8192
        private const val AHEAD_SHARE = 60L

        private fun hhmmss(ms: Long): String =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))
    }
}

/** ExoPlayer's view of a recording: a live progressive stream read from the ring. */
class LiveRecordingDataSource(private val rec: LiveRecording, private val startOffset: Long) : BaseDataSource(true) {
    private val generation = rec.readGeneration
    /**
     * The stream map goes first (see [LiveRecording.streamMap]); it's known once data has arrived, so it's
     * settled on the first read and kept for retries.
     */
    private var map: ByteArray? = null
    private var mapPos = 0
    private var skip = 0L
    private var opened = false
    private var position = 0L
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        openAt(dataSpec.position)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    internal fun openAt(position: Long) {
        skip = position
        map?.let { place(it) }
        opened = true
    }

    private fun place(m: ByteArray) {
        mapPos = minOf(skip, m.size.toLong()).toInt()
        position = startOffset + maxOf(0L, skip - m.size)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) throw IOException("closed")
        val map = map ?: run {
            rec.awaitData(startOffset)
            (rec.streamMap(startOffset) ?: ByteArray(0)).also {
                map = it
                place(it)
            }
        }
        if (mapPos < map.size) {
            val n = minOf(length, map.size - mapPos)
            System.arraycopy(map, mapPos, buffer, offset, n)
            mapPos += n
            bytesTransferred(n)
            return n
        }
        // This item covers one connection's data: end where the next connection's data begins.
        val end = rec.segmentEndAfter(startOffset)
        if (end != null && position >= end) return C.RESULT_END_OF_INPUT
        val max = if (end != null) min(length.toLong(), end - position).toInt() else length
        val n = rec.read(position, buffer, offset, max)
        if (n > 0) {
            position += n
            rec.noteRead(position, generation)
            bytesTransferred(n)
        }
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (!opened) return
        opened = false
        transferEnded()
    }
}
