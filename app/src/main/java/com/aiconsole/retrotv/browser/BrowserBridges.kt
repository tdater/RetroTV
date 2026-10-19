package com.aiconsole.retrotv.browser

import android.util.Base64
import android.webkit.JavascriptInterface

// Small JS interfaces the browser adds to every page.

/** Receives blob: downloads read by [PageScripts.saveBlob]. Only tokens we issued are accepted. */
internal fun BrowserActivity.filesBridge(): Any = object {
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

/** Layout width for [PageScripts.VIEWPORT]. */
internal fun BrowserActivity.viewBridge(): Any = object {
    @JavascriptInterface
    fun width(host: String): Int = if (host.isEmpty()) 0 else settings.viewMode(host).viewportWidth
}
