package com.aiconsole.retrotv.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.browser.BrowserActivity
import com.aiconsole.retrotv.data.Site

enum class Screen { Home, Downloads, Scripts, Settings, History }

/** The RetroTV home: site grid plus the downloads, scripts, history and settings screens. */
class MainActivity : ComponentActivity() {

    private val screen = mutableStateOf(Screen.Home)

    /** Opened from the browser for one screen: Back returns to the browser, not the home grid. */
    private var direct = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = RetroApp.from(this)
        screen.value = screenFrom(intent)
        direct = intent?.hasExtra(EXTRA_SCREEN) == true
        // Start-up site: launching RetroTV can go straight into a favourite site.
        if (savedInstanceState == null && intent?.action == Intent.ACTION_MAIN) {
            app.sites.byId(app.settings.value.startupSite)?.let { openSite(it) }
        }
        setContent {
            val prefs by app.settings.state.collectAsState()
            val current by screen
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
                        }
                    }
                }
            }
        }
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
