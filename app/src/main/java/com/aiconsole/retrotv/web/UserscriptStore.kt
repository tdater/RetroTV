package com.aiconsole.retrotv.web

import android.content.Context
import androidx.core.content.edit
import com.aiconsole.retrotv.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** An installed userscript, whether it runs, and where it was installed from (for updates). */
data class InstalledScript(val script: Userscript, val enabled: Boolean, val sourceUrl: String?)

/** Userscripts: one `<id>.user.js` file each, plus disabled and source-URL lists. */
class UserscriptStore(context: Context) {

    private val directory = File(context.filesDir, "userscripts")
    private val prefs = context.applicationContext.getSharedPreferences("userscripts", Context.MODE_PRIVATE)

    private val _scripts = MutableStateFlow(load())
    val scripts: StateFlow<List<InstalledScript>> = _scripts.asStateFlow()

    /** Wrapped sources of the enabled scripts, rebuilt only when the list changes. */
    @Volatile
    var injectable: List<Pair<Userscript, String>> = build(_scripts.value)
        private set

    /** Downloads a `.user.js` for review before installing. Throws [IOException]. */
    suspend fun fetch(url: String): Userscript = withContext(Dispatchers.IO) {
        val body = Http.get(url, maxBytes = MAX_BYTES)
        Userscripts.parse(body) ?: throw IOException("Not a userscript (no ==UserScript== header with a @name)")
    }

    /** Parses pasted code. */
    fun parse(code: String): Userscript? = Userscripts.parse(code)

    suspend fun install(script: Userscript, sourceUrl: String?) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        File(directory, "${script.id}.user.js").writeText(script.source)
        prefs.edit { if (sourceUrl != null) putString("src:${script.id}", sourceUrl) }
        setEnabledInternal(script.id, true)
        reload()
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        File(directory, "$id.user.js").delete()
        prefs.edit { remove("src:$id") }
        setEnabledInternal(id, true)
        reload()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        setEnabledInternal(id, enabled)
        reload()
    }

    /** Re-downloads every script that has a source URL. Returns how many changed. */
    suspend fun updateAll(): Int = withContext(Dispatchers.IO) {
        var changed = 0
        _scripts.value.forEach { installed ->
            val url = installed.sourceUrl ?: return@forEach
            val fresh = runCatching { fetch(url) }.getOrNull() ?: return@forEach
            if (fresh.source != installed.script.source) {
                File(directory, "${installed.script.id}.user.js").writeText(fresh.source)
                changed++
            }
        }
        reload()
        changed
    }

    private fun reload() {
        val list = load()
        injectable = build(list)
        _scripts.value = list
    }

    private fun build(list: List<InstalledScript>) =
        list.filter { it.enabled }.map { it.script to Userscripts.wrap(it.script) }

    private fun setEnabledInternal(id: String, enabled: Boolean) {
        val disabled = prefs.getStringSet(DISABLED, emptySet()).orEmpty().toMutableSet()
        if (enabled) disabled -= id else disabled += id
        prefs.edit { putStringSet(DISABLED, disabled) }
    }

    private fun load(): List<InstalledScript> {
        val disabled = prefs.getStringSet(DISABLED, emptySet()).orEmpty()
        return directory.listFiles { f -> f.name.endsWith(".user.js") }.orEmpty()
            .mapNotNull { f -> runCatching { Userscripts.parse(f.readText()) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
            .map { InstalledScript(it, it.id !in disabled, prefs.getString("src:${it.id}", null)) }
    }

    private companion object {
        const val DISABLED = "disabled"
        const val MAX_BYTES = 3_000_000
    }
}

/** A search result from Greasy Fork. */
data class ScriptResult(
    val id: Long,
    val name: String,
    val description: String,
    val codeUrl: String,
    val installs: Long,
    val rating: Int,
    val pageUrl: String
)

/** Greasy Fork's public JSON: keyword search and "scripts for this site". */
object GreasyFork {

    suspend fun search(query: String): List<ScriptResult> =
        fetch("https://greasyfork.org/en/scripts.json?q=" + URLEncoder.encode(query.trim(), "UTF-8") + "&sort=total_installs")

    suspend fun forSite(host: String): List<ScriptResult> =
        fetch("https://greasyfork.org/en/scripts/by-site/" + URLEncoder.encode(host, "UTF-8") + ".json?sort=total_installs")

    private suspend fun fetch(url: String): List<ScriptResult> = withContext(Dispatchers.IO) {
        val arr = JSONArray(Http.get(url, maxBytes = 4_000_000))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val code = o.optString("code_url").takeIf { it.endsWith(".user.js") } ?: return@mapNotNull null
            ScriptResult(
                id = o.optLong("id"),
                name = o.optString("name"),
                description = o.optString("description"),
                codeUrl = code,
                installs = o.optLong("total_installs"),
                rating = o.optInt("good_ratings") - o.optInt("bad_ratings"),
                pageUrl = o.optString("url")
            )
        }
    }
}
