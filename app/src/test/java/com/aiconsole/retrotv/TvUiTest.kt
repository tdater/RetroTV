package com.aiconsole.retrotv

import android.view.KeyEvent
import com.aiconsole.retrotv.data.BuiltInSites
import com.aiconsole.retrotv.player.GameRequest
import com.aiconsole.retrotv.player.RecentGame
import com.aiconsole.retrotv.ui.AttractRules
import com.aiconsole.retrotv.ui.ButtonNames
import com.aiconsole.retrotv.ui.Readability
import com.aiconsole.retrotv.ui.Slide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttractRulesTest {
    private fun game(t: String, ms: Long, fav: Boolean = false) =
        RecentGame(GameRequest(core = "nes", game = "https://x/$t.zip", title = t), playedAt = 1, playMs = ms, favorite = fav)

    @Test
    fun startsAfterTheIdleTime() {
        assertFalse(AttractRules.due(0, 119_999, 2))
        assertTrue(AttractRules.due(0, 120_000, 2))
        assertFalse(AttractRules.due(0, 1_000_000_000, 0))
    }

    @Test
    fun gamesFirstSitesAsFallback() {
        val sites = BuiltInSites.ALL.take(3)
        assertTrue(AttractRules.slides(emptyList(), sites).all { it is Slide.SiteCard })
        assertEquals(3, AttractRules.slides(emptyList(), sites).size)
        val slides = AttractRules.slides(listOf(game("A", 900), game("B", 10, fav = true), game("C", 5_000)), sites)
        assertEquals(listOf("B", "C", "A"), slides.map { (it as Slide.Game).game.request.displayTitle })
        assertEquals(2, AttractRules.slides(listOf(game("A", 1), game("B", 2), game("C", 3)), sites, max = 2).size)
    }
}

class ButtonNamesTest {
    @Test
    fun labels() {
        assertEquals("A", ButtonNames.label(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals("Y", ButtonNames.label(KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals("L2", ButtonNames.label(KeyEvent.KEYCODE_BUTTON_L2))
        assertEquals("Select", ButtonNames.label(KeyEvent.KEYCODE_BUTTON_SELECT))
        assertEquals("Start", ButtonNames.label(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals("▲", ButtonNames.label(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals("R3", ButtonNames.label(KeyEvent.KEYCODE_BUTTON_THUMBR))
        assertNull(ButtonNames.label(KeyEvent.KEYCODE_VOLUME_UP))
    }
}

class ReadabilityTest {
    @Test
    fun scales() {
        assertEquals(1.0f, Readability.fontScale(0))
        assertEquals(1.15f, Readability.fontScale(1))
        assertEquals(1.3f, Readability.fontScale(2))
        assertEquals(1.0f, Readability.fontScale(9))
        assertEquals(3, Readability.focusBorder(false))
        assertEquals(6, Readability.focusBorder(true))
        assertEquals(listOf("Normal", "Large", "Extra large"), Readability.LABELS)
    }
}
