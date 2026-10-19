package com.aiconsole.retrotv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.aiconsole.retrotv.data.SiteSearch
import com.aiconsole.retrotv.player.LibraryRules
import com.aiconsole.retrotv.player.Systems

/** Search every game site at once: matches in My games (by console), then one tile per site. */
@Composable
fun SearchScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    val prefs by app.settings.state.collectAsState()
    val custom by app.sites.custom.collectAsState()
    val games by app.library.recent.collectAsState()
    var typed by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    val sites = remember(custom, prefs.hiddenSites) { app.sites.all().filter { it.id !in prefs.hiddenSites } }
    val mine = if (query.isBlank()) emptyList() else LibraryRules.filter(games, null, query)
    val bySystem = mine.groupBy { Systems.of(it.request.core)?.id ?: it.request.core }

    ScreenFrame("Search all sites", onBack) {
        LazyVerticalGrid(
            GridCells.Adaptive(250.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TvTextField(typed, { typed = it }, "Game name, e.g. Contra", Modifier.focusRequester(field), onDone = { query = typed.trim() })
            }
            if (query.isBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Type a game and press OK. Your own games show first, then a search on every site.", color = TextDim, fontSize = 15.sp)
                }
                return@LazyVerticalGrid
            }
            bySystem.forEach { (system, list) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        SectionTitle("My games · ${Systems.label(system)}")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(list, key = { it.request.key }) { g -> GameTile(g, { playRecent(context, g) }, {}, Modifier.width(150.dp)) }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Search \"$query\" on") }
            items(sites, key = { it.id }) { site ->
                val color = Color(site.color)
                TvClickable(
                    onClick = { onOpenUrl(SiteSearch.url(site, query, prefs.searchEngine)) },
                    modifier = Modifier.aspectRatio(16f / 7f),
                    background = color.copy(alpha = 0.5f),
                    focusedBackground = color.copy(alpha = 0.85f),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(site.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("🔎 $query", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
