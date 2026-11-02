package io.github.lucasclgibson.nimbus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.lucasclgibson.nimbus.player.FrameRateMatcher
import io.github.lucasclgibson.nimbus.ui.AppRoot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import io.github.lucasclgibson.nimbus.ui.theme.NimbusTheme

class MainActivity : ComponentActivity() {
    private val graph by lazy { (application as NimbusApp).graph }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NimbusTheme {
                AppRoot(graph)
            }
        }
        // Drop the splash drawable once Compose is up.
        window.decorView.post { window.setBackgroundDrawable(null) }
        graph.sync.refreshIfStale()

        val matcher = FrameRateMatcher(this, lifecycleScope)
        graph.playback.displaySwitcher = { fps -> matcher.switchFor(fps, graph.prefs.matchFrameRate.value) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(graph.playback.videoFps, graph.playback.session, graph.prefs.matchFrameRate.flow) { fps, s, on ->
                    Triple(fps, s != null, on)
                }.collect { (fps, playing, on) -> matcher.update(fps, playing, on) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.playback.onAppForeground()
        graph.household.syncSoon()
        lifecycleScope.launch { runCatching { graph.updater.check() } }
    }

    override fun onDestroy() {
        super.onDestroy()
        graph.playback.displaySwitcher = null
    }

    override fun onStop() {
        super.onStop()
        graph.playback.onAppBackground()
        graph.parental.relock()
        graph.household.syncSoon()
    }
}
