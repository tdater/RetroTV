package com.aiconsole.retrotv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.link.SyncPeer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Sync saves with another RetroTV on the same Wi-Fi: both TVs open this screen, then one picks the
 * other and types the code it shows. Each save goes wherever it's missing or older.
 */
@Composable
fun SyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    val peers by app.sync.peers.collectAsState()
    val url by app.link.url.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf<SyncPeer?>(null) }
    var busy by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    DisposableEffect(Unit) {
        val startedHere = app.link.url.value == null
        scope.launch {
            if (app.link.start() == null) message = "The TV isn't on Wi-Fi."
            else app.sync.start(app.link.port)
        }
        onDispose {
            app.sync.stop()
            if (startedHere) app.link.stop()
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    ScreenFrame("Sync saves with another TV", onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Open this screen on both TVs (same Wi-Fi), then pick the other TV here and type the code it shows.", color = TextDim, fontSize = 15.sp)
            if (url != null) {
                Text("This TV: ${app.sync.tvName}", color = Color.White, fontSize = 18.sp)
                Text("Code: ${app.link.syncCode}", color = MaterialTheme.colorScheme.primary, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            }
            SectionTitle("Other RetroTVs")
            if (peers.isEmpty()) Text("Looking… (the other TV needs this screen open too)", color = TextDim, fontSize = 15.sp)
            peers.forEachIndexed { i, peer ->
                TvButton(peer.name, { if (!busy) asking = peer }, if (i == 0) Modifier.focusRequester(first) else Modifier, icon = "📺")
            }
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
            Text("Newer saves win; nothing is ever deleted. Saves are matched per game and slot.", color = TextDim, fontSize = 13.sp)
        }
    }

    asking?.let { peer ->
        BackHandler { asking = null }
        TextDialog("Code shown on ${peer.name}", "6-digit code", "", "Sync", onDismiss = { asking = null }) { code ->
            asking = null
            busy = true
            message = "Syncing with ${peer.name}…"
            scope.launch {
                message = try {
                    val r = app.sync.syncWith(peer, code.trim())
                    "Done: ${r.pulled} file(s) copied here, ${r.pushed} sent there"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Sync failed: ${e.message}"
                }
                busy = false
            }
        }
    }
}
