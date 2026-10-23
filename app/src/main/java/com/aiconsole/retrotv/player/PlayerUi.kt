package com.aiconsole.retrotv.player

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.ui.Ink
import com.aiconsole.retrotv.ui.MenuTile
import com.aiconsole.retrotv.ui.OverlayPanel
import com.aiconsole.retrotv.ui.TextDim
import com.aiconsole.retrotv.ui.TvClickable
import com.aiconsole.retrotv.ui.TvButton
import kotlinx.coroutines.delay

sealed interface PlayerPanel {
    data object Menu : PlayerPanel
    data object Players : PlayerPanel
    data object Cheats : PlayerPanel
    /** Optional player setup before a multiplayer game starts. */
    data object Coop : PlayerPanel
    /** One-time offer when the game runs slower than the console: turn on Smooth mode. */
    data object Choppy : PlayerPanel
    data class Failed(val message: String, val hint: String = "") : PlayerPanel
}

class PlayerUiState {
    var panel by mutableStateOf<PlayerPanel?>(null)
    var started by mutableStateOf(false)
    var slot by mutableIntStateOf(1)
    /** Filled slots → time saved, and their screenshots. */
    var slotTimes by mutableStateOf(emptyMap<Int, Long>())
    var thumbs by mutableStateOf(emptyMap<Int, ImageBitmap>())
    var muted by mutableStateOf(false)
    var fast by mutableStateOf(false)
    var stats by mutableStateOf(false)
    var pointer by mutableStateOf(false)
    var toast by mutableStateOf("")
    var toastId by mutableIntStateOf(0)
    var players by mutableStateOf(listOf<String?>(null, null, null, null))
    var claiming by mutableStateOf<Int?>(null)
    var filter by mutableStateOf("disabled")
    var cheats by mutableStateOf(emptyList<Cheat>())
    /** Game download so far / total (-1 unknown); null when nothing is being fetched. */
    var fetch by mutableStateOf<Pair<Long, Long>?>(null)
    /** The controls help is open; [helpAuto] closes it after a few seconds (first launch). */
    var help by mutableStateOf(false)
    var helpAuto by mutableStateOf(false)
    var helpController by mutableStateOf(false)
    var autoResume by mutableStateOf(false)
    /** The console's [Systems] id (player count for the co-op screen). */
    var systemId by mutableStateOf<String?>(null)
    /** Smooth mode (slow TV boxes), Compatibility graphics (WebGL 1 cores) and Frame sync for this game. */
    var smooth by mutableStateOf(false)
    var compat by mutableStateOf(false)
    var vsync by mutableStateOf(true)
}

interface PlayerActions {
    fun resume()
    fun save()
    fun load()
    fun selectSlot(slot: Int)
    fun restart()
    fun toggleFast()
    fun toggleMute()
    fun toggleStats()
    fun emulatorMenu()
    fun openPlayers()
    fun openMenu()
    fun cycleFilter()
    fun openCheats()
    fun toggleCheat(index: Int)
    fun deleteCheat(index: Int)
    fun addCheats(text: String): Int
    fun claimPlayer(slot: Int)
    fun buttonLayout()
    fun useLayoutForConsole()
    fun resetConsoleLayout()
    fun toggleAutoResume()
    fun toggleSmooth()
    fun toggleVsync()
    fun toggleCompat()
    /** The choppy-game prompt: [turnOn] enables Smooth mode, otherwise just closes it. */
    fun answerChoppy(turnOn: Boolean)
    fun finishCoop()
    fun showHelp()
    fun hideHelp()
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
            PlayerPanel.Players -> PlayersPanel(state, actions)
            PlayerPanel.Coop -> CoopPanel(state, actions)
            PlayerPanel.Choppy -> ChoppyPanel(actions)
            PlayerPanel.Cheats -> CheatsPanel(state.cheats, actions::toggleCheat, actions::deleteCheat, actions::addCheats) { actions.openMenu() }
            is PlayerPanel.Failed -> FailedPanel(p.message, p.hint, canPlayOnSite, actions)
        }
        val fetch = state.fetch
        if (fetch != null && !state.started && state.panel == null) FetchBar(fetch.first, fetch.second)
        if (state.help) ControlsHelpCard(state, actions)
    }
}

/** "Fetching game… 40%" with a determinate bar (indeterminate when the size is unknown). */
@Composable
private fun BoxScope.FetchBar(done: Long, total: Long) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 36.dp)
            .fillMaxWidth(0.5f)
            .background(Ink.copy(alpha = 0.92f), RoundedCornerShape(16.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(ProgressText.format(done, total), color = Color.White, fontSize = 17.sp)
        val fraction = ProgressText.fraction(done, total)
        // No endless animation while the game loads: an unknown size shows the text only.
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
    }
}

/** What the buttons do; closes after 8 s on first launch (helpAuto) or on any key (see PlayerActivity). */
@Composable
private fun ControlsHelpCard(state: PlayerUiState, actions: PlayerActions) {
    if (state.helpAuto) LaunchedEffect(Unit) {
        delay(8000)
        actions.hideHelp()
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .fillMaxWidth(0.7f)
                .background(Ink.copy(alpha = 0.96f), RoundedCornerShape(20.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(ControlsHelp.TITLE, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            ControlsHelp.lines(state.helpController).forEach { Text(it, color = Color.White, fontSize = 17.sp) }
            Text(ControlsHelp.DISMISS, color = TextDim, fontSize = 14.sp)
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
        val used = if (slot in state.slotTimes) "saved" else "empty"
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuTile("▶", "Resume", actions::resume, Modifier.weight(1f).focusRequester(first))
            MenuTile("💾", "Save to slot $slot", actions::save, Modifier.weight(1f))
            MenuTile("📂", "Load slot $slot ($used)", actions::load, Modifier.weight(1f))
            MenuTile("↺", "Restart game", actions::restart, Modifier.weight(1f))
            MenuTile("🖥", "Filter: ${Filters.label(state.filter)}", actions::cycleFilter, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (n in 1..SaveNames.SLOTS) SlotCard(n, n == slot, state.slotTimes[n], state.thumbs[n], { actions.selectSlot(n) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuTile("⏩", if (state.fast) "Fast-forward: on" else "Fast-forward: off", actions::toggleFast, Modifier.weight(1f), selected = state.fast)
            MenuTile(if (state.muted) "🔇" else "🔊", if (state.muted) "Sound: off" else "Sound: on", actions::toggleMute, Modifier.weight(1f))
            MenuTile("📈", if (state.stats) "Speed info: on" else "Speed info: off", actions::toggleStats, Modifier.weight(1f), selected = state.stats)
            MenuTile("🎮", "Players", actions::openPlayers, Modifier.weight(1f))
            MenuTile("🧪", "Cheats" + state.cheats.count { it.on }.let { if (it > 0) " ($it on)" else "" }, actions::openCheats, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuTile("🕹", "Button layout", actions::buttonLayout, Modifier.weight(1f))
            MenuTile("❓", "Show controls help", actions::showHelp, Modifier.weight(1f))
            MenuTile("🛠", "Emulator settings (pointer)", actions::emulatorMenu, Modifier.weight(1f))
            if (canPlayOnSite) MenuTile("🌐", "Play on the site instead", actions::playOnSite, Modifier.weight(1f))
            MenuTile("⏻", "Exit game", actions::exit, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuTile("⏯", if (state.autoResume) "Auto-resume: on" else "Auto-resume: off", actions::toggleAutoResume, Modifier.weight(1f), selected = state.autoResume)
            MenuTile("📌", "Use this layout for all $system games", actions::useLayoutForConsole, Modifier.weight(1f))
            MenuTile("♻", "Reset $system layout", actions::resetConsoleLayout, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MenuTile("🐢", if (state.smooth) "Smooth mode: on" else "Smooth mode: off", actions::toggleSmooth, Modifier.weight(1f), selected = state.smooth)
            MenuTile("⏱", if (state.vsync) "Frame sync: on" else "Frame sync: off", actions::toggleVsync, Modifier.weight(1f), selected = state.vsync)
            MenuTile("🧱", if (state.compat) "Compatibility graphics: on (next start)" else "Compatibility graphics: off (next start)", actions::toggleCompat, Modifier.weight(1f), selected = state.compat)
        }
        Text(
            "Controller: Select + Start opens this menu · Select + L1 quick save, Select + R1 quick load (slot $slot), " +
                "hold Select + L2 to rewind, Select + R2 fast-forward. Remote: Back or Menu. Without a controller the remote plays: " +
                "D-pad, OK = A, Play/Pause = Start, Rewind = B (or rewind, when it's on), Fast-forward = X.",
            color = TextDim, fontSize = 13.sp
        )
    }
}

/** One save slot: its screenshot (or "Empty") and when it was saved; OK selects it. */
@Composable
private fun SlotCard(n: Int, selected: Boolean, savedAt: Long?, thumb: ImageBitmap?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    TvClickable(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        background = if (selected) accent.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surface,
        focusedBackground = if (selected) accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceVariant,
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                if (thumb != null) Image(thumb, "Slot $n", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(if (savedAt != null) "Saved" else "Empty", color = TextDim, fontSize = 13.sp)
            }
            Text(
                "Slot $n" + (savedAt?.let { " · " + DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) } ?: ""),
                color = Color.White, fontSize = 13.sp, maxLines = 1, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/** Which controller is which player: pick a row, then press Start on the controller that should be it. */
@Composable
private fun PlayersPanel(state: PlayerUiState, actions: PlayerActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel("Players", Modifier.fillMaxSize(), widthFraction = 0.6f) {
        Text("Choose a player, then press Start on the controller that should be that player. Players swap places.", color = TextDim, fontSize = 14.sp)
        state.players.forEachIndexed { i, name ->
            val waiting = state.claiming == i
            TvButton(
                "Player ${i + 1}: " + if (waiting) "press Start on a controller…" else name ?: "nobody",
                { actions.claimPlayer(i) },
                if (i == 0) Modifier.focusRequester(first) else Modifier,
                icon = if (waiting) "⏳" else "🎮",
                selected = waiting
            )
        }
        Text("Back returns to the game menu.", color = TextDim, fontSize = 13.sp)
    }
}

/** Before a multiplayer game starts: who is player 1-4. Skippable; Continue, Start (no row chosen) or Back begins the game. */
@Composable
private fun CoopPanel(state: PlayerUiState, actions: PlayerActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel("Who's playing?", Modifier.fillMaxSize(), widthFraction = 0.6f) {
        Text("Choose a player, then press Start on the controller that should be that player.", color = TextDim, fontSize = 14.sp)
        val labels = CoopSetup.labels(state.systemId, state.players, state.claiming)
        labels.forEachIndexed { i, label ->
            val waiting = state.claiming == i
            TvButton(label, { actions.claimPlayer(i) }, if (i == 0) Modifier.focusRequester(first) else Modifier, icon = if (waiting) "⏳" else "🎮", selected = waiting)
        }
        TvButton("Start the game", actions::finishCoop, icon = "▶")
        Text("Back skips this. You can change players later in the game menu.", color = TextDim, fontSize = 13.sp)
    }
}

/** "Choppy?": shown once per game when the game runs well below the console's speed. */
@Composable
private fun ChoppyPanel(actions: PlayerActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel("Choppy? Turn on Smooth mode", Modifier.fillMaxSize(), widthFraction = 0.6f) {
        Text("This game is running slower than it should on this TV. Smooth mode turns off filters, rewind and the speed readout to lighten the load.", color = Color.White, fontSize = 16.sp)
        Text("You can change it any time in the game menu or Settings.", color = TextDim, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Turn on", { actions.answerChoppy(true) }, Modifier.focusRequester(first), icon = "🐢")
            TvButton("Not now", { actions.answerChoppy(false) })
        }
    }
}

@Composable
private fun FailedPanel(message: String, hint: String, canPlayOnSite: Boolean, actions: PlayerActions) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel("This game didn't start", Modifier.fillMaxSize(), widthFraction = 0.6f) {
        Text(message.ifBlank { "The emulator reported an error." }, color = Color.White, fontSize = 16.sp)
        Text(
            hint.ifBlank { if (canPlayOnSite) "The site's own player may still run it." else "Check the file and the system it was opened as." },
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
