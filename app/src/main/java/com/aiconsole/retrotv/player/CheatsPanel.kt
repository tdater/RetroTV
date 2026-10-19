package com.aiconsole.retrotv.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.ui.OverlayPanel
import com.aiconsole.retrotv.ui.TextDim
import com.aiconsole.retrotv.ui.TvButton
import com.aiconsole.retrotv.ui.TvTextField

/** This game's cheats: OK toggles one, hold OK deletes it; codes can be typed or pasted as a list. */
@Composable
fun CheatsPanel(cheats: List<Cheat>, onToggle: (Int) -> Unit, onDelete: (Int) -> Unit, onAdd: (String) -> Int, onClose: () -> Unit) {
    var adding by remember { mutableStateOf(cheats.isEmpty()) }
    var text by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(adding) { runCatching { first.requestFocus() } }
    OverlayPanel("Cheats", Modifier.fillMaxSize(), widthFraction = 0.75f) {
        if (adding) {
            Text(
                "One per line: \"Name: CODE\", \"CODE Name\" or just the code. Game Genie, Action Replay and GameShark codes work when the console's emulator supports them.",
                color = TextDim, fontSize = 14.sp
            )
            TvTextField(text, { text = it }, "Cheat codes", Modifier.focusRequester(first).heightIn(min = 160.dp), singleLine = false, minLines = 5)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Add", {
                    val n = onAdd(text)
                    note = if (n == 0) "No cheat codes found in that text" else "Added $n"
                    if (n > 0) {
                        text = ""
                        adding = false
                    }
                }, icon = "＋")
                TvButton("Cancel", { adding = false; if (cheats.isEmpty()) onClose() })
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(cheats) { i, c ->
                    TvButton(
                        (if (c.on) "On · " else "Off · ") + c.name + "  (" + c.code.take(40) + ")",
                        { onToggle(i) },
                        (if (i == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth(),
                        icon = if (c.on) "☑" else "☐",
                        selected = c.on,
                        onLongClick = { onDelete(i) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Add or paste cheats", { adding = true }, if (cheats.isEmpty()) Modifier.focusRequester(first) else Modifier, icon = "＋")
                TvButton("Close", onClose, icon = "✕")
            }
            Text("OK turns a cheat on or off · hold OK to delete it. Cheats are kept for this game.", color = TextDim, fontSize = 13.sp)
        }
        if (note.isNotEmpty()) Text(note, color = TextDim, fontSize = 14.sp)
    }
}
