package io.github.lucasclgibson.nimbus.data.repo

import android.util.Log
import io.github.lucasclgibson.nimbus.core.DAY
import io.github.lucasclgibson.nimbus.core.Background
import io.github.lucasclgibson.nimbus.core.HOUR
import io.github.lucasclgibson.nimbus.core.await
import io.github.lucasclgibson.nimbus.core.friendlyError
import io.github.lucasclgibson.nimbus.core.isSeparatorChannel
import io.github.lucasclgibson.nimbus.core.normaliseChannelName
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.core.requireOk
import io.github.lucasclgibson.nimbus.core.timeOf
import io.github.lucasclgibson.nimbus.core.yearFromTitle
import io.github.lucasclgibson.nimbus.data.Prefs
import io.github.lucasclgibson.nimbus.data.db.CategoryEntity
import io.github.lucasclgibson.nimbus.data.db.ChannelEntity
import io.github.lucasclgibson.nimbus.data.db.EpisodeEntity
import io.github.lucasclgibson.nimbus.data.db.Kind
import io.github.lucasclgibson.nimbus.data.db.MovieEntity
import io.github.lucasclgibson.nimbus.data.db.NimbusDb
import io.github.lucasclgibson.nimbus.data.db.PlaylistEntity
import io.github.lucasclgibson.nimbus.data.db.PlaylistType
import io.github.lucasclgibson.nimbus.data.db.ProgrammeEntity
import io.github.lucasclgibson.nimbus.data.db.SeriesEntity
import io.github.lucasclgibson.nimbus.data.source.M3uEntry
import io.github.lucasclgibson.nimbus.data.source.M3uParser
import io.github.lucasclgibson.nimbus.data.source.XmltvParser
import io.github.lucasclgibson.nimbus.data.source.XmltvProgramme
import io.github.lucasclgibson.nimbus.data.source.XmltvSink
import io.github.lucasclgibson.nimbus.data.source.bool
import io.github.lucasclgibson.nimbus.data.source.double
import io.github.lucasclgibson.nimbus.data.source.int
import io.github.lucasclgibson.nimbus.data.source.long
import io.github.lucasclgibson.nimbus.data.source.str
import io.github.lucasclgibson.nimbus.data.source.strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.FilterInputStream
import java.io.InputStream
import java.text.NumberFormat
import java.util.Locale

/**
 * Pulls playlists and guides into the local database. Everything the UI shows comes from the database,
 * so the app opens instantly and keeps working from cache if a provider is down.
 */
class SyncManager(
    private val db: NimbusDb,
    private val http: OkHttpClient,
    private val store: PlaylistStore,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
) {
    data class Status(val message: String, val fraction: Float? = null, val error: Boolean = false)

    private val _status = MutableStateFlow<Map<Long, Status>>(emptyMap())
    val status: StateFlow<Map<Long, Status>> = _status

    /** Bumped after channel/VOD lists change, and after a new guide is in place. Screens reload on change. */
    val contentVersion = MutableStateFlow(0)
    val epgVersion = MutableStateFlow(0)

    private val _running = MutableStateFlow<Set<Long>>(emptySet())
    val running: StateFlow<Set<Long>> = _running

    private val gate = Mutex()

    fun refreshIfStale() {
        scope.launch {
            store.ready()
            for (p in db.playlists().all()) {
                when {
                    p.lastSync == 0L || now() - p.lastSync > 20 * HOUR -> sync(p.id, content = true, epg = true)
                    now() - p.lastEpgSync > 10 * HOUR -> sync(p.id, content = false, epg = true)
                }
            }
        }
    }

    fun syncAll() {
        scope.launch { db.playlists().all().forEach { sync(it.id) } }
    }

    fun sync(id: Long, content: Boolean = true, epg: Boolean = true): Job = scope.launch(Background.dispatcher) {
        if (id in _running.value) return@launch
        _running.update { it + id }
        var failed = false
        try {
            gate.withLock {
                var p = db.playlists().get(id) ?: return@withLock
                if (content) {
                    p = try {
                        syncContent(p)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "content sync failed", e)
                        failed = true
                        val msg = friendlyError(e)
                        report(id, msg, error = true)
                        db.playlists().setStatus(id, "Update failed: $msg")
                        if (p.lastSync == 0L) return@withLock
                        p
                    }
                }
                if (epg) {
                    try {
                        syncEpg(p)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "epg sync failed", e)
                        failed = true
                        report(id, "Guide update failed: ${friendlyError(e)}", error = true)
                    }
                }
            }
        } finally {
            _running.update { it - id }
            if (!failed) _status.update { it - id }
        }
    }

    private fun report(id: Long, message: String, fraction: Float? = null, error: Boolean = false) {
        _status.update { it + (id to Status(message, fraction, error)) }
    }

    // ---------------------------------------------------------------- content

    private suspend fun syncContent(p: PlaylistEntity): PlaylistEntity {
        val updated = if (p.type == PlaylistType.XTREAM) syncXtream(p) else syncM3u(p)
        val counts = updated.copy(
            lastSync = now(),
            channelCount = db.channels().count(p.id),
            movieCount = db.movies().count(p.id),
            seriesCount = db.series().count(p.id),
        )
        val fmt = NumberFormat.getIntegerInstance(Locale.UK)
        val final = counts.copy(
            status = "Updated ${timeOf(now())} · ${fmt.format(counts.channelCount)} channels · " +
                "${fmt.format(counts.movieCount)} films · ${fmt.format(counts.seriesCount)} series",
        )
        db.playlists().update(final)
        contentVersion.update { it + 1 }
        return final
    }

    private suspend fun syncXtream(p0: PlaylistEntity): PlaylistEntity {
        val api = store.xtream(p0)
        report(p0.id, "Signing in…")
        val account = api.login()
        var p = p0.copy(
            serverTimezone = account.timezone.ifBlank { p0.serverTimezone },
            expiresAt = account.expiresAt,
            maxConnections = account.maxConnections,
        )
        if (account.formats.isNotEmpty() && p.outputFormat !in account.formats) {
            p = p.copy(outputFormat = if ("m3u8" in account.formats) "m3u8" else "ts")
        }
        db.playlists().update(p)

        // Every list streams straight into the database in batches, stamped with this refresh. Rows a
        // successful refresh didn't see are swept afterwards; a failed refresh leaves the old ones alone.
        // (Big providers carry 180k+ films: holding them all in memory risks an OOM mid-film.)
        val stamp = now()

        // Live
        report(p.id, "Loading channels…")
        val previousEpg = db.channels().epgIds(p.id).associate { it.key to it.epgId }
        val liveCats = api.categories("get_live_categories")
        val liveIds = HashSet<String>()
        val channels = Batcher<ChannelEntity> { db.channels().upsertBlocking(it) }
        api.forEachItem("get_live_streams") { m ->
            val id = m.str("stream_id")
            if (id.isEmpty() || isSeparatorChannel(m.str("name"))) return@forEachItem
            val key = "${p.id}:$id"
            val archive = m.bool("tv_archive")
            val cat = m.str("category_id")
            liveIds += cat
            channels.add(
                ChannelEntity(
                    key = key,
                    playlistId = p.id,
                    streamId = id,
                    number = m.int("num").takeIf { it > 0 } ?: (channels.count + 1),
                    name = m.str("name").trim(),
                    logo = m.str("stream_icon").trim(),
                    categoryId = cat,
                    epgId = m.str("epg_channel_id").trim().ifEmpty { previousEpg[key].orEmpty() },
                    url = "",
                    catchupType = if (archive) "xc" else "",
                    catchupDays = if (archive) m.int("tv_archive_duration").coerceIn(1, 30) else 0,
                    catchupSource = "",
                    tvgShiftMin = 0,
                    userAgent = "",
                    referrer = "",
                    sortOrder = channels.count,
                    syncStamp = stamp,
                )
            )
        }
        channels.flush()
        saveCategories(p.id, Kind.LIVE, liveCats, liveIds)
        db.channels().sweep(p.id, stamp)
        contentVersion.update { it + 1 }

        // Films – a failure here must not wipe what we already have.
        report(p.id, "Loading films…")
        try {
            val cats = api.categories("get_vod_categories")
            val ids = HashSet<String>()
            val movies = Batcher<MovieEntity> { db.movies().upsertBlocking(it) }
            api.forEachItem("get_vod_streams") { m ->
                val id = m.str("stream_id")
                if (id.isEmpty()) return@forEachItem
                val name = m.str("name").trim()
                val cat = m.str("category_id")
                ids += cat
                movies.add(
                    MovieEntity(
                        key = "${p.id}:$id",
                        playlistId = p.id,
                        streamId = id,
                        name = name,
                        year = m.int("year").takeIf { it in 1900..2100 } ?: yearFromTitle(name),
                        poster = m.str("stream_icon").trim(),
                        rating = normaliseRating(m.double("rating"), m.double("rating_5based")),
                        categoryId = cat,
                        added = m.long("added") * 1000,
                        ext = m.str("container_extension"),
                        url = "",
                        tmdbId = m.str("tmdb").ifEmpty { m.str("tmdb_id") },
                        sortOrder = movies.count,
                        syncStamp = stamp,
                    )
                )
                if (movies.count % 20_000 == 0) report(p.id, "Loading films… ${NumberFormat.getIntegerInstance(Locale.UK).format(movies.count)}")
            }
            movies.flush()
            saveCategories(p.id, Kind.MOVIE, cats, ids)
            db.movies().sweep(p.id, stamp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "vod list failed", e)
        }

        report(p.id, "Loading series…")
        try {
            val cats = api.categories("get_series_categories")
            val ids = HashSet<String>()
            val series = Batcher<SeriesEntity> { db.series().upsertBlocking(it) }
            api.forEachItem("get_series") { m ->
                val id = m.str("series_id")
                if (id.isEmpty()) return@forEachItem
                val name = m.str("name").trim()
                val release = m.str("releaseDate").ifEmpty { m.str("release_date") }
                val cat = m.str("category_id")
                ids += cat
                series.add(
                    SeriesEntity(
                        key = "${p.id}:$id",
                        playlistId = p.id,
                        seriesId = id,
                        name = name,
                        year = release.take(4).toIntOrNull()?.takeIf { it in 1900..2100 } ?: yearFromTitle(name),
                        poster = m.str("cover").trim(),
                        backdrop = m.strings("backdrop_path").firstOrNull().orEmpty(),
                        rating = normaliseRating(m.double("rating"), m.double("rating_5based")),
                        categoryId = cat,
                        plot = m.str("plot").trim(),
                        genre = m.str("genre").trim(),
                        added = m.long("last_modified") * 1000,
                        tmdbId = m.str("tmdb").ifEmpty { m.str("tmdb_id") },
                        sortOrder = series.count,
                        syncStamp = stamp,
                    )
                )
            }
            series.flush()
            saveCategories(p.id, Kind.SERIES, cats, ids)
            db.series().sweep(p.id, stamp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "series list failed", e)
        }
        return p
    }

    private fun normaliseRating(rating: Double, rating5: Double): Double = when {
        rating in 0.1..10.0 -> rating
        rating > 10 && rating <= 100 -> rating / 10
        rating5 in 0.1..5.0 -> rating5 * 2
        else -> 0.0
    }

    private suspend fun syncM3u(p0: PlaylistEntity): PlaylistEntity {
        report(p0.id, "Downloading playlist…")
        val request = Request.Builder().url(p0.url.trim()).header("User-Agent", store.userAgent(p0.id)).build()
        val response = http.newCall(request).await().requireOk()
        val previousEpg = db.channels().forPlaylist(p0.id).associate { it.key to it.epgId }

        val stamp = now()
        val channels = ArrayList<ChannelEntity>(4096)
        val movies = Batcher<MovieEntity> { db.movies().upsertBlocking(it) }
        val series = LinkedHashMap<String, SeriesEntity>()
        val episodes = Batcher<EpisodeEntity> { db.episodes().upsertBlocking(it) }
        val liveCats = LinkedHashMap<String, String>()
        val movieCats = LinkedHashMap<String, String>()
        val seriesCats = LinkedHashMap<String, String>()
        var header: Map<String, String> = emptyMap()
        val keyCounts = HashMap<String, Int>()
        fun unique(base: String): String {
            val n = keyCounts.merge(base, 1, Int::plus) ?: 1
            return if (n == 1) base else "$base#$n"
        }
        var index = 0
        val base = now()

        withContext(Background.dispatcher) {
            response.use { r ->
                val stream = r.body?.byteStream() ?: error("Empty playlist")
                M3uParser.parse(stream, onHeader = { header = it }) { e ->
                    index++
                    val group = e.group.ifBlank { "Uncategorised" }
                    when (classify(e)) {
                        Kind.MOVIE -> {
                            movieCats.putIfAbsent(group, group)
                            movies.add(MovieEntity(
                                key = unique("${p0.id}:m:${e.name}"),
                                playlistId = p0.id,
                                streamId = "",
                                name = e.name,
                                year = yearFromTitle(e.name),
                                poster = e.attr("tvg-logo"),
                                rating = 0.0,
                                categoryId = group,
                                added = base - index * 1000L,
                                ext = "",
                                url = e.url,
                                tmdbId = e.attr("tmdb", "tmdb-id", "tvg-tmdb"),
                                sortOrder = movies.count,
                                syncStamp = stamp,
                            ))
                        }
                        Kind.SERIES -> {
                            val parsed = SERIES_EP.find(e.name)
                            val showName = parsed?.groupValues?.get(1)?.trim()?.trimEnd('-', '|', ':', '.')?.trim()
                                ?.ifBlank { null } ?: e.name
                            val season = parsed?.groupValues?.get(2)?.toIntOrNull() ?: 1
                            val ep = parsed?.groupValues?.get(3)?.toIntOrNull() ?: (episodes.count + 1)
                            val seriesKey = "${p0.id}:s:$showName"
                            seriesCats.putIfAbsent(group, group)
                            series.getOrPut(seriesKey) {
                                SeriesEntity(
                                    key = seriesKey, playlistId = p0.id, seriesId = "", name = showName,
                                    year = yearFromTitle(showName), poster = e.attr("tvg-logo"), backdrop = "",
                                    rating = 0.0, categoryId = group, plot = "", genre = "",
                                    added = base - index * 1000L, tmdbId = "", sortOrder = series.size, syncStamp = stamp,
                                )
                            }
                            episodes.add(EpisodeEntity(
                                key = unique("$seriesKey:$season:$ep"),
                                seriesKey = seriesKey,
                                playlistId = p0.id,
                                season = season,
                                episode = ep,
                                title = parsed?.groupValues?.get(4)?.trim()?.trimStart('-', '|', ':', '.')?.trim().orEmpty(),
                                url = e.url,
                                image = e.attr("tvg-logo"),
                                syncStamp = stamp,
                            ))
                        }
                        else -> if (!isSeparatorChannel(e.name)) {
                            liveCats.putIfAbsent(group, group)
                            val key = unique("${p0.id}:${e.name}")
                            var catchType = e.attr("catchup", "catchup-type").ifEmpty { header["catchup"].orEmpty() }
                            val catchSource = e.attr("catchup-source").ifEmpty { header["catchup-source"].orEmpty() }
                            val catchDays = (e.attr("catchup-days", "timeshift", "tvg-rec")
                                .ifEmpty { header["catchup-days"].orEmpty() }).toIntOrNull() ?: 0
                            if (catchDays > 0 && catchType.isEmpty()) {
                                catchType = when {
                                    catchSource.isNotEmpty() -> "default"
                                    XC_URL.containsMatchIn(e.url) -> "xc"
                                    else -> "shift"
                                }
                            }
                            val shift = (e.attr("tvg-shift").ifEmpty { header["tvg-shift"].orEmpty() })
                                .replace(',', '.').toDoubleOrNull() ?: 0.0
                            channels += ChannelEntity(
                                key = key,
                                playlistId = p0.id,
                                streamId = "",
                                number = e.attr("tvg-chno", "channel-number").toIntOrNull() ?: (channels.size + 1),
                                name = e.name,
                                logo = e.attr("tvg-logo"),
                                categoryId = group,
                                epgId = e.attr("tvg-id").ifEmpty { previousEpg[key].orEmpty() },
                                url = e.url,
                                catchupType = if (catchDays > 0 || catchType == "append" || catchType == "default") catchType else "",
                                catchupDays = if (catchType.isNotEmpty()) catchDays.coerceAtLeast(if (catchDays > 0) 1 else 0) else 0,
                                catchupSource = catchSource,
                                tvgShiftMin = (shift * 60).toInt(),
                                userAgent = e.userAgent,
                                referrer = e.referrer,
                                sortOrder = channels.size,
                            )
                        }
                    }
                }
            }
        }
        movies.flush()
        episodes.flush()
        if (channels.isEmpty() && movies.count == 0 && series.isEmpty()) {
            error("The playlist is empty or isn't an M3U file")
        }
        saveCategories(p0.id, Kind.LIVE, liveCats.map { it.key to it.value }, emptySet())
        db.channels().replace(p0.id, channels)
        saveCategories(p0.id, Kind.MOVIE, movieCats.map { it.key to it.value }, emptySet())
        db.movies().sweep(p0.id, stamp)
        saveCategories(p0.id, Kind.SERIES, seriesCats.map { it.key to it.value }, emptySet())
        db.series().replace(p0.id, series.values.toList())
        db.episodes().sweep(p0.id, stamp)

        val autoEpg = (header["url-tvg"] ?: header["x-tvg-url"] ?: header["tvg-url"]).orEmpty()
        val p = p0.copy(autoEpgUrl = autoEpg)
        db.playlists().update(p)
        return p
    }

    private fun classify(e: M3uEntry): Int {
        val type = e.attr("tvg-type", "type").lowercase()
        val path = e.url.substringBefore('?').lowercase()
        return when {
            type == "movie" || type == "movies" || type == "vod" -> Kind.MOVIE
            type == "series" || type == "show" -> Kind.SERIES
            type == "live" || type == "stream" -> Kind.LIVE
            path.contains("/series/") -> Kind.SERIES
            path.contains("/movie/") || path.contains("/movies/") -> Kind.MOVIE
            VOD_EXT.any { path.endsWith(it) } -> if (SERIES_EP.containsMatchIn(e.name)) Kind.SERIES else Kind.MOVIE
            else -> Kind.LIVE
        }
    }

    private suspend fun saveCategories(pid: Long, kind: Int, cats: List<Pair<String, String>>, usedIds: Set<String>) {
        val existing = db.categories().forPlaylist(pid, kind).associateBy { it.categoryId }
        val list = ArrayList<CategoryEntity>(cats.size + 4)
        val seen = HashSet<String>()
        fun add(id: String, name: String) {
            if (!seen.add(id)) return
            val old = existing[id]
            // Adult categories start hidden; anything the user has toggled keeps their choice.
            val hidden = old?.hidden ?: ADULT.containsMatchIn(name)
            list += CategoryEntity(pid, kind, id, name.ifBlank { "Untitled" }, list.size, hidden)
        }
        cats.forEach { (id, name) -> add(id, name) }
        usedIds.filter { it !in seen }.forEach { add(it, if (it.isBlank()) "Uncategorised" else "Other") }
        db.categories().replace(pid, kind, list)
    }

    // ---------------------------------------------------------------- guide

    private fun epgUrls(p: PlaylistEntity): List<String> {
        val urls = LinkedHashSet<String>()
        p.epgUrl.split('\n', ',').map { it.trim() }.filter { it.startsWith("http", true) }.forEach { urls += it }
        if (p.type == PlaylistType.XTREAM) urls += store.xtream(p).xmltvUrl()
        p.autoEpgUrl.split(',').map { it.trim() }.filter { it.startsWith("http", true) }.forEach { urls += it }
        return urls.toList()
    }

    private suspend fun syncEpg(p: PlaylistEntity) {
        val urls = epgUrls(p)
        // Only channels you can actually see (visible groups + favourites). Big providers carry tens of
        // thousands of channels; importing all of them is slow and fills the stick. Anything else gets
        // per-channel guide data on demand when it scrolls into view.
        val favourites = db.channels().favourites().filter { it.playlistId == p.id }
        val channels = (db.channels().allVisible().filter { it.playlistId == p.id } + favourites).distinctBy { it.key }
        if (urls.isEmpty() || channels.isEmpty()) return
        val startedAt = now()
        val maxEnd = startedAt + prefs.epgFutureDays.value * DAY
        val pastDays = HashMap<String, Int>()
        val shifts = HashMap<String, Int>()
        for (c in channels) if (c.epgId.isNotEmpty()) {
            pastDays.merge(c.epgId, c.catchupDays.coerceIn(1, 14), ::maxOf)
            if (c.tvgShiftMin != 0) shifts[c.epgId] = c.tvgShiftMin
        }
        val gen = p.epgGeneration + 1
        db.programmes().deleteOtherGenerations(p.id, p.epgGeneration) // clear leftovers of an interrupted import
        val filled = HashSet<String>()
        val epgUpdates = HashMap<String, String>()
        var total = 0
        var anyOk = false
        var lastError: Exception? = null
        val ua = store.userAgent(p.id)

        for ((i, url) in urls.withIndex()) {
            val label = if (urls.size > 1) "Updating TV guide (${i + 1}/${urls.size})…" else "Updating TV guide…"
            report(p.id, label, 0f)
            try {
                val request = Request.Builder().url(url).header("User-Agent", ua).build()
                val response = http.newCall(request).await().requireOk()
                val stored = withContext(Background.dispatcher) {
                    response.use { r ->
                        val body = r.body ?: error("Empty guide")
                        val length = body.contentLength()
                        val counting = CountingInputStream(body.byteStream())
                        val sink = GuideSink(p, gen, channels, filled, epgUpdates, pastDays, shifts, startedAt, maxEnd) {
                            if (length > 0) report(p.id, label, (counting.count.toFloat() / length).coerceIn(0f, 1f))
                            else report(p.id, "$label ${NumberFormat.getIntegerInstance(Locale.UK).format(it)} programmes")
                        }
                        XmltvParser.parse(counting, sink)
                        sink.finish()
                        filled += sink.loadedIds
                        sink.stored
                    }
                }
                total += stored
                anyOk = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "guide $url failed", e)
                lastError = e
            }
        }

        if (!anyOk) {
            db.programmes().deleteOtherGenerations(p.id, p.epgGeneration)
            throw lastError ?: IllegalStateException("No guide data")
        }
        for ((key, epgId) in epgUpdates) db.channels().setEpgId(key, epgId)
        val fresh = db.playlists().get(p.id) ?: return
        db.playlists().update(fresh.copy(epgGeneration = gen, lastEpgSync = now(), programmeCount = total))
        db.programmes().deleteOtherGenerations(p.id, gen)
        epgVersion.update { it + 1 }
        if (lastError != null) report(p.id, "Some guide sources failed: ${friendlyError(lastError!!)}", error = true)
    }

    /** Receives XMLTV callbacks, maps feed channel ids onto ours (by id, case, or name) and batches inserts. */
    private inner class GuideSink(
        private val p: PlaylistEntity,
        private val gen: Int,
        private val channels: List<ChannelEntity>,
        private val alreadyFilled: Set<String>,
        private val epgUpdates: HashMap<String, String>,
        private val pastDays: HashMap<String, Int>,
        private val shifts: Map<String, Int>,
        private val nowMs: Long,
        private val maxEnd: Long,
        private val onProgress: (Int) -> Unit,
    ) : XmltvSink {
        private val feedIds = HashSet<String>()
        /** lower-cased feed id -> every spelling of it in the feed */
        private val feedLower = HashMap<String, MutableList<String>>()
        /** our epg id -> the feed id its programmes are coming from (the first spelling that has any) */
        private val sourceOf = HashMap<String, String>()
        private val nameToFeed = HashMap<String, String>()
        /** feed id -> our stored epg ids */
        private val targets = HashMap<String, MutableList<String>>()
        private var resolved = false
        private val buffer = ArrayList<ProgrammeEntity>(BATCH)
        val loadedIds = HashSet<String>()
        var stored = 0
        private var lastReport = 0L

        override fun channel(id: String, names: List<String>, icon: String) {
            feedIds += id
            feedLower.getOrPut(id.lowercase(Locale.ROOT)) { ArrayList(1) }.let { if (id !in it) it += id }
            names.forEach { n -> nameToFeed.putIfAbsent(normaliseChannelName(n), id) }
        }

        private fun target(feedId: String, ourId: String) {
            if (ourId in alreadyFilled) return
            val list = targets.getOrPut(feedId) { ArrayList(1) }
            if (ourId !in list) list += ourId
        }

        private fun resolve() {
            resolved = true
            if (feedIds.isEmpty()) {
                // Feed without <channel> entries: trust the ids as-is.
                channels.forEach { c -> if (c.epgId.isNotEmpty()) target(c.epgId, c.epgId) }
                return
            }
            for (c in channels) {
                val id = c.epgId
                // Feeds often list an id twice, differing only in capitals, with the programmes under one
                // spelling only: listen to every spelling, and keep whichever delivers first.
                val direct = if (id.isEmpty()) emptyList() else guideIdSpellings(id, feedLower)
                if (direct.isNotEmpty()) {
                    direct.forEach { target(it, id) }
                    continue
                }
                val byName = nameToFeed[normaliseChannelName(c.name)]
                    ?: id.takeIf { it.isNotEmpty() }?.let { nameToFeed[normaliseChannelName(it.substringBeforeLast('.'))] }
                    ?: continue
                if (id.isEmpty()) {
                    epgUpdates[c.key] = byName
                    pastDays.merge(byName, c.catchupDays.coerceIn(1, 14), ::maxOf)
                    target(byName, byName)
                } else {
                    target(byName, id)
                }
            }
        }

        override fun wants(channelId: String): Boolean {
            if (!resolved) resolve()
            return targets.containsKey(channelId)
        }

        override fun programme(prog: XmltvProgramme) {
            val ours = targets[prog.channel] ?: return
            for (epgId in ours) {
                val source = sourceOf[epgId]
                if (source != null && source != prog.channel) continue
                val shift = (shifts[epgId] ?: 0) * 60_000L
                val start = prog.start + shift
                val end = prog.stop + shift
                val past = (pastDays[epgId] ?: 1) * DAY
                if (end < nowMs - past || start > maxEnd) continue
                if (source == null) sourceOf[epgId] = prog.channel
                buffer += ProgrammeEntity(
                    playlistId = p.id,
                    generation = gen,
                    epgId = epgId,
                    startMs = start,
                    endMs = end,
                    title = prog.title.ifEmpty { "Untitled" },
                    subtitle = prog.subtitle,
                    description = prog.description,
                    category = prog.category,
                    episode = prog.episode,
                    icon = prog.icon,
                )
                loadedIds += epgId
                if (buffer.size >= BATCH) flush()
            }
        }

        private fun flush() {
            if (buffer.isEmpty()) return
            db.runInTransaction { db.programmes().insertAllBlocking(buffer) }
            stored += buffer.size
            buffer.clear()
            val t = System.currentTimeMillis()
            if (t - lastReport > 400) {
                lastReport = t
                onProgress(stored)
            }
        }

        fun finish() = flush()
    }

    /** Collects rows from a streaming parser and writes them in batches (each batch its own transaction). */
    private class Batcher<T>(private val size: Int = 1500, private val write: (List<T>) -> Unit) {
        private val buffer = ArrayList<T>(size)
        var count = 0
            private set

        fun add(item: T) {
            buffer += item
            count++
            if (buffer.size >= size) flush()
        }

        fun flush() {
            if (buffer.isEmpty()) return
            write(buffer)
            buffer.clear()
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        @Volatile var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    companion object {
        private const val TAG = "NimbusSync"
        private const val BATCH = 2000
        private val ADULT = Regex("""(?i)(xxx|adult|porn|\b18\s*\+|erotic|for adults)""")
        private val VOD_EXT = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv")
        private val XC_URL = Regex("""^https?://[^/]+/(live/)?[^/]+/[^/]+/\d+(\.[a-z0-9]+)?$""", RegexOption.IGNORE_CASE)
        val SERIES_EP = Regex("""^(.*?)[\s._\-]*[Ss](\d{1,2})[\s._\-]*[Ee](\d{1,3})(.*)$""")
    }
}

/**
 * The feed ids that can carry the guide for a channel whose guide id is [id]: the exact spelling first, then
 * the ones that differ only in capitals. [feedByLower] maps each lower-cased feed id to its spellings.
 */
internal fun guideIdSpellings(id: String, feedByLower: Map<String, List<String>>): List<String> {
    val all = feedByLower[id.lowercase(Locale.ROOT)] ?: return emptyList()
    return if (id in all) listOf(id) + all.filter { it != id } else all
}
