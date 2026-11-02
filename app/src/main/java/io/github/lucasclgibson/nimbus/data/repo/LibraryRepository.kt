package io.github.lucasclgibson.nimbus.data.repo

import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.data.db.ChannelEntity
import io.github.lucasclgibson.nimbus.data.db.FavouriteEntity
import io.github.lucasclgibson.nimbus.data.db.HistoryEntity
import io.github.lucasclgibson.nimbus.data.db.Kind
import io.github.lucasclgibson.nimbus.data.db.NimbusDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Favourites and watch history. */
class LibraryRepository(private val db: NimbusDb, scope: CoroutineScope) {

    val favourites: StateFlow<Set<String>> = db.library().observeFavourites()
        .map { list -> list.mapTo(HashSet()) { "${it.kind}:${it.key}" } }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun isFavourite(kind: Int, key: String) = "$kind:$key" in favourites.value

    suspend fun setFavourite(kind: Int, key: String, on: Boolean) {
        if (on) db.library().addFavourite(FavouriteEntity(kind, key, now()))
        else db.library().removeFavourite(kind, key)
    }

    suspend fun toggleFavourite(kind: Int, key: String): Boolean {
        val on = !isFavourite(kind, key)
        setFavourite(kind, key, on)
        return on
    }

    /** One entry per film / per series (latest episode), most recent first. */
    val continueWatching: StateFlow<List<HistoryEntity>> = db.library().observeInProgress()
        .map { list ->
            val seen = HashSet<String>()
            list.filter { h -> seen.add(if (h.kind == Kind.EPISODE) "s:${h.parentKey}" else h.key) }.take(20)
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val recentChannels: StateFlow<List<HistoryEntity>> = db.library().observeRecentChannels(20)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun observe(key: String): Flow<HistoryEntity?> = db.library().observeHistory(key)

    fun observeSeries(seriesKey: String): Flow<List<HistoryEntity>> = db.library().observeForParent(seriesKey)

    suspend fun get(key: String): HistoryEntity? = db.library().history(key)

    suspend fun channelWatched(c: ChannelEntity) {
        db.library().upsertHistory(
            HistoryEntity(
                key = historyKey(Kind.LIVE, c.key), kind = Kind.LIVE, itemKey = c.key, parentKey = "",
                title = c.name, subtitle = "", image = c.logo, backdrop = "", positionMs = 0, durationMs = 0,
                updatedAt = now(), finished = false,
            )
        )
    }

    suspend fun saveProgress(h: HistoryEntity) = db.library().upsertHistory(h)

    suspend fun clear(key: String) = db.library().deleteHistory(key)

    /**
     * Take a film or a whole series off Continue watching. Progress is kept (episode ticks, resume points)
     * and it comes back the next time it's watched.
     */
    suspend fun removeFromContinueWatching(h: HistoryEntity) =
        db.library().dismissInProgress(h.key, if (h.kind == Kind.EPISODE) h.parentKey else "", now())

    companion object {
        fun historyKey(kind: Int, itemKey: String) = "$kind:$itemKey"
    }
}
