package com.aiconsole.retrotv

import com.aiconsole.retrotv.data.BuiltInSites
import com.aiconsole.retrotv.data.SiteBadges
import com.aiconsole.retrotv.data.SiteSearch
import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.player.GameRequest
import com.aiconsole.retrotv.player.RecentGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchAndBadgesTest {

    private val google = UrlInput.SearchEngine.Google
    private fun site(id: String) = BuiltInSites.byId(id)!!

    @Test
    fun sitesWithTheirOwnSearch() {
        assertEquals("https://www.retrogames.cc/search?q=mario+%26+luigi", SiteSearch.url(site("retrogames_cc"), "mario & luigi", google))
        assertEquals("https://www.playretrogames.com/search.php?q=sonic", SiteSearch.url(site("playretrogames"), "sonic", google))
        assertEquals("https://rec0ded88.com/?s=street+fighter", SiteSearch.url(site("rec0ded88"), "street fighter", google))
        assertEquals("https://archive.org/details/internetarcade?query=pac-man", SiteSearch.url(site("internetarcade"), "pac-man", google))
    }

    @Test
    fun otherSitesUseTheSearchEngine() {
        assertEquals(UrlInput.toUrl("site:play.webrcade.com zelda", google), SiteSearch.url(site("webrcade"), "zelda", google))
        assertTrue(SiteSearch.url(site("webrcade"), "zelda #1", google).contains("%231"))
    }

    private fun played(page: String, at: Long) =
        RecentGame(GameRequest(core = "nes", game = "https://x/$at.zip", title = "Game $at", page = page), playedAt = at)

    @Test
    fun cardsShowTheNewestGameFromTheirSite() {
        val games = listOf(played("https://www.retrogames.cc/nes/a.html", 100), played("https://www.retrogames.cc/nes/b.html", 300), played("https://gam.onl/x", 500))
        assertEquals(300L, SiteBadges.lastGame("retrogames.cc", games)?.playedAt)
        assertEquals(300L, SiteBadges.lastGame("www.retrogames.cc", games)?.playedAt)
        assertNull(SiteBadges.lastGame("classicreload.com", games))
    }

    @Test
    fun playerBadgeIgnoresWww() {
        assertTrue(SiteBadges.usesPlayer("www.x.com", setOf("x.com")))
        assertTrue(SiteBadges.usesPlayer("x.com", setOf("www.x.com")))
        assertFalse(SiteBadges.usesPlayer("y.com", setOf("x.com")))
    }
}
