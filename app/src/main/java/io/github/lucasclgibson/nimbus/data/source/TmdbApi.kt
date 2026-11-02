package io.github.lucasclgibson.nimbus.data.source

import io.github.lucasclgibson.nimbus.core.await
import io.github.lucasclgibson.nimbus.core.requireOk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class CastMember(val name: String, val role: String, val photo: String)

data class Review(val author: String, val rating: Double, val content: String, val date: String)

data class TmdbInfo(
    val id: String,
    val title: String,
    val tagline: String,
    val overview: String,
    val voteAverage: Double,
    val voteCount: Int,
    val runtimeMin: Int,
    val genres: List<String>,
    val year: Int,
    val certification: String,
    val backdrop: String,
    val poster: String,
    val cast: List<CastMember>,
    val directors: List<String>,
    val reviews: List<Review>,
    val trailerKey: String,
)

/** The Movie Database. Optional: only used when the user has entered an API key. */
class TmdbApi(private val http: OkHttpClient, private val key: String) {

    private fun request(path: String, params: String = ""): Request {
        val bearer = key.length > 40 // v4 read access tokens are long JWTs, v3 keys are 32 hex chars
        val url = "https://api.themoviedb.org/3/$path?language=en-GB$params" + if (bearer) "" else "&api_key=$key"
        return Request.Builder().url(url).apply {
            if (bearer) header("Authorization", "Bearer $key")
            header("Accept", "application/json")
        }.build()
    }

    suspend fun getJson(path: String, params: String = ""): String {
        val r = http.newCall(request(path, params)).await().requireOk()
        return withContext(Dispatchers.IO) { r.use { it.body?.string().orEmpty() } }
    }

    suspend fun searchId(tv: Boolean, title: String, year: Int): String? {
        val q = URLEncoder.encode(title, "UTF-8")
        val yearParam = when {
            year <= 0 -> ""
            tv -> "&first_air_date_year=$year"
            else -> "&year=$year"
        }
        val json = JSONObject(getJson(if (tv) "search/tv" else "search/movie", "&query=$q$yearParam"))
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) {
            return if (year > 0) searchId(tv, title, 0) else null
        }
        return results.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.toString()
    }

    suspend fun detailsJson(tv: Boolean, id: String): String {
        val append = if (tv) "credits,reviews,videos,content_ratings" else "credits,reviews,videos,release_dates"
        return getJson(if (tv) "tv/$id" else "movie/$id", "&append_to_response=$append")
    }

    companion object {
        const val IMG = "https://image.tmdb.org/t/p/"

        fun parse(json: String, tv: Boolean): TmdbInfo {
            val o = JSONObject(json)
            val genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty()
            val credits = o.optJSONObject("credits")
            val cast = credits?.optJSONArray("cast")?.let { a ->
                (0 until minOf(a.length(), 16)).map { i ->
                    val c = a.getJSONObject(i)
                    CastMember(
                        c.optString("name"),
                        c.optString("character"),
                        c.optString("profile_path").takeIf { it.isNotBlank() && it != "null" }?.let { "${IMG}w185$it" }.orEmpty(),
                    )
                }
            }.orEmpty()
            val directors = if (tv) {
                o.optJSONArray("created_by")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } }.orEmpty()
            } else {
                credits?.optJSONArray("crew")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("job") == "Director" }.map { it.optString("name") }
                }.orEmpty()
            }
            val reviews = o.optJSONObject("reviews")?.optJSONArray("results")?.let { a ->
                (0 until minOf(a.length(), 6)).map { i ->
                    val r = a.getJSONObject(i)
                    Review(
                        author = r.optString("author"),
                        rating = r.optJSONObject("author_details")?.optDouble("rating", 0.0)?.takeIf { !it.isNaN() } ?: 0.0,
                        content = r.optString("content")
                            .replace(Regex("<[^>]+>"), "")
                            .replace("**", "")
                            .replace(Regex("""(?<![\w])_([^_]+)_(?![\w])"""), "$1")
                            .replace("\r", "")
                            .trim(),
                        date = r.optString("created_at").take(10),
                    )
                }
            }.orEmpty()
            val trailer = o.optJSONObject("videos")?.optJSONArray("results")?.let { a ->
                val vids = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("site") == "YouTube" }
                (vids.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
                    ?: vids.firstOrNull { it.optString("type") == "Trailer" }
                    ?: vids.firstOrNull())?.optString("key")
            }.orEmpty()
            val certification = if (tv) {
                o.optJSONObject("content_ratings")?.optJSONArray("results")?.let { a ->
                    val list = (0 until a.length()).map { a.getJSONObject(it) }
                    (list.firstOrNull { it.optString("iso_3166_1") == "GB" } ?: list.firstOrNull { it.optString("iso_3166_1") == "US" })
                        ?.optString("rating")
                }.orEmpty()
            } else {
                o.optJSONObject("release_dates")?.optJSONArray("results")?.let { a ->
                    val list = (0 until a.length()).map { a.getJSONObject(it) }
                    val pick = list.firstOrNull { it.optString("iso_3166_1") == "GB" } ?: list.firstOrNull { it.optString("iso_3166_1") == "US" }
                    pick?.optJSONArray("release_dates")?.let { d ->
                        (0 until d.length()).map { d.getJSONObject(it).optString("certification") }.firstOrNull { it.isNotBlank() }
                    }
                }.orEmpty()
            }
            val date = if (tv) o.optString("first_air_date") else o.optString("release_date")
            val runtime = if (tv) {
                o.optJSONArray("episode_run_time")?.let { a -> if (a.length() > 0) a.optInt(0) else 0 } ?: 0
            } else o.optInt("runtime")
            fun img(field: String, size: String) =
                o.optString(field).takeIf { it.isNotBlank() && it != "null" }?.let { "$IMG$size$it" }.orEmpty()
            return TmdbInfo(
                id = o.optInt("id").toString(),
                title = if (tv) o.optString("name") else o.optString("title"),
                tagline = o.optString("tagline"),
                overview = o.optString("overview"),
                voteAverage = o.optDouble("vote_average", 0.0),
                voteCount = o.optInt("vote_count"),
                runtimeMin = runtime,
                genres = genres,
                year = date.take(4).toIntOrNull() ?: 0,
                certification = certification,
                backdrop = img("backdrop_path", "w1280"),
                poster = img("poster_path", "w342"),
                cast = cast,
                directors = directors,
                reviews = reviews,
                trailerKey = trailer,
            )
        }
    }
}
