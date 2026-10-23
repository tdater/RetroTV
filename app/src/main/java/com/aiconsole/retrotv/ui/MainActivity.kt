package com.aiconsole.retrotv.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.browser.BrowserActivity
import com.aiconsole.retrotv.data.Site
import com.aiconsole.retrotv.player.SaveNames
import kotlinx.coroutines.delay

enum class Screen { Home, Downloads, Scripts, Settings, History, Library, Search, Phone, Sync, Setup }

/** The RetroTV home: site grid plus the downloads, scripts, history and settings screens. */
class MainActivity : ComponentActivity() {

    private val screen = mutableStateOf(Screen.Home)

    /** Opened from the browser for one screen: Back returns to the browser, not the home grid. */
    private var direct = false

    /** Attract mode on screen, and the last key or stick input (it starts after idle time on the home screen). */
    private val attract = mutableStateOf(false)
    @Volatile private var lastInput = SystemClock.uptimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = RetroApp.from(this)
        screen.value = screenFrom(intent)
        direct = intent?.hasExtra(EXTRA_SCREEN) == true
        val firstRun = !direct && !app.settings.value.setupDone
        if (firstRun) screen.value = Screen.Setup
        // Start-up site: launching RetroTV can go straight into a favourite site.
        if (!firstRun && savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) {
            app.sites.byId(app.settings.value.startupSite)?.let { openSite(it) }
        }
        setContent {
            val prefs by app.settings.state.collectAsState()
            val current by screen
            val showAttract by attract
            LaunchedEffect(current) {
                while (current == Screen.Home) {
                    delay(10_000)
                    if (!attract.value && AttractRules.due(lastInput, SystemClock.uptimeMillis(), app.settings.value.attractAfterMin)) attract.value = true
                }
            }
            RetroTheme(prefs) {
                RetroBackground(prefs) {
                    Box(Modifier.fillMaxSize().padding(horizontal = prefs.overscanDp.dp + 8.dp, vertical = (prefs.overscanDp * 0.6f).dp + 8.dp)) {
                        val goHome: () -> Unit = ::leaveScreen
                        if (current != Screen.Home) BackHandler { goHome() }
                        when (current) {
                            Screen.Home -> HomeScreen(onNavigate = { screen.value = it }, onOpen = ::openSite, onOpenUrl = ::openUrl)
                            Screen.Downloads -> DownloadsScreen(onBack = goHome, onOpenUrl = ::openUrl)
                            Screen.Scripts -> ScreenFrame("Userscripts", goHome) { ScriptsPanel(null, Modifier.fillMaxSize(), autoSearchSite = false) }
                            Screen.Settings -> SettingsScreen(onBack = goHome, onNavigate = { screen.value = it })
                            Screen.History -> HistoryScreen(onBack = goHome, onOpenUrl = ::openUrl)
                            Screen.Library -> LibraryScreen(onBack = goHome)
                            Screen.Search -> SearchScreen(onBack = goHome, onOpenUrl = ::openUrl)
                            Screen.Phone -> PhoneLinkScreen(onBack = goHome)
                            Screen.Sync -> SyncScreen(onBack = goHome)
                            Screen.Setup -> SetupScreen(onDone = {
                                app.settings.update { it.copy(setupDone = true) }
                                screen.value = Screen.Home
                            })
                        }
                    }
                    if (showAttract) {
                        val slides = remember {
                            val games = app.library.recent.value
                            // Saved-state screenshots (slot PNGs) of each game, mixed in with the box art.
                            val shots = games.associate { g ->
                                g.request.key to (1..SaveNames.SLOTS).mapNotNull { app.saves.thumbnail(g.request.key, it)?.path }
                            }
                            AttractRules.slides(games, app.sites.all().filter { it.id !in prefs.hiddenSites }, shots = shots, round = (System.currentTimeMillis() / 60_000).toInt())
                        }
                        AttractMode(slides)
                    }
                }
            }
        }
    }

    // Any input resets the idle timer; the key that ends the attract mode does nothing else.
    // RestrictedApi is lint's false positive on super.dispatchKeyEvent.
    @SuppressLint("RestrictedApi", "GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (attract.value) {
            if (event.action == KeyEvent.ACTION_UP) attract.value = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        lastInput = SystemClock.uptimeMillis()
        if (attract.value) {
            attract.value = false
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        lastInput = SystemClock.uptimeMillis()
        attract.value = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_SCREEN)) {
            screen.value = screenFrom(intent)
            direct = true
        }
    }

    private fun leaveScreen() {
        if (direct) finish() else screen.value = Screen.Home
    }

    private fun screenFrom(intent: Intent?): Screen =
        intent?.getStringExtra(EXTRA_SCREEN)?.let { name -> Screen.entries.firstOrNull { it.name == name } } ?: Screen.Home

    fun openSite(site: Site) = startActivity(BrowserActivity.open(this, site.url, site.id, site.name))

    fun openUrl(url: String) = startActivity(BrowserActivity.open(this, url))

    companion object {
        const val EXTRA_SCREEN = "screen"
        val SCREEN_DOWNLOADS = Screen.Downloads.name
        val SCREEN_SETTINGS = Screen.Settings.name

        fun intent(context: Context, screen: String): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_SCREEN, screen)
    }
}
