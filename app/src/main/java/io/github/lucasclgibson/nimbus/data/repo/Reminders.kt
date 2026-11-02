package io.github.lucasclgibson.nimbus.data.repo

import io.github.lucasclgibson.nimbus.core.now
import io.github.lucasclgibson.nimbus.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(
    val channelKey: String,
    val channelName: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

/** "Remind me" for upcoming programmes, like Sky: a prompt shortly before it starts. */
class Reminders(private val prefs: Prefs, scope: CoroutineScope) {
    private val _list = MutableStateFlow(load())
    val list: StateFlow<List<Reminder>> = _list

    /** The reminder to show right now (a prompt on screen), if any. */
    private val _due = MutableStateFlow<Reminder?>(null)
    val due: StateFlow<Reminder?> = _due

    init {
        scope.launch {
            while (isActive) {
                tick()
                delay(10_000)
            }
        }
    }

    fun has(channelKey: String, startMs: Long) = _list.value.any { it.channelKey == channelKey && it.startMs == startMs }

    /** @return true if the reminder is now set */
    fun toggle(r: Reminder): Boolean {
        val on = !has(r.channelKey, r.startMs)
        _list.value = if (on) (_list.value + r).sortedBy { it.startMs }
        else _list.value.filterNot { it.channelKey == r.channelKey && it.startMs == r.startMs }
        save()
        return on
    }

    /** Set or clear a reminder without toggling (household sync). */
    fun set(r: Reminder, on: Boolean) {
        val exists = has(r.channelKey, r.startMs)
        if (on == exists) return
        _list.value = if (on) (_list.value + r).sortedBy { it.startMs }
        else _list.value.filterNot { it.channelKey == r.channelKey && it.startMs == r.startMs }
        save()
    }

    fun dismiss() {
        _due.value = null
        tick()
    }

    private fun tick() {
        val t = now()
        val stale = _list.value.filter { it.endMs <= t }
        if (stale.isNotEmpty()) {
            _list.value = _list.value - stale.toSet()
            save()
        }
        val current = _due.value
        if (current != null && current.endMs <= t) _due.value = null
        if (_due.value == null) {
            val fire = _list.value.firstOrNull { it.startMs - LEAD_MS <= t } ?: return
            _list.value = _list.value - fire
            save()
            _due.value = fire
        }
    }

    private fun load(): List<Reminder> = runCatching {
        val a = JSONArray(prefs.reminders.value)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Reminder(o.getString("c"), o.optString("n"), o.optString("t"), o.getLong("s"), o.getLong("e"))
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val a = JSONArray()
        _list.value.forEach { r ->
            a.put(JSONObject().put("c", r.channelKey).put("n", r.channelName).put("t", r.title).put("s", r.startMs).put("e", r.endMs))
        }
        prefs.reminders.value = a.toString()
    }

    companion object {
        private const val LEAD_MS = 60_000L
    }
}
