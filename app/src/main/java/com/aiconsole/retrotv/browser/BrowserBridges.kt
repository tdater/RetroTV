package com.aiconsole.retrotv.browser

import android.util.Base64
import android.webkit.JavascriptInterface

// Small JS interfaces the browser adds to every page.

/** Receives blob: downloads read by [PageScripts.saveBlob]. Only tokens we issued are accepted. */
internal class FilesBridge(private val a: BrowserActivity) {
    @JavascriptInterface
    fun save(token: String, name: String, mime: String, base64: String) {
        val expected = a.pendingBlobs.remove(token) ?: return
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        a.saveInBackground(expected.ifEmpty { name }, mime, bytes)
    }

    @JavascriptInterface
    fun failed(token: String, message: String) {
        if (a.pendingBlobs.remove(token) != null) a.handler.post { a.toast("Couldn't save: $message") }
    }
}

/** Layout width for [PageScripts.VIEWPORT]. */
internal class ViewBridge(private val a: BrowserActivity) {
    @JavascriptInterface
    fun width(host: String): Int = if (host.isEmpty()) 0 else a.settings.viewMode(host).viewportWidth
}
