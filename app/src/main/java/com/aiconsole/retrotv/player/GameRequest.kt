package com.aiconsole.retrotv.player

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A game for the built-in player. File values are `http(s)://` URLs (fetched natively with the
 * site's cookies and referrer), `blob:<token>` (sent by the page, already on disk), `file:<path>`
 * or `content://` (the user's own ROM).
 */
data class GameRequest(
    val core: String,
    val game: String,
    val bios: String? = null,
    val parent: String? = null,
    val patch: String? = null,
    val state: String? = null,
    val gameName: String = "",
    val title: String = "",
    /** The page that started the game: referrer for downloads, and "Play on the site" target. */
    val page: String = "",
    /** The frame the game came from (often an embed page); sent as the downloads' referrer. */
    val referer: String = "",
    val userAgent: String = "",
    /** WebView profile of a sandboxed site app, for its cookies. */
    val profile: String? = null
) {
    val displayTitle: String get() = title.ifBlank { gameName }.ifBlank { game.substringAfterLast('/').substringBefore('?') }.ifBlank { "Game" }

    /** Stable id for saves and the recent-games list. */
    val key: String get() = (core + "|" + gameName.ifBlank { game.substringBefore('?') }).lowercase()

    fun toIntent(intent: Intent): Intent = intent.apply {
        putExtra("core", core)
        putExtra("game", game)
        putExtra("bios", bios)
        putExtra("parent", parent)
        putExtra("patch", patch)
        putExtra("state", state)
        putExtra("gameName", gameName)
        putExtra("title", title)
        putExtra("page", page)
        putExtra("referer", referer)
        putExtra("userAgent", userAgent)
        putExtra("profile", profile)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("core", core)
        put("game", game)
        bios?.let { put("bios", it) }
        parent?.let { put("parent", it) }
        patch?.let { put("patch", it) }
        put("gameName", gameName)
        put("title", title)
        put("page", page)
        put("referer", referer)
        put("userAgent", userAgent)
        profile?.let { put("profile", it) }
    }

    companion object {
        fun fromIntent(intent: Intent): GameRequest? {
            val core = intent.getStringExtra("core") ?: return null
            val game = intent.getStringExtra("game") ?: return null
            return GameRequest(
                core = core,
                game = game,
                bios = intent.getStringExtra("bios"),
                parent = intent.getStringExtra("parent"),
                patch = intent.getStringExtra("patch"),
                state = intent.getStringExtra("state"),
                gameName = intent.getStringExtra("gameName").orEmpty(),
                title = intent.getStringExtra("title").orEmpty(),
                page = intent.getStringExtra("page").orEmpty(),
                referer = intent.getStringExtra("referer").orEmpty(),
                userAgent = intent.getStringExtra("userAgent").orEmpty(),
                profile = intent.getStringExtra("profile")
            )
        }

        fun fromJson(o: JSONObject): GameRequest? {
            val core = o.optString("core").ifEmpty { return null }
            val game = o.optString("game").ifEmpty { return null }
            fun opt(k: String) = o.optString(k).takeIf { it.isNotEmpty() }
            return GameRequest(
                core = core, game = game, bios = opt("bios"), parent = opt("parent"), patch = opt("patch"),
                gameName = o.optString("gameName"), title = o.optString("title"), page = o.optString("page"),
                referer = o.optString("referer"), userAgent = o.optString("userAgent"), profile = opt("profile")
            )
        }
    }
}

/** A game in the library: when it was last played, for how long in total, and how often. */
data class RecentGame(val request: GameRequest, val playedAt: Long, val playMs: Long = 0, val plays: Int = 1, val favorite: Boolean = false)

/** Games played in the built-in player, newest first: "My games" and the home screen's "Recent games". */
class GameLibrary(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("games", Context.MODE_PRIVATE)
    private val _recent = MutableStateFlow(load())
    val recent: StateFlow<List<RecentGame>> = _recent.asStateFlow()

    private fun load(): List<RecentGame> = runCatching {
        val a = JSONArray(prefs.getString("recent", "[]"))
        (0 until a.length()).mapNotNull { i ->
            val o = a.getJSONObject(i)
            GameRequest.fromJson(o.getJSONObject("r"))?.let {
                RecentGame(it, o.optLong("t"), playMs = o.optLong("ms"), plays = o.optInt("n", 1), favorite = o.optBoolean("fav"))
            }
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<RecentGame>) {
        _recent.value = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("r", it.request.toJson()).put("t", it.playedAt).put("ms", it.playMs).put("n", it.plays).put("fav", it.favorite)) }
        prefs.edit { putString("recent", a.toString()) }
    }

    fun record(request: GameRequest) {
        // A one-off blob token can't be replayed; the cached copy is recorded by the player instead.
        if (request.game.startsWith("blob:")) return
        val old = _recent.value.firstOrNull { it.request.key == request.key }
        val entry = RecentGame(request, System.currentTimeMillis(), old?.playMs ?: 0, (old?.plays ?: 0) + 1, old?.favorite ?: false)
        save((listOf(entry) + _recent.value.filter { it.request.key != request.key }).take(MAX))
    }

    fun addPlayTime(key: String, ms: Long) = save(_recent.value.map { if (it.request.key == key) it.copy(playMs = it.playMs + ms) else it })

    fun setFavorite(key: String, on: Boolean) = save(_recent.value.map { if (it.request.key == key) it.copy(favorite = on) else it })

    fun remove(key: String) = save(_recent.value.filter { it.request.key != key })

    fun clear() = save(emptyList())

    private companion object {
        const val MAX = 300
    }
}
