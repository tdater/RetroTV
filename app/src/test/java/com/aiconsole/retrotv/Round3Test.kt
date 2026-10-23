package com.aiconsole.retrotv

import com.aiconsole.retrotv.player.AutoResume
import com.aiconsole.retrotv.player.ControlProfiles
import com.aiconsole.retrotv.player.CoopSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Round3Test {

    private val layout = """{"0":{"0":{"value":"x"}}}"""
    private val other = """{"0":{"0":{"value":"z"}}}"""

    @Test fun profileKeys() {
        assertEquals("controls_abc", ControlProfiles.gameKey("abc"))
        assertEquals("profile_segamd", ControlProfiles.systemKey(" segaMD "))
        assertNull(ControlProfiles.systemKey(null))
        assertNull(ControlProfiles.systemKey("  "))
    }

    @Test fun profileValidity() {
        assertTrue(ControlProfiles.isValid(layout))
        assertFalse(ControlProfiles.isValid(null))
        assertFalse(ControlProfiles.isValid(""))
        assertFalse(ControlProfiles.isValid("[1]"))
        assertFalse(ControlProfiles.isValid("{" + "x".repeat(ControlProfiles.MAX_LENGTH) + "}"))
    }

    @Test fun resolveOrderGameSystemDefault() {
        assertEquals(layout, ControlProfiles.resolve(layout, other))
        assertEquals(other, ControlProfiles.resolve(null, other))
        assertEquals(other, ControlProfiles.resolve("junk", other))
        assertNull(ControlProfiles.resolve(null, null))
        assertEquals(ControlProfiles.Source.Game, ControlProfiles.source(layout, other))
        assertEquals(ControlProfiles.Source.System, ControlProfiles.source(null, other))
        assertEquals(ControlProfiles.Source.Default, ControlProfiles.source("", ""))
    }

    @Test fun copyAndReset() {
        val prefs = mapOf("controls_g" to layout, "profile_nes" to other)
        val copied = ControlProfiles.copyToSystem(prefs, "g", "nes")
        assertEquals(layout, copied["profile_nes"])
        assertEquals(prefs, ControlProfiles.copyToSystem(prefs, "missing", "nes"))
        assertEquals(prefs, ControlProfiles.copyToSystem(prefs, "g", null))
        assertFalse("profile_nes" in ControlProfiles.reset(prefs, "nes"))
        assertEquals(prefs, ControlProfiles.reset(prefs, null))
    }

    @Test fun autoResumePicksNewestSlot() {
        assertNull(AutoResume.pickSlot(emptyMap()))
        assertEquals(3, AutoResume.pickSlot(mapOf(1 to 100L, 3 to 300L, 2 to 200L)))
        assertEquals(1, AutoResume.pickSlot(mapOf(2 to 50L, 1 to 50L)))
        assertNull(AutoResume.pickSlot(mapOf(99 to 5L)))
    }

    @Test fun coopOnlyWhenMultiplayerAndSeveralPads() {
        assertTrue(CoopSetup.shouldShow("snes", 2))
        assertFalse(CoopSetup.shouldShow("snes", 1))
        assertFalse(CoopSetup.shouldShow("gba", 3))
        assertFalse(CoopSetup.shouldShow(null, 3))
        assertEquals(4, CoopSetup.maxPlayers("N64"))
        assertEquals(1, CoopSetup.maxPlayers("dos"))
    }

    @Test fun coopLabels() {
        val labels = CoopSetup.labels("segaMS", listOf("Pad A", null, null, null), 1)
        assertEquals(listOf("Player 1: Pad A", "Player 2: press Start on a controller…"), labels)
        assertEquals(4, CoopSetup.labels("nes", listOf(null, null, null, null), null).size)
        assertEquals("Player 3: nobody", CoopSetup.labels("nes", emptyList(), null)[2])
    }
}
