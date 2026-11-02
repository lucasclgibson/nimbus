package io.github.lucasclgibson.nimbus.core

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Low-priority threads for heavy background work (catalogue refreshes, guide imports, search indexing),
 * so it can never take CPU time from playback or the UI.
 */
object Background {
    val dispatcher: CoroutineDispatcher = Executors.newFixedThreadPool(2) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "nimbus-background").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}
