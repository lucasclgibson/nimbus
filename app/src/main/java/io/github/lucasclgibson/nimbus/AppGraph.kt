package io.github.lucasclgibson.nimbus

import android.content.Context
import io.github.lucasclgibson.nimbus.core.buildHttpClient
import io.github.lucasclgibson.nimbus.data.Prefs
import io.github.lucasclgibson.nimbus.data.db.NimbusDb
import io.github.lucasclgibson.nimbus.data.db.PlaylistEntity
import io.github.lucasclgibson.nimbus.data.repo.LibraryRepository
import io.github.lucasclgibson.nimbus.data.repo.LiveRepository
import io.github.lucasclgibson.nimbus.data.hub.HouseholdSync
import io.github.lucasclgibson.nimbus.data.hub.HubClient
import io.github.lucasclgibson.nimbus.data.hub.Updater
import io.github.lucasclgibson.nimbus.data.repo.Parental
import io.github.lucasclgibson.nimbus.data.repo.PlaylistStore
import io.github.lucasclgibson.nimbus.data.repo.Reminders
import io.github.lucasclgibson.nimbus.data.repo.SearchIndex
import io.github.lucasclgibson.nimbus.data.repo.SyncManager
import io.github.lucasclgibson.nimbus.data.repo.VodRepository
import io.github.lucasclgibson.nimbus.phone.SetupServer
import io.github.lucasclgibson.nimbus.player.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Hand-wired dependencies; the app is small enough not to need a DI framework. */
class AppGraph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http = buildHttpClient()
    val db = NimbusDb.create(context)
    val prefs = Prefs(context)
    val store = PlaylistStore(db, http, scope)
    val sync = SyncManager(db, http, store, prefs, scope)
    val live = LiveRepository(db, store)
    val vod = VodRepository(db, http, store, prefs)
    val library = LibraryRepository(db, scope)
    val playback = PlaybackManager(context, live, library, prefs, scope)
    val search = SearchIndex(db, prefs, sync, vod, scope) { playback.session.value != null }.also { it.start() }
    val reminders = Reminders(prefs, scope)
    val parental = Parental(prefs, db, sync, scope)
    val hub = HubClient(context, http, prefs)
    val updater = Updater(context, hub)
    val household = HouseholdSync(context, hub, db, prefs, reminders, sync, scope) { deletePlaylist(it) }.also { it.start() }
    val setupServer = SetupServer(scope)

    suspend fun addPlaylist(p: PlaylistEntity): Long {
        val order = db.playlists().all().size
        val id = db.playlists().insert(p.copy(sortOrder = order))
        sync.sync(id)
        return id
    }

    suspend fun updatePlaylist(p: PlaylistEntity, resync: Boolean) {
        db.playlists().update(p)
        if (resync) sync.sync(p.id)
    }

    suspend fun deletePlaylist(id: Long) {
        playback.stop()
        db.channels().deleteFor(id)
        db.movies().deleteFor(id)
        db.series().deleteFor(id)
        db.episodes().deleteFor(id)
        db.programmes().deleteFor(id)
        for (kind in 0..2) db.categories().deleteFor(id, kind)
        db.playlists().delete(id)
        sync.contentVersion.value++
        sync.epgVersion.value++
    }
}
