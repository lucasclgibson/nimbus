package io.github.lucasclgibson.nimbus.player

import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * ExoPlayer's buffering logic with start/rebuffer thresholds that depend on what's playing:
 * live TV starts fast (IPTV servers usually send a big initial burst), catch-up near the live edge
 * waits for a deep cushion (archives there arrive in bursts), and after any stall we rebuild a bigger
 * cushion before carrying on, so one hiccup doesn't turn into a run of freezes.
 */
class SmoothLoadControl(large: Boolean) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    /* minBufferMs = */ if (large) 45_000 else 30_000,
    /* maxBufferMs = */ if (large) 120_000 else 90_000,
    /* bufferForPlaybackMs = */ 1_500,
    /* bufferForPlaybackAfterRebufferMs = */ 6_000,
    /* targetBufferBytes = */ (if (large) 128 else 96) * 1024 * 1024,
    /* prioritizeTimeOverSizeThresholds = */ false,
    /* backBufferDurationMs = */ 0,
    /* retainBackBufferFromKeyframe = */ false,
) {
    private val scale = if (large) 1.5 else 1.0

    /** Buffer needed before motion starts / restarts, in ms. Set per session by the player. */
    @Volatile var startMs = 1_500L
    @Volatile var rebufferMs = 6_000L

    /** If set, the player's own buffer stops here (ms): catch-up from the recorder is cushioned in the ring. */
    @Volatile var capMs = 0L

    fun forLive() {
        startMs = 1_500
        rebufferMs = 6_000
        capMs = 0
    }

    fun forCatchup(nearLiveEdge: Boolean, recorded: Boolean = false) {
        if (nearLiveEdge) {
            startMs = 12_000
            rebufferMs = 15_000
        } else {
            startMs = 2_500
            rebufferMs = 6_000
        }
        capMs = if (recorded) 30_000 else 0
    }

    fun forVod() {
        startMs = 2_500
        rebufferMs = 6_000
        capMs = 0
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val cap = capMs
        if (cap > 0 && parameters.bufferedDurationUs >= cap * 1000) return false
        return super.shouldContinueLoading(parameters)
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val wantMs = (if (parameters.rebuffering) rebufferMs else startMs) * scale
        val speed = parameters.playbackSpeed.coerceAtLeast(0.1f)
        return parameters.bufferedDurationUs / 1000.0 / speed >= wantMs
    }
}
