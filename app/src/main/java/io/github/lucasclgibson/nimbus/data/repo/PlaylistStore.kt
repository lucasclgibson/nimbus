package io.github.lucasclgibson.nimbus.data.repo

import io.github.lucasclgibson.nimbus.core.DEFAULT_USER_AGENT
import io.github.lucasclgibson.nimbus.data.db.NimbusDb
import io.github.lucasclgibson.nimbus.data.db.PlaylistEntity
import io.github.lucasclgibson.nimbus.data.db.PlaylistType
import io.github.lucasclgibson.nimbus.data.source.XtreamApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.time.ZoneId

/** In-memory mirror of the playlists table so hot paths (URL building, EPG generation) stay synchronous. */
class PlaylistStore(private val db: NimbusDb, private val http: OkHttpClient, scope: CoroutineScope) {
    private val _all = MutableStateFlow<List<PlaylistEntity>>(emptyList())
    val all: StateFlow<List<PlaylistEntity>> = _all
    private val loaded = CompletableDeferred<Unit>()
    private val apis = HashMap<Long, Pair<PlaylistEntity, XtreamApi>>()

    init {
        scope.launch {
            db.playlists().observeAll().collect {
                _all.value = it
                loaded.complete(Unit)
            }
        }
    }

    suspend fun ready() = loaded.await()

    operator fun get(id: Long): PlaylistEntity? = _all.value.firstOrNull { it.id == id }

    fun userAgent(id: Long): String = get(id)?.userAgent?.ifBlank { null } ?: DEFAULT_USER_AGENT

    fun xtream(p: PlaylistEntity): XtreamApi = synchronized(apis) {
        val cached = apis[p.id]
        if (cached != null && cached.first.url == p.url && cached.first.username == p.username &&
            cached.first.password == p.password && cached.first.userAgent == p.userAgent
        ) return cached.second
        val api = XtreamApi(http, p.url, p.username, p.password, p.userAgent.ifBlank { DEFAULT_USER_AGENT })
        apis[p.id] = p to api
        api
    }

    fun xtream(id: Long): XtreamApi? = get(id)?.takeIf { it.type == PlaylistType.XTREAM }?.let { xtream(it) }

    fun serverZone(id: Long): ZoneId =
        get(id)?.serverTimezone?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            ?: ZoneId.systemDefault()
}
