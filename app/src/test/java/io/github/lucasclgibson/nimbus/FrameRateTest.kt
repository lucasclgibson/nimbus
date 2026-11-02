package io.github.lucasclgibson.nimbus

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import io.github.lucasclgibson.nimbus.player.FrameRateSniffer
import io.github.lucasclgibson.nimbus.player.FrameRates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * The frame rate decides the TV's refresh rate: 23.976 → 23.976 Hz, 25 → 50 Hz, 29.97 → 59.94 Hz,
 * 30 → 60 Hz. Neighbours (23.976/24, 29.97/30) must not be confused, even with millisecond timestamps.
 */
class FrameRateTest {
    /** Timestamps of [n] frames at [fps], rounded to whole milliseconds like Matroska stores them. */
    private fun msRounded(fps: Double, n: Int) = LongArray(n) { i -> (i * 1000.0 / fps).roundToLong() * 1000 }

    private fun exact(fps: Double, n: Int) = LongArray(n) { i -> (i * 1_000_000.0 / fps).roundToLong() }

    /** Decode order with two B-frames per P-frame: I0 P3 B1 B2 P6 B4 B5 … */
    private fun decodeOrder(pts: LongArray): LongArray {
        val out = ArrayList<Long>()
        out += pts[0]
        var i = 1
        while (i + 2 < pts.size) {
            out += pts[i + 2]
            out += pts[i]
            out += pts[i + 1]
            i += 3
        }
        return out.toLongArray()
    }

    @Test
    fun ukTelevision() {
        assertEquals(25f, FrameRates.estimate(exact(25.0, 32))!!, 0.01f)
        assertEquals(50f, FrameRates.estimate(exact(50.0, 32))!!, 0.02f)
        assertTrue(FrameRates.unambiguous(25f) && FrameRates.unambiguous(50f))
        assertTrue(!FrameRates.unambiguous(23.976f) && !FrameRates.unambiguous(29.97f))
    }

    @Test
    fun filmAndUsRatesSurviveMillisecondRounding() {
        val film = FrameRates.estimate(msRounded(24000.0 / 1001, 128))!!
        assertTrue("23.976 read as $film", film in 23.95f..23.99f)
        val twentyFour = FrameRates.estimate(msRounded(24.0, 128))!!
        assertTrue("24 read as $twentyFour", twentyFour in 23.99f..24.01f)
        val ntsc = FrameRates.estimate(msRounded(30000.0 / 1001, 128))!!
        assertTrue("29.97 read as $ntsc", ntsc in 29.95f..29.985f)
        val thirty = FrameRates.estimate(msRounded(30.0, 128))!!
        assertTrue("30 read as $thirty", thirty in 29.985f..30.02f)
    }

    @Test
    fun bFramesAndDroppedFramesDontMatter() {
        val film = FrameRates.estimate(decodeOrder(msRounded(24000.0 / 1001, 130)))!!
        assertTrue("23.976 in decode order read as $film", film in 23.95f..23.99f)
        val gaps = exact(25.0, 60).filterIndexed { i, _ -> i % 10 != 7 }.toLongArray()
        assertEquals(25f, FrameRates.estimate(gaps)!!, 0.01f)
    }

    // ------------------------------------------------------------------ the sniffer around a file reader

    /** Stands in for a file reader: one video track, a declared rate (or not), then frames at [times]. */
    private class FakeReader(private val times: LongArray, private val declared: Float = Format.NO_VALUE.toFloat()) : Extractor {
        private lateinit var out: ExtractorOutput
        private var track: TrackOutput? = null
        private var next = 0
        val forwarded = ArrayList<Long>()

        override fun sniff(input: ExtractorInput) = true
        override fun init(output: ExtractorOutput) { out = output }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            val t = track ?: out.track(1, C.TRACK_TYPE_VIDEO).also {
                track = it
                it.format(Format.Builder().setSampleMimeType("video/avc").setFrameRate(declared).build())
                out.endTracks()
            }
            if (next >= times.size) return Extractor.RESULT_END_OF_INPUT
            t.sampleData(ParsableByteArray(ByteArray(4)), 4, TrackOutput.SAMPLE_DATA_PART_MAIN)
            t.sampleMetadata(times[next++], C.BUFFER_FLAG_KEY_FRAME, 4, 0, null)
            return Extractor.RESULT_CONTINUE
        }
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }

    /** Runs [reader] through the sniffer; returns what it reported and what reached the player's own track. */
    private fun run(reader: FakeReader): Pair<List<Float>, List<Long>> {
        val reports = ArrayList<Float>()
        val reached = ArrayList<Long>()
        val extractor = FrameRateSniffer(ExtractorsFactory { arrayOf(reader) }) { reports += it }.createExtractors().single()
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = object : TrackOutput {
                override fun format(format: Format) = Unit
                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int) = length
                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)
                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                    reached += timeUs
                }
            }
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        })
        val holder = PositionHolder()
        @Suppress("ControlFlowWithEmptyBody")
        while (extractor.read(DefaultExtractorInput({ _, _, _ -> C.RESULT_END_OF_INPUT }, 0, 0), holder) != Extractor.RESULT_END_OF_INPUT) {
        }
        return reports to reached
    }

    @Test
    fun broadcastIsReportedEarlyAndOnce() {
        val frames = exact(25.0, 300)
        val (reports, reached) = run(FakeReader(frames))
        assertEquals(1, reports.size)
        assertEquals(25f, reports[0], 0.01f)
        assertEquals(frames.toList(), reached) // everything still reaches the player, untouched
    }

    @Test
    fun filmWaitsForEnoughFramesToTellItFrom24() {
        val (reports, _) = run(FakeReader(decodeOrder(msRounded(24000.0 / 1001, 200))))
        assertEquals(1, reports.size)
        assertTrue("film read as ${reports[0]}", reports[0] in 23.95f..23.99f)
    }

    @Test
    fun preciseTimestampsSettleFilmEarly() {
        // MPEG-TS / MP4 timestamps aren't rounded: 32 frames are enough even for 23.976.
        val (reports, _) = run(FakeReader(exact(24000.0 / 1001, 40)))
        assertEquals(1, reports.size)
        assertTrue("read as ${reports[0]}", reports[0] in 23.97f..23.98f)
    }

    @Test
    fun declaredRateWins() {
        val (reports, _) = run(FakeReader(exact(25.0, 50), declared = 23.976f))
        assertEquals(listOf(23.976f), reports)
    }

    @Test
    fun tooFewFramesReportsNothing() {
        val (reports, _) = run(FakeReader(exact(24000.0 / 1001, 20)))
        assertTrue(reports.isEmpty())
    }
}
