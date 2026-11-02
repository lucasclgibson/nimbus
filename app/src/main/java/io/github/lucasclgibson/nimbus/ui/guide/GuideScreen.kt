package io.github.lucasclgibson.nimbus.ui.guide

import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsOff
import io.github.lucasclgibson.nimbus.data.repo.Reminder
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.lucasclgibson.nimbus.core.HOUR
import io.github.lucasclgibson.nimbus.core.MINUTE
import io.github.lucasclgibson.nimbus.core.dayLabel
import io.github.lucasclgibson.nimbus.core.displayChannelName
import io.github.lucasclgibson.nimbus.core.durationLabel
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.core.timeOf
import io.github.lucasclgibson.nimbus.data.db.ChannelEntity
import io.github.lucasclgibson.nimbus.data.db.Kind
import io.github.lucasclgibson.nimbus.data.db.ProgrammeLite
import io.github.lucasclgibson.nimbus.player.Session
import io.github.lucasclgibson.nimbus.ui.components.Badge
import io.github.lucasclgibson.nimbus.ui.components.ChannelLogo
import io.github.lucasclgibson.nimbus.ui.components.Clock
import io.github.lucasclgibson.nimbus.ui.components.EmptyState
import io.github.lucasclgibson.nimbus.ui.components.FocusSurface
import io.github.lucasclgibson.nimbus.ui.components.MenuItem
import io.github.lucasclgibson.nimbus.ui.components.OptionsMenu
import io.github.lucasclgibson.nimbus.ui.components.PillButton
import io.github.lucasclgibson.nimbus.ui.components.ProgressLine
import io.github.lucasclgibson.nimbus.ui.components.RequestOnce
import io.github.lucasclgibson.nimbus.ui.components.SetBackdrop
import io.github.lucasclgibson.nimbus.ui.components.VideoSlot
import io.github.lucasclgibson.nimbus.ui.components.tryFocus
import io.github.lucasclgibson.nimbus.ui.nav.Dest
import io.github.lucasclgibson.nimbus.ui.nav.LocalApp
import io.github.lucasclgibson.nimbus.ui.nav.LocalNav
import io.github.lucasclgibson.nimbus.ui.nav.entryState
import io.github.lucasclgibson.nimbus.ui.theme.C
import io.github.lucasclgibson.nimbus.ui.theme.T
import kotlinx.coroutines.launch

private val ROW_H = 46.dp
private val CHANNEL_W = 196.dp

@Composable
fun GuideScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val state = entryState { GuideState(app, it) }
    val session by app.playback.session.collectAsState()
    val favourites by app.library.favourites.collectAsState()
    val grid = remember { FocusRequester() }
    val chips = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<GuideMenu?>(null) }
    SetBackdrop(null)

    val measurer = rememberTextMeasurer(cacheSize = 512)
    val bell = rememberVectorPainter(Icons.Rounded.NotificationsActive)
    val reminderList by app.reminders.list.collectAsState()
    val reminderTimes = remember(reminderList) { reminderList.groupBy({ it.channelKey }, { it.startMs }).mapValues { it.value.toSet() } }

    val playingKey = when (val s = session) {
        is Session.Live -> s.channel.key
        is Session.Catchup -> s.channel.key
        else -> null
    }

    fun play(slot: Slot?, fromStart: Boolean = false) {
        val ch = state.channel ?: return
        val t = now()
        val p = slot?.programme
        when {
            p != null && p.endMs <= t && app.live.canCatchup(ch, p.startMs) ->
                app.playback.playCatchup(ch, state.channels, p.title, p.startMs, p.endMs)
            p != null && fromStart && p.startMs <= t && app.live.canCatchup(ch, p.startMs) ->
                app.playback.playCatchup(ch, state.channels, p.title, p.startMs, p.endMs)
            slot != null && slot.start > t -> {
                menu = GuideMenu.Info
                return
            }
            playingKey == ch.key && session is Session.Live -> Unit
            else -> app.playback.playLive(ch, state.channels)
        }
        nav.push(Dest.Player)
    }

    Column(Modifier.fillMaxSize()) {
        // ------------------------------------------------ header: programme info + mini TV
        Row(Modifier.fillMaxWidth().height(194.dp).padding(start = 48.dp, end = 40.dp, top = 20.dp, bottom = 10.dp)) {
            ProgrammeHeader(state, Modifier.weight(1f).fillMaxHeight())
            Spacer(Modifier.width(26.dp))
            // No background here: the video surface sits *behind* the UI and shows through transparent areas.
            Box(Modifier.size(292.dp, 164.dp)) {
                if (playingKey != null) {
                    VideoSlot(Modifier.fillMaxSize())
                } else {
                    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(C.Panel), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.LiveTv, null, Modifier.size(32.dp), tint = C.TextMute)
                        Spacer(Modifier.height(8.dp))
                        Text("Press OK to watch", style = T.Small, color = C.TextMute)
                    }
                }
            }
        }

        // ------------------------------------------------ groups
        val groupState = rememberLazyListState()
        val selectedGroup = state.groups.indexOfFirst { it.id == state.groupId }.coerceAtLeast(0)
        LazyRow(
            state = groupState,
            modifier = Modifier.fillMaxWidth().height(40.dp).focusRequester(chips).focusRestorer(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(state.groups, key = { _, g -> g.id }) { i, g ->
                val selected = i == selectedGroup
                FocusSurface(
                    onClick = {
                        state.selectGroup(g.id)
                        grid.tryFocus()
                    },
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(50),
                    color = if (selected) C.GlassHi else C.Glass,
                    focusedColor = C.Focus,
                    contentColor = if (selected) C.Text else C.TextDim,
                    focusedContentColor = C.OnFocus,
                    focusedScale = 1.05f,
                    border = false,
                ) {
                    Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(g.title, style = T.Label, maxLines = 1)
                    }
                }
            }
        }
        LaunchedEffect(selectedGroup, state.groups.size) {
            if (state.groups.isNotEmpty()) groupState.scrollToItem((selectedGroup - 1).coerceAtLeast(0))
        }

        // ------------------------------------------------ grid
        if (state.loaded && state.channels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (state.groupId == "fav") "No favourites yet" else "No channels",
                    if (app.store.all.value.isEmpty()) "Add a playlist in Settings to get started."
                    else "Press ☰ on any channel in the guide to add it to your favourites.",
                    action = { PillButton("Settings", { nav.go(Dest.Settings) }) },
                )
            }
        } else {
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = 6.dp)) {
                val areaWidth = maxWidth - CHANNEL_W - 48.dp - 24.dp
                val dpPerMin = areaWidth.value / (WINDOW / MINUTE).toFloat()
                val density = LocalDensity.current
                Column(Modifier.fillMaxSize()) {
                    TimeHeader(state, dpPerMin)
                    var pendingClick by remember { mutableStateOf(false) }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .focusRequester(grid)
                            .onFocusChanged { state.gridFocused = it.isFocused }
                            .onPreviewKeyEvent { e ->
                                val down = e.type == KeyEventType.KeyDown
                                val repeat = e.nativeKeyEvent.repeatCount
                                when (e.key) {
                                    Key.DirectionUp -> {
                                        if (down && !state.moveVertical(-1)) chips.tryFocus()
                                        true
                                    }
                                    Key.DirectionDown -> { if (down) state.moveVertical(1); true }
                                    Key.DirectionLeft -> { if (down) state.moveHorizontal(-1); true }
                                    Key.DirectionRight -> { if (down) state.moveHorizontal(1); true }
                                    Key.ChannelUp, Key.PageUp -> { if (down) state.page(-1); true }
                                    Key.ChannelDown, Key.PageDown -> { if (down) state.page(1); true }
                                    Key.MediaFastForward -> { if (down) state.jumpDays(1); true }
                                    Key.MediaRewind -> { if (down) state.jumpDays(-1); true }
                                    Key.MediaPlayPause, Key.MediaPlay -> { if (down) state.jumpNow(); true }
                                    // ☰ opens the programme's options (channel groups are in there too).
                                    Key.Menu -> { if (down && repeat == 0) menu = GuideMenu.Programme; true }
                                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                        if (down && repeat == 0) pendingClick = true
                                        if (down && repeat == 1 && pendingClick) {
                                            pendingClick = false
                                            menu = GuideMenu.Programme
                                        }
                                        if (!down && pendingClick) {
                                            pendingClick = false
                                            play(state.focusedSlot)
                                        }
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .focusable(),
                    ) {
                        LaunchedEffect(Unit) {
                            snapshotFlow { state.listState.layoutInfo.viewportSize.height }.collect { h ->
                                if (h > 0) state.visibleRows = (h / with(density) { ROW_H.toPx() }).toInt().coerceAtLeast(3)
                            }
                        }
                        LaunchedEffect(state.channels) {
                            snapshotFlow { state.listState.firstVisibleItemIndex }.collect { state.requestVisible() }
                        }
                        val focusedIndex = state.channelIndex
                        LazyColumn(state = state.listState, modifier = Modifier.fillMaxSize(), userScrollEnabled = false) {
                            itemsIndexed(state.channels, key = { _, c -> c.key }) { i, ch ->
                                val isFocusedRow = i == focusedIndex
                                GuideRow(
                                    channel = ch,
                                    programmes = state.rows[ch.key],
                                    windowStart = state.windowStart,
                                    dpPerMin = dpPerMin,
                                    focusTime = { if (isFocusedRow && state.gridFocused) state.focusTime else Long.MIN_VALUE },
                                    rowFocused = isFocusedRow,
                                    now = state.clock,
                                    playing = ch.key == playingKey,
                                    favourite = "${Kind.LIVE}:${ch.key}" in favourites,
                                    catchup = ch.catchupDays > 0,
                                    reminders = reminderTimes[ch.key] ?: emptySet(),
                                    slots = { list, from, to -> state.slotsIn(list, from, to) },
                                    measurer = measurer,
                                    bell = bell,
                                )
                            }
                        }
                        NowLine(state, dpPerMin)
                    }
                }
            }
        }
    }
    grid.RequestOnce(state.loaded, enabled = state.loaded && state.channels.isNotEmpty())
    // Belt and braces: the guide should always open with the cursor in the grid, not on a group chip.
    LaunchedEffect(state.loaded) {
        if (state.loaded) {
            kotlinx.coroutines.delay(450)
            if (!state.gridFocused && state.channels.isNotEmpty()) grid.tryFocus()
        }
    }

    when (menu) {
        GuideMenu.Groups -> OptionsMenu(
            title = "Channel groups",
            items = state.groups.map { g ->
                MenuItem(g.title, selected = g.id == state.groupId, action = {
                    state.selectGroup(g.id)
                    scope.launch { grid.tryFocus() }
                })
            },
            onDismiss = { menu = null },
        )
        GuideMenu.Programme, GuideMenu.Info -> {
            val ch = state.channel
            val slot = state.focusedSlot
            if (ch != null && slot != null) {
                val p = slot.programme
                val t = now()
                val isFav = "${Kind.LIVE}:${ch.key}" in favourites
                val items = buildList {
                    add(MenuItem("Watch ${displayChannelName(ch.name)}", "Live now", Icons.Rounded.LiveTv) {
                        app.playback.playLive(ch, state.channels)
                        nav.push(Dest.Player)
                    })
                    if (p != null && p.startMs <= t && p.endMs > t && app.live.canCatchup(ch, p.startMs)) {
                        add(MenuItem("Watch from start", "${timeOf(p.startMs)} · ${p.title}", Icons.Rounded.Replay) { play(slot, fromStart = true) })
                    }
                    if (p != null && p.endMs <= t && app.live.canCatchup(ch, p.startMs)) {
                        add(MenuItem("Play from catch-up", p.title, Icons.Rounded.History) { play(slot) })
                    }
                    if (p != null && p.startMs > t) {
                        val set = (reminderTimes[ch.key] ?: emptySet()).contains(p.startMs)
                        add(
                            MenuItem(
                                if (set) "Cancel reminder" else "Remind me",
                                if (set) "" else "A prompt appears just before it starts",
                                if (set) Icons.Rounded.NotificationsOff else Icons.Rounded.NotificationsActive,
                            ) {
                                app.reminders.toggle(Reminder(ch.key, displayChannelName(ch.name), p.title, p.startMs, p.endMs))
                            }
                        )
                    }
                    add(
                        MenuItem(
                            if (isFav) "Remove from favourites" else "Add to favourites", displayChannelName(ch.name),
                            if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        ) { scope.launch { app.library.toggleFavourite(Kind.LIVE, ch.key) } }
                    )
                    add(MenuItem("Jump to now", "", Icons.Rounded.PlayArrow) { state.jumpNow() })
                    add(
                        MenuItem("Channel groups", state.groups.firstOrNull { it.id == state.groupId }?.title.orEmpty(), Icons.Rounded.ViewList) {
                            menu = GuideMenu.Groups
                        }
                    )
                }
                OptionsMenu(
                    title = p?.title ?: ch.name,
                    subtitle = if (p != null) "${displayChannelName(ch.name)} · ${dayLabel(p.startMs)} ${timeOf(p.startMs)} – ${timeOf(p.endMs)}" else "",
                    items = items,
                    onDismiss = { menu = null },
                )
            } else {
                LaunchedEffect(Unit) { menu = null }
            }
        }
        null -> Unit
    }
}

private enum class GuideMenu { Groups, Programme, Info }

@Composable
private fun ProgrammeHeader(state: GuideState, modifier: Modifier) {
    val ch = state.header?.first
    val slot = state.header?.second
    val p = slot?.programme
    val detail = state.detail?.takeIf { it.id == p?.id }
    val t = state.clock
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TV Guide", style = T.Label, color = C.Accent)
            Spacer(Modifier.width(10.dp))
            if (ch != null) Text("${ch.number}  ${displayChannelName(ch.name)}", style = T.Label, color = C.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Clock(style = T.H3)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            p?.title ?: if (ch != null) displayChannelName(ch.name) else "",
            style = T.H1, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (slot != null) {
                val live = slot.start <= t && slot.end > t
                Text(
                    "${dayLabel(slot.start)}  ${timeOf(slot.start)} – ${timeOf(slot.end)}" +
                        (if (p != null) "  ·  ${durationLabel(slot.end - slot.start)}" else ""),
                    style = T.BodyStrong, color = C.TextDim,
                )
                Spacer(Modifier.width(10.dp))
                if (live) Badge("Live")
                if (ch != null && p != null && slot.end <= t && ch.catchupDays > 0 && slot.start >= t - ch.catchupDays * 24 * HOUR) {
                    Spacer(Modifier.width(6.dp))
                    Badge("Catch up", color = C.Catchup, textColor = C.OnFocus)
                }
                if (ch != null && p != null && live && ch.catchupDays > 0) {
                    Spacer(Modifier.width(6.dp))
                    Badge("Restart", color = C.Catchup, textColor = C.OnFocus)
                }
                detail?.episode?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = T.BodyStrong, color = C.TextDim)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val desc = when {
            detail != null -> listOf(detail.subtitle, detail.description).filter { it.isNotBlank() }.joinToString(" – ")
            p == null && slot != null -> "No programme information"
            else -> ""
        }
        Text(desc, style = T.Body, color = C.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TimeHeader(state: GuideState, dpPerMin: Float) {
    val ws = state.windowStart
    Row(Modifier.fillMaxWidth().height(26.dp).padding(start = 48.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(dayLabel(state.focusTime), style = T.Label, color = C.Text, modifier = Modifier.width(CHANNEL_W))
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            for (i in 0 until 4) {
                val t = ws + i * 30 * MINUTE
                Text(
                    timeOf(t), style = T.Label, color = C.TextDim,
                    modifier = Modifier.offset(x = (i * 30 * dpPerMin).dp + 6.dp).align(Alignment.CenterStart),
                )
                Box(Modifier.offset(x = (i * 30 * dpPerMin).dp).width(1.dp).fillMaxHeight().background(C.Stroke))
            }
        }
    }
}

@Composable
private fun NowLine(state: GuideState, dpPerMin: Float) {
    val t = state.clock
    val ws = state.windowStart
    if (t < ws || t > ws + WINDOW) return
    val x = CHANNEL_W + 48.dp + ((t - ws) / MINUTE.toFloat() * dpPerMin).dp
    Box(Modifier.offset(x = x - 1.dp).width(2.dp).fillMaxHeight().background(C.Live.copy(alpha = 0.85f)))
}

@Composable
private fun GuideRow(
    channel: ChannelEntity,
    programmes: List<ProgrammeLite>?,
    windowStart: Long,
    dpPerMin: Float,
    focusTime: () -> Long,
    rowFocused: Boolean,
    now: Long,
    playing: Boolean,
    favourite: Boolean,
    catchup: Boolean,
    reminders: Set<Long>,
    slots: (List<ProgrammeLite>, Long, Long) -> List<Slot>,
    measurer: TextMeasurer,
    bell: Painter,
) {
    Row(Modifier.fillMaxWidth().height(ROW_H).padding(start = 48.dp, end = 24.dp)) {
        // channel cell
        Row(
            Modifier
                .width(CHANNEL_W - 6.dp)
                .fillMaxHeight()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (rowFocused) C.PanelHi else C.Panel)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(24.dp).background(if (playing) C.Live else Color.Transparent, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(5.dp))
            Text(channel.number.toString(), style = T.Label, color = if (rowFocused) C.Text else C.TextMute, modifier = Modifier.width(34.dp), maxLines = 1)
            ChannelLogo(channel.logo, channel.name, width = 54.dp, height = 32.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(displayChannelName(channel.name), style = T.Small, color = if (rowFocused) C.Text else C.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (favourite || catchup) {
                    Text(
                        (if (favourite) "♥ " else "") + (if (catchup) "↺ ${channel.catchupDays}d" else ""),
                        style = T.Tiny, color = if (catchup) C.Catchup else C.Live, maxLines = 1,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        ProgrammeStrip(
            programmes, windowStart, dpPerMin, focusTime, now, catchup, reminders, slots, measurer, bell,
            Modifier.weight(1f).fillMaxHeight(),
        )
    }
}

/**
 * The programme cells of one row, drawn on a single canvas: no per-cell composables, cached text
 * layouts, and the cursor is read at draw time so moving it only redraws the row it's in.
 */
@Composable
private fun ProgrammeStrip(
    programmes: List<ProgrammeLite>?,
    windowStart: Long,
    dpPerMin: Float,
    focusTime: () -> Long,
    now: Long,
    archive: Boolean,
    reminders: Set<Long>,
    slots: (List<ProgrammeLite>, Long, Long) -> List<Slot>,
    measurer: TextMeasurer,
    bell: Painter,
    modifier: Modifier,
) {
    val we = windowStart + WINDOW
    val list = remember(programmes, windowStart) { programmes?.let { slots(it, windowStart, we) } }
    Canvas(modifier) {
        val pxPerMin = dpPerMin * density
        val gap = 3.dp.toPx()
        val vpad = 2.dp.toPx()
        val radius = CornerRadius(6.dp.toPx())
        val hpad = 9.dp.toPx()
        val h = size.height - 2 * vpad
        if (list == null) {
            drawRoundRect(C.CellPast, Offset(0f, vpad), Size(size.width, h), radius)
            return@Canvas
        }
        val ft = focusTime()
        for (s in list) {
            val x0 = (maxOf(s.start, windowStart) - windowStart) / MINUTE.toFloat() * pxPerMin
            val x1 = (minOf(s.end, we) - windowStart) / MINUTE.toFloat() * pxPerMin - gap
            val w = x1 - x0
            if (w <= 1f) continue
            val focused = s.start <= ft && ft < s.end
            val past = s.end <= now
            val live = s.start <= now && s.end > now
            val bg = when {
                focused -> C.Focus
                live -> C.CellNow
                past -> C.CellPast
                else -> C.Cell
            }
            drawRoundRect(bg, Offset(x0, vpad), Size(w, h), radius)

            val right = x1 - hpad
            var x = x0 + hpad
            if (right - x < 8f) continue
            // Markers before the title: catch-up available, reminder set.
            val catchupMark = past && archive && s.programme != null && w > 40.dp.toPx()
            val reminderMark = s.programme != null && s.start in reminders
            var markWidth = 0f
            val iconSize = 12.dp.toPx()
            if (reminderMark) markWidth += iconSize + 4.dp.toPx()
            val arrow = if (catchupMark) measurer.measure("↺ ", T.Label) else null
            if (arrow != null) markWidth += arrow.size.width
            val avail = (right - x - markWidth).toInt()
            if (avail < 6) continue
            val titleLayout = measurer.measure(
                s.programme?.title ?: "No information", T.Label, overflow = TextOverflow.Ellipsis, softWrap = false,
                maxLines = 1, constraints = Constraints(maxWidth = avail),
            )
            val timeLayout = if (focused && w > 90.dp.toPx()) measurer.measure(
                "${timeOf(s.start)} – ${timeOf(s.end)}", T.Small, overflow = TextOverflow.Clip, softWrap = false,
                maxLines = 1, constraints = Constraints(maxWidth = (right - x).toInt().coerceAtLeast(1)),
            ) else null
            val total = titleLayout.size.height + (timeLayout?.size?.height ?: 0)
            val y = vpad + (h - total) / 2f
            val fg = when {
                focused -> C.OnFocus
                past -> C.TextMute
                else -> C.Text
            }
            if (reminderMark) {
                translate(x, y + (titleLayout.size.height - iconSize) / 2f) {
                    with(bell) { draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(if (focused) C.OnFocus else C.Gold)) }
                }
                x += iconSize + 4.dp.toPx()
            }
            if (arrow != null) {
                drawText(arrow, color = if (focused) C.OnFocus else C.Catchup, topLeft = Offset(x, y))
                x += arrow.size.width
            }
            drawText(titleLayout, color = fg, topLeft = Offset(x, y))
            if (timeLayout != null) {
                drawText(timeLayout, color = C.OnFocus.copy(alpha = 0.7f), topLeft = Offset(x0 + hpad, y + titleLayout.size.height))
            }
            if (live && s.programme != null) {
                val px0 = x0 + 6.dp.toPx()
                val pw = (x1 - 6.dp.toPx()) - px0
                if (pw > 0) {
                    val py = vpad + h - 5.dp.toPx()
                    val ph = 2.dp.toPx()
                    drawRect(Color(0x40FFFFFF), Offset(px0, py), Size(pw, ph))
                    val frac = ((now - s.start).toFloat() / (s.end - s.start)).coerceIn(0f, 1f)
                    drawRect(if (focused) C.Accent else C.Live, Offset(px0, py), Size(pw * frac, ph))
                }
            }
        }
    }
}
