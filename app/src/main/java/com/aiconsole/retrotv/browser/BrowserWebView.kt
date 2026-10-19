package com.aiconsole.retrotv.browser

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.data.ViewMode
import com.aiconsole.retrotv.web.Gamepad
import com.aiconsole.retrotv.web.UserAgents

// Creating, configuring and recovering the browser's WebView.

// ---- WebView setup ----

@SuppressLint("SetJavaScriptEnabled")
internal fun BrowserActivity.createWebView() {
    val wv = WebView(this)
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
        WebViewCompat.setWebViewRenderProcessClient(wv, RendererWatch.executor, RendererWatch.client)
    }
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
    wv.addJavascriptInterface(filesBridge(), "RetroTVFiles")
    wv.addJavascriptInterface(gameBridge(), "RetroTVGame")
    wv.addJavascriptInterface(viewBridge(), "RetroTVView")
    wv.webViewClient = browserClient()
    wv.webChromeClient = browserChrome()
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
internal fun BrowserActivity.installScripts(wv: WebView) {
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
internal fun BrowserActivity.injectFallback(view: WebView) {
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
internal fun BrowserActivity.applySiteSettings(host: String): Boolean {
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

internal fun BrowserActivity.recreateWebView() {
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
