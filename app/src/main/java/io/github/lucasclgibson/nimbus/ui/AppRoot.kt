package io.github.lucasclgibson.nimbus.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import io.github.lucasclgibson.nimbus.AppGraph
import io.github.lucasclgibson.nimbus.ui.components.BackdropLayer
import io.github.lucasclgibson.nimbus.ui.components.BackdropState
import io.github.lucasclgibson.nimbus.ui.components.LocalBackdrop
import io.github.lucasclgibson.nimbus.ui.components.VideoLayer
import io.github.lucasclgibson.nimbus.ui.components.MenuItem
import io.github.lucasclgibson.nimbus.ui.components.OptionsMenu
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.core.timeOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import io.github.lucasclgibson.nimbus.ui.detail.MovieDetailScreen
import io.github.lucasclgibson.nimbus.ui.detail.SeriesDetailScreen
import io.github.lucasclgibson.nimbus.ui.guide.GuideScreen
import io.github.lucasclgibson.nimbus.ui.home.HomeScreen
import io.github.lucasclgibson.nimbus.ui.nav.Dest
import io.github.lucasclgibson.nimbus.ui.nav.LocalApp
import io.github.lucasclgibson.nimbus.ui.nav.LocalEntry
import io.github.lucasclgibson.nimbus.ui.nav.LocalNav
import io.github.lucasclgibson.nimbus.ui.nav.Navigator
import io.github.lucasclgibson.nimbus.ui.player.PlayerScreen
import io.github.lucasclgibson.nimbus.ui.search.SearchScreen
import io.github.lucasclgibson.nimbus.ui.settings.ManageCategoriesScreen
import io.github.lucasclgibson.nimbus.ui.settings.PhoneSetupScreen
import io.github.lucasclgibson.nimbus.ui.settings.PlaylistFormScreen
import io.github.lucasclgibson.nimbus.ui.settings.SettingsScreen
import io.github.lucasclgibson.nimbus.ui.vod.BrowseScreen

@Composable
fun AppRoot(app: AppGraph) {
    val nav = remember {
        Navigator(if (app.prefs.startup.value == "guide") Dest.Guide else Dest.Home)
    }
    val backdrop = remember { BackdropState() }
    val activity = LocalContext.current as? Activity

    // Keep the screen awake while something is playing, and while live TV is paused (for up to
    // 30 minutes – about what the live buffer holds) so the screensaver doesn't throw the pause away.
    val playing by app.playback.state.collectAsState()
    val pausedAt by app.playback.livePausedAt.collectAsState()
    val view = LocalView.current
    LaunchedEffect(playing.playing, pausedAt) {
        view.keepScreenOn = playing.playing || pausedAt != null
        if (!playing.playing && pausedAt != null) {
            kotlinx.coroutines.delay(30 * 60_000L)
            view.keepScreenOn = false
        }
    }

    // Optional: jump straight into the last channel, like a set-top box.
    LaunchedEffect(Unit) {
        if (app.prefs.startup.value == "last_channel") {
            app.store.ready()
            val key = app.prefs.lastChannelKey.value
            val ch = if (key.isNotEmpty()) app.live.channel(key) else null
            if (ch != null) {
                val list = app.live.channels(app.prefs.guideGroup.value).ifEmpty { listOf(ch) }
                app.playback.playLive(ch, list)
                nav.push(Dest.Player)
            }
        }
    }

    CompositionLocalProvider(LocalApp provides app, LocalNav provides nav, LocalBackdrop provides backdrop) {
        BackHandler {
            if (!nav.pop()) {
                if (nav.root.dest != Dest.Home) nav.go(Dest.Home) else activity?.finish()
            }
        }
        Box(Modifier.fillMaxSize()) {
            BackdropLayer(backdrop)
            VideoLayer(app.playback)
            val entry = nav.current
            key(entry) {
                CompositionLocalProvider(LocalEntry provides entry) {
                    Screen(entry.dest)
                }
            }
        }
        ReminderPrompt(app, nav)
    }
}

/** Sky-style reminder: the programme is starting – watch it or dismiss. */
@Composable
private fun ReminderPrompt(app: AppGraph, nav: Navigator) {
    val due by app.reminders.due.collectAsState()
    val r = due ?: return
    val scope = rememberCoroutineScope()
    OptionsMenu(
        title = r.title,
        subtitle = "${if (r.startMs <= now()) "On now" else "Starting at ${timeOf(r.startMs)}"} on ${r.channelName}",
        items = listOf(
            MenuItem("Watch now", r.channelName, Icons.Rounded.LiveTv) {
                scope.launch {
                    val ch = app.live.channel(r.channelKey) ?: return@launch
                    val list = app.live.channels(app.prefs.guideGroup.value).ifEmpty { listOf(ch) }
                    app.playback.playLive(ch, if (list.any { it.key == ch.key }) list else listOf(ch))
                    if (nav.current.dest != Dest.Player) nav.push(Dest.Player)
                }
            },
            MenuItem("Dismiss", "", Icons.Rounded.Close) {},
        ),
        onDismiss = { app.reminders.dismiss() },
    )
}

@Composable
private fun Screen(dest: Dest) {
    when (dest) {
        Dest.Home -> HomeScreen()
        Dest.Guide -> GuideScreen()
        Dest.Movies -> BrowseScreen(series = false)
        Dest.Shows -> BrowseScreen(series = true)
        Dest.Search -> SearchScreen()
        Dest.Settings -> SettingsScreen()
        Dest.Player -> PlayerScreen()
        is Dest.MovieDetail -> MovieDetailScreen(dest.key)
        is Dest.SeriesDetail -> SeriesDetailScreen(dest.key)
        is Dest.PlaylistForm -> PlaylistFormScreen(dest.type, dest.editId)
        Dest.PhoneSetup -> PhoneSetupScreen()
        is Dest.ManageCategories -> ManageCategoriesScreen(dest.kind)
    }
}
