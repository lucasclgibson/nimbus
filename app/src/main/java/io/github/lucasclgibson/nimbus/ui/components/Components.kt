package io.github.lucasclgibson.nimbus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.layout
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.core.timeOf
import io.github.lucasclgibson.nimbus.core.tmdbSized
import io.github.lucasclgibson.nimbus.ui.theme.Brushes
import io.github.lucasclgibson.nimbus.ui.theme.C
import io.github.lucasclgibson.nimbus.ui.theme.T
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------------------- images

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    width: Dp,
    height: Dp,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    fade: Boolean = false,
    fallback: @Composable BoxScope.() -> Unit = {},
) {
    val context = LocalPlatformContext.current
    val density = LocalDensity.current
    // Only compose the (text) fallback when there is no image or it failed: composing it for every
    // poster while it loads made fast scrolling janky on the stick.
    var failed by remember(url) { mutableStateOf(false) }
    val request = remember(url, width, height, fade) {
        ImageRequest.Builder(context)
            .data(url)
            .size(with(density) { width.roundToPx().coerceAtLeast(1) }, with(density) { height.roundToPx().coerceAtLeast(1) })
            .crossfade(if (fade) 160 else 0)
            .build()
    }
    Box(modifier) {
        if (url.isNullOrBlank() || failed) {
            fallback()
        } else {
            AsyncImage(
                model = request,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
                alignment = alignment,
                onState = { if (it is AsyncImagePainter.State.Error) failed = true },
            )
        }
    }
}

@Composable
fun BoxScope.TitleFallback(title: String, style: TextStyle = T.H3) {
    Box(
        Modifier.matchParentSize().background(Brush.linearGradient(listOf(C.PanelHi, C.Bg2, C.Panel))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title, style = style, color = C.TextDim, textAlign = TextAlign.Center, maxLines = 4,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(10.dp),
        )
    }
}

@Composable
fun ChannelLogo(url: String, name: String, modifier: Modifier = Modifier, width: Dp = 64.dp, height: Dp = 40.dp) {
    RemoteImage(
        url, modifier.size(width, height), width, height, contentScale = ContentScale.Fit,
        fallback = {
            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Text(
                    name.take(14), style = T.Label, color = C.TextDim, maxLines = 2, textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

// ---------------------------------------------------------------------------------------- focus

/**
 * Focusable elements here never "pin" their item in a lazy list (a focused item, or the one focusRestorer
 * will return to, normally asks its list to keep it composed even when off-screen).
 *
 * Compose up to 1.11 (fixed in 1.12) never unpins an item whose composition is thrown away while pinned:
 * leaving a screen, switching category, or a list's contents changing, with an item focused or remembered.
 * Those stale pins pile up – our lists keep their state between visits – and once two land on the same
 * position the list places that item twice when it scrolls off-screen, crashing the app ("Place was
 * called on a node which was placed already"). Remote navigation always keeps the focused item in view,
 * so pinning buys nothing here. Remove this when moving to Compose 1.12 or later.
 */
@Composable
private fun NoPinning(content: @Composable () -> Unit) =
    CompositionLocalProvider(androidx.compose.ui.layout.LocalPinnableContainer provides null, content = content)

@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    color: Color = Color.Transparent,
    focusedColor: Color = color,
    contentColor: Color = C.Text,
    focusedContentColor: Color = contentColor,
    focusedScale: Float = 1.06f,
    border: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) = NoPinning {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color,
            contentColor = contentColor,
            focusedContainerColor = focusedColor,
            focusedContentColor = focusedContentColor,
            pressedContainerColor = focusedColor,
            pressedContentColor = focusedContentColor,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border = if (border) {
            ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.5.dp, C.Focus), shape = shape))
        } else ClickableSurfaceDefaults.border(),
        glow = ClickableSurfaceDefaults.glow(),
        content = content,
    )
}

/**
 * Lightweight focusable tile (scale + white outline on focus). tv-material's Surface animates colour,
 * border, glow and scale per item, which adds up across a 20-poster grid on a Fire TV stick.
 */
@Composable
fun TvTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(10.dp),
    focusedScale: Float = 1.07f,
    background: Color = C.Panel,
    content: @Composable BoxScope.() -> Unit,
) = NoPinning {
    var focused by remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (focused) focusedScale else 1f, androidx.compose.animation.core.tween(130), label = "tile",
    )
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // Remote "hold OK": Compose's clickable doesn't turn a held DPAD_CENTER into a long click, so do it here.
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.shape = shape
                clip = true
            }
            .background(background)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val select = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!select || onLongClick == null) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            pressed = true
                            longFired = false
                        } else if (pressed && !longFired) {
                            longFired = true
                            onLongClick()
                        }
                    }
                    KeyEventType.KeyUp -> {
                        if (pressed && !longFired) onClick()
                        pressed = false
                        longFired = false
                    }
                }
                true
            }
            .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
            .then(if (focused) Modifier.border(2.5.dp, C.Focus, shape) else Modifier),
        content = content,
    )
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    compact: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.height(if (compact) 34.dp else 40.dp),
        shape = RoundedCornerShape(50),
        color = if (primary) Color(0x40FFFFFF) else C.Glass,
        focusedColor = C.Focus,
        contentColor = C.Text,
        focusedContentColor = C.OnFocus,
        focusedScale = 1.05f,
        border = false,
    ) {
        Row(
            Modifier.fillMaxHeight().padding(horizontal = if (compact) 14.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(if (compact) 16.dp else 18.dp))
                if (text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            }
            if (text.isNotEmpty()) Text(text, style = T.Label, maxLines = 1)
        }
    }
}

/** Request focus once the node is attached and focusable (lazy items and pushed screens may need a few frames). */
@Composable
fun FocusRequester.RequestOnce(key: Any? = Unit, enabled: Boolean = true) {
    LaunchedEffect(key, enabled) {
        if (!enabled) return@LaunchedEffect
        repeat(40) {
            withFrameNanos { }
            if (tryFocus()) return@LaunchedEffect
        }
    }
}

fun FocusRequester.tryFocus(): Boolean = runCatching { requestFocus(FocusDirection.Enter) }.getOrDefault(false)

/**
 * Room that scrolling lists keep around focusable items. A focused card grows ~7% and draws a 2.5 dp
 * outline past its own bounds, and lists clip whatever is drawn beyond their edges along the way they
 * scroll – without this room the outline of a card at either end is cut off.
 */
val FocusRoom = 14.dp

/**
 * Widens a horizontally scrolling list by [room] on both sides without moving its items (give the list a
 * contentPadding of the same [room]), so cards at either end have space for their focus outline while
 * still lining up with the title above.
 */
fun Modifier.bleedHorizontally(room: androidx.compose.ui.unit.Dp = FocusRoom): Modifier =
    layout { measurable, constraints ->
        val extra = room.roundToPx()
        val wide = if (constraints.hasBoundedWidth) {
            constraints.copy(minWidth = constraints.minWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra)
        } else constraints
        val placeable = measurable.measure(wide)
        layout((placeable.width - 2 * extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
    }

/**
 * How far from the edge of a scrolling list a focused element is kept when it's scrolled into view: room
 * for the focus outline (~8 dp past the card) plus the edge of the picture some TVs crop (overscan).
 */
val ScrollMargin = 36.dp

/**
 * Scroll only as far as needed to reveal the focused element (the platform default on phones), keeping
 * [margin] px clear at the edge (see [ScrollMargin]). Compose's TV default pins focus ~30% down the list,
 * which pushes tall headers off-screen.
 */
class MinimalBringIntoView(private val margin: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Elements too big to show with the margin are revealed without it.
        val m = if (size + 2 * margin <= containerSize) margin else 0f
        val leading = offset - m
        val trailing = offset + size + m
        return when {
            leading >= 0 && trailing <= containerSize -> 0f
            leading < 0 && trailing > containerSize -> 0f
            kotlin.math.abs(leading) < kotlin.math.abs(trailing - containerSize) -> leading
            else -> trailing - containerSize
        }
    }
}

@Composable
fun rememberMinimalBringIntoView(): androidx.compose.foundation.gestures.BringIntoViewSpec {
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { ScrollMargin.toPx() }
    return remember(margin) { MinimalBringIntoView(margin) }
}

/** Keep the focused element pinned a fixed distance from the start (rows snap cleanly under a hero). */
class PinBringIntoView(private val pinPx: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - pinPx
}

// ---------------------------------------------------------------------------------------- cards

@Composable
fun PosterCard(
    title: String,
    image: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 112.dp,
    progress: Float = 0f,
    rating: Double = 0.0,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    showTitle: Boolean = false,
) {
    val height = width * 1.5f
    Column(modifier.width(width)) {
        TvTile(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .size(width, height)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { if (it.isFocused) onFocused() },
        ) {
            RemoteImage(tmdbSized(image, "w342"), Modifier.fillMaxSize(), width, height) { TitleFallback(title) }
            if (rating > 0) RatingChip(rating, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (progress > 0f) ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth())
        }
        if (showTitle) {
            Spacer(Modifier.height(7.dp))
            Text(title, style = T.Small, color = C.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun WideCard(
    title: String,
    subtitle: String,
    image: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 216.dp,
    progress: Float = 0f,
    progressColor: Color = C.Accent,
    badge: String? = null,
    imageFit: Boolean = false,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val height = width * 9f / 16f
    Column(modifier.width(width)) {
        TvTile(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier
                .size(width, height)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { if (it.isFocused) onFocused() },
            focusedScale = 1.06f,
        ) {
            if (imageFit) {
                Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(C.PanelHi, C.Panel))))
                RemoteImage(image, Modifier.fillMaxSize().padding(22.dp), width, height, ContentScale.Fit) {
                    TitleFallback(title, T.H3)
                }
            } else {
                RemoteImage(tmdbSized(image, "w500"), Modifier.fillMaxSize(), width, height) { TitleFallback(title, T.H3) }
            }
            if (badge != null) Badge(badge, Modifier.align(Alignment.TopStart).padding(8.dp))
            if (progress > 0f) {
                ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(10.dp).fillMaxWidth(), color = progressColor)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(title, style = T.BodyStrong, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotEmpty()) Text(subtitle, style = T.Small, color = C.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------- bits

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = C.Live, textColor: Color = Color.White) {
    Box(modifier.background(color, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text.uppercase(), style = T.Tiny, color = textColor, maxLines = 1)
    }
}

@Composable
fun RatingChip(rating: Double, modifier: Modifier = Modifier) {
    val text = remember(rating) {
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = C.Gold)); append("★ "); pop()
            append("%.1f".format(rating))
        }
    }
    Text(
        text, style = T.Tiny.copy(fontSize = 10.sp), color = Color.White, maxLines = 1,
        modifier = modifier.background(Color(0xCC000000), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = C.Accent, height: Dp = 3.dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(2.dp)).background(Color(0x40FFFFFF))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = T.H3, color = C.Text, modifier = modifier)
}

/** The Nimbus logo: the cloud mark (res/drawable/nimbus_mark, from tools/make_brand.py) and the wordmark. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: TextStyle = T.H2.copy(fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)) {
    val markHeight = with(androidx.compose.ui.platform.LocalDensity.current) { (size.fontSize * 0.92f).toDp() }
    Row(modifier) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(io.github.lucasclgibson.nimbus.R.drawable.nimbus_mark),
            contentDescription = null,
            // The cloud's base sits on the wordmark's baseline, like the launch screen and the Fire TV tile.
            modifier = Modifier.height(markHeight).alignBy { (it.measuredHeight * 0.96f).toInt() },
        )
        Spacer(Modifier.width(markHeight * 0.32f))
        Text(
            "nimbus",
            style = size.copy(brush = Brushes.Brand, letterSpacing = (-0.015).em),
            modifier = Modifier.alignBy(androidx.compose.ui.layout.FirstBaseline),
        )
    }
}

@Composable
fun Clock(modifier: Modifier = Modifier, style: TextStyle = T.H3) {
    val time by produceState(timeOf(now())) {
        while (true) {
            value = timeOf(now())
            delay(60_000 - now() % 60_000 + 50)
        }
    }
    Text(time, style = style, color = C.Text, modifier = modifier)
}

@Composable
fun MetaLine(parts: List<String>, modifier: Modifier = Modifier, style: TextStyle = T.BodyStrong, color: Color = C.TextDim) {
    val items = parts.filter { it.isNotBlank() }
    if (items.isEmpty()) return
    Text(items.joinToString("  ·  "), style = style, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.widthIn(max = 520.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, style = T.H2, color = C.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, style = T.Body, color = C.TextDim, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).background(color, CircleShape))
}
