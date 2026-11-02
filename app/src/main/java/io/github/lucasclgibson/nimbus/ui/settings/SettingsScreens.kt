package io.github.lucasclgibson.nimbus.ui.settings

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import io.github.lucasclgibson.nimbus.ui.components.PinDialog
import io.github.lucasclgibson.nimbus.data.repo.Parental
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.lucasclgibson.nimbus.BuildConfig
import io.github.lucasclgibson.nimbus.core.USER_AGENT_PRESETS
import io.github.lucasclgibson.nimbus.core.friendlyError
import io.github.lucasclgibson.nimbus.data.db.CategoryEntity
import io.github.lucasclgibson.nimbus.data.db.Kind
import io.github.lucasclgibson.nimbus.data.db.PlaylistEntity
import io.github.lucasclgibson.nimbus.data.db.PlaylistType
import io.github.lucasclgibson.nimbus.data.source.XtreamApi
import io.github.lucasclgibson.nimbus.phone.SetupServer
import io.github.lucasclgibson.nimbus.ui.components.FocusSurface
import io.github.lucasclgibson.nimbus.ui.components.MenuItem
import io.github.lucasclgibson.nimbus.ui.components.OptionsMenu
import io.github.lucasclgibson.nimbus.ui.components.PillButton
import io.github.lucasclgibson.nimbus.ui.components.RequestOnce
import io.github.lucasclgibson.nimbus.ui.components.SectionTitle
import io.github.lucasclgibson.nimbus.ui.components.SetBackdrop
import io.github.lucasclgibson.nimbus.ui.components.TopNav
import io.github.lucasclgibson.nimbus.ui.components.TvTextField
import io.github.lucasclgibson.nimbus.ui.components.tryFocus
import io.github.lucasclgibson.nimbus.ui.components.FocusRoom
import io.github.lucasclgibson.nimbus.ui.components.bleedHorizontally
import io.github.lucasclgibson.nimbus.ui.nav.Dest
import io.github.lucasclgibson.nimbus.ui.nav.LocalApp
import io.github.lucasclgibson.nimbus.ui.nav.LocalNav
import io.github.lucasclgibson.nimbus.ui.theme.C
import io.github.lucasclgibson.nimbus.ui.theme.T
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================================ settings

@Composable
fun SettingsScreen() = androidx.compose.runtime.CompositionLocalProvider(
    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides io.github.lucasclgibson.nimbus.ui.components.rememberMinimalBringIntoView(),
) {
    val app = LocalApp.current
    var open by remember { mutableStateOf(!app.parental.hasPin || !app.prefs.lockSettings.value || app.parental.settingsUnlocked) }
    if (open) SettingsContent() else SettingsLocked { open = true }
}

/** Settings locked by the parental PIN. */
@Composable
private fun SettingsLocked(onUnlocked: () -> Unit) {
    val app = LocalApp.current
    var asking by remember { mutableStateOf(true) }
    val button = remember { FocusRequester() }
    SetBackdrop(null)
    Column(Modifier.fillMaxSize()) {
        TopNav(Dest.Settings)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(40.dp), tint = C.TextDim)
                Spacer(Modifier.height(12.dp))
                Text("Settings are locked", style = T.H2, color = C.Text)
                Spacer(Modifier.height(16.dp))
                PillButton("Enter PIN", { asking = true }, primary = true, modifier = Modifier.focusRequester(button))
            }
        }
    }
    button.RequestOnce()
    if (asking) PinDialog("Enter your PIN", "to open Settings", onSubmit = { pin ->
        app.parental.unlockSettings(pin).also { if (it) onUnlocked() }
    }, onDismiss = { asking = false })
}

private enum class PinFlow { SetFirst, SetConfirm, VerifyForChange, VerifyForRemove }

@Composable
private fun SettingsContent() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val playlists by app.store.all.collectAsState()
    val status by app.sync.status.collectAsState()
    val running by app.sync.running.collectAsState()
    val startup by app.prefs.startup.flow.collectAsState()
    val buffer by app.prefs.bufferMode.flow.collectAsState()
    val afr by app.prefs.matchFrameRate.flow.collectAsState()
    val tmdb by app.prefs.tmdbKey.flow.collectAsState()
    var confirmDelete by remember { mutableStateOf<PlaylistEntity?>(null) }
    var pinFlow by remember { mutableStateOf<PinFlow?>(null) }
    // After a PIN dialog closes, land on the parental buttons (not wherever focus fell back to).
    val pinFocus = remember { FocusRequester() }
    var pinFlowWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(pinFlow) {
        if (pinFlow != null) {
            pinFlowWasOpen = true
        } else if (pinFlowWasOpen) {
            pinFlowWasOpen = false
            repeat(20) {
                androidx.compose.runtime.withFrameNanos { }
                if (pinFocus.tryFocus()) return@LaunchedEffect
            }
        }
    }
    var firstPin by remember { mutableStateOf("") }
    val pinHashValue by app.prefs.pinHash.flow.collectAsState()
    val hasPin = pinHashValue.isNotEmpty()
    val lockAdult by app.prefs.lockAdult.flow.collectAsState()
    val lockSettings by app.prefs.lockSettings.flow.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var tmdbDraft by remember(tmdb) { mutableStateOf(tmdb) }
    SetBackdrop(null)

    Column(Modifier.fillMaxSize()) {
        TopNav(Dest.Settings)
        LazyColumn(
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { SectionTitle("Playlists") }
            items(playlists, key = { it.id }) { p ->
                val s = status[p.id]
                Row(
                    Modifier.fillMaxWidth().background(C.Panel, RoundedCornerShape(14.dp)).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, style = T.H3, color = C.Text)
                            Spacer(Modifier.width(10.dp))
                            Text(if (p.type == PlaylistType.XTREAM) "XTREAM" else "M3U", style = T.Tiny, color = C.Accent)
                        }
                        Spacer(Modifier.height(4.dp))
                        val line = when {
                            p.id in running -> (s?.message ?: "Updating…") + (s?.fraction?.let { " ${(it * 100).toInt()}%" } ?: "")
                            s?.error == true -> s.message
                            else -> p.status.ifBlank { "Not loaded yet" }
                        }
                        Text(line, style = T.Small, color = if (s?.error == true) C.Bad else C.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val extra = buildList {
                            if (p.expiresAt > 0) add("Expires " + SimpleDateFormat("d MMM yyyy", Locale.UK).format(Date(p.expiresAt)))
                            if (p.maxConnections > 0) add("${p.maxConnections} connection${if (p.maxConnections == 1) "" else "s"}")
                            if (p.programmeCount > 0) add("${p.programmeCount} guide entries")
                            if (p.lastEpgSync > 0) add("guide updated " + SimpleDateFormat("EEE HH:mm", Locale.UK).format(Date(p.lastEpgSync)))
                        }
                        if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = T.Small, color = C.TextMute)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Refresh", { app.sync.sync(p.id) }, icon = Icons.Rounded.Refresh, compact = true)
                        PillButton("Edit", { nav.push(Dest.PlaylistForm(p.type, p.id)) }, icon = Icons.Rounded.Edit, compact = true)
                        PillButton("", { confirmDelete = p }, icon = Icons.Rounded.Delete, compact = true)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Add Xtream Codes", { nav.push(Dest.PlaylistForm(PlaylistType.XTREAM)) }, icon = Icons.Rounded.Add)
                    PillButton("Add M3U URL", { nav.push(Dest.PlaylistForm(PlaylistType.M3U)) }, icon = Icons.Rounded.Link)
                    PillButton("Add from phone", { nav.push(Dest.PhoneSetup) }, icon = Icons.Rounded.PhoneAndroid)
                }
            }

            item { Spacer(Modifier.height(6.dp)); SectionTitle("Live TV & guide") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Choose channel groups", { nav.push(Dest.ManageCategories(Kind.LIVE)) }, icon = Icons.Rounded.Visibility)
                    PillButton("Refresh TV guide now", { playlists.forEach { app.sync.sync(it.id, content = false, epg = true) } }, icon = Icons.Rounded.Refresh)
                }
            }

            item { Spacer(Modifier.height(6.dp)); SectionTitle("Films & TV shows") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Choose film categories", { nav.push(Dest.ManageCategories(Kind.MOVIE)) }, icon = Icons.Rounded.Visibility)
                    PillButton("Choose TV show categories", { nav.push(Dest.ManageCategories(Kind.SERIES)) }, icon = Icons.Rounded.Visibility)
                }
            }
            item {
                Column {
                    TvTextField(
                        value = tmdbDraft,
                        onValueChange = { tmdbDraft = it },
                        label = "TMDB API key – adds reviews, cast photos, ratings and trailers (free at themoviedb.org)",
                        placeholder = "Optional – or send it from your phone",
                        modifier = Modifier.width(620.dp),
                        keyboardType = KeyboardType.Text,
                        onDone = { app.prefs.tmdbKey.value = tmdbDraft.trim() },
                    )
                    Text(if (tmdb.isNotBlank()) "Key saved ✓" else "No key – using your provider's info only.", style = T.Small, color = C.TextMute, modifier = Modifier.padding(top = 4.dp))
                }
            }

            item { Spacer(Modifier.height(6.dp)); SectionTitle("When Nimbus starts") }
            item {
                Segmented(
                    listOf("home" to "Home", "guide" to "TV Guide", "last_channel" to "Last channel"),
                    startup,
                ) { app.prefs.startup.value = it }
            }

            item { Spacer(Modifier.height(6.dp)); SectionTitle("Playback") }
            item {
                Column {
                    Segmented(listOf(0 to "Normal buffer", 1 to "Large buffer (unstable connections)"), buffer) {
                        app.prefs.bufferMode.value = it
                    }
                    Text("Buffer changes apply next time Nimbus starts.", style = T.Small, color = C.TextMute, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                Column {
                    Segmented(listOf(true to "Match frame rate", false to "Keep TV's refresh rate"), afr) { app.prefs.matchFrameRate.value = it }
                    Text(
                        "Switches the TV to 50 Hz for UK channels and 23.976 Hz for films, like a Sky box – smoother sport. " +
                            "The screen may go black for a second when it switches.",
                        style = T.Small, color = C.TextMute, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(6.dp)); SectionTitle("Parental controls") }
            item {
                if (!hasPin) {
                    Column {
                        PillButton("Set a PIN", { pinFlow = PinFlow.SetFirst }, icon = Icons.Rounded.Lock, modifier = Modifier.focusRequester(pinFocus))
                        Text(
                            "Adult groups are hidden already. A PIN stops anyone showing them again (or changing settings) without it.",
                            style = T.Small, color = C.TextMute, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Segmented(listOf(true to "Adult groups need the PIN", false to "No lock on adult groups"), lockAdult) {
                            app.prefs.lockAdult.value = it
                        }
                        Segmented(listOf(false to "Settings open", true to "Settings need the PIN"), lockSettings) {
                            app.prefs.lockSettings.value = it
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("Change PIN", { pinFlow = PinFlow.VerifyForChange }, icon = Icons.Rounded.Lock, compact = true, modifier = Modifier.focusRequester(pinFocus))
                            PillButton("Remove PIN", { pinFlow = PinFlow.VerifyForRemove }, icon = Icons.Rounded.LockOpen, compact = true)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(6.dp)); SectionTitle("Household & updates") }
            item {
                val household by app.household.status.collectAsState()
                val update by app.updater.available.collectAsState()
                val hubFound by app.prefs.hubFound.flow.collectAsState()
                val hubManual by app.prefs.hubUrl.flow.collectAsState()
                var hubDraft by remember(hubManual) { mutableStateOf(hubManual) }
                var checking by remember { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when {
                            hubManual.isNotBlank() || hubFound.isNotBlank() ->
                                "Hub: ${(hubManual.ifBlank { hubFound }).removePrefix("http://").substringBefore('/')}" +
                                    (if (household.isNotBlank()) " · $household" else "")
                            else -> "No Nimbus hub found on this network – favourites, resume points and updates stay on this TV."
                        },
                        style = T.Small, color = C.TextDim,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton(
                            if (checking) "Checking…" else if (update != null) "Install ${update!!.versionName}" else "Check for updates",
                            {
                                scope.launch {
                                    checking = true
                                    val u = app.updater.check(force = true)
                                    checking = false
                                    if (u != null && update != null) {
                                        if (app.updater.canInstall()) app.updater.install()
                                        else app.updater.permissionIntent()?.let { context.startActivity(it) }
                                    }
                                }
                            },
                            icon = Icons.Rounded.Refresh, compact = true,
                        )
                        PillButton("Sync now", { app.household.syncSoon() }, icon = Icons.Rounded.Refresh, compact = true)
                    }
                    TvTextField(
                        value = hubDraft,
                        onValueChange = { hubDraft = it },
                        label = "Hub address (optional – found automatically)",
                        placeholder = "http://192.168.1.20:8787/nimbus",
                        modifier = Modifier.width(620.dp),
                        onDone = {
                            app.prefs.hubUrl.value = hubDraft.trim()
                            app.household.syncSoon()
                        },
                    )
                }
            }

            item { Spacer(Modifier.height(6.dp)); SectionTitle("About") }
            item {
                Text(
                    "Nimbus ${BuildConfig.VERSION_NAME} · ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${SetupServer.localIp() ?: "offline"}",
                    style = T.Small, color = C.TextMute,
                )
            }
            item {
                Text(
                    "Free and open source (GPL-3.0): github.com/lucasclgibson/nimbus. Nimbus doesn't provide any channels or content. " +
                        "Film and TV details come from TMDB: this product uses the TMDB API but is not endorsed or certified by TMDB.",
                    style = T.Small, color = C.TextMute,
                )
            }
        }
    }

    when (pinFlow) {
        PinFlow.SetFirst -> PinDialog("Choose a 4-digit PIN", onSubmit = { pin ->
            firstPin = pin
            pinFlow = PinFlow.SetConfirm
            true
        }, onDismiss = { pinFlow = null })
        PinFlow.SetConfirm -> PinDialog("Enter it again", "to confirm", onSubmit = { pin ->
            (pin == firstPin).also {
                if (it) {
                    app.parental.setPin(pin)
                    pinFlow = null
                }
            }
        }, onDismiss = { pinFlow = null })
        PinFlow.VerifyForChange -> PinDialog("Enter your current PIN", onSubmit = { pin ->
            app.parental.check(pin).also { if (it) pinFlow = PinFlow.SetFirst }
        }, onDismiss = { pinFlow = null })
        PinFlow.VerifyForRemove -> PinDialog("Enter your PIN", "to remove it", onSubmit = { pin ->
            app.parental.check(pin).also {
                if (it) {
                    app.parental.clearPin()
                    pinFlow = null
                }
            }
        }, onDismiss = { pinFlow = null })
        null -> Unit
    }

    confirmDelete?.let { p ->
        OptionsMenu(
            title = "Delete “${p.name}”?",
            subtitle = "Removes its channels, guide, films and series from this TV.",
            items = listOf(
                MenuItem("Delete playlist", "", Icons.Rounded.Delete) {
                    scope.launch {
                        app.deletePlaylist(p.id)
                        nav.resetTabs()
                    }
                },
                MenuItem("Cancel", "") {},
            ),
            onDismiss = { confirmDelete = null },
            focusIndex = 1, // destructive: start on Cancel
        )
    }
}

@Composable
private fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            FocusSurface(
                onClick = { onSelect(value) },
                modifier = Modifier.height(38.dp),
                shape = RoundedCornerShape(50),
                color = if (on) C.Accent else C.Glass,
                focusedColor = C.Focus,
                contentColor = C.Text,
                focusedContentColor = C.OnFocus,
                focusedScale = 1.05f,
                border = false,
            ) {
                Box(Modifier.padding(horizontal = 18.dp).height(38.dp), contentAlignment = Alignment.Center) {
                    Text((if (on) "✓  " else "") + label, style = T.Label)
                }
            }
        }
    }
}

// ============================================================================================ playlist form

@Composable
fun PlaylistFormScreen(type: String, editId: Long) {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val xtream = type == PlaylistType.XTREAM
    var existing by remember { mutableStateOf<PlaylistEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var epg by remember { mutableStateOf("") }
    var uaIndex by remember { mutableStateOf(0) }
    var customUa by remember { mutableStateOf("") }
    var format by remember { mutableStateOf("ts") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    SetBackdrop(null)

    LaunchedEffect(editId) {
        if (editId != 0L) {
            app.db.playlists().get(editId)?.let { p ->
                existing = p
                name = p.name; url = p.url; user = p.username; pass = p.password; epg = p.epgUrl; format = p.outputFormat
                val idx = USER_AGENT_PRESETS.indexOfFirst { it.second == p.userAgent }
                if (idx >= 0) uaIndex = idx else { uaIndex = -1; customUa = p.userAgent }
            }
        }
    }

    fun save() {
        error = null
        if (url.isBlank() || (xtream && user.isBlank())) {
            error = if (xtream) "Enter the server address, username and password." else "Enter the playlist URL."
            return
        }
        busy = true
        scope.launch {
            val ua = if (uaIndex >= 0) USER_AGENT_PRESETS[uaIndex].second else customUa.trim()
            try {
                val cleanUrl = if (xtream) XtreamApi.normaliseBase(url) else url.trim()
                if (xtream) {
                    XtreamApi(app.http, cleanUrl, user.trim(), pass, ua.ifBlank { io.github.lucasclgibson.nimbus.core.DEFAULT_USER_AGENT }).login()
                }
                val label = name.trim().ifBlank { cleanUrl.substringAfter("://").substringBefore('/').substringBefore(':') }
                val base = existing
                if (base == null) {
                    app.addPlaylist(
                        PlaylistEntity(name = label, type = type, url = cleanUrl, username = user.trim(), password = pass, epgUrl = epg.trim(), userAgent = ua, outputFormat = format)
                    )
                } else {
                    app.updatePlaylist(
                        base.copy(name = label, url = cleanUrl, username = user.trim(), password = pass, epgUrl = epg.trim(), userAgent = ua, outputFormat = format),
                        resync = true,
                    )
                }
                nav.resetTabs()
                nav.go(Dest.Home)
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                busy = false
            }
        }
    }

    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 36.dp)) {
        Column(Modifier.width(470.dp)) {
            Text(
                when {
                    existing != null -> "Edit playlist"
                    xtream -> "Add Xtream Codes login"
                    else -> "Add M3U playlist"
                },
                style = T.H1, color = C.Text,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = FocusRoom / 2, bottom = 40.dp)) {
                item { TvTextField(name, { name = it }, "Name", placeholder = "e.g. My TV", keyboardType = KeyboardType.Text, focusRequester = first) }
                item {
                    TvTextField(url, { url = it }, if (xtream) "Server address" else "Playlist URL",
                        placeholder = if (xtream) "http://provider.example:8080" else "http://…/get.php?…&type=m3u_plus")
                }
                if (xtream) {
                    item { TvTextField(user, { user = it }, "Username", keyboardType = KeyboardType.Text) }
                    item { TvTextField(pass, { pass = it }, "Password", password = true, keyboardType = KeyboardType.Password) }
                }
                item { TvTextField(epg, { epg = it }, "Extra EPG / XMLTV URL (optional)", placeholder = if (xtream) "Provider guide is added automatically" else "Uses the playlist's url-tvg if empty") }
                item {
                    Column {
                        Text("User agent", style = T.Label, color = C.TextDim)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            USER_AGENT_PRESETS.forEachIndexed { i, (label, _) ->
                                Chip(label, i == uaIndex) { uaIndex = i }
                            }
                        }
                    }
                }
                if (xtream) item {
                    Column {
                        Text("Live stream format", style = T.Label, color = C.TextDim)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Chip("MPEG-TS (recommended)", format == "ts") { format = "ts" }
                            Chip("HLS", format == "m3u8") { format = "m3u8" }
                        }
                    }
                }
                item {
                    Column {
                        error?.let {
                            Text(it, style = T.BodyStrong, color = C.Bad)
                            Spacer(Modifier.height(10.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton(if (busy) "Checking…" else "Save and load", { if (!busy) save() }, primary = true)
                            PillButton("Cancel", { nav.pop() })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.weight(1f).padding(top = 56.dp)) {
            Text("Easier on your phone", style = T.H3, color = C.Text)
            Spacer(Modifier.height(6.dp))
            Text("Typing long addresses with the remote is a pain. Open a page on your phone and send the details to the TV instead.", style = T.Body, color = C.TextDim)
            Spacer(Modifier.height(14.dp))
            PillButton("Use my phone", { nav.replaceTop(Dest.PhoneSetup) }, icon = Icons.Rounded.PhoneAndroid)
            Spacer(Modifier.height(28.dp))
            Text(
                if (xtream) "Your provider's guide (EPG), catch-up, films and series are all loaded automatically from the Xtream login."
                else "Channels, films and series in the playlist are sorted automatically. Catch-up works when the playlist includes catchup attributes.",
                style = T.Small, color = C.TextMute,
            )
        }
    }
    first.RequestOnce()
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(50),
        color = if (selected) C.Accent else C.Glass,
        focusedColor = C.Focus,
        contentColor = C.Text,
        focusedContentColor = C.OnFocus,
        focusedScale = 1.05f,
        border = false,
    ) {
        Box(Modifier.padding(horizontal = 12.dp).height(32.dp), contentAlignment = Alignment.Center) {
            Text(label, style = T.Small)
        }
    }
}

// ============================================================================================ phone setup

@Composable
fun PhoneSetupScreen() {
    val app = LocalApp.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val back = remember { FocusRequester() }
    SetBackdrop(null)

    DisposableEffect(Unit) {
        address = app.setupServer.start()
        onDispose { app.setupServer.stop() }
    }
    LaunchedEffect(Unit) {
        app.setupServer.submissions.collect { s ->
            if (s.tmdbKey.isNotBlank()) app.prefs.tmdbKey.value = s.tmdbKey
            if (s.url.isNotBlank()) {
                val xtream = s.type == PlaylistType.XTREAM
                val url = if (xtream) XtreamApi.normaliseBase(s.url) else s.url
                val label = s.name.ifBlank { url.substringAfter("://").substringBefore('/').substringBefore(':') }
                scope.launch {
                    app.addPlaylist(
                        PlaylistEntity(name = label, type = s.type, url = url, username = s.username, password = s.password, epgUrl = s.epgUrl)
                    )
                }
                message = "Got it – loading “$label”. You can add another, or press Back."
            } else {
                message = "TMDB key saved ✓"
            }
        }
    }

    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 48.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Set up from your phone", style = T.Display, color = C.Text)
            Spacer(Modifier.height(16.dp))
            Text("1.  Connect your phone to the same Wi-Fi as this TV", style = T.H3, color = C.TextDim)
            Spacer(Modifier.height(8.dp))
            Text("2.  Scan the code, or open this address:", style = T.H3, color = C.TextDim)
            Spacer(Modifier.height(10.dp))
            Text(address ?: "No network connection", style = T.H1, color = C.Accent)
            Spacer(Modifier.height(8.dp))
            Text("3.  Enter your playlist details and tap Send to TV", style = T.H3, color = C.TextDim)
            Spacer(Modifier.height(24.dp))
            message?.let {
                Text(it, style = T.H3, color = C.Good)
                Spacer(Modifier.height(16.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton(if (message != null) "Done" else "Back", {
                    if (message != null) {
                        nav.resetTabs()
                        nav.go(Dest.Home)
                    } else nav.pop()
                }, modifier = Modifier.focusRequester(back))
            }
        }
        address?.let { a ->
            Box(Modifier.size(260.dp).background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp)) {
                QrCode(a, Modifier.fillMaxSize())
            }
        }
    }
    back.RequestOnce()
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    }
    Canvas(modifier) {
        val n = matrix.width
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}

// ============================================================================================ categories

private val PREFIX = Regex("""^\s*[\[(]?([A-Za-z0-9+]{2,6})[\])]?\s*[|:\-]""")

/** "UK | Sport" -> "UK", "[AR] Movies" -> "AR"; groups without a tag fall under "Other". */
private fun groupTag(name: String): String = PREFIX.find(name)?.groupValues?.get(1)?.uppercase(Locale.ROOT) ?: "Other"

@Composable
fun ManageCategoriesScreen(kind: Int) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var cats by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var changed by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    SetBackdrop(null)
    LaunchedEffect(kind) { cats = app.db.categories().all(kind) }
    val multi = app.store.all.value.size > 1
    // Quick filters by provider tag, biggest first ("UK", "EN", "AR", "4K"…)
    val tags = remember(cats) {
        cats.groupBy { groupTag(it.name) }.entries.sortedByDescending { it.value.size }.map { it.key to it.value }
    }

    var pendingShow by remember { mutableStateOf<List<CategoryEntity>?>(null) }

    fun set(list: List<CategoryEntity>, hidden: Boolean) {
        if (!hidden && list.any { app.parental.needsPin(it.name) }) {
            pendingShow = list
            return
        }
        changed = true
        scope.launch {
            list.forEach { app.db.categories().setHidden(it.playlistId, it.kind, it.categoryId, hidden) }
            cats = app.db.categories().all(kind)
            app.sync.contentVersion.value++
        }
    }

    // Tags kept by "UK & English only": local + English + untagged + quality-only tags.
    val keepTags = remember { setOf("UK", "GB", "EN", "ENG", "IE", "IRL", "4K", "UHD", "FHD", "HD", "Other") }

    // The guide only imports visible channel groups, so refresh it once you're done choosing.
    DisposableEffect(Unit) {
        onDispose {
            if (changed && kind == Kind.LIVE) app.store.all.value.forEach { app.sync.sync(it.id, content = false, epg = true) }
        }
    }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 36.dp)) {
        Text(
            when (kind) {
                Kind.LIVE -> "Channel groups"
                Kind.MOVIE -> "Film categories"
                else -> "TV show categories"
            },
            style = T.H1, color = C.Text,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Hidden groups disappear from the guide, browse screens and search. ${cats.count { !it.hidden }} of ${cats.size} shown. " +
                "Quick filters toggle every group with that tag.",
            style = T.Body, color = C.TextDim,
        )
        Spacer(Modifier.height(14.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.bleedHorizontally().focusRestorer(),
            contentPadding = PaddingValues(horizontal = FocusRoom),
        ) {
            item {
                PillButton(
                    "UK & English only",
                    {
                        changed = true
                        scope.launch {
                            cats.forEach { c ->
                                val hide = groupTag(c.name) !in keepTags || Parental.isAdult(c.name)
                                if (hide != c.hidden) app.db.categories().setHidden(c.playlistId, c.kind, c.categoryId, hide)
                            }
                            cats = app.db.categories().all(kind)
                            app.sync.contentVersion.value++
                        }
                    },
                    icon = Icons.Rounded.Visibility, compact = true, primary = true, modifier = Modifier.focusRequester(first),
                )
            }
            // "Show all" never reveals adult groups – those are shown one at a time (with the PIN if set).
            item { PillButton("Show all", { set(cats.filterNot { Parental.isAdult(it.name) }, false) }, icon = Icons.Rounded.Visibility, compact = true) }
            item { PillButton("Hide all", { set(cats, true) }, icon = Icons.Rounded.VisibilityOff, compact = true) }
            items(tags, key = { it.first }) { (tag, list) ->
                val shown = list.count { !it.hidden }
                val state = when (shown) {
                    0 -> "off"
                    list.size -> "on"
                    else -> "$shown/${list.size}"
                }
                PillButton(
                    "$tag · ${list.size}  ($state)",
                    { set(if (shown > 0) list else list.filterNot { Parental.isAdult(it.name) }, hidden = shown > 0) },
                    icon = if (shown > 0) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                    compact = true,
                    primary = shown > 0,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(top = FocusRoom / 2, bottom = 60.dp)) {
            items(cats, key = { "${it.playlistId}:${it.categoryId}" }) { c ->
                FocusSurface(
                    onClick = { set(listOf(c), !c.hidden) },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = if (c.hidden) C.Panel.copy(alpha = 0.5f) else C.Panel,
                    focusedColor = C.Focus,
                    contentColor = if (c.hidden) C.TextMute else C.Text,
                    focusedContentColor = C.OnFocus,
                    focusedScale = 1.01f,
                    border = false,
                ) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (c.hidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        val prefix = if (multi) (app.store[c.playlistId]?.name?.let { "$it · " } ?: "") else ""
                        Text(prefix + c.name, style = T.Label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (c.hidden) "Hidden" else "Shown", style = T.Small)
                    }
                }
            }
        }
    }
    first.RequestOnce()
    pendingShow?.let { list ->
        PinDialog("Enter your PIN", "to show adult groups", onSubmit = { pin ->
            app.parental.unlockAdult(pin).also {
                if (it) {
                    pendingShow = null
                    set(list, false)
                }
            }
        }, onDismiss = { pendingShow = null })
    }
}
