package io.github.lucasclgibson.nimbus.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import kotlin.math.abs

/** Frame rate from a run of presentation timestamps. */
object FrameRates {
    /**
     * Average frame rate of the first [count] of [timesUs] (any order: decode order has B-frames out of
     * sequence), or null if they don't look like steady video. Uses the mean step, not the median or a
     * trimmed mean: Matroska stores whole milliseconds, so 29.97 fps steps go 33, 34, 33… and only the
     * mean comes out at 29.97 rather than "30.2" (which picks 60 Hz instead of 59.94).
     */
    fun estimate(timesUs: LongArray, count: Int = timesUs.size): Float? {
        if (count < 9) return null
        val t = timesUs.copyOf(count).also { it.sort() }
        val steps = LongArray(count - 1)
        var n = 0
        for (i in 1 until count) {
            val d = t[i] - t[i - 1]
            if (d > 0) steps[n++] = d
        }
        if (n < 8) return null
        val median = steps.copyOf(n).also { it.sort() }[n / 2]
        // Gaps (frames still to come in decode order, dropped frames) are left out.
        var sum = 0L
        var used = 0
        for (i in 0 until n) {
            if (abs(steps[i] - median) * 5 <= median) {
                sum += steps[i]
                used++
            }
        }
        if (used < n * 2 / 3 || sum <= 0) return null
        val fps = 1_000_000.0 * used / sum
        return if (fps in 10.0..121.0) fps.toFloat() else null
    }

    /**
     * Rates that a short sample can't confuse with a neighbour: 25 and 50 fps (UK TV). Telling 23.976
     * from 24, or 29.97 from 30, needs a few seconds of timestamps.
     */
    fun unambiguous(fps: Float): Boolean = abs(fps - 25f) < 0.15f || abs(fps - 50f) < 0.3f

    /**
     * Good enough after a short run: an unmistakable rate, or timestamps finer than whole milliseconds
     * (MPEG-TS, MP4), which pin down even 23.976 vs 24 within about a second.
     */
    fun settledEarly(timesUs: LongArray, count: Int, fps: Float): Boolean =
        unambiguous(fps) || (0 until count).any { timesUs[it] % 1000L != 0L }
}

/**
 * Works out the video's frame rate from the first frames as they're downloaded – before anything is
 * shown – so the TV can switch refresh rate before playback starts rather than blanking the picture a
 * second in. MP4 declares its frame rate; Matroska (most films and episodes) and MPEG-TS don't, as far as
 * the player is concerned. Reports at most once per extractor, on the loading thread.
 */
class FrameRateSniffer(private val delegate: ExtractorsFactory, private val onFrameRate: (Float) -> Unit) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map { wrap(it) }.toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map { wrap(it) }.toTypedArray()

    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    private fun wrap(inner: Extractor): Extractor = object : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
        override fun getSniffFailureDetails(): List<SniffFailure> = inner.sniffFailureDetails
        override fun init(output: ExtractorOutput) = inner.init(Output(output))
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
        override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
        override fun release() = inner.release()
        override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
    }

    private inner class Output(private val inner: ExtractorOutput) : ExtractorOutput {
        private var reported = false

        override fun track(id: Int, type: Int): TrackOutput {
            val track = inner.track(id, type)
            return if (type == C.TRACK_TYPE_VIDEO) Track(track) else track
        }

        override fun endTracks() = inner.endTracks()

        override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)

        fun report(fps: Float) {
            if (reported) return
            reported = true
            onFrameRate(fps)
        }

        private inner class Track(private val inner: TrackOutput) : TrackOutput {
            private val times = LongArray(MAX_SAMPLES)
            private var count = 0
            private var done = false

            override fun format(format: Format) {
                inner.format(format)
                if (!done && format.frameRate > 0f) {
                    done = true
                    report(format.frameRate)
                }
            }

            override fun durationUs(durationUs: Long) = inner.durationUs(durationUs)

            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                inner.sampleData(input, length, allowEndOfInput, sampleDataPart)

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
                inner.sampleData(data, length, sampleDataPart)

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                if (done) return
                times[count++] = timeUs
                if (count == EARLY_SAMPLES || count == MAX_SAMPLES) {
                    val fps = FrameRates.estimate(times, count)
                    if (fps != null && (count == MAX_SAMPLES || FrameRates.settledEarly(times, count, fps))) {
                        done = true
                        report(fps)
                    } else if (count == MAX_SAMPLES) {
                        done = true
                    }
                }
            }
        }
    }

    companion object {
        /** Enough for 25/50 fps (about a second of TV). */
        private const val EARLY_SAMPLES = 32
        /** Enough to tell 23.976 from 24 and 29.97 from 30 with millisecond timestamps (about 5 s). */
        private const val MAX_SAMPLES = 128
    }
}
