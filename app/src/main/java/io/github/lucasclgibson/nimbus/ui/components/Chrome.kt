package io.github.lucasclgibson.nimbus.ui.components

import android.view.ViewGroup
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.lucasclgibson.nimbus.player.PlaybackManager
import io.github.lucasclgibson.nimbus.ui.nav.Dest
import io.github.lucasclgibson.nimbus.ui.nav.LocalApp
import io.github.lucasclgibson.nimbus.ui.nav.LocalNav
import io.github.lucasclgibson.nimbus.ui.nav.TopTabs
import io.github.lucasclgibson.nimbus.ui.theme.C
import io.github.lucasclgibson.nimbus.ui.theme.T
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------- top nav

@Composable
fun TopNav(current: Dest, modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val selected = remember { FocusRequester() }
    Row(
        modifier.fillMaxWidth().height(58.dp).padding(start = 48.dp, end = 48.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark()
        Spacer(Modifier.width(26.dp))
        // Entering the bar from below always lands on the current tab (never the geometrically nearest one).
        @Suppress("DEPRECATION")
        Row(
            Modifier.focusProperties { enter = { selected } }.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TopTabs.forEach { (dest, label) ->
                val isCurrent = dest == current
                FocusSurface(
                    onClick = { if (!isCurrent) nav.go(dest) },
                    modifier = Modifier.height(34.dp).then(if (isCurrent) Modifier.focusRequester(selected) else Modifier),
                    shape = RoundedCornerShape(50),
                    color = if (isCurrent) C.Glass else Color.Transparent,
                    focusedColor = C.Focus,
                    contentColor = if (isCurrent) C.Text else C.TextDim,
                    focusedContentColor = C.OnFocus,
                    focusedScale = 1.04f,
                    border = false,
                ) {
                    Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = T.Label)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        SyncIndicator()
        Spacer(Modifier.width(16.dp))
        Clock()
    }
}

@Composable
fun SyncIndicator() {
    val app = LocalApp.current
    val running by app.sync.running.collectAsState()
    val status by app.sync.status.collectAsState()
    if (running.isEmpty()) return
    val s = running.firstNotNullOfOrNull { status[it] }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(C.Accent)
        Spacer(Modifier.width(8.dp))
        val pct = s?.fraction?.let { " ${(it * 100).roundToInt()}%" }.orEmpty()
        Text((s?.message ?: "Updating…") + pct, style = T.Small, color = C.TextDim, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------- video

/** Marks where live video should appear on this screen. The single player renders there. */
@Composable
fun VideoSlot(modifier: Modifier = Modifier) {
    val pm = LocalApp.current.playback
    val owner = remember { Any() }
    Box(
        modifier.onGloballyPositioned { c ->
            val b = c.boundsInRoot()
            pm.claimSlot(owner, IntRect(b.left.roundToInt(), b.top.roundToInt(), b.right.roundToInt(), b.bottom.roundToInt()))
        }
    )
    DisposableEffect(Unit) { onDispose { pm.releaseSlot(owner) } }
}

@Composable
fun VideoLayer(pm: PlaybackManager) {
    val rect by pm.slot.collectAsState()
    val resize by pm.resizeMode.collectAsState()
    val density = LocalDensity.current
    val config = androidx.compose.ui.platform.LocalConfiguration.current
    val r = rect
    // The surface is created once at full-screen size and never shrunk to zero: when no screen shows
    // video it is parked off-screen. Re-creating SurfaceViews from 0x0 is where black-screen bugs live.
    val mod = if (r == null || r.width <= 0 || r.height <= 0) {
        Modifier
            .offset { IntOffset(-(config.screenWidthDp.dp.roundToPx() * 2), 0) }
            .size(config.screenWidthDp.dp, config.screenHeightDp.dp)
    } else {
        Modifier
            .offset { IntOffset(r.left, r.top) }
            .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                // Transparent shutter: if a first-frame callback is ever missed we still show the picture.
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                setBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                player = pm.player
                subtitleView?.apply {
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK,
                            android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD),
                        )
                    )
                    setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.08f)
                    setApplyEmbeddedStyles(false)
                    setBottomPaddingFraction(0.07f)
                }
                (videoSurfaceView as? android.view.SurfaceView)?.holder?.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: android.view.SurfaceHolder) = redraw()
                    override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = redraw()
                    override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
                    private fun redraw() {
                        invalidate()
                        post { videoSurfaceView?.invalidate(); invalidate() }
                    }
                })
            }
        },
        update = { it.resizeMode = resize },
        modifier = mod,
    )
}

// ---------------------------------------------------------------------------------------- backdrop

class BackdropState {
    var url by mutableStateOf<String?>(null)
}

/** Full-screen artwork behind everything, faded into the dark base like Sky's hero pages. */
@Composable
fun BackdropLayer(state: BackdropState) {
    // GPU fill is the stick's bottleneck: draw the base gradient once, then the artwork, then only the
    // parts of the scrims that are actually visible (no full-screen transparent passes).
    val base = remember { Brush.verticalGradient(listOf(C.Bg2, C.Bg1, C.Bg0)) }
    // Offscreen = HWUI caches the composed backdrop as one texture and only re-renders it when the artwork
    // changes, so scrolling pays for a single full-screen draw instead of four.
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawBehind { drawRect(base) }
    ) {
        Crossfade(targetState = state.url, animationSpec = tween(400), label = "backdrop") { url ->
            if (!url.isNullOrBlank()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            val w = size.width
                            val h = size.height
                            val left = Brush.horizontalGradient(
                                0f to C.Bg0, 0.5f to C.Bg0.copy(alpha = 0.88f), 1f to Color.Transparent,
                                startX = 0f, endX = w * 0.78f,
                            )
                            val top = Brush.verticalGradient(0f to C.Bg0.copy(alpha = 0.55f), 1f to Color.Transparent, startY = 0f, endY = h * 0.18f)
                            val bottom = Brush.verticalGradient(0f to Color.Transparent, 0.35f to C.Bg0.copy(alpha = 0.55f), 1f to C.Bg0, startY = h * 0.45f, endY = h)
                            onDrawWithContent {
                                drawContent()
                                drawRect(left, size = androidx.compose.ui.geometry.Size(w * 0.78f, h))
                                drawRect(top, size = androidx.compose.ui.geometry.Size(w, h * 0.18f))
                                drawRect(bottom, topLeft = androidx.compose.ui.geometry.Offset(0f, h * 0.45f), size = androidx.compose.ui.geometry.Size(w, h * 0.55f))
                            }
                        }
                ) {
                    RemoteImage(
                        io.github.lucasclgibson.nimbus.core.tmdbSized(url, "w1280"), Modifier.fillMaxSize(), 960.dp, 540.dp,
                        contentScale = ContentScale.Crop, alignment = Alignment.TopEnd, fade = true,
                    )
                }
            }
        }
    }
}

/**
 * Artwork contained to a hero band (top-right), faded into the background with an alpha mask.
 * Full-screen artwork behind scrolling rows costs the stick's GPU too much; this costs a fraction
 * and is cached as one layer.
 */
@Composable
fun HeroImage(url: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithCache {
                val fadeLeft = Brush.horizontalGradient(0f to Color.Transparent, 0.42f to Color.Black, startX = 0f, endX = size.width)
                val fadeBottom = Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent, startY = 0f, endY = size.height)
                val fadeTop = Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.22f to Color.Black, startY = 0f, endY = size.height)
                onDrawWithContent {
                    drawContent()
                    drawRect(fadeLeft, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    drawRect(fadeBottom, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                    drawRect(fadeTop, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                }
            }
    ) {
        Crossfade(targetState = url, animationSpec = tween(320), label = "hero") { u ->
            if (!u.isNullOrBlank()) {
                RemoteImage(
                    io.github.lucasclgibson.nimbus.core.tmdbSized(u, "w780"), Modifier.fillMaxSize(), 600.dp, 338.dp,
                    contentScale = ContentScale.Crop, alignment = Alignment.TopCenter,
                )
            }
        }
    }
}

@Composable
fun SetBackdrop(url: String?) {
    val state = LocalBackdrop.current
    LaunchedEffect(url) { state.url = url?.ifBlank { null } }
}

val LocalBackdrop = androidx.compose.runtime.staticCompositionLocalOf { BackdropState() }

// ---------------------------------------------------------------------------------------- input

/**
 * Remote-friendly text field: D-pad moves over it like a button, OK opens the keyboard,
 * and "Done" hands focus back so navigation keeps working.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Uri,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onDone: () -> Unit = {},
) {
    val field = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) { if (editing) field.tryFocus() }
    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label, style = T.Label, color = C.TextDim)
            Spacer(Modifier.height(6.dp))
        }
        FocusSurface(
            onClick = { editing = true },
            modifier = Modifier.fillMaxWidth().height(46.dp).focusRequester(focusRequester),
            shape = RoundedCornerShape(10.dp),
            color = C.Panel,
            focusedColor = C.PanelHi,
            focusedScale = 1.02f,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
                    .focusRequester(field)
                    .focusProperties { canFocus = editing }
                    .onFocusChanged { if (!it.isFocused && editing) editing = false },
                textStyle = T.H3.copy(color = C.Text),
                singleLine = true,
                cursorBrush = SolidColor(C.Accent),
                visualTransformation = if (password && !editing) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = {
                    editing = false
                    focusRequester.tryFocus()
                    onDone()
                }),
            )
            if (value.isEmpty()) {
                Text(
                    placeholder, style = T.H3, color = C.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- menus

data class MenuItem(
    val label: String,
    val detail: String = "",
    val icon: ImageVector? = null,
    val selected: Boolean = false,
    val action: () -> Unit,
)

/** Focus-trapping options dialog (separate window, so the D-pad can't wander off behind it). */
@Composable
fun OptionsMenu(title: String, items: List<MenuItem>, onDismiss: () -> Unit, subtitle: String = "", focusIndex: Int? = null) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(min = 360.dp, max = 400.dp)
                    .background(Brush.horizontalGradient(listOf(C.Bg1.copy(alpha = 0.96f), C.Bg0)))
                    .padding(horizontal = 28.dp, vertical = 36.dp),
            ) {
                Text(title, style = T.H2, color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, style = T.Body, color = C.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(20.dp))
                val initial = focusIndex ?: items.indexOfFirst { it.selected }.coerceAtLeast(0)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = FocusRoom / 2)) {
                    itemsIndexed(items) { i, item ->
                        FocusSurface(
                            onClick = {
                                onDismiss()
                                item.action()
                            },
                            modifier = Modifier.fillMaxWidth().height(if (item.detail.isEmpty()) 46.dp else 56.dp)
                                .then(if (i == initial) Modifier.focusRequester(first) else Modifier),
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent,
                            focusedColor = C.Focus,
                            contentColor = C.Text,
                            focusedContentColor = C.OnFocus,
                            focusedScale = 1.02f,
                            border = false,
                        ) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (item.icon != null) {
                                    Icon(item.icon, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(item.label, style = T.H3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (item.detail.isNotEmpty()) {
                                        Text(item.detail, style = T.Small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.offset(y = 1.dp))
                                    }
                                }
                                if (item.selected) Text("✓", style = T.H3)
                            }
                        }
                    }
                }
            }
        }
        first.RequestOnce()
    }
}
