package com.aiconsole.retrotv

import com.aiconsole.retrotv.player.CoreUsage
import com.aiconsole.retrotv.player.PreloadRules
import com.aiconsole.retrotv.player.PreloadRules.Skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadTest {
    private val free = 2_000_000_000L
    private val now = 100L * 3_600_000

    @Test fun runsWhenEverythingAllows() {
        assertNull(PreloadRules.skipReason(true, true, now, 0, free))
        assertNull(PreloadRules.skipReason(true, true, now, now - PreloadRules.INTERVAL_MS, free))
    }

    @Test fun skipsWhenOffRecentMeteredOrLow() {
        assertEquals(Skip.Off, PreloadRules.skipReason(false, true, now, 0, free))
        assertEquals(Skip.Recent, PreloadRules.skipReason(true, true, now, now - 1000, free))
        assertEquals(Skip.Metered, PreloadRules.skipReason(true, false, now, 0, free))
        assertEquals(Skip.LowStorage, PreloadRules.skipReason(true, true, now, 0, PreloadRules.MIN_FREE_BYTES - 1))
    }

    @Test fun clockGoingBackwardsDoesNotBlockForever() {
        assertNull(PreloadRules.skipReason(true, true, now, now + 1000, free))
    }

    @Test fun picksTopTwoByPlayTime() {
        val usage = listOf(
            CoreUsage("nes", 3, 600_000), CoreUsage("snes", 1, 900_000),
            CoreUsage("gba", 9, 10_000), CoreUsage("nes", 2, 600_000)
        )
        assertEquals(listOf("nes", "snes"), PreloadRules.topCores(usage))
    }

    @Test fun tiesBreakOnPlayCountAndUnplayedAreIgnored() {
        val usage = listOf(CoreUsage("a", 1, 0), CoreUsage("b", 5, 0), CoreUsage("c", 0, 0))
        assertEquals(listOf("b", "a"), PreloadRules.topCores(usage))
        assertTrue(PreloadRules.topCores(emptyList()).isEmpty())
    }

    @Test fun runStopsAtByteCapOrLowSpace() {
        assertTrue(PreloadRules.mayContinue(0, free))
        assertFalse(PreloadRules.mayContinue(PreloadRules.RUN_CAP_BYTES, free))
        assertFalse(PreloadRules.mayContinue(0, PreloadRules.MIN_FREE_BYTES - 1))
    }

    @Test fun statusLines() {
        assertEquals("Off", PreloadRules.status(false, listOf("NES"), 5, null))
        assertEquals("Waiting for Wi-Fi", PreloadRules.status(true, emptyList(), 0, Skip.Metered))
        assertEquals("Preloaded: NES, SNES · 38 MB", PreloadRules.status(true, listOf("NES", "SNES"), 38_000_000, null))
        assertEquals("Ready: NES", PreloadRules.status(true, listOf("NES"), 0, null))
        assertEquals("Paused: low storage", PreloadRules.status(true, listOf("NES"), 0, Skip.LowStorage))
    }
}
