package io.github.lucasclgibson.nimbus.ui.guide

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.lucasclgibson.nimbus.AppGraph
import io.github.lucasclgibson.nimbus.core.DAY
import io.github.lucasclgibson.nimbus.core.HOUR
import io.github.lucasclgibson.nimbus.core.MINUTE
import io.github.lucasclgibson.nimbus.core.floorTo
import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.data.db.ChannelEntity
import io.github.lucasclgibson.nimbus.data.db.ProgrammeEntity
import io.github.lucasclgibson.nimbus.data.db.ProgrammeLite
import io.github.lucasclgibson.nimbus.data.repo.LiveGroup
import io.github.lucasclgibson.nimbus.data.repo.LiveRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A guide cell: a real programme, or a filler hour where the guide has no data. */
data class Slot(val start: Long, val end: Long, val programme: ProgrammeLite?)

const val WINDOW = 120 * MINUTE

class GuideState(private val app: AppGraph, private val scope: CoroutineScope) {
    var groups by mutableStateOf<List<LiveGroup>>(emptyList())
    var groupId by mutableStateOf(app.prefs.guideGroup.value)
    var channels by mutableStateOf<List<ChannelEntity>>(emptyList())
    var loaded by mutableStateOf(false)
    val rows = mutableStateMapOf<String, List<ProgrammeLite>>()
    private val pending = HashSet<String>()

    var channelIndex by mutableIntStateOf(0)
    var focusTime by mutableLongStateOf(now())
    var windowStart by mutableLongStateOf(floorTo(now(), 30 * MINUTE))
    var clock by mutableLongStateOf(now())
    var detail by mutableStateOf<ProgrammeEntity?>(null)
    /** What the header shows; follows the cursor once it settles so rapid moves don't re-lay it out. */
    var header by mutableStateOf<Pair<ChannelEntity, Slot>?>(null)
    private var headerJob: Job? = null
    val listState = LazyListState()
    var gridFocused by mutableStateOf(false)
    var visibleRows = 6

    private var contentVersion = -1
    private var epgVersion = -1
    private var detailJob: Job? = null

    val channel: ChannelEntity? get() = channels.getOrNull(channelIndex)

    init {
        scope.launch {
            while (true) {
                clock = now()
                delay(30_000)
            }
        }
        scope.launch {
            app.sync.contentVersion.collect { v ->
                if (v != contentVersion) {
                    contentVersion = v
                    reload(keepCursor = loaded)
                }
            }
        }
        scope.launch {
            app.sync.epgVersion.collect { v ->
                if (v != epgVersion) {
                    val first = epgVersion == -1
                    epgVersion = v
                    if (!first) {
                        rows.clear()
                        pending.clear()
                        requestVisible()
                    }
                }
            }
        }
    }

    private suspend fun reload(keepCursor: Boolean) {
        val currentKey = channel?.key
        groups = app.live.groups()
        if (groups.none { it.id == groupId }) groupId = fallbackGroup()
        var list = app.live.channels(groupId, FIRST_PAGE)
        if (list.isEmpty() && groupId == LiveRepository.FAVOURITES) {
            groupId = fallbackGroup()
            list = app.live.channels(groupId, FIRST_PAGE)
        }
        show(list, if (keepCursor) currentKey else app.prefs.lastChannelKey.value, resetTime = !keepCursor)
        loaded = true
    }

    /** Without favourites, open on the group of the channel you last watched (not 50,000 channels). */
    private suspend fun fallbackGroup(): String {
        val last = app.live.channel(app.prefs.lastChannelKey.value)
        val id = last?.let { "${it.playlistId}:${it.categoryId}" }
        return if (id != null && groups.any { it.id == id }) id else LiveRepository.ALL
    }

    fun selectGroup(id: String) {
        if (id == groupId && channels.isNotEmpty()) return
        groupId = id
        app.prefs.guideGroup.value = id
        scope.launch {
            show(app.live.channels(id, FIRST_PAGE), app.prefs.lastChannelKey.value, resetTime = false)
        }
    }

    /**
     * Show a group straight away from its first page; huge groups ("All channels" on big providers)
     * finish loading in the background without moving the cursor.
     */
    private suspend fun show(first: List<ChannelEntity>, target: String?, resetTime: Boolean) {
        val group = groupId
        channels = first
        rows.clear()
        pending.clear()
        channelIndex = first.indexOfFirst { it.key == target }.coerceAtLeast(0)
        if (resetTime) jumpNow()
        listState.scrollToItem((channelIndex - 2).coerceAtLeast(0))
        requestVisible()
        refreshDetail()
        if (first.size < FIRST_PAGE) return
        val full = app.live.channels(group)
        if (groupId != group) return
        val focusedKey = channel?.key
        val moved = focusedKey != null && channelIndex != first.indexOfFirst { it.key == target }.coerceAtLeast(0)
        channels = full
        val keep = if (moved || first.any { it.key == target }) focusedKey else target
        channelIndex = full.indexOfFirst { it.key == keep }.coerceAtLeast(0)
        if (!moved && first.none { it.key == target }) listState.scrollToItem((channelIndex - 2).coerceAtLeast(0))
        requestVisible()
        refreshDetail()
    }

    // ------------------------------------------------------------------ data

    fun requestVisible() {
        val first = listState.firstVisibleItemIndex
        requestRows((first - 6).coerceAtLeast(0)..(first + visibleRows + 12))
    }

    private fun requestRows(range: IntRange) {
        val wanted = range.mapNotNull { channels.getOrNull(it) }.filter { it.key !in rows && it.key !in pending }
        if (wanted.isEmpty()) return
        pending += wanted.map { it.key }
        val snapshot = channels
        scope.launch {
            val map = app.live.programmes(wanted, 0, Long.MAX_VALUE)
            if (channels !== snapshot) return@launch
            map.forEach { (k, v) -> rows[k] = v }
            wanted.forEach { pending -= it.key }
            if (wanted.any { it == channel }) refreshDetail()
            wanted.filter { map[it.key].isNullOrEmpty() }.forEach { ch -> fetchFallback(ch) }
            checkArchive()
        }
    }

    /** If the window is before the earliest guide data of visible catch-up channels, back-fill it. */
    fun checkArchive() {
        val first = listState.firstVisibleItemIndex
        for (i in first until (first + visibleRows + 1).coerceAtMost(channels.size)) {
            val ch = channels[i]
            if (ch.catchupDays <= 0) continue
            val list = rows[ch.key] ?: continue
            val earliest = list.firstOrNull()?.startMs ?: Long.MAX_VALUE
            if (windowStart >= earliest || list.isEmpty()) continue
            scope.launch {
                val epgId = app.live.fetchArchiveEpg(ch, earliest) ?: return@launch
                reloadRows(epgId, ch)
            }
        }
    }

    /** Refresh every loaded row that shares this guide id (several channels often do). */
    private suspend fun reloadRows(epgId: String, origin: ChannelEntity) {
        if (origin.epgId.isEmpty()) {
            val idx = channels.indexOfFirst { it.key == origin.key }
            if (idx >= 0) channels = channels.toMutableList().also { it[idx] = origin.copy(epgId = epgId) }
        }
        val affected = channels.filter { it.epgId == epgId && it.key in rows }
        app.live.programmes(affected, 0, Long.MAX_VALUE).forEach { (k, v) -> rows[k] = v }
        if (affected.any { it.key == channel?.key }) refreshDetail()
    }

    private fun fetchFallback(ch: ChannelEntity) {
        scope.launch {
            val epgId = app.live.fetchChannelEpg(ch) ?: return@launch
            val updated = ch.copy(epgId = epgId)
            val idx = channels.indexOfFirst { it.key == ch.key }
            if (idx >= 0) channels = channels.toMutableList().also { it[idx] = updated }
            rows[ch.key] = app.live.programmes(listOf(updated), 0, Long.MAX_VALUE)[ch.key].orEmpty()
            if (channel?.key == ch.key) refreshDetail()
        }
    }

    // ------------------------------------------------------------------ slots

    fun slotAt(list: List<ProgrammeLite>, t: Long): Slot {
        var lo = 0
        var hi = list.size - 1
        var prevEnd = Long.MIN_VALUE
        var nextStart = Long.MAX_VALUE
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = list[mid]
            when {
                t < p.startMs -> { nextStart = p.startMs; hi = mid - 1 }
                t >= p.endMs -> { prevEnd = p.endMs; lo = mid + 1 }
                else -> return Slot(p.startMs, p.endMs, p)
            }
        }
        val hour = floorTo(t, HOUR)
        return Slot(maxOf(prevEnd, hour), minOf(nextStart, hour + HOUR), null)
    }

    fun slotsIn(list: List<ProgrammeLite>, from: Long, to: Long): List<Slot> {
        val out = ArrayList<Slot>(8)
        var t = from
        while (t < to && out.size < 64) {
            val s = slotAt(list, t)
            out += s
            t = s.end
        }
        return out
    }

    val focusedSlot: Slot?
        get() {
            val ch = channel ?: return null
            return slotAt(rows[ch.key].orEmpty(), focusTime)
        }

    // ------------------------------------------------------------------ navigation

    private companion object {
        const val FIRST_PAGE = 400
    }

    private val minTime get() = floorTo(now() - 14 * DAY, HOUR)
    private val maxTime get() = now() + 8 * DAY

    fun moveVertical(delta: Int): Boolean {
        if (channels.isEmpty()) return false
        val next = channelIndex + delta
        if (next < 0) return false
        channelIndex = next.coerceAtMost(channels.lastIndex)
        keepRowVisible()
        refreshDetail()
        return true
    }

    fun page(delta: Int) {
        if (channels.isEmpty()) return
        channelIndex = (channelIndex + delta * visibleRows).coerceIn(0, channels.lastIndex)
        keepRowVisible()
        refreshDetail()
    }

    fun moveHorizontal(delta: Int) {
        val ch = channel ?: return
        val list = rows[ch.key].orEmpty()
        val cur = slotAt(list, focusTime)
        val target = if (delta > 0) slotAt(list, cur.end) else slotAt(list, cur.start - 1)
        if (target.start < minTime || target.start > maxTime) return
        if (target.start < windowStart) {
            windowStart = floorTo(target.start, 30 * MINUTE)
        } else if (target.start >= windowStart + WINDOW - 15 * MINUTE) {
            windowStart = floorTo(target.start, 30 * MINUTE) - 30 * MINUTE
        }
        focusTime = maxOf(target.start, windowStart)
        refreshDetail()
        checkArchive()
    }

    fun jumpDays(days: Int) {
        val t = (focusTime + days * DAY).coerceIn(minTime, maxTime)
        windowStart = floorTo(t, 30 * MINUTE)
        focusTime = maxOf(t, windowStart)
        refreshDetail()
        checkArchive()
    }

    fun jumpNow() {
        val t = now()
        windowStart = floorTo(t, 30 * MINUTE)
        focusTime = t
        refreshDetail()
    }

    private fun keepRowVisible() {
        val first = listState.firstVisibleItemIndex
        val target = when {
            channelIndex < first + 1 -> (channelIndex - 1).coerceAtLeast(0)
            channelIndex > first + visibleRows - 2 -> channelIndex - visibleRows + 2
            else -> return
        }
        scope.launch {
            listState.scrollToItem(target.coerceIn(0, (channels.size - 1).coerceAtLeast(0)))
            requestVisible()
            checkArchive()
        }
    }

    private fun refreshDetail() {
        headerJob?.cancel()
        headerJob = scope.launch {
            if (header != null) delay(70)
            val ch = channel
            val slot = focusedSlot
            header = if (ch != null && slot != null) ch to slot else null
        }
        detailJob?.cancel()
        val id = focusedSlot?.programme?.id
        if (id == null) {
            detail = null
            return
        }
        if (detail?.id == id) return
        detailJob = scope.launch {
            delay(90)
            detail = app.live.programme(id)
        }
    }
}
