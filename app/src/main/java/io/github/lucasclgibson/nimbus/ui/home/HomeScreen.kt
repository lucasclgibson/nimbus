package io.github.lucasclgibson.nimbus.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.lucasclgibson.nimbus.AppGraph
import io.github.lucasclgibson.nimbus.core.cleanTitle
import io.github.lucasclgibson.nimbus.core.displayChannelName
import io.github.lucasclgibson.nimbus.core.isSeparatorChannel
import io.github.lucasclgibson.nimbus.core.clockLabel
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.core.timeOf
import io.github.lucasclgibson.nimbus.data.db.ChannelEntity
import io.github.lucasclgibson.nimbus.data.db.HistoryEntity
import io.github.lucasclgibson.nimbus.data.db.Kind
import io.github.lucasclgibson.nimbus.data.db.MovieEntity
import io.github.lucasclgibson.nimbus.data.db.PlaylistType
import io.github.lucasclgibson.nimbus.data.db.SeriesEntity
import io.github.lucasclgibson.nimbus.data.repo.NowNext
import io.github.lucasclgibson.nimbus.player.Session
import io.github.lucasclgibson.nimbus.player.VodItem
import io.github.lucasclgibson.nimbus.ui.components.BrandMark
import io.github.lucasclgibson.nimbus.ui.components.HeroImage
import io.github.lucasclgibson.nimbus.ui.components.MetaLine
import io.github.lucasclgibson.nimbus.ui.components.PillButton
import io.github.lucasclgibson.nimbus.ui.components.PinBringIntoView
import io.github.lucasclgibson.nimbus.ui.components.PosterCard
import io.github.lucasclgibson.nimbus.ui.components.RequestOnce
import io.github.lucasclgibson.nimbus.ui.components.tryFocus
import io.github.lucasclgibson.nimbus.ui.components.SectionTitle
import io.github.lucasclgibson.nimbus.ui.components.SetBackdrop
import io.github.lucasclgibson.nimbus.ui.components.TopNav
import io.github.lucasclgibson.nimbus.ui.components.VideoSlot
import io.github.lucasclgibson.nimbus.ui.components.WideCard
import io.github.lucasclgibson.nimbus.ui.nav.Dest
import io.github.lucasclgibson.nimbus.ui.nav.LocalApp
import io.github.lucasclgibson.nimbus.ui.nav.LocalNav
import io.github.lucasclgibson.nimbus.ui.nav.Navigator
import io.github.lucasclgibson.nimbus.ui.nav.entryState
import io.github.lucasclgibson.nimbus.ui.theme.C
import io.github.lucasclgibson.nimbus.ui.theme.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Info
import io.github.lucasclgibson.nimbus.ui.components.MenuItem
import io.github.lucasclgibson.nimbus.ui.components.OptionsMenu

data class Hero(
    val title: String,
    val meta: List<String> = emptyList(),
    val body: String = "",
    val backdrop: String = "",
    val tag: String = "",
    /** A quiet line under the text (what holding OK does). */
    val hint: String = "",
)

sealed interface HomeRow {
    val id: String
    val title: String

    data class Resume(override val id: String, override val title: String, val items: List<HistoryEntity>) : HomeRow
    data class Channels(override val id: String, override val title: String, val items: List<ChannelEntity>, val nowNext: Map<String, NowNext>) : HomeRow
    data class Movies(override val id: String, override val title: String, val items: List<MovieEntity>) : HomeRow
    data class Shows(override val id: String, override val title: String, val items: List<SeriesEntity>) : HomeRow
    data class Nudge(override val id: String, override val title: String, val message: String) : HomeRow
    data class Update(override val id: String, override val title: String, val info: io.github.lucasclgibson.nimbus.data.hub.UpdateInfo) : HomeRow
    data class LiveNow(override val id: String, override val title: String, val items: List<io.github.lucasclgibson.nimbus.data.repo.OnTv>) : HomeRow
}

class HomeState(private val app: AppGraph, private val scope: CoroutineScope) {
    /** Key of the card that should take focus after a removal re-composes the row. */
    var pendingFocus by mutableStateOf<String?>(null)
    var rows by mutableStateOf<List<HomeRow>>(emptyList())
    var hero by mutableStateOf<Hero?>(null)
    var loaded by mutableStateOf(false)
    val listState = LazyListState()
    private var heroJob: Job? = null
    private var base: List<HomeRow> = emptyList()
    private var resume: List<HistoryEntity> = emptyList()
    private var channelRows: List<HomeRow> = emptyList()

    init {
        scope.launch {
            app.sync.contentVersion.collect { loadContent() }
        }
        scope.launch {
            combine(app.library.continueWatching, app.library.favourites, app.library.recentChannels) { a, _, _ -> a }
                .collect { cw ->
                    resume = cw
                    // Back from the player: the hero still shows the old "time left".
                    heroResume?.let { k -> cw.firstOrNull { it.key == k } }?.let { h -> hero = hero?.copy(meta = resumeMeta(h)) }
                    loadChannels()
                }
        }
        scope.launch {
            while (true) {
                delay(60_000)
                loadChannels()
            }
        }
        scope.launch { app.updater.available.collect { update = it } }
    }

    private var nudge: HomeRow? = null

    private suspend fun loadContent() {
        app.store.ready()
        val out = ArrayList<HomeRow>()
        val cats = app.db.categories().all(Kind.LIVE)
        nudge = if (!app.prefs.groupsReviewed.value && cats.size > 25 && cats.count { !it.hidden } > cats.size * 0.6) {
            HomeRow.Nudge(
                "nudge", "Make it yours",
                "Your provider has ${cats.size} channel groups, many in other languages. Pick the ones you watch – " +
                    "the guide gets faster and Home shows what you care about.",
            )
        } else null
        app.vod.recentMovies(30).takeIf { it.isNotEmpty() }?.let { out += HomeRow.Movies("rm", "New films", it) }
        app.vod.recentSeries(30).takeIf { it.isNotEmpty() }?.let { out += HomeRow.Shows("rs", "New TV shows", it) }
        app.vod.topMovies(30).takeIf { it.size >= 6 }?.let { out += HomeRow.Movies("tm", "Top rated films", it) }
        app.vod.topSeries(30).takeIf { it.size >= 6 }?.let { out += HomeRow.Shows("ts", "Top rated shows", it) }
        base = out
        publish()
        loaded = true
        if (hero == null) {
            val m = (out.firstOrNull() as? HomeRow.Movies)?.items?.firstOrNull()
            if (m != null) focusMovie(m) else (out.firstOrNull() as? HomeRow.Shows)?.items?.firstOrNull()?.let { focusSeries(it) }
        }
    }

    private suspend fun loadChannels() {
        val favs = app.db.channels().favourites().filterNot { isSeparatorChannel(it.name) }
        val recentKeys = app.library.recentChannels.value.map { it.itemKey }
        val recent = if (recentKeys.isEmpty()) emptyList() else {
            val byKey = app.db.channels().getMany(recentKeys).associateBy { it.key }
            recentKeys.mapNotNull { byKey[it] }.filterNot { isSeparatorChannel(it.name) }
        }
        val nn = app.live.nowNext((favs + recent).distinctBy { it.key })
        val out = ArrayList<HomeRow>()
        val sport = runCatching { app.search.liveSport(app.store.all.value) }.getOrDefault(emptyList())
        if (sport.isNotEmpty()) out += HomeRow.LiveNow("sport", "Live sport now", sport)
        if (favs.isNotEmpty()) out += HomeRow.Channels("fav", "Favourite channels", favs, nn)
        if (recent.isNotEmpty()) out += HomeRow.Channels("recent", "Recently watched channels", recent.take(15), nn)
        channelRows = out
        publish()
    }

    var update: io.github.lucasclgibson.nimbus.data.hub.UpdateInfo? = null
        set(value) {
            field = value
            publish()
        }

    private fun publish() {
        val list = ArrayList<HomeRow>()
        update?.let { list += HomeRow.Update("update", "Update ready", it) }
        nudge?.let { list += it }
        if (resume.isNotEmpty()) list += HomeRow.Resume("cw", "Continue watching", resume)
        list += channelRows
        list += base
        rows = list
    }

    // ------------------------------------------------------------------ hero

    private fun setHero(delayMs: Long, block: suspend () -> Unit) {
        heroResume = null
        heroJob?.cancel()
        heroJob = scope.launch {
            delay(delayMs)
            block()
        }
    }

    fun focusMovie(m: MovieEntity) {
        setHero(110) {
            hero = Hero(cleanTitle(m.name), listOf(m.year.takeIf { it > 0 }?.toString().orEmpty(), rating(m.rating)), "", hero?.backdrop.orEmpty(), "Film")
            delay(340)
            app.vod.movieDetails(m.key).collect { d ->
                hero = Hero(
                    d.title,
                    listOf(d.year.takeIf { it > 0 }?.toString().orEmpty(), d.genres.split(',').take(2).joinToString(", ").trim(), rating(d.rating),
                        d.runtimeMin.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" }.orEmpty()),
                    d.plot, d.backdrop.ifBlank { d.poster }, "Film",
                )
            }
        }
    }

    fun focusSeries(s: SeriesEntity) {
        setHero(110) {
            hero = Hero(cleanTitle(s.name), listOf(s.year.takeIf { it > 0 }?.toString().orEmpty(), s.genre, rating(s.rating)), s.plot, s.backdrop.ifBlank { hero?.backdrop.orEmpty() }, "TV show")
            if (s.backdrop.isBlank()) {
                delay(340)
                app.vod.seriesDetails(s.key).collect { d ->
                    hero = hero?.copy(backdrop = d.backdrop.ifBlank { d.poster }, body = d.plot.ifBlank { s.plot })
                }
            }
        }
    }

    fun focusChannel(c: ChannelEntity, nn: NowNext?) = setHero(110) {
        val p = nn?.now
        hero = Hero(
            p?.title ?: displayChannelName(c.name),
            listOf("${c.number}  ${displayChannelName(c.name)}", p?.let { "${timeOf(it.startMs)} – ${timeOf(it.endMs)}" }.orEmpty(),
                nn?.next?.let { "Next: ${it.title}" }.orEmpty()),
            "", "", "Live TV",
        )
        if (p != null) {
            delay(190)
            val d = app.live.programme(p.id)
            if (d != null) hero = hero?.copy(body = listOf(d.subtitle, d.description).filter { it.isNotBlank() }.joinToString(" – "))
        }
    }

    /** History item shown in the hero, if any. */
    private var heroResume: String? = null

    private fun resumeMeta(h: HistoryEntity): List<String> {
        val left = (h.durationMs - h.positionMs).coerceAtLeast(0)
        return listOf(h.subtitle, if (h.durationMs > 0) "${clockLabel(left)} left" else "")
    }

    fun focusResume(h: HistoryEntity) = setHero(110) {
        heroResume = h.key
        hero = Hero(
            cleanTitle(h.title), resumeMeta(h),
            "", h.backdrop.ifBlank { h.image }, if (h.kind == Kind.EPISODE) "TV show" else "Film",
            hint = if (h.kind == Kind.EPISODE) "Hold OK to remove from Continue watching" else "Hold OK to remove from Continue watching or start again",
        )
        delay(340)
        run {
            val flow = if (h.kind == Kind.EPISODE) app.vod.seriesDetails(h.parentKey) else app.vod.movieDetails(h.itemKey)
            flow.collect { d ->
                val episodePlot = d.seasons.firstNotNullOfOrNull { s -> s.episodes.firstOrNull { it.key == h.itemKey } }?.plot
                hero = hero?.copy(
                    body = episodePlot?.ifBlank { null } ?: d.plot,
                    backdrop = d.backdrop.ifBlank { hero?.backdrop.orEmpty() },
                )
            }
        }
    }

    private fun rating(r: Double) = if (r > 0) "★ %.1f".format(r) else ""

    // ------------------------------------------------------------------ actions

    fun resume(h: HistoryEntity, nav: Navigator, fromStart: Boolean = false) {
        scope.launch {
            if (h.kind == Kind.MOVIE) {
                val m = app.vod.movie(h.itemKey) ?: return@launch nav.push(Dest.MovieDetail(h.itemKey))
                app.playback.playVod(
                    VodItem(h.key, Kind.MOVIE, m.key, "", cleanTitle(h.title), h.subtitle, app.vod.movieUrl(m), h.image, h.backdrop,
                        app.store.userAgent(m.playlistId), startPositionMs = if (fromStart) 0 else h.positionMs)
                )
                nav.push(Dest.Player)
            } else {
                nav.push(Dest.SeriesDetail(h.parentKey))
            }
        }
    }

    /** Continue watching card whose options (hold OK) are open. */
    var resumeOptions by mutableStateOf<HistoryEntity?>(null)
}

@Composable
fun HomeScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val playlists by app.store.all.collectAsState()
    var storeReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        app.store.ready()
        storeReady = true
    }
    if (!storeReady) return
    if (playlists.isEmpty()) {
        WelcomeScreen()
        return
    }
    val state = entryState { HomeState(app, it) }
    val session by app.playback.session.collectAsState()
    val liveSession = session as? Session.Live
    val hero = state.hero
    SetBackdrop(null)

    Box(Modifier.fillMaxSize()) {
    if (liveSession == null) {
        HeroImage(hero?.backdrop, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.64f).height(330.dp))
    }
    Column(Modifier.fillMaxSize()) {
        TopNav(Dest.Home)
        Row(Modifier.fillMaxWidth().height(206.dp).padding(start = 48.dp, end = 48.dp, top = 10.dp)) {
            Column(Modifier.weight(1f)) {
                if (hero != null) {
                    if (hero.tag.isNotEmpty()) Text(hero.tag.uppercase(), style = T.Tiny, color = C.Accent)
                    Spacer(Modifier.height(4.dp))
                    Text(hero.title, style = T.Display, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    MetaLine(hero.meta)
                    Spacer(Modifier.height(10.dp))
                    Text(hero.body, style = T.Body, color = C.TextDim, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(520.dp))
                    if (hero.hint.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(hero.hint, style = T.Small, color = C.TextMute)
                    }
                }
            }
            if (liveSession != null) {
                Spacer(Modifier.width(24.dp))
                Column(horizontalAlignment = Alignment.End) {
                    // Transparent on purpose: the video surface shows through from behind the UI.
                    Box(Modifier.size(272.dp, 153.dp)) {
                        VideoSlot(Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${liveSession.channel.number}  ${liveSession.channel.name}", style = T.Small, color = C.TextDim, maxLines = 1)
                }
            }
        }
        val first = remember { FocusRequester() }
        // Rows snap so the focused row's title sits at the top of the list; rows themselves keep the TV default.
        val rowSpec = androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current
        val pin = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(pin) { PinBringIntoView(pin) },
        ) {
            LazyColumn(
                state = state.listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 400.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(state.rows, key = { it.id }) { row ->
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides rowSpec,
                    ) {
                        HomeRowView(row, state, nav, if (row === state.rows.firstOrNull()) first else null)
                    }
                }
            }
        }
        first.RequestOnce(state.rows.isNotEmpty(), enabled = state.rows.isNotEmpty())
    }
    }
    state.resumeOptions?.let { h -> ResumeOptions(h, state, nav) }
}

/** Hold OK on a Continue watching card. */
@Composable
private fun ResumeOptions(h: HistoryEntity, state: HomeState, nav: Navigator) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val episode = h.kind == Kind.EPISODE
    val items = buildList {
        add(MenuItem("Remove from Continue watching", if (episode) "Hides the whole show until you watch it again" else "",
            Icons.Rounded.RemoveCircleOutline) {
            // Focus moves to the neighbour, not off to the menu bar.
            val row = state.rows.filterIsInstance<HomeRow.Resume>().firstOrNull()?.items.orEmpty()
            val i = row.indexOfFirst { it.key == h.key }
            val neighbour = row.getOrNull(i + 1) ?: row.getOrNull(i - 1)
            state.pendingFocus = neighbour?.key
            neighbour?.let { state.focusResume(it) }
            scope.launch { app.library.removeFromContinueWatching(h) }
        })
        if (episode) {
            add(MenuItem("Go to the show", "Episodes and seasons", Icons.Rounded.ViewList) { nav.push(Dest.SeriesDetail(h.parentKey)) })
        } else {
            add(MenuItem("Start again", "Play from the beginning", Icons.Rounded.Replay) { state.resume(h, nav, fromStart = true) })
            add(MenuItem("Film details", "", Icons.Rounded.Info) { nav.push(Dest.MovieDetail(h.itemKey)) })
        }
    }
    OptionsMenu(title = cleanTitle(h.title), subtitle = h.subtitle, items = items, onDismiss = { state.resumeOptions = null })
}

@Composable
private fun HomeRowView(row: HomeRow, state: HomeState, nav: Navigator, firstFocus: FocusRequester?) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    if (row is HomeRow.Nudge) {
        NudgeRow(row, firstFocus)
        return
    }
    if (row is HomeRow.Update) {
        UpdateRow(row, firstFocus)
        return
    }
    Column {
        SectionTitle(row.title, Modifier.padding(start = 48.dp, bottom = 10.dp))
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (row) {
                is HomeRow.Resume -> itemsIndexed(row.items, key = { _, h -> h.key }) { i, h ->
                    val fr = if (i == 0) firstFocus else null
                    val self = remember { FocusRequester() }
                    WideCard(
                        title = cleanTitle(h.title),
                        subtitle = h.subtitle.ifBlank { if (h.durationMs > 0) "${clockLabel(h.durationMs - h.positionMs)} left" else "" },
                        image = h.backdrop.ifBlank { h.image },
                        progress = if (h.durationMs > 0) h.positionMs.toFloat() / h.durationMs else 0f,
                        onClick = { state.resume(h, nav) },
                        onLongClick = { state.resumeOptions = h },
                        onFocused = { state.focusResume(h) },
                        focusRequester = fr ?: self,
                    )
                    if (state.pendingFocus == h.key) {
                        LaunchedEffect(row.items.size) {
                            state.pendingFocus = null
                            (fr ?: self).tryFocus()
                        }
                    }
                }
                is HomeRow.Channels -> items(row.items, key = { it.key }) { c ->
                    val nn = row.nowNext[c.key]
                    val p = nn?.now
                    val t = now()
                    WideCard(
                        title = p?.title ?: c.name,
                        subtitle = "${c.number} · ${displayChannelName(c.name)}",
                        image = c.logo,
                        imageFit = true,
                        width = 188.dp,
                        progress = if (p != null) ((t - p.startMs).toFloat() / (p.endMs - p.startMs)) else 0f,
                        progressColor = C.Live,
                        onClick = {
                            scope.launch {
                                val list = if (row.id == "fav") row.items else app.live.channels(app.prefs.guideGroup.value).ifEmpty { row.items }
                                app.playback.playLive(c, list)
                                nav.push(Dest.Player)
                            }
                        },
                        onFocused = { state.focusChannel(c, nn) },
                        focusRequester = if (c === row.items.first()) firstFocus else null,
                    )
                }
                is HomeRow.Movies -> items(row.items, key = { it.key }) { m ->
                    PosterCard(
                        title = cleanTitle(m.name),
                        image = m.poster,
                        rating = m.rating,
                        onClick = { nav.push(Dest.MovieDetail(m.key)) },
                        onFocused = { state.focusMovie(m) },
                        focusRequester = if (m === row.items.first()) firstFocus else null,
                    )
                }
                is HomeRow.Nudge, is HomeRow.Update -> Unit
                is HomeRow.LiveNow -> items(row.items, key = { it.programme.id }) { o ->
                    val p = o.programme
                    val t = now()
                    WideCard(
                        title = p.title,
                        subtitle = "${displayChannelName(o.channel.name)} · ${timeOf(p.startMs)} – ${timeOf(p.endMs)}",
                        image = o.channel.logo,
                        imageFit = true,
                        width = 200.dp,
                        badge = "Live",
                        progress = ((t - p.startMs).toFloat() / (p.endMs - p.startMs)).coerceIn(0f, 1f),
                        progressColor = C.Live,
                        onClick = {
                            scope.launch {
                                app.playback.playLive(o.channel, row.items.map { it.channel }.distinctBy { it.key })
                                nav.push(Dest.Player)
                            }
                        },
                        onFocused = { state.focusChannel(o.channel, NowNext(io.github.lucasclgibson.nimbus.data.db.ProgrammeLite(p.id, p.epgId, p.startMs, p.endMs, p.title), null)) },
                        focusRequester = if (o === row.items.first()) firstFocus else null,
                    )
                }
                is HomeRow.Shows -> items(row.items, key = { it.key }) { s ->
                    PosterCard(
                        title = cleanTitle(s.name),
                        image = s.poster,
                        rating = s.rating,
                        onClick = { nav.push(Dest.SeriesDetail(s.key)) },
                        onFocused = { state.focusSeries(s) },
                        focusRequester = if (s === row.items.first()) firstFocus else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateRow(row: HomeRow.Update, focus: FocusRequester?) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val progress by app.updater.progress.collectAsState()
    val message by app.updater.message.collectAsState()
    Row(
        Modifier
            .padding(horizontal = 48.dp)
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0x3334D399), Color(0x142E8BFF))), RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Nimbus ${row.info.versionName} is ready", style = T.H3, color = C.Text)
            Spacer(Modifier.height(4.dp))
            val p = progress
            Text(
                message ?: when {
                    p == null -> row.info.notes.ifBlank { "Improvements and fixes. Takes a few seconds." } +
                        if (!android.provider.Settings.canDrawOverlays(context)) " Nimbus closes to update – open it again from Your Apps." else ""
                    p > 1f -> "Confirm the install on screen…"
                    else -> "Downloading… ${(p * 100).toInt()}%"
                },
                style = T.Body, color = C.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(18.dp))
        PillButton(
            "Install now",
            {
                if (!app.updater.canInstall()) {
                    app.updater.permissionIntent()?.let { context.startActivity(it) }
                } else scope.launch { app.updater.install() }
            },
            primary = true,
            modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
        )
    }
}

@Composable
private fun NudgeRow(row: HomeRow.Nudge, focus: FocusRequester?) {
    val app = LocalApp.current
    val nav = LocalNav.current
    Row(
        Modifier
            .padding(horizontal = 48.dp)
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0x332E8BFF), Color(0x147A5CFF))), RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.title, style = T.H3, color = C.Text)
            Spacer(Modifier.height(4.dp))
            Text(row.message, style = T.Body, color = C.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(18.dp))
        PillButton(
            "Choose channel groups", { nav.push(Dest.ManageCategories(Kind.LIVE)) }, primary = true,
            modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
        )
        Spacer(Modifier.width(10.dp))
        PillButton("Not now", {
            app.prefs.groupsReviewed.value = true
            app.sync.contentVersion.value++
        })
    }
}

@Composable
fun WelcomeScreen() {
    val nav = LocalNav.current
    val first = remember { FocusRequester() }
    SetBackdrop(null)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(size = T.Display.copy(fontSize = T.Display.fontSize * 1.6f))
            Spacer(Modifier.height(10.dp))
            Text("Your TV, beautifully organised.", style = T.H2, color = C.Text)
            Spacer(Modifier.height(6.dp))
            Text("Add your IPTV playlist to get live TV, a full guide with catch-up, films and box sets.", style = T.Body, color = C.TextDim)
            Spacer(Modifier.height(30.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(
                    "Xtream Codes login", { nav.push(Dest.PlaylistForm(PlaylistType.XTREAM)) },
                    icon = Icons.Rounded.Add, primary = true,
                    modifier = Modifier.focusRequester(first),
                )
                PillButton("M3U playlist URL", { nav.push(Dest.PlaylistForm(PlaylistType.M3U)) }, icon = Icons.Rounded.Link)
                PillButton("Set up from my phone", { nav.push(Dest.PhoneSetup) }, icon = Icons.Rounded.PhoneAndroid)
            }
        }
    }
    first.RequestOnce()
}
