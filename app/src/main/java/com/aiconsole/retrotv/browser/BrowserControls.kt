package com.aiconsole.retrotv.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.lifecycle.lifecycleScope
import com.aiconsole.retrotv.apps.SiteApps
import com.aiconsole.retrotv.data.ControllerMode
import com.aiconsole.retrotv.data.SitePrefs
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.data.ViewMode
import com.aiconsole.retrotv.data.WindowMode
import com.aiconsole.retrotv.downloads.DownloadItem
import com.aiconsole.retrotv.downloads.SavedFile
import com.aiconsole.retrotv.ui.MainActivity
import com.aiconsole.retrotv.web.Userscript
import kotlinx.coroutines.launch

// The browser's toolbar and menu commands ([BrowserActions]).

internal fun BrowserActivity.browserControls(): BrowserActions = object : BrowserActions {
    // (fixThisPage: see below; the ladder itself is in FixLadder.kt)

    override fun show(panel: Panel?) {
        ui.panel = panel
        cursorView.shown = panel == null && ui.inputMode == InputMode.Cursor
        if (panel == null) webView?.requestFocus()
    }

    override fun back() {
        show(null)
        webView?.takeIf { it.canGoBack() }?.goBack()
    }

    override fun forward() {
        show(null)
        webView?.takeIf { it.canGoForward() }?.goForward()
    }

    override fun reload() {
        show(null)
        val wv = webView ?: return
        if (ui.loading) wv.stopLoading() else wv.reload()
    }

    override fun home() {
        show(null)
        if (ui.standalone || isTaskRoot) {
            startActivity(Intent(this@browserControls, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        if (!ui.standalone) finish()
    }

    override fun siteStart() {
        show(null)
        webView?.loadUrl(ui.site?.url ?: startUrl)
    }

    override fun load(text: String) {
        val url = UrlInput.toUrl(text, settings.value.searchEngine)
        if (url.isEmpty()) return
        show(null)
        applySiteSettings(UrlInput.host(url).orEmpty())
        webView?.loadUrl(url)
    }

    override fun voiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a website or search")
        try {
            voice.launch(intent)
        } catch (_: ActivityNotFoundException) {
            toast("Voice search isn't available on this TV")
        }
    }

    override fun toggleWindow() {
        ui.windowed = !ui.windowed
        settings.update { it.copy(windowMode = if (ui.windowed) WindowMode.Windowed else WindowMode.Fullscreen) }
        applyWindowMode()
        toast(if (ui.windowed) "Windowed: toolbar shown" else "Full screen: hold OK or press Menu for controls")
    }

    override fun toggleFocusGame() {
        show(null)
        val wv = webView ?: return
        wv.evaluateJavascript(PageScripts.FOCUS_GAME) { result ->
            when (result?.trim('"')) {
                "on" -> {
                    windowedBeforeFocus = ui.windowed
                    ui.focusGame = true
                    ui.windowed = false
                    applyWindowMode()
                    toast("Game focused · Back to restore")
                }
                "off" -> {
                    ui.focusGame = false
                    ui.windowed = windowedBeforeFocus
                    applyWindowMode()
                }
                else -> toast("No game found on this page yet: start the game first")
            }
        }
    }

    override fun toggleDesktop() {
        val host = ui.host
        settings.updateSite(host) { it.copy(viewMode = if (ui.desktop) ViewMode.Mobile else ViewMode.Desktop) }
        applySiteSettings(host)
        toast(if (ui.desktop) "Desktop view for $host: full-size layout, 1280 px wide" else "TV view for $host: mobile layout, everything larger")
        show(null)
        webView?.reload()
    }

    override fun zoom(delta: Int) {
        webView?.zoomBy(if (delta > 0) 1.2f else 1f / 1.2f)
    }

    override fun toggleInput() {
        ui.inputMode = if (ui.inputMode == InputMode.Cursor) InputMode.Dpad else InputMode.Cursor
        cursor.releaseAll()
        cursorView.shown = ui.inputMode == InputMode.Cursor && ui.panel == null
        toast(if (ui.inputMode == InputMode.Cursor) "Remote: pointer" else "Remote: D-pad keys (arrows + Enter) · Play/Pause to switch back")
    }

    override fun toggleAdBlock() {
        val host = ui.host
        settings.updateSite(host) { it.copy(adBlock = if (ui.siteAdBlock) false else null) }
        applySiteSettings(host)
        toast(if (ui.siteAdBlock) "Ad blocking on for $host" else "Ad blocking off for $host")
        webView?.reload()
    }

    override fun toggleGamePlayer() {
        val host = ui.host
        val next = !ui.gamePlayer
        sitePlayer -= host
        settings.updateSite(host) { it.copy(player = if (next == settings.value.builtInPlayer) null else next) }
        applySiteSettings(host)
        show(null)
        toast(if (ui.gamePlayer) "Games on $host open in RetroTV's player" else "Games on $host use the site's own player")
        webView?.reload()
    }

    override fun cycleController() {
        val modes = ControllerMode.entries
        val next = modes[(modes.indexOf(ui.controllerMode) + 1) % modes.size]
        settings.updateSite(ui.host) { it.copy(controllerMode = next) }
        applySiteSettings(ui.host)
        releaseKeys()
        toast("Controller: ${next.label} on ${ui.host}")
    }

    override fun fixThisPage() {
        val host = ui.host
        val tried = ui.fixTried[host].orEmpty()
        // Steps are judged against the state before the first fix, so the count stays stable and
        // a step never "changes" a setting back to what it was.
        val state = ui.fixBase[host] ?: FixLadder.State(ui.desktop, ui.siteAdBlock, ui.gamePlayer, ui.controllerMode.name)
        val step = FixLadder.next(tried, state)
        if (step == null) {
            // Everything was tried: put the site's options back as they were and start over.
            fixOriginal.remove(host)?.let { orig -> settings.updateSite(host) { orig } }
            ui.fixTried = ui.fixTried - host
            ui.fixBase = ui.fixBase - host
            sitePlayer -= host
            applySiteSettings(host)
            show(null)
            toast("Fixes reset for $host · tap Fix it to start again")
            webView?.reload()
            return
        }
        val (n, total) = FixLadder.progress(tried, state)
        if (step.persists) fixOriginal.getOrPut(host) { settings.site(host) }
        ui.fixBase = ui.fixBase + (host to state)
        ui.fixTried = ui.fixTried + (host to (tried + step))
        show(null)
        // The winning combination stays: every persisting step is written to this site's options.
        when (step) {
            FixLadder.Step.Reload -> webView?.reload()
            FixLadder.Step.TogglePlayer -> toggleGamePlayer()
            FixLadder.Step.ToggleView -> toggleDesktop()
            FixLadder.Step.AdBlockOff -> toggleAdBlock()
            FixLadder.Step.KeyboardKeys -> setController(ControllerMode.Keyboard)
            FixLadder.Step.GamepadApi -> setController(ControllerMode.Native)
            FixLadder.Step.BothControllers -> setController(ControllerMode.Both)
            FixLadder.Step.Report -> openSettings()
        }
        toast(
            if (step == FixLadder.Step.Report) "Last step ($n of $total): Settings ▸ Report a problem"
            else "Fix $n of $total: ${step.label} · play again, tap Fix it if still broken"
        )
    }

    private fun setController(mode: ControllerMode) {
        settings.updateSite(ui.host) { it.copy(controllerMode = mode) }
        applySiteSettings(ui.host)
        releaseKeys()
        webView?.reload()
    }

    override fun toggleControllerBrowse() {
        ui.controllerBrowse = !ui.controllerBrowse
        releaseKeys()
        cursorView.shown = true
        toast(if (ui.controllerBrowse) "Controller moves the pointer · X to play again" else "Controller plays the game")
    }

    override fun installApp() {
        show(null)
        val site = appSite()
        if (site != null && SiteApps.hasAlias(this@browserControls, site)) {
            val installed = SiteApps.isInstalled(this@browserControls, site)
            SiteApps.setInstalled(this@browserControls, site, !installed)
            toast(if (installed) "${site.name} app removed" else "${site.name} installed: it's now in your TV's apps")
            return
        }
        val name = ui.title.ifEmpty { ui.host }
        val url = ui.url.ifEmpty { startUrl }
        if (SiteApps.pinShortcut(this@browserControls, name, url, 0xFF7E57C2)) {
            toast("Asked the launcher to add \"$name\"")
        } else {
            app.sites.add(name, url)
            toast("This launcher can't add shortcuts, so \"$name\" was added to the RetroTV home screen")
        }
    }

    override fun addToHome() {
        show(null)
        app.sites.add(ui.title.ifEmpty { ui.host }, ui.url.ifEmpty { startUrl })
        toast("Added to the RetroTV home screen")
    }

    override fun openDownloads() {
        show(null)
        startActivity(MainActivity.intent(this@browserControls, MainActivity.SCREEN_DOWNLOADS))
    }

    override fun openSettings() {
        show(null)
        startActivity(MainActivity.intent(this@browserControls, MainActivity.SCREEN_SETTINGS))
    }

    override fun openBlocked() {
        val url = ui.blockedPopup ?: return
        ui.blockedPopup = null
        show(null)
        webView?.loadUrl(url)
    }

    override fun exit() {
        show(null)
        if (ui.standalone) finishAndRemoveTask() else finish()
    }

    override fun installScript(script: Userscript, url: String) {
        lifecycleScope.launch {
            app.userscripts.install(script, url)
            show(null)
            toast("Installed ${script.name}")
            webView?.reload()
        }
    }

    override fun installApk(item: DownloadItem) = app.downloads.open(this@browserControls, item)

    override fun openSaved(file: SavedFile) = app.downloads.openSaved(this@browserControls, file)

    override fun updateSite(change: (SitePrefs) -> SitePrefs) {
        settings.updateSite(ui.host, change)
        applySiteSettings(ui.host)
        webView?.reload()
    }
}
