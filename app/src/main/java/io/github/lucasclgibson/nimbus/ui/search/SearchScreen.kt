package io.github.lucasclgibson.nimbus.ui.search

import android.util.Log
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.lucasclgibson.nimbus.AppGraph
import io.github.lucasclgibson.nimbus.core.cleanTitle
import io.github.lucasclgibson.nimbus.core.dayLabel
import io.github.lucasclgibson.nimbus.core.displayChannelName
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.core.timeOf
import io.github.lucasclgibson.nimbus.data.db.ChannelEntity
import io.github.lucasclgibson.nimbus.data.db.MovieEntity
import io.github.lucasclgibson.nimbus.data.db.SeriesEntity
import io.github.lucasclgibson.nimbus.data.repo.OnTv
import io.github.lucasclgibson.nimbus.ui.components.EmptyState
import io.github.lucasclgibson.nimbus.ui.components.FocusSurface
import io.github.lucasclgibson.nimbus.ui.components.PosterCard
import io.github.lucasclgibson.nimbus.ui.components.SectionTitle
import io.github.lucasclgibson.nimbus.ui.components.SetBackdrop
import io.github.lucasclgibson.nimbus.ui.components.TopNav
import io.github.lucasclgibson.nimbus.ui.components.TvTextField
import io.github.lucasclgibson.nimbus.ui.components.WideCard
import io.github.lucasclgibson.nimbus.ui.components.tryFocus
import io.github.lucasclgibson.nimbus.ui.components.FocusRoom
import io.github.lucasclgibson.nimbus.ui.components.bleedHorizontally
import io.github.lucasclgibson.nimbus.ui.nav.Dest
import io.github.lucasclgibson.nimbus.ui.nav.LocalApp
import io.github.lucasclgibson.nimbus.ui.nav.LocalNav
import io.github.lucasclgibson.nimbus.ui.nav.entryState
import io.github.lucasclgibson.nimbus.ui.player.Spinner
import io.github.lucasclgibson.nimbus.ui.theme.C
import io.github.lucasclgibson.nimbus.ui.theme.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchState(private val app: AppGraph, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
        private set
    var channels by mutableStateOf<List<ChannelEntity>>(emptyList())
    var movies by mutableStateOf<List<MovieEntity>>(emptyList())
    var series by mutableStateOf<List<SeriesEntity>>(emptyList())
    var onTv by mutableStateOf<List<OnTv>>(emptyList())
    var searching by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var recent by mutableStateOf(app.prefs.recentSearches.value.split('\n').filter { it.isNotBlank() })

    /** The result card that had focus, so Back from a film/channel lands on the same card. */
    var lastFocus by mutableStateOf<String?>(null)

    /** Bumped for every new result set: fresh scroll positions, but kept while you open a result and come back. */
    var generation by mutableIntStateOf(0)
    var column = LazyListState()
        private set
    private val rows = HashMap<String, LazyListState>()
    private var job: Job? = null

    fun rowState(id: String): LazyListState = rows.getOrPut(id) { LazyListState() }

    fun type(s: String) = update(query + s)
    fun backspace() = update(query.dropLast(1))
    fun clear() = update("")

    fun update(q: String) {
        if (q == query) return
        query = q
        lastFocus = null
        job?.cancel()
        val term = q.trim()
        if (term.isEmpty()) {
            channels = emptyList(); movies = emptyList(); series = emptyList(); onTv = emptyList()
            searching = false; searched = false
            return
        }
        searching = true
        job = scope.launch {
            delay(220) // let quick typing settle
            val t0 = System.currentTimeMillis()
            coroutineScope {
                val ch = async { app.search.channels(term) }
                val mv = async { app.search.movies(term) }
                val sr = async { app.search.series(term) }
                val tv = async { if (term.length >= 3) app.search.onTv(term, app.store.all.value) else emptyList() }
                channels = ch.await(); movies = mv.await(); series = sr.await(); onTv = tv.await()
            }
            Log.i("NimbusSearch", "\"$term\" answered in ${System.currentTimeMillis() - t0}ms")
            column = LazyListState()
            rows.clear()
            generation++
            searching = false
            searched = true
        }
    }

    fun clearRecent() {
        recent = emptyList()
        app.prefs.recentSearches.value = ""
    }

    /** Remember the current search once something from it is opened. */
    fun keep() {
        val q = query.trim()
        if (q.length < 2) return
        recent = (listOf(q) + recent.filterNot { it.equals(q, ignoreCase = true) }).take(8)
        app.prefs.recentSearches.value = recent.joinToString("\n")
    }
}

private val KEYS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890".map { it.toString() }

@Composable
fun SearchScreen() {
    val app = LocalApp.current
    val state = entryState { SearchState(app, it) }
    val firstKey = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    SetBackdrop(null)
    Column(Modifier.fillMaxSize()) {
        TopNav(Dest.Search)
        Row(
            Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
                // ⏪ / ⏩ edit the search from anywhere on the screen (results included).
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.MediaRewind -> { state.backspace(); true }
                        Key.MediaFastForward -> { if (state.query.isNotEmpty()) state.type(" "); true }
                        else -> false
                    }
                },
        ) {
            SearchKeyboard(state, firstKey, Modifier.padding(start = 48.dp).width(265.dp))
            Spacer(Modifier.width(30.dp))
            Results(state, firstKey, restore, Modifier.weight(1f).fillMaxHeight())
        }
    }
    // Start on the keyboard; coming back from a result, return to that result.
    LaunchedEffect(Unit) {
        repeat(30) {
            withFrameNanos { }
            if (state.lastFocus != null) {
                if (restore.tryFocus()) return@LaunchedEffect
            } else if (firstKey.tryFocus()) return@LaunchedEffect
        }
        firstKey.tryFocus()
    }
}

// ---------------------------------------------------------------------------------------- keyboard

@Composable
private fun SearchKeyboard(state: SearchState, firstKey: FocusRequester, modifier: Modifier) {
    Column(modifier) {
        // What you've typed. Selecting it opens the Fire TV keyboard instead (long names, other characters).
        TvTextField(
            value = state.query,
            onValueChange = { state.update(it) },
            label = "",
            placeholder = "Search",
            modifier = Modifier.fillMaxWidth(),
            keyboardType = KeyboardType.Text,
        )
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                // Coming back from the results returns to the key you were on.
                .focusRestorer(firstKey)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Backspace -> { state.backspace(); true }
                        Key.Spacebar -> { state.type(" "); true }
                        else -> {
                            // Typing on a phone remote / USB keyboard works too.
                            val c = e.utf16CodePoint.toChar()
                            if (c.isLetterOrDigit()) { state.type(c.lowercase()); true } else false
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            KEYS.chunked(6).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    row.forEachIndexed { c, k ->
                        KeyButton(
                            k,
                            Modifier.size(40.dp, 36.dp).then(if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier),
                        ) { state.type(k.lowercase()) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                KeyButton("Space", Modifier.size(85.dp, 36.dp)) { state.type(" ") }
                KeyButton("Delete", Modifier.size(85.dp, 36.dp), icon = Icons.AutoMirrored.Rounded.Backspace) { state.backspace() }
                KeyButton("Clear", Modifier.size(85.dp, 36.dp)) { state.clear() }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("⏪ delete   ·   ⏩ space", style = T.Small, color = C.TextMute)
        Spacer(Modifier.height(3.dp))
        Text("Select the box above to use the Fire TV keyboard", style = T.Small, color = C.TextMute)
    }
}

@Composable
private fun KeyButton(label: String, modifier: Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = C.Glass,
        focusedColor = C.Focus,
        contentColor = C.Text,
        focusedContentColor = C.OnFocus,
        focusedScale = 1.12f,
        border = false,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, label, Modifier.size(18.dp))
            else Text(label, style = if (label.length == 1) T.H3 else T.Label, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------------------- results

@Composable
private fun Results(state: SearchState, firstKey: FocusRequester, restore: FocusRequester, modifier: Modifier) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    fun requesterFor(id: String) = if (state.lastFocus == id) restore else null

    Box(modifier) {
        val empty = state.channels.isEmpty() && state.movies.isEmpty() && state.series.isEmpty() && state.onTv.isEmpty()
        when {
            state.query.isBlank() -> RecentSearches(state, firstKey)
            empty && state.searching -> Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(22.dp)
                Spacer(Modifier.width(12.dp))
                Text("Searching…", style = T.Body, color = C.TextDim)
            }
            empty && state.searched -> Box(Modifier.fillMaxSize().padding(end = 48.dp), contentAlignment = Alignment.Center) {
                EmptyState("No results", "Nothing matches “${state.query.trim()}”. Try fewer letters.")
            }
            else -> androidx.compose.runtime.key(state.generation) {
                LazyColumn(
                    state = state.column,
                    contentPadding = PaddingValues(top = 2.dp, bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (state.channels.isNotEmpty()) item(key = "ch") {
                        ResultRow("Channels", state.rowState("ch")) {
                            items(state.channels, key = { it.key }) { c ->
                                val id = "c:${c.key}"
                                WideCard(
                                    title = displayChannelName(c.name), subtitle = "Channel ${c.number}", image = c.logo,
                                    imageFit = true, width = 150.dp,
                                    focusRequester = requesterFor(id), onFocused = { state.lastFocus = id },
                                    onClick = {
                                        state.keep()
                                        scope.launch {
                                            app.playback.playLive(c, state.channels)
                                            nav.push(Dest.Player)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (state.onTv.isNotEmpty()) item(key = "tv") {
                        ResultRow("On TV", state.rowState("tv")) {
                            items(state.onTv, key = { it.programme.id }) { o ->
                                val p = o.programme
                                val id = "p:${p.id}"
                                val t = now()
                                val live = p.startMs <= t && p.endMs > t
                                val past = p.endMs <= t
                                WideCard(
                                    title = p.title,
                                    subtitle = "${displayChannelName(o.channel.name)} · " + when {
                                        live -> "On now"
                                        else -> "${dayLabel(p.startMs)} ${timeOf(p.startMs)}"
                                    },
                                    image = o.channel.logo, imageFit = true, width = 172.dp,
                                    badge = when {
                                        live -> "Live"
                                        past && app.live.canCatchup(o.channel, p.startMs) -> "Catch up"
                                        else -> null
                                    },
                                    focusRequester = requesterFor(id), onFocused = { state.lastFocus = id },
                                    onClick = {
                                        state.keep()
                                        if (past && app.live.canCatchup(o.channel, p.startMs)) {
                                            app.playback.playCatchup(o.channel, listOf(o.channel), p.title, p.startMs, p.endMs)
                                        } else {
                                            app.playback.playLive(o.channel, listOf(o.channel))
                                        }
                                        nav.push(Dest.Player)
                                    },
                                )
                            }
                        }
                    }
                    if (state.movies.isNotEmpty()) item(key = "mv") {
                        ResultRow("Films", state.rowState("mv")) {
                            items(state.movies, key = { it.key }) { m ->
                                val id = "m:${m.key}"
                                PosterCard(
                                    cleanTitle(m.name), m.poster,
                                    onClick = { state.keep(); nav.push(Dest.MovieDetail(m.key)) },
                                    rating = m.rating, width = 96.dp, showTitle = true,
                                    focusRequester = requesterFor(id), onFocused = { state.lastFocus = id },
                                )
                            }
                        }
                    }
                    if (state.series.isNotEmpty()) item(key = "sr") {
                        ResultRow("TV shows", state.rowState("sr")) {
                            items(state.series, key = { it.key }) { s ->
                                val id = "s:${s.key}"
                                PosterCard(
                                    cleanTitle(s.name), s.poster,
                                    onClick = { state.keep(); nav.push(Dest.SeriesDetail(s.key)) },
                                    rating = s.rating, width = 96.dp, showTitle = true,
                                    focusRequester = requesterFor(id), onFocused = { state.lastFocus = id },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSearches(state: SearchState, firstKey: FocusRequester) {
    Column(Modifier.fillMaxSize().padding(end = 48.dp)) {
        if (state.recent.isEmpty()) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, Modifier.size(22.dp), tint = C.TextMute)
                Spacer(Modifier.width(10.dp))
                Text("Channels, what's on TV, films and TV shows – results appear as you type.", style = T.Body, color = C.TextDim)
            }
            return
        }
        SectionTitle("Recent searches", Modifier.padding(bottom = 10.dp - FocusRoom / 2))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(top = FocusRoom / 2, bottom = FocusRoom)) {
            item(key = "__clear") {
                FocusSurface(
                    onClick = { state.clearRecent() },
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(50),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    focusedColor = C.Focus,
                    contentColor = C.TextMute,
                    focusedContentColor = C.OnFocus,
                    focusedScale = 1.04f,
                    border = false,
                ) {
                    Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                        Text("Clear history", style = T.Small)
                    }
                }
            }
            items(state.recent, key = { it }) { q ->
                FocusSurface(
                    onClick = {
                        state.update(q)
                        firstKey.tryFocus() // this list is replaced by the results
                    },
                    modifier = Modifier.height(38.dp).widthIn(min = 120.dp),
                    shape = RoundedCornerShape(50),
                    color = C.Glass,
                    focusedColor = C.Focus,
                    contentColor = C.TextDim,
                    focusedContentColor = C.OnFocus,
                    focusedScale = 1.04f,
                    border = false,
                ) {
                    Row(Modifier.fillMaxHeight().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.History, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(q, style = T.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(title: String, rowState: LazyListState, content: LazyListScope.() -> Unit) {
    Column {
        SectionTitle(title, Modifier.padding(bottom = 10.dp))
        LazyRow(
            state = rowState,
            modifier = Modifier.bleedHorizontally().focusRestorer(),
            contentPadding = PaddingValues(start = FocusRoom, end = 48.dp + FocusRoom),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}
