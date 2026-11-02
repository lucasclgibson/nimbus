package io.github.lucasclgibson.nimbus

import io.github.lucasclgibson.nimbus.player.ArchivePlan
import io.github.lucasclgibson.nimbus.player.LiveRecording
import io.github.lucasclgibson.nimbus.player.RingStore
import io.github.lucasclgibson.nimbus.player.Ts
import io.github.lucasclgibson.nimbus.player.TsKeyframes
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/** Catch-up recording: responses that drop or end are spliced back together without repeats or gaps. */
class RecorderTest {
    private fun ts(name: String): ByteArray = javaClass.getResourceAsStream("/ts/$name")!!.readBytes()

    private val clean = ts("clean.ts")

    /** Where the first complete picture starts: recordings begin there. */
    private val firstKey = run {
        val k = TsKeyframes()
        var p = 0
        while (k.feed(clean, p, p.toLong()) != TsKeyframes.KEY) p += Ts.PACKET
        k.decidedOffset.toInt()
    }
    private val fromKey = clean.copyOfRange(firstKey, clean.size)

    /** The same programme with an unrelated clock (as a server that re-stamps each response would send). */
    private val restamped = ts("spliced.ts").copyOfRange(clean.size, 2 * clean.size)

    /** Same timestamps, different bytes (as a server that re-packetises each response would send). */
    private val repacked = clean.copyOf().also { b ->
        var p = 0
        while (p + Ts.PACKET <= b.size) {
            b[p + Ts.PACKET - 1] = (b[p + Ts.PACKET - 1].toInt() xor 0x55).toByte()
            p += Ts.PACKET
        }
    }

    private class ArrayRing(override val capacity: Long) : RingStore {
        private val data = ByteArray(capacity.toInt())
        override fun write(pos: Long, b: ByteArray, off: Int, len: Int) = System.arraycopy(b, off, data, pos.toInt(), len)
        override fun read(pos: Long, b: ByteArray, off: Int, len: Int) = System.arraycopy(data, pos.toInt(), b, off, len)
        override fun close() = Unit
    }

    /** Delivers [bytes] up to [dropAt], then fails like a reset connection (or ends cleanly if [dropAt] is null). */
    private class Feed(private val bytes: ByteArray, private val dropAt: Int?) : InputStream() {
        private var pos = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val stop = dropAt ?: bytes.size
            if (pos >= stop) {
                if (dropAt != null) throw IOException("connection reset")
                return -1
            }
            val n = minOf(len, stop - pos, 7_000) // arbitrary, unaligned reads
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
        }
    }

    /** Records an archive whose responses are [responses] in turn; returns the recording once it's done. */
    private fun record(vararg responses: () -> InputStream): LiveRecording = recordFrom(T0, *responses)

    private fun recordFrom(start: Long, vararg responses: () -> InputStream): LiveRecording {
        var served = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val feed = responses[served++]()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(object : ResponseBody() {
                    private val src = feed.source().buffer()
                    override fun contentType(): MediaType? = null
                    override fun contentLength(): Long = -1
                    override fun source(): BufferedSource = src
                })
                .build()
        }.build()
        // Every request asks for the whole programme again from its start (the overlap gets spliced away).
        val plan = ArchivePlan(start, finished = { it >= T0 + 9_500 }) { from, _ -> if (from >= T0 + 9_500) null else "http://archive.test/x.ts" to T0 }
        val rec = LiveRecording(ArrayRing(8L shl 20), http, "archive:test", "test", "", plan)
        rec.start()
        val until = System.currentTimeMillis() + 20_000
        while (!rec.finished && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("recording finished", rec.finished)
        assertEquals(responses.size, served)
        return rec
    }

    private fun bytesOf(rec: LiveRecording): ByteArray {
        val out = ByteArray(rec.written.toInt())
        var pos = 0
        while (pos < out.size) pos += rec.read(pos.toLong(), out, pos, out.size - pos)
        return out
    }

    @Test
    fun dropAndRepeatOfSameBytesIsSplicedExactly() {
        val rec = record({ Feed(clean, dropAt = 150_001) }, { Feed(clean, dropAt = null) })
        assertTrue(rec.completed)
        assertEquals(1, rec.resumedExactly)
        assertEquals(1, rec.segmentList().size)
        assertArrayEquals(fromKey, bytesOf(rec))
    }

    @Test
    fun cleanEndThenRepeatIsSplicedExactly() {
        val rec = record({ Feed(clean.copyOf(200_000), dropAt = null) }, { Feed(clean, dropAt = null) })
        assertTrue(rec.completed)
        assertEquals(1, rec.segmentList().size)
        assertArrayEquals(fromKey, bytesOf(rec))
    }

    @Test
    fun sameTimestampsDifferentBytesSplicesAfterLastFrame() {
        val rec = record({ Feed(clean, dropAt = 150_001) }, { Feed(repacked, dropAt = null) })
        assertTrue(rec.completed)
        assertEquals(0, rec.resumedExactly)
        val segs = rec.segmentList()
        assertEquals(2, segs.size)
        val data = bytesOf(rec)
        val second = segs[1].offset.toInt()
        assertTrue("splice starts with a video frame", Ts.startsVideo(data, second))
        // Nothing much repeated: about one programme's worth of data in total.
        assertTrue("written ${data.size} vs ${clean.size}", data.size in (clean.size - 20_000)..(clean.size + 20_000))
        // The second segment carries on (in programme time) where the first got to.
        assertTrue("content start ${segs[1].contentStart - T0}", segs[1].contentStart - T0 in 4_000L..6_500L)
    }

    @Test
    fun unrelatedTimestampsSpliceByProgrammeTime() {
        val rec = record({ Feed(clean, dropAt = 150_001) }, { Feed(restamped, dropAt = null) })
        assertTrue(rec.completed)
        val segs = rec.segmentList()
        assertEquals(2, segs.size)
        val data = bytesOf(rec)
        assertTrue("written ${data.size} vs ${clean.size}", data.size in (clean.size - 20_000)..(clean.size + 20_000))
        assertTrue("content start ${segs[1].contentStart - T0}", segs[1].contentStart - T0 in 4_000L..6_500L)
        assertTrue(rec.recordedUpTo - T0 >= 9_500)
    }

    /** Two separately recorded files back to back: every stream's counter restarts at 0 at the join. */
    private val stitched = clean.copyOf().also { b ->
        val join = (b.size / Ts.PACKET / 2) * Ts.PACKET
        val firstAfter = HashMap<Int, Int>()
        var p = join
        while (p + Ts.PACKET <= b.size) {
            if (b[p + 3].toInt() and 0x10 != 0) {
                val pid = Ts.pid(b, p)
                val cc = b[p + 3].toInt() and 0x0F
                val base = firstAfter.getOrPut(pid) { cc }
                b[p + 3] = ((b[p + 3].toInt() and 0xF0) or ((cc - base) and 0x0F)).toByte()
            }
            p += Ts.PACKET
        }
    }

    @Test
    fun countersRunOnAcrossJoinsInTheArchive() {
        val rec = record({ Feed(stitched, dropAt = null) })
        val data = bytesOf(rec)
        val last = HashMap<Int, Int>()
        var p = 0
        while (p + Ts.PACKET <= data.size) {
            if (data[p + 3].toInt() and 0x10 != 0) {
                val pid = Ts.pid(data, p)
                val cc = data[p + 3].toInt() and 0x0F
                last[pid]?.let { assertEquals("pid $pid at $p", (it + 1) and 0x0F, cc) }
                last[pid] = cc
            }
            p += Ts.PACKET
        }
        assertTrue(rec.counterFixes > 0)
    }

    @Test
    fun seekPointsAreKeyframesAtOrBeforeTheTarget() {
        val rec = record({ Feed(clean, dropAt = 150_001) }, { Feed(clean, dropAt = null) })
        val data = bytesOf(rec)
        for (target in longArrayOf(T0 + 1_000, T0 + 4_000, T0 + 7_000)) {
            val seg = rec.seekPoint(target)!!
            val at = seg.offset.toInt()
            assertTrue("video starts at $at", Ts.startsVideo(data, at))
            assertTrue("keyframe at $at", Ts.randomAccess(data, at))
            assertTrue("${seg.contentStart - T0} vs ${target - T0}", seg.contentStart <= target && target - seg.contentStart < 4_000)
        }
        assertEquals(null, rec.seekPoint(T0 + 60_000))
    }

    @Test
    fun junkBeforeAndBetweenPacketsIsDropped() {
        val mid = (clean.size / Ts.PACKET / 2) * Ts.PACKET
        val junk = ByteArray(100) { (it * 7 + 3).toByte() }
        val messy = junk + clean.copyOfRange(0, mid) + junk.copyOf(50) + clean.copyOfRange(mid, clean.size)
        val rec = record({ Feed(messy, dropAt = null) })
        assertTrue(rec.completed)
        assertArrayEquals(fromKey, bytesOf(rec))
        assertEquals(1, rec.segmentList().size)
    }

    @Test
    fun playbackFromMidRecordingGetsTheStreamMapFirst() {
        val rec = record({ Feed(clean, dropAt = null) })
        val recorded = bytesOf(rec)
        val from = (recorded.size / Ts.PACKET / 2).toLong() * Ts.PACKET
        val ds = io.github.lucasclgibson.nimbus.player.LiveRecordingDataSource(rec, from)
        ds.openAt(0)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = ds.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        val got = out.toByteArray()
        // PAT then PMT, then the recording from the chosen offset.
        assertEquals(0, Ts.pid(got, 0))
        assertTrue(Ts.pid(got, Ts.PACKET) != 0)
        assertArrayEquals(recorded.copyOfRange(from.toInt(), recorded.size), got.copyOfRange(2 * Ts.PACKET, got.size))
    }

    @Test
    fun recordingStartsOnTheKeyframeBeforeTheStartTime() {
        // Keyframes every second; ask for 4.7 s in: playback should start on the one just before.
        val rec = recordFrom(T0 + 4_700, { Feed(clean, dropAt = null) })
        assertTrue(rec.completed)
        val data = bytesOf(rec)
        val k = TsKeyframes()
        var p = 0
        while (p + Ts.PACKET <= clean.size) {
            k.feed(clean, p, p.toLong()) // learn the stream map
            p += Ts.PACKET
        }
        // The first access unit recorded is judged a start point.
        p = 0
        var verdict = TsKeyframes.NONE
        while (verdict == TsKeyframes.NONE && p + Ts.PACKET <= data.size) {
            verdict = k.feed(data, p, p.toLong())
            p += Ts.PACKET
        }
        assertEquals(TsKeyframes.KEY, verdict)
        assertEquals(0L, k.decidedOffset)
        val start = rec.segmentList()[0].contentStart - T0
        assertTrue("starts at $start ms", start in 3_600L..4_700L)
    }

    @Test
    fun aSwitchToAnotherCodecGetsItsOwnSegmentAndStreamMap() {
        // A provider handing over from an H.264 source to an HEVC one mid-connection.
        val both = clean + ts("hevc_params_every_frame.ts")
        val rec = record({ Feed(both, dropAt = null) })
        val segs = rec.segmentList()
        assertEquals(2, segs.size)
        val type = { m: ByteArray? ->
            val k = TsKeyframes()
            k.feed(m!!, 0, 0)
            k.feed(m, Ts.PACKET, 0)
            k.streamType
        }
        assertEquals(0x1B, type(rec.streamMap(segs[0].offset)))
        assertEquals(0x24, type(rec.streamMap(segs[1].offset)))
        // The second part begins at the new stream map's PMT packet.
        val data = bytesOf(rec)
        val pmtPid = Ts.pid(rec.streamMap(segs[1].offset)!!, Ts.PACKET)
        assertEquals(pmtPid, Ts.pid(data, segs[1].offset.toInt()))
    }

    companion object {
        private const val T0 = 1_700_000_000_000L
    }
}
