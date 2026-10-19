package com.aiconsole.retrotv.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.player.GameRequest
import com.aiconsole.retrotv.player.PlayerActivity
import com.aiconsole.retrotv.player.RecentGame
import com.aiconsole.retrotv.player.Systems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Starts a recent game: from the cache / own file, or the site page when the file is gone. */
fun playRecent(context: Context, game: RecentGame) {
    val app = RetroApp.from(context)
    val r = game.request
    if (app.games.isAvailable(r.game)) {
        context.startActivity(PlayerActivity.intent(context, r))
    } else if (r.page.startsWith("http")) {
        openInBrowser(context, r.page)
    } else {
        app.library.remove(r.key)
    }
}

@Composable
fun RecentGamesRow(games: List<RecentGame>, onRemove: (RecentGame) -> Unit) {
    val context = LocalContext.current
    Column {
        SectionTitle("Recent games")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(games.take(15), key = { it.request.key }) { g ->
                GameTile(g, { playRecent(context, g) }, { onRemove(g) }, Modifier.width(150.dp))
            }
        }
        Text("Recent games open straight in RetroTV's player · hold OK to remove one · My games has them all", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Opens the file picker ([open]) or plays a file ([play]); [Dialog] asks for the console when needed. */
class RomPicker(val open: () -> Unit, val play: (Uri, String) -> Unit, val Dialog: @Composable () -> Unit)

/**
 * "Play a ROM file": copy the file into RetroTV's game folder, ask for the console when the file
 * type doesn't say, and start the player.
 */
@Composable
fun rememberRomPicker(onMessage: (String) -> Unit): RomPicker {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) } // file value, name

    fun start(value: String, name: String, core: String) {
        val request = GameRequest(core = core, game = value, gameName = name.substringBeforeLast('.'), title = name.substringBeforeLast('.'))
        context.startActivity(PlayerActivity.intent(context, request))
    }

    fun play(uri: Uri, name: String) {
        onMessage("Loading $name…")
        scope.launch {
            try {
                val value = withContext(Dispatchers.IO) { app.games.importRom(uri, name) }
                onMessage("")
                val system = Systems.forFile(name)
                if (system != null) start(value, name, system.id) else pending = value to name
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onMessage("Couldn't open that file: ${e.message}")
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) play(uri, displayName(context, uri) ?: "game")
    }
    val open: () -> Unit = {
        try {
            picker.launch(arrayOf("*/*"))
        } catch (_: Exception) {
            onMessage("This TV has no file picker. Download ROMs from Emulators & downloads instead.")
        }
    }
    val dialog: @Composable () -> Unit = {
        pending?.let { (value, name) ->
            BackHandler { pending = null }
            SystemPicker(name, onPick = { core -> pending = null; start(value, name, core) }, onDismiss = { pending = null })
        }
    }
    return RomPicker(open, ::play, dialog)
}

@Composable
fun SystemPicker(fileName: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel("Which console is $fileName for?", Modifier.fillMaxSize(), widthFraction = 0.8f) {
        Text("Archives and disc images don't say. Pick the system to run it with.", color = TextDim, fontSize = 14.sp)
        LazyVerticalGrid(
            GridCells.Fixed(4),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(380.dp)
        ) {
            items(Systems.ALL, key = { it.id }) { s ->
                TvButton(s.label, { onPick(s.id) }, if (s == Systems.ALL.first()) Modifier.focusRequester(first) else Modifier)
            }
        }
        TvButton("Cancel", onDismiss)
        Text("Arcade zips: FinalBurn Neo first; MAME 2003 for older romsets.", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
    }
}

private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')
