package com.aiconsole.retrotv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.aiconsole.retrotv.RetroApp
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenUrl: (String) -> Unit) {
    val app = RetroApp.from(LocalContext.current)
    val history by app.history.entries.collectAsState()
    var query by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    ScreenFrame("History", onBack) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 10.dp)) {
            TvTextField(query, { query = it }, "Search history", Modifier.weight(1f).focusRequester(first))
            TvButton("Clear all", { app.history.clear() }, icon = "🗑")
        }
        val q = query.trim().lowercase()
        val shown = if (q.isEmpty()) history else history.filter { it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
        if (shown.isEmpty()) Text("Nothing here yet.", color = TextDim)
        val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.url }) { h ->
                TvClickable(onClick = { onOpenUrl(h.url) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(h.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${fmt.format(Date(h.time))} · ${h.url}", color = TextDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
