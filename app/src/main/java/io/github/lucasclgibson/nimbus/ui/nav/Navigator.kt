package io.github.lucasclgibson.nimbus.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.lucasclgibson.nimbus.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

sealed interface Dest {
    data object Home : Dest
    data object Guide : Dest
    data object Movies : Dest
    data object Shows : Dest
    data object Search : Dest
    data object Settings : Dest
    data class MovieDetail(val key: String) : Dest
    data class SeriesDetail(val key: String) : Dest
    data object Player : Dest
    data class PlaylistForm(val type: String, val editId: Long = 0) : Dest
    data object PhoneSetup : Dest
    data class ManageCategories(val kind: Int) : Dest
}

val TopTabs: List<Pair<Dest, String>> = listOf(
    Dest.Home to "Home",
    Dest.Guide to "TV Guide",
    Dest.Movies to "Movies",
    Dest.Shows to "TV Shows",
    Dest.Search to "Search",
    Dest.Settings to "Settings",
)

/** One screen instance. Holds the screen's state objects so they survive navigating away and back. */
class Entry(val dest: Dest) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val bag = HashMap<String, Any>()
    fun dispose() = scope.cancel()
}

/**
 * Minimal TV navigator: one retained entry per top-level tab plus a stack of pushed screens.
 * No animations – instant transitions feel fastest with a remote.
 */
class Navigator(start: Dest) {
    private val tabEntries = HashMap<Dest, Entry>()
    var root by mutableStateOf(Entry(start).also { tabEntries[start] = it })
        private set
    val stack = mutableStateListOf<Entry>()
    val current: Entry get() = stack.lastOrNull() ?: root

    fun go(tab: Dest) {
        clearStack()
        root = tabEntries.getOrPut(tab) { Entry(tab) }
    }

    fun push(dest: Dest) {
        if (stack.lastOrNull()?.dest == dest) return
        stack.add(Entry(dest))
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex).dispose()
        return true
    }

    fun replaceTop(dest: Dest) {
        pop()
        push(dest)
    }

    fun clearStack() {
        while (stack.isNotEmpty()) stack.removeAt(stack.lastIndex).dispose()
    }

    /** Forget a tab's retained state (e.g. after a playlist change). */
    fun resetTabs() {
        val keep = root
        tabEntries.values.filter { it !== keep }.forEach { it.dispose() }
        tabEntries.entries.removeAll { it.value !== keep }
    }
}

val LocalApp = staticCompositionLocalOf<AppGraph> { error("no app") }
val LocalNav = staticCompositionLocalOf<Navigator> { error("no nav") }
val LocalEntry = staticCompositionLocalOf<Entry> { error("no entry") }

/** Screen-scoped state that survives the screen leaving composition (until the entry is popped). */
@Composable
inline fun <reified T : Any> entryState(key: String = T::class.java.name, crossinline create: (CoroutineScope) -> T): T {
    val entry = LocalEntry.current
    @Suppress("UNCHECKED_CAST")
    return entry.bag.getOrPut(key) { create(entry.scope) } as T
}
