package com.aiconsole.retrotv.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.apps.SiteApps
import com.aiconsole.retrotv.browser.BrowserActivity
import com.aiconsole.retrotv.data.ControllerMode
import com.aiconsole.retrotv.data.Site
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.data.ViewMode
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private const val FAVORITES = "★ Favorites"
private const val ALL = "All"

@Composable
fun HomeScreen(onNavigate: (Screen) -> Unit, onOpen: (Site) -> Unit, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    val prefs by app.settings.state.collectAsState()
    val custom by app.sites.custom.collectAsState()
    val history by app.history.entries.collectAsState()
    val games by app.library.recent.collectAsState()
    var note by remember { mutableStateOf("") }
    val roms = rememberRomPicker { note = it }
    var filter by rememberSaveable { mutableStateOf(ALL) }
    var menuFor by remember { mutableStateOf<Site?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) } // "go", "add", "rename"
    var refresh by remember { mutableStateOf(0) }
    val firstCard = remember { FocusRequester() }

    val all = remember(custom) { app.sites.all() }
    val visible = all.filter { it.id !in prefs.hiddenSites }
        .sortedByDescending { it.id in prefs.favorites }
    val tags = listOf(ALL, FAVORITES) + visible.flatMap { it.tags }.distinct()
    val shown = when (filter) {
        ALL -> visible
        FAVORITES -> visible.filter { it.id in prefs.favorites }
        else -> visible.filter { filter in it.tags }
    }

    LaunchedEffect(Unit) { runCatching { firstCard.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(prefs.cardSize.widthDp.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Header(prefs.showClock, onNavigate, onGo = { dialog = "go" }, onPlayRom = roms.open)
            }
            if (note.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text(note, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tags) { t -> TvButton(t, { filter = t }, selected = filter == t) }
                }
            }
            if (games.isNotEmpty() && filter == ALL) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    RecentGamesRow(games) { app.library.remove(it.request.key) }
                }
            }
            if (history.isNotEmpty() && filter == ALL) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        SectionTitle("Continue playing")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(history.take(10), key = { it.url }) { h ->
                                TvClickable(onClick = { onOpenUrl(h.url) }, modifier = Modifier.width(260.dp)) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(h.title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(UrlInput.host(h.url).orEmpty(), color = TextDim, fontSize = 12.sp, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle(if (filter == ALL) "Game sites" else filter) }
            items(shown, key = { it.id }) { site ->
                val index = shown.indexOf(site)
                SiteCard(
                    site = site,
                    favorite = site.id in prefs.favorites,
                    installed = remember(site.id, refresh) { SiteApps.isInstalled(context, site) },
                    showDescription = prefs.showDescriptions,
                    onClick = { onOpen(site) },
                    onMenu = { menuFor = site },
                    modifier = if (index == 0) Modifier.focusRequester(firstCard) else Modifier
                )
            }
            item {
                TvClickable(onClick = { dialog = "add" }, modifier = Modifier.aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("＋", fontSize = 40.sp, color = MaterialTheme.colorScheme.primary)
                        Text("Add a site", color = Color.White, fontSize = 16.sp)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Hold OK (or press Menu) on a site for options: favorite, install as its own app, view mode, ad blocking.",
                    color = TextDim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        }

        roms.Dialog()
        menuFor?.let { site ->
            BackHandler { menuFor = null }
            SiteMenu(
                site = site,
                onClose = { menuFor = null; refresh++ },
                onOpen = { menuFor = null; onOpen(site) },
                onRename = { dialog = "rename" }
            )
        }
        when (dialog) {
            "go" -> {
                BackHandler { dialog = null }
                TextDialog("Go to a website", "Address or search", "", "Go", onDismiss = { dialog = null }) { text ->
                    dialog = null
                    val url = UrlInput.toUrl(text, prefs.searchEngine)
                    if (url.isNotEmpty()) onOpenUrl(url)
                }
            }
            "add" -> {
                BackHandler { dialog = null }
                AddSiteDialog(onDismiss = { dialog = null }) { name, url ->
                    dialog = null
                    app.sites.add(name, url)
                }
            }
            "rename" -> {
                val site = menuFor
                BackHandler { dialog = null }
                TextDialog("Rename", "Name", site?.name.orEmpty(), "Save", onDismiss = { dialog = null }) { text ->
                    dialog = null
                    if (site != null && text.isNotBlank()) app.sites.rename(site.id, text.trim())
                    menuFor = null
                }
            }
        }
    }
}

@Composable
private fun Header(showClock: Boolean, onNavigate: (Screen) -> Unit, onGo: () -> Unit, onPlayRom: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("RETRO", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 3.sp)
                    Text("TV", fontSize = 40.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, letterSpacing = 3.sp)
                }
                Text("Every retro console, no emulators to install", color = TextDim, fontSize = 14.sp)
            }
            Spacer(Modifier.weight(1f))
            if (showClock) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(15_000)
                        now = System.currentTimeMillis()
                    }
                }
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light)
            }
        }
        // Their own row: next to the logo and clock they ran out of room on a TV and were cut off.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { TvButton("Go to a site", onGo, icon = "🔍") }
            item { TvButton("My games", { onNavigate(Screen.Library) }, icon = "🕹") }
            item { TvButton("Play a ROM file", onPlayRom, icon = "🎮") }
            item { TvButton("Emulators & downloads", { onNavigate(Screen.Downloads) }, icon = "⬇") }
            item { TvButton("Scripts", { onNavigate(Screen.Scripts) }, icon = "📜") }
            item { TvButton("History", { onNavigate(Screen.History) }, icon = "🕘") }
            item { TvButton("Settings", { onNavigate(Screen.Settings) }, icon = "⚙") }
        }
    }
}

@Composable
private fun SiteCard(
    site: Site,
    favorite: Boolean,
    installed: Boolean,
    showDescription: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = Color(site.color)
    TvClickable(
        onClick = onClick,
        onLongClick = onMenu,
        modifier = modifier.aspectRatio(16f / 9f),
        focusedScale = 1.08f,
        background = color.copy(alpha = 0.55f),
        focusedBackground = color.copy(alpha = 0.85f),
        onKey = { e ->
            if (e.keyCode == KeyEvent.KEYCODE_MENU || e.keyCode == KeyEvent.KEYCODE_INFO) {
                if (e.action == KeyEvent.ACTION_UP) onMenu()
                true
            } else false
        },
        contentAlignment = Alignment.BottomStart
    ) { focused ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))), RoundedCornerShape(14.dp))
        )
        Text(
            site.name.take(1).uppercase(),
            fontSize = 64.sp,
            fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = 0.16f),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp)
        )
        Row(Modifier.align(Alignment.TopStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (favorite) Pill("★", Color(0xFFFFD23F))
            if (installed) Pill("App", Color(0xFF3DDC84))
            if (!site.builtIn) Pill("Mine", Color.White)
        }
        Column(Modifier.padding(14.dp)) {
            Text(site.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showDescription || focused) {
                Text(site.description, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Long-press menu for a site card. */
@Composable
private fun SiteMenu(site: Site, onClose: () -> Unit, onOpen: () -> Unit, onRename: () -> Unit) {
    val context = LocalContext.current
    val app = RetroApp.from(context)
    val prefs by app.settings.state.collectAsState()
    val siteVersion by app.settings.siteVersion.collectAsState()
    val first = remember { FocusRequester() }
    var message by remember { mutableStateOf("") }
    val host = site.host
    val sp = remember(siteVersion, host) { app.settings.site(host) }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    OverlayPanel(site.name, Modifier.fillMaxSize(), widthFraction = 0.8f) {
        Text(site.url, color = TextDim, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Play", onOpen, Modifier.weight(1f).focusRequester(first), icon = "▶")
            TvButton("Open as app", {
                context.startActivity(SiteApps.appIntent(context, site.url, site.name, site.id))
                onClose()
            }, Modifier.weight(1f), icon = "🪟")
            val fav = site.id in prefs.favorites
            TvButton(if (fav) "Unfavorite" else "Favorite", {
                app.settings.update { p -> p.copy(favorites = if (fav) p.favorites - site.id else p.favorites + site.id) }
            }, Modifier.weight(1f), icon = "★")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (SiteApps.hasAlias(context, site)) {
                val installed = SiteApps.isInstalled(context, site)
                TvButton(if (installed) "Remove app" else "Install as app", {
                    SiteApps.setInstalled(context, site, !installed)
                    message = if (installed) "Removed from the TV's apps" else "Installed: ${site.name} now appears in your TV's apps row"
                }, Modifier.weight(1f), icon = "📲")
            } else {
                TvButton("Pin to launcher", {
                    message = if (SiteApps.pinShortcut(context, site.name, site.url, site.color)) "Asked the launcher to add it"
                    else "This launcher doesn't support pinned shortcuts; use Open as app"
                }, Modifier.weight(1f), icon = "📌")
            }
            TvButton("Hide", {
                app.settings.update { p -> p.copy(hiddenSites = p.hiddenSites + site.id) }
                onClose()
            }, Modifier.weight(1f), icon = "🙈")
            if (!site.builtIn) {
                TvButton("Rename", onRename, Modifier.weight(1f), icon = "✏")
                TvButton("Delete", { app.sites.remove(site.id); onClose() }, Modifier.weight(1f), icon = "🗑")
            }
        }
        ChoiceRow(
            "View", listOf<ViewMode?>(null, ViewMode.Desktop, ViewMode.Mobile), sp.viewMode,
            { it?.label ?: "Default (${prefs.viewMode.label})" }, { v -> app.settings.updateSite(host) { it.copy(viewMode = v) } }
        )
        ChoiceRow(
            "Ad blocking", listOf<Boolean?>(null, true, false), sp.adBlock,
            { when (it) { null -> "Default"; true -> "On"; false -> "Off for this site" } },
            { v -> app.settings.updateSite(host) { it.copy(adBlock = v) } }
        )
        ChoiceRow(
            "Game player", listOf<Boolean?>(null, true, false), sp.player,
            { when (it) { null -> "Default (${if (prefs.builtInPlayer) "RetroTV" else "site's own"})"; true -> "RetroTV player"; false -> "Site's own player" } },
            { v -> app.settings.updateSite(host) { it.copy(player = v) } }
        )
        ChoiceRow(
            "Controller", listOf<ControllerMode?>(null) + ControllerMode.entries, sp.controllerMode,
            { it?.label ?: "Default (${prefs.controllerMode.label})" }, { v -> app.settings.updateSite(host) { it.copy(controllerMode = v) } }
        )
        ChoiceRow(
            "Start RetroTV on this site", listOf(false, true), prefs.startupSite == site.id,
            { if (it) "Yes" else "No" }, { v -> app.settings.update { it.copy(startupSite = if (v) site.id else "") } }
        )
        if (message.isNotEmpty()) Text(message, color = MaterialTheme.colorScheme.primary, fontSize = 15.sp)
        TvButton("Close", onClose, icon = "✕")
    }
}

@Composable
fun TextDialog(title: String, label: String, initial: String, action: String, onDismiss: () -> Unit, multiLine: Boolean = false, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    OverlayPanel(title, Modifier.fillMaxSize(), widthFraction = 0.6f) {
        TvTextField(
            text, { text = it }, label, Modifier.focusRequester(field).let { if (multiLine) it.height(220.dp) else it },
            singleLine = !multiLine, onDone = { onSubmit(text) }, minLines = if (multiLine) 6 else 1
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton(action, { onSubmit(text) }, icon = "✓")
            TvButton("Cancel", onDismiss)
        }
    }
}

@Composable
private fun AddSiteDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    OverlayPanel("Add a site", Modifier.fillMaxSize(), widthFraction = 0.6f) {
        TvTextField(url, { url = it }, "Address (e.g. example.com)", Modifier.focusRequester(field))
        TvTextField(name, { name = it }, "Name (optional)", onDone = { if (url.isNotBlank()) onAdd(name, url) })
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvButton("Add", { if (url.isNotBlank()) onAdd(name, url) }, icon = "＋")
            TvButton("Cancel", onDismiss)
        }
    }
}

/** Shared frame for the secondary screens: title, Back button, content. */
@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            TvButton("Back", onBack, icon = "◀")
            Spacer(Modifier.width(18.dp))
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        content()
    }
}

/** Opens a URL in the browser (used by screens that only have a context). */
fun openInBrowser(context: android.content.Context, url: String) =
    context.startActivity(BrowserActivity.open(context, url))
