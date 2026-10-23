package com.aiconsole.retrotv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.player.RecentGame

/** The home screen's collection rows: one row of box art per collection that has games. */
@Composable
fun CollectionsRows(collections: List<GameCollection>, games: List<RecentGame>, onRemove: (GameCollection, RecentGame) -> Unit) {
    val context = LocalContext.current
    Column {
        collections.forEach { c ->
            val inCollection = CollectionRules.resolve(c, games) { it.request.key }
            if (inCollection.isNotEmpty()) {
                SectionTitle(c.name)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(inCollection, key = { it.request.key }) { g ->
                        GameTile(g, { playRecent(context, g) }, { onRemove(c, g) }, Modifier.width(150.dp))
                    }
                }
            }
        }
        Text("Collections open straight in RetroTV's player · hold OK to take a game out of its collection", color = TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** "Add to collection…" for one game: each collection toggles with OK, plus a new one. */
@Composable
fun CollectionPicker(game: RecentGame, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { CollectionStore.from(ctx) }
    val collections by store.collections.collectAsState()
    var naming by remember { mutableStateOf(false) }
    val key = game.request.key
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler { if (naming) naming = false else onClose() }

    OverlayPanel("Add to collection", Modifier.fillMaxSize(), widthFraction = 0.6f) {
        Text(game.request.displayTitle, color = TextDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (collections.isEmpty()) Text("No collections yet. Make one, like \"Couch co-op\" or \"Kids\".", color = TextDim, fontSize = 14.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            items(collections, key = { it.id }) { c ->
                val inside = key in c.keys
                TvButton(
                    (if (inside) "✓ " else "") + c.name + "  (${c.keys.size})",
                    { store.toggle(c.id, key) },
                    Modifier.fillMaxWidth().let { if (c == collections.first()) it.focusRequester(first) else it },
                    selected = inside
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("New collection…", { naming = true }, if (collections.isEmpty()) Modifier.focusRequester(first) else Modifier, icon = "＋")
            TvButton("Done", onClose, icon = "✓")
        }
    }
    if (naming) {
        TextDialog("New collection", "Name (e.g. Couch co-op)", "", "Create", onDismiss = { naming = false }) { text ->
            naming = false
            store.create(text)?.let { store.add(it, key) }
        }
    }
}

/** Rename and delete collections (from My games). Deleting a collection never touches the games. */
@Composable
fun ManageCollections(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { CollectionStore.from(ctx) }
    val collections by store.collections.collectAsState()
    var renaming by remember { mutableStateOf<GameCollection?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var refocus by remember { mutableStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    // A deleted collection takes its focused button along: put focus on "New collection…".
    LaunchedEffect(refocus) { if (refocus > 0) runCatching { first.requestFocus() } }
    BackHandler { onClose() }

    OverlayPanel("Collections", Modifier.fillMaxSize(), widthFraction = 0.7f) {
        if (collections.isEmpty()) Text("No collections yet. Hold OK on a game in My games, then Collections.", color = TextDim, fontSize = 14.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            items(collections, key = { it.id }) { c ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(
                        "${c.name} · ${c.keys.size} ${PlayStats.plural(c.keys.size, "game")}",
                        color = Color.White, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    TvButton("Rename", { renaming = c }, icon = "✏")
                    if (confirm == c.id) TvButton("Really delete?", { store.delete(c.id); confirm = null; refocus++ }, icon = "🗑", selected = true)
                    else TvButton("Delete", { confirm = c.id }, icon = "🗑")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("New collection…", { creating = true }, Modifier.focusRequester(first), icon = "＋")
            TvButton("Close", onClose, icon = "✕")
        }
    }
    renaming?.let { c ->
        BackHandler { renaming = null }
        TextDialog("Rename collection", "Name", c.name, "Save", onDismiss = { renaming = null }) { text ->
            renaming = null
            store.rename(c.id, text)
        }
    }
    if (creating) {
        BackHandler { creating = false }
        TextDialog("New collection", "Name (e.g. Kids)", "", "Create", onDismiss = { creating = false }) { text ->
            creating = false
            store.create(text)
        }
    }
}

/**
 * Settings ▸ Home screen: pick a row with OK, then Show/Hide, Move up or Move down. The buttons
 * stay put while the list changes, so the D-pad never loses its place.
 */
@Composable
fun HomeLayoutPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { HomeLayoutStore.from(ctx) }
    val rows by store.rows.collectAsState()
    var selected by remember { mutableStateOf(HomeLayout.DEFAULT_ORDER.first()) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    BackHandler { onClose() }

    OverlayPanel("Home screen", Modifier.fillMaxSize(), widthFraction = 0.6f) {
        Text("Choose a row, then show or hide it or move it up or down. The tag filter and the site cards stay together in Game sites.", color = TextDim, fontSize = 14.sp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEachIndexed { i, r ->
                TvButton(
                    "${i + 1}. ${HomeLayout.label(r.id)}  ·  ${if (r.visible) "shown" else "hidden"}",
                    { selected = r.id },
                    Modifier.fillMaxWidth().let { if (i == 0) it.focusRequester(first) else it },
                    selected = r.id == selected
                )
            }
        }
        val current = rows.firstOrNull { it.id == selected }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(if (current?.visible == false) "Show" else "Hide", { store.set(HomeLayout.toggle(rows, selected)) }, icon = "👁")
            TvButton("Move up", { store.set(HomeLayout.moveUp(rows, selected)) }, icon = "▲")
            TvButton("Move down", { store.set(HomeLayout.moveDown(rows, selected)) }, icon = "▼")
            TvButton("Reset", { store.reset() }, icon = "↺")
            TvButton("Done", onClose, icon = "✓")
        }
    }
}
