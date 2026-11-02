package io.github.lucasclgibson.nimbus.player

/**
 * Just enough MPEG-TS parsing to follow a stream's clock: the timestamp of each audio/video PES.
 * Used to spot timestamp jumps (a provider restarting its source, archive chunks stitched together) and
 * to know how much programme has gone by (for resuming catch-up exactly where it left off).
 */
object Ts {
    const val PACKET = 188
    const val WRAP = 1L shl 33 // PTS/DTS are 33-bit, 90 kHz

    /** First position in [from, to) where three packets in a row start, or -1. */
    fun findSync(b: ByteArray, from: Int, to: Int): Int {
        var q = from
        while (q + 2 * PACKET < to) {
            if (b[q] == SYNC && b[q + PACKET] == SYNC && b[q + 2 * PACKET] == SYNC) return q
            q++
        }
        return -1
    }

    fun hex(b: ByteArray, off: Int, len: Int): String = (off until off + len).joinToString(" ") { "%02x".format(b[it]) }

    fun pid(b: ByteArray, p: Int): Int = ((b[p + 1].toInt() and 0x1F) shl 8) or (b[p + 2].toInt() and 0xFF)

    /**
     * Timestamp (90 kHz; DTS if present, otherwise PTS) of an audio/video PES that starts in the packet at
     * [p], or -1 if this packet doesn't start one.
     */
    fun pesTimestamp(b: ByteArray, p: Int): Long {
        if (b[p] != SYNC) return -1
        if (b[p + 1].toInt() and 0x40 == 0) return -1 // payload_unit_start_indicator
        val afc = (b[p + 3].toInt() shr 4) and 3
        if (afc == 0 || afc == 2) return -1 // no payload
        var q = p + 4
        if (afc == 3) q += 1 + (b[p + 4].toInt() and 0xFF)
        val end = p + PACKET
        if (q + 14 > end) return -1
        if (b[q] != ZERO || b[q + 1] != ZERO || b[q + 2] != ONE) return -1
        val streamId = b[q + 3].toInt() and 0xFF
        if (streamId !in 0xC0..0xEF && streamId != 0xBD) return -1
        val flags = (b[q + 7].toInt() shr 6) and 3
        if (flags < 2) return -1
        val at = if (flags == 3 && q + 19 <= end) q + 14 else q + 9
        return readTs(b, at)
    }

    /** Where the payload of the packet at [p] starts, or -1 if it has none. */
    fun payloadStart(b: ByteArray, p: Int): Int {
        val afc = (b[p + 3].toInt() shr 4) and 3
        if (afc and 1 == 0) return -1
        val q = if (afc == 3) p + 5 + (b[p + 4].toInt() and 0xFF) else p + 4
        return if (q < p + PACKET) q else -1
    }

    /** Does the packet at [p] start a video PES? */
    fun startsVideo(b: ByteArray, p: Int): Boolean {
        if (b[p + 1].toInt() and 0x40 == 0) return false
        val afc = (b[p + 3].toInt() shr 4) and 3
        if (afc == 0 || afc == 2) return false
        var q = p + 4
        if (afc == 3) q += 1 + (b[p + 4].toInt() and 0xFF)
        if (q + 4 > p + PACKET) return false
        return b[q] == ZERO && b[q + 1] == ZERO && b[q + 2] == ONE && (b[q + 3].toInt() and 0xF0) == 0xE0
    }

    /** Is the packet at [p] flagged as a random access point (a keyframe starts here)? */
    fun randomAccess(b: ByteArray, p: Int): Boolean {
        val afc = (b[p + 3].toInt() shr 4) and 3
        return afc and 2 != 0 && (b[p + 4].toInt() and 0xFF) > 0 && b[p + 5].toInt() and 0x40 != 0
    }

    /** Are the packets at [a]/[ai] and [b]/[bi] byte-for-byte the same? */
    fun samePacket(a: ByteArray, ai: Int, b: ByteArray, bi: Int): Boolean {
        for (k in 0 until PACKET) if (a[ai + k] != b[bi + k]) return false
        return true
    }

    private fun readTs(b: ByteArray, i: Int): Long =
        (((b[i].toLong() shr 1) and 0x07L) shl 30) or
            ((b[i + 1].toLong() and 0xFF) shl 22) or
            (((b[i + 2].toLong() and 0xFF) shr 1) shl 15) or
            ((b[i + 3].toLong() and 0xFF) shl 7) or
            ((b[i + 4].toLong() and 0xFF) shr 1)

    /** a - b on the 33-bit clock, as a signed difference. */
    fun delta(a: Long, b: Long): Long {
        var d = (a - b).mod(WRAP)
        if (d > WRAP / 2) d -= WRAP
        return d
    }

    const val SYNC: Byte = 0x47
    private const val ZERO: Byte = 0
    private const val ONE: Byte = 1
}

/**
 * Follows one stretch of continuous timestamps. [check] reports a jump when a stream's clock leaps
 * forwards or backwards; [elapsedMs] is how much programme the stretch has covered so far.
 */
class TsTimeline {
    private val last = HashMap<Int, Long>()
    private var first = -1L
    var elapsedMs = 0L
        private set

    fun reset() {
        last.clear()
        first = -1L
        elapsedMs = 0L
    }

    /** Make [ts] the start of the stretch; returns how far (ms) the start moved. */
    fun rebase(ts: Long): Long {
        if (first < 0) return 0
        val shift = Ts.delta(ts, first) / 90
        first = ts
        elapsedMs = (elapsedMs - shift).coerceAtLeast(0)
        return shift
    }

    /** Last timestamp seen for [pid] in this stretch. */
    fun lastTs(pid: Int): Long? = last[pid]

    /** Programme time of [ts] relative to the start of this stretch (ms). */
    fun offsetMs(ts: Long): Long = if (first < 0) 0 else Ts.delta(ts, first) / 90

    /**
     * Feed a PES timestamp for [pid]. Returns false (and records nothing) if it breaks the timeline,
     * in which case the caller should start a new stretch at this packet.
     */
    fun check(pid: Int, ts: Long): Boolean {
        val prev = last[pid]
        if (prev != null) {
            val d = Ts.delta(ts, prev)
            if (d > JUMP_FORWARD || d < -JUMP_BACK) return false
        }
        last[pid] = ts
        if (first < 0) first = ts
        val el = Ts.delta(ts, first) / 90
        if (el in (elapsedMs + 1)..MAX_STRETCH_MS) elapsedMs = el
        return true
    }

    companion object {
        // Within one elementary stream, consecutive PES timestamps are tens of ms apart.
        private const val JUMP_FORWARD = 5L * 90_000
        private const val JUMP_BACK = 1L * 90_000
        private const val MAX_STRETCH_MS = 12L * 60 * 60 * 1000
    }
}

/**
 * Finds the places a decoder can start from scratch: video access units that carry the codec's parameter
 * sets AND are an intra picture (H.264 IDR or I-slice, HEVC IRAP, MPEG-2 I-picture after a sequence
 * header). Many IPTV streams send parameter sets with every frame, or flag every I-frame as a "random
 * access point" while only repeating SPS/PPS every 10 s; either way only a true start point gives a
 * moving picture straight away. The PAT/PMT tell us which stream is the video and its codec – which
 * can change when a provider reconnects you to a different source.
 */
class TsKeyframes {
    var videoPid = -1
        private set
    var streamType = -1
        private set
    /** A PMT has been read (so [videoPid] < 0 means there's no video we know). */
    var mapKnown = false
        private set
    /** Counts changes of the video stream (PID or codec). */
    var mapChanges = 0
        private set
    private var pmtPid = -1
    private var pat: ByteArray? = null
    private var pmt: ByteArray? = null

    /** The latest PAT and PMT packets, back to back (null until both have been seen). */
    @Volatile var psi: ByteArray? = null
        private set

    // The access unit being judged.
    private var pending = false
    private var pendingOffset = 0L
    private var pendingTs = -1L
    private var looked = 0
    private var params = false
    private val carry = ByteArray(3)
    private var carryLen = 0

    /** The access unit judged by the last [feed] that returned [KEY] or [NOT_KEY]: offset and timestamp. */
    var decidedOffset = -1L
        private set
    var decidedTs = -1L
        private set

    /** Offset of the access unit still being judged, or -1. */
    val judging: Long get() = if (pending) pendingOffset else -1L

    /**
     * Feed every packet in order with its offset in the recording. Returns [KEY] or [NOT_KEY] when the access
     * unit starting at [decidedOffset] has been judged, else [NONE].
     */
    fun feed(b: ByteArray, p: Int, offset: Long): Int {
        val pid = Ts.pid(b, p)
        val start = b[p + 1].toInt() and 0x40 != 0
        if (start && pid == 0) {
            parsePat(b, p)
            pat = b.copyOfRange(p, p + Ts.PACKET)
            updatePsi()
            return NONE
        }
        if (start && pid == pmtPid) {
            parsePmt(b, p)
            pmt = b.copyOfRange(p, p + Ts.PACKET)
            updatePsi()
            return NONE
        }
        if (pid != videoPid) return NONE
        var from = Ts.payloadStart(b, p)
        if (from < 0) return NONE
        if (start) {
            // An access unit still undecided here never showed what it was: not a usable start.
            pending = false
            val ts = Ts.pesTimestamp(b, p)
            if (ts < 0 || from + 9 > p + Ts.PACKET) return NONE
            from += 9 + (b[from + 8].toInt() and 0xFF)
            pending = true
            pendingOffset = offset
            pendingTs = ts
            looked = 0
            params = false
            carryLen = 0
        }
        if (!pending) return NONE
        val r = scan(b, from, p + Ts.PACKET)
        // Captions and other SEI data can push the picture a few packets in.
        if (r == MAYBE && ++looked < LOOKAHEAD) return NONE
        pending = false
        decidedOffset = pendingOffset
        decidedTs = pendingTs
        return if (r == KEY) KEY else NOT_KEY
    }

    private fun scan(b: ByteArray, from: Int, to: Int): Int {
        // A start code split across packets.
        if (carryLen == 3 && from + 2 < to) {
            val k = when {
                carry[0].toInt() == 0 && carry[1].toInt() == 0 && carry[2].toInt() == 1 -> 0
                carry[1].toInt() == 0 && carry[2].toInt() == 0 && b[from].toInt() == 1 -> 1
                carry[2].toInt() == 0 && b[from].toInt() == 0 && b[from + 1].toInt() == 1 -> 2
                else -> -1
            }
            if (k >= 0) {
                val r = nal(b, from + k, to)
                if (r != MAYBE) return r
            }
        }
        var i = from
        while (i + 3 < to) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val r = nal(b, i + 3, to)
                if (r != MAYBE) return r
                i += 3
            } else {
                i++
            }
        }
        if (to - from >= 3) {
            System.arraycopy(b, to - 3, carry, 0, 3)
            carryLen = 3
        }
        return MAYBE
    }

    /** One NAL unit / start code whose header byte is at [h]: decides, or MAYBE to keep looking. */
    private fun nal(b: ByteArray, h: Int, to: Int): Int {
        if (h >= to) return MAYBE
        val x = b[h].toInt() and 0xFF
        when (streamType) {
            0x1B -> { // H.264
                if (x and 0x80 != 0) return NOT_KEY // forbidden bit set: this isn't H.264
                when (x and 0x1F) {
                    7, 8 -> params = true // SPS, PPS
                    5 -> return if (params) KEY else NOT_KEY // IDR
                    1 -> {
                        if (!params) return NOT_KEY
                        val t = sliceTypeH264(b, h + 1, to)
                        return when {
                            t < 0 -> KEY // header cut off by the packet end; with parameter sets it's nearly always an I-frame
                            t % 5 == 2 || t % 5 == 4 -> KEY // I / SI
                            else -> NOT_KEY
                        }
                    }
                }
            }
            0x24 -> { // HEVC
                if (h + 1 >= to) return MAYBE
                val y = b[h + 1].toInt() and 0xFF
                // Base-layer NAL header: forbidden bit 0, layer id 0, temporal id + 1 > 0. Anything else isn't HEVC.
                if (x and 0x81 != 0 || y and 0xF8 != 0 || y and 0x07 == 0) return NOT_KEY
                when ((x shr 1) and 0x3F) {
                    32, 33, 34 -> params = true // VPS, SPS, PPS
                    in 16..21 -> return if (params) KEY else NOT_KEY // IRAP (BLA / IDR / CRA)
                    in 0..15 -> return NOT_KEY
                }
            }
            0x01, 0x02 -> when (x) { // MPEG-1/2 video
                0xB3 -> params = true // sequence header
                0x00 -> { // picture: picture_coding_type
                    if (h + 2 >= to) return MAYBE
                    return if (params && (b[h + 2].toInt() shr 3) and 7 == 1) KEY else NOT_KEY
                }
            }
            else -> return NOT_KEY
        }
        return MAYBE
    }

    /** H.264 slice_type (after first_mb_in_slice), or -1 if the header runs past [to]. */
    private fun sliceTypeH264(b: ByteArray, from: Int, to: Int): Int {
        var bit = 0
        fun readBit(): Int {
            val i = from + (bit shr 3)
            if (i >= to) return -1
            val v = (b[i].toInt() shr (7 - (bit and 7))) and 1
            bit++
            return v
        }
        fun ue(): Int {
            var zeros = 0
            while (true) {
                val v = readBit()
                if (v < 0) return -1
                if (v == 1) break
                if (++zeros > 20) return -1
            }
            var value = 0
            repeat(zeros) {
                val v = readBit()
                if (v < 0) return -1
                value = (value shl 1) or v
            }
            return (1 shl zeros) - 1 + value
        }
        if (ue() < 0) return -1
        return ue()
    }

    private fun updatePsi() {
        val a = pat ?: return
        val m = pmt ?: return
        if (psi?.let { it.size == 2 * Ts.PACKET && Ts.samePacket(it, 0, a, 0) && Ts.samePacket(it, Ts.PACKET, m, 0) } == true) return
        psi = a + m
    }

    private fun section(b: ByteArray, p: Int): Int {
        val q = Ts.payloadStart(b, p)
        if (q < 0) return -1
        val s = q + 1 + (b[q].toInt() and 0xFF)
        return if (s + 12 < p + Ts.PACKET) s else -1
    }

    private fun parsePat(b: ByteArray, p: Int) {
        val s = section(b, p)
        if (s < 0 || b[s].toInt() != 0x00) return
        val end = minOf(s + 3 + (((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF)) - 4, p + Ts.PACKET - 4)
        var i = s + 8
        while (i + 4 <= end) {
            val program = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            if (program != 0) {
                pmtPid = ((b[i + 2].toInt() and 0x1F) shl 8) or (b[i + 3].toInt() and 0xFF)
                return
            }
            i += 4
        }
    }

    private fun parsePmt(b: ByteArray, p: Int) {
        val s = section(b, p)
        if (s < 0 || b[s].toInt() != 0x02) return
        mapKnown = true
        val end = minOf(s + 3 + (((b[s + 1].toInt() and 0x0F) shl 8) or (b[s + 2].toInt() and 0xFF)) - 4, p + Ts.PACKET)
        var i = s + 12 + (((b[s + 10].toInt() and 0x0F) shl 8) or (b[s + 11].toInt() and 0xFF))
        while (i + 5 <= end) {
            val type = b[i].toInt() and 0xFF
            val pid = ((b[i + 1].toInt() and 0x1F) shl 8) or (b[i + 2].toInt() and 0xFF)
            if (type == 0x1B || type == 0x24 || type == 0x01 || type == 0x02) {
                if (pid != videoPid || type != streamType) {
                    videoPid = pid
                    streamType = type
                    mapChanges++
                    pending = false
                }
                return
            }
            i += 5 + (((b[i + 3].toInt() and 0x0F) shl 8) or (b[i + 4].toInt() and 0xFF))
        }
    }

    companion object {
        const val NONE = 0
        const val KEY = 1
        const val NOT_KEY = 2
        private const val MAYBE = 3
        private const val LOOKAHEAD = 16

        fun codecName(type: Int): String = when (type) {
            0x1B -> "H.264"
            0x24 -> "HEVC"
            0x01, 0x02 -> "MPEG-2"
            else -> "0x" + Integer.toHexString(type)
        }
    }
}
