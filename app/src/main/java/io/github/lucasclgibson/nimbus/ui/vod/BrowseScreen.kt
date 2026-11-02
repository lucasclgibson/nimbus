package io.github.lucasclgibson.nimbus.ui.vod

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.lucasclgibson.nimbus.AppGraph
import io.github.lucasclgibson.nimbus.core.cleanTitle
import io.github.lucasclgibson.nimbus.data.db.Kind
import io.github.lucasclgibson.nimbus.data.db.MovieEntity
import io.github.lucasclgibson.nimbus.data.db.SeriesEntity
import io.github.lucasclgibson.nimbus.data.repo.VodCategory
import io.github.lucasclgibson.nimbus.data.repo.VodRepository
import io.github.lucasclgibson.nimbus.ui.components.EmptyState
import io.github.lucasclgibson.nimbus.ui.components.FocusSurface
import io.github.lucasclgibson.nimbus.ui.components.HeroImage
import io.github.lucasclgibson.nimbus.ui.components.MetaLine
import io.github.lucasclgibson.nimbus.ui.components.PosterCard
import io.github.lucasclgibson.nimbus.ui.components.RequestOnce
import io.github.lucasclgibson.nimbus.ui.components.SetBackdrop
import io.github.lucasclgibson.nimbus.ui.components.TopNav
import io.github.lucasclgibson.nimbus.ui.components.FocusRoom
import io.github.lucasclgibson.nimbus.ui.nav.Dest
import io.github.lucasclgibson.nimbus.ui.nav.LocalApp
import io.github.lucasclgibson.nimbus.ui.nav.LocalNav
import io.github.lucasclgibson.nimbus.ui.nav.entryState
import io.github.lucasclgibson.nimbus.ui.theme.C
import io.github.lucasclgibson.nimbus.ui.theme.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class BrowseItem(
    val key: String,
    val title: String,
    val poster: String,
    val year: Int,
    val rating: Double,
    val genre: String,
    val plot: String,
    val backdrop: String,
)

class BrowseState(private val app: AppGraph, private val scope: CoroutineScope, private val series: Boolean) {
    var categories by mutableStateOf<List<VodCategory>>(emptyList())
    var selected by mutableStateOf(VodRepository.RECENT)
    var items by mutableStateOf<List<BrowseItem>>(emptyList())
    var loading by mutableStateOf(true)
    var focused by mutableStateOf<BrowseItem?>(null)
    var focusedBackdrop by mutableStateOf<String?>(null)
    val grid = LazyGridState()
    val rail = LazyListState()
    private var loadJob: Job? = null
    private var detailJob: Job? = null

    init {
        scope.launch {
            app.sync.contentVersion.collect {
                categories = app.vod.categories(if (series) Kind.SERIES else Kind.MOVIE)
                load(selected, immediate = true)
            }
        }
    }

    fun load(catId: String, immediate: Boolean = false) {
        if (catId == selected && items.isNotEmpty() && !immediate) return
        loadJob?.cancel()
        loadJob = scope.launch {
            if (!immediate) delay(320)
            selected = catId
            loading = true
            items = if (series) {
                app.vod.series(catId).map { it.toItem() }
            } else {
                app.vod.movies(catId).map { it.toItem() }
            }
            loading = false
            grid.scrollToItem(0)
        }
    }

    fun focus(item: BrowseItem) {
        detailJob?.cancel()
        // Update the header once focus settles: laying out a new title + plot on every key press while
        // scrolling quickly costs frames on the stick.
        detailJob = scope.launch {
            delay(110)
            focused = item
            if (item.backdrop.isNotBlank()) {
                focusedBackdrop = item.backdrop
                return@launch
            }
            delay(390)
            val flow = if (series) app.vod.seriesDetails(item.key) else app.vod.movieDetails(item.key)
            flow.collect { d ->
                if (focused?.key == item.key) {
                    focused = item.copy(plot = d.plot.ifBlank { item.plot }, genre = d.genres.ifBlank { item.genre })
                    focusedBackdrop = d.backdrop.ifBlank { null }
                }
            }
        }
    }

    private fun MovieEntity.toItem() = BrowseItem(key, cleanTitle(name), poster, year, rating, "", "", "")
    private fun SeriesEntity.toItem() = BrowseItem(key, cleanTitle(name), poster, year, rating, genre, plot, backdrop)
}

@Composable
fun BrowseScreen(series: Boolean) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val state = entryState("browse-$series") { BrowseState(app, it, series) }
    val favourites by app.library.favourites.collectAsState()
    val scope = rememberCoroutineScope()
    val kind = if (series) Kind.SERIES else Kind.MOVIE
    SetBackdrop(null)

    Box(Modifier.fillMaxSize()) {
    HeroImage(state.focusedBackdrop, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.55f).height(250.dp))
    Column(Modifier.fillMaxSize()) {
        TopNav(if (series) Dest.Shows else Dest.Movies)
        Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
            // ------------------------------------------------ category rail
            LazyColumn(
                state = state.rail,
                modifier = Modifier.width(250.dp).fillMaxHeight().focusRestorer(),
                contentPadding = PaddingValues(start = 48.dp, end = 12.dp, bottom = 40.dp, top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(state.categories, key = { it.id }) { c ->
                    val selected = c.id == state.selected
                    FocusSurface(
                        onClick = { state.load(c.id, immediate = true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .onFocusChanged { if (it.isFocused) state.load(c.id) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (selected) C.Glass else Color.Transparent,
                        focusedColor = C.Focus,
                        contentColor = if (selected) C.Text else C.TextDim,
                        focusedContentColor = C.OnFocus,
                        focusedScale = 1.03f,
                        border = false,
                    ) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selected) {
                                Box(Modifier.width(3.dp).height(16.dp).background(C.Accent, RoundedCornerShape(2.dp)))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(c.title, style = T.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // ------------------------------------------------ header + grid
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 40.dp)) {
                val f = state.focused
                Column(Modifier.fillMaxWidth().height(92.dp).padding(start = 8.dp)) {
                    if (f != null) {
                        Text(f.title, style = T.H1, color = C.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        MetaLine(listOf(f.year.takeIf { it > 0 }?.toString().orEmpty(), if (f.rating > 0) "★ %.1f".format(f.rating) else "", f.genre))
                        Spacer(Modifier.height(4.dp))
                        Text(f.plot, style = T.Body, color = C.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!state.loading && state.items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            if (state.selected == VodRepository.MY_LIST) "Your list is empty" else "Nothing here yet",
                            if (state.selected == VodRepository.MY_LIST) "Press ☰ on any title to add it to My list." else "Try another category.",
                        )
                    }
                } else {
                    val firstItem = remember { FocusRequester() }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(5),
                        state = state.grid,
                        modifier = Modifier.fillMaxSize().focusRestorer(),
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = FocusRoom, bottom = 60.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(state.items, key = { it.key }) { item ->
                            val fav = "$kind:${item.key}" in favourites
                            PosterCard(
                                title = (if (fav) "♥ " else "") + item.title,
                                image = item.poster,
                                rating = item.rating,
                                width = 108.dp,
                                onClick = { nav.push(if (series) Dest.SeriesDetail(item.key) else Dest.MovieDetail(item.key)) },
                                onLongClick = { scope.launch { app.library.toggleFavourite(kind, item.key) } },
                                onFocused = { state.focus(item) },
                                focusRequester = if (item === state.items.first()) firstItem else null,
                            )
                        }
                    }
                    firstItem.RequestOnce(state.items.isNotEmpty() && state.focused == null, enabled = state.items.isNotEmpty() && state.focused == null)
                }
            }
        }
    }
    }
}
