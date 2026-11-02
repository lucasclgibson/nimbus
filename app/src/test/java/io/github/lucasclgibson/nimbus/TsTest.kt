package io.github.lucasclgibson.nimbus

import io.github.lucasclgibson.nimbus.player.Ts
import io.github.lucasclgibson.nimbus.player.TsTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TsTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/ts/$name")!!.readBytes()

    /** Returns (byte offsets where a new timeline starts, elapsed ms of each stretch). */
    private fun walk(b: ByteArray): Pair<List<Int>, List<Long>> {
        assertEquals(0, Ts.findSync(b, 0, b.size))
        val tl = TsTimeline()
        val splits = ArrayList<Int>()
        val stretches = ArrayList<Long>()
        var p = 0
        while (p + Ts.PACKET <= b.size) {
            val ts = Ts.pesTimestamp(b, p)
            if (ts >= 0 && !tl.check(Ts.pid(b, p), ts)) {
                splits += p
                stretches += tl.elapsedMs
                tl.reset()
                tl.check(Ts.pid(b, p), ts)
            }
            p += Ts.PACKET
        }
        stretches += tl.elapsedMs
        return splits to stretches
    }

    @Test fun cleanStreamHasNoJumps() {
        val (splits, stretches) = walk(bytes("clean.ts"))
        assertEquals(emptyList<Int>(), splits)
        assertTrue("about 10 s of programme, got ${stretches[0]}", stretches[0] in 9_000..10_500)
    }

    @Test fun splicedStreamSplitsOnceAtTheJoin() {
        val clean = bytes("clean.ts").size
        val (splits, stretches) = walk(bytes("spliced.ts"))
        assertEquals(1, splits.size)
        // The new timeline starts within the first packets of the second file.
        assertTrue("split at ${splits[0]}, join at $clean", splits[0] in clean..(clean + 20 * Ts.PACKET))
        assertTrue(stretches[0] in 9_000..10_500)
        assertTrue(stretches[1] in 9_000..10_500)
    }

    @Test fun clockWrapIsNotAJump() {
        val (splits, stretches) = walk(bytes("wrap.ts"))
        assertEquals(emptyList<Int>(), splits)
        assertTrue(stretches[0] in 9_000..10_500)
    }

    /** Timestamps of the start points the finder confirms in a file. */
    private fun keyframes(name: String): Pair<io.github.lucasclgibson.nimbus.player.TsKeyframes, List<Long>> {
        val b = javaClass.getResourceAsStream("/ts/$name")!!.readBytes()
        val keys = io.github.lucasclgibson.nimbus.player.TsKeyframes()
        val found = ArrayList<Long>()
        var p = 0
        while (p + Ts.PACKET <= b.size) {
            if (keys.feed(b, p, p.toLong()) == io.github.lucasclgibson.nimbus.player.TsKeyframes.KEY) found += keys.decidedTs
            p += Ts.PACKET
        }
        return keys to found
    }

    private fun assertEverySecond(found: List<Long>, count: Int) {
        assertEquals(count, found.size)
        // Decode timestamps: the reordering delay can change after the first group of pictures.
        for (i in 1 until found.size) assertEquals(90_000.0, (found[i] - found[i - 1]).toDouble(), 18_000.0)
    }

    @Test
    fun keyframeFinderSpotsEveryKeyframeAndNothingElse() {
        // ffprobe: H.264 keyframes every second, 10 in the file.
        val (keys, found) = keyframes("clean.ts")
        assertEquals(0x1B, keys.streamType)
        assertEverySecond(found, 10)
    }

    @Test
    fun parameterSetsOnEveryFrameDontMakeEveryFrameAKeyframe() {
        // Encoded with SPS/PPS (H.264) or VPS/SPS/PPS (HEVC) in front of every frame; ffprobe: 4 keyframes each.
        assertEverySecond(keyframes("avc_params_every_frame.ts").second, 4)
        val (hevc, found) = keyframes("hevc_params_every_frame.ts")
        assertEquals(0x24, hevc.streamType)
        assertEverySecond(found, 4)
        assertEverySecond(keyframes("avc_open_gop.ts").second, 4)
    }
}
