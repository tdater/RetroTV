package com.aiconsole.retrotv.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.ScriptHandler
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.apps.SiteApps
import com.aiconsole.retrotv.data.ACCENTS
import com.aiconsole.retrotv.data.BuiltInSites
import com.aiconsole.retrotv.data.ControllerMode
import com.aiconsole.retrotv.data.SitePrefs
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.data.WindowMode
import com.aiconsole.retrotv.downloads.DownloadItem
import com.aiconsole.retrotv.downloads.SavedFile
import com.aiconsole.retrotv.player.PlayerActivity
import com.aiconsole.retrotv.ui.RetroTheme
import com.aiconsole.retrotv.web.AdBlock
import com.aiconsole.retrotv.web.Userscript
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The TV browser. One WebView plus:
 * - a remote-driven pointer ([CursorController]) or D-pad-keys mode;
 * - controllers fed to pages as gamepads ([VirtualPads]) and/or keyboard keys ([Gamepad]);
 * - ad, pop-up and redirect blocking ([AdBlock]) that never touches the page itself;
 * - windowed / full-screen modes, HTML5 full screen and "Focus game";
 * - downloads (APKs install, ROMs open in emulator apps) and userscripts.
 */
class BrowserActivity : ComponentActivity(), BrowserActions {

    internal val app by lazy { RetroApp.from(this) }
    internal val settings get() = app.settings
    internal val ui = BrowserUiState()
    internal val pads = VirtualPads()
    internal val handler = Handler(Looper.getMainLooper())
    internal val controls: BrowserActions by lazy { browserControls() }

    internal lateinit var root: FrameLayout
    internal lateinit var webFrame: FrameLayout
    internal lateinit var fullFrame: FrameLayout
    internal lateinit var cursorView: CursorView
    internal lateinit var cursor: CursorController
    internal lateinit var overlay: ComposeView

    internal var webView: WebView? = null
    internal var customView: View? = null
    internal var customCallback: WebChromeClient.CustomViewCallback? = null
    internal val scriptHandlers = mutableListOf<ScriptHandler>()
    internal var docStartScripts = false
    internal var defaultUa = ""
    internal var startUrl = "about:blank"
    internal var profileName: String? = null

    @Volatile
    internal var adBlockActive = true

    /** RetroTV's player takes games on this page (read by the game hook from any frame). */
    @Volatile
    internal var playerActive = true

    /** Hosts where the user chose the site's own player for this visit. */
    internal val sitePlayer = HashSet<String>()
    internal var lastLaunch = 0L
    internal val gameHook by lazy { runCatching { assets.open("js/game-hook.js").bufferedReader().use { it.readText() } }.getOrDefault("") }

    /** Auto controller mode: buttons that were sent as keys (their release must be too). */
    internal val autoKeys = HashSet<Int>()
    internal val blocked = AtomicInteger()
    internal val pendingBlobs = ConcurrentHashMap<String, String>()

    /** A long press opened the menu; swallow the rest of that key press. */
    internal var swallowKey = -1
    internal var dpadCenterDown = false
    internal var selectHeld = false
    internal var stickKeys = emptySet<Int>()
    internal var windowedBeforeFocus = true
    internal var crashes = 0
    internal var lastCrash = 0L

    internal val updateBlocked = Runnable { ui.blockedCount = blocked.get() }
    internal val longPress = Runnable {
        val pointerHold = ui.inputMode == InputMode.Cursor && cursor.isPressing && !cursor.isDragging
        if (pointerHold || dpadCenterDown) {
            cursor.cancelPress()
            dpadCenterDown = false
            swallowKey = KeyEvent.KEYCODE_DPAD_CENTER
            openMenu()
        }
    }

    internal val player = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == PlayerActivity.RESULT_PLAY_ON_SITE) {
            sitePlayer += ui.host
            applySiteSettings(ui.host)
            toast("Using ${ui.host}'s own player · Menu ▸ Game player to switch back")
            webView?.reload()
        } else {
            toast("Back on ${ui.title.ifEmpty { ui.host }} · pick another game")
        }
    }

    internal val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == RESULT_OK && !text.isNullOrBlank()) load(text)
    }

    internal val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            val before = ui.padCount
            updatePadCount()
            if (ui.padCount > before) toast("Controller connected")
        }

        override fun onInputDeviceRemoved(deviceId: Int) {
            pads.forget(deviceId)
            updatePadCount()
        }

        override fun onInputDeviceChanged(deviceId: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = settings.value
        ui.windowed = prefs.windowMode == WindowMode.Windowed
        ui.standalone = intent.getBooleanExtra(EXTRA_STANDALONE, false)
        ui.site = app.sites.byId(intent.getStringExtra(EXTRA_SITE_ID))
        startUrl = intent.dataString ?: ui.site?.url ?: "about:blank"
        if (ui.standalone && prefs.sandboxApps) {
            profileName = "app_" + (ui.site?.id ?: UrlInput.registrable(UrlInput.host(startUrl).orEmpty())).replace(Regex("[^A-Za-z0-9_]"), "_")
        }
        intent.getStringExtra(EXTRA_TITLE)?.let { ui.title = it }
        if (intent.getBooleanExtra(EXTRA_SITE_PLAYER, false)) UrlInput.host(startUrl)?.let { sitePlayer += it }
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webFrame = FrameLayout(this)
        fullFrame = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        cursorView = CursorView(this)
        overlay = ComposeView(this).apply {
            setContent {
                val p by settings.state.collectAsState()
                val history by app.history.entries.collectAsState()
                val custom by app.sites.custom.collectAsState()
                RetroTheme(p) { BrowserOverlay(ui, this@BrowserActivity, history, BuiltInSites.ALL + custom) }
            }
        }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(webFrame, FrameLayout.LayoutParams(match, match))
        root.addView(fullFrame, FrameLayout.LayoutParams(match, match))
        root.addView(overlay, FrameLayout.LayoutParams(match, match))
        root.addView(cursorView, FrameLayout.LayoutParams(match, match))
        setContentView(root)

        cursor = CursorController(cursorView, { root }, { settings.value.cursorSpeed }, ::edgeScroll, ::hover)
        applyCursorLook()
        defaultUa = runCatching { WebSettings.getDefaultUserAgent(this) }.getOrDefault("")

        createWebView()
        applyWindowMode()
        webView?.loadUrl(startUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ui.panel != null) show(null) else handleBack()
            }
        })

        lifecycleScope.launch {
            var first = true
            app.userscripts.scripts.collect {
                if (!first) webView?.let(::installScripts)
                first = false
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    app.downloads.completed.collect { item ->
                        when {
                            item.done && item.isApk -> show(Panel.ApkReady(item))
                            item.done -> toast("Downloaded ${item.title} · Menu ▸ Downloads to open it")
                            item.failed -> toast("Download failed: ${item.title}")
                        }
                    }
                }
                launch { app.downloads.saved.collect { show(Panel.Saved(it)) } }
                launch {
                    settings.state.collect {
                        applyCursorLook()
                        AdBlock.setCustom(it.customBlockList)
                    }
                }
            }
        }
    }

    internal fun applyCursorLook() {
        val p = settings.value
        cursorView.sizeLevel = p.cursorSize
        cursorView.accent = ACCENTS[p.accent.coerceIn(0, ACCENTS.lastIndex)].second.toInt()
        cursorView.invalidate()
    }

    // ---- Layout and modes ----

    internal fun applyWindowMode() {
        val top = if (ui.windowed && !ui.htmlFullscreen) (TOOLBAR_DP * resources.displayMetrics.density).toInt() else 0
        val lp = webFrame.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != top) {
            lp.topMargin = top
            webFrame.layoutParams = lp
        }
        hideSystemBars()
    }

    internal fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    internal fun exitCustomView(notify: Boolean) {
        val v = customView ?: return
        val cb = customCallback
        customView = null
        customCallback = null
        fullFrame.removeView(v)
        fullFrame.visibility = View.GONE
        ui.htmlFullscreen = false
        applyWindowMode()
        if (notify) cb?.onCustomViewHidden()
        webView?.requestFocus()
    }

    internal fun updatePadCount() {
        ui.padCount = InputDevice.getDeviceIds().count { id ->
            val d = InputDevice.getDevice(id) ?: return@count false
            !d.isVirtual && ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
        }
    }

    internal fun toast(text: String) {
        ui.toast = text
        ui.toastId++
    }

    // ---- Input ----

    // TV remotes need the raw Back key (long press opens the menu); gesture/system back still goes
    // through the OnBackPressedCallback. RestrictedApi is lint's false positive on super.dispatchKeyEvent.
    @SuppressLint("RestrictedApi", "GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN

        // The rest of a long press that opened the menu.
        if (swallowKey != -1 && (code == swallowKey || (swallowKey == KeyEvent.KEYCODE_DPAD_CENTER && isConfirm(code)))) {
            if (!down) swallowKey = -1
            return true
        }

        // A panel is open: Compose handles the keys; Back / Menu close it.
        if (ui.panel != null) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_B || isMenuKey(code)) {
                if (!down) show(null)
                return true
            }
            if (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_SELECT) {
                if (code == KeyEvent.KEYCODE_BUTTON_SELECT) selectHeld = down
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (VirtualPads.isController(event) && code != KeyEvent.KEYCODE_BACK) return handleController(event)

        if (isMenuKey(code)) {
            if (!down) openMenu()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                if (down) {
                    if (event.repeatCount == 1) {
                        swallowKey = KeyEvent.KEYCODE_BACK
                        openMenu()
                    }
                } else if (!event.isCanceled) {
                    handleBack()
                }
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (down) pageScroll(-1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) pageScroll(1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (!down) toggleInput()
                return true
            }
        }

        if (ui.inputMode == InputMode.Cursor) {
            if (isArrow(code)) {
                if (down) cursor.press(code) else cursor.release(code)
                return true
            }
            if (isConfirm(code)) {
                if (down) {
                    if (event.repeatCount == 0) {
                        cursor.pressDown()
                        handler.removeCallbacks(longPress)
                        handler.postDelayed(longPress, LONG_PRESS_MS)
                    }
                } else {
                    handler.removeCallbacks(longPress)
                    cursor.pressUp()
                }
                return true
            }
        } else {
            if (isArrow(code)) {
                dispatchToPage(event)
                return true
            }
            if (isConfirm(code)) {
                if (down) {
                    if (event.repeatCount == 0) {
                        dpadCenterDown = true
                        handler.removeCallbacks(longPress)
                        handler.postDelayed(longPress, LONG_PRESS_MS)
                    }
                } else {
                    handler.removeCallbacks(longPress)
                    if (dpadCenterDown) {
                        dpadCenterDown = false
                        val now = SystemClock.uptimeMillis()
                        dispatchToPage(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
                        dispatchToPage(KeyEvent(now, now + 40, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("RestrictedApi")
    internal fun handleController(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) selectHeld = down
        if (code == KeyEvent.KEYCODE_BUTTON_START && down && selectHeld) {
            swallowKey = KeyEvent.KEYCODE_BUTTON_START
            openMenu()
            return true
        }
        if (code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (!down) openMenu()
            return true
        }
        if (ui.controllerBrowse) return controllerBrowse(event)

        val mode = ui.controllerMode
        var handled = false
        if (mode != ControllerMode.Keyboard) handled = pads.onKey(event)
        // Auto: keys only while the page isn't reading gamepads, decided when the button goes down.
        val sendKeys = when (mode) {
            ControllerMode.Native -> false
            ControllerMode.Keyboard, ControllerMode.Both -> true
            ControllerMode.Auto -> if (down) !pads.pagePolls else code in autoKeys
        }
        if (sendKeys) {
            val mapped = settings.value.mapping[code]
            if (mapped != null && mapped != 0) {
                sendKey(event, mapped)
                handled = true
                if (mode == ControllerMode.Auto) {
                    if (down) autoKeys += code else autoKeys -= code
                }
            }
        }
        return handled || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ui.panel == null && VirtualPads.isJoystick(ev) && ev.action == MotionEvent.ACTION_MOVE) {
            if (ui.controllerBrowse) {
                val x = ev.getAxisValue(MotionEvent.AXIS_X) + ev.getAxisValue(MotionEvent.AXIS_HAT_X)
                val y = ev.getAxisValue(MotionEvent.AXIS_Y) + ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
                cursor.stick(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
                val ry = ev.getAxisValue(MotionEvent.AXIS_RZ)
                if (kotlin.math.abs(ry) > 0.25f) scrollBy(0f, ry * 40f)
                return true
            }
            val mode = ui.controllerMode
            if (mode != ControllerMode.Keyboard) pads.onMotion(ev)
            val keys = mode == ControllerMode.Keyboard || mode == ControllerMode.Both || (mode == ControllerMode.Auto && !pads.pagePolls)
            if (keys) stickToKeys(ev) else releaseStickKeys()
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    internal fun handleBack() {
        val wv = webView
        when {
            customView != null -> {
                wv?.evaluateJavascript(PageScripts.EXIT_FULLSCREEN, null)
                exitCustomView(notify = true)
            }
            ui.focusGame -> toggleFocusGame()
            wv != null && wv.canGoBack() -> wv.goBack()
            else -> exit()
        }
    }

    internal fun openMenu() {
        handler.removeCallbacks(longPress)
        releaseKeys()
        updatePadCount()
        val site = appSite()
        ui.appInstalled = site?.let { SiteApps.isInstalled(this, it) }
        show(Panel.Menu)
    }

    /** The built-in site this page belongs to (it has a launcher alias). */
    internal fun appSite() = ui.site?.takeIf { it.builtIn && UrlInput.sameSite(it.url, ui.url.ifEmpty { startUrl }) }
        ?: BuiltInSites.ALL.firstOrNull { UrlInput.sameSite(it.url, ui.url) && UrlInput.host(it.url) == UrlInput.host(ui.url) }

    // ---- BrowserActions (implemented in BrowserControls.kt) ----

    override fun show(panel: Panel?) = controls.show(panel)
    override fun back() = controls.back()
    override fun forward() = controls.forward()
    override fun reload() = controls.reload()
    override fun home() = controls.home()
    override fun siteStart() = controls.siteStart()
    override fun load(text: String) = controls.load(text)
    override fun voiceSearch() = controls.voiceSearch()
    override fun toggleWindow() = controls.toggleWindow()
    override fun toggleFocusGame() = controls.toggleFocusGame()
    override fun toggleDesktop() = controls.toggleDesktop()
    override fun zoom(delta: Int) = controls.zoom(delta)
    override fun toggleInput() = controls.toggleInput()
    override fun toggleAdBlock() = controls.toggleAdBlock()
    override fun toggleGamePlayer() = controls.toggleGamePlayer()
    override fun cycleController() = controls.cycleController()
    override fun toggleControllerBrowse() = controls.toggleControllerBrowse()
    override fun installApp() = controls.installApp()
    override fun addToHome() = controls.addToHome()
    override fun openDownloads() = controls.openDownloads()
    override fun openSettings() = controls.openSettings()
    override fun openBlocked() = controls.openBlocked()
    override fun exit() = controls.exit()
    override fun installScript(script: Userscript, url: String) = controls.installScript(script, url)
    override fun installApk(item: DownloadItem) = controls.installApk(item)
    override fun openSaved(file: SavedFile) = controls.openSaved(file)
    override fun updateSite(change: (SitePrefs) -> SitePrefs) = controls.updateSite(change)

    // ---- Lifecycle ----

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.takeIf { it != ui.url }?.let { webView?.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        hideSystemBars()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, handler)
        updatePadCount()
    }

    override fun onPause() {
        handler.removeCallbacks(longPress)
        releaseKeys()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputListener)
        webView?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let {
            webFrame.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SITE_ID = "site_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_STANDALONE = "standalone"

        /** Open with the site's own game player (from the player's "Play on the site"). */
        const val EXTRA_SITE_PLAYER = "site_player"
        internal const val LONG_PRESS_MS = 650L

        fun open(context: Context, url: String, siteId: String? = null, title: String? = null): Intent =
            Intent(context, BrowserActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(url.toUri())
                .putExtra(EXTRA_SITE_ID, siteId)
                .putExtra(EXTRA_TITLE, title)
    }
}
