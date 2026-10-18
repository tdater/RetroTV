package com.aiconsole.retrotv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.web.GreasyFork
import com.aiconsole.retrotv.web.ScriptResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The userscript manager: one-click Greasy Fork search (scripts for the current site, or any
 * keyword), install from a URL, and the installed list with on/off, update and remove.
 */
@Composable
fun ScriptsPanel(host: String?, modifier: Modifier = Modifier, autoSearchSite: Boolean = true) {
    val app = RetroApp.from(LocalContext.current)
    val store = app.userscripts
    val installed by store.scripts.collectAsState()
    val prefs by app.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var installUrl by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<ScriptResult>() }
    val first = remember { FocusRequester() }

    fun searchFor(label: String, block: suspend () -> List<ScriptResult>) {
        busy = true
        status = "Searching $label…"
        scope.launch {
            try {
                val found = block()
                results.clear()
                results.addAll(found.sortedByDescending { it.installs })
                status = if (found.isEmpty()) "Nothing found for $label" else "${found.size} scripts for $label"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Search failed: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun install(url: String, name: String) {
        status = "Installing $name…"
        scope.launch {
            try {
                val script = store.fetch(url)
                store.install(script, url)
                status = "Installed ${script.name}. Reload the page to run it."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Couldn't install: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { first.requestFocus() }
        if (autoSearchSite && !host.isNullOrEmpty()) searchFor(host) { GreasyFork.forSite(host) }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TvButton("Find scripts", { tab = 0 }, Modifier.focusRequester(first), icon = "🔍", selected = tab == 0)
            TvButton("Installed (${installed.size})", { tab = 1 }, icon = "📜", selected = tab == 1)
            TvButton(
                if (prefs.userscripts) "Scripts: On" else "Scripts: Off",
                { app.settings.update { it.copy(userscripts = !it.userscripts) } },
                icon = if (prefs.userscripts) "✅" else "⛔"
            )
        }
        if (status.isNotEmpty()) Text(status, color = TextDim, fontSize = 15.sp)

        if (tab == 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvTextField(query, { query = it }, "Search Greasy Fork", Modifier.weight(1f), onDone = {
                    if (query.isNotBlank()) searchFor("\"$query\"") { GreasyFork.search(query) }
                })
                TvButton("Search", { if (query.isNotBlank()) searchFor("\"$query\"") { GreasyFork.search(query) } }, enabled = !busy)
            }
            val chips = buildList {
                if (!host.isNullOrEmpty()) add("This site" to host)
                addAll(listOf("Ad block" to "adblock", "Anti-adblock" to "anti adblock", "Fullscreen" to "fullscreen",
                    "Gamepad" to "gamepad", "Dark mode" to "dark mode", "Popup blocker" to "popup blocker", "Archive.org" to "archive.org"))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chips) { (label, q) ->
                    TvButton(label, {
                        if (label == "This site" && host != null) searchFor(host) { GreasyFork.forSite(host) }
                        else { query = q; searchFor("\"$q\"") { GreasyFork.search(q) } }
                    })
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(results, key = { it.id }) { r ->
                    val isInstalled = installed.any { it.sourceUrl == r.codeUrl || it.script.name == r.name }
                    TvClickable(onClick = { if (!isInstalled) install(r.codeUrl, r.name) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.02f) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(r.description, color = TextDim, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${r.installs} installs · rating ${if (r.rating >= 0) "+" else ""}${r.rating}", color = TextDim, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            if (isInstalled) Pill("Installed", Color(0xFF3DDC84)) else Pill("Install")
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvTextField(installUrl, { installUrl = it }, "Install from .user.js URL", Modifier.weight(1f), onDone = {
                    if (installUrl.isNotBlank()) install(installUrl.trim(), installUrl.trim())
                })
                TvButton("Install", { if (installUrl.isNotBlank()) install(installUrl.trim(), installUrl.trim()) })
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Check for updates", {
                    status = "Checking…"
                    scope.launch {
                        val n = runCatching { store.updateAll() }.getOrDefault(0)
                        status = if (n == 0) "Everything is up to date" else "Updated $n scripts"
                    }
                }, icon = "⟳")
            }
            if (installed.isEmpty()) Text("No userscripts installed yet. Use Find scripts.", color = TextDim)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f, fill = false)) {
                items(installed, key = { it.script.id }) { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvClickable(
                            onClick = { scope.launch { store.setEnabled(s.script.id, !s.enabled) } },
                            modifier = Modifier.weight(1f),
                            focusedScale = 1.02f
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.script.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    val where = (s.script.matches + s.script.includes).take(3).joinToString("  ").ifEmpty { "All sites" }
                                    Text("v${s.script.version.ifEmpty { "?" }} · $where", color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Pill(if (s.enabled) "On" else "Off", if (s.enabled) Color(0xFF3DDC84) else TextDim)
                            }
                        }
                        TvButton("Remove", { scope.launch { store.remove(s.script.id); status = "Removed ${s.script.name}" } }, icon = "🗑")
                    }
                }
            }
        }
        Text(
            "Scripts run in every frame at the page's @run-at time. GM_* helpers are emulated; cross-site GM_xmlhttpRequest is limited to CORS.",
            color = TextDim.copy(alpha = 0.7f), fontSize = 12.sp, style = MaterialTheme.typography.bodySmall
        )
    }
}
