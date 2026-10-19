package com.aiconsole.retrotv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.data.BuiltInSites

private val TEST_BUTTONS = listOf("A", "B", "X", "Y", "L1", "R1", "L2", "R2", "Select", "Start", "▲", "▼", "◀", "▶")

/**
 * First-run setup: welcome, a controller button test, favourite sites and a tour of the remote.
 * Everything works with just the remote; [onDone] marks the setup finished.
 */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val app = RetroApp.from(LocalContext.current)
    val prefs by app.settings.state.collectAsState()
    var step by remember { mutableIntStateOf(0) }
    var pressed by remember { mutableStateOf(emptySet<String>()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(step) { runCatching { first.requestFocus() } }
    BackHandler { if (step > 0) step-- else onDone() }

    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            val native = e.nativeKeyEvent
            val label = ButtonNames.label(native.keyCode)
            if (step == 1 && label != null && e.type == KeyEventType.KeyDown) pressed = pressed + label
            // On the controller test, face and shoulder buttons only light up; the D-pad and OK still move and click.
            step == 1 && KeyEvent.isGamepadButton(native.keyCode)
        },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Welcome to RetroTV · step ${step + 1} of 5", color = TextDim, fontSize = 15.sp)
        when (step) {
            0 -> {
                Text("Every retro console, no emulators to install", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("A minute of setup: check your controller, pick favourite sites and learn the remote. You can skip any step.", color = TextDim, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton("Let's go", { step = 1 }, Modifier.focusRequester(first), icon = "▶")
                    TvButton("Skip setup", onDone)
                }
            }
            1 -> {
                Text("Controller test", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Press every button on your controller; each one lights up. No controller? The remote works everywhere: skip this.", color = TextDim, fontSize = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TEST_BUTTONS.forEach { b -> Pill(b, if (b in pressed) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.25f)) }
                }
                Text("${pressed.size} of ${TEST_BUTTONS.size} buttons seen", color = Color.White, fontSize = 16.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(if (pressed.isEmpty()) "Skip (no controller)" else "Looks good", { step = 2 }, Modifier.focusRequester(first), icon = "✓")
                }
            }
            2 -> {
                Text("Favourite sites", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Favourites come first on the home screen. Press OK to add or remove one.", color = TextDim, fontSize = 17.sp)
                LazyVerticalGrid(
                    GridCells.Adaptive(220.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().height(300.dp)
                ) {
                    items(BuiltInSites.ALL, key = { it.id }) { site ->
                        val fav = site.id in prefs.favorites
                        TvButton(site.name, {
                            app.settings.update { p -> p.copy(favorites = if (fav) p.favorites - site.id else p.favorites + site.id) }
                        }, if (site == BuiltInSites.ALL.first()) Modifier.focusRequester(first) else Modifier, icon = if (fav) "★" else "☆", selected = fav)
                    }
                }
                TvButton("Next", { step = 3 }, icon = "▶")
            }
            3 -> {
                Text("The remote", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                listOf(
                    "D-pad moves the pointer on game sites; OK clicks. Push against an edge to scroll.",
                    "Hold OK, or press Menu, for every option on a site.",
                    "Back goes back and leaves full screen.",
                    "CH+ and CH− scroll a page at a time.",
                    "Play/Pause switches to D-pad keys, so you can play with just the remote.",
                    "In RetroTV's player: Back or Menu opens the game menu (save, load, filters, cheats)."
                ).forEach { Text("•  $it", color = Color.White, fontSize = 18.sp) }
                TvButton("Next", { step = 4 }, Modifier.focusRequester(first), icon = "▶")
            }
            else -> {
                Text("All set", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("Open a site and start a game. My games collects what you play; Phone link turns a phone into a controller.", color = TextDim, fontSize = 18.sp)
                TvButton("Start playing", onDone, Modifier.focusRequester(first), icon = "🎮")
            }
        }
    }
}
