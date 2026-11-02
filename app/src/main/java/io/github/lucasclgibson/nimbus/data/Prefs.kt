package io.github.lucasclgibson.nimbus.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small settings store backed by SharedPreferences, exposed as StateFlows for Compose. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("nimbus", Context.MODE_PRIVATE)

    inner class Pref<T>(val key: String, private val default: T) {
        @Suppress("UNCHECKED_CAST")
        private fun read(): T = when (default) {
            is String -> sp.getString(key, default) as T
            is Boolean -> sp.getBoolean(key, default) as T
            is Int -> sp.getInt(key, default) as T
            is Long -> sp.getLong(key, default) as T
            else -> error("unsupported")
        }

        private val state = MutableStateFlow(read())
        val flow: StateFlow<T> = state.asStateFlow()
        var value: T
            get() = state.value
            set(v) = write(v, System.currentTimeMillis())

        /** When this setting was last changed (0 = never): household sync keeps the newest. */
        val modifiedAt: Long get() = sp.getLong("$key.t", 0L)

        private fun write(v: T, t: Long) {
            state.value = v
            sp.edit().apply {
                when (v) {
                    is String -> putString(key, v)
                    is Boolean -> putBoolean(key, v)
                    is Int -> putInt(key, v)
                    is Long -> putLong(key, v)
                }
                putLong("$key.t", t)
            }.apply()
        }

        /** Apply a value that came from another TV, keeping its timestamp. */
        @Suppress("UNCHECKED_CAST")
        fun setFromSync(raw: String, t: Long) {
            val v: Any = when (default) {
                is String -> raw
                is Boolean -> raw.toBoolean()
                is Int -> raw.toIntOrNull() ?: return
                is Long -> raw.toLongOrNull() ?: return
                else -> return
            }
            write(v as T, t)
        }
    }

    val tmdbKey = Pref("tmdb_key", "")
    /** "home", "last_channel" or "guide" */
    val startup = Pref("startup", "home")
    val lastChannelKey = Pref("last_channel", "")
    val previousChannelKey = Pref("previous_channel", "")
    val guideGroup = Pref("guide_group", "all")
    val subtitlesOn = Pref("subtitles_on", false)
    val preferredAudio = Pref("preferred_audio", "")
    /** 0 = normal, 1 = large (unreliable connections) */
    val bufferMode = Pref("buffer_mode", 0)
    val epgFutureDays = Pref("epg_future_days", 7)
    val showClock = Pref("show_clock", true)
    /** Set when the user taps "Not now" on the Home groups prompt. */
    val groupsReviewed = Pref("groups_nudge_dismissed", false)
    /** Newline-separated, most recent first. */
    val recentSearches = Pref("recent_searches", "")
    /** Household hub: set by hand (optional), or found automatically on the network. */
    val hubUrl = Pref("hub_url", "")
    val hubFound = Pref("hub_found", "")
    /** Parental controls: SHA-256 of the PIN ("" = no PIN). */
    val pinHash = Pref("pin_hash", "")
    val lockAdult = Pref("lock_adult", true)
    val lockSettings = Pref("lock_settings", false)
    /** JSON list of programme reminders. */
    val reminders = Pref("reminders", "[]")
    /** Switch the TV to the video's refresh rate (50 Hz for UK TV, 23.976 for films). */
    val matchFrameRate = Pref("match_frame_rate", true)
    /** Which catalogue / guide state the search index was last built from. */
    val searchCatalogueStamp = Pref("search_catalogue_stamp", "")
    val searchGuideStamp = Pref("search_guide_stamp", "")
}
