package com.aiconsole.retrotv.web

import com.aiconsole.retrotv.data.UrlInput

/** Which pop-ups a game site may open without being held as a "blocked pop-up" (pure, unit-tested). */
object PopupRules {

    private val GAME_PATHS = listOf("/game", "/play", "/emulator")
    private val HOST_CHARS = Regex("[a-z0-9.-]+")

    /**
     * True for a game launch: the host is exactly a trusted host or a dot-anchored subdomain of one,
     * and the path (never the query alone, never another host's URL) looks like a game page, or the
     * query names a ROM. [trusted] holds bare hosts (built-in sites, the ad blocker's game-host allow-list).
     */
    fun isGameLaunch(url: String, trusted: (String) -> Boolean): Boolean {
        // Browsers treat '\' like '/' and '@' as userinfo, so such an authority can name another host
        // than a plain parse does; refuse it outright, and only accept plain host characters.
        val authority = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '\\' in authority || '@' in authority) return false
        val host = UrlInput.host(url) ?: return false
        if (!HOST_CHARS.matches(host) || !trusted(host)) return false
        val rest = url.substringAfter("://").substringAfter('/', "")
        val path = "/" + rest.substringBefore('#').substringBefore('?').lowercase()
        val query = rest.substringBefore('#').substringAfter('?', "").lowercase()
        return GAME_PATHS.any { path.startsWith(it) } ||
            query.split('&').any { it.startsWith("rom=") }
    }

    /** Exact host or dot-anchored subdomain of any of [hosts]. */
    fun hostIn(host: String, hosts: Collection<String>): Boolean =
        hosts.any { it.isNotEmpty() && (host == it || host.endsWith(".$it")) }
}
