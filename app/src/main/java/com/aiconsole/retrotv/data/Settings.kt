package com.aiconsole.retrotv.data

import android.content.Context
import androidx.core.content.edit
import com.aiconsole.retrotv.web.Gamepad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Auto: a gamepad while the page reads gamepads (emulators), otherwise keyboard keys ("press any key" screens). */
enum class ControllerMode(val label: String) { Auto("Auto"), Native("Gamepad API"), Keyboard("Keyboard keys"), Both("Gamepad + keyboard") }
enum class CardSize(val label: String, val widthDp: Int) { Small("Small", 200), Medium("Medium", 250), Large("Large", 310) }
enum class Backdrop(val label: String) { Gradient("Gradient"), Dark("Dark"), Black("Black (OLED)"), Arcade("Arcade grid") }
/** Desktop lays pages out 1280 px wide (more fits); TV / mobile 640 px wide, so everything is bigger. */
enum class ViewMode(val label: String, val viewportWidth: Int) { Mobile("TV / mobile", 640), Desktop("Desktop", 1280) }
enum class WindowMode(val label: String) { Windowed("Windowed"), Fullscreen("Full screen") }

/** Accent colours the UI can be themed with. */
val ACCENTS: List<Pair<String, Long>> = listOf(
    "Neon purple" to 0xFF9C6BFF, "Arcade red" to 0xFFFF4D5E, "Pac yellow" to 0xFFFFD23F, "Retro green" to 0xFF3DDC84,
    "Cyber cyan" to 0xFF22D3EE, "Sunset orange" to 0xFFFF8A3D, "Hot pink" to 0xFFFF5CC8, "Ice blue" to 0xFF6EA8FF
)

data class Prefs(
    val accent: Int = 0,
    val cardSize: CardSize = CardSize.Medium,
    val backdrop: Backdrop = Backdrop.Gradient,
    val overscanDp: Int = 24,
    val showDescriptions: Boolean = true,
    val showClock: Boolean = true,
    val cursorSpeed: Int = 3,
    val cursorSize: Int = 2,
    val viewMode: ViewMode = ViewMode.Desktop,
    val windowMode: WindowMode = WindowMode.Windowed,
    val adBlock: Boolean = true,
    val cosmetic: Boolean = true,
    val blockPopups: Boolean = true,
    val controllerMode: ControllerMode = ControllerMode.Auto,
    val mapping: Map<Int, Int> = Gamepad.DEFAULT,
    val searchEngine: UrlInput.SearchEngine = UrlInput.SearchEngine.Google,
    val keepScreenOn: Boolean = true,
    val textZoom: Int = 100,
    val startupSite: String = "",
    val customUserAgent: String = "",
    val customBlockList: String = "",
    val hiddenSites: Set<String> = emptySet(),
    val favorites: Set<String> = emptySet(),
    val userscripts: Boolean = true,
    val autoFullscreenGames: Boolean = false,
    val sandboxApps: Boolean = true,
    /** Games on EmulatorJS sites open in RetroTV's own player. */
    val builtInPlayer: Boolean = true
)

/** Overrides one site (by host) can have; null means "use the global setting". */
data class SitePrefs(
    val viewMode: ViewMode? = null,
    val adBlock: Boolean? = null,
    val controllerMode: ControllerMode? = null,
    val zoom: Int? = null,
    val player: Boolean? = null
)

class Settings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<Prefs> = _state.asStateFlow()
    val value: Prefs get() = _state.value

    fun update(change: (Prefs) -> Prefs) {
        val next = change(_state.value)
        _state.value = next
        save(next)
    }

    fun reset() = update { Prefs() }

    private inline fun <reified E : Enum<E>> enumOf(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    private fun load(): Prefs {
        val d = Prefs()
        return Prefs(
            accent = prefs.getInt("accent", d.accent).coerceIn(0, ACCENTS.lastIndex),
            cardSize = enumOf(prefs.getString("cardSize", null), d.cardSize),
            backdrop = enumOf(prefs.getString("backdrop", null), d.backdrop),
            overscanDp = prefs.getInt("overscanDp", d.overscanDp),
            showDescriptions = prefs.getBoolean("showDescriptions", d.showDescriptions),
            showClock = prefs.getBoolean("showClock", d.showClock),
            cursorSpeed = prefs.getInt("cursorSpeed", d.cursorSpeed),
            cursorSize = prefs.getInt("cursorSize", d.cursorSize),
            viewMode = enumOf(prefs.getString("viewMode", null), d.viewMode),
            windowMode = enumOf(prefs.getString("windowMode", null), d.windowMode),
            adBlock = prefs.getBoolean("adBlock", d.adBlock),
            cosmetic = prefs.getBoolean("cosmetic", d.cosmetic),
            blockPopups = prefs.getBoolean("blockPopups", d.blockPopups),
            // v1.0 defaulted to the Gamepad API; Auto replaces it once (version 2).
            controllerMode = enumOf(prefs.getString("controllerMode", null), d.controllerMode).let {
                if (prefs.getInt("version", 1) < 2 && it == ControllerMode.Native) ControllerMode.Auto else it
            },
            mapping = Gamepad.decode(prefs.getString("mapping", null)),
            searchEngine = enumOf(prefs.getString("searchEngine", null), d.searchEngine),
            keepScreenOn = prefs.getBoolean("keepScreenOn", d.keepScreenOn),
            textZoom = prefs.getInt("textZoom", d.textZoom),
            startupSite = prefs.getString("startupSite", d.startupSite).orEmpty(),
            customUserAgent = prefs.getString("customUserAgent", d.customUserAgent).orEmpty(),
            customBlockList = prefs.getString("customBlockList", d.customBlockList).orEmpty(),
            hiddenSites = prefs.getStringSet("hiddenSites", d.hiddenSites).orEmpty().toSet(),
            favorites = prefs.getStringSet("favorites", d.favorites).orEmpty().toSet(),
            userscripts = prefs.getBoolean("userscripts", d.userscripts),
            autoFullscreenGames = prefs.getBoolean("autoFullscreenGames", d.autoFullscreenGames),
            sandboxApps = prefs.getBoolean("sandboxApps", d.sandboxApps),
            builtInPlayer = prefs.getBoolean("builtInPlayer", d.builtInPlayer)
        )
    }

    private fun save(p: Prefs) = prefs.edit {
        putInt("accent", p.accent)
        putString("cardSize", p.cardSize.name)
        putString("backdrop", p.backdrop.name)
        putInt("overscanDp", p.overscanDp)
        putBoolean("showDescriptions", p.showDescriptions)
        putBoolean("showClock", p.showClock)
        putInt("cursorSpeed", p.cursorSpeed)
        putInt("cursorSize", p.cursorSize)
        putString("viewMode", p.viewMode.name)
        putString("windowMode", p.windowMode.name)
        putBoolean("adBlock", p.adBlock)
        putBoolean("cosmetic", p.cosmetic)
        putBoolean("blockPopups", p.blockPopups)
        putString("controllerMode", p.controllerMode.name)
        putString("mapping", Gamepad.encode(p.mapping))
        putString("searchEngine", p.searchEngine.name)
        putBoolean("keepScreenOn", p.keepScreenOn)
        putInt("textZoom", p.textZoom)
        putString("startupSite", p.startupSite)
        putString("customUserAgent", p.customUserAgent)
        putString("customBlockList", p.customBlockList)
        putStringSet("hiddenSites", p.hiddenSites)
        putStringSet("favorites", p.favorites)
        putBoolean("userscripts", p.userscripts)
        putBoolean("autoFullscreenGames", p.autoFullscreenGames)
        putBoolean("sandboxApps", p.sandboxApps)
        putBoolean("builtInPlayer", p.builtInPlayer)
        putInt("version", 2)
    }

    // Per-site overrides, keyed by host.

    private val sitePrefs = context.applicationContext.getSharedPreferences("site_prefs", Context.MODE_PRIVATE)
    private val _siteVersion = MutableStateFlow(0)
    val siteVersion: StateFlow<Int> = _siteVersion.asStateFlow()

    fun site(host: String): SitePrefs {
        val json = sitePrefs.getString(host, null) ?: return SitePrefs()
        return runCatching {
            val o = JSONObject(json)
            SitePrefs(
                viewMode = o.optString("viewMode").takeIf { it.isNotEmpty() }?.let { n -> ViewMode.entries.firstOrNull { it.name == n } },
                adBlock = if (o.has("adBlock")) o.getBoolean("adBlock") else null,
                controllerMode = o.optString("controllerMode").takeIf { it.isNotEmpty() }?.let { n -> ControllerMode.entries.firstOrNull { it.name == n } },
                zoom = if (o.has("zoom")) o.getInt("zoom") else null,
                player = if (o.has("player")) o.getBoolean("player") else null
            )
        }.getOrDefault(SitePrefs())
    }

    fun updateSite(host: String, change: (SitePrefs) -> SitePrefs) {
        if (host.isEmpty()) return
        val s = change(site(host))
        val o = JSONObject()
        s.viewMode?.let { o.put("viewMode", it.name) }
        s.adBlock?.let { o.put("adBlock", it) }
        s.controllerMode?.let { o.put("controllerMode", it.name) }
        s.zoom?.let { o.put("zoom", it) }
        s.player?.let { o.put("player", it) }
        sitePrefs.edit { if (o.length() == 0) remove(host) else putString(host, o.toString()) }
        _siteVersion.value++
    }

    fun viewMode(host: String) = site(host).viewMode ?: value.viewMode
    fun adBlock(host: String) = value.adBlock && (site(host).adBlock ?: true)
    fun controllerMode(host: String) = site(host).controllerMode ?: value.controllerMode
    fun zoom(host: String) = site(host).zoom ?: value.textZoom
    fun builtInPlayer(host: String) = site(host).player ?: value.builtInPlayer

    fun clearSiteOverrides() {
        sitePrefs.edit { clear() }
        _siteVersion.value++
    }
}

/** Sites the user added (bookmarks shown on the home screen next to the built-ins). */
class SiteStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("sites", Context.MODE_PRIVATE)
    private val _custom = MutableStateFlow(load())
    val custom: StateFlow<List<Site>> = _custom.asStateFlow()

    fun all(): List<Site> = BuiltInSites.ALL + _custom.value
    fun byId(id: String?): Site? = all().firstOrNull { it.id == id }

    fun add(name: String, url: String): Site {
        val clean = UrlInput.toUrl(url)
        val existing = _custom.value.firstOrNull { it.url == clean }
        if (existing != null) return existing
        val palette = listOf(0xFF7E57C2, 0xFF26A69A, 0xFFEF5350, 0xFF42A5F5, 0xFFFFA726, 0xFF66BB6A, 0xFFEC407A, 0xFF8D6E63)
        val site = Site(
            id = "user_" + System.currentTimeMillis().toString(36),
            name = name.trim().ifEmpty { UrlInput.host(clean) ?: clean },
            url = clean,
            description = UrlInput.host(clean).orEmpty(),
            color = palette[(clean.hashCode() and 0x7fffffff) % palette.size],
            builtIn = false,
            tags = listOf("My sites")
        )
        save(_custom.value + site)
        return site
    }

    fun rename(id: String, name: String) = save(_custom.value.map { if (it.id == id) it.copy(name = name) else it })
    fun remove(id: String) = save(_custom.value.filterNot { it.id == id })

    private fun save(list: List<Site>) {
        _custom.value = list
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url).put("desc", s.description).put("color", s.color))
        }
        prefs.edit { putString("custom", arr.toString()) }
    }

    private fun load(): List<Site> = runCatching {
        val arr = JSONArray(prefs.getString("custom", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Site(o.getString("id"), o.getString("name"), o.getString("url"), o.optString("desc"), o.optLong("color", 0xFF7E57C2), builtIn = false, tags = listOf("My sites"))
        }
    }.getOrDefault(emptyList())
}

data class HistoryEntry(val title: String, val url: String, val time: Long)

/** Recently visited pages, newest first, de-duplicated by URL. */
class History(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun record(title: String?, url: String?) {
        if (url.isNullOrEmpty() || !url.startsWith("http")) return
        val entry = HistoryEntry(title?.takeIf { it.isNotBlank() } ?: url, url, System.currentTimeMillis())
        save((listOf(entry) + _entries.value.filterNot { it.url == url }).take(MAX))
    }

    fun clear() = save(emptyList())

    private fun save(list: List<HistoryEntry>) {
        _entries.value = list
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.title).put("u", it.url).put("w", it.time)) }
        prefs.edit { putString("h", arr.toString()) }
    }

    private fun load(): List<HistoryEntry> = runCatching {
        val arr = JSONArray(prefs.getString("h", "[]"))
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { HistoryEntry(it.getString("t"), it.getString("u"), it.getLong("w")) } }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX = 300
    }
}
