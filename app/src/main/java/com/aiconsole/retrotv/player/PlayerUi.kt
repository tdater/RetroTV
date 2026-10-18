package com.aiconsole.retrotv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.ui.Ink
import com.aiconsole.retrotv.ui.MenuTile
import com.aiconsole.retrotv.ui.OverlayPanel
import com.aiconsole.retrotv.ui.TextDim
import com.aiconsole.retrotv.ui.TvButton
import kotlinx.coroutines.delay

sealed interface PlayerPanel {
    data object Menu : PlayerPanel
    data class Failed(val message: String) : PlayerPanel
}

class PlayerUiState {
    var panel by mutableStateOf<PlayerPanel?>(null)
    var started by mutableStateOf(false)
    var slot by mutableIntStateOf(1)
    var filledSlots by mutableStateOf(emptySet<Int>())
    var muted by mutableStateOf(false)
    var fast by mutableStateOf(false)
    var pointer by mutableStateOf(false)
    var toast by mutableStateOf("")
    var toastId by mutableIntStateOf(0)
}

interface PlayerActions {
    fun resume()
    fun save()
    fun load()
    fun nextSlot()
    fun restart()
    fun toggleFast()
    fun toggleMute()
    fun emulatorMenu()
    fun playOnSite()
    fun retry()
    fun exit()
}

@Composable
fun PlayerOverlay(state: PlayerUiState, title: String, system: String, canPlayOnSite: Boolean, actions: PlayerActions) {
    Box(Modifier.fillMaxSize()) {
        if (state.toast.isNotEmpty()) {
            val id = state.toastId
            LaunchedEffect(id) {
                delay(3500)
                if (state.toastId == id) state.toast = ""
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 36.dp)
                    .background(Ink.copy(alpha = 0.92f), RoundedCornerShape(24.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) { Text(state.toast, color = Color.White, fontSize = 17.sp) }
        }
        when (val p = state.panel) {
            null -> Unit
            PlayerPanel.Menu -> MenuPanel(state, title, system, canPlayOnSite, actions)
            is PlayerPanel.Failed -> FailedPanel(p.message, canPlayOnSite, actions)
        }
    }
}

@Composable
private fun MenuPanel(state: PlayerUiState, title: String, system: String, canPlayOnSite: Boolean, actions: PlayerActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel(title, Modifier.fillMaxSize(), widthFraction = 0.84f) {
        Text("$system · RetroTV player · game paused", color = TextDim, fontSize = 14.sp)
        val slot = state.slot
        val used = if (slot in state.filledSlots) "saved" else "empty"
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuTile("▶", "Resume", actions::resume, Modifier.weight(1f).focusRequester(first))
            MenuTile("💾", "Save to slot $slot", actions::save, Modifier.weight(1f))
            MenuTile("📂", "Load slot $slot ($used)", actions::load, Modifier.weight(1f))
            MenuTile("🔢", "Slot $slot of 4 · change", actions::nextSlot, Modifier.weight(1f))
            MenuTile("↺", "Restart game", actions::restart, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuTile("⏩", if (state.fast) "Fast-forward: on" else "Fast-forward: off", actions::toggleFast, Modifier.weight(1f), selected = state.fast)
            MenuTile(if (state.muted) "🔇" else "🔊", if (state.muted) "Sound: off" else "Sound: on", actions::toggleMute, Modifier.weight(1f))
            MenuTile("🛠", "Emulator settings (pointer)", actions::emulatorMenu, Modifier.weight(1f))
            if (canPlayOnSite) MenuTile("🌐", "Play on the site instead", actions::playOnSite, Modifier.weight(1f))
            MenuTile("⏻", "Exit game", actions::exit, Modifier.weight(1f))
        }
        Text(
            "Controller: Select + Start opens this menu · Remote: Back or Menu. Without a controller the remote plays: " +
                "D-pad, OK = A, Play/Pause = Start, Rewind = B, Fast-forward = X.",
            color = TextDim, fontSize = 13.sp
        )
    }
}

@Composable
private fun FailedPanel(message: String, canPlayOnSite: Boolean, actions: PlayerActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel("This game didn't start", Modifier.fillMaxSize(), widthFraction = 0.6f) {
        Text(message.ifBlank { "The emulator reported an error." }, color = Color.White, fontSize = 16.sp)
        Text(
            if (canPlayOnSite) "The site's own player may still run it." else "Check the file and the system it was opened as.",
            color = TextDim, fontSize = 14.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canPlayOnSite) TvButton("Play on the site", actions::playOnSite, Modifier.focusRequester(first), icon = "🌐")
            TvButton("Try again", actions::retry, if (canPlayOnSite) Modifier else Modifier.focusRequester(first), icon = "↺")
            TvButton("Exit", actions::exit, icon = "⏻")
        }
        Text("Built-in player: EmulatorJS", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
    }
}
