package com.aiconsole.retrotv.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.aiconsole.retrotv.player.Systems
import com.aiconsole.retrotv.data.Http
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.downloads.ApkSource
import com.aiconsole.retrotv.downloads.DownloadItem
import com.aiconsole.retrotv.downloads.EmulatorApp
import com.aiconsole.retrotv.downloads.Emulators
import com.aiconsole.retrotv.downloads.OpenAction
import com.aiconsole.retrotv.downloads.OpenPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TABS = listOf("Emulator apps", "Game sources", "My downloads", "Direct link")

/**
 * Emulator APKs straight from their developers (no Play Store), places to get games, the
 * download list (install APKs, open ROMs in an emulator) and a direct-link downloader.
 */
@Composable
fun DownloadsScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    ScreenFrame("Emulators & downloads", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            TABS.forEachIndexed { i, t ->
                TvButton(t, { tab = i }, if (i == 0) Modifier.focusRequester(first) else Modifier, selected = tab == i)
            }
        }
        when (tab) {
            0 -> EmulatorList(onOpenUrl)
            1 -> GameSources(onOpenUrl)
            2 -> DownloadList()
            else -> DirectLink()
        }
    }
}

@Composable
private fun EmulatorList(onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    val scope = rememberCoroutineScope()
    val status = remember { mutableStateMapOf<String, String>() }
    val is64 = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    fun get(emu: EmulatorApp) {
        if (emu.source is ApkSource.Page) {
            onOpenUrl(emu.source.url)
            return
        }
        status[emu.name] = "Finding the latest version…"
        scope.launch {
            try {
                val url = Emulators.resolve(emu.source, Build.VERSION.SDK_INT, is64)
                val name = app.downloads.enqueue(url, Http.USER_AGENT, null, "application/vnd.android.package-archive", null)
                status[emu.name] = "Downloading $name. You'll be asked to install it when it finishes."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status[emu.name] = "Couldn't get it (${e.message}). Try the website."
            }
        }
    }

    LaunchedEffect(Unit) {
        app.downloads.completed.collect { item ->
            if (item.done && item.isApk) app.downloads.open(context as Activity, item)
        }
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Apps download from their official sources (GitHub releases, buildbots, project sites). " +
                    "The first install asks you to allow RetroTV to install apps.",
                color = TextDim, fontSize = 14.sp
            )
        }
        items(Emulators.APPS, key = { it.name }) { emu ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvClickable(onClick = { get(emu) }, modifier = Modifier.weight(1f), focusedScale = 1.02f) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(emu.name, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Text(emu.systems, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                            Text(emu.description, color = TextDim, fontSize = 13.sp)
                            status[emu.name]?.let { Text(it, color = Color(0xFFFFD23F), fontSize = 13.sp) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Pill(if (emu.source is ApkSource.Page) "Website" else "Download APK")
                    }
                }
                TvButton("Website", { onOpenUrl(emu.homepage) }, icon = "🌐")
            }
        }
    }
}

@Composable
private fun GameSources(onOpenUrl: (String) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Open a source, then pick a file: downloads land in your Downloads folder; game files play in RetroTV. " +
                    "Any site works; add your own in Direct link or on the home screen.",
                color = TextDim, fontSize = 14.sp
            )
        }
        items(Emulators.GAME_SOURCES, key = { it.url }) { s ->
            TvClickable(onClick = { onOpenUrl(s.url) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.02f) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(s.description, color = TextDim, fontSize = 13.sp)
                    Text(s.url, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun DownloadList() {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    var items by remember { mutableStateOf<List<DownloadItem>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    val roms = rememberRomPicker { note = it }
    LaunchedEffect(tick) {
        while (true) {
            items = runCatching { app.downloads.list() }.getOrDefault(emptyList())
            delay(if (items.any { it.running }) 1000 else 4000)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Saved to ${app.downloads.folderLabel}", color = TextDim, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TvButton("Clear finished", { app.downloads.clearFinished(); tick++ }, icon = "🧹")
            }
            if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
            if (items.isEmpty()) Text("Nothing downloaded yet.", color = TextDim)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvClickable(
                            onClick = { if (d.done) app.downloads.open(context as Activity, d, roms.play) },
                            modifier = Modifier.weight(1f),
                            focusedScale = 1.02f
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.title, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        d.statusLabel + (if (d.total > 0) " · ${d.total / 1_048_576} MB" else ""),
                                        color = if (d.failed) Color(0xFFFF6B6B) else TextDim, fontSize = 13.sp
                                    )
                                }
                                if (d.done) Pill(
                                    when (OpenPolicy.decide(d.title, d.mimeType)) {
                                        OpenAction.InstallApk -> "Install"
                                        OpenAction.PlayInRetroTV -> "Play in RetroTV"
                                        OpenAction.OpenWith -> "Open with…"
                                    }
                                )
                            }
                        }
                        if (d.done && !d.isApk && Systems.isRom(d.title)) {
                            TvButton("Play", {
                                val uri = app.downloads.uriFor(d)
                                if (uri != null) roms.play(uri, d.title)
                            }, icon = "🎮")
                        }
                        TvButton("Delete", { app.downloads.remove(d.id); tick++ }, icon = "🗑")
                    }
                }
            }
        }
        roms.Dialog()
    }
}

@Composable
private fun DirectLink() {
    val app = RetroApp.from(LocalContext.current)
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    fun go() {
        val u = UrlInput.toUrl(url)
        if (!u.startsWith("http")) {
            message = "Enter a full http(s) link"
            return
        }
        message = try {
            "Downloading ${app.downloads.enqueue(u, Http.USER_AGENT)}. See My downloads."
        } catch (e: Exception) {
            "Couldn't start: ${e.message}"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Paste or type any direct file link: an APK, a ROM, a zip, a BIOS…", color = TextDim)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvTextField(url, { url = it }, "https://…", Modifier.weight(1f), onDone = ::go)
            TvButton("Download", ::go, icon = "⬇")
        }
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary)
    }
}
