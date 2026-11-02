package io.github.lucasclgibson.nimbus.data.repo

import android.util.Log
import io.github.lucasclgibson.nimbus.core.cleanTitle
import io.github.lucasclgibson.nimbus.core.displayChannelName
import io.github.lucasclgibson.nimbus.core.isSeparatorChannel
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.data.Prefs
import io.github.lucasclgibson.nimbus.data.db.ChannelEntity
import io.github.lucasclgibson.nimbus.data.db.MovieEntity
import io.github.lucasclgibson.nimbus.data.db.NimbusDb
import io.github.lucasclgibson.nimbus.data.db.PlaylistEntity
import io.github.lucasclgibson.nimbus.data.db.ProgrammeEntity
import io.github.lucasclgibson.nimbus.data.db.SeriesEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

data class OnTv(val programme: ProgrammeEntity, val channel: ChannelEntity)

/** One copy of a title (providers list films/series several times: 4K, HD, other languages…). */
data class Version(val key: String, val label: String, val category: String, val current: Boolean)

/**
 * Search-as-you-type over channel, film and series names and guide titles, using SQLite FTS4
 * (word-prefix matching with prefix indexes, so every key press answers in milliseconds even with
 * 280k+ titles). The index lives in its own tables outside Room and is rebuilt in the background after
 * each catalogue or guide refresh. Until it's ready, search falls back to plain LIKE scans.
 */
class SearchIndex(
    private val db: NimbusDb,
    private val prefs: Prefs,
    private val sync: SyncManager,
    private val vod: VodRepository,
    private val scope: CoroutineScope,
    private val isPlaying: () -> Boolean = { false },
) {
    private val lock = Mutex()
    @Volatile private var catalogueReady = false
    private val _guideReady = MutableStateFlow(false)
    /** Whether guide titles are searchable: not until the index has opened after launch, nor while it's rebuilt. */
    val guideReady: StateFlow<Boolean> = _guideReady
    private val sql get() = db.openHelper.writableDatabase

    fun start() {
        scope.launch(io.github.lucasclgibson.nimbus.core.Background.dispatcher) {
            val ok = lock.withLock { runCatching { create() }.onFailure { Log.w(TAG, "FTS unavailable", it) }.isSuccess }
            if (!ok) return@launch
            // Indexes still current from last time are usable straight away (Home's live sport row comes from the
            // guide's). Building is heavy, so it waits for the UI to settle after launch first.
            if (!markCurrent()) delay(4_000)
            refresh()
            // Rebuild after refreshes. Hiding groups also bumps contentVersion; the stamps filter that out.
            launch { sync.contentVersion.drop(1).debounce(3_000).collect { refresh() } }
            launch { sync.epgVersion.drop(1).debounce(3_000).collect { refresh() } }
        }
    }

    // ------------------------------------------------------------------ building

    private fun create() {
        val prefix = "prefix=\"1,2,3\""
        for (t in listOf(FTS_CHANNELS, FTS_MOVIES, FTS_SERIES)) {
            createFts(
                "CREATE VIRTUAL TABLE IF NOT EXISTS $t USING fts4(name, ref, notindexed=ref, $prefix, tokenize=unicode61)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS $t USING fts4(name, ref, notindexed=ref, $prefix)",
            )
        }
        createFts(
            "CREATE VIRTUAL TABLE IF NOT EXISTS $FTS_GUIDE USING fts4(title, prefix=\"2,3\", tokenize=unicode61)",
            "CREATE VIRTUAL TABLE IF NOT EXISTS $FTS_GUIDE USING fts4(title, prefix=\"2,3\")",
        )
    }

    private fun createFts(preferred: String, fallback: String) {
        try {
            sql.execSQL(preferred)
        } catch (e: Exception) {
            sql.execSQL(fallback) // no unicode61 tokenizer: ASCII-only case folding
        }
    }

    private fun catalogueStamp(playlists: List<PlaylistEntity>) = playlists.joinToString(",") { "${it.id}:${it.lastSync}" }

    private fun guideStamp(playlists: List<PlaylistEntity>) =
        playlists.joinToString(",") { "${it.id}:${it.lastEpgSync}:${it.epgGeneration}" }

    /** Whether the guide index matches these playlists' guide: it's rebuilt a few seconds after each refresh. */
    private fun guideCurrent(playlists: List<PlaylistEntity>) =
        _guideReady.value && prefs.searchGuideStamp.value == guideStamp(playlists)

    /** Marks the indexes that match the current catalogue and guide as ready. True if both do. */
    private suspend fun markCurrent(): Boolean = try {
        val playlists = db.playlists().all()
        val catalogue = prefs.searchCatalogueStamp.value == catalogueStamp(playlists)
        val guide = prefs.searchGuideStamp.value == guideStamp(playlists)
        if (catalogue) catalogueReady = true
        if (guide) _guideReady.value = true
        catalogue && guide
    } catch (e: Exception) {
        false
    }

    private suspend fun refresh() {
        try {
            // The guide first, whatever's playing: its index points at programme rows, which every guide refresh
            // replaces, so an old one finds nothing. Copying the titles within SQLite is quick.
            lock.withLock {
                val guide = guideStamp(db.playlists().all())
                if (prefs.searchGuideStamp.value != guide) {
                    _guideReady.value = false
                    val t0 = System.currentTimeMillis()
                    fillGuide()
                    prefs.searchGuideStamp.value = guide
                    Log.i(TAG, "guide indexed in ${System.currentTimeMillis() - t0}ms")
                }
                _guideReady.value = true
            }
            // Indexing 280k titles is a minute or two of CPU: do it when nothing is playing. Until then the last
            // index still finds nearly everything.
            if (prefs.searchCatalogueStamp.value != catalogueStamp(db.playlists().all())) {
                while (isPlaying()) delay(30_000)
            }
            lock.withLock {
                val catalogue = catalogueStamp(db.playlists().all())
                if (prefs.searchCatalogueStamp.value != catalogue) {
                    catalogueReady = false
                    val t0 = System.currentTimeMillis()
                    fill(FTS_CHANNELS, "channels") { displayChannelName(it) }
                    fill(FTS_MOVIES, "movies") { cleanTitle(it) }
                    fill(FTS_SERIES, "series") { cleanTitle(it) }
                    prefs.searchCatalogueStamp.value = catalogue
                    Log.i(TAG, "catalogue indexed in ${System.currentTimeMillis() - t0}ms")
                }
                catalogueReady = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "indexing failed", e)
        }
    }

    /** Copy names in chunks (each its own transaction) so other writes are never held up for long. */
    private fun fill(fts: String, table: String, clean: (String) -> String) {
        val db = sql
        db.execSQL("DELETE FROM $fts")
        val insert = db.compileStatement("INSERT INTO $fts(name, ref) VALUES (?, ?)")
        var last = -1L
        while (true) {
            val chunk = ArrayList<Triple<Long, String, String>>(CHUNK)
            db.query("SELECT rowid, name, `key` FROM $table WHERE rowid > ? ORDER BY rowid LIMIT $CHUNK", arrayOf<Any?>(last)).use { c ->
                while (c.moveToNext()) chunk += Triple(c.getLong(0), c.getString(1).orEmpty(), c.getString(2))
            }
            if (chunk.isEmpty()) break
            db.beginTransaction()
            try {
                for ((_, name, key) in chunk) {
                    insert.bindString(1, clean(name))
                    insert.bindString(2, key)
                    insert.executeInsert()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            last = chunk.last().first
        }
    }

    private fun fillGuide() {
        val db = sql
        db.execSQL("DELETE FROM $FTS_GUIDE")
        var last = -1L
        while (true) {
            var upTo = -1L
            db.query(
                "SELECT max(id) FROM (SELECT id FROM programmes WHERE id > ? ORDER BY id LIMIT 20000)", arrayOf<Any?>(last),
            ).use { c -> if (c.moveToFirst() && !c.isNull(0)) upTo = c.getLong(0) }
            if (upTo < 0) break
            db.execSQL("INSERT INTO $FTS_GUIDE(docid, title) SELECT id, title FROM programmes WHERE id > ? AND id <= ?", arrayOf<Any?>(last, upTo))
            last = upTo
        }
    }

    // ------------------------------------------------------------------ querying

    private fun tokens(q: String): List<String> =
        q.lowercase(Locale.ROOT).split(NON_WORD).filter { it.isNotEmpty() }.take(6)

    private fun refs(fts: String, match: String, limit: Int): List<String> =
        sql.query("SELECT ref FROM $fts WHERE $fts MATCH ? LIMIT $limit", arrayOf<Any?>(match)).use { c ->
            val out = ArrayList<String>()
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    /** Titles *starting* with what was typed first, then any word starting with it. */
    private fun matchRefs(fts: String, q: String, limit: Int): List<String> {
        val t = tokens(q)
        if (t.isEmpty()) return emptyList()
        val body = t.joinToString(" ") { "$it*" }
        val out = LinkedHashSet<String>()
        runCatching { out += refs(fts, "^$body", limit) }
        if (out.size < limit) out += refs(fts, body, limit)
        return out.toList()
    }

    private fun score(cleanLower: String, q: String): Int = when {
        cleanLower == q -> 0
        cleanLower.startsWith(q) -> 1
        else -> 2
    }

    private class Ranked<T>(val item: T, val title: String, val score: Int, val pos: Int, val tie: Long)

    /** Clean each title once, then rank: exact > starts with > word match; FTS order; newest. */
    private fun <T> rank(
        rows: List<T>, keys: List<String>, q: String,
        name: (T) -> String, key: (T) -> String, tie: (T) -> Long,
    ): List<Ranked<T>> {
        val pos = HashMap<String, Int>(keys.size * 2).also { m -> keys.forEachIndexed { i, k -> m[k] = i } }
        return rows.map { r ->
            val t = name(r).lowercase(Locale.ROOT)
            Ranked(r, t, score(t, q), pos[key(r)] ?: Int.MAX_VALUE, tie(r))
        }.sortedWith(compareBy<Ranked<T>>({ it.score }, { it.pos }, { it.tie }))
    }

    suspend fun channels(query: String): List<ChannelEntity> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase(Locale.ROOT)
        val rows = if (catalogueReady) {
            val keys = matchRefs(FTS_CHANNELS, q, 120)
            rank(keys.chunked(900).flatMap { db.channels().byKeysVisible(it) }, keys, q,
                { displayChannelName(it.name) }, { it.key }, { it.sortOrder.toLong() }).map { it.item }
        } else {
            if (q.length < 2) db.channels().searchPrefix(q, 40) else db.channels().search(q, 40)
        }
        rows.filterNot { isSeparatorChannel(it.name) }.take(40)
    }

    suspend fun movies(query: String): List<MovieEntity> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase(Locale.ROOT)
        if (!catalogueReady) return@withContext vod.searchMovies(q)
        val keys = matchRefs(FTS_MOVIES, q, if (q.length < 3) 120 else 240)
        rank(keys.chunked(900).flatMap { db.movies().byKeysVisible(it) }, keys, q,
            { cleanTitle(it.name) }, { it.key }, { -it.added })
            .distinctBy { it.title + "|" + it.item.year }
            .take(40).map { it.item }
    }

    suspend fun series(query: String): List<SeriesEntity> = withContext(Dispatchers.IO) {
        val q = query.trim().lowercase(Locale.ROOT)
        if (!catalogueReady) return@withContext vod.searchSeries(q)
        val keys = matchRefs(FTS_SERIES, q, if (q.length < 3) 100 else 200)
        rank(keys.chunked(900).flatMap { db.series().byKeysVisible(it) }, keys, q,
            { cleanTitle(it.name) }, { it.key }, { -it.added })
            .distinctBy { it.title + "|" + it.item.year }
            .take(40).map { it.item }
    }

    /** Upcoming and on-now programmes whose title matches, each on a channel you can see. */
    suspend fun onTv(query: String, playlists: List<io.github.lucasclgibson.nimbus.data.db.PlaylistEntity>): List<OnTv> = withContext(Dispatchers.IO) {
        val t = tokens(query)
        if (t.isEmpty()) return@withContext emptyList()
        val from = now()
        val progs: List<ProgrammeEntity> = if (guideCurrent(playlists)) {
            val ids = sql.query(
                "SELECT docid FROM $FTS_GUIDE WHERE $FTS_GUIDE MATCH ? LIMIT 4000", arrayOf<Any?>(t.joinToString(" ") { "$it*" }),
            ).use { c ->
                val out = ArrayList<Long>()
                while (c.moveToNext()) out += c.getLong(0)
                out
            }
            ids.chunked(900).flatMap { db.programmes().byIdsFrom(it, from) }
        } else {
            playlists.flatMap { p -> db.programmes().search(p.id, p.epgGeneration, query.trim(), from, 60) }
        }
        val current = playlists.associate { it.id to it.epgGeneration }
        val live = progs.filter { current[it.playlistId] == it.generation }
            .sortedBy { it.startMs }
            // Regional variants (BBC One London/Yorks/…) carry the same showing: keep one per title + time.
            .distinctBy { it.title.lowercase(Locale.ROOT) + "@" + it.startMs }
            .take(60)
        // One query per playlist to find the channels, instead of one per programme.
        val channels = HashMap<String, ChannelEntity>()
        live.groupBy { it.playlistId }.forEach { (pid, list) ->
            list.map { it.epgId }.distinct().chunked(900).forEach { ids ->
                db.channels().visibleForEpgIds(pid, ids).forEach { c -> channels.putIfAbsent("$pid|${c.epgId}", c) }
            }
        }
        live.mapNotNull { p -> channels["${p.playlistId}|${p.epgId}"]?.let { OnTv(p, it) } }.take(30)
    }

    /** Other copies of the same film or series, labelled by quality/language, current one first. */
    suspend fun versions(kind: Int, key: String): List<Version> = withContext(Dispatchers.IO) {
        data class Copy(val key: String, val name: String, val year: Int, val pid: Long, val cat: String)
        val me: Copy = when (kind) {
            io.github.lucasclgibson.nimbus.data.db.Kind.MOVIE -> db.movies().get(key)?.let { Copy(it.key, it.name, it.year, it.playlistId, it.categoryId) }
            else -> db.series().get(key)?.let { Copy(it.key, it.name, it.year, it.playlistId, it.categoryId) }
        } ?: return@withContext emptyList()
        val title = cleanTitle(me.name).lowercase(Locale.ROOT)
        val t = tokens(title)
        if (t.isEmpty()) return@withContext emptyList()
        val fts = if (kind == io.github.lucasclgibson.nimbus.data.db.Kind.MOVIE) FTS_MOVIES else FTS_SERIES
        val keys = if (catalogueReady) runCatching { refs(fts, "^" + t.joinToString(" "), 120) }.getOrDefault(emptyList()) else emptyList()
        val copies: List<Copy> = when (kind) {
            io.github.lucasclgibson.nimbus.data.db.Kind.MOVIE -> (if (keys.isNotEmpty()) keys.chunked(900).flatMap { db.movies().byKeysVisible(it) } else db.movies().search(title, 120))
                .map { Copy(it.key, it.name, it.year, it.playlistId, it.categoryId) }
            else -> (if (keys.isNotEmpty()) keys.chunked(900).flatMap { db.series().byKeysVisible(it) } else db.series().search(title, 120))
                .map { Copy(it.key, it.name, it.year, it.playlistId, it.categoryId) }
        }.filter {
            cleanTitle(it.name).lowercase(Locale.ROOT) == title && (me.year == 0 || it.year == 0 || it.year == me.year)
        }
        val all = (listOf(me) + copies).distinctBy { it.key }
        if (all.size < 2) return@withContext emptyList()
        val catNames = all.map { it.pid }.distinct().flatMap { pid ->
            db.categories().forPlaylist(pid, kind).map { "$pid:${it.categoryId}" to it.name }
        }.toMap()
        all.map { c ->
            val cat = catNames["${c.pid}:${c.cat}"].orEmpty()
            Version(c.key, versionLabel(c.name, cat), cat.trim(), c.key == key)
        }
    }

    private fun versionLabel(name: String, category: String): String {
        val t = "$name $category".uppercase(Locale.ROOT)
        val parts = ArrayList<String>()
        when {
            Regex("""\b(4K|UHD|2160P?)\b""").containsMatchIn(t) -> parts += "4K"
            Regex("""\b(FHD|1080P?)\b""").containsMatchIn(t) -> parts += "Full HD"
            Regex("""\b(HD|720P?)\b""").containsMatchIn(t) -> parts += "HD"
            Regex("""\bSD\b""").containsMatchIn(t) -> parts += "SD"
        }
        if (Regex("""DOLBY\s*VISION|\bDV\b""").containsMatchIn(t)) parts += "Dolby Vision"
        else if (Regex("""\bHDR""").containsMatchIn(t)) parts += "HDR"
        if (Regex("""\b(HEVC|H\.?265|X265)\b""").containsMatchIn(t)) parts += "HEVC"
        if (Regex("""ATMOS""").containsMatchIn(t)) parts += "Atmos"
        if (Regex("""MULTI[- ]?SUB""").containsMatchIn(t)) parts += "Multi-sub"
        Regex("""^\s*[\[(]?([A-Z]{2,3})[\])]?\s*[|:\-]""").find(name.uppercase(Locale.ROOT))?.groupValues?.get(1)
            ?.takeIf { it !in setOf("4K", "HD", "SD", "UHD", "FHD", "TOP", "NEW", "VIP") }
            ?.let { parts += it }
        return parts.joinToString(" · ").ifEmpty { "Standard" }
    }

    /**
     * Live sport on right now: programmes whose title starts with "Live", on channels you can see.
     * Null while the guide isn't searchable, as for a few seconds after each guide refresh.
     */
    suspend fun liveSport(playlists: List<PlaylistEntity>): List<OnTv>? = withContext(Dispatchers.IO) {
        if (!guideCurrent(playlists)) return@withContext null
        val t = now()
        val ids = sql.query("SELECT docid FROM $FTS_GUIDE WHERE $FTS_GUIDE MATCH ? LIMIT 8000", arrayOf<Any?>("^live*")).use { c ->
            val out = ArrayList<Long>()
            while (c.moveToNext()) out += c.getLong(0)
            out
        }
        val current = playlists.associate { it.id to it.epgGeneration }
        val on = ids.chunked(900).flatMap { db.programmes().byIdsFrom(it, t) }
            .filter { it.startMs <= t && current[it.playlistId] == it.generation }
            .distinctBy { it.title.lowercase(Locale.ROOT) + "@" + it.startMs }
        val channels = HashMap<String, ChannelEntity>()
        on.groupBy { it.playlistId }.forEach { (pid, list) ->
            list.map { it.epgId }.distinct().chunked(900).forEach { chunk ->
                db.channels().visibleForEpgIds(pid, chunk).forEach { c -> channels.putIfAbsent("$pid|${c.epgId}", c) }
            }
        }
        on.mapNotNull { p -> channels["${p.playlistId}|${p.epgId}"]?.let { OnTv(p, it) } }
            .sortedBy { it.channel.number }
            .take(20)
    }

    companion object {
        private const val TAG = "NimbusSearch"
        private const val CHUNK = 4000
        private const val FTS_CHANNELS = "fts_channels"
        private const val FTS_MOVIES = "fts_movies"
        private const val FTS_SERIES = "fts_series"
        private const val FTS_GUIDE = "fts_guide"
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    }
}
