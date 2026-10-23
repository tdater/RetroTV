package com.aiconsole.retrotv

import com.aiconsole.retrotv.browser.FixLadder
import com.aiconsole.retrotv.browser.FixLadder.Step
import com.aiconsole.retrotv.web.AdBlock
import com.aiconsole.retrotv.web.SiteHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FixThisPageTest {

    private val start = FixLadder.State(desktop = true, adBlockOn = true, builtInPlayer = true, controller = FixLadder.AUTO)

    @Test
    fun laddersRunInOrderFromTheStartingState() {
        val tried = mutableSetOf<Step>()
        val order = mutableListOf<Step>()
        // The browser judges every step against the state saved before the first fix, so the
        // steps' own effects must not change what is offered next.
        val state = start
        while (true) {
            val step = FixLadder.next(tried, state) ?: break
            order += step
            tried += step
            assertEquals(8, FixLadder.progress(tried - step, state).second)
        }
        assertEquals(
            listOf(
                Step.Reload, Step.TogglePlayer, Step.ToggleView, Step.AdBlockOff,
                Step.KeyboardKeys, Step.GamepadApi, Step.BothControllers, Step.Report
            ),
            order
        )
        assertNull(FixLadder.next(tried, state))
    }

    @Test
    fun stepCountStaysStableAndNeverRestoresTheOriginalController() {
        val base = start.copy(controller = FixLadder.KEYBOARD)
        val tried = mutableSetOf<Step>()
        val order = mutableListOf<Step>()
        val totals = mutableSetOf<Int>()
        while (true) {
            totals += FixLadder.progress(tried, base).second
            val step = FixLadder.next(tried, base) ?: break
            order += step
            tried += step
        }
        assertEquals(setOf(7), totals)
        assertFalse(Step.KeyboardKeys in order)
        assertEquals(listOf(Step.GamepadApi, Step.BothControllers, Step.Report), order.takeLast(3))
    }

    @Test
    fun stepsThatChangeNothingAreSkipped() {
        val noAds = start.copy(adBlockOn = false, controller = FixLadder.KEYBOARD)
        val tried = setOf(Step.Reload, Step.TogglePlayer, Step.ToggleView)
        assertEquals(Step.GamepadApi, FixLadder.next(tried, noAds))
        assertFalse(FixLadder.applicable(Step.AdBlockOff, noAds))
        assertFalse(FixLadder.applicable(Step.KeyboardKeys, noAds))
        assertTrue(FixLadder.applicable(Step.Reload, noAds))
    }

    @Test
    fun progressCountsTriedPlusRemaining() {
        assertEquals(1 to 8, FixLadder.progress(emptySet(), start))
        assertEquals(2 to 8, FixLadder.progress(setOf(Step.Reload), start))
        assertEquals("Try 1 of 8: Reload the page", FixLadder.hint(emptySet(), start))
        assertEquals("Try 2 of 8: Switch game player", FixLadder.hint(setOf(Step.Reload), start))
    }

    @Test
    fun exhaustedLadderOffersReset() {
        assertEquals("All fixes tried · tap to reset", FixLadder.hint(Step.entries.toSet(), start))
        assertNull(FixLadder.next(Step.entries.toSet(), start))
    }

    @Test
    fun onlyRealOptionChangesArePersisted() {
        assertFalse(Step.Reload.persists)
        assertFalse(Step.Report.persists)
        listOf(Step.TogglePlayer, Step.ToggleView, Step.AdBlockOff, Step.KeyboardKeys, Step.GamepadApi, Step.BothControllers)
            .forEach { assertTrue(it.persists) }
        assertEquals(Step.Report, Step.entries.last())
    }

    @Test
    fun fakeStorageOverlayHostsAreBlocked() {
        assertTrue(AdBlock.isBlocked("sowve.com"))
        assertTrue(AdBlock.isBlocked("cdn.totaldrive.com"))
    }

    @Test
    fun gameAndEmulatorHostsAreNeverBlocked() {
        listOf(
            "cdn.emulatorjs.org", "emulatorjs.com", "filesus3.retrogames.cc", "roms.emulatorgamer.com",
            "r2.oldgames.app", "mem.neptunjs.com", "ia800000.us.archive.org", "archive.org"
        ).forEach {
            assertTrue("$it should be allowed", AdBlock.isAllowed(it))
            assertFalse("$it must not be blocked", AdBlock.isBlocked(it))
        }
    }

    @Test
    fun broadAdHostsStayBlocked() {
        listOf(
            "media.net", "connatix.com", "aniview.com", "playwire.com", "primis.tech",
            "mgid.com", "branch.io", "segment.io", "onesignal.com", "cookielaw.org"
        ).forEach { assertTrue("$it should be blocked", AdBlock.isBlocked(it)) }
    }

    @Test
    fun twoClickSitesGetOneHint() {
        val hint = "Press OK on Play, then OK again to start"
        assertEquals(hint, SiteHints.forHost("retrogamesnexus.com"))
        assertEquals(hint, SiteHints.forHost("www.playclassic.games"))
        assertEquals(hint, SiteHints.forHost("EmulatorGamer.com"))
        assertEquals("emulatorgamer.com", SiteHints.key("www.emulatorgamer.com"))
        assertNull(SiteHints.forHost("archive.org"))
        assertNull(SiteHints.forHost(null))
        assertNull(SiteHints.key("example.com"))
    }
}
