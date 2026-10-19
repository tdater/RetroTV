package com.aiconsole.retrotv.browser

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap
import androidx.lifecycle.lifecycleScope
import com.aiconsole.retrotv.data.FreezeWatchdog
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.web.AdBlock
import com.aiconsole.retrotv.web.Surrogates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.UUID

// Page navigation, pop-up holding, full-screen video and downloads for the browser's WebView.

internal fun BrowserActivity.browserClient(): WebViewClient = object : WebViewClient() {

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
        FreezeWatchdog.context = "Browser: ${UrlInput.host(url).orEmpty()}"
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

internal fun BrowserActivity.browserChrome(): WebChromeClient = object : WebChromeClient() {

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
        val temp = WebView(this@browserChrome)
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

internal fun BrowserActivity.handlePopup(url: String) {
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

internal fun BrowserActivity.countBlocked() {
    blocked.incrementAndGet()
    handler.removeCallbacks(updateBlocked)
    handler.postDelayed(updateBlocked, 250)
}

internal fun BrowserActivity.cosmetic(view: WebView) {
    if (adBlockActive && settings.value.cosmetic) view.evaluateJavascript(PageScripts.cosmetic(AdBlock.COSMETIC_CSS), null)
}

internal fun BrowserActivity.updateNav() {
    val wv = webView ?: return
    ui.canBack = wv.canGoBack()
    ui.canForward = wv.canGoForward()
}

internal fun BrowserActivity.handleExternalScheme(url: String) {
    if (url.startsWith("intent:")) {
        val fallback = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }.getOrNull()
        if (fallback != null && fallback.startsWith("http")) {
            webView?.loadUrl(fallback)
            return
        }
    }
    toast("Blocked a link that tried to open another app")
}

internal fun BrowserActivity.isUserscriptUrl(url: String) = url.substringBefore('?').substringBefore('#').endsWith(".user.js", true)

internal fun BrowserActivity.promptScript(url: String) {
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

internal fun BrowserActivity.onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
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

internal fun BrowserActivity.saveInBackground(name: String, mime: String, bytes: ByteArray) {
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
