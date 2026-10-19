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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
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
import com.aiconsole.retrotv.data.ACCENTS
import com.aiconsole.retrotv.data.FreezeWatchdog
import com.aiconsole.retrotv.ui.RetroTheme
import com.aiconsole.retrotv.web.UserAgents
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
    private var legacy = false
    private var triedLegacy = false
    private var selectHeld = false
    private var swallowKey = -1

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

        app.library.record(request)
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
        wv.webChromeClient = WebChromeClient()
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
        val threads = Systems.needsThreads(request.core)
        val query = linkedMapOf(
            "core" to request.core,
            "name" to request.gameName.ifBlank { request.displayTitle },
            "key" to request.key,
            "dir" to SaveNames.dirName(request.key),
            "color" to "#%06X".format(Locale.US, accent() and 0xFFFFFFL),
            "data" to GameFiles.EJS_DATA
        )
        files.forEach { (k, v) -> query[k] = v }
        if (legacy) query["legacy"] = "1"
        if (threads) {
            query["threads"] = "1"
            query["coi"] = "1"
        }
        val qs = query.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        ui.started = false
        webView?.loadUrl("${GameFiles.ORIGIN}/player/index.html?$qs")
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

        if (ui.panel != null) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || code == KeyEvent.KEYCODE_BUTTON_B || isMenuKey(code)) {
                if (!down) {
                    if (ui.panel is PlayerPanel.Failed) exit() else resume()
                }
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
            pads.onKey(event)
            return true
        }

        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE || isMenuKey(code)) {
            if (!down && !event.isCanceled) show(PlayerPanel.Menu)
            return true
        }
        pads.onRemoteKey(event)
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

    override fun resume() = show(null)

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

    override fun toggleMute() {
        ui.muted = !ui.muted
        js("mute(${ui.muted})")
    }

    override fun emulatorMenu() {
        ui.panel = null
        setPointer(true)
        js("menu()")
        toast("Pointer on: EmulatorJS menu at the bottom · Back to return to the game")
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
        loadGame()
    }

    override fun exit() {
        js("flush()")
        ui.panel = null
        handler.postDelayed({ finish() }, 250)
    }

    /** Calls from the player page (binder threads). */
    private inner class PlayerBridge {
        @JavascriptInterface
        fun ready() = Unit

        @JavascriptInterface
        fun started() = handler.post {
            ui.started = true
            if (ui.panel == null) toast("Back or Menu: game menu (save, load, exit)")
        }

        @JavascriptInterface
        fun failed(message: String) = handler.post {
            if (!legacy && !triedLegacy) {
                // Older TV GPUs often fail the WebGL 2 cores: try the compatible ones once.
                triedLegacy = true
                legacy = true
                toast("Trying compatibility mode…")
                loadGame()
            } else {
                show(PlayerPanel.Failed(message))
            }
        }

        @JavascriptInterface
        fun saved(slot: Int, ok: Boolean, message: String) = handler.post {
            if (ok) refreshSlots() else toast("Couldn't save: $message")
        }

        @JavascriptInterface
        fun loaded(slot: Int, ok: Boolean, message: String) = handler.post {
            if (!ok) toast(message.ifBlank { "Couldn't load slot $slot" })
        }

        @JavascriptInterface
        fun slots(json: String) = handler.post { refreshSlots() }
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
        webView?.onResume()
        hideSystemBars()
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(inputListener, handler)
    }

    override fun onPause() {
        pads.releaseAll()
        js("flush()")
        if (ui.started && ui.panel == null) show(PlayerPanel.Menu)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(inputListener)
        webView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        webView?.let {
            root.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val RESULT_PLAY_ON_SITE = 2

        fun intent(context: Context, request: GameRequest): Intent =
            request.toIntent(Intent(context, PlayerActivity::class.java))
    }
}
