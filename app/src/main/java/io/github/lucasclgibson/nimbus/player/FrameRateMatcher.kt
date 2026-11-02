package io.github.lucasclgibson.nimbus.player

import android.app.Activity
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Switches the TV's refresh rate to match the video (UK broadcasts are 25/50 fps, films 23.976), like a
 * Sky box outputting 50 Hz. Avoids the judder of showing 50 Hz pictures on a 60 Hz output.
 * Only switches when the target actually differs, so zapping between UK channels never re-syncs HDMI.
 */
class FrameRateMatcher(private val activity: Activity, private val scope: CoroutineScope) {
    private var revertJob: Job? = null

    /** When we last asked for a different mode (the TV shows black for a while after). */
    @Volatile private var requestedAt = 0L

    /**
     * Before a stream starts: switch to the mode for [fps] (if [enabled] and it differs) and return once
     * the TV should be showing a picture again, so playback starts after the switch's black screen
     * instead of a second into it. Returns at once when no switch is needed.
     */
    suspend fun switchFor(fps: Float, enabled: Boolean) {
        if (!enabled) return
        val target = refreshFor(fps) ?: return
        val mode = pickMode(target) ?: return
        revertJob?.cancel()
        val already = display()?.mode?.modeId == mode.modeId
        apply(mode.modeId)
        if (already) return // no re-sync, no black screen: nothing to wait for
        // The output changes within a fraction of a second; the TV then needs a moment to lock on to it.
        withTimeoutOrNull(3_000) { while (display()?.mode?.modeId != mode.modeId) delay(50) }
        val wait = requestedAt + SWITCH_BLANK_MS - SystemClock.elapsedRealtime()
        if (wait > 0) delay(wait)
    }

    fun update(fps: Float, playing: Boolean, enabled: Boolean) {
        if (!enabled) {
            revertJob?.cancel()
            apply(0)
            return
        }
        if (!playing) {
            // Back to the TV's normal mode once nothing has played for a little while.
            // Stay in the video's mode across short gaps (menus, the guide, between films) – every
            // switch makes the TV re-sync for a second or two.
            if (revertJob?.isActive != true) revertJob = scope.launch {
                delay(10 * 60_000L)
                apply(0)
            }
            return
        }
        revertJob?.cancel()
        if (fps <= 0f) return // not known yet: keep whatever mode we're in
        val target = refreshFor(fps) ?: return
        val mode = pickMode(target) ?: return
        apply(mode.modeId)
    }

    private fun refreshFor(fps: Float): Float? = when (fps) {
        in 23.60f..23.99f -> 23.976f
        in 23.99f..24.40f -> 24f
        in 24.60f..25.40f, in 49.00f..51.00f -> 50f
        in 29.50f..29.985f, in 59.00f..59.97f -> 59.94f
        in 29.985f..30.50f, in 59.97f..61.00f -> 60f
        else -> null
    }

    private fun display(): Display? =
        if (Build.VERSION.SDK_INT >= 30) activity.display else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay

    private fun pickMode(target: Float): Display.Mode? {
        val d = display() ?: return null
        val current = d.mode
        val sameSize = d.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        fun near(rate: Float) = sameSize.filter { abs(it.refreshRate - rate) < 0.05f }.minByOrNull { abs(it.refreshRate - rate) }
        return near(target) ?: (if (target == 23.976f) near(24f) else null)
    }

    private fun apply(modeId: Int) {
        val lp = activity.window.attributes
        if (lp.preferredDisplayModeId == modeId) return
        Log.i(TAG, "display mode -> ${if (modeId == 0) "default" else modeId}")
        requestedAt = SystemClock.elapsedRealtime()
        lp.preferredDisplayModeId = modeId
        activity.window.attributes = lp
    }

    companion object {
        private const val TAG = "NimbusAfr"
        /** From asking for a new mode until the TV shows a picture again (HDMI re-sync), roughly. */
        private const val SWITCH_BLANK_MS = 2_500L
    }
}
