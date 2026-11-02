package io.github.lucasclgibson.nimbus.data.repo

import android.util.JsonReader
import android.util.Log
import io.github.lucasclgibson.nimbus.core.DAY
import io.github.lucasclgibson.nimbus.core.cleanTitle
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.core.yearFromTitle
import io.github.lucasclgibson.nimbus.data.Prefs
import io.github.lucasclgibson.nimbus.data.db.DetailCacheEntity
import io.github.lucasclgibson.nimbus.data.db.Kind
import io.github.lucasclgibson.nimbus.data.db.MovieEntity
import io.github.lucasclgibson.nimbus.data.db.NimbusDb
import io.github.lucasclgibson.nimbus.data.db.PlaylistType
import io.github.lucasclgibson.nimbus.data.db.SeriesEntity
import io.github.lucasclgibson.nimbus.data.source.CastMember
import io.github.lucasclgibson.nimbus.data.source.JMap
import io.github.lucasclgibson.nimbus.data.source.Review
import io.github.lucasclgibson.nimbus.data.source.TmdbApi
import io.github.lucasclgibson.nimbus.data.source.double
import io.github.lucasclgibson.nimbus.data.source.int
import io.github.lucasclgibson.nimbus.data.source.list
import io.github.lucasclgibson.nimbus.data.source.map
import io.github.lucasclgibson.nimbus.data.source.readAny
import io.github.lucasclgibson.nimbus.data.source.str
import io.github.lucasclgibson.nimbus.data.source.strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import java.io.StringReader

data class VodCategory(val id: String, val title: String)

data class Episode(
    val key: String,
    val season: Int,
    val number: Int,
    val title: String,
    val plot: String,
    val image: String,
    val durationMs: Long,
    val url: String,
    val airDate: String,
)

data class Season(val number: Int, val name: String, val episodes: List<Episode>)

data class VodDetails(
    val key: String,
    val kind: Int,
    val playlistId: Long,
    val title: String,
    val year: Int = 0,
    val poster: String = "",
    val backdrop: String = "",
    val plot: String = "",
    val tagline: String = "",
    val genres: String = "",
    val runtimeMin: Int = 0,
    val rating: Double = 0.0,
    val voteCount: Int = 0,
    val ratingSource: String = "",
    val certification: String = "",
    val directors: String = "",
    val castText: String = "",
    val cast: List<CastMember> = emptyList(),
    val reviews: List<Review> = emptyList(),
    val trailerKey: String = "",
    val tmdbId: String = "",
    val seasons: List<Season> = emptyList(),
    val streamUrl: String = "",
    val userAgent: String = "",
    val loading: Boolean = true,
)

class VodRepository(
    private val db: NimbusDb,
    private val http: OkHttpClient,
    private val store: PlaylistStore,
    private val prefs: Prefs,
) {
    suspend fun categories(kind: Int): List<VodCategory> {
        store.ready()
        val multi = store.all.value.size > 1
        val out = arrayListOf(VodCategory(RECENT, "Recently added"), VodCategory(MY_LIST, "My list"))
        db.categories().all(kind).filter { !it.hidden }.forEach { c ->
            val prefix = if (multi) store[c.playlistId]?.name?.let { "$it · " }.orEmpty() else ""
            out += VodCategory("${c.playlistId}:${c.categoryId}", prefix + c.name)
        }
        return out
    }

    suspend fun movies(catId: String): List<MovieEntity> = when (catId) {
        RECENT -> db.movies().recent(400)
        MY_LIST -> db.movies().favourites()
        else -> catId.substringBefore(':').toLongOrNull()?.let { db.movies().inCategory(it, catId.substringAfter(':')) }.orEmpty()
    }

    suspend fun series(catId: String): List<SeriesEntity> = when (catId) {
        RECENT -> db.series().recent(400)
        MY_LIST -> db.series().favourites()
        else -> catId.substringBefore(':').toLongOrNull()?.let { db.series().inCategory(it, catId.substringAfter(':')) }.orEmpty()
    }

    suspend fun recentMovies(limit: Int) = db.movies().recent(limit)
    suspend fun recentSeries(limit: Int) = db.series().recent(limit)
    suspend fun topMovies(limit: Int) = db.movies().topRated(now() - 3 * 365 * DAY, limit)
    suspend fun topSeries(limit: Int) = db.series().topRated(now() - 3 * 365 * DAY, limit)
    suspend fun movie(key: String) = db.movies().get(key)
    suspend fun seriesItem(key: String) = db.series().get(key)
    /** Ranked search; providers list the same title many times (4K/HD/languages), so show each once. */
    suspend fun searchMovies(q: String): List<MovieEntity> =
        (if (q.length < 2) db.movies().searchPrefix(q, 160) else db.movies().search(q, 160))
            .distinctBy { cleanTitle(it.name).lowercase() + "|" + it.year }.take(40)

    suspend fun searchSeries(q: String): List<SeriesEntity> =
        (if (q.length < 2) db.series().searchPrefix(q, 120) else db.series().search(q, 120))
            .distinctBy { cleanTitle(it.name).lowercase() + "|" + it.year }.take(40)

    fun movieUrl(m: MovieEntity): String {
        if (m.url.isNotEmpty()) return m.url
        val p = store[m.playlistId] ?: return ""
        return store.xtream(p).movieUrl(m.streamId, m.ext)
    }

    // ------------------------------------------------------------------ details

    fun movieDetails(key: String): Flow<VodDetails> = flow {
        store.ready()
        val m = db.movies().get(key) ?: return@flow
        var d = VodDetails(
            key = key, kind = Kind.MOVIE, playlistId = m.playlistId, title = cleanTitle(m.name), year = m.year,
            poster = m.poster, rating = m.rating, ratingSource = if (m.rating > 0) "Rating" else "", tmdbId = m.tmdbId,
            streamUrl = movieUrl(m), userAgent = store.userAgent(m.playlistId),
        )
        emit(d)
        val p = store[m.playlistId]
        if (p?.type == PlaylistType.XTREAM && m.streamId.isNotEmpty()) {
            val api = store.xtream(p)
            parse(cachedText("xt:vod:$key", 3 * DAY) { api.getText(api.apiUrl("get_vod_info", "&vod_id=${m.streamId}")) })?.let { root ->
                val info = root.map("info") ?: emptyMap()
                d = d.copy(
                    plot = info.str("plot").ifBlank { info.str("description") }.trim(),
                    genres = info.str("genre").trim(),
                    castText = info.str("cast").ifBlank { info.str("actors") }.trim(),
                    directors = info.str("director").trim(),
                    runtimeMin = (info.int("duration_secs") / 60).takeIf { it > 0 } ?: parseDuration(info.str("duration")),
                    backdrop = info.strings("backdrop_path").firstOrNull().orEmpty(),
                    poster = d.poster.ifBlank { info.str("movie_image").ifBlank { info.str("cover_big") } },
                    tmdbId = info.str("tmdb_id").ifBlank { d.tmdbId },
                    trailerKey = info.str("youtube_trailer").trim(),
                    certification = info.str("mpaa_rating").ifBlank { info.str("age") }.trim(),
                    year = d.year.takeIf { it > 0 } ?: info.str("releasedate").take(4).toIntOrNull() ?: 0,
                    rating = d.rating.takeIf { it > 0 } ?: info.double("rating").takeIf { it in 0.1..10.0 } ?: 0.0,
                )
                emit(d)
            }
        }
        d = enrichWithTmdb(d, tv = false)
        emit(d.copy(loading = false))
    }.flowOn(Dispatchers.IO)

    fun seriesDetails(key: String): Flow<VodDetails> = flow {
        store.ready()
        val s = db.series().get(key) ?: return@flow
        var d = VodDetails(
            key = key, kind = Kind.SERIES, playlistId = s.playlistId, title = cleanTitle(s.name), year = s.year,
            poster = s.poster, backdrop = s.backdrop, plot = s.plot, genres = s.genre, rating = s.rating,
            ratingSource = if (s.rating > 0) "Rating" else "", tmdbId = s.tmdbId, userAgent = store.userAgent(s.playlistId),
        )
        emit(d)
        val p = store[s.playlistId]
        if (p?.type == PlaylistType.XTREAM && s.seriesId.isNotEmpty()) {
            val api = store.xtream(p)
            parse(cachedText("xt:series:$key", 12 * 60 * 60 * 1000L) {
                api.getText(api.apiUrl("get_series_info", "&series_id=${s.seriesId}"))
            })?.let { root ->
                val info = root.map("info") ?: emptyMap()
                val seasonNames = root.list("seasons").mapNotNull { it as? Map<*, *> }.associate { m ->
                    @Suppress("UNCHECKED_CAST") val mm = m as JMap
                    mm.int("season_number") to mm.str("name")
                }
                val seasons = parseEpisodes(root, s.playlistId).map { (num, eps) ->
                    Season(num, seasonNames[num]?.takeIf { it.isNotBlank() } ?: "Season $num", eps.sortedBy { it.number })
                }.sortedBy { if (it.number == 0) Int.MAX_VALUE else it.number }
                d = d.copy(
                    plot = info.str("plot").ifBlank { d.plot }.trim(),
                    genres = info.str("genre").ifBlank { d.genres }.trim(),
                    castText = info.str("cast").trim(),
                    directors = info.str("director").trim(),
                    backdrop = info.strings("backdrop_path").firstOrNull() ?: d.backdrop,
                    poster = d.poster.ifBlank { info.str("cover") },
                    trailerKey = info.str("youtube_trailer").trim(),
                    tmdbId = info.str("tmdb").ifBlank { info.str("tmdb_id") }.ifBlank { d.tmdbId },
                    runtimeMin = info.int("episode_run_time"),
                    year = d.year.takeIf { it > 0 } ?: info.str("releaseDate").take(4).toIntOrNull() ?: 0,
                    seasons = seasons,
                )
                emit(d)
            }
        } else {
            val eps = db.episodes().forSeries(key)
            val seasons = eps.distinctBy { it.key }.groupBy { it.season }.map { (num, list) ->
                Season(num, "Season $num", list.map { e ->
                    Episode(e.key, e.season, e.episode, e.title, "", e.image, 0, e.url, "")
                })
            }.sortedBy { it.number }
            d = d.copy(seasons = seasons)
            emit(d)
        }
        d = enrichWithTmdb(d, tv = true)
        emit(d.copy(loading = false))
    }.flowOn(Dispatchers.IO)

    private fun parseEpisodes(root: JMap, playlistId: Long): List<Pair<Int, List<Episode>>> {
        val p = store[playlistId] ?: return emptyList()
        val api = store.xtream(p)
        val groups: List<Pair<Int?, List<Any?>>> = when (val raw = root["episodes"]) {
            is Map<*, *> -> raw.entries.map { (k, v) -> (k as? String)?.toIntOrNull() to ((v as? List<*>) ?: emptyList<Any?>()) }
            is List<*> -> raw.map { null to ((it as? List<*>) ?: emptyList<Any?>()) }
            else -> emptyList()
        }
        val bySeason = HashMap<Int, MutableList<Episode>>()
        for ((seasonKey, list) in groups) {
            for (item in list) {
                @Suppress("UNCHECKED_CAST") val m = item as? JMap ?: continue
                val id = m.str("id")
                if (id.isEmpty()) continue
                val info = m.map("info") ?: emptyMap()
                val season = m.int("season").takeIf { it > 0 } ?: info.int("season").takeIf { it > 0 } ?: seasonKey ?: 1
                val number = m.int("episode_num").takeIf { it > 0 } ?: (bySeason[season]?.size ?: 0) + 1
                val rawTitle = m.str("title")
                val title = EP_TITLE.find(rawTitle)?.groupValues?.get(1)?.trim()?.ifBlank { null }
                    ?: info.str("name").ifBlank { null }
                    ?: rawTitle.takeIf { !it.contains(Regex("(?i)S\\d+\\s*E\\d+")) }
                    ?: "Episode $number"
                bySeason.getOrPut(season) { ArrayList() } += Episode(
                    key = "$playlistId:e:$id",
                    season = season,
                    number = number,
                    title = title,
                    plot = info.str("plot").trim(),
                    image = info.str("movie_image").ifBlank { info.str("cover_big") },
                    durationMs = info.int("duration_secs") * 1000L,
                    url = api.episodeUrl(id, m.str("container_extension")),
                    airDate = info.str("releasedate").ifBlank { info.str("air_date") },
                )
            }
        }
        // Panels sometimes list an episode twice; duplicate keys would crash the episode list.
        val seen = HashSet<String>()
        return bySeason.entries.map { (season, eps) -> season to eps.filter { seen.add(it.key) } }.filter { it.second.isNotEmpty() }
    }

    private suspend fun enrichWithTmdb(d: VodDetails, tv: Boolean): VodDetails {
        val key = prefs.tmdbKey.value.trim()
        if (key.isEmpty()) return d
        return try {
            val api = TmdbApi(http, key)
            val kind = if (tv) "tv" else "movie"
            val id = d.tmdbId.filter { it.isDigit() }.ifEmpty {
                cachedText("tmdb:search:$kind:${d.title}:${d.year}", 30 * DAY) {
                    api.searchId(tv, d.title, d.year) ?: ""
                }.orEmpty()
            }
            if (id.isEmpty()) return d
            val json = cachedText("tmdb:$kind:$id", 14 * DAY) { api.detailsJson(tv, id) } ?: return d
            val t = TmdbApi.parse(json, tv)
            d.copy(
                tmdbId = id,
                // TMDB is asked for en-GB; provider text is often in another language, so TMDB wins when it has text.
                plot = t.overview.ifBlank { d.plot },
                tagline = t.tagline,
                genres = t.genres.joinToString(", ").ifBlank { d.genres },
                runtimeMin = t.runtimeMin.takeIf { it > 0 } ?: d.runtimeMin,
                rating = if (t.voteCount > 0) t.voteAverage else d.rating,
                voteCount = t.voteCount,
                ratingSource = if (t.voteCount > 0) "TMDB" else d.ratingSource,
                certification = t.certification.ifBlank { d.certification },
                year = d.year.takeIf { it > 0 } ?: t.year,
                backdrop = t.backdrop.ifBlank { d.backdrop },
                poster = d.poster.ifBlank { t.poster },
                cast = t.cast,
                directors = t.directors.joinToString(", ").ifBlank { d.directors },
                reviews = t.reviews,
                trailerKey = d.trailerKey.ifBlank { t.trailerKey },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NimbusVod", "tmdb failed", e)
            d
        }
    }

    /** Cache-first fetch; serves stale data if the network call fails. */
    private suspend fun cachedText(key: String, ttl: Long, fetch: suspend () -> String): String? {
        val cached = db.library().cached(key)
        if (cached != null && now() - cached.fetchedAt < ttl) return cached.json
        return try {
            val fresh = fetch()
            if (fresh.isNotBlank()) db.library().putCache(DetailCacheEntity(key, fresh, now()))
            fresh.ifBlank { cached?.json }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("NimbusVod", "fetch $key failed", e)
            cached?.json
        }
    }

    private fun parse(text: String?): JMap? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            JsonReader(StringReader(text)).use { r ->
                r.isLenient = true
                @Suppress("UNCHECKED_CAST")
                r.readAny() as? JMap
            }
        }.getOrNull()
    }

    private fun parseDuration(s: String): Int {
        val parts = s.split(':').mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 60 + parts[1]
            2 -> parts[0]
            else -> 0
        }
    }

    companion object {
        const val RECENT = "recent"
        const val MY_LIST = "mylist"
        private val EP_TITLE = Regex("""(?i)S\d{1,2}\s*E\d{1,3}\s*[-–:|.]?\s*(.*)$""")
    }
}

/** Tiny helper used by browse screens. */
fun MovieEntity.displayYear(): Int = if (year > 0) year else yearFromTitle(name)
