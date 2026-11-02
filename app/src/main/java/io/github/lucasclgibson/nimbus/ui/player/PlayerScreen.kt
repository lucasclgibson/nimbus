package io.github.lucasclgibson.nimbus.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C as MC
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.tv.material3.Text
import io.github.lucasclgibson.nimbus.core.MINUTE
import io.github.lucasclgibson.nimbus.core.clockLabel
import io.github.lucasclgibson.nimbus.core.displayChannelName
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.core.timeOf
import io.github.lucasclgibson.nimbus.data.db.ChannelEntity
import io.github.lucasclgibson.nimbus.data.db.Kind
import io.github.lucasclgibson.nimbus.data.repo.NowNext
import io.github.lucasclgibson.nimbus.player.PlayerState
import io.github.lucasclgibson.nimbus.player.Session
import io.github.lucasclgibson.nimbus.ui.components.Badge
import io.github.lucasclgibson.nimbus.ui.components.ChannelLogo
import io.github.lucasclgibson.nimbus.ui.components.Clock
import io.github.lucasclgibson.nimbus.ui.components.FocusSurface
import io.github.lucasclgibson.nimbus.ui.components.MenuItem
import io.github.lucasclgibson.nimbus.ui.components.OptionsMenu
import io.github.lucasclgibson.nimbus.ui.components.PillButton
import io.github.lucasclgibson.nimbus.ui.components.ProgressLine
import io.github.lucasclgibson.nimbus.ui.components.RequestOnce
import io.github.lucasclgibson.nimbus.ui.components.SetBackdrop
import io.github.lucasclgibson.nimbus.ui.components.VideoSlot
import io.github.lucasclgibson.nimbus.ui.components.tryFocus
import io.github.lucasclgibson.nimbus.ui.components.FocusRoom
import io.github.lucasclgibson.nimbus.ui.nav.Dest
import io.github.lucasclgibson.nimbus.ui.nav.LocalApp
import io.github.lucasclgibson.nimbus.ui.nav.LocalNav
import io.github.lucasclgibson.nimbus.ui.theme.C
import io.github.lucasclgibson.nimbus.ui.theme.T
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import io.github.lucasclgibson.nimbus.core.durationLabel
import io.github.lucasclgibson.nimbus.core.dayLabel
import io.github.lucasclgibson.nimbus.data.db.ProgrammeLite
import io.github.lucasclgibson.nimbus.player.programmeAt

@Composable
fun PlayerScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val session by app.playback.session.collectAsState()
    val st by app.playback.state.collectAsState()
    val showInfo by showStreamInfo.collectAsState()
    SetBackdrop(null)
    Box(Modifier.fillMaxSize()) {
        VideoSlot(Modifier.fillMaxSize())
        // Start on black until the new picture arrives – except when zapping, where the last channel's
        // frame bridges the gap like a set-top box.
        if (!st.firstFrame && session != null && !st.keepFrame) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
        when (val s = session) {
            is Session.Live -> LiveOverlay(s, st)
            is Session.Catchup -> TimelineOverlay(st, catchup = s)
            is Session.Vod -> TimelineOverlay(st, catchup = null)
            null -> LaunchedEffect(Unit) { nav.pop() }
        }
        StatusLayer(st)
        if (showInfo) StreamInfoPanel(Modifier.align(Alignment.TopStart).padding(top = 28.dp, start = 48.dp))
    }
}

/** Toggled from the player's options menu. */
private val showStreamInfo = kotlinx.coroutines.flow.MutableStateFlow(false)

private fun streamInfoItem(): MenuItem = MenuItem(
    if (showStreamInfo.value) "Hide stream info" else "Stream info",
    "Resolution, bitrate, buffer and smoothness",
    Icons.Rounded.Info,
) { showStreamInfo.value = !showStreamInfo.value }

@Composable
private fun StreamInfoPanel(modifier: Modifier) {
    val app = LocalApp.current
    val s by app.playback.stats.collectAsState()
    Column(modifier.background(Color(0xCC02040A), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Stream info", style = T.Label, color = C.Accent)
        Spacer(Modifier.height(4.dp))
        @Composable fun line(k: String, v: String) {
            Row {
                Text(k, style = T.Small, color = C.TextMute, modifier = Modifier.width(96.dp))
                Text(v, style = T.Small, color = C.Text)
            }
        }
        line("Picture", listOf(s.resolution, if (s.fps > 0) "%.2f fps".format(s.fps) else "", s.videoCodec).filter { it.isNotEmpty() }.joinToString(" · "))
        line("Audio", s.audioCodec.ifEmpty { "–" })
        line("Bitrate", if (s.bitrateKbps > 0) "%.1f Mbps".format(s.bitrateKbps / 1000f) else "–")
        line("Buffer", "%.1f s ahead".format(s.cushionMs / 1000f) + if (s.bufferSpanMs > 0) " · ${clockLabel(s.bufferSpanMs)} recorded" else "")
        line("Smoothness", "${s.stalls} stalls · ${s.droppedFrames} dropped frames · ${s.reconnects} reconnects")
        line("Watching", clockLabel(s.watchingMs))
    }
}

private fun KeyEvent.isDown() = type == KeyEventType.KeyDown
private fun KeyEvent.isSelect() = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

// ============================================================================================ live

@Composable
private fun LiveOverlay(s: Session.Live, st: PlayerState) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val pm = app.playback
    val scope = rememberCoroutineScope()
    val root = remember { FocusRequester() }
    var banner by remember { mutableStateOf(true) }
    var bannerToken by remember { mutableIntStateOf(0) }
    var showList by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    val favourites by app.library.favourites.collectAsState()

    // Zapping: first press tunes instantly; held/rapid presses preview and tune when you stop.
    var target by remember { mutableStateOf<ChannelEntity?>(null) }
    var zapJob by remember { mutableStateOf<Job?>(null) }
    var lastZap by remember { mutableLongStateOf(0L) }
    val shown = target ?: s.channel

    fun showBanner() {
        banner = true
        bannerToken++
    }

    fun zap(delta: Int) {
        val list = s.zapList
        if (list.size < 2) return
        val base = target ?: s.channel
        val idx = list.indexOfFirst { it.key == base.key }.coerceAtLeast(0)
        val next = list[(idx + delta).mod(list.size)]
        val t = System.currentTimeMillis()
        zapJob?.cancel()
        if (t - lastZap > 700 && target == null) {
            pm.playLive(next, list)
        } else {
            target = next
            zapJob = scope.launch {
                delay(450)
                pm.playLive(next, list)
                target = null
            }
        }
        lastZap = t
        showBanner()
    }

    val nowNext by produceState<NowNext?>(null, shown.key) {
        while (true) {
            value = app.live.nowNext(listOf(shown))[shown.key]
            delay(30_000)
        }
    }

    val pausedAt by pm.livePausedAt.collectAsState()
    val buffered = pm.hasLiveBuffer
    val canRewind = buffered || app.live.hasCatchup(s.channel)
    // How far behind live we're watching (local buffer), refreshed every second.
    val behind by produceState(0L, s.channel.key) {
        while (true) {
            value = pm.liveDelayMs()
            delay(1_000)
        }
    }
    // ⏪ on live: each press steps further back; playback jumps there once you stop pressing.
    var rewindTo by remember { mutableStateOf<Long?>(null) }
    var rewindJob by remember { mutableStateOf<Job?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun rewind(repeat: Int) {
        if (!canRewind) {
            notice = "This channel can't be rewound"
            return
        }
        val step = when {
            repeat > 20 -> 5 * 60_000L
            repeat > 6 -> 2 * 60_000L
            else -> 30_000L
        }
        val oldest = if (app.live.hasCatchup(s.channel)) now() - s.channel.catchupDays * 24 * 60 * 60_000L else pm.liveBufferStart()
        val base = rewindTo ?: (pausedAt ?: pm.livePosition())
        rewindTo = (base - step).coerceAtLeast(oldest)
        showBanner()
        rewindJob?.cancel()
        rewindJob = scope.launch {
            delay(900)
            val t = rewindTo ?: return@launch
            rewindTo = null
            pm.seekLive(t)
        }
    }

    fun forward(repeat: Int) {
        if (rewindTo == null && behind < 5_000) {
            notice = if (pausedAt != null) "Press ⏯ to carry on" else "You're watching live"
            return
        }
        val step = when {
            repeat > 20 -> 5 * 60_000L
            repeat > 6 -> 2 * 60_000L
            else -> 30_000L
        }
        val base = rewindTo ?: pm.livePosition()
        rewindTo = (base + step).coerceAtMost(now())
        showBanner()
        rewindJob?.cancel()
        rewindJob = scope.launch {
            delay(900)
            val t = rewindTo ?: return@launch
            rewindTo = null
            if (t >= now() - 4_000) pm.jumpLive() else pm.seekLive(t)
        }
    }

    fun togglePause() {
        if (pausedAt != null) pm.resumeLive() else pm.pauseLive()
        showBanner()
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_500)
            notice = null
        }
    }

    LaunchedEffect(bannerToken, banner, pausedAt) {
        if (banner && pausedAt == null) {
            delay(5_000)
            banner = false
        }
    }

    BackHandler(enabled = banner && !showList && pausedAt == null) { banner = false }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(root)
            .onPreviewKeyEvent { e ->
                if (showList) return@onPreviewKeyEvent false
                when {
                    e.key == Key.DirectionUp || e.key == Key.ChannelUp -> { if (e.isDown()) zap(1); true }
                    e.key == Key.DirectionDown || e.key == Key.ChannelDown -> { if (e.isDown()) zap(-1); true }
                    e.key == Key.DirectionLeft -> { if (e.isDown()) showList = true; true }
                    e.key == Key.DirectionRight -> { if (e.isDown()) showBanner(); true }
                    e.isSelect() -> {
                        if (e.isDown() && e.nativeKeyEvent.repeatCount == 0) {
                            when {
                                pausedAt != null -> togglePause()
                                banner -> menu = "actions"
                                else -> showBanner()
                            }
                        }
                        true
                    }
                    e.key == Key.Menu -> { if (e.isDown()) menu = "actions"; true }
                    e.key == Key.Guide -> { if (e.isDown()) { nav.pop(); nav.go(Dest.Guide) }; true }
                    e.key == Key.MediaRewind -> { if (e.isDown()) rewind(e.nativeKeyEvent.repeatCount); true }
                    e.key == Key.MediaFastForward -> { if (e.isDown()) forward(e.nativeKeyEvent.repeatCount); true }
                    e.key == Key.MediaPlayPause -> { if (e.isDown() && e.nativeKeyEvent.repeatCount == 0) togglePause(); true }
                    e.key == Key.MediaPause -> { if (e.isDown()) { pm.pauseLive(); showBanner() }; true }
                    e.key == Key.MediaPlay -> { if (e.isDown()) { pm.resumeLive(); showBanner() }; true }
                    e.key == Key.LastChannel -> {
                        if (e.isDown()) {
                            val prevKey = app.prefs.previousChannelKey.value
                            s.zapList.firstOrNull { it.key == prevKey }?.let { pm.playLive(it, s.zapList) }
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AnimatedVisibility(banner && !showList, enter = fadeIn(tween(160)), exit = fadeOut(tween(260)), modifier = Modifier.align(Alignment.BottomCenter)) {
            LiveBanner(shown, nowNext, preview = target != null, favourite = "${Kind.LIVE}:${shown.key}" in favourites, canRewind = canRewind)
        }
        pausedAt?.let { at -> if (!showList) PausedPanel(at, buffered || app.live.hasCatchup(s.channel), Modifier.align(Alignment.Center)) }
        val status = rewindTo?.let { if (it >= now() - 4_000) "⏩  Live" else "⏪  ${clockLabel(now() - it)} behind live" }
            ?: notice
            ?: if (pausedAt == null && behind >= 5_000) "${clockLabel(behind)} behind live  ·  ⏩ catch up" else null
        if (status != null && !showList) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 48.dp)
                    .background(Color(0xCC02040A), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(status, style = T.H3, color = C.Text) }
        }
        AnimatedVisibility(
            showList,
            enter = slideInHorizontally(tween(180)) { -it / 3 } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(160)) { -it / 3 } + fadeOut(tween(160)),
        ) {
            ChannelListPanel(
                channels = s.zapList,
                current = s.channel,
                onPick = { ch ->
                    showList = false
                    if (ch.key != s.channel.key) pm.playLive(ch, s.zapList)
                    showBanner()
                    root.tryFocus()
                },
                onClose = {
                    showList = false
                    root.tryFocus()
                },
            )
        }
    }
    root.RequestOnce(showList, enabled = !showList && menu == null)

    if (menu != null) {
        val ch = s.channel
        val p = nowNext?.now
        val isFav = "${Kind.LIVE}:${ch.key}" in favourites
        val items = buildList {
            if (p != null && app.live.canCatchup(ch, p.startMs)) {
                add(MenuItem("Watch from start", "${timeOf(p.startMs)} · ${p.title}", Icons.Rounded.Replay) {
                    pm.playCatchup(ch, s.zapList, p.title, p.startMs, p.endMs)
                })
            }
            if (behind >= 5_000) add(MenuItem("Back to live", "You're ${clockLabel(behind)} behind", Icons.Rounded.LiveTv) { pm.jumpLive() })
            add(MenuItem("TV guide", "", Icons.Rounded.GridView) { nav.pop(); nav.go(Dest.Guide) })
            add(streamInfoItem())
            add(MenuItem("Channel list", "", Icons.Rounded.LiveTv) { showList = true })
            addAll(trackItems(st, pm::selectTrack))
            add(aspectItem(pm.resizeMode.value) { pm.resizeMode.value = it })
            add(MenuItem(if (isFav) "Remove from favourites" else "Add to favourites", ch.name,
                if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) {
                scope.launch { app.library.toggleFavourite(Kind.LIVE, ch.key) }
            })
        }
        OptionsMenu(title = ch.name, subtitle = p?.title.orEmpty(), items = items, onDismiss = {
            menu = null
            root.tryFocus()
        })
    }
}

/** Live TV paused: when, and how far behind live you'll be. */
@Composable
private fun PausedPanel(pausedAt: Long, canRewind: Boolean, modifier: Modifier) {
    val behind by produceState(0L, pausedAt) {
        while (true) {
            value = now() - pausedAt
            delay(1_000)
        }
    }
    Column(
        modifier.background(Color(0xCC02040A), RoundedCornerShape(18.dp)).padding(horizontal = 34.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("❚❚", style = T.Display, color = C.Text)
        Spacer(Modifier.height(6.dp))
        Text("Paused at ${timeOfSeconds(pausedAt)}", style = T.H3, color = C.Text)
        Text("${clockLabel(behind)} behind live", style = T.Body, color = C.TextDim)
        Spacer(Modifier.height(8.dp))
        Text(
            if (canRewind) "⏯ or OK to carry on" else "⏯ to carry on · long pauses skip ahead on this channel",
            style = T.Small, color = C.TextMute,
        )
    }
}

private fun timeOfSeconds(ms: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()))

@Composable
private fun LiveBanner(ch: ChannelEntity, nn: NowNext?, preview: Boolean, favourite: Boolean, canRewind: Boolean) {
    val t = now()
    Box(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD902040A), Color(0xF202040A))))
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, bottom = 30.dp, top = 54.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                Modifier.size(132.dp, 84.dp).background(Color(0x22FFFFFF), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { ChannelLogo(ch.logo, ch.name, width = 104.dp, height = 62.dp) }
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${ch.number}", style = T.H2, color = C.Accent)
                    Spacer(Modifier.width(10.dp))
                    Text(displayChannelName(ch.name), style = T.H2, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (favourite) Text("  ♥", style = T.H3, color = C.Live)
                    if (preview) {
                        Spacer(Modifier.width(10.dp))
                        Badge("Press OK", color = C.Accent)
                    }
                }
                Spacer(Modifier.height(6.dp))
                val p = nn?.now
                if (p != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge("Now")
                        Spacer(Modifier.width(8.dp))
                        Text(p.title, style = T.H3, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(12.dp))
                        Text("${timeOf(p.startMs)} – ${timeOf(p.endMs)}", style = T.Label, color = C.TextDim)
                    }
                    Spacer(Modifier.height(6.dp))
                    ProgressLine((t - p.startMs).toFloat() / (p.endMs - p.startMs), Modifier.width(420.dp), color = C.Live)
                } else {
                    Text("No programme information", style = T.Body, color = C.TextDim)
                }
                nn?.next?.let { nx ->
                    Spacer(Modifier.height(6.dp))
                    Text("Next  ${timeOf(nx.startMs)}  ${nx.title}", style = T.Label, color = C.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Clock(style = T.H2)
                Spacer(Modifier.height(4.dp))
                Text(if (ch.catchupDays > 0) "↺ Catch up ${ch.catchupDays}d" else "", style = T.Small, color = C.Catchup)
                Text(
                    if (canRewind) "◀ Channels  ▲▼ Zap  ⏯ Pause  ⏪ Rewind  OK Options" else "◀ Channels  ▲▼ Zap  ⏯ Pause  OK Options",
                    style = T.Small, color = C.TextMute,
                )
            }
        }
    }
}

@Composable
private fun ChannelListPanel(channels: List<ChannelEntity>, current: ChannelEntity, onPick: (ChannelEntity) -> Unit, onClose: () -> Unit) {
    val app = LocalApp.current
    val list = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val start = channels.indexOfFirst { it.key == current.key }.coerceAtLeast(0)
    val nowNext by produceState(emptyMap<String, NowNext>(), channels) {
        value = app.live.nowNext(channels.take(1500))
    }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { list.scrollToItem((start - 3).coerceAtLeast(0)) }
    Box(
        Modifier
            .fillMaxHeight()
            .width(430.dp)
            .background(Brush.horizontalGradient(listOf(Color(0xF502040A), Color(0xE6060D22), Color(0x00060D22))))
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionRight && e.isDown()) { onClose(); true } else false
            }
    ) {
        Column(Modifier.fillMaxSize().padding(start = 36.dp, end = 40.dp, top = 30.dp)) {
            Text("Channels", style = T.H2, color = C.Text)
            Spacer(Modifier.height(12.dp))
            LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(top = FocusRoom / 2, bottom = 40.dp)) {
                itemsIndexed(channels, key = { _, c -> c.key }) { i, c ->
                    val p = nowNext[c.key]?.now
                    val isCurrent = c.key == current.key
                    FocusSurface(
                        onClick = { onPick(c) },
                        modifier = Modifier.fillMaxWidth().height(54.dp).then(if (i == start) Modifier.focusRequester(focus) else Modifier),
                        shape = RoundedCornerShape(10.dp),
                        color = if (isCurrent) C.Glass else Color.Transparent,
                        focusedColor = C.Focus,
                        contentColor = C.Text,
                        focusedContentColor = C.OnFocus,
                        focusedScale = 1.02f,
                        border = false,
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(c.number.toString(), style = T.Label, modifier = Modifier.width(38.dp))
                            ChannelLogo(c.logo, c.name, width = 52.dp, height = 32.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(displayChannelName(c.name), style = T.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p?.title ?: "", style = T.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (isCurrent) Text("●", style = T.Small, color = C.Live)
                        }
                    }
                }
            }
        }
    }
    focus.RequestOnce()
}

// ============================================================================================ VOD + catch-up

@Composable
private fun TimelineOverlay(st: PlayerState, catchup: Session.Catchup?) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val pm = app.playback
    val session by pm.session.collectAsState()
    val vod = (session as? Session.Vod)?.item
    val root = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var controls by remember { mutableStateOf(true) }
    var token by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    // Scrubbing: presses accumulate into a target, applied once you stop pressing.
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    val guide by pm.catchupGuide.collectAsState()

    val clock by produceState(Triple(0L, 0L, 0L), session) {
        while (true) {
            val p = pm.player
            value = if (catchup != null) {
                val pos = pm.catchupPosition()
                Triple(pos, catchup.progStart, catchup.progEnd)
            } else {
                Triple(p.currentPosition, 0L, p.duration.takeIf { it > 0 && it != MC.TIME_UNSET } ?: 0L)
            }
            delay(500)
        }
    }
    val (position, rangeStart, rangeEnd) = clock

    fun show() {
        controls = true
        token++
    }

    fun nudge(direction: Int, repeat: Int) {
        val stepMs = when {
            repeat > 30 -> 5 * MINUTE
            repeat > 12 -> 2 * MINUTE
            repeat > 4 -> MINUTE
            else -> if (direction > 0) 30_000L else 10_000L
        }
        val base = seekTarget ?: position
        // Catch-up scrubs through the channel's whole timeline: any programme in the archive, up to live.
        val upper = if (catchup != null) now() else rangeEnd.takeIf { it > 0 } ?: Long.MAX_VALUE
        val lower = if (catchup != null) pm.archiveStart(catchup.channel) else 0L
        seekTarget = (base + direction * stepMs).coerceIn(lower, upper)
        show()
        seekJob?.cancel()
        seekJob = scope.launch {
            delay(if (catchup != null) 900 else 550)
            val t = seekTarget ?: return@launch
            if (catchup != null) pm.seekCatchupTo(t) else pm.player.seekTo(t)
            seekTarget = null
        }
    }

    LaunchedEffect(token, st.playing, controls) {
        if (controls && st.playing && seekTarget == null) {
            delay(4_500)
            controls = false
        }
    }
    BackHandler(enabled = controls && st.playing && seekTarget == null) { controls = false }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(root)
            .onPreviewKeyEvent { e ->
                when {
                    e.key == Key.DirectionLeft || e.key == Key.MediaRewind -> { if (e.isDown()) nudge(-1, e.nativeKeyEvent.repeatCount); true }
                    e.key == Key.DirectionRight || e.key == Key.MediaFastForward -> { if (e.isDown()) nudge(1, e.nativeKeyEvent.repeatCount); true }
                    e.isSelect() || e.key == Key.MediaPlayPause || e.key == Key.MediaPlay || e.key == Key.MediaPause -> {
                        if (e.isDown() && e.nativeKeyEvent.repeatCount == 0) {
                            if (e.isSelect() && !controls) show() else {
                                pm.togglePause()
                                show()
                            }
                        }
                        true
                    }
                    e.key == Key.DirectionUp || e.key == Key.Menu -> { if (e.isDown()) menu = true; true }
                    e.key == Key.DirectionDown -> {
                        if (e.isDown()) {
                            if (catchup != null) pm.playLive(catchup.channel, catchup.zapList) else show()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AnimatedVisibility(controls || !st.playing || seekTarget != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(300))) {
            Box(Modifier.fillMaxSize()) {
                // top title: for catch-up, the programme under the marker (it changes as you scrub across programmes)
                val shown = seekTarget ?: position
                val prog = if (catchup != null) guide.programmeAt(shown) else null
                Box(Modifier.fillMaxWidth().height(130.dp).background(Brush.verticalGradient(listOf(Color(0xE602040A), Color.Transparent)))) {
                    Column(Modifier.padding(start = 48.dp, top = 30.dp, end = 48.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (catchup != null) {
                                if ((prog?.endMs ?: catchup.progEnd) > now()) {
                                    Badge("Behind live", color = C.Live)
                                } else {
                                    Badge("Catch up", color = C.Catchup, textColor = C.OnFocus)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(displayChannelName(catchup.channel.name), style = T.Label, color = C.TextDim)
                            }
                            Spacer(Modifier.weight(1f))
                            Clock(style = T.H3)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (catchup != null) prog?.title ?: catchup.title else vod?.title.orEmpty(),
                            style = T.H1, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        val sub = when {
                            catchup == null -> vod?.subtitle.orEmpty()
                            else -> {
                                val start = prog?.startMs ?: catchup.progStart
                                val end = prog?.endMs ?: catchup.progEnd
                                val day = dayLabel(start).takeIf { it != "Today" }?.let { "$it " }.orEmpty()
                                "$day${timeOf(start)} – ${timeOf(end)}"
                            }
                        }
                        if (sub.isNotEmpty()) Text(sub, style = T.Body, color = C.TextDim, maxLines = 1)
                    }
                }
                // bottom timeline
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(140.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF202040A))))
                ) {
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, end = 48.dp, bottom = 34.dp)) {
                        val shownPos = seekTarget ?: position
                        val span = (rangeEnd - rangeStart).coerceAtLeast(1)
                        val frac = ((shownPos - rangeStart).toFloat() / span).coerceIn(0f, 1f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (st.playWhenReady) "▶" else "❚❚", style = T.H3, color = C.Text)
                            Spacer(Modifier.width(14.dp))
                            if (catchup != null) {
                                Text(timeOf(shownPos), style = T.H3, color = C.Text)
                            } else {
                                Text(clockLabel(shownPos), style = T.H3, color = C.Text)
                            }
                            if (seekTarget != null) {
                                Spacer(Modifier.width(10.dp))
                                val delta = (seekTarget!! - position) / 1000
                                Text((if (delta >= 0) "+" else "−") + clockLabel(kotlin.math.abs(delta) * 1000), style = T.Label, color = C.Accent)
                            }
                            Spacer(Modifier.weight(1f))
                            if (catchup != null) {
                                Text("${durationLabel(now() - shownPos).ifEmpty { "Under a minute" }} behind live", style = T.H3, color = C.TextDim)
                            } else if (rangeEnd > 0) {
                                Text("−" + clockLabel(rangeEnd - shownPos), style = T.H3, color = C.TextDim)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (catchup != null) {
                            CatchupStrip(guide, shownPos, pm.archiveStart(catchup.channel))
                        } else {
                            Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x40FFFFFF), RoundedCornerShape(3.dp))) {
                                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Brush.horizontalGradient(listOf(C.Accent, C.Accent2)), RoundedCornerShape(3.dp)))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (catchup != null) "◀ ▶ Rewind / forward   OK Pause   ▼ Back to live   ▲ Options"
                            else "◀ ▶ Seek   OK Pause   ▲ Audio & subtitles",
                            style = T.Small, color = C.TextMute,
                        )
                    }
                }
            }
        }
        if (vod?.next != null && st.ended) UpNext(vod.next.title, vod.next.subtitle) { pm.playVod(vod.next) }
        if (vod != null && vod.next == null && st.ended) LaunchedEffect(Unit) { delay(1500); nav.pop() }
    }
    root.RequestOnce(menu, enabled = !menu)

    if (menu) {
        val items = buildList {
            addAll(trackItems(st, pm::selectTrack))
            add(aspectItem(pm.resizeMode.value) { pm.resizeMode.value = it })
            if (catchup == null) add(MenuItem("Start over", "", Icons.Rounded.Replay) { pm.player.seekTo(0) })
            if (catchup != null) add(MenuItem("Back to live", catchup.channel.name, Icons.Rounded.LiveTv) { pm.playLive(catchup.channel, catchup.zapList) })
            vod?.next?.let { n -> add(MenuItem("Next episode", n.subtitle, Icons.Rounded.SkipNext) { pm.playVod(n) }) }
            add(streamInfoItem())
        }
        OptionsMenu(title = catchup?.title ?: vod?.title.orEmpty(), items = items, onDismiss = {
            menu = false
            root.tryFocus()
        })
    }
}

/**
 * Catch-up's timeline: the channel's programmes as a strip that slides under a fixed marker, so scrubbing
 * runs straight from one programme into the next, across the whole archive. Two hours across; the
 * marker is the moment on screen, or where you're scrubbing to.
 */
@Composable
private fun CatchupStrip(guide: List<ProgrammeLite>, shown: Long, archiveStart: Long) {
    // Slide smoothly between presses (relative to an anchor: epoch milliseconds don't fit in a Float).
    val anchor = remember { shown }
    val slide by animateFloatAsState((shown - anchor).toFloat(), tween(140, easing = LinearEasing), label = "strip")
    val centre = anchor + slide.toLong()
    val from = centre - STRIP_WINDOW / 2
    val to = from + STRIP_WINDOW
    val liveAt = now()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(52.dp)) {
        val w = constraints.maxWidth.toFloat()
        fun x(t: Long) = (t - from).toFloat() / STRIP_WINDOW * w
        fun dp(px: Float) = with(density) { px.coerceAtLeast(0f).toDp() }
        val top = with(density) { 12.dp.roundToPx() }
        val gap = with(density) { 1.5.dp.toPx() }
        val shade = Color(0xB302040A)
        Box(Modifier.offset { IntOffset(0, top) }.fillMaxWidth().height(34.dp).background(Color(0x0FFFFFFF), RoundedCornerShape(6.dp)))
        var i = guide.indexOfFirst { it.endMs > from }.coerceAtLeast(0)
        while (i < guide.size && guide[i].startMs < to) {
            val p = guide[i++]
            val l = x(maxOf(p.startMs, from)) + gap
            val r = x(minOf(p.endMs, to)) - gap
            if (r - l < 2f) continue
            val current = shown >= p.startMs && shown < p.endMs
            Box(
                Modifier.offset { IntOffset(l.roundToInt(), top) }.width(dp(r - l)).height(34.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (current) Color(0x40FFFFFF) else Color(0x1FFFFFFF)),
            ) {
                // How far into this programme the marker is.
                if (current) {
                    Box(
                        Modifier.fillMaxHeight().width(dp(x(centre) - l))
                            .background(Brush.horizontalGradient(listOf(C.Accent.copy(alpha = 0.75f), C.Accent2.copy(alpha = 0.75f)))),
                    )
                }
                // Not aired yet, or older than the archive.
                if (p.endMs > liveAt) Box(Modifier.offset { IntOffset((x(liveAt) - l).roundToInt().coerceAtLeast(0), 0) }.fillMaxSize().background(shade))
                if (p.startMs < archiveStart) Box(Modifier.fillMaxHeight().width(dp(x(archiveStart) - l)).background(shade))
                if (r - l > with(density) { 64.dp.toPx() }) {
                    Text(
                        "${timeOf(p.startMs)}  ${p.title}", style = T.Small, color = if (current) C.Text else C.TextDim,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp),
                    )
                }
            }
        }
        if (liveAt in from..to) {
            val lx = x(liveAt).roundToInt()
            Box(Modifier.offset { IntOffset(lx, top - 3.dp.roundToPx()) }.width(2.dp).height(40.dp).background(C.Live))
            Text("LIVE", style = T.Tiny, color = C.Live, modifier = Modifier.offset { IntOffset(lx + 4.dp.roundToPx(), 0) })
        }
        // The marker (always the middle: the strip moves, not the marker).
        Box(
            Modifier.offset { IntOffset((x(centre) - with(density) { 1.dp.toPx() }).roundToInt(), 0) }
                .width(2.dp).fillMaxHeight().background(Color.White, RoundedCornerShape(1.dp)),
        )
    }
}

private const val STRIP_WINDOW = 2 * 60 * 60 * 1000L

@Composable
private fun UpNext(title: String, subtitle: String, onPlay: () -> Unit) {
    val focus = remember { FocusRequester() }
    var seconds by remember { mutableIntStateOf(10) }
    LaunchedEffect(Unit) {
        while (seconds > 0) {
            delay(1000)
            seconds--
        }
        onPlay()
    }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.BottomEnd) {
        Column(Modifier.padding(48.dp).width(360.dp).background(C.Panel, RoundedCornerShape(14.dp)).padding(22.dp)) {
            Text("Up next in $seconds", style = T.Label, color = C.Accent)
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = T.H3, color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(title, style = T.Body, color = C.TextDim, maxLines = 1)
            Spacer(Modifier.height(14.dp))
            PillButton("Play now", onPlay, icon = Icons.Rounded.SkipNext, primary = true, modifier = Modifier.focusRequester(focus))
        }
    }
    focus.RequestOnce()
}

// ============================================================================================ shared

private fun trackItems(st: PlayerState, select: (io.github.lucasclgibson.nimbus.player.TrackOption?, Int) -> Unit): List<MenuItem> {
    val out = ArrayList<MenuItem>()
    if (st.audioTracks.size > 1) {
        st.audioTracks.forEach { t ->
            out += MenuItem("Audio: ${t.label}", t.detail, Icons.Rounded.Audiotrack, t.selected) { select(t, MC.TRACK_TYPE_AUDIO) }
        }
    }
    if (st.textTracks.isNotEmpty()) {
        val anyOn = st.textTracks.any { it.selected }
        out += MenuItem("Subtitles: Off", "", Icons.Rounded.Subtitles, !anyOn) { select(null, MC.TRACK_TYPE_TEXT) }
        st.textTracks.forEach { t ->
            out += MenuItem("Subtitles: ${t.label}", t.detail, Icons.Rounded.Subtitles, t.selected) { select(t, MC.TRACK_TYPE_TEXT) }
        }
    }
    return out
}

private fun aspectItem(current: Int, set: (Int) -> Unit): MenuItem {
    val modes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
    )
    val idx = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)
    val next = modes[(idx + 1) % modes.size]
    return MenuItem("Picture: ${modes[idx].second}", "Switch to ${next.second}", Icons.Rounded.AspectRatio) { set(next.first) }
}

/** Buffering spinner, reconnect notice and error card – shared by every mode. */
@Composable
private fun StatusLayer(st: PlayerState) {
    val app = LocalApp.current
    val nav = LocalNav.current
    // Only show the spinner if a stall lasts: sub-second hiccups shouldn't draw attention.
    var spinner by remember { mutableStateOf(false) }
    val waiting = (st.buffering || st.retrying || st.preparing) && st.error == null
    LaunchedEffect(waiting) {
        if (waiting) {
            delay(700)
            spinner = true
        } else spinner = false
    }
    Box(Modifier.fillMaxSize()) {
        if (spinner && waiting) {
            Box(Modifier.align(Alignment.Center)) { Spinner() }
        }
        if (st.retrying && st.error == null) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 48.dp)
                    .background(Color(0xCC02040A), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Reconnecting…", style = T.Label, color = C.Text)
            }
        }
        val err = st.error
        if (err != null) {
            val focus = remember { FocusRequester() }
            Box(Modifier.fillMaxSize().background(Color(0xB302040A)), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.width(520.dp).background(C.Panel, RoundedCornerShape(16.dp)).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Can't play this right now", style = T.H2, color = C.Text)
                    Spacer(Modifier.height(8.dp))
                    Text(err, style = T.Body, color = C.TextDim)
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PillButton("Try again", { app.playback.retryNow() }, primary = true, modifier = Modifier.focusRequester(focus))
                        PillButton("Back", { nav.pop() })
                    }
                }
            }
            focus.RequestOnce(err)
        }
    }
}

@Composable
fun Spinner(size: androidx.compose.ui.unit.Dp = 46.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Canvas(Modifier.size(size)) {
        val stroke = 4.dp.toPx()
        drawArc(
            color = Color(0x33FFFFFF), startAngle = 0f, sweepAngle = 360f, useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke),
        )
        drawArc(
            color = Color.White, startAngle = angle, sweepAngle = 90f, useCenter = false,
            topLeft = Offset(stroke / 2, stroke / 2), size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}
