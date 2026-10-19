package com.aiconsole.retrotv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.link.PhonePads
import com.aiconsole.retrotv.link.ReceivedRom
import com.aiconsole.retrotv.player.GameRequest
import com.aiconsole.retrotv.player.PlayerActivity
import com.aiconsole.retrotv.player.Systems
import kotlinx.coroutines.launch

/**
 * Phone link: a QR code a phone on the same Wi-Fi scans to get a controller, send games and back
 * up saves. The link keeps running after this screen closes (so the phone can play) until Stop or
 * 30 idle minutes.
 */
@Composable
fun PhoneLinkScreen(onBack: () -> Unit, extra: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    val url by app.link.url.collectAsState()
    val phones by PhonePads.connected.collectAsState()
    val received by app.link.routes.received.collectAsState()
    var picking by remember { mutableStateOf<ReceivedRom?>(null) }
    fun play(rom: ReceivedRom, core: String) {
        val title = rom.name.substringBeforeLast('.')
        context.startActivity(PlayerActivity.intent(context, GameRequest(core = core, game = rom.value, gameName = title, title = title)))
    }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (app.link.url.value == null && app.link.start() == null) message = "The TV isn't on Wi-Fi, so a phone can't reach it."
        runCatching { first.requestFocus() }
    }

    ScreenFrame("Phone link", onBack) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val u = url
            if (u != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    QrCode(u, Modifier.size(240.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Scan with a phone on the same Wi-Fi", color = Color.White, fontSize = 20.sp)
                        Text(u, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
                        Text(if (phones == 0) "No phone connected yet" else "$phones phone(s) connected as controllers", color = Color.White, fontSize = 15.sp)
                        Text("• Controller: a touch gamepad, up to 4 phones as extra players.", color = TextDim, fontSize = 15.sp)
                        Text("• Send a game: ROM files from the phone appear below with a Play button.", color = TextDim, fontSize = 15.sp)
                        Text("• Saves: download a backup to the phone or restore one.", color = TextDim, fontSize = 15.sp)
                        Text("The link stays on while you play and turns itself off after 30 minutes unused.", color = TextDim, fontSize = 13.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TvButton("Stop the phone link", { app.link.stop(); message = "Phone link stopped" }, Modifier.focusRequester(first), icon = "⏹")
                }
            } else {
                Text("The phone link is off.", color = Color.White, fontSize = 18.sp)
                TvButton("Start the phone link", {
                    scope.launch { message = if (app.link.start() == null) "The TV isn't on Wi-Fi, so a phone can't reach it." else "" }
                }, Modifier.focusRequester(first), icon = "📱")
            }
            if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
            if (received.isNotEmpty()) {
                SectionTitle("Sent from your phone")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(received, key = { it.value }) { rom ->
                        TvButton(rom.name, {
                            val system = Systems.forFile(rom.name)
                            if (system != null) play(rom, system.id) else picking = rom
                        }, icon = "▶")
                    }
                }
            }
            extra()
        }
    }
    picking?.let { rom ->
        BackHandler { picking = null }
        SystemPicker(rom.name, onPick = { core -> picking = null; play(rom, core) }, onDismiss = { picking = null })
    }
}
