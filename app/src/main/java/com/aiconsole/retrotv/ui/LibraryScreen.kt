package com.aiconsole.retrotv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.player.LibraryRules
import com.aiconsole.retrotv.player.LibrarySort
import com.aiconsole.retrotv.player.RecentGame
import com.aiconsole.retrotv.player.Systems

/** "My games": every game played in RetroTV's player, as box art, by console, with play time. */
@Composable
fun LibraryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    val games by app.library.recent.collectAsState()
    var system by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.Recent) }
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<RecentGame?>(null) }
    val shown = LibraryRules.sort(LibraryRules.filter(games, system, query), sort)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    ScreenFrame("My games", onBack) {
        if (games.isEmpty()) {
            Text(
                "Games you play in RetroTV's player collect here with their box art. Open a game on any site, or Play a ROM file from the home screen.",
                color = TextDim, fontSize = 16.sp
            )
            return@ScreenFrame
        }
        LazyVerticalGrid(
            GridCells.Adaptive(170.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvTextField(query, { query = it }, "Search my games", Modifier.weight(1f))
                    ChoiceRow("Sort", LibrarySort.entries, sort, { it.label }, { sort = it }, Modifier.weight(1f))
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { TvButton("All (${games.size})", { system = null }, selected = system == null) }
                    items(LibraryRules.systems(games)) { id -> TvButton(Systems.label(id), { system = id }, selected = system == id) }
                }
            }
            items(shown, key = { it.request.key }) { g ->
                GameTile(g, { playRecent(context, g) }, { menuFor = g }, if (g == shown.first()) Modifier.focusRequester(first) else Modifier)
            }
        }
    }

    menuFor?.let { g ->
        BackHandler { menuFor = null }
        GameMenu(g, onClose = { menuFor = null })
    }
}

/** A library game: box art, title, console and play time; hold OK for its menu. */
@Composable
fun GameTile(g: RecentGame, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val r = g.request
    TvClickable(onClick = onClick, onLongClick = onLongClick, modifier = modifier) {
        Column(Modifier.padding(8.dp)) {
            ArtImage(r.core, r.displayTitle, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            Text(
                (if (g.favorite) "★ " else "") + r.displayTitle,
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                listOf(Systems.label(r.core), LibraryRules.playTime(g.playMs)).filter { it.isNotEmpty() }.joinToString(" · "),
                color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun GameMenu(g: RecentGame, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    val r = g.request
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    OverlayPanel(r.displayTitle, Modifier.fillMaxSize(), widthFraction = 0.7f) {
        val from = UrlInput.host(r.page)?.removePrefix("www.") ?: "your file"
        Text("${Systems.label(r.core)} · from $from · played ${g.plays}× ${LibraryRules.playTime(g.playMs)}".trim(), color = TextDim, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Play", { onClose(); playRecent(context, g) }, Modifier.weight(1f).focusRequester(first), icon = "▶")
            TvButton(if (g.favorite) "Unfavorite" else "Favorite", { app.library.setFavorite(r.key, !g.favorite); onClose() }, Modifier.weight(1f), icon = "★")
            if (r.page.startsWith("http")) TvButton("Open the site page", { onClose(); openInBrowser(context, r.page) }, Modifier.weight(1f), icon = "🌐")
            TvButton("Remove", { app.library.remove(r.key); onClose() }, Modifier.weight(1f), icon = "🗑")
        }
        Text("Saves stay on the TV when a game is removed from this list.", color = TextDim, fontSize = 13.sp)
        TvButton("Close", onClose, icon = "✕")
    }
}
