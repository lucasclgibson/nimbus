package io.github.lucasclgibson.nimbus.ui.detail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import io.github.lucasclgibson.nimbus.AppGraph
import io.github.lucasclgibson.nimbus.core.clockLabel
import io.github.lucasclgibson.nimbus.core.cleanTitle
import io.github.lucasclgibson.nimbus.core.durationLabel
import io.github.lucasclgibson.nimbus.data.db.HistoryEntity
import io.github.lucasclgibson.nimbus.data.db.Kind
import io.github.lucasclgibson.nimbus.data.repo.Episode
import io.github.lucasclgibson.nimbus.data.repo.LibraryRepository
import io.github.lucasclgibson.nimbus.data.repo.VodDetails
import io.github.lucasclgibson.nimbus.data.source.CastMember
import io.github.lucasclgibson.nimbus.data.source.Review
import io.github.lucasclgibson.nimbus.player.VodItem
import io.github.lucasclgibson.nimbus.ui.components.Badge
import io.github.lucasclgibson.nimbus.ui.components.FocusSurface
import io.github.lucasclgibson.nimbus.ui.components.MetaLine
import io.github.lucasclgibson.nimbus.ui.components.PillButton
import io.github.lucasclgibson.nimbus.ui.components.PosterCard
import io.github.lucasclgibson.nimbus.ui.components.RemoteImage
import io.github.lucasclgibson.nimbus.ui.components.RequestOnce
import io.github.lucasclgibson.nimbus.ui.components.SectionTitle
import io.github.lucasclgibson.nimbus.ui.components.SetBackdrop
import io.github.lucasclgibson.nimbus.ui.components.WideCard
import io.github.lucasclgibson.nimbus.ui.components.FocusRoom
import io.github.lucasclgibson.nimbus.ui.components.bleedHorizontally
import io.github.lucasclgibson.nimbus.ui.components.rememberMinimalBringIntoView
import io.github.lucasclgibson.nimbus.ui.nav.Dest
import io.github.lucasclgibson.nimbus.ui.nav.LocalApp
import io.github.lucasclgibson.nimbus.ui.nav.LocalNav
import io.github.lucasclgibson.nimbus.ui.nav.Navigator
import io.github.lucasclgibson.nimbus.ui.nav.entryState
import io.github.lucasclgibson.nimbus.ui.theme.C
import io.github.lucasclgibson.nimbus.ui.theme.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class Similar(val key: String, val title: String, val poster: String, val rating: Double)

class DetailState(private val app: AppGraph, scope: CoroutineScope, val key: String, val series: Boolean) {
    var details by mutableStateOf<VodDetails?>(null)
    var similar by mutableStateOf<List<Similar>>(emptyList())
    var season by mutableIntStateOf(-1)
    var versions by mutableStateOf<List<io.github.lucasclgibson.nimbus.data.repo.Version>>(emptyList())
    val list = LazyListState()

    init {
        scope.launch {
            val flow = if (series) app.vod.seriesDetails(key) else app.vod.movieDetails(key)
            flow.collect { details = it }
        }
        scope.launch { versions = app.search.versions(if (series) Kind.SERIES else Kind.MOVIE, key) }
        scope.launch {
            similar = if (series) {
                val s = app.vod.seriesItem(key)
                if (s == null) emptyList() else app.db.series().inCategory(s.playlistId, s.categoryId)
                    .filter { it.key != key }.shuffled().take(24).map { Similar(it.key, cleanTitle(it.name), it.poster, it.rating) }
            } else {
                val m = app.vod.movie(key)
                if (m == null) emptyList() else app.db.movies().inCategory(m.playlistId, m.categoryId)
                    .filter { it.key != key }.shuffled().take(24).map { Similar(it.key, cleanTitle(it.name), it.poster, it.rating) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- film

@Composable
fun MovieDetailScreen(key: String) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val state = entryState { DetailState(app, it, key, series = false) }
    val d = state.details
    val historyKey = LibraryRepository.historyKey(Kind.MOVIE, key)
    val history by app.library.observe(historyKey).collectAsState(null)
    val favs by app.library.favourites.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SetBackdrop(d?.backdrop?.ifBlank { d.poster })
    val playFocus = remember { FocusRequester() }

    fun play(fromStart: Boolean) {
        val dd = d ?: return
        val resume = history?.takeIf { !it.finished && !fromStart }?.positionMs ?: 0
        app.playback.playVod(
            VodItem(
                historyKey = historyKey, kind = Kind.MOVIE, itemKey = key, parentKey = "",
                title = dd.title, subtitle = listOf(dd.year.takeIf { it > 0 }?.toString().orEmpty(), dd.genres).filter { it.isNotBlank() }.joinToString(" · "),
                url = dd.streamUrl, image = dd.poster, backdrop = dd.backdrop, userAgent = dd.userAgent, startPositionMs = resume,
            )
        )
        nav.push(Dest.Player)
    }

    DetailScaffold(state, d) {
        val h = history
        val canResume = h != null && !h.finished && h.positionMs > 20_000
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(
                if (canResume) "Resume  ${clockLabel(h!!.positionMs)}" else "Play",
                { play(false) }, icon = Icons.Rounded.PlayArrow, primary = true,
                modifier = Modifier.focusRequester(playFocus),
            )
            if (canResume) PillButton("Start over", { play(true) }, icon = Icons.Rounded.Replay)
            val fav = "${Kind.MOVIE}:$key" in favs
            PillButton(if (fav) "In My list" else "My list", { scope.launch { app.library.toggleFavourite(Kind.MOVIE, key) } },
                icon = if (fav) Icons.Rounded.Check else Icons.Rounded.Add)
            if (d?.trailerKey?.isNotBlank() == true) PillButton("Trailer", { openTrailer(context, d.trailerKey) }, icon = Icons.Rounded.SmartDisplay)
        }
        if (canResume && h!!.durationMs > 0) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                io.github.lucasclgibson.nimbus.ui.components.ProgressLine(h.positionMs.toFloat() / h.durationMs, Modifier.width(220.dp))
                Spacer(Modifier.width(10.dp))
                Text("${clockLabel(h.durationMs - h.positionMs)} left", style = T.Small, color = C.TextDim)
            }
        }
    }
    playFocus.RequestOnce(d != null, enabled = d != null)
}

// ---------------------------------------------------------------------------------------- series

@Composable
fun SeriesDetailScreen(key: String) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val state = entryState { DetailState(app, it, key, series = true) }
    val d = state.details
    val history by app.library.observeSeries(key).collectAsState(emptyList())
    val favs by app.library.favourites.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    SetBackdrop(d?.backdrop?.ifBlank { d.poster })
    val playFocus = remember { FocusRequester() }
    val byEpisode = remember(history) { history.associateBy { it.itemKey } }
    val all = remember(d?.seasons) { d?.seasons?.flatMap { it.episodes }.orEmpty() }

    // Up next: resume the last episode watched, or the one after it, or the first.
    val upNext: Pair<Episode, Long>? = remember(all, history) {
        val last = history.maxByOrNull { it.updatedAt }
        val idx = all.indexOfFirst { it.key == last?.itemKey }
        when {
            all.isEmpty() -> null
            last == null || idx < 0 -> all.first() to 0L
            !last.finished -> all[idx] to last.positionMs
            idx + 1 < all.size -> all[idx + 1] to 0L
            else -> all.first() to 0L
        }
    }

    fun play(ep: Episode, from: Long) {
        val dd = d ?: return
        var next: VodItem? = null
        val chain = all.asReversed().map { e ->
            VodItem(
                historyKey = LibraryRepository.historyKey(Kind.EPISODE, e.key), kind = Kind.EPISODE, itemKey = e.key, parentKey = key,
                title = dd.title, subtitle = "S${e.season} E${e.number} · ${e.title}", url = e.url,
                image = e.image.ifBlank { dd.poster }, backdrop = dd.backdrop.ifBlank { e.image }, userAgent = dd.userAgent,
                season = e.season, episode = e.number, next = next,
            ).also { next = it }
        }
        val item = chain.firstOrNull { it.itemKey == ep.key } ?: return
        app.playback.playVod(item.copy(startPositionMs = from))
        nav.push(Dest.Player)
    }

    DetailScaffold(state, d, extra = {
        if (d != null && d.seasons.isNotEmpty()) {
            item(key = "episodes") {
                SeasonsAndEpisodes(state, d, byEpisode, upNext?.first?.key) { ep ->
                    val h = byEpisode[ep.key]
                    play(ep, h?.takeIf { !it.finished }?.positionMs ?: 0)
                }
            }
        } else if (d != null && !d.loading) {
            item(key = "no-episodes") {
                Text("No episodes available yet.", style = T.Body, color = C.TextDim, modifier = Modifier.padding(start = 48.dp, top = 8.dp))
            }
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val un = upNext
            val label = when {
                un == null -> if (d?.loading != false) "Loading episodes…" else "Play"
                un.second > 20_000 -> "Resume S${un.first.season} E${un.first.number}"
                history.isNotEmpty() -> "Play S${un.first.season} E${un.first.number}"
                else -> "Play S${un.first.season} E${un.first.number}"
            }
            PillButton(label, { un?.let { play(it.first, it.second) } }, icon = Icons.Rounded.PlayArrow, primary = true,
                modifier = Modifier.focusRequester(playFocus))
            val fav = "${Kind.SERIES}:$key" in favs
            PillButton(if (fav) "In My list" else "My list", { scope.launch { app.library.toggleFavourite(Kind.SERIES, key) } },
                icon = if (fav) Icons.Rounded.Check else Icons.Rounded.Add)
            if (d?.trailerKey?.isNotBlank() == true) PillButton("Trailer", { openTrailer(context, d.trailerKey) }, icon = Icons.Rounded.SmartDisplay)
        }
    }
    playFocus.RequestOnce(d != null, enabled = d != null)
}

@Composable
private fun SeasonsAndEpisodes(
    state: DetailState,
    d: VodDetails,
    history: Map<String, HistoryEntity>,
    upNextKey: String?,
    onPlay: (Episode) -> Unit,
) {
    val initialSeason = remember(d.seasons, upNextKey) {
        d.seasons.indexOfFirst { s -> s.episodes.any { it.key == upNextKey } }.coerceAtLeast(0)
    }
    if (state.season < 0 || state.season >= d.seasons.size) state.season = initialSeason
    val season = d.seasons[state.season]
    val episodeList = rememberLazyListState()
    Column {
        if (d.seasons.size > 1) {
            LazyRow(
                modifier = Modifier.focusRestorer(),
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(d.seasons, key = { _, s -> s.number }) { i, s ->
                    val selected = i == state.season
                    FocusSurface(
                        onClick = { state.season = i },
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(50),
                        color = if (selected) C.GlassHi else C.Glass,
                        focusedColor = C.Focus,
                        contentColor = if (selected) C.Text else C.TextDim,
                        focusedContentColor = C.OnFocus,
                        focusedScale = 1.05f,
                        border = false,
                    ) {
                        Box(Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text(s.name, style = T.Label)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        } else {
            SectionTitle(season.name, Modifier.padding(start = 48.dp, bottom = 10.dp))
        }
        LaunchedEffect(state.season) {
            val idx = season.episodes.indexOfFirst { it.key == upNextKey }
            episodeList.scrollToItem((if (idx >= 0) idx else 0))
        }
        LazyRow(
            state = episodeList,
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(season.episodes, key = { it.key }) { ep ->
                val h = history[ep.key]
                val progress = when {
                    h == null -> 0f
                    h.finished -> 1f
                    h.durationMs > 0 -> h.positionMs.toFloat() / h.durationMs
                    else -> 0f
                }
                WideCard(
                    title = "${ep.number}. ${ep.title}",
                    subtitle = listOf(durationLabel(ep.durationMs), if (h?.finished == true) "Watched" else "", ep.airDate.take(10))
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    image = ep.image.ifBlank { d.backdrop.ifBlank { d.poster } },
                    width = 232.dp,
                    progress = progress,
                    badge = if (ep.key == upNextKey) "Up next" else null,
                    onClick = { onPlay(ep) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- shared

@Composable
private fun DetailScaffold(
    state: DetailState,
    d: VodDetails?,
    extra: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
    buttons: @Composable () -> Unit,
) {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var openReview by remember { mutableStateOf<Review?>(null) }
    CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rememberMinimalBringIntoView()) {
    LazyColumn(
        state = state.list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
    ) {
        item(key = "header") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 330.dp)
                    // Whenever focus is in the header (Play, My list…) show the whole header.
                    .onFocusChanged { if (it.hasFocus) scope.launch { state.list.animateScrollToItem(0) } }
                    .padding(start = 48.dp, top = 44.dp, end = 300.dp)
            ) {
                if (d == null) return@Column
                Text(d.title, style = T.Display, color = C.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d.rating > 0) {
                        Text("★ %.1f".format(d.rating), style = T.H3, color = C.Gold)
                        if (d.voteCount > 0) Text("  (${d.voteCount} votes, ${d.ratingSource})", style = T.Small, color = C.TextMute)
                        Spacer(Modifier.width(14.dp))
                    }
                    if (d.certification.isNotBlank()) {
                        Badge(d.certification, color = Color(0x33FFFFFF))
                        Spacer(Modifier.width(12.dp))
                    }
                    MetaLine(
                        listOf(
                            d.year.takeIf { it > 0 }?.toString().orEmpty(),
                            if (d.runtimeMin > 0) durationLabel(d.runtimeMin * 60_000L) else "",
                            if (d.seasons.isNotEmpty()) "${d.seasons.size} season${if (d.seasons.size == 1) "" else "s"}" else "",
                            d.genres,
                        )
                    )
                }
                if (d.tagline.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(d.tagline, style = T.H3.copy(fontStyle = FontStyle.Italic), color = C.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                Text(d.plot.ifBlank { if (d.loading) "" else "No description available." }, style = T.Body, color = C.TextDim, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                if (d.directors.isNotBlank()) Text("Directed by ${d.directors}", style = T.Small, color = C.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val cast = d.cast.take(5).joinToString(", ") { it.name }.ifBlank { d.castText }
                if (cast.isNotBlank()) Text("Starring $cast", style = T.Small, color = C.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(22.dp))
                buttons()
                if (state.versions.size > 1) {
                    Spacer(Modifier.height(16.dp))
                    VersionsRow(state.versions) { v ->
                        if (!v.current) nav.replaceTop(if (state.series) Dest.SeriesDetail(v.key) else Dest.MovieDetail(v.key))
                    }
                }
            }
        }
        extra()
        if (d != null && d.cast.isNotEmpty()) item(key = "cast") { CastRow(d.cast) }
        if (d != null && d.reviews.isNotEmpty()) item(key = "reviews") { ReviewsRow(d.reviews) { openReview = it } }
        if (state.similar.isNotEmpty()) {
            item(key = "similar") {
                Column {
                    SectionTitle("More like this", Modifier.padding(start = 48.dp, bottom = 10.dp))
                    LazyRow(
                        modifier = Modifier.focusRestorer(),
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(state.similar.distinctBy { it.key }, key = { it.key }) { s ->
                            PosterCard(
                                s.title, s.poster, rating = s.rating, width = 104.dp,
                                onClick = { nav.push(if (state.series) Dest.SeriesDetail(s.key) else Dest.MovieDetail(s.key)) },
                            )
                        }
                    }
                }
            }
        }
    }
    }
    openReview?.let { r -> ReviewDialog(r) { openReview = null } }
}

/** The same title in other qualities/languages from the provider – pick one to play. */
@Composable
private fun VersionsRow(versions: List<io.github.lucasclgibson.nimbus.data.repo.Version>, onPick: (io.github.lucasclgibson.nimbus.data.repo.Version) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Versions", style = T.Label, color = C.TextDim)
        Spacer(Modifier.width(12.dp))
        LazyRow(
            modifier = Modifier.bleedHorizontally().focusRestorer(),
            contentPadding = PaddingValues(horizontal = FocusRoom),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(versions, key = { it.key }) { v ->
                FocusSurface(
                    onClick = { onPick(v) },
                    modifier = Modifier.height(44.dp).widthIn(max = 230.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = if (v.current) C.GlassHi else C.Glass,
                    focusedColor = C.Focus,
                    contentColor = C.Text,
                    focusedContentColor = C.OnFocus,
                    focusedScale = 1.05f,
                    border = false,
                ) {
                    Column(Modifier.padding(horizontal = 12.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                        Text((if (v.current) "✓ " else "") + v.label, style = T.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (v.category.isNotEmpty()) Text(v.category, style = T.Tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun CastRow(cast: List<CastMember>) {
    Column {
        SectionTitle("Cast", Modifier.padding(start = 48.dp, bottom = 10.dp))
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(cast, key = { i, c -> "$i:${c.name}" }) { _, c ->
                Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    FocusSurface(onClick = {}, modifier = Modifier.size(78.dp), shape = CircleShape, color = C.Panel, focusedScale = 1.08f) {
                        RemoteImage(c.photo, Modifier.fillMaxSize().clip(CircleShape), 78.dp, 78.dp) {
                            Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                                Text(c.name.split(' ').mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), style = T.H2, color = C.TextDim)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(c.name, style = T.Small, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(c.role, style = T.Small, color = C.TextMute, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ReviewsRow(reviews: List<Review>, onOpen: (Review) -> Unit) {
    Column {
        SectionTitle("Reviews", Modifier.padding(start = 48.dp, bottom = 10.dp))
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(reviews, key = { i, r -> "$i:${r.author}" }) { _, r ->
                FocusSurface(
                    onClick = { onOpen(r) },
                    modifier = Modifier.size(330.dp, 150.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = C.Panel,
                    focusedColor = C.PanelHi,
                    focusedScale = 1.04f,
                ) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.author, style = T.Label, color = C.Text, maxLines = 1, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
                            if (r.rating > 0) Text("★ %.0f/10".format(r.rating), style = T.Label, color = C.Gold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(r.content.replace(Regex("\\s+"), " "), style = T.Body, color = C.TextDim, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewDialog(r: Review, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val focus = remember { FocusRequester() }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().background(Color(0xCC02040A)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(640.dp).heightIn(max = 440.dp).background(C.Panel, RoundedCornerShape(16.dp)).padding(28.dp)
            ) {
                Text(r.author, style = T.H2, color = C.Text)
                if (r.rating > 0) Text("★ %.0f/10  ·  ${r.date}".format(r.rating), style = T.Label, color = C.Gold)
                Spacer(Modifier.height(14.dp))
                FocusSurface(onClick = onDismiss, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).focusRequester(focus), color = Color.Transparent, focusedScale = 1f, border = false) {
                    Text(r.content, style = T.Body, color = C.TextDim, modifier = Modifier.verticalScroll(scroll))
                }
            }
        }
        focus.RequestOnce()
    }
}

private fun openTrailer(context: Context, key: String) {
    val intents = listOf(
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")).setPackage("com.amazon.firetv.youtube"),
        Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$key")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key")),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}
