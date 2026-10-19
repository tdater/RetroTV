package com.aiconsole.retrotv.data

import com.aiconsole.retrotv.player.RecentGame
import java.net.URI
import java.net.URLEncoder

/**
 * Search links for each game site (pure, unit-tested). Templates were checked against the sites
 * on 2026-10-19; sites without a usable search page get a `site:` search on the user's engine.
 */
object SiteSearch {

    /** Search paths by bare host (see [UrlInput.host]); `{q}` is the encoded query. */
    private val PATHS = mapOf(
        "retrogames.cc" to "/search?q={q}",
        "playretrogames.com" to "/search.php?q={q}",
        "rec0ded88.com" to "/?s={q}",
        "free80sarcade.com" to "/?s={q}",
        "emulatorgamer.com" to "/?s={q}",
        "retrogamesnexus.com" to "/?s={q}",
        "playclassic.games" to "/?s={q}"
    )

    fun url(site: Site, query: String, engine: UrlInput.SearchEngine): String {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        // A user's own site may have an address java.net.URI rejects; it still gets a web search.
        val uri = runCatching { URI(site.url) }.getOrNull()?.takeIf { it.host != null }
            ?: return UrlInput.toUrl("site:${site.host} ${query.trim()}".trim(), engine)
        PATHS[site.host]?.let { return "${uri.scheme}://${uri.host}" + it.replace("{q}", q) }
        if (site.host == "archive.org" && site.url.contains("/details/")) return site.url.substringBefore('?').trimEnd('/') + "?query=$q"
        return UrlInput.toUrl("site:${uri.host} ${query.trim()}", engine)
    }
}

/** What a site card shows beyond its name (pure, unit-tested). Hosts compare without `www.`. */
object SiteBadges {

    private fun bare(host: String) = host.lowercase().removePrefix("www.")

    /** The game most recently played from this site's pages. */
    fun lastGame(host: String, games: List<RecentGame>): RecentGame? =
        games.filter { UrlInput.host(it.request.page)?.let(::bare) == bare(host) }.maxByOrNull { it.playedAt }

    /** True once a game from this site has opened in RetroTV's player. */
    fun usesPlayer(host: String, playerHosts: Set<String>): Boolean = playerHosts.any { bare(it) == bare(host) }
}
