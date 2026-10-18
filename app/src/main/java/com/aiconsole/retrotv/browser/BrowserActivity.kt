package com.aiconsole.retrotv.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.apps.SiteApps
import com.aiconsole.retrotv.data.ACCENTS
import com.aiconsole.retrotv.data.BuiltInSites
import com.aiconsole.retrotv.data.ControllerMode
import com.aiconsole.retrotv.data.SitePrefs
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.data.ViewMode
import com.aiconsole.retrotv.data.WindowMode
import com.aiconsole.retrotv.downloads.DownloadItem
import com.aiconsole.retrotv.downloads.SavedFile
import com.aiconsole.retrotv.player.GameRequest
import com.aiconsole.retrotv.player.PlayerActivity
import com.aiconsole.retrotv.ui.MainActivity
import com.aiconsole.retrotv.ui.RetroTheme
import com.aiconsole.retrotv.web.AdBlock
import com.aiconsole.retrotv.web.Gamepad
import com.aiconsole.retrotv.web.Surrogates
import com.aiconsole.retrotv.web.UserAgents
import com.aiconsole.retrotv.web.Userscript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
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

    private val app by lazy { RetroApp.from(this) }
    private val settings get() = app.settings
    private val ui = BrowserUiState()
    private val pads = VirtualPads()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var webFrame: FrameLayout
    private lateinit var fullFrame: FrameLayout
    private lateinit var cursorView: CursorView
    private lateinit var cursor: CursorController
    private lateinit var overlay: ComposeView

    private var webView: WebView? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private val scriptHandlers = mutableListOf<ScriptHandler>()
    private var docStartScripts = false
    private var defaultUa = ""
    private var startUrl = "about:blank"
    private var profileName: String? = null

    @Volatile
    private var adBlockActive = true

    /** RetroTV's player takes games on this page (read by the game hook from any frame). */
    @Volatile
    private var playerActive = true

    /** Hosts where the user chose the site's own player for this visit. */
    private val sitePlayer = HashSet<String>()
    private var lastLaunch = 0L
    private val gameHook by lazy { runCatching { assets.open("js/game-hook.js").bufferedReader().use { it.readText() } }.getOrDefault("") }

    /** Auto controller mode: buttons that were sent as keys (their release must be too). */
    private val autoKeys = HashSet<Int>()
    private val blocked = AtomicInteger()
    private val pendingBlobs = ConcurrentHashMap<String, String>()

    /** A long press opened the menu; swallow the rest of that key press. */
    private var swallowKey = -1
    private var dpadCenterDown = false
    private var selectHeld = false
    private var stickKeys = emptySet<Int>()
    private var windowedBeforeFocus = true
    private var crashes = 0
    private var lastCrash = 0L

    private val updateBlocked = Runnable { ui.blockedCount = blocked.get() }
    private val longPress = Runnable {
        val pointerHold = ui.inputMode == InputMode.Cursor && cursor.isPressing && !cursor.isDragging
        if (pointerHold || dpadCenterDown) {
            cursor.cancelPress()
            dpadCenterDown = false
            swallowKey = KeyEvent.KEYCODE_DPAD_CENTER
            openMenu()
        }
    }

    private val player = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == PlayerActivity.RESULT_PLAY_ON_SITE) {
            sitePlayer += ui.host
            applySiteSettings(ui.host)
            toast("Using ${ui.host}'s own player · Menu ▸ Game player to switch back")
            webView?.reload()
        } else {
            toast("Back on ${ui.title.ifEmpty { ui.host }} · pick another game")
        }
    }

    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == RESULT_OK && !text.isNullOrBlank()) load(text)
    }

    private val inputListener = object : InputManager.InputDeviceListener {
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

    private fun applyCursorLook() {
        val p = settings.value
        cursorView.sizeLevel = p.cursorSize
        cursorView.accent = ACCENTS[p.accent.coerceIn(0, ACCENTS.lastIndex)].second.toInt()
        cursorView.invalidate()
    }

    // ---- WebView setup ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        val wv = WebView(this)
        // A site app gets its own cookies and storage (Hermit-style sandbox) where supported.
        profileName?.let { name ->
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                runCatching {
                    ProfileStore.getInstance().getOrCreateProfile(name)
                    WebViewCompat.setProfile(wv, name)
                }
            }
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.setBackgroundColor(Color.BLACK)
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        // No long-press text selection: holding OK is the menu, and pointer drags shouldn't select.
        wv.setOnLongClickListener { true }
        wv.isLongClickable = false
        wv.isHapticFeedbackEnabled = false
        wv.addJavascriptInterface(pads.Bridge(), "RetroTVPad")
        wv.addJavascriptInterface(FilesBridge(), "RetroTVFiles")
        wv.addJavascriptInterface(GameBridge(), "RetroTVGame")
        wv.addJavascriptInterface(ViewBridge(), "RetroTVView")
        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownload(url, userAgent, contentDisposition, mimeType)
        }
        webFrame.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        installScripts(wv)
        applySiteSettings(UrlInput.host(startUrl).orEmpty())
        wv.requestFocus()
    }

    /** Gamepad polyfill and userscripts, at document start in every frame where supported. */
    private fun installScripts(wv: WebView) {
        scriptHandlers.forEach { runCatching { it.remove() } }
        scriptHandlers.clear()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            docStartScripts = true
            scriptHandlers += WebViewCompat.addDocumentStartJavaScript(wv, VirtualPads.POLYFILL, setOf("*"))
            scriptHandlers += WebViewCompat.addDocumentStartJavaScript(wv, PageScripts.VIEWPORT, setOf("*"))
            if (gameHook.isNotEmpty()) scriptHandlers += WebViewCompat.addDocumentStartJavaScript(wv, gameHook, setOf("*"))
            if (settings.value.userscripts) {
                app.userscripts.injectable.forEach { (_, js) ->
                    scriptHandlers += WebViewCompat.addDocumentStartJavaScript(wv, js, setOf("*"))
                }
            }
        } else {
            docStartScripts = false
        }
    }

    /** Older WebViews: inject into the main frame once the new document exists. */
    private fun injectFallback(view: WebView) {
        if (docStartScripts) return
        view.evaluateJavascript(VirtualPads.POLYFILL, null)
        view.evaluateJavascript(PageScripts.VIEWPORT, null)
        if (gameHook.isNotEmpty()) view.evaluateJavascript(gameHook, null)
        if (settings.value.userscripts) app.userscripts.injectable.forEach { (_, js) -> view.evaluateJavascript(js, null) }
    }

    /**
     * Applies the site's view mode, ad blocking, controller mode and text size. Returns true when
     * the user agent changed (the page must reload to get the other layout).
     */
    private fun applySiteSettings(host: String): Boolean {
        val wv = webView ?: return false
        val desktop = settings.viewMode(host) == ViewMode.Desktop
        ui.host = host
        ui.desktop = desktop
        ui.siteAdBlock = settings.adBlock(host)
        ui.controllerMode = settings.controllerMode(host)
        ui.sitePrefs = settings.site(host)
        adBlockActive = ui.siteAdBlock
        ui.gamePlayer = settings.builtInPlayer(host) && host !in sitePlayer
        playerActive = ui.gamePlayer
        val ua = settings.value.customUserAgent.ifBlank {
            if (desktop) UserAgents.desktop(defaultUa) else UserAgents.mobile(defaultUa)
        }
        val changed = wv.settings.userAgentString != ua
        wv.settings.userAgentString = ua
        // Both views set a fixed layout width (PageScripts.VIEWPORT) that's zoomed to fit the screen.
        wv.settings.loadWithOverviewMode = true
        wv.settings.textZoom = settings.zoom(host)
        return changed
    }

    private fun recreateWebView() {
        val url = webView?.url ?: ui.url.ifEmpty { startUrl }
        webView?.let {
            webFrame.removeView(it)
            it.destroy()
        }
        webView = null
        exitCustomView(notify = false)
        val now = SystemClock.uptimeMillis()
        crashes = if (now - lastCrash < 30_000) crashes + 1 else 1
        lastCrash = now
        createWebView()
        if (crashes > 2) {
            toast("This page keeps crashing the browser, so it was stopped")
            webView?.loadUrl("about:blank")
        } else {
            toast("The page crashed and was reloaded")
            webView?.loadUrl(url)
        }
    }

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            val scheme = request.url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
                handleExternalScheme(url)
                return true
            }
            if (isUserscriptUrl(url)) {
                promptScript(url)
                return true
            }
            if (!request.isForMainFrame) return false
            if (adBlockActive && AdBlock.isBlocked(request.url.host)) {
                countBlocked()
                toast("Blocked an ad redirect")
                return true
            }
            val current = view.url
            if (adBlockActive && settings.value.blockPopups && !request.hasGesture() && !request.isRedirect &&
                current != null && current.startsWith("http") && !UrlInput.sameSite(current, url)
            ) {
                ui.blockedPopup = url
                toast("Blocked an automatic redirect · Menu ▸ Open blocked pop-up")
                return true
            }
            val newHost = UrlInput.host(url).orEmpty()
            if (newHost != ui.host && applySiteSettings(newHost)) {
                view.loadUrl(url)
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!adBlockActive || request.isForMainFrame) return null
            val host = request.url.host
            if (!AdBlock.isBlocked(host)) return null
            countBlocked()
            // Scripts get a stand-in (or an empty script) so pages waiting on an ad library carry on.
            val path = request.url.path.orEmpty()
            val surrogate = Surrogates.find(host.orEmpty(), path)
            if (surrogate != null || Surrogates.looksLikeScript(path, request.requestHeaders["Accept"])) {
                val body = (surrogate ?: Surrogates.EMPTY).toByteArray()
                val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
                return WebResourceResponse("application/javascript", "utf-8", 200, "OK", headers, ByteArrayInputStream(body))
            }
            return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            ui.loading = true
            ui.url = url
            ui.focusGame = false
            blocked.set(0)
            ui.blockedCount = 0
            applySiteSettings(UrlInput.host(url).orEmpty())
            updateNav()
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            injectFallback(view)
            cosmetic(view)
        }

        override fun onPageFinished(view: WebView, url: String) {
            ui.loading = false
            injectFallback(view)
            cosmetic(view)
            app.history.record(view.title, url)
            view.title?.takeIf { it.isNotBlank() }?.let { ui.title = it }
            updateNav()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            ui.url = url
            updateNav()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === webView) recreateWebView()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            ui.progress = newProgress
            ui.loading = newProgress < 100
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) ui.title = title
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            fullFrame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullFrame.visibility = View.VISIBLE
            ui.htmlFullscreen = true
            applyWindowMode()
        }

        override fun onHideCustomView() {
            exitCustomView(notify = false)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
            if (!isUserGesture && adBlockActive && settings.value.blockPopups) {
                toast("Blocked a pop-up")
                return false
            }
            // Catch the pop-up's first URL in a throwaway WebView, then decide where it goes.
            val temp = WebView(this@BrowserActivity)
            var handled = false
            fun take(url: String?) {
                if (handled || url.isNullOrEmpty() || url == "about:blank") return
                handled = true
                handlePopup(url)
                handler.post { temp.destroy() }
            }
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    take(r.url.toString())
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    v.stopLoading()
                    take(url)
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = temp
            resultMsg.sendToTarget()
            return true
        }

        override fun getDefaultVideoPoster(): Bitmap = createBitmap(1, 1)
    }

    private fun handlePopup(url: String) {
        if (adBlockActive && AdBlock.isBlocked(UrlInput.host(url))) {
            countBlocked()
            toast("Blocked a pop-up ad")
            return
        }
        if (adBlockActive && settings.value.blockPopups && !UrlInput.sameSite(webView?.url, url)) {
            ui.blockedPopup = url
            toast("Blocked a pop-up · Menu ▸ Open blocked pop-up")
            return
        }
        webView?.loadUrl(url)
    }

    private fun countBlocked() {
        blocked.incrementAndGet()
        handler.removeCallbacks(updateBlocked)
        handler.postDelayed(updateBlocked, 250)
    }

    private fun cosmetic(view: WebView) {
        if (adBlockActive && settings.value.cosmetic) view.evaluateJavascript(PageScripts.cosmetic(AdBlock.COSMETIC_CSS), null)
    }

    private fun updateNav() {
        val wv = webView ?: return
        ui.canBack = wv.canGoBack()
        ui.canForward = wv.canGoForward()
    }

    private fun handleExternalScheme(url: String) {
        if (url.startsWith("intent:")) {
            val fallback = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }.getOrNull()
            if (fallback != null && fallback.startsWith("http")) {
                webView?.loadUrl(fallback)
                return
            }
        }
        toast("Blocked a link that tried to open another app")
    }

    private fun isUserscriptUrl(url: String) = url.substringBefore('?').substringBefore('#').endsWith(".user.js", true)

    private fun promptScript(url: String) {
        show(Panel.InstallScript(url, null, null))
        lifecycleScope.launch {
            val next = try {
                Panel.InstallScript(url, app.userscripts.fetch(url), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Panel.InstallScript(url, null, e.message ?: "unknown error")
            }
            if ((ui.panel as? Panel.InstallScript)?.url == url) ui.panel = next
        }
    }

    // ---- Downloads ----

    private fun onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        when {
            isUserscriptUrl(url) -> promptScript(url)
            url.startsWith("blob:") -> {
                val ext = mimeType?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                val name = contentDisposition?.let { UrlInput.guessFileName(url, it, mimeType) }
                    ?: ("download-" + System.currentTimeMillis() + (ext?.let { ".$it" } ?: ""))
                val token = UUID.randomUUID().toString()
                pendingBlobs[token] = name
                webView?.evaluateJavascript(PageScripts.saveBlob(url, token, name), null)
                toast("Saving $name…")
            }
            url.startsWith("data:") -> {
                val header = url.substringBefore(',')
                val data = url.substringAfter(',')
                val mime = header.removePrefix("data:").substringBefore(';').ifEmpty { "application/octet-stream" }
                val bytes = runCatching {
                    if (header.contains(";base64")) Base64.decode(data, Base64.DEFAULT) else Uri.decode(data).toByteArray()
                }.getOrNull() ?: return toast("Couldn't read that download")
                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                saveInBackground("download-" + System.currentTimeMillis() + (ext?.let { ".$it" } ?: ""), mime, bytes)
            }
            else -> try {
                val name = app.downloads.enqueue(url, userAgent, contentDisposition, mimeType, webView?.url)
                toast("Downloading $name")
            } catch (e: Exception) {
                toast("Download failed: ${e.message}")
            }
        }
    }

    private fun saveInBackground(name: String, mime: String, bytes: ByteArray) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                app.downloads.saveBytes(name, mime, bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { toast("Couldn't save $name: ${e.message}") }
            }
        }
    }

    /** Receives blob: downloads read by [PageScripts.saveBlob]. Only tokens we issued are accepted. */
    inner class FilesBridge {
        @JavascriptInterface
        fun save(token: String, name: String, mime: String, base64: String) {
            val expected = pendingBlobs.remove(token) ?: return
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
            saveInBackground(expected.ifEmpty { name }, mime, bytes)
        }

        @JavascriptInterface
        fun failed(token: String, message: String) {
            if (pendingBlobs.remove(token) != null) handler.post { toast("Couldn't save: $message") }
        }
    }

    /**
     * The game hook (assets/js/game-hook.js) in any frame: an EmulatorJS game is about to start.
     * When RetroTV's player is on here, the hook stops the site's emulator and hands the game over.
     */
    inner class GameBridge {
        @JavascriptInterface
        fun enabled(core: String): Boolean = playerActive && core.isNotBlank()

        @JavascriptInterface
        fun launch(json: String) {
            handler.post { launchGame(json) }
        }

        @JavascriptInterface
        fun failed(message: String) {
            handler.post { toast("Couldn't hand the game to RetroTV's player ($message) · Menu ▸ Game player: site") }
        }

        @JavascriptInterface
        fun blobOpen(name: String, size: Double): String = if (playerActive) app.games.blobOpen(name, size.toLong()).orEmpty() else ""

        @JavascriptInterface
        fun blobWrite(token: String, data: String) = app.games.blobWrite(token, data)

        @JavascriptInterface
        fun blobClose(token: String): Boolean = app.games.blobClose(token)
    }

    /** Layout width for [PageScripts.VIEWPORT]. */
    inner class ViewBridge {
        @JavascriptInterface
        fun width(host: String): Int = if (host.isEmpty()) 0 else settings.viewMode(host).viewportWidth
    }

    private fun launchGame(json: String) {
        val now = SystemClock.uptimeMillis()
        if (!playerActive || isFinishing || now - lastLaunch < 3000) return
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        fun opt(key: String) = o.optString(key).takeIf { it.isNotEmpty() && it != "null" }
        fun file(value: String?): String? = when {
            value == null -> null
            value.startsWith("blob:") -> app.games.blobFile(value.removePrefix("blob:"))?.let { "file:" + it.absolutePath }
            value.startsWith("http://") || value.startsWith("https://") -> value
            else -> null
        }
        val game = file(opt("game")) ?: return toast("RetroTV's player couldn't read this game's file")
        lastLaunch = now
        // An embed frame's title is often just "Play"; the page's own title names the game better.
        val frameTitle = opt("title")?.takeIf { o.optBoolean("top") || (it.length > 4 && !it.startsWith("Play", ignoreCase = true)) }
        val request = GameRequest(
            core = o.optString("core"),
            game = game,
            bios = file(opt("bios")),
            parent = file(opt("parent")),
            patch = file(opt("patch")),
            state = file(opt("state")),
            gameName = opt("gameName").orEmpty(),
            title = cleanTitle(frameTitle ?: ui.title),
            page = ui.url.ifEmpty { opt("page").orEmpty() },
            referer = opt("page").orEmpty(),
            userAgent = webView?.settings?.userAgentString.orEmpty(),
            profile = profileName
        )
        releaseKeys()
        show(null)
        try {
            player.launch(PlayerActivity.intent(this, request))
        } catch (e: Exception) {
            toast("Couldn't open the player: ${e.message}")
        }
    }

    /** "Play Contra Online - Site" → "Contra". */
    private fun cleanTitle(title: String): String {
        var t = title.substringBefore(" | ").substringBefore(" – ")
        if (t.contains(" - ")) t = t.substringBeforeLast(" - ").ifBlank { t }
        t = t.removePrefix("Play ").removeSuffix(" Online").removeSuffix(" online").trim()
        return t.ifBlank { title }
    }

    // ---- Layout and modes ----

    private fun applyWindowMode() {
        val top = if (ui.windowed && !ui.htmlFullscreen) (TOOLBAR_DP * resources.displayMetrics.density).toInt() else 0
        val lp = webFrame.layoutParams as FrameLayout.LayoutParams
        if (lp.topMargin != top) {
            lp.topMargin = top
            webFrame.layoutParams = lp
        }
        hideSystemBars()
    }

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun exitCustomView(notify: Boolean) {
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

    private fun updatePadCount() {
        ui.padCount = InputDevice.getDeviceIds().count { id ->
            val d = InputDevice.getDevice(id) ?: return@count false
            !d.isVirtual && ((d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
        }
    }

    private fun toast(text: String) {
        ui.toast = text
        ui.toastId++
    }

    // ---- Input ----

    private fun isMenuKey(code: Int) = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO ||
        code == KeyEvent.KEYCODE_GUIDE || code == KeyEvent.KEYCODE_SETTINGS

    private fun isConfirm(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun isArrow(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

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
    private fun handleController(event: KeyEvent): Boolean {
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

    /** "Pad moves pointer": the controller drives the browser instead of the game. */
    private fun controllerBrowse(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (down) cursor.press(code) else cursor.release(code)
            KeyEvent.KEYCODE_BUTTON_A -> if (down) {
                if (event.repeatCount == 0) cursor.pressDown()
            } else cursor.pressUp()
            KeyEvent.KEYCODE_BUTTON_B -> if (!down) handleBack()
            KeyEvent.KEYCODE_BUTTON_Y -> if (!down) openMenu()
            KeyEvent.KEYCODE_BUTTON_X -> if (!down) toggleControllerBrowse()
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> if (down) pageScroll(-1)
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> if (down) pageScroll(1)
        }
        return true
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

    /** Keyboard mode: hat switch and left stick become the mapped D-pad keys. */
    private fun stickToKeys(ev: MotionEvent) {
        val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val now = Gamepad.stickDirections(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y)) +
            Gamepad.stickDirections(hx, hy, 0.5f)
        val mapping = settings.value.mapping
        val t = SystemClock.uptimeMillis()
        (now - stickKeys).forEach { dir -> mapping[dir]?.takeIf { it != 0 }?.let { dispatchToPage(keyboardEvent(t, KeyEvent.ACTION_DOWN, it)) } }
        (stickKeys - now).forEach { dir -> mapping[dir]?.takeIf { it != 0 }?.let { dispatchToPage(keyboardEvent(t, KeyEvent.ACTION_UP, it)) } }
        stickKeys = now
    }

    private fun keyboardEvent(time: Long, action: Int, code: Int) =
        KeyEvent(time, time, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

    private fun sendKey(src: KeyEvent, code: Int) {
        dispatchToPage(
            KeyEvent(src.downTime, src.eventTime, src.action, code, src.repeatCount, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        )
    }

    private fun dispatchToPage(event: KeyEvent) {
        (customView ?: webView)?.dispatchKeyEvent(event)
    }

    private fun releaseStickKeys() {
        if (stickKeys.isEmpty()) return
        val t = SystemClock.uptimeMillis()
        val mapping = settings.value.mapping
        stickKeys.forEach { dir -> mapping[dir]?.takeIf { it != 0 }?.let { dispatchToPage(keyboardEvent(t, KeyEvent.ACTION_UP, it)) } }
        stickKeys = emptySet()
    }

    private fun releaseKeys() {
        releaseStickKeys()
        val t = SystemClock.uptimeMillis()
        val mapping = settings.value.mapping
        autoKeys.forEach { code -> mapping[code]?.takeIf { it != 0 }?.let { dispatchToPage(keyboardEvent(t, KeyEvent.ACTION_UP, it)) } }
        autoKeys.clear()
        pads.releaseAll()
        cursor.releaseAll()
    }

    @Suppress("DEPRECATION")
    private fun cssScale(): Float = webView?.scale?.takeIf { it > 0f } ?: resources.displayMetrics.density

    private fun edgeScroll(x: Float, y: Float, dx: Float, dy: Float) {
        if (customView != null) return
        val wv = webView ?: return
        val s = cssScale()
        val lx = (x - webFrame.left).coerceIn(0f, wv.width - 1f)
        val ly = (y - webFrame.top).coerceIn(0f, wv.height - 1f)
        wv.evaluateJavascript(PageScripts.scrollAt(lx / s, ly / s, dx / s, dy / s), null)
    }

    private fun scrollBy(dx: Float, dy: Float) = edgeScroll(cursorView.px, cursorView.py, dx, dy)

    private fun pageScroll(direction: Int) {
        val wv = webView ?: return
        edgeScroll(cursorView.px, cursorView.py, 0f, direction * wv.height * 0.8f)
    }

    private fun hover(x: Float, y: Float) {
        if (customView != null) return
        val wv = webView ?: return
        val lx = x - webFrame.left
        val ly = y - webFrame.top
        if (lx < 0 || ly < 0 || lx > wv.width || ly > wv.height) return
        val ev = CursorController.hoverEvent(lx, ly)
        wv.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun handleBack() {
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

    private fun openMenu() {
        handler.removeCallbacks(longPress)
        releaseKeys()
        updatePadCount()
        val site = appSite()
        ui.appInstalled = site?.let { SiteApps.isInstalled(this, it) }
        show(Panel.Menu)
    }

    /** The built-in site this page belongs to (it has a launcher alias). */
    private fun appSite() = ui.site?.takeIf { it.builtIn && UrlInput.sameSite(it.url, ui.url.ifEmpty { startUrl }) }
        ?: BuiltInSites.ALL.firstOrNull { UrlInput.sameSite(it.url, ui.url) && UrlInput.host(it.url) == UrlInput.host(ui.url) }

    // ---- BrowserActions ----

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
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
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

    override fun toggleControllerBrowse() {
        ui.controllerBrowse = !ui.controllerBrowse
        releaseKeys()
        cursorView.shown = true
        toast(if (ui.controllerBrowse) "Controller moves the pointer · X to play again" else "Controller plays the game")
    }

    override fun installApp() {
        show(null)
        val site = appSite()
        if (site != null && SiteApps.hasAlias(this, site)) {
            val installed = SiteApps.isInstalled(this, site)
            SiteApps.setInstalled(this, site, !installed)
            toast(if (installed) "${site.name} app removed" else "${site.name} installed: it's now in your TV's apps")
            return
        }
        val name = ui.title.ifEmpty { ui.host }
        val url = ui.url.ifEmpty { startUrl }
        if (SiteApps.pinShortcut(this, name, url, 0xFF7E57C2)) {
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
        startActivity(MainActivity.intent(this, MainActivity.SCREEN_DOWNLOADS))
    }

    override fun openSettings() {
        show(null)
        startActivity(MainActivity.intent(this, MainActivity.SCREEN_SETTINGS))
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

    override fun installApk(item: DownloadItem) = app.downloads.open(this, item)

    override fun openSaved(file: SavedFile) = app.downloads.openSaved(this, file)

    override fun updateSite(change: (SitePrefs) -> SitePrefs) {
        settings.updateSite(ui.host, change)
        applySiteSettings(ui.host)
        webView?.reload()
    }

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
        private const val LONG_PRESS_MS = 650L

        fun open(context: Context, url: String, siteId: String? = null, title: String? = null): Intent =
            Intent(context, BrowserActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(url.toUri())
                .putExtra(EXTRA_SITE_ID, siteId)
                .putExtra(EXTRA_TITLE, title)
    }
}
