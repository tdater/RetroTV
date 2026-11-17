package com.aiconsole.retrotv

import com.aiconsole.retrotv.data.UrlInput
import com.aiconsole.retrotv.web.PopupRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupRulesTest {
    private val hosts = listOf("retrogames.cc")
    private fun launch(url: String) = PopupRules.isGameLaunch(url) { PopupRules.hostIn(it, hosts) }

    @Test fun trustedHostGamePathIsALaunch() {
        assertTrue(launch("https://www.retrogames.cc/play/mario"))
        assertTrue(launch("https://files.retrogames.cc/game/x.html"))
        assertTrue(launch("https://retrogames.cc/x?rom=abc"))
    }

    @Test fun adUrlsWithGameWordsAreNotLaunches() {
        assertFalse(launch("https://ads.example/x?r=/play"))
        assertFalse(launch("https://ads.example/play/offer"))
        assertFalse(launch("https://ads.example/x?rom=1"))
        assertFalse(launch("https://evil.com/path/retrogames.cc/play"))
    }

    @Test fun lookAlikeHostsAreNotTrusted() {
        assertFalse(launch("https://notretrogames.cc/play"))
        assertFalse(launch("https://retrogames.cc.evil.com/play"))
    }

    @Test fun authorityTricksAreRefused() {
        assertFalse(launch("https://x@evil.com\\.retrogames.cc/play"))
        assertFalse(launch("https://evil.com\\@retrogames.cc/play"))
        assertFalse(launch("https://user@retrogames.cc/play"))
        assertFalse(launch("https://retrogames.cc\\.evil.com/play"))
    }

    @Test fun trustedHostOtherPathIsNotALaunch() {
        assertFalse(launch("https://retrogames.cc/promo"))
    }

    @Test fun hostRejectsBackslashAndUserinfo() {
        assertNull(UrlInput.host("https://x@evil.com\\.retrogames.cc/p"))
        assertNull(UrlInput.host("https://user@retrogames.cc/"))
        assertEquals("retrogames.cc", UrlInput.host("https://www.retrogames.cc:8080/a"))
    }
}
