package com.aiconsole.retrotv.ui

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.webkit.WebViewCompat
import java.util.Locale
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.data.ACCENTS
import com.aiconsole.retrotv.data.Backdrop
import com.aiconsole.retrotv.data.Backup
import com.aiconsole.retrotv.data.CardSize
import com.aiconsole.retrotv.data.ControllerMode
import com.aiconsole.retrotv.data.Prefs
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.data.ViewMode
import com.aiconsole.retrotv.data.WindowMode
import com.aiconsole.retrotv.web.AdBlock
import com.aiconsole.retrotv.web.Gamepad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Edit { UserAgent, BlockList }

@Composable
fun SettingsScreen(onBack: () -> Unit, onNavigate: (Screen) -> Unit) {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    val prefs by app.settings.state.collectAsState()
    val custom by app.sites.custom.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Edit?>(null) }
    var showMapping by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    fun set(change: (Prefs) -> Prefs) = app.settings.update(change)

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = try {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw IllegalStateException("Couldn't read the file")
                val restored = Backup.import(json, app.settings.value)
                app.settings.update { restored.prefs }
                AdBlock.setCustom(restored.prefs.customBlockList)
                restored.sites.forEach { (name, url) -> app.sites.add(name, url) }
                var scripts = 0
                restored.scriptUrls.forEach { url ->
                    runCatching { app.userscripts.install(app.userscripts.fetch(url), url) }.onSuccess { scripts++ }
                }
                "Restored ${restored.sites.size} sites and $scripts scripts"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Restore failed: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        ScreenFrame("Settings", onBack) {
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                item { SectionTitle("Appearance") }
                item {
                    ChoiceRow("Accent colour", ACCENTS.indices.toList(), prefs.accent, { ACCENTS[it].first }, { v -> set { it.copy(accent = v) } }, Modifier.focusRequester(first))
                }
                item { ChoiceRow("Background", Backdrop.entries, prefs.backdrop, { it.label }, { v -> set { it.copy(backdrop = v) } }) }
                item { ChoiceRow("Site card size", CardSize.entries, prefs.cardSize, { it.label }, { v -> set { it.copy(cardSize = v) } }) }
                item { ToggleRow("Show site descriptions", prefs.showDescriptions, { v -> set { it.copy(showDescriptions = v) } }) }
                item { ToggleRow("Show clock", prefs.showClock, { v -> set { it.copy(showClock = v) } }) }
                item {
                    ChoiceRow(
                        "Screen edge margin", listOf(0, 8, 16, 24, 32, 48, 64), prefs.overscanDp, { "$it dp" },
                        { v -> set { it.copy(overscanDp = v) } }, subtitle = "Raise it if the edges are cut off on your TV (overscan)"
                    )
                }

                item { SectionTitle("Games") }
                item {
                    ToggleRow("RetroTV game player", prefs.builtInPlayer, { v -> set { it.copy(builtInPlayer = v) } },
                        subtitle = "Games on EmulatorJS sites open in RetroTV's own full-screen player: no site ads, save slots, " +
                            "the remote or a controller. Each site can still use its own player (Menu ▸ Game player).")
                }
                item {
                    var size by remember { mutableStateOf(app.games.cacheSize()) }
                    SettingRow("Downloaded games", "%.1f GB · Clear".format(Locale.US, size / 1e9), {
                        app.games.clearCache()
                        size = app.games.cacheSize()
                        message = "Game cache cleared (your own ROM files are kept)"
                    }, subtitle = "Games played in RetroTV's player are kept so they start instantly and offline (up to 1.5 GB)")
                }
                item { SettingRow("Clear recent games", "", { app.library.clear(); message = "Recent games cleared" }) }

                item { SectionTitle("Browser") }
                item {
                    ChoiceRow("Default view", ViewMode.entries, prefs.viewMode, { it.label }, { v -> set { it.copy(viewMode = v) } },
                        subtitle = "Desktop: the full-size site, 1280 px wide. TV view: the mobile layout, everything larger. Each site can override it.")
                }
                item {
                    ChoiceRow("Default window", WindowMode.entries, prefs.windowMode, { it.label }, { v -> set { it.copy(windowMode = v) } },
                        subtitle = "Windowed keeps the toolbar on screen; Full screen gives the game the whole TV")
                }
                item { ChoiceRow("Search engine", UrlInput.SearchEngine.entries, prefs.searchEngine, { it.label }, { v -> set { it.copy(searchEngine = v) } }) }
                item { ChoiceRow("Text size", listOf(75, 90, 100, 115, 130, 150, 175, 200), prefs.textZoom, { "$it%" }, { v -> set { it.copy(textZoom = v) } }) }
                item { ToggleRow("Keep the screen on", prefs.keepScreenOn, { v -> set { it.copy(keepScreenOn = v) } }, subtitle = "No screensaver while a game is open") }
                item {
                    val sites = listOf("") + app.sites.all().map { it.id }
                    ChoiceRow("Start-up site", sites, prefs.startupSite.takeIf { it in sites } ?: "",
                        { id -> if (id.isEmpty()) "Home screen" else app.sites.byId(id)?.name ?: id },
                        { v -> set { it.copy(startupSite = v) } }, subtitle = "Open a site straight away when RetroTV starts")
                }
                item {
                    ToggleRow("Separate sign-ins per site app", prefs.sandboxApps, { v -> set { it.copy(sandboxApps = v) } },
                        subtitle = "Installed site apps keep their own cookies and storage (needs a recent Android System WebView)")
                }
                item {
                    SettingRow("Custom user agent", prefs.customUserAgent.ifBlank { "Automatic" }, { editing = Edit.UserAgent },
                        subtitle = "Overrides the desktop/mobile switch for every site")
                }

                item { SectionTitle("Pointer") }
                item { ChoiceRow("Pointer speed", (1..5).toList(), prefs.cursorSpeed, { "$it" }, { v -> set { it.copy(cursorSpeed = v) } }) }
                item { ChoiceRow("Pointer size", (1..4).toList(), prefs.cursorSize, { "$it" }, { v -> set { it.copy(cursorSize = v) } }) }

                item { SectionTitle("Ad blocking") }
                item {
                    ToggleRow("Block ads and trackers", prefs.adBlock, { v -> set { it.copy(adBlock = v) } },
                        subtitle = "Network blocking of ad servers only. Game and emulator files are never blocked.")
                }
                item { ToggleRow("Hide empty ad boxes", prefs.cosmetic, { v -> set { it.copy(cosmetic = v) } }) }
                item {
                    ToggleRow("Block pop-ups and redirects", prefs.blockPopups, { v -> set { it.copy(blockPopups = v) } },
                        subtitle = "Stops ad tabs and \"click anywhere\" redirects; open a blocked one from the menu")
                }
                item {
                    SettingRow("Extra block list", "${AdBlock.parseHosts(prefs.customBlockList).size} hosts", { editing = Edit.BlockList },
                        subtitle = "One host per line; hosts-file lines and ||host^ rules work")
                }
                item { SettingRow("Reset all per-site options", "", { app.settings.clearSiteOverrides(); message = "Per-site options reset" }) }

                item { SectionTitle("Controller") }
                item {
                    ChoiceRow("Controller mode", ControllerMode.entries, prefs.controllerMode, { it.label }, { v -> set { it.copy(controllerMode = v) } },
                        subtitle = "Auto: a gamepad for emulators, keyboard keys for pages that want keys (\"press any key\"). Each site can override it.")
                }
                item { SettingRow("Keyboard key mapping", if (showMapping) "Hide" else "Edit", { showMapping = !showMapping }, subtitle = "Which key each button sends in keyboard mode") }
                if (showMapping) {
                    Gamepad.BUTTONS.forEach { (button, label) ->
                        item(key = "map$button") {
                            val keys = Gamepad.KEYS.map { it.first }
                            ChoiceRow(
                                "   $label", keys, prefs.mapping[button] ?: 0, { Gamepad.keyLabel(it) },
                                { v -> set { it.copy(mapping = it.mapping + (button to v)) } }
                            )
                        }
                    }
                    item { SettingRow("Reset mapping", "", { set { it.copy(mapping = Gamepad.DEFAULT) } }) }
                }

                item { SectionTitle("Userscripts") }
                item { ToggleRow("Run userscripts", prefs.userscripts, { v -> set { it.copy(userscripts = v) } }) }
                item { SettingRow("Manage and find scripts", "Open", { onNavigate(Screen.Scripts) }) }

                item { SectionTitle("Data") }
                item {
                    SettingRow("Back up settings and sites", "Export", {
                        scope.launch {
                            message = try {
                                val urls = app.userscripts.scripts.value.mapNotNull { it.sourceUrl }
                                val json = Backup.export(app.settings.value, custom, urls)
                                val file = withContext(Dispatchers.IO) {
                                    app.downloads.saveBytes("retrotv-backup.json", "application/json", json.toByteArray())
                                }
                                "Saved ${file.name} to ${app.downloads.folderLabel}"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                "Backup failed: ${e.message}"
                            }
                        }
                    })
                }
                item {
                    SettingRow("Restore a backup", "Import", {
                        try {
                            importer.launch(arrayOf("application/json", "text/plain", "*/*"))
                        } catch (_: Exception) {
                            message = "This TV has no file picker; copy the backup with a file manager app"
                        }
                    })
                }
                item {
                    SettingRow("Show hidden sites", "${prefs.hiddenSites.size} hidden", { set { it.copy(hiddenSites = emptySet()) }; message = "All sites are visible again" })
                }
                item { SettingRow("Clear history", "", { app.history.clear(); message = "History cleared" }) }
                item {
                    SettingRow("Clear cookies and site data", "", {
                        CookieManager.getInstance().removeAllCookies(null)
                        WebStorage.getInstance().deleteAllData()
                        message = "Cookies and site data cleared (you'll be signed out of sites)"
                    })
                }
                item {
                    SettingRow("Clear cache", "", {
                        runCatching { WebView(context).apply { clearCache(true) }.destroy() }
                        message = "Cache cleared"
                    })
                }
                item { SettingRow("Reset all settings", "", { app.settings.reset(); message = "Settings reset" }) }

                item { SectionTitle("About") }
                item {
                    val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
                    val webView = runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }.getOrNull() ?: "unknown"
                    Column(Modifier.padding(8.dp)) {
                        Text("RetroTV $version", color = androidx.compose.ui.graphics.Color.White)
                        Text("Android System WebView $webView. Update it from the Play Store if games run slowly.", color = TextDim, fontSize = 14.sp)
                    }
                }
            }
        }

        when (editing) {
            Edit.UserAgent -> {
                BackHandler { editing = null }
                TextDialog("Custom user agent", "Leave empty for automatic", prefs.customUserAgent, "Save", onDismiss = { editing = null }) { text ->
                    set { it.copy(customUserAgent = text.trim()) }
                    editing = null
                }
            }
            Edit.BlockList -> {
                BackHandler { editing = null }
                TextDialog("Extra block list", "ads.example.com", prefs.customBlockList, "Save", onDismiss = { editing = null }, multiLine = true) { text ->
                    set { it.copy(customBlockList = text) }
                    AdBlock.setCustom(text)
                    editing = null
                }
            }
            null -> Unit
        }
    }
}
