package io.github.lucasclgibson.nimbus.data.repo

import io.github.lucasclgibson.nimbus.core.DAY
import io.github.lucasclgibson.nimbus.core.DEFAULT_USER_AGENT
import io.github.lucasclgibson.nimbus.core.HOUR
import io.github.lucasclgibson.nimbus.core.MINUTE
import io.github.lucasclgibson.nimbus.core.isSeparatorChannel
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.data.db.ChannelEntity
import io.github.lucasclgibson.nimbus.data.db.Kind
import io.github.lucasclgibson.nimbus.data.db.NimbusDb
import io.github.lucasclgibson.nimbus.data.db.PlaylistType
import io.github.lucasclgibson.nimbus.data.db.ProgrammeEntity
import io.github.lucasclgibson.nimbus.data.db.ProgrammeLite
import io.github.lucasclgibson.nimbus.data.source.Catchup
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class LiveGroup(val id: String, val title: String)

class LiveRepository(
    private val db: NimbusDb,
    private val store: PlaylistStore,
) {
    suspend fun groups(): List<LiveGroup> {
        store.ready()
        val cats = db.categories().all(Kind.LIVE).filter { !it.hidden }
        val multi = store.all.value.size > 1
        val out = ArrayList<LiveGroup>(cats.size + 2)
        out += LiveGroup(FAVOURITES, "Favourites")
        out += LiveGroup(ALL, "All channels")
        cats.forEach { c ->
            val prefix = if (multi) store[c.playlistId]?.name?.let { "$it · " }.orEmpty() else ""
            out += LiveGroup("${c.playlistId}:${c.categoryId}", prefix + c.name)
        }
        return out
    }

    suspend fun channels(groupId: String, limit: Int = Int.MAX_VALUE): List<ChannelEntity> = when (groupId) {
        FAVOURITES -> db.channels().favourites()
        ALL -> if (limit == Int.MAX_VALUE) db.channels().allVisible() else db.channels().allVisibleLimited(limit)
        else -> {
            val pid = groupId.substringBefore(':').toLongOrNull()
            if (pid == null) emptyList() else db.channels().inCategory(pid, groupId.substringAfter(':'))
        }
    }.filterNot { isSeparatorChannel(it.name) }

    suspend fun channel(key: String): ChannelEntity? = db.channels().get(key)

    /** Programmes overlapping [from, to) for each channel, keyed by channel key, sorted and de-overlapped. */
    suspend fun programmes(channels: Collection<ChannelEntity>, from: Long, to: Long): Map<String, List<ProgrammeLite>> {
        store.ready()
        val result = HashMap<String, List<ProgrammeLite>>(channels.size * 2)
        channels.groupBy { it.playlistId }.forEach { (pid, list) ->
            val gen = store[pid]?.epgGeneration ?: return@forEach
            val ids = list.mapNotNull { it.epgId.ifEmpty { null } }.distinct()
            val byEpg = HashMap<String, MutableList<ProgrammeLite>>()
            ids.chunked(400).forEach { chunk ->
                db.programmes().range(pid, gen, chunk, from, to).forEach {
                    byEpg.getOrPut(it.epgId) { ArrayList() }.add(it)
                }
            }
            val cleaned = byEpg.mapValues { (_, v) -> sanitise(v) }
            list.forEach { c ->
                val rows = cleaned[c.epgId].orEmpty()
                val shift = plusOneShift(c)
                result[c.key] = if (shift == 0L || rows.isEmpty()) rows else rows.map { it.copy(startMs = it.startMs + shift, endMs = it.endMs + shift) }
            }
        }
        return result
    }

    /** Current and next programme for each channel. */
    suspend fun nowNext(channels: Collection<ChannelEntity>, at: Long = now()): Map<String, NowNext> {
        val map = programmes(channels, at, at + 8 * HOUR)
        return map.mapValues { (_, list) ->
            val idx = list.indexOfFirst { it.startMs <= at && it.endMs > at }
            if (idx >= 0) NowNext(list[idx], list.getOrNull(idx + 1)) else NowNext(null, list.firstOrNull { it.startMs > at })
        }
    }

    suspend fun programme(id: Long): ProgrammeEntity? = db.programmes().get(id)

    /** The programme on a channel at a given moment, if the guide knows it. */
    suspend fun programmeAt(c: ChannelEntity, t: Long): ProgrammeLite? =
        programmes(listOf(c), t - 12 * HOUR, t + 12 * HOUR)[c.key]?.firstOrNull { it.startMs <= t && it.endMs > t }

    // -------------------------------------------------------------- playback URLs

    fun liveUrl(c: ChannelEntity): String {
        if (c.url.isNotEmpty()) return c.url
        val p = store[c.playlistId] ?: return ""
        return store.xtream(p).liveUrl(c.streamId, p.outputFormat.ifBlank { "ts" })
    }

    fun userAgent(c: ChannelEntity): String = c.userAgent.ifBlank { store.userAgent(c.playlistId) }.ifBlank { DEFAULT_USER_AGENT }

    fun hasCatchup(c: ChannelEntity): Boolean = c.catchupDays > 0

    /** Is this (past or in-progress) programme inside the channel's archive window? */
    fun canCatchup(c: ChannelEntity, start: Long, at: Long = now()): Boolean =
        c.catchupDays > 0 && start < at && start >= at - c.catchupDays * DAY - HOUR

    fun catchupUrl(c: ChannelEntity, startMs: Long, endMs: Long): String? {
        val p = store[c.playlistId] ?: return null
        val durationMin = ((endMs - startMs) / MINUTE).toInt().coerceAtLeast(1)
        return if (p.type == PlaylistType.XTREAM && c.streamId.isNotEmpty()) {
            store.xtream(p).timeshiftUrl(c.streamId, startMs, durationMin, store.serverZone(p.id))
        } else {
            Catchup.build(c.catchupType, c.catchupSource, c.url, startMs, endMs, store.serverZone(p.id))
        }
    }

    // -------------------------------------------------------------- per-channel EPG fallback

    private val attempted = HashSet<String>()
    private val permits = Semaphore(3)

    /**
     * Xtream panels often leave channels out of xmltv.php but still answer per-channel EPG requests.
     * Called by the guide for visible channels with no data; tries each channel once per app session.
     * @return the channel's epg id if new data was stored
     */
    suspend fun fetchChannelEpg(c: ChannelEntity): String? {
        val p = store[c.playlistId] ?: return null
        if (p.type != PlaylistType.XTREAM || c.streamId.isEmpty()) return null
        synchronized(attempted) { if (!attempted.add(c.key)) return null }
        return permits.withPermit {
            val listings = runCatching { store.xtream(p).channelEpg(c.streamId) }.getOrNull().orEmpty()
            if (listings.isEmpty()) return@withPermit null
            val epgId = c.epgId.ifEmpty { "xc:${c.streamId}" }
            if (c.epgId.isEmpty()) db.channels().setEpgId(c.key, epgId)
            val gen = store[p.id]?.epgGeneration ?: p.epgGeneration
            db.programmes().deleteChannel(p.id, gen, epgId)
            db.programmes().insertAll(listings.map {
                ProgrammeEntity(
                    playlistId = p.id, generation = gen, epgId = epgId, startMs = it.start, endMs = it.end,
                    title = it.title.ifBlank { "Untitled" }, subtitle = "", description = it.description,
                    category = "", episode = "", icon = "",
                )
            })
            epgId
        }
    }

    private val archiveAttempted = HashSet<String>()

    /**
     * Many panels' xmltv.php only starts "today", although catch-up channels keep days of archive.
     * The per-channel EPG call does include the past, so back-fill it when the guide scrolls before
     * the earliest programme we have. Once per channel guide id per session.
     * @return the channel's epg id if anything was added
     */
    suspend fun fetchArchiveEpg(c: ChannelEntity, before: Long): String? {
        val p = store[c.playlistId] ?: return null
        if (p.type != PlaylistType.XTREAM || c.streamId.isEmpty() || c.catchupDays <= 0) return null
        val tag = "${p.id}:${c.epgId.ifEmpty { c.key }}"
        synchronized(archiveAttempted) { if (!archiveAttempted.add(tag)) return null }
        return permits.withPermit {
            val listings = runCatching { store.xtream(p).channelEpg(c.streamId) }.getOrNull().orEmpty()
            val cutoff = now() - (c.catchupDays + 1) * DAY
            val past = listings.filter { it.end <= before && it.start >= cutoff }
            if (past.isEmpty()) return@withPermit null
            val epgId = c.epgId.ifEmpty { "xc:${c.streamId}" }
            if (c.epgId.isEmpty()) db.channels().setEpgId(c.key, epgId)
            val gen = store[p.id]?.epgGeneration ?: p.epgGeneration
            db.programmes().insertAll(past.map {
                ProgrammeEntity(
                    playlistId = p.id, generation = gen, epgId = epgId, startMs = it.start, endMs = it.end,
                    title = it.title.ifBlank { "Untitled" }, subtitle = "", description = it.description,
                    category = "", episode = "", icon = "",
                )
            })
            epgId
        }
    }

    /** Providers often give "ITV 1 +1" the same guide id as ITV 1; its schedule is really an hour later. */
    private fun plusOneShift(c: ChannelEntity): Long {
        if (!PLUS_ONE.containsMatchIn(c.name)) return 0
        val id = c.epgId.lowercase()
        return if (id.contains("+1") || id.contains("plus1") || id.contains("plusone") || id.contains("+ 1")) 0 else HOUR
    }

    companion object {
        private val PLUS_ONE = Regex("""\+\s?1(\b|$)""")
        const val FAVOURITES = "fav"
        const val ALL = "all"

        /** Drop zero-length items and trim overlaps so the guide never draws stacked cells. */
        fun sanitise(list: List<ProgrammeLite>): List<ProgrammeLite> {
            if (list.size < 2) return list.filter { it.endMs > it.startMs }
            val out = ArrayList<ProgrammeLite>(list.size)
            for (p in list) {
                if (p.endMs <= p.startMs) continue
                val prev = out.lastOrNull()
                if (prev != null && p.startMs < prev.endMs) {
                    if (p.endMs <= prev.endMs) continue
                    if (p.startMs <= prev.startMs) continue
                    out[out.size - 1] = prev.copy(endMs = p.startMs)
                }
                out += p
            }
            return out
        }
    }
}

data class NowNext(val now: ProgrammeLite?, val next: ProgrammeLite?)
