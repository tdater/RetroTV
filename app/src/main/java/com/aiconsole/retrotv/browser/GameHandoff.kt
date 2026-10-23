package com.aiconsole.retrotv.browser

import android.os.SystemClock
import android.util.Log
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

    @Volatile
    private var lastFailed = false

    /**
     * Returns false when the player can't take this game (the hook then restores the site's own
     * emulator in that frame); true when the hand-over is under way ([launchFailed] reports a later failure).
     */
    @JavascriptInterface
    fun launch(json: String): Boolean {
        if (!a.playerActive) return false
        if (a.handOffGame(json) == null) {
            a.handler.post { a.useSitePlayer("the game's file can't be read") }
            return false
        }
        lastFailed = false
        a.handler.post { if (!a.launchGame(json)) lastFailed = true }
        return true
    }

    /** True once when the player failed to open after [launch] said yes. */
    @JavascriptInterface
    fun launchFailed(): Boolean = lastFailed.also { lastFailed = false }

    @JavascriptInterface
    fun failed(message: String) {
        a.handler.post { a.useSitePlayer(message) }
    }

    @JavascriptInterface
    fun blobOpen(name: String, size: Double): String = if (a.playerActive) a.app.games.blobOpen(name, size.toLong()).orEmpty() else ""

    @JavascriptInterface
    fun blobWrite(token: String, data: String) = a.app.games.blobWrite(token, data)

    @JavascriptInterface
    fun blobClose(token: String): Boolean = a.app.games.blobClose(token)
}

private fun BrowserActivity.handOffFile(value: String?): String? = when {
    value == null -> null
    value.startsWith("blob:") -> app.games.blobFile(value.removePrefix("blob:"))?.let { "file:" + it.absolutePath }
    value.startsWith("http://") || value.startsWith("https://") -> value
    else -> null // data: URLs and the like can't be fetched natively
}

/** The player-readable game file of a hand-over, or null when the player can't take it. Any thread. */
internal fun BrowserActivity.handOffGame(json: String): String? {
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
    return handOffFile(o.optString("game").takeIf { it.isNotEmpty() && it != "null" })
}

/**
 * The hand-over failed: this site plays with its own emulator from now on (until Menu ▸ Game
 * player is switched back), and the page's stopped loader is run again.
 */
internal fun BrowserActivity.useSitePlayer(reason: String) {
    Log.w("GameHandoff", "Using the site's player: $reason")
    if (ui.host.isNotBlank()) sitePlayer += ui.host
    playerActive = false
    ui.gamePlayer = false
    toast("Playing with the site's own player")
    webView?.evaluateJavascript("window.__retrotvRestore && window.__retrotvRestore()", null)
}

/** Opens the player for a hand-over; false when that failed (the site's own player is then restored). */
internal fun BrowserActivity.launchGame(json: String): Boolean {
    val now = SystemClock.uptimeMillis()
    // Not ours, or the same Play pressed twice: the first launch is already on its way.
    if (!playerActive || isFinishing || now - lastLaunch < 3000) return true
    val o = runCatching { JSONObject(json) }.getOrNull() ?: run {
        useSitePlayer("unreadable hand-over")
        return false
    }
    fun opt(key: String) = o.optString(key).takeIf { it.isNotEmpty() && it != "null" }
    fun file(value: String?): String? = handOffFile(value)
    val game = file(opt("game")) ?: run {
        useSitePlayer("the game's file can't be read")
        return false
    }
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
    return try {
        player.launch(PlayerActivity.intent(this, request))
        true
    } catch (e: Exception) {
        useSitePlayer("couldn't open the player: ${e.message}")
        false
    }
}

/** "Play Contra Online - Site" → "Contra". */
internal fun BrowserActivity.cleanTitle(title: String): String {
    var t = title.substringBefore(" | ").substringBefore(" – ")
    if (t.contains(" - ")) t = t.substringBeforeLast(" - ").ifBlank { t }
    t = t.removePrefix("Play ").removeSuffix(" Online").removeSuffix(" online").trim()
    return t.ifBlank { title }
}
