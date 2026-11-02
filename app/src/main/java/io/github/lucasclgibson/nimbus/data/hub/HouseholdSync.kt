package io.github.lucasclgibson.nimbus.data.hub

import android.content.Context
import android.util.Log
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.data.Prefs
import io.github.lucasclgibson.nimbus.data.db.FavouriteEntity
import io.github.lucasclgibson.nimbus.data.db.HistoryEntity
import io.github.lucasclgibson.nimbus.data.db.NimbusDb
import io.github.lucasclgibson.nimbus.data.db.PlaylistEntity
import io.github.lucasclgibson.nimbus.data.repo.Reminder
import io.github.lucasclgibson.nimbus.data.repo.Reminders
import io.github.lucasclgibson.nimbus.data.repo.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Keeps every TV in the house in step through the hub: playlists, favourites, resume points,
 * reminders, hidden groups and household settings. Each item carries a timestamp; deletions are
 * tombstones; the hub keeps the newest per item, so nothing a TV changes is lost.
 */
class HouseholdSync(
    context: Context,
    private val hub: HubClient,
    private val db: NimbusDb,
    private val prefs: Prefs,
    private val reminders: Reminders,
    private val sync: SyncManager,
    private val scope: CoroutineScope,
    private val deletePlaylist: suspend (Long) -> Unit,
) {
    private val lastFile = File(context.filesDir, "household-last.json")
    private val mutex = Mutex()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private val syncedPrefs = listOf(
        prefs.tmdbKey, prefs.pinHash, prefs.lockAdult, prefs.lockSettings, prefs.matchFrameRate, prefs.startup,
    )

    fun start() {
        scope.launch(Dispatchers.IO) {
            delay(8_000)
            while (isActive) {
                runOnce()
                delay(5 * 60_000L)
            }
        }
    }

    fun syncSoon() {
        scope.launch(Dispatchers.IO) { runOnce() }
    }

    // ------------------------------------------------------------------ ids

    private fun stableId(p: PlaylistEntity): String {
        val raw = "${p.type}|${p.url.trim().trimEnd('/').lowercase()}|${p.username.trim()}"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }

    /** "<playlistId>:rest" <-> "<stableId>:rest" */
    private class Keys(playlists: List<PlaylistEntity>, id: (PlaylistEntity) -> String) {
        private val toStable = playlists.associate { it.id.toString() to id(it) }
        private val toLocal = playlists.associate { id(it) to it.id.toString() }
        fun stable(local: String): String? {
            if (local.isEmpty()) return ""
            val pid = local.substringBefore(':')
            return toStable[pid]?.let { it + ":" + local.substringAfter(':') }
        }
        fun local(stable: String): String? {
            if (stable.isEmpty()) return ""
            val sid = stable.substringBefore(':')
            return toLocal[sid]?.let { it + ":" + stable.substringAfter(':') }
        }
        fun localPid(stableId: String): Long? = toLocal[stableId]?.toLongOrNull()
    }

    // ------------------------------------------------------------------ snapshot

    private suspend fun snapshot(keys: Keys, playlists: List<PlaylistEntity>, last: JSONObject): JSONObject {
        val out = JSONObject()
        fun section(name: String) = out.optJSONObject(name) ?: JSONObject().also { out.put(name, it) }
        fun lastT(name: String, id: String, value: JSONObject): Long {
            // Items without their own timestamp: keep the time we last synced them at if unchanged;
            // changed since the last sync -> now; first sync ever -> 0 so the hub's copy wins.
            val prev = last.optJSONObject(name)?.optJSONObject(id) ?: return if (last.length() == 0) 0 else now()
            val a = JSONObject(value.toString()).apply { remove("t") }
            val b = JSONObject(prev.toString()).apply { remove("t") }
            return if (a.toString() == b.toString()) prev.optLong("t") else now()
        }

        // playlists (credentials stay on the home network: hub + TVs only)
        for (p in playlists) {
            val sid = stableId(p)
            val v = JSONObject().put("name", p.name).put("type", p.type).put("url", p.url).put("username", p.username)
                .put("password", p.password).put("epgUrl", p.epgUrl).put("userAgent", p.userAgent).put("outputFormat", p.outputFormat)
            section("pl").put(sid, v.put("t", lastT("pl", sid, v)))
        }
        // favourites
        for (f in db.library().allFavourites()) {
            val k = keys.stable(f.key) ?: continue
            section("fav").put("${f.kind}|$k", JSONObject().put("t", f.addedAt).put("kind", f.kind).put("key", k))
        }
        // watch history / resume points
        for (h in db.library().allHistory()) {
            val item = keys.stable(h.itemKey) ?: continue
            val parent = keys.stable(h.parentKey) ?: continue
            section("hist").put(
                "${h.kind}|$item",
                JSONObject().put("t", h.updatedAt).put("kind", h.kind).put("item", item).put("parent", parent)
                    .put("title", h.title).put("subtitle", h.subtitle).put("image", h.image).put("backdrop", h.backdrop)
                    .put("pos", h.positionMs).put("dur", h.durationMs).put("fin", h.finished).put("dis", h.dismissed)
                    .put("season", h.season).put("episode", h.episode),
            )
        }
        // reminders
        for (r in reminders.list.value) {
            val ch = keys.stable(r.channelKey) ?: continue
            val id = "$ch@${r.startMs}"
            val v = JSONObject().put("ch", ch).put("name", r.channelName).put("title", r.title).put("start", r.startMs).put("end", r.endMs)
            section("rem").put(id, v.put("t", lastT("rem", id, v)))
        }
        // hidden groups (the set of hidden ones)
        for (kind in 0..2) {
            for (c in db.categories().all(kind)) {
                if (!c.hidden) continue
                val sid = playlists.firstOrNull { it.id == c.playlistId }?.let { stableId(it) } ?: continue
                val id = "$sid|$kind|${c.categoryId}"
                val v = JSONObject().put("hidden", true)
                section("hide").put(id, v.put("t", lastT("hide", id, v)))
            }
        }
        // household settings
        for (p in syncedPrefs) {
            val v = JSONObject().put("value", p.value.toString())
            section("pref").put(p.key, v.put("t", p.modifiedAt))
        }
        return out
    }

    // ------------------------------------------------------------------ run

    private suspend fun runOnce() = mutex.withLock {
        try {
            if (hub.base() == null) {
                _status.value = ""
                return@withLock
            }
            val playlists = db.playlists().all()
            val keys = Keys(playlists) { stableId(it) }
            val last = runCatching { JSONObject(lastFile.readText()) }.getOrDefault(JSONObject())
            val mine = snapshot(keys, playlists, last)

            // Outgoing: everything we have, plus tombstones for what disappeared since the last sync.
            val outgoing = JSONObject(mine.toString())
            for (name in last.keys()) {
                val prev = last.optJSONObject(name) ?: continue
                val cur = outgoing.optJSONObject(name) ?: JSONObject().also { outgoing.put(name, it) }
                for (id in prev.keys()) {
                    if (!cur.has(id) && !prev.getJSONObject(id).optBoolean("deleted")) {
                        cur.put(id, JSONObject().put("t", now()).put("deleted", true))
                    }
                }
            }
            val merged = hub.putJson("sync/home", outgoing) ?: run {
                _status.value = "Hub not reachable"
                return@withLock
            }
            val changed = apply(merged, mine, keys)
            // Remember what we now hold (tombstones excluded) for the next diff.
            val keep = JSONObject()
            for (name in merged.keys()) {
                val sec = merged.optJSONObject(name) ?: continue
                val kept = JSONObject()
                for (id in sec.keys()) {
                    val item = sec.getJSONObject(id)
                    if (!item.optBoolean("deleted")) kept.put(id, item)
                }
                keep.put(name, kept)
            }
            lastFile.writeText(keep.toString())
            _status.value = "In sync" + if (changed > 0) " · $changed change${if (changed == 1) "" else "s"} from other TVs" else ""
        } catch (e: Exception) {
            Log.w(HubClient.TAG, "sync failed", e)
            _status.value = "Sync failed: ${e.message}"
        }
    }

    /** Apply everything in [merged] that's newer than what this TV has. @return number of changes */
    private suspend fun apply(merged: JSONObject, mine: JSONObject, keys: Keys): Int = withContext(Dispatchers.IO) {
        var changes = 0
        var contentChanged = false
        fun localT(section: String, id: String): Long? = mine.optJSONObject(section)?.optJSONObject(id)?.optLong("t")

        // playlists first: everything else refers to them
        var added = false
        merged.optJSONObject("pl")?.let { sec ->
            for (sid in sec.keys()) {
                val item = sec.getJSONObject(sid)
                val localPid = keys.localPid(sid)
                val mineT = localT("pl", sid)
                if (item.optBoolean("deleted")) {
                    if (localPid != null && mineT != null && item.optLong("t") >= mineT) {
                        deletePlaylist(localPid)
                        changes++
                    }
                } else if (localPid == null) {
                    val id = db.playlists().insert(
                        PlaylistEntity(
                            name = item.optString("name"), type = item.optString("type"), url = item.optString("url"),
                            username = item.optString("username"), password = item.optString("password"),
                            epgUrl = item.optString("epgUrl"), userAgent = item.optString("userAgent"),
                            outputFormat = item.optString("outputFormat", "ts"),
                        )
                    )
                    sync.sync(id)
                    added = true
                    changes++
                } else if (mineT != null && item.optLong("t") > mineT) {
                    val p = db.playlists().get(localPid) ?: continue
                    val updated = p.copy(
                        name = item.optString("name", p.name), password = item.optString("password", p.password),
                        epgUrl = item.optString("epgUrl", p.epgUrl), userAgent = item.optString("userAgent", p.userAgent),
                        outputFormat = item.optString("outputFormat", p.outputFormat),
                    )
                    if (updated != p) {
                        db.playlists().update(updated)
                        if (updated.password != p.password || updated.epgUrl != p.epgUrl) sync.sync(localPid)
                        changes++
                    }
                }
            }
        }
        val k = if (added) Keys(db.playlists().all()) { stableId(it) } else keys

        merged.optJSONObject("fav")?.let { sec ->
            for (id in sec.keys()) {
                val item = sec.getJSONObject(id)
                val kind = id.substringBefore('|').toIntOrNull() ?: continue
                val local = k.local(id.substringAfter('|')) ?: continue
                val mineT = localT("fav", id)
                if (item.optBoolean("deleted")) {
                    if (mineT != null && item.optLong("t") >= mineT) {
                        db.library().removeFavourite(kind, local)
                        changes++
                    }
                } else if (mineT == null) {
                    db.library().addFavourite(FavouriteEntity(kind, local, item.optLong("t")))
                    changes++
                }
            }
        }

        merged.optJSONObject("hist")?.let { sec ->
            for (id in sec.keys()) {
                val item = sec.getJSONObject(id)
                val mineT = localT("hist", id)
                if (item.optBoolean("deleted")) {
                    if (mineT != null && item.optLong("t") >= mineT) {
                        val kind = id.substringBefore('|').toIntOrNull() ?: continue
                        val itemKey = k.local(id.substringAfter('|')) ?: continue
                        db.library().deleteHistory("$kind:$itemKey")
                        changes++
                    }
                    continue
                }
                if (mineT != null && mineT >= item.optLong("t")) continue
                val kind = item.optInt("kind")
                val itemKey = k.local(item.optString("item")) ?: continue
                val parent = k.local(item.optString("parent")) ?: continue
                db.library().upsertHistory(
                    HistoryEntity(
                        key = "$kind:$itemKey", kind = kind, itemKey = itemKey, parentKey = parent,
                        title = item.optString("title"), subtitle = item.optString("subtitle"), image = item.optString("image"),
                        backdrop = item.optString("backdrop"), positionMs = item.optLong("pos"), durationMs = item.optLong("dur"),
                        updatedAt = item.optLong("t"), finished = item.optBoolean("fin"),
                        season = item.optInt("season"), episode = item.optInt("episode"), dismissed = item.optBoolean("dis"),
                    )
                )
                changes++
            }
        }

        merged.optJSONObject("rem")?.let { sec ->
            for (id in sec.keys()) {
                val item = sec.getJSONObject(id)
                val mineT = localT("rem", id)
                if (item.optBoolean("deleted")) {
                    if (mineT != null && item.optLong("t") >= mineT) {
                        val ch = k.local(id.substringBefore('@')) ?: continue
                        val start = id.substringAfter('@').toLongOrNull() ?: continue
                        reminders.set(Reminder(ch, "", "", start, start), false)
                        changes++
                    }
                } else if (mineT == null) {
                    val ch = k.local(item.optString("ch")) ?: continue
                    reminders.set(Reminder(ch, item.optString("name"), item.optString("title"), item.optLong("start"), item.optLong("end")), true)
                    changes++
                }
            }
        }

        merged.optJSONObject("hide")?.let { sec ->
            for (id in sec.keys()) {
                val item = sec.getJSONObject(id)
                val parts = id.split('|')
                if (parts.size < 3) continue
                val pid = k.localPid(parts[0]) ?: continue
                val kind = parts[1].toIntOrNull() ?: continue
                val cat = parts.drop(2).joinToString("|")
                val mineT = localT("hide", id)
                if (item.optBoolean("deleted")) {
                    if (mineT != null && item.optLong("t") >= mineT) {
                        db.categories().setHidden(pid, kind, cat, false)
                        changes++
                        contentChanged = true
                    }
                } else if (mineT == null) {
                    db.categories().setHidden(pid, kind, cat, true)
                    changes++
                    contentChanged = true
                }
            }
        }

        merged.optJSONObject("pref")?.let { sec ->
            for (p in syncedPrefs) {
                val item = sec.optJSONObject(p.key) ?: continue
                if (item.optLong("t") > p.modifiedAt) {
                    p.setFromSync(item.optString("value"), item.optLong("t"))
                    changes++
                }
            }
        }

        if (contentChanged) sync.contentVersion.update { it + 1 }
        changes
    }
}
