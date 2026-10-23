package com.aiconsole.retrotv

import com.aiconsole.retrotv.player.Caps
import com.aiconsole.retrotv.player.ControlsHelp
import com.aiconsole.retrotv.player.Preflight
import com.aiconsole.retrotv.player.ProgressText
import com.aiconsole.retrotv.player.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRound2Test {

    private val full = Caps(webgl2 = true, sharedArrayBuffer = true, crossOriginIsolated = true, wasmThreads = true)

    @Test
    fun capableWebViewIsOkForEverything() {
        for (core in listOf("nes", "psp", "ppsspp", "dos", "dosbox_pure", "n64")) assertEquals(Verdict.Ok, Preflight.verdict(core, full))
    }

    @Test
    fun threadedSystemsBlockWithoutIsolation() {
        for (core in listOf("psp", "ppsspp", "dos", "dosbox_pure")) {
            val v = Preflight.verdict(core, full.copy(crossOriginIsolated = false))
            assertTrue(core, v is Verdict.Block && v.suggestSitePlayer && v.message.isNotBlank())
        }
        assertTrue(Preflight.verdict("psp", full.copy(sharedArrayBuffer = false)) is Verdict.Block)
        val noThreads = Preflight.verdict("dos", full.copy(wasmThreads = false)) as Verdict.Block
        assertTrue(noThreads.message.contains("MS-DOS"))
    }

    @Test
    fun unthreadedSystemsIgnoreIsolation() {
        val plain = Caps(webgl2 = true, sharedArrayBuffer = false, crossOriginIsolated = false, wasmThreads = false)
        assertEquals(Verdict.Ok, Preflight.verdict("snes", plain))
    }

    @Test
    fun noWebGl2WarnsAndBlockWins() {
        val v = Preflight.verdict("snes", full.copy(webgl2 = false))
        assertEquals(Verdict.Warn(Preflight.NO_WEBGL2), v)
        assertTrue(Preflight.verdict("psp", full.copy(webgl2 = false, crossOriginIsolated = false)) is Verdict.Block)
        assertTrue(Preflight.verdict("psp", full.copy(webgl2 = false)) is Verdict.Warn)
    }

    @Test
    fun progressWithKnownTotal() {
        assertEquals("Fetching game… 0%", ProgressText.format(0, 100))
        assertEquals("Fetching game… 40%", ProgressText.format(40, 100))
        assertEquals("Fetching game… 33%", ProgressText.format(1, 3))
        assertEquals("Fetching game… 100%", ProgressText.format(100, 100))
    }

    @Test
    fun progressOverHundredIsClamped() {
        assertEquals("Fetching game… 100%", ProgressText.format(150, 100))
        assertEquals(1f, ProgressText.fraction(150, 100)!!, 0f)
        assertEquals(0f, ProgressText.fraction(-5, 100)!!, 0f)
    }

    @Test
    fun progressWithUnknownTotalShowsSize() {
        assertEquals("Fetching game…", ProgressText.format(0, -1))
        assertEquals("Fetching game…", ProgressText.format(0, 0))
        assertEquals("Fetching game… 12 MB", ProgressText.format(12L * 1024 * 1024, -1))
        assertEquals("Fetching game… 2.5 MB", ProgressText.format(2_621_440, -1))
        assertEquals("Fetching game… 800 KB", ProgressText.format(800L * 1024, -1))
        assertEquals("Fetching game… 1 KB", ProgressText.format(10, -1))
        assertNull(ProgressText.fraction(5, -1))
        assertNull(ProgressText.fraction(5, 0))
    }

    @Test
    fun controlsHelpDiffersByInput() {
        val pad = ControlsHelp.lines(true)
        val remote = ControlsHelp.lines(false)
        assertTrue(pad.any { it.contains("Select + Start") })
        assertTrue(remote.any { it.contains("OK") && it.contains("A button") })
        assertTrue(remote.any { it.contains("Back or Menu") })
        assertTrue(pad.any { it.contains("Back or Menu") })
        assertTrue(pad != remote)
        assertTrue(pad.all { it.isNotBlank() } && remote.all { it.isNotBlank() })
    }
}
