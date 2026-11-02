package io.github.lucasclgibson.nimbus.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object PlaylistType {
    const val XTREAM = "xtream"
    const val M3U = "m3u"
}

object Kind {
    const val LIVE = 0
    const val MOVIE = 1
    const val SERIES = 2
    const val EPISODE = 3
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    /** Xtream server base URL, or the M3U URL. */
    val url: String,
    val username: String = "",
    val password: String = "",
    /** Extra / override XMLTV URLs, one per line. */
    val epgUrl: String = "",
    /** XMLTV URL(s) advertised by the playlist itself (M3U url-tvg header). */
    val autoEpgUrl: String = "",
    val userAgent: String = "",
    /** Xtream live output: "ts" or "m3u8". */
    val outputFormat: String = "ts",
    val serverTimezone: String = "",
    val expiresAt: Long = 0,
    val maxConnections: Int = 0,
    val lastSync: Long = 0,
    val lastEpgSync: Long = 0,
    val epgGeneration: Int = 0,
    val sortOrder: Int = 0,
    val status: String = "",
    val channelCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    val programmeCount: Int = 0,
)

@Entity(tableName = "categories", primaryKeys = ["playlistId", "kind", "categoryId"])
data class CategoryEntity(
    val playlistId: Long,
    val kind: Int,
    val categoryId: String,
    val name: String,
    val sortOrder: Int,
    val hidden: Boolean = false,
)

@Entity(
    tableName = "channels",
    indices = [Index("playlistId", "categoryId", "sortOrder"), Index("playlistId", "sortOrder"), Index("playlistId", "epgId")],
)
data class ChannelEntity(
    /** Stable across refreshes: "<playlistId>:<streamId or name>". Favourites and history key off this. */
    @PrimaryKey val key: String,
    val playlistId: Long,
    val streamId: String,
    val number: Int,
    val name: String,
    val logo: String,
    val categoryId: String,
    val epgId: String,
    /** Direct stream URL (M3U). Empty for Xtream, whose URLs are built from credentials. */
    val url: String,
    /** "", "xc", "default", "append", "shift", "flussonic" */
    val catchupType: String,
    val catchupDays: Int,
    val catchupSource: String,
    val tvgShiftMin: Int,
    val userAgent: String,
    val referrer: String,
    val sortOrder: Int,
    /** Time of the refresh that last saw this row; rows a successful refresh didn't see are swept. */
    @ColumnInfo(defaultValue = "0") val syncStamp: Long = 0,
)

@Entity(
    tableName = "programmes",
    indices = [Index("playlistId", "generation", "epgId", "startMs")],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val generation: Int,
    val epgId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val subtitle: String,
    val description: String,
    val category: String,
    val episode: String,
    val icon: String,
)

/** Lightweight row used by the guide grid (descriptions are loaded on focus). */
data class ProgrammeLite(
    val id: Long,
    val epgId: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
)

@Entity(
    tableName = "movies",
    indices = [Index("playlistId", "categoryId", "sortOrder"), Index("added"), Index("rating")],
)
data class MovieEntity(
    @PrimaryKey val key: String,
    val playlistId: Long,
    val streamId: String,
    val name: String,
    val year: Int,
    val poster: String,
    val rating: Double,
    val categoryId: String,
    val added: Long,
    val ext: String,
    val url: String,
    val tmdbId: String,
    val sortOrder: Int,
    /** Time of the refresh that last saw this row; rows a successful refresh didn't see are swept. */
    @ColumnInfo(defaultValue = "0") val syncStamp: Long = 0,
)

@Entity(
    tableName = "series",
    indices = [Index("playlistId", "categoryId", "sortOrder"), Index("added"), Index("rating")],
)
data class SeriesEntity(
    @PrimaryKey val key: String,
    val playlistId: Long,
    val seriesId: String,
    val name: String,
    val year: Int,
    val poster: String,
    val backdrop: String,
    val rating: Double,
    val categoryId: String,
    val plot: String,
    val genre: String,
    val added: Long,
    val tmdbId: String,
    val sortOrder: Int,
    /** Time of the refresh that last saw this row; rows a successful refresh didn't see are swept. */
    @ColumnInfo(defaultValue = "0") val syncStamp: Long = 0,
)

/** Episodes of M3U-sourced series (Xtream episodes are fetched on demand). */
@Entity(tableName = "episodes", indices = [Index("seriesKey")])
data class EpisodeEntity(
    @PrimaryKey val key: String,
    val seriesKey: String,
    val playlistId: Long,
    val season: Int,
    val episode: Int,
    val title: String,
    val url: String,
    val image: String,
    /** Time of the refresh that last saw this row; rows a successful refresh didn't see are swept. */
    @ColumnInfo(defaultValue = "0") val syncStamp: Long = 0,
)

@Entity(tableName = "favourites", primaryKeys = ["kind", "key"])
data class FavouriteEntity(
    val kind: Int,
    val key: String,
    val addedAt: Long,
)

/** Watch history: drives Continue Watching, resume points and recent channels. */
@Entity(tableName = "history", indices = [Index("kind", "updatedAt"), Index("parentKey")])
data class HistoryEntity(
    /** "<kind>:<itemKey>" */
    @PrimaryKey val key: String,
    val kind: Int,
    val itemKey: String,
    /** Series key for episodes. */
    val parentKey: String,
    val title: String,
    val subtitle: String,
    val image: String,
    val backdrop: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val finished: Boolean,
    val season: Int = 0,
    val episode: Int = 0,
    /** Removed from Continue watching (progress kept; watching again brings it back). */
    @ColumnInfo(defaultValue = "0") val dismissed: Boolean = false,
)

@Entity(tableName = "detail_cache")
data class DetailCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long,
)
