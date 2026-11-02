package io.github.lucasclgibson.nimbus.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class KeyEpg(val key: String, val epgId: String)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY sortOrder, id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Update
    suspend fun update(p: PlaylistEntity)

    @Query("UPDATE playlists SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY playlistId, sortOrder")
    fun observe(kind: Int): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY playlistId, sortOrder")
    suspend fun all(kind: Int): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE playlistId = :pid AND kind = :kind")
    suspend fun forPlaylist(pid: Long, kind: Int): List<CategoryEntity>

    @Query("UPDATE categories SET hidden = :hidden WHERE playlistId = :pid AND kind = :kind AND categoryId = :cat")
    suspend fun setHidden(pid: Long, kind: Int, cat: String, hidden: Boolean)

    @Query("DELETE FROM categories WHERE playlistId = :pid AND kind = :kind")
    suspend fun deleteFor(pid: Long, kind: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<CategoryEntity>)

    @Transaction
    suspend fun replace(pid: Long, kind: Int, list: List<CategoryEntity>) {
        deleteFor(pid, kind)
        insertAll(list)
    }
}

@Dao
interface ChannelDao {
    @Query(
        """SELECT c.* FROM channels c JOIN categories g
           ON g.playlistId = c.playlistId AND g.kind = 0 AND g.categoryId = c.categoryId
           WHERE g.hidden = 0 ORDER BY c.playlistId, c.sortOrder"""
    )
    suspend fun allVisible(): List<ChannelEntity>

    @Query(
        """SELECT c.* FROM channels c JOIN categories g
           ON g.playlistId = c.playlistId AND g.kind = 0 AND g.categoryId = c.categoryId
           WHERE g.hidden = 0 ORDER BY c.playlistId, c.sortOrder LIMIT :limit"""
    )
    suspend fun allVisibleLimited(limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND categoryId = :cat ORDER BY sortOrder")
    suspend fun inCategory(pid: Long, cat: String): List<ChannelEntity>

    @Query("SELECT c.* FROM channels c JOIN favourites f ON f.kind = 0 AND f.`key` = c.`key` ORDER BY f.addedAt")
    suspend fun favourites(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE `key` = :key")
    suspend fun get(key: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE `key` IN (:keys)")
    suspend fun getMany(keys: List<String>): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :pid")
    suspend fun forPlaylist(pid: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE playlistId = :pid AND epgId = :epgId ORDER BY sortOrder LIMIT 1")
    suspend fun forEpg(pid: Long, epgId: String): ChannelEntity?

    // Search: substring match, best matches first (name starts with it, then a word starts with it).
    @Query(
        """SELECT c.* FROM channels c JOIN categories g
           ON g.playlistId = c.playlistId AND g.kind = 0 AND g.categoryId = c.categoryId
           WHERE g.hidden = 0 AND c.name LIKE '%' || :q || '%'
           ORDER BY CASE WHEN c.name LIKE :q || '%' THEN 0 WHEN c.name LIKE '% ' || :q || '%' THEN 1 ELSE 2 END, c.playlistId, c.sortOrder LIMIT :limit"""
    )
    suspend fun search(q: String, limit: Int): List<ChannelEntity>

    /** One-letter searches: only names or words starting with it. */
    @Query(
        """SELECT c.* FROM channels c JOIN categories g
           ON g.playlistId = c.playlistId AND g.kind = 0 AND g.categoryId = c.categoryId
           WHERE g.hidden = 0 AND (c.name LIKE :q || '%' OR c.name LIKE '% ' || :q || '%')
           ORDER BY CASE WHEN c.name LIKE :q || '%' THEN 0 WHEN c.name LIKE '% ' || :q || '%' THEN 1 ELSE 2 END, c.playlistId, c.sortOrder LIMIT :limit"""
    )
    suspend fun searchPrefix(q: String, limit: Int): List<ChannelEntity>

    @Query(
        """SELECT c.* FROM channels c JOIN categories g
           ON g.playlistId = c.playlistId AND g.kind = 0 AND g.categoryId = c.categoryId
           WHERE g.hidden = 0 AND c.`key` IN (:keys)"""
    )
    suspend fun byKeysVisible(keys: List<String>): List<ChannelEntity>

    @Query(
        """SELECT c.* FROM channels c JOIN categories g
           ON g.playlistId = c.playlistId AND g.kind = 0 AND g.categoryId = c.categoryId
           WHERE g.hidden = 0 AND c.playlistId = :pid AND c.epgId IN (:epgIds) ORDER BY c.sortOrder"""
    )
    suspend fun visibleForEpgIds(pid: Long, epgIds: List<String>): List<ChannelEntity>

    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :pid")
    suspend fun count(pid: Long): Int

    @Query("DELETE FROM channels WHERE playlistId = :pid")
    suspend fun deleteFor(pid: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<ChannelEntity>)

    @Query("UPDATE channels SET epgId = :epgId WHERE `key` = :key")
    suspend fun setEpgId(key: String, epgId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(list: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun sweep(pid: Long, stamp: Long)

    @Query("SELECT `key`, epgId FROM channels WHERE playlistId = :pid AND epgId != ''")
    suspend fun epgIds(pid: Long): List<KeyEpg>

    @Transaction
    suspend fun replace(pid: Long, list: List<ChannelEntity>) {
        deleteFor(pid)
        list.chunked(2000).forEach { insertAll(it) }
    }
}

@Dao
interface ProgrammeDao {
    @Query(
        """SELECT id, epgId, startMs, endMs, title FROM programmes
           WHERE playlistId = :pid AND generation = :gen AND epgId IN (:ids) AND endMs > :from AND startMs < :to
           ORDER BY epgId, startMs"""
    )
    suspend fun range(pid: Long, gen: Int, ids: List<String>, from: Long, to: Long): List<ProgrammeLite>

    @Query(
        """SELECT id, epgId, startMs, endMs, title FROM programmes
           WHERE playlistId = :pid AND generation = :gen AND epgId IN (:ids) AND endMs > :at AND startMs <= :until
           ORDER BY epgId, startMs"""
    )
    suspend fun nowAndNext(pid: Long, gen: Int, ids: List<String>, at: Long, until: Long): List<ProgrammeLite>

    @Query("SELECT * FROM programmes WHERE id = :id")
    suspend fun get(id: Long): ProgrammeEntity?

    @Query("SELECT * FROM programmes WHERE id IN (:ids) AND endMs > :from")
    suspend fun byIdsFrom(ids: List<Long>, from: Long): List<ProgrammeEntity>

    @Query(
        """SELECT * FROM programmes WHERE playlistId = :pid AND generation = :gen AND title LIKE '%' || :q || '%'
           AND endMs > :from ORDER BY startMs LIMIT :limit"""
    )
    suspend fun search(pid: Long, gen: Int, q: String, from: Long, limit: Int): List<ProgrammeEntity>

    @Insert
    suspend fun insertAll(list: List<ProgrammeEntity>)

    /** Called from inside the streaming XMLTV parser (already on a background thread). */
    @Insert
    fun insertAllBlocking(list: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE playlistId = :pid AND generation != :keep")
    suspend fun deleteOtherGenerations(pid: Long, keep: Int)

    @Query("DELETE FROM programmes WHERE playlistId = :pid AND generation = :gen AND epgId = :epgId")
    suspend fun deleteChannel(pid: Long, gen: Int, epgId: String)

    @Query("DELETE FROM programmes WHERE playlistId = :pid")
    suspend fun deleteFor(pid: Long)

    @Query("SELECT COUNT(*) FROM programmes WHERE playlistId = :pid AND generation = :gen")
    suspend fun count(pid: Long, gen: Int): Int

    @Query("SELECT COUNT(*) FROM programmes WHERE playlistId = :pid AND generation = :gen AND epgId = :epgId")
    suspend fun countFor(pid: Long, gen: Int, epgId: String): Int
}

@Dao
interface MovieDao {
    @Query("SELECT * FROM movies WHERE playlistId = :pid AND categoryId = :cat ORDER BY sortOrder")
    suspend fun inCategory(pid: Long, cat: String): List<MovieEntity>

    @Query(
        """SELECT m.* FROM movies m JOIN categories g ON g.playlistId = m.playlistId AND g.kind = 1 AND g.categoryId = m.categoryId
           WHERE g.hidden = 0 ORDER BY m.added DESC LIMIT :limit"""
    )
    suspend fun recent(limit: Int): List<MovieEntity>

    @Query(
        """SELECT m.* FROM movies m JOIN categories g ON g.playlistId = m.playlistId AND g.kind = 1 AND g.categoryId = m.categoryId
           WHERE g.hidden = 0 AND m.rating > 0 AND m.rating < 9.9 AND m.added > :since ORDER BY m.rating DESC LIMIT :limit"""
    )
    suspend fun topRated(since: Long, limit: Int): List<MovieEntity>

    @Query(
        """SELECT m.* FROM movies m JOIN categories g ON g.playlistId = m.playlistId AND g.kind = 1 AND g.categoryId = m.categoryId
           WHERE g.hidden = 0 AND m.name LIKE '%' || :q || '%'
           ORDER BY CASE WHEN m.name LIKE :q || '%' THEN 0 WHEN m.name LIKE '% ' || :q || '%' THEN 1 ELSE 2 END, m.added DESC LIMIT :limit"""
    )
    suspend fun search(q: String, limit: Int): List<MovieEntity>

    @Query(
        """SELECT m.* FROM movies m JOIN categories g ON g.playlistId = m.playlistId AND g.kind = 1 AND g.categoryId = m.categoryId
           WHERE g.hidden = 0 AND (m.name LIKE :q || '%' OR m.name LIKE '% ' || :q || '%')
           ORDER BY CASE WHEN m.name LIKE :q || '%' THEN 0 WHEN m.name LIKE '% ' || :q || '%' THEN 1 ELSE 2 END, m.added DESC LIMIT :limit"""
    )
    suspend fun searchPrefix(q: String, limit: Int): List<MovieEntity>

    @Query(
        """SELECT m.* FROM movies m JOIN categories g ON g.playlistId = m.playlistId AND g.kind = 1 AND g.categoryId = m.categoryId
           WHERE g.hidden = 0 AND m.`key` IN (:keys)"""
    )
    suspend fun byKeysVisible(keys: List<String>): List<MovieEntity>

    @Query("SELECT m.* FROM movies m JOIN favourites f ON f.kind = 1 AND f.`key` = m.`key` ORDER BY f.addedAt DESC")
    suspend fun favourites(): List<MovieEntity>

    @Query("SELECT * FROM movies WHERE `key` = :key")
    suspend fun get(key: String): MovieEntity?

    @Query("SELECT COUNT(*) FROM movies WHERE playlistId = :pid")
    suspend fun count(pid: Long): Int

    @Query("DELETE FROM movies WHERE playlistId = :pid")
    suspend fun deleteFor(pid: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(list: List<MovieEntity>)

    @Query("DELETE FROM movies WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun sweep(pid: Long, stamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<MovieEntity>)

    @Transaction
    suspend fun replace(pid: Long, list: List<MovieEntity>) {
        deleteFor(pid)
        list.chunked(2000).forEach { insertAll(it) }
    }
}

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series WHERE playlistId = :pid AND categoryId = :cat ORDER BY sortOrder")
    suspend fun inCategory(pid: Long, cat: String): List<SeriesEntity>

    @Query(
        """SELECT s.* FROM series s JOIN categories g ON g.playlistId = s.playlistId AND g.kind = 2 AND g.categoryId = s.categoryId
           WHERE g.hidden = 0 ORDER BY s.added DESC LIMIT :limit"""
    )
    suspend fun recent(limit: Int): List<SeriesEntity>

    @Query(
        """SELECT s.* FROM series s JOIN categories g ON g.playlistId = s.playlistId AND g.kind = 2 AND g.categoryId = s.categoryId
           WHERE g.hidden = 0 AND s.rating > 0 AND s.rating < 9.9 AND s.added > :since ORDER BY s.rating DESC LIMIT :limit"""
    )
    suspend fun topRated(since: Long, limit: Int): List<SeriesEntity>

    @Query(
        """SELECT s.* FROM series s JOIN categories g ON g.playlistId = s.playlistId AND g.kind = 2 AND g.categoryId = s.categoryId
           WHERE g.hidden = 0 AND s.name LIKE '%' || :q || '%'
           ORDER BY CASE WHEN s.name LIKE :q || '%' THEN 0 WHEN s.name LIKE '% ' || :q || '%' THEN 1 ELSE 2 END, s.added DESC LIMIT :limit"""
    )
    suspend fun search(q: String, limit: Int): List<SeriesEntity>

    @Query(
        """SELECT s.* FROM series s JOIN categories g ON g.playlistId = s.playlistId AND g.kind = 2 AND g.categoryId = s.categoryId
           WHERE g.hidden = 0 AND (s.name LIKE :q || '%' OR s.name LIKE '% ' || :q || '%')
           ORDER BY CASE WHEN s.name LIKE :q || '%' THEN 0 WHEN s.name LIKE '% ' || :q || '%' THEN 1 ELSE 2 END, s.added DESC LIMIT :limit"""
    )
    suspend fun searchPrefix(q: String, limit: Int): List<SeriesEntity>

    @Query(
        """SELECT s.* FROM series s JOIN categories g ON g.playlistId = s.playlistId AND g.kind = 2 AND g.categoryId = s.categoryId
           WHERE g.hidden = 0 AND s.`key` IN (:keys)"""
    )
    suspend fun byKeysVisible(keys: List<String>): List<SeriesEntity>

    @Query("SELECT s.* FROM series s JOIN favourites f ON f.kind = 2 AND f.`key` = s.`key` ORDER BY f.addedAt DESC")
    suspend fun favourites(): List<SeriesEntity>

    @Query("SELECT * FROM series WHERE `key` = :key")
    suspend fun get(key: String): SeriesEntity?

    @Query("SELECT COUNT(*) FROM series WHERE playlistId = :pid")
    suspend fun count(pid: Long): Int

    @Query("DELETE FROM series WHERE playlistId = :pid")
    suspend fun deleteFor(pid: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(list: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun sweep(pid: Long, stamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<SeriesEntity>)

    @Transaction
    suspend fun replace(pid: Long, list: List<SeriesEntity>) {
        deleteFor(pid)
        list.chunked(2000).forEach { insertAll(it) }
    }
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episodes WHERE seriesKey = :seriesKey ORDER BY season, episode")
    suspend fun forSeries(seriesKey: String): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE playlistId = :pid")
    suspend fun deleteFor(pid: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertBlocking(list: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE playlistId = :pid AND syncStamp != :stamp")
    suspend fun sweep(pid: Long, stamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<EpisodeEntity>)

    @Transaction
    suspend fun replace(pid: Long, list: List<EpisodeEntity>) {
        deleteFor(pid)
        list.chunked(2000).forEach { insertAll(it) }
    }
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM favourites")
    fun observeFavourites(): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourites")
    suspend fun allFavourites(): List<FavouriteEntity>

    @Query("SELECT * FROM history ORDER BY updatedAt DESC LIMIT 1500")
    suspend fun allHistory(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavourite(f: FavouriteEntity)

    @Query("DELETE FROM favourites WHERE kind = :kind AND `key` = :key")
    suspend fun removeFavourite(kind: Int, key: String)

    @Query("SELECT * FROM history WHERE kind IN (1, 3) AND finished = 0 AND dismissed = 0 AND positionMs > 20000 ORDER BY updatedAt DESC LIMIT 60")
    fun observeInProgress(): Flow<List<HistoryEntity>>

    /** Hide a film, or every unfinished episode of a series, from Continue watching. */
    @Query(
        "UPDATE history SET dismissed = 1, updatedAt = :at WHERE `key` = :key " +
            "OR (:parentKey != '' AND kind = 3 AND parentKey = :parentKey AND finished = 0)"
    )
    suspend fun dismissInProgress(key: String, parentKey: String, at: Long)

    @Query("SELECT * FROM history WHERE kind = 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentChannels(limit: Int): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE `key` = :key")
    suspend fun history(key: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE `key` = :key")
    fun observeHistory(key: String): Flow<HistoryEntity?>

    @Query("SELECT * FROM history WHERE parentKey = :parentKey ORDER BY updatedAt DESC")
    fun observeForParent(parentKey: String): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(h: HistoryEntity)

    @Query("DELETE FROM history WHERE `key` = :key")
    suspend fun deleteHistory(key: String)

    @Query("SELECT * FROM detail_cache WHERE `key` = :key")
    suspend fun cached(key: String): DetailCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCache(e: DetailCacheEntity)
}
