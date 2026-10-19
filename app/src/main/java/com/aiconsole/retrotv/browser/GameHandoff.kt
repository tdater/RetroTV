package com.aiconsole.retrotv.browser

import android.os.SystemClock
import android.webkit.JavascriptInterface
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.player.GameRequest
import com.aiconsole.retrotv.player.PlayerActivity
import kotlinx.coroutines.launch
import org.json.JSONObject

// Handing an EmulatorJS game from a site page to RetroTV's own player.

/**
 * The game hook (assets/js/game-hook.js) in any frame: an EmulatorJS game is about to start.
 * When RetroTV's player is on here, the hook stops the site's emulator and hands the game over.
 */
internal class GameBridge(private val a: BrowserActivity) {
    @JavascriptInterface
    fun enabled(core: String): Boolean = a.playerActive && core.isNotBlank()

    @JavascriptInterface
    fun launch(json: String) {
        a.handler.post { a.launchGame(json) }
    }

    @JavascriptInterface
    fun failed(message: String) {
        a.handler.post { a.toast("Couldn't hand the game to RetroTV's player ($message) · Menu ▸ Game player: site") }
    }

    @JavascriptInterface
    fun blobOpen(name: String, size: Double): String = if (a.playerActive) a.app.games.blobOpen(name, size.toLong()).orEmpty() else ""

    @JavascriptInterface
    fun blobWrite(token: String, data: String) = a.app.games.blobWrite(token, data)

    @JavascriptInterface
    fun blobClose(token: String): Boolean = a.app.games.blobClose(token)
}

internal fun BrowserActivity.launchGame(json: String) {
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
    UrlInput.host(request.page)?.let { host ->
        if (host !in settings.value.playerHosts) settings.update { it.copy(playerHosts = it.playerHosts + host) }
    }
    try {
        player.launch(PlayerActivity.intent(this, request))
    } catch (e: Exception) {
        toast("Couldn't open the player: ${e.message}")
    }
}

/** "Play Contra Online - Site" → "Contra". */
internal fun BrowserActivity.cleanTitle(title: String): String {
    var t = title.substringBefore(" | ").substringBefore(" – ")
    if (t.contains(" - ")) t = t.substringBeforeLast(" - ").ifBlank { t }
    t = t.removePrefix("Play ").removeSuffix(" Online").removeSuffix(" online").trim()
    return t.ifBlank { title }
}
