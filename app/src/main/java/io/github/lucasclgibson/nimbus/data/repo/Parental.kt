package io.github.lucasclgibson.nimbus.data.repo

import io.github.lucasclgibson.nimbus.data.Prefs
import io.github.lucasclgibson.nimbus.data.db.NimbusDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Parental PIN. With a PIN set, adult groups stay hidden unless unlocked with the PIN, and lock again
 * whenever Nimbus goes to the background; Settings can be locked too.
 */
class Parental(private val prefs: Prefs, private val db: NimbusDb, private val sync: SyncManager, private val scope: CoroutineScope) {
    val hasPin: Boolean get() = prefs.pinHash.value.isNotEmpty()

    @Volatile var adultUnlocked = false
        private set
    @Volatile var settingsUnlocked = false
        private set

    fun check(pin: String): Boolean = hasPin && hash(pin) == prefs.pinHash.value

    fun unlockAdult(pin: String): Boolean = check(pin).also { if (it) adultUnlocked = true }

    fun unlockSettings(pin: String): Boolean = check(pin).also { if (it) settingsUnlocked = true }

    fun setPin(pin: String) {
        prefs.pinHash.value = hash(pin)
        settingsUnlocked = true // you're in Settings right now
        adultUnlocked = false
        relockAdult()
    }

    fun clearPin() {
        prefs.pinHash.value = ""
        adultUnlocked = false
        settingsUnlocked = false
    }

    /** Back to locked: on leaving the app, and after a PIN is set. */
    fun relock() {
        if (!hasPin) return
        adultUnlocked = false
        settingsUnlocked = false
        relockAdult()
    }

    private fun relockAdult() {
        if (!hasPin || !prefs.lockAdult.value) return
        scope.launch(Dispatchers.IO) {
            var changed = false
            for (kind in 0..2) {
                db.categories().all(kind).filter { !it.hidden && isAdult(it.name) }.forEach {
                    db.categories().setHidden(it.playlistId, it.kind, it.categoryId, true)
                    changed = true
                }
            }
            if (changed) sync.contentVersion.update { it + 1 }
        }
    }

    /** Does showing this group need the PIN right now? */
    fun needsPin(categoryName: String): Boolean = hasPin && prefs.lockAdult.value && !adultUnlocked && isAdult(categoryName)

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("nimbus-pin:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        val ADULT = Regex("""(?i)(xxx|adult|porn|\b18\s*\+|erotic|for adults)""")
        fun isAdult(name: String) = ADULT.containsMatchIn(name)
    }
}
