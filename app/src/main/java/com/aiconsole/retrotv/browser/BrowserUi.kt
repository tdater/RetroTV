package com.aiconsole.retrotv.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.data.ControllerMode
import com.aiconsole.retrotv.data.HistoryEntry
import com.aiconsole.retrotv.data.Site
import com.aiconsole.retrotv.data.SitePrefs
import com.aiconsole.retrotv.data.ViewMode
import com.aiconsole.retrotv.downloads.DownloadItem
import com.aiconsole.retrotv.downloads.SavedFile
import com.aiconsole.retrotv.ui.ChoiceRow
import com.aiconsole.retrotv.ui.Ink
import com.aiconsole.retrotv.ui.OverlayPanel
import com.aiconsole.retrotv.ui.Pill
import com.aiconsole.retrotv.ui.ScriptsPanel
import com.aiconsole.retrotv.ui.TextDim
import com.aiconsole.retrotv.ui.MenuTile
import com.aiconsole.retrotv.ui.TvButton
import com.aiconsole.retrotv.ui.TvTextField
import com.aiconsole.retrotv.web.Userscript

enum class InputMode(val label: String) { Cursor("Pointer"), Dpad("D-pad keys") }

sealed interface Panel {
    data object Menu : Panel
    data object Address : Panel
    data object Scripts : Panel
    data object SiteOptions : Panel
    data object Help : Panel
    data class InstallScript(val url: String, val script: Userscript?, val error: String?) : Panel
    data class ApkReady(val item: DownloadItem) : Panel
    data class Saved(val file: SavedFile) : Panel
}

/** Everything the overlay shows, written by [BrowserActivity] on the main thread. */
class BrowserUiState {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var progress by mutableIntStateOf(0)
    var loading by mutableStateOf(false)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)
    var panel by mutableStateOf<Panel?>(null)
    var windowed by mutableStateOf(true)
    var htmlFullscreen by mutableStateOf(false)
    var inputMode by mutableStateOf(InputMode.Cursor)
    var controllerBrowse by mutableStateOf(false)
    var desktop by mutableStateOf(true)
    var siteAdBlock by mutableStateOf(true)
    var controllerMode by mutableStateOf(ControllerMode.Auto)
    var gamePlayer by mutableStateOf(true)
    var blockedCount by mutableIntStateOf(0)
    var blockedPopup by mutableStateOf<String?>(null)
    var focusGame by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var toastId by mutableIntStateOf(0)
    var standalone by mutableStateOf(false)
    var padCount by mutableIntStateOf(0)
    var site by mutableStateOf<Site?>(null)
    var appInstalled by mutableStateOf<Boolean?>(null)
    var sitePrefs by mutableStateOf(SitePrefs())
    var host by mutableStateOf("")
}

interface BrowserActions {
    fun back()
    fun forward()
    fun reload()
    fun home()
    fun siteStart()
    fun load(text: String)
    fun voiceSearch()
    fun toggleWindow()
    fun toggleFocusGame()
    fun toggleDesktop()
    fun zoom(delta: Int)
    fun toggleInput()
    fun toggleAdBlock()
    fun cycleController()
    fun toggleGamePlayer()
    fun toggleControllerBrowse()
    fun installApp()
    fun addToHome()
    fun openDownloads()
    fun openBlocked()
    fun openSettings()
    fun exit()
    fun show(panel: Panel?)
    fun installScript(script: Userscript, url: String)
    fun installApk(item: DownloadItem)
    fun openSaved(file: SavedFile)
    fun updateSite(change: (SitePrefs) -> SitePrefs)
}

const val TOOLBAR_DP = 56

@Composable
fun BrowserOverlay(state: BrowserUiState, actions: BrowserActions, history: List<HistoryEntry>, sites: List<Site>) {
    Box(Modifier.fillMaxSize()) {
        if (state.windowed && !state.htmlFullscreen) Toolbar(state, actions)
        if (state.loading && state.progress in 1..99) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = if (state.windowed && !state.htmlFullscreen) TOOLBAR_DP.dp - 3.dp else 0.dp)
                    .fillMaxWidth(state.progress / 100f)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        state.toast?.let { text ->
            val id = state.toastId
            LaunchedEffect(id) {
                kotlinx.coroutines.delay(3200)
                if (state.toastId == id) state.toast = null
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .background(Ink.copy(alpha = 0.92f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) { Text(text, color = Color.White, fontSize = 17.sp) }
        }
        when (val p = state.panel) {
            null -> Unit
            Panel.Menu -> MenuPanel(state, actions)
            Panel.Address -> AddressPanel(state, actions, history, sites)
            Panel.Scripts -> OverlayPanel("Userscripts", Modifier.fillMaxSize(), widthFraction = 0.86f) {
                ScriptsPanel(state.host.ifEmpty { null }, Modifier.fillMaxWidth().height(520.dp))
            }
            Panel.SiteOptions -> SiteOptionsPanel(state, actions)
            Panel.Help -> HelpPanel(actions)
            is Panel.InstallScript -> InstallScriptPanel(p, actions)
            is Panel.ApkReady -> ConfirmPanel(
                "Download finished",
                "${p.item.title} is ready to install.",
                "Install now" to { actions.installApk(p.item) },
                actions
            )
            is Panel.Saved -> ConfirmPanel(
                "Saved",
                "${p.file.name} was saved to Downloads/RetroTV.",
                "Open" to { actions.openSaved(p.file) },
                actions
            )
        }
    }
}

@Composable
private fun ToolButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxHeight()
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontSize = 20.sp) }
}

@Composable
private fun Toolbar(state: BrowserUiState, actions: BrowserActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(TOOLBAR_DP.dp)
            .background(Ink)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton("◀") { actions.back() }
        ToolButton("▶") { actions.forward() }
        ToolButton(if (state.loading) "✕" else "⟳") { actions.reload() }
        ToolButton("⌂") { actions.home() }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
                .pointerInput(Unit) { detectTapGestures { actions.show(Panel.Address) } }
                .padding(horizontal = 14.dp, vertical = 4.dp)
        ) {
            Text(state.title.ifEmpty { state.host }, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state.url, color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.siteAdBlock) Pill("🛡 ${state.blockedCount}", Color(0xFF3DDC84)) else Pill("🛡 off", TextDim)
            Pill(if (state.desktop) "Desktop" else "TV view")
            Pill(if (state.inputMode == InputMode.Cursor) "Pointer" else "D-pad")
            if (state.padCount > 0) Pill("🎮 ${state.padCount}${if (state.controllerBrowse) " browse" else ""}", Color(0xFFFFD23F))
        }
        ToolButton("⛶") { actions.toggleWindow() }
        ToolButton("☰") { actions.show(Panel.Menu) }
    }
}

@Composable
private fun MenuPanel(state: BrowserUiState, actions: BrowserActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel(state.title.ifEmpty { "RetroTV" }, Modifier.fillMaxSize(), widthFraction = 0.9f) {
        Text(state.url, color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val rows: List<List<Triple<String, String, () -> Unit>>> = listOf(
            listOf(
                Triple("◀", "Back") { actions.back() },
                Triple("▶", "Forward") { actions.forward() },
                Triple("⟳", if (state.loading) "Stop" else "Reload") { actions.reload() },
                Triple("🔍", "Address / search") { actions.show(Panel.Address) },
                Triple("🏁", "Site start page") { actions.siteStart() },
                Triple("⌂", if (state.standalone) "RetroTV home" else "Home") { actions.home() }
            ),
            listOf(
                Triple("⛶", if (state.windowed) "Full screen" else "Windowed") { actions.toggleWindow() },
                Triple("🎯", if (state.focusGame) "Unfocus game" else "Focus game") { actions.toggleFocusGame() },
                Triple("🖥", if (state.desktop) "Switch to TV view" else "Switch to desktop view") { actions.toggleDesktop() },
                Triple("➖", "Zoom out") { actions.zoom(-1) },
                Triple("➕", "Zoom in") { actions.zoom(1) },
                Triple("🖱", if (state.inputMode == InputMode.Cursor) "Remote: pointer" else "Remote: D-pad keys") { actions.toggleInput() }
            ),
            listOf(
                Triple("🛡", if (state.siteAdBlock) "Ad block: on" else "Ad block: off") { actions.toggleAdBlock() },
                Triple("🕹", if (state.gamePlayer) "Game player: RetroTV" else "Game player: site's own") { actions.toggleGamePlayer() },
                Triple("🎮", "Controller: ${state.controllerMode.label}") { actions.cycleController() },
                Triple("🖲", if (state.controllerBrowse) "Controller plays game" else "Controller moves pointer") { actions.toggleControllerBrowse() },
                Triple("📜", "Userscripts") { actions.show(Panel.Scripts) },
                Triple("⚙", "This site's options") { actions.show(Panel.SiteOptions) }
            ),
            listOfNotNull(
                Triple("📲", if (state.appInstalled == true) "Remove app" else "Install as app") { actions.installApp() },
                Triple("⭐", "Add to home") { actions.addToHome() },
                Triple("⬇", "Downloads") { actions.openDownloads() },
                state.blockedPopup?.let { Triple("↗", "Open blocked pop-up") { actions.openBlocked() } },
                Triple("❔", "Controls help") { actions.show(Panel.Help) },
                Triple("🛠", "Settings") { actions.openSettings() },
                Triple("⏻", "Exit") { actions.exit() }
            )
        )
        rows.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { c, (icon, label, action) ->
                    val m = Modifier.weight(1f).let { if (r == 0 && c == 0) it.focusRequester(first) else it }
                    MenuTile(icon, label, action, m)
                }
            }
        }
        Text(
            "Hold OK or press Menu for this panel · Back closes it · Select + Start opens it from a controller",
            color = TextDim, fontSize = 13.sp
        )
    }
}

@Composable
private fun AddressPanel(state: BrowserUiState, actions: BrowserActions, history: List<HistoryEntry>, sites: List<Site>) {
    var text by remember { mutableStateOf(state.url) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    OverlayPanel("Go to", Modifier.fillMaxSize(), widthFraction = 0.8f) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTextField(text, { text = it }, "Address or search", Modifier.weight(1f).focusRequester(field), onDone = { actions.load(text) })
            TvButton("Go", { actions.load(text) }, icon = "➜")
            TvButton("Voice", { actions.voiceSearch() }, icon = "🎤")
        }
        val q = text.trim().lowercase()
        val suggestions = remember(q, history, sites) {
            val siteHits = sites.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.url.contains(q) }.map { it.name to it.url }
            val historyHits = history.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }.map { it.title to it.url }
            (historyHits.take(8) + siteHits.take(6)).distinctBy { it.second }.take(10)
        }
        LazyColumn(Modifier.height(380.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(suggestions) { (title, url) ->
                TvButton("$title  ·  $url", { actions.load(url) }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SiteOptionsPanel(state: BrowserUiState, actions: BrowserActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val s = state.sitePrefs
    OverlayPanel("Options for ${state.host.ifEmpty { "this page" }}", Modifier.fillMaxSize(), widthFraction = 0.7f) {
        ChoiceRow(
            "View", listOf<ViewMode?>(null, ViewMode.Desktop, ViewMode.Mobile), s.viewMode,
            { it?.label ?: "Default" }, { v -> actions.updateSite { it.copy(viewMode = v) } },
            Modifier.focusRequester(first), subtitle = "Desktop: full-size layout, 1280 px wide · TV view: mobile layout, everything larger"
        )
        ChoiceRow(
            "Game player", listOf<Boolean?>(null, true, false), s.player,
            { when (it) { null -> "Default"; true -> "RetroTV player"; false -> "Site's own player" } },
            { v -> actions.updateSite { it.copy(player = v) } },
            subtitle = "RetroTV's player runs EmulatorJS games full screen, ad-free, with save slots"
        )
        ChoiceRow(
            "Ad blocking", listOf<Boolean?>(null, true, false), s.adBlock,
            { when (it) { null -> "Default"; true -> "On"; false -> "Off for this site" } },
            { v -> actions.updateSite { it.copy(adBlock = v) } },
            subtitle = "Turn off if a game or menu stops working here"
        )
        ChoiceRow(
            "Controller", listOf<ControllerMode?>(null) + ControllerMode.entries, s.controllerMode,
            { it?.label ?: "Default" }, { v -> actions.updateSite { it.copy(controllerMode = v) } },
            subtitle = "Auto sends gamepad input to emulators and keys to everything else"
        )
        ChoiceRow(
            "Text size", listOf<Int?>(null, 75, 90, 100, 115, 130, 150, 175, 200), s.zoom,
            { it?.let { z -> "$z%" } ?: "Default" }, { v -> actions.updateSite { it.copy(zoom = v) } }
        )
        TvButton("Reset this site", { actions.updateSite { SitePrefs() } }, icon = "↺")
    }
}

@Composable
private fun HelpPanel(actions: BrowserActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel("Controls", Modifier.fillMaxSize(), widthFraction = 0.75f) {
        val lines = listOf(
            "Remote D-pad" to "Moves the pointer (speeds up while held). Push against an edge to scroll.",
            "OK" to "Click. Hold OK and move to drag. Hold OK still to open the menu.",
            "Back" to "Previous page · leaves full-screen video/game · exits at the first page.",
            "Menu / Info / Guide" to "Opens the quick menu.",
            "Channel up/down" to "Scroll a page at a time.",
            "Play/Pause" to "Switches the remote between pointer and D-pad keys (arrow keys + Enter for games).",
            "Controller" to "Goes straight to the game. Auto: a gamepad for emulators, keyboard keys for \"press any key\" screens.",
            "Games" to "EmulatorJS games open in RetroTV's player (full screen, save slots). Back or Menu there opens the game menu.",
            "Select + Start" to "Opens the menu from a controller. \"Controller moves pointer\" lets a controller browse: stick/D-pad move, A clicks, B goes back, Y opens the menu, shoulders scroll."
        )
        lines.forEach { (k, v) ->
            Row {
                Text(k, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.width(220.dp))
                Text(v, color = Color.White, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        TvButton("Got it", { actions.show(null) }, Modifier.focusRequester(first))
    }
}

@Composable
private fun InstallScriptPanel(p: Panel.InstallScript, actions: BrowserActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(p.script, p.error) { runCatching { first.requestFocus() } }
    OverlayPanel("Install userscript?", Modifier.fillMaxSize(), widthFraction = 0.65f) {
        val s = p.script
        when {
            p.error != null -> Text("Couldn't load the script: ${p.error}", color = Color(0xFFFF6B6B))
            s == null -> Text("Loading ${p.url}…", color = TextDim)
            else -> {
                Text(s.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (s.description.isNotEmpty()) Text(s.description, color = TextDim)
                Text("Version ${s.version.ifEmpty { "?" }} · runs on: " + (s.matches + s.includes).take(5).joinToString(", ").ifEmpty { "all sites" }, color = TextDim, fontSize = 14.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s != null) TvButton("Install", { actions.installScript(s, p.url) }, Modifier.focusRequester(first), icon = "✅")
            TvButton("Cancel", { actions.show(null) }, if (s == null) Modifier.focusRequester(first) else Modifier)
        }
    }
}

@Composable
private fun ConfirmPanel(title: String, text: String, primary: Pair<String, () -> Unit>, actions: BrowserActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel(title, Modifier.fillMaxSize(), widthFraction = 0.55f) {
        Text(text, color = Color.White, fontSize = 17.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(primary.first, { actions.show(null); primary.second() }, Modifier.focusRequester(first))
            TvButton("Later", { actions.show(null) })
        }
    }
}
