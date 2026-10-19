package com.aiconsole.retrotv

import android.view.KeyEvent
import com.aiconsole.retrotv.browser.PadSlots
import com.aiconsole.retrotv.player.Cheat
import com.aiconsole.retrotv.player.CheatRules
import com.aiconsole.retrotv.player.Filters
import com.aiconsole.retrotv.player.HotkeyAction
import com.aiconsole.retrotv.player.Hotkeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PadSlotsTest {
    @Test
    fun claimingSwapsOrMoves() {
        assertEquals(mapOf(11 to 0, 10 to 1), PadSlots.claim(mapOf(10 to 0, 11 to 1), 11, 0))
        assertEquals(mapOf(12 to 0), PadSlots.claim(mapOf(10 to 0), 12, 0))
        assertEquals(mapOf(10 to 2), PadSlots.claim(mapOf(10 to 0), 10, 2))
        val m = mapOf(10 to 0)
        assertEquals(m, PadSlots.claim(m, 11, 7))
    }
}

class HotkeysTest {
    @Test
    fun selectCombos() {
        assertEquals(HotkeyAction.QuickSave, Hotkeys.action(true, KeyEvent.KEYCODE_BUTTON_L1, true))
        assertEquals(HotkeyAction.QuickLoad, Hotkeys.action(true, KeyEvent.KEYCODE_BUTTON_R1, true))
        assertEquals(HotkeyAction.RewindOn, Hotkeys.action(true, KeyEvent.KEYCODE_BUTTON_L2, true))
        assertEquals(HotkeyAction.RewindOff, Hotkeys.action(true, KeyEvent.KEYCODE_BUTTON_L2, false))
        assertEquals(HotkeyAction.FastForward, Hotkeys.action(true, KeyEvent.KEYCODE_BUTTON_R2, true))
    }

    @Test
    fun gameButtonsPassThrough() {
        assertNull(Hotkeys.action(false, KeyEvent.KEYCODE_BUTTON_L1, true))
        assertNull(Hotkeys.action(true, KeyEvent.KEYCODE_BUTTON_A, true))
        assertNull(Hotkeys.action(true, KeyEvent.KEYCODE_BUTTON_L1, false))
    }

    @Test
    fun remoteRewindKey() {
        assertEquals(HotkeyAction.RewindOn, Hotkeys.action(false, KeyEvent.KEYCODE_MEDIA_REWIND, true))
        assertEquals(HotkeyAction.RewindOff, Hotkeys.action(false, KeyEvent.KEYCODE_MEDIA_REWIND, false))
    }
}

class FiltersTest {
    @Test
    fun namesAndCycling() {
        assertEquals("CRT curved", Filters.label("crt-geom.glslp"))
        assertEquals("Off", Filters.label("nope"))
        assertEquals("crt-easymode.glslp", Filters.next("disabled"))
        assertEquals("disabled", Filters.next(Filters.ALL.last().first))
        assertEquals("crt-easymode.glslp", Filters.next("nope"))
    }
}

class CheatRulesTest {
    @Test
    fun pastedListsParse() {
        val text = "Infinite lives: 00C-5D2-6FB\r\n# comment\n\nSXIOPO Moon jump\nAPZLGG\nnot a code"
        assertEquals(
            listOf(Cheat("Infinite lives", "00C-5D2-6FB"), Cheat("Moon jump", "SXIOPO"), Cheat("Cheat 3", "APZLGG")),
            CheatRules.parse(text)
        )
    }

    @Test
    fun codesAreRecognised() {
        assertFalse(CheatRules.isCode("hello"))
        assertTrue(CheatRules.isCode("8100-1234"))
        assertTrue(CheatRules.isCode("8100:1234"))
        assertEquals(listOf(Cheat("Cheat 1", "8100:1234")), CheatRules.parse("8100:1234"))
    }

    @Test
    fun storageRoundTrips() {
        val list = listOf(Cheat("Lives: 99", "8100-1234", on = true), Cheat("Jump", "SXIOPO"))
        assertEquals(list, CheatRules.decode(CheatRules.encode(list)))
        assertEquals(emptyList<Cheat>(), CheatRules.decode(""))
    }
}
