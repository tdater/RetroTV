package com.aiconsole.retrotv.data

import com.aiconsole.retrotv.web.Gamepad
import org.json.JSONArray
import org.json.JSONObject

/** Export / import of the user's sites, favourites, layout and controller settings as JSON. */
object Backup {

    fun export(prefs: Prefs, custom: List<Site>, scriptUrls: List<String>): String {
        val o = JSONObject()
        o.put("format", "retrotv-backup-1")
        o.put("sites", JSONArray().apply { custom.forEach { put(JSONObject().put("name", it.name).put("url", it.url)) } })
        o.put("favorites", JSONArray(prefs.favorites.toList()))
        o.put("hidden", JSONArray(prefs.hiddenSites.toList()))
        o.put("scripts", JSONArray(scriptUrls))
        o.put("settings", JSONObject().apply {
            put("accent", prefs.accent)
            put("cardSize", prefs.cardSize.name)
            put("backdrop", prefs.backdrop.name)
            put("overscanDp", prefs.overscanDp)
            put("viewMode", prefs.viewMode.name)
            put("windowMode", prefs.windowMode.name)
            put("controllerMode", prefs.controllerMode.name)
            put("mapping", Gamepad.encode(prefs.mapping))
            put("searchEngine", prefs.searchEngine.name)
            put("cursorSpeed", prefs.cursorSpeed)
            put("cursorSize", prefs.cursorSize)
            put("customBlockList", prefs.customBlockList)
            put("startupSite", prefs.startupSite)
        })
        return o.toString(2)
    }

    data class Restored(val prefs: Prefs, val sites: List<Pair<String, String>>, val scriptUrls: List<String>)

    fun import(json: String, current: Prefs): Restored {
        val o = JSONObject(json)
        require(o.optString("format").startsWith("retrotv-backup")) { "Not a RetroTV backup" }
        fun strings(key: String): List<String> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val s = o.optJSONObject("settings") ?: JSONObject()
        val prefs = current.copy(
            accent = s.optInt("accent", current.accent).coerceIn(0, ACCENTS.lastIndex),
            cardSize = enumOf(s, "cardSize", current.cardSize),
            backdrop = enumOf(s, "backdrop", current.backdrop),
            overscanDp = s.optInt("overscanDp", current.overscanDp),
            viewMode = enumOf(s, "viewMode", current.viewMode),
            windowMode = enumOf(s, "windowMode", current.windowMode),
            controllerMode = enumOf(s, "controllerMode", current.controllerMode),
            mapping = if (s.has("mapping")) Gamepad.decode(s.getString("mapping")) else current.mapping,
            searchEngine = enumOf(s, "searchEngine", current.searchEngine),
            cursorSpeed = s.optInt("cursorSpeed", current.cursorSpeed),
            cursorSize = s.optInt("cursorSize", current.cursorSize),
            customBlockList = s.optString("customBlockList", current.customBlockList),
            startupSite = s.optString("startupSite", current.startupSite),
            favorites = strings("favorites").toSet(),
            hiddenSites = strings("hidden").toSet()
        )
        val sites = o.optJSONArray("sites")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("name") to it.optString("url") }
        }.orEmpty().filter { it.second.startsWith("http") }
        return Restored(prefs, sites, strings("scripts"))
    }

    private inline fun <reified E : Enum<E>> enumOf(o: JSONObject, key: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == o.optString(key) } ?: fallback
}
