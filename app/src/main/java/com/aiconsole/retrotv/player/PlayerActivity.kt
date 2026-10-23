package com.aiconsole.retrotv.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.browser.BrowserActivity
import com.aiconsole.retrotv.browser.CursorController
import com.aiconsole.retrotv.browser.CursorView
import com.aiconsole.retrotv.browser.RendererWatch
import com.aiconsole.retrotv.browser.VirtualPads
import com.aiconsole.retrotv.browser.WebConsole
import com.aiconsole.retrotv.data.ACCENTS
import com.aiconsole.retrotv.data.FreezeWatchdog
import com.aiconsole.retrotv.data.ProblemReport
import com.aiconsole.retrotv.link.PhonePads
import com.aiconsole.retrotv.ui.RetroTheme
import com.aiconsole.retrotv.web.UserAgents
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * RetroTV's own game player: EmulatorJS in a full-screen WebView ([GameFiles] serves the page,
 * the game files and the emulator). Controllers are standard gamepads ([VirtualPads]); without
 * one the remote plays. Back / Menu / Select+Start open the game menu (save slots, restart...).
 */
class PlayerActivity : ComponentActivity(), PlayerActions {

    private val app by lazy { RetroApp.from(this) }
    private val ui = PlayerUiState()
    private val pads = VirtualPads()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var request: GameRequest
    private lateinit var root: FrameLayout
    private lateinit var cursorView: CursorView
    private lateinit var cursor: CursorController
    private var webView: WebView? = null
    private var displayHz = 0f
    private var legacy = false
    private var noThreads = false
    /** The start number in the [RetryPlan] ladder (1 = first). */
    private var attempt = 1
    /** Whether the player page is cross-origin isolated (threaded cores need it); null until the page says. */
    private var isolated: Boolean? = null
    private var selectHeld = false
    private var swallowKey = -1
    private var resumedAt = 0L
    private var rewinding = false
    private val gamePrefs by lazy { getSharedPreferences("game_prefs", Context.MODE_PRIVATE) }
    /** The button layout for this start ([ControlProfiles.resolve]), read by the page through the bridge. */
    @Volatile private var controlsProfile = ""
    /** The Button layout screen is open: its result is kept as this game's own layout when it closes. */
    private var layoutMode = false
    private var coopDone = false
    /** Starts the emulator once the optional Players screen is done. */
    private var pendingGo: (() -> Unit)? = null
    /** Read by the page on a binder thread: the Players screen is waiting for "Start the game". */
    @Volatile private var coopHold = false
    /** The auto-resume save on exit is in flight; the page's "saved" answer closes the player. */
    private var exitSaving = false
    /** This instance is counted in [PlayerOpen]. */
    private var counted = false

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = Unit
        override fun onInputDeviceRemoved(deviceId: Int) {
            pads.forget(deviceId)
        }
        override fun onInputDeviceChanged(deviceId: Int) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = GameRequest.fromIntent(intent) ?: run {
            finish()
            return
        }
        PlayerOpen.enter()
        counted = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        displayHz = runCatching { DisplayModes.preferSixtyHz(this) }.getOrDefault(0f)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        cursorView = CursorView(this).apply { shown = false }
        val overlay = ComposeView(this).apply {
            setContent {
                val p by app.settings.state.collectAsState()
                RetroTheme(p) {
                    PlayerOverlay(ui, request.displayTitle, Systems.label(request.core), request.page.startsWith("http"), this@PlayerActivity)
                }
            }
        }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wv = createWebView()
        root.addView(wv, FrameLayout.LayoutParams(match, match))
        root.addView(overlay, FrameLayout.LayoutParams(match, match))
        root.addView(cursorView, FrameLayout.LayoutParams(match, match))
        setContentView(root)
        cursor = CursorController(cursorView, { root }, { app.settings.value.cursorSpeed }, { _, _, _, _ -> }, { _, _ -> })
        cursorView.sizeLevel = app.settings.value.cursorSize
        cursorView.accent = accent().toInt()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBackKey()
        })

        app.games.progressListener = { done, total ->
            handler.post { ui.fetch = if (total in 1..done) null else done to total }
        }
        app.library.record(request)
        ui.systemId = Systems.of(request.core)?.id
        ui.autoResume = gamePrefs.getBoolean(AutoResume.PREF, false)
        FreezeWatchdog.context = "Player: ${request.core} ${request.displayTitle}"
        loadGame()
    }

    private fun accent(): Long = ACCENTS[app.settings.value.accent.coerceIn(0, ACCENTS.lastIndex)].second

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            WebViewCompat.setWebViewRenderProcessClient(wv, RendererWatch.executor, RendererWatch.client)
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            setSupportZoom(false)
            // A desktop user agent: EmulatorJS treats "Android" as a phone and adds touch controls.
            userAgentString = UserAgents.desktop(runCatching { WebSettings.getDefaultUserAgent(this@PlayerActivity) }.getOrDefault(""))
        }
        wv.setBackgroundColor(Color.BLACK)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.isLongClickable = false
        wv.setOnLongClickListener { true }
        wv.addJavascriptInterface(pads.Bridge(), "RetroTVPad")
        pads.attach(wv)
        wv.addJavascriptInterface(PlayerBridge(), "RetroTVPlayer")
        wv.addJavascriptInterface(SavesBridge(), "RetroTVSaves")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, VirtualPads.POLYFILL, setOf(GameFiles.ORIGIN))
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? =
                app.games.serve(req.url, req.method)

            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean =
                req.url.host != GameFiles.HOST

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(VirtualPads.POLYFILL, null)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                root.removeView(view)
                view.destroy()
                webView = null
                show(PlayerPanel.Failed("The emulator ran out of memory or crashed."))
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            private val console = WebConsole("Player")

            override fun onConsoleMessage(message: ConsoleMessage): Boolean = console.log(message)
        }
        webView = wv
        return wv
    }

    private fun loadGame() {
        val files = app.games.prepare(request)
        val game = files["game"]
        if (game == null) {
            show(PlayerPanel.Failed("The game file isn't available any more. Open it from the site again."))
            return
        }
        val threads = Systems.needsThreads(request.core) && !noThreads
        isolated = null
        val query = linkedMapOf(
            "core" to request.core,
            "name" to request.gameName.ifBlank { request.displayTitle },
            "key" to request.key,
            "dir" to SaveNames.dirName(request.key),
            "color" to "#%06X".format(Locale.US, accent() and 0xFFFFFFL),
            "data" to GameFiles.EJS_DATA
        )
        files.forEach { (k, v) -> query[k] = v }
        val prefs = app.settings.value
        ui.smooth = prefs.smoothMode
        ui.compat = prefs.compatGraphics
        if (ui.compat && !legacy) {
            // Compatibility graphics: the WebGL 1 cores up front, so the retry ladder skips that step.
            legacy = true
            attempt = maxOf(attempt, 2)
        }
        ui.vsync = gamePrefs.getBoolean(VSYNC_PREFIX + request.key, true)
        if (!ui.vsync) query["vsync"] = "disabled"
        if (legacy) query["legacy"] = "1"
        if (rewindAllowed()) query["rewind"] = "1"
        ui.filter = effectiveFilter()
        ui.cheats = CheatRules.decode(gamePrefs.getString("cheats_" + request.key, "").orEmpty())
        controlsProfile = ControlProfiles.resolve(
            gamePrefs.getString(ControlProfiles.gameKey(request.key), null),
            ControlProfiles.systemKey(ui.systemId)?.let { gamePrefs.getString(it, null) }
        ).orEmpty()
        if (ui.filter != "disabled") query["shader"] = ui.filter
        if (app.settings.value.sharpPixels) query["sharp"] = "1"
        if (threads) {
            query["threads"] = "1"
            query["coi"] = "1"
        }
        val qs = query.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        ui.started = false
        ui.fetch = null
        resetPerf()
        updateContext()
        webView?.loadUrl("${GameFiles.ORIGIN}/player/index.html?$qs")
    }

    /** Smooth mode keeps filters off; otherwise the game's own filter, else the default. */
    private fun effectiveFilter(): String =
        if (ui.smooth) "disabled" else gamePrefs.getString("filter_" + request.key, null) ?: app.settings.value.filter

    /** Rewind needs the setting and no Smooth mode (it costs memory and CPU). */
    private fun rewindAllowed(): Boolean = app.settings.value.rewind && !ui.smooth

    // ---- Performance watch: StutterRules decides, a line per session goes to "Report a problem" ----

    private val fpsSamples = ArrayList<Double>()
    private var settleUntil = 0L
    private var emuSum = 0.0
    private var screenSum = 0.0
    private var perfCount = 0
    private var glVersion = 0

    private fun resetPerf() {
        fpsSamples.clear()
        settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS
    }

    private fun updateContext() {
        val avg = if (perfCount > 0) " ~%.0f fps".format(Locale.US, emuSum / perfCount) else ""
        FreezeWatchdog.context = "Player: ${request.core} ${request.displayTitle} (smooth ${if (ui.smooth) "on" else "off"}, " +
            "WebGL ${if (glVersion > 0) glVersion else "?"}$avg)"
    }

    /** One second of rates from the page. Only time spent playing counts (not menus, fast-forward or rewind). */
    private fun onPerf(emu: Double, screen: Double, gl: Int) {
        if (gl > 0) glVersion = gl
        val now = SystemClock.elapsedRealtime()
        val playing = ui.started && ui.panel == null && !ui.fast && !rewinding && !ui.pointer && emu >= 0
        if (!playing) {
            fpsSamples.clear()
            settleUntil = now + SETTLE_MS
            return
        }
        if (now < settleUntil) return
        emuSum += emu
        screenSum += screen
        perfCount++
        fpsSamples += emu
        if (fpsSamples.size > StutterRules.WINDOW) fpsSamples.removeAt(0)
        if (perfCount % 10 == 1) updateContext()
        val asked = gamePrefs.getBoolean(CHOPPY_PREFIX + request.key, false)
        val target = StutterRules.effectiveTarget(StutterRules.targetFps(ui.systemId), displayHz)
        if (!ui.smooth && !asked && StutterRules.shouldPrompt(fpsSamples, target, screen)) {
            gamePrefs.edit { putBoolean(CHOPPY_PREFIX + request.key, true) }
            show(PlayerPanel.Choppy)
        }
    }

    /** Writes this session's average rates as one line for "Report a problem". */
    private fun flushPerf() {
        if (perfCount == 0) return
        val target = StutterRules.targetFps(ui.systemId)?.let { "%.0f".format(Locale.US, it) } ?: "?"
        ProblemReport.note(
            this,
            "perf ${request.core} \"${request.displayTitle}\": emulated %.1f fps (target %s), screen %.1f fps, smooth=%s, WebGL %s, legacyCores=%s, frameSync=%s, %d s".format(
                Locale.US, emuSum / perfCount, target, screenSum / perfCount, ui.smooth, if (glVersion > 0) glVersion else "?", legacy, ui.vsync, perfCount
            )
        )
        emuSum = 0.0
        screenSum = 0.0
        perfCount = 0
    }

    private fun js(code: String) = webView?.evaluateJavascript("window.RetroPlayer && RetroPlayer.$code", null)

    private fun toast(text: String) {
        ui.toast = text
        ui.toastId++
    }

    private fun show(panel: PlayerPanel?) {
        val was = ui.panel
        ui.panel = panel
        if (panel != null) {
            pads.releaseAll()
            if (ui.started) js("pause(true)")
            if (panel == PlayerPanel.Menu) refreshSlots()
        } else {
            webView?.requestFocus()
            if (was != null && ui.started) js("pause(false)")
        }
    }

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---- Input ----

    private fun isMenuKey(code: Int) = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO ||
        code == KeyEvent.KEYCODE_GUIDE || code == KeyEvent.KEYCODE_SETTINGS

    private fun isConfirm(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun isArrow(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    private fun isVolume(code: Int) = code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE

    // The raw Back key is the game menu; RestrictedApi is lint's false positive on super.dispatchKeyEvent.
    @SuppressLint("RestrictedApi", "GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (isVolume(code)) return super.dispatchKeyEvent(event)
        if (swallowKey != -1 && code == swallowKey) {
            if (!down) swallowKey = -1
            return true
        }

        // The controls help closes on any key; the key's release is swallowed so it doesn't reach the game.
        if (ui.help) {
            if (down) {
                hideHelp()
                swallowKey = code
            }
            return true
        }

        // Choosing a player: the Start press goes to VirtualPads even with a panel open.
        if (pads.claiming != null && code == KeyEvent.KEYCODE_BUTTON_START && VirtualPads.isController(event)) {
            if (down) {
                val slot = pads.claiming ?: 0
                pads.onKey(event)
                refreshPlayers()
                toast("That controller is now player ${slot + 1}")
            }
            return true
        }

        if (ui.panel != null) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_B || isMenuKey(code)) {
                if (!down) {
                    when (ui.panel) {
                        is PlayerPanel.Failed -> exit()
                        PlayerPanel.Players -> { pads.claim(null); refreshPlayers(); show(PlayerPanel.Menu) }
                        PlayerPanel.Coop -> finishCoop()
                        PlayerPanel.Cheats -> show(PlayerPanel.Menu)
                        PlayerPanel.Choppy -> answerChoppy(false)
                        else -> resume()
                    }
                }
                return true
            }
            if (ui.panel == PlayerPanel.Coop && code == KeyEvent.KEYCODE_BUTTON_START && VirtualPads.isController(event)) {
                if (!down) finishCoop()
                return true
            }
            if (code == KeyEvent.KEYCODE_BUTTON_START || code == KeyEvent.KEYCODE_BUTTON_SELECT) return true
            return super.dispatchKeyEvent(event)
        }

        if (ui.pointer) return pointerKey(event)

        if (VirtualPads.isController(event) && code != KeyEvent.KEYCODE_BACK) {
            if (code == KeyEvent.KEYCODE_BUTTON_SELECT) selectHeld = down
            if (code == KeyEvent.KEYCODE_BUTTON_START && down && selectHeld) {
                swallowKey = KeyEvent.KEYCODE_BUTTON_START
                pads.releaseAll()
                show(PlayerPanel.Menu)
                return true
            }
            if (code == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (!down) show(PlayerPanel.Menu)
                return true
            }
            // Releasing L2 always ends a rewind, even if Select was let go first.
            if (rewinding && code == KeyEvent.KEYCODE_BUTTON_L2 && !down) {
                hotkey(HotkeyAction.RewindOff)
                return true
            }
            Hotkeys.action(selectHeld, code, down)?.let { if (hotkey(it)) return true }
            pads.onKey(event)
            return true
        }

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || isMenuKey(code)) {
            if (!down && !event.isCanceled) show(PlayerPanel.Menu)
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_REWIND && rewindAllowed()) {
            Hotkeys.action(false, code, down)?.let { hotkey(it) }
            return true
        }
        pads.onRemoteKey(event)
        return true
    }

    /** Runs a [Hotkeys] action; false when it doesn't apply (the key then goes to the game). */
    private fun hotkey(action: HotkeyAction): Boolean {
        if (!ui.started) return false
        when (action) {
            HotkeyAction.QuickSave -> js("save(${ui.slot})")
            HotkeyAction.QuickLoad -> js("load(${ui.slot})")
            HotkeyAction.RewindOn -> {
                if (!rewindAllowed()) {
                    toast(if (ui.smooth) "Rewind is off in Smooth mode" else "Turn on Rewind in Settings > Games first")
                    return true
                }
                rewinding = true
                js("rewind(true)")
            }
            HotkeyAction.RewindOff -> {
                if (!rewinding) return false
                rewinding = false
                js("rewind(false)")
            }
            HotkeyAction.FastForward -> toggleFast()
        }
        return true
    }

    /** "Emulator settings": the pointer works EmulatorJS's own menus; Back leaves it. */
    @SuppressLint("GestureBackNavigation")
    private fun pointerKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        when {
            isArrow(code) -> if (down) cursor.press(code) else cursor.release(code)
            isConfirm(code) || code == KeyEvent.KEYCODE_BUTTON_A -> if (down) {
                if (event.repeatCount == 0) cursor.pressDown()
            } else cursor.pressUp()
            code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B || isMenuKey(code) -> if (!down) {
                setPointer(false)
                if (layoutMode) {
                    layoutMode = false
                    js("snapshot('game')")
                }
                js("hideMenu()")
                js("pause(false)")
            }
        }
        return true
    }

    private fun setPointer(on: Boolean) {
        ui.pointer = on
        cursor.releaseAll()
        cursorView.shown = on
        cursorView.invalidate()
    }

    private fun onBackKey() {
        if (ui.panel != null) resume() else show(PlayerPanel.Menu)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ui.panel == null && VirtualPads.isJoystick(ev) && ev.action == MotionEvent.ACTION_MOVE) {
            if (ui.pointer) {
                cursor.stick(ev.getAxisValue(MotionEvent.AXIS_X).coerceIn(-1f, 1f), ev.getAxisValue(MotionEvent.AXIS_Y).coerceIn(-1f, 1f))
            } else {
                pads.onMotion(ev)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // ---- PlayerActions ----

    override fun resume() {
        if (ui.panel == PlayerPanel.Coop) finishCoop() else show(null)
    }

    override fun save() {
        js("save(${ui.slot})")
        show(null)
    }

    override fun load() {
        js("load(${ui.slot})")
        show(null)
    }

    override fun selectSlot(slot: Int) {
        ui.slot = slot.coerceIn(1, SaveNames.SLOTS)
    }

    override fun restart() {
        js("restart()")
        show(null)
    }

    override fun toggleFast() {
        ui.fast = !ui.fast
        js("fast(${ui.fast})")
    }

    override fun toggleStats() {
        ui.stats = !ui.stats
        val webViewVersion = runCatching { WebViewCompat.getCurrentWebViewPackage(this)?.versionName?.substringBefore('.') }.getOrNull() ?: "?"
        val metrics = resources.displayMetrics
        val info = "display %.0f Hz %d×%d · WebView %s · smooth %s · frame sync %s".format(
            Locale.US, displayHz, metrics.widthPixels, metrics.heightPixels, webViewVersion,
            if (ui.smooth) "on" else "off", if (ui.vsync) "on" else "off"
        )
        js("stats(${ui.stats}, ${JSONObject.quote(info)})")
        if (ui.stats) show(null)
    }

    /** Smooth mode on or off (a device setting): filters and rewind off, the speed readout closed. */
    private fun applySmooth(on: Boolean) {
        app.settings.update { it.copy(smoothMode = on) }
        ui.smooth = on
        if (on && ui.stats) toggleStats()
        ui.filter = effectiveFilter()
        js("filter('${ui.filter}')")
        updateContext()
        toast(if (on) "Smooth mode on · filters off now, rewind off from the next start" else "Smooth mode off")
    }

    override fun toggleSmooth() = applySmooth(!ui.smooth)

    override fun answerChoppy(turnOn: Boolean) {
        if (turnOn) applySmooth(true)
        show(null)
    }

    /** Frame sync (EmulatorJS's vsync option) on or off for this game, applied live so the two can be compared. */
    override fun toggleVsync() {
        ui.vsync = !ui.vsync
        gamePrefs.edit { putBoolean(VSYNC_PREFIX + request.key, ui.vsync) }
        js("vsync('${if (ui.vsync) "enabled" else "disabled"}')")
        toast(if (ui.vsync) "Frame sync on" else "Frame sync off")
    }

    override fun toggleCompat() {
        ui.compat = !ui.compat
        app.settings.update { it.copy(compatGraphics = ui.compat) }
        toast(if (ui.compat) "Compatibility graphics on · from the next start" else "Compatibility graphics off · from the next start")
    }

    override fun toggleMute() {
        ui.muted = !ui.muted
        js("mute(${ui.muted})")
    }

    /** Next display filter for this game, applied live and remembered for it. */
    override fun cycleFilter() {
        if (ui.smooth) {
            toast("Smooth mode keeps filters off · turn it off in this menu to use one")
            return
        }
        ui.filter = Filters.next(ui.filter)
        gamePrefs.edit { putString("filter_" + request.key, ui.filter) }
        js("filter('${ui.filter}')")
    }

    override fun openMenu() = show(PlayerPanel.Menu)

    override fun openCheats() = show(PlayerPanel.Cheats)

    override fun toggleCheat(index: Int) = setCheats(ui.cheats.mapIndexed { i, c -> if (i == index) c.copy(on = !c.on) else c })

    override fun deleteCheat(index: Int) = setCheats(ui.cheats.filterIndexed { i, _ -> i != index })

    override fun addCheats(text: String): Int {
        val found = CheatRules.parse(text).map { it.copy(on = true) }
        if (found.isNotEmpty()) setCheats(ui.cheats + found)
        return found.size
    }

    private fun setCheats(list: List<Cheat>) {
        ui.cheats = list
        gamePrefs.edit { putString("cheats_" + request.key, CheatRules.encode(list)) }
        applyCheats()
    }

    /** Sends the cheat list to EmulatorJS (it resets and re-applies the ones that are on). */
    private fun applyCheats() {
        if (!ui.started) return
        val arr = JSONArray()
        ui.cheats.forEach { arr.put(JSONObject().put("code", it.code).put("on", it.on)) }
        js("cheats($arr)")
    }

    override fun openPlayers() {
        refreshPlayers()
        show(PlayerPanel.Players)
    }

    override fun claimPlayer(slot: Int) {
        pads.claim(slot)
        refreshPlayers()
    }

    private fun refreshPlayers() {
        ui.players = pads.players()
        ui.claiming = pads.claiming
    }

    override fun buttonLayout() {
        ui.panel = null
        setPointer(true)
        layoutMode = true
        js("controls()")
        toast("Pointer on: set each button, it's remembered for this game · Back to return to the game")
    }

    override fun useLayoutForConsole() {
        if (ControlProfiles.systemKey(ui.systemId) == null) {
            toast("RetroTV doesn't know this console, so it can't keep a layout for it")
            return
        }
        // The page answers with snapshot(): the game's current layout becomes the console's.
        js("snapshot('system')")
        show(null)
    }

    override fun resetConsoleLayout() {
        val key = ControlProfiles.systemKey(ui.systemId)
        if (key == null) {
            toast("RetroTV doesn't know this console")
            return
        }
        gamePrefs.edit { remove(key) }
        toast("${Systems.label(request.core)} layout reset · applies to games started from now on")
    }

    override fun toggleAutoResume() {
        ui.autoResume = !ui.autoResume
        gamePrefs.edit { putBoolean(AutoResume.PREF, ui.autoResume) }
        toast(if (ui.autoResume) "Games will continue from their latest save" else "Auto-resume off")
    }

    /** The Players screen before the game is done (or skipped): start the emulator. */
    override fun finishCoop() {
        pads.claim(null)
        refreshPlayers()
        coopDone = true
        coopHold = false
        show(null)
        pendingGo?.invoke()
        pendingGo = null
    }

    private fun onSnapshot(purpose: String, json: String) {
        if (!ControlProfiles.isValid(json)) {
            toast("Couldn't read the button layout")
            return
        }
        when (purpose) {
            "game" -> gamePrefs.edit { putString(ControlProfiles.gameKey(request.key), json) }
            "system" -> {
                val key = ControlProfiles.systemKey(ui.systemId) ?: return
                gamePrefs.edit { putString(key, json) }
                toast("This layout is now used for all ${Systems.label(request.core)} games")
            }
        }
    }

    /** Connected controllers and phones: the co-op screen only appears with more than one. */
    private fun padCount(): Int {
        val devices = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .map { PadDevice(it.descriptor.orEmpty().ifEmpty { "id${it.id}" }, it.sources, it.isVirtual) }
        return CoopSetup.countControllers(devices) + PhonePads.connected.value
    }

    /** Starts the emulator, after the optional Players screen when several people can play. */
    private fun startOrCoop(go: () -> Unit) {
        if (!coopDone && CoopSetup.shouldShow(ui.systemId, padCount())) {
            pendingGo = go
            coopHold = true
            refreshPlayers()
            show(PlayerPanel.Coop)
        } else {
            go()
        }
    }

    override fun emulatorMenu() {
        ui.panel = null
        setPointer(true)
        js("menu()")
        toast("Pointer on: EmulatorJS menu at the bottom · Back to return to the game")
    }

    override fun showHelp() {
        ui.helpController = hasController()
        ui.helpAuto = false
        ui.help = true
    }

    override fun hideHelp() {
        ui.help = false
        ui.helpAuto = false
    }

    private fun hasController(): Boolean = InputDevice.getDeviceIds().any { id ->
        val src = InputDevice.getDevice(id)?.sources ?: 0
        src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    /** First launch only: the controls card for 8 s (or until a key); later starts just toast the menu keys. */
    private fun firstLaunchHelp() {
        if (gamePrefs.getBoolean(HINT_SHOWN, false)) {
            toast("Back or Menu: game menu (save, load, exit)")
            return
        }
        gamePrefs.edit { putBoolean(HINT_SHOWN, true) }
        showHelp()
        ui.helpAuto = true
    }

    /** The page's capability report, before it downloads any emulator file: start, start with a warning, or stop. */
    private fun onCaps(caps: Caps) {
        when (val v = Preflight.verdict(request.core, caps)) {
            Verdict.Ok -> startOrCoop { js("go(false)") }
            is Verdict.Warn -> {
                // No WebGL 2: the compatibility (WebGL 1) cores, chosen up front so the retry ladder skips that step.
                toast(v.message)
                // Set when the game really starts (after the Players screen), so a hold never skips the legacy retry.
                startOrCoop {
                    legacy = true
                    attempt = maxOf(attempt, 2)
                    js("go(true)")
                }
            }
            is Verdict.Block -> {
                js("halt()")
                show(PlayerPanel.Failed(v.message, if (v.suggestSitePlayer) Preflight.HINT else ""))
            }
        }
    }

    override fun playOnSite() {
        js("flush()")
        if (callingActivity != null) {
            setResult(RESULT_PLAY_ON_SITE)
        } else if (request.page.startsWith("http")) {
            // Started from the home screen: open the game's page with the site's own player.
            startActivity(BrowserActivity.open(this, request.page).putExtra(BrowserActivity.EXTRA_SITE_PLAYER, true))
        }
        finish()
    }

    override fun retry() {
        ui.panel = null
        // A manual retry starts the ladder over.
        attempt = 1
        legacy = false
        noThreads = false
        loadGame()
    }

    override fun exit() {
        js("flush()")
        ui.panel = null
        if (ui.autoResume && ui.started) {
            // Auto-resume: save to the hidden auto slot (the manual slots stay as the player left them),
            // and close when the page reports it saved; if it never answers, close anyway.
            exitSaving = true
            js("save(${SaveNames.AUTO})")
            handler.postDelayed({ finish() }, AutoResume.EXIT_CAP_MS)
        } else {
            handler.postDelayed({ finish() }, 250L)
        }
    }

    /** Auto-resume: load the hidden auto slot (else the newest manual slot) shortly after the emulator started. */
    private fun autoResumeNow() {
        if (!ui.autoResume) return
        val slot = AutoResume.pickSlot(app.saves.slots(request.key), app.saves.autoTime(request.key)) ?: return
        if (slot != SaveNames.AUTO) ui.slot = slot
        handler.postDelayed({ js("load($slot)") }, 800)
    }

    /** Calls from the player page (binder threads). */
    private inner class PlayerBridge {
        @JavascriptInterface
        fun ready() = Unit

        /** True while the Players (co-op) screen is open: the page's start fallback keeps waiting. */
        @JavascriptInterface
        fun holding(): Boolean = coopHold

        @JavascriptInterface
        fun profile(): String = controlsProfile

        @JavascriptInterface
        fun snapshot(purpose: String, json: String) = handler.post { onSnapshot(purpose, json) }

        @JavascriptInterface
        fun started() = handler.post {
            val first = !ui.started
            ui.started = true
            if (first) resetPerf()
            if (first) autoResumeNow()
            if (ui.cheats.isNotEmpty()) applyCheats()
            ui.fetch = null
            if (ui.panel == null) firstLaunchHelp()
        }

        @JavascriptInterface
        fun caps(json: String) {
            val o = runCatching { JSONObject(json) }.getOrNull()
            val caps = Caps(
                webgl2 = o?.optBoolean("webgl2", true) ?: true,
                sharedArrayBuffer = o?.optBoolean("sab", false) ?: false,
                crossOriginIsolated = o?.optBoolean("isolated", false) ?: false,
                wasmThreads = o?.optBoolean("threads", false) ?: false
            )
            handler.post { onCaps(caps) }
        }

        @JavascriptInterface
        fun isolated(ok: Boolean) = handler.post { isolated = ok }

        /** Once a second: emulated fps (-1 unknown), screen fps, WebGL version. */
        @JavascriptInterface
        fun perf(emuFps: Double, screenFps: Double, gl: Int) = handler.post { onPerf(emuFps, screenFps, gl) }

        @JavascriptInterface
        fun failed(message: String) = handler.post { onStartFailed(message) }

        @JavascriptInterface
        fun saved(slot: Int, ok: Boolean, message: String) = handler.post {
            if (slot == SaveNames.AUTO) {
                // The exit save is done (or failed): nothing left to wait for.
                if (exitSaving) finish()
            } else if (ok) refreshSlots() else toast("Couldn't save: $message")
        }

        @JavascriptInterface
        fun loaded(slot: Int, ok: Boolean, message: String) = handler.post {
            if (!ok) toast(message.ifBlank { "Couldn't load slot $slot" })
        }

        @JavascriptInterface
        fun slots(json: String) = handler.post { refreshSlots() }
    }

    /** The game didn't start: walk the [RetryPlan] ladder, then show the failure with its buttons. */
    private fun onStartFailed(message: String) {
        val problem = app.games.takeProblem()
        if (problem != null) {
            // The site sent a web page / error instead of the game: retrying the cores can't help.
            show(PlayerPanel.Failed(problem, "Try again fetches the file anew. The site's own player may still run it."))
            return
        }
        val threaded = Systems.needsThreads(request.core) && !noThreads
        val iso = isolated ?: true
        // Derived from the file list: false today, so the "without threads" step only runs once a core ships such a build.
        when (RetryPlan.next(attempt + 1, threaded, iso, CoreFiles.hasNonThreadBuild(request.core))) {
            RetryStep.Legacy -> {
                // Older TV GPUs often fail the WebGL 2 cores: try the compatible ones.
                attempt++
                legacy = true
                toast("Trying compatibility mode…")
                loadGame()
            }
            RetryStep.NoThreads -> {
                attempt++
                noThreads = true
                toast("Trying without threads…")
                loadGame()
            }
            else -> if (threaded && !iso) {
                show(PlayerPanel.Failed(RetryPlan.NO_THREADS_MESSAGE, "Update Android System WebView, or play on the site."))
            } else {
                show(PlayerPanel.Failed(message))
            }
        }
    }

    /** Save uploads from the player page (binder threads); only this game's key is accepted. */
    private inner class SavesBridge {
        @JavascriptInterface
        fun open(key: String, slot: Int, size: Double): String =
            if (key == request.key) app.saves.open(key, slot, size.toLong()).orEmpty() else ""

        @JavascriptInterface
        fun write(token: String, base64: String): Boolean = app.saves.write(token, base64)

        @JavascriptInterface
        fun close(token: String, pngBase64: String): Boolean = app.saves.close(token, pngBase64)

        @JavascriptInterface
        fun slots(key: String): String =
            if (key != request.key) "{}" else JSONObject(app.saves.slots(key).mapKeys { it.key.toString() }).toString()
    }

    /** Re-reads the slots and their screenshots for the menu (decoded off the main thread). */
    private fun refreshSlots() {
        val key = request.key
        Thread {
            val times = app.saves.slots(key)
            val thumbs = times.keys.mapNotNull { slot ->
                app.saves.thumbnail(key, slot)?.let { f -> runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()?.let { slot to it } }
            }.toMap()
            handler.post {
                ui.slotTimes = times
                ui.thumbs = thumbs
            }
        }.start()
    }

    // ---- Lifecycle ----

    override fun onResume() {
        super.onResume()
        resumedAt = SystemClock.elapsedRealtime()
        PhonePads.target = pads
        PhonePads.onMenu = { handler.post { if (ui.panel == null) show(PlayerPanel.Menu) else resume() } }
        webView?.onResume()
        hideSystemBars()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, handler)
    }

    override fun onPause() {
        if (PhonePads.target === pads) {
            PhonePads.target = null
            PhonePads.onMenu = null
        }
        // Time on screen counts as play time (menus included); short visits don't.
        val played = SystemClock.elapsedRealtime() - resumedAt
        if (resumedAt > 0 && played >= 5_000) app.library.addPlayTime(request.key, played)
        resumedAt = 0
        pads.releaseAll()
        js("flush()")
        flushPerf()
        if (ui.started && ui.panel == null) show(PlayerPanel.Menu)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputListener)
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (counted) {
            counted = false
            PlayerOpen.leave()
        }
        handler.removeCallbacksAndMessages(null)
        app.games.progressListener = null
        webView?.let {
            root.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val RESULT_PLAY_ON_SITE = 2
        private const val HINT_SHOWN = "controls_hint_shown"
        private const val VSYNC_PREFIX = "vsync_"
        private const val CHOPPY_PREFIX = "choppy_asked_"
        /** After the start, a menu or fast-forward: samples are ignored while things settle. */
        private const val SETTLE_MS = 5_000L

        fun intent(context: Context, request: GameRequest): Intent =
            request.toIntent(Intent(context, PlayerActivity::class.java))
    }
}
