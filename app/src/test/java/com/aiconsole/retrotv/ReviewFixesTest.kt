package com.aiconsole.retrotv

import com.aiconsole.retrotv.link.SyncRules
import com.aiconsole.retrotv.player.AutoResume
import com.aiconsole.retrotv.player.CoopSetup
import com.aiconsole.retrotv.player.DevicePerf
import com.aiconsole.retrotv.player.PadDevice
import com.aiconsole.retrotv.player.PlayerOpen
import com.aiconsole.retrotv.player.SaveNames
import com.aiconsole.retrotv.player.StutterRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFixesTest {

    // ---- Auto-resume slot ----

    @Test fun autoSlotWinsWhenPresent() =
        assertEquals(SaveNames.AUTO, AutoResume.pickSlot(mapOf(1 to 900L, 2 to 100L), autoTime = 50L))

    @Test fun newestManualSlotWithoutAuto() =
        assertEquals(1, AutoResume.pickSlot(mapOf(1 to 900L, 2 to 100L), autoTime = null))

    @Test fun autoAloneIsStillPicked() = assertEquals(SaveNames.AUTO, AutoResume.pickSlot(emptyMap(), 10L))

    @Test fun nothingSaved() = assertNull(AutoResume.pickSlot(emptyMap(), null))

    @Test fun exitCapIsBounded() = assertTrue(AutoResume.EXIT_CAP_MS in 5_000L..10_000L)

    @Test fun autoIsNotOneOfTheFourSlots() {
        assertEquals(4, SaveNames.SLOTS)
        assertNull(SaveNames.slotOf("auto.state"))
        assertTrue(SaveNames.isAutoState("auto.state"))
        assertFalse(SaveNames.isAutoState("slot1.state"))
        assertEquals("auto", SaveNames.stem(SaveNames.AUTO))
        assertEquals("slot3", SaveNames.stem(3))
    }

    @Test fun autoFilesAreServedAndSynced() {
        assertTrue(SaveNames.isServed("auto.state"))
        assertTrue(SaveNames.isServed("auto.png"))
        assertFalse(SaveNames.isServed("auto.txt"))
        val dir = "0123456789abcdef01234567"
        assertTrue(SyncRules.isValid("$dir/auto.state"))
        assertTrue(SyncRules.isValid("$dir/auto.png"))
        assertFalse(SyncRules.isValid("$dir/auto.zip"))
    }

    // ---- Player open flag ----

    @Test fun playerOpenCounts() {
        assertFalse(PlayerOpen.isOpen)
        PlayerOpen.enter()
        PlayerOpen.enter()
        assertTrue(PlayerOpen.isOpen)
        PlayerOpen.leave()
        assertTrue(PlayerOpen.isOpen)
        PlayerOpen.leave()
        PlayerOpen.leave() // extra leave never goes negative
        assertFalse(PlayerOpen.isOpen)
        PlayerOpen.enter()
        assertTrue(PlayerOpen.isOpen)
        PlayerOpen.leave()
    }

    // ---- Controller count ----

    private val gamepad = 0x00000401
    private val keyboard = 0x00000101

    @Test fun virtualDevicesAreNotControllers() =
        assertEquals(1, CoopSetup.countControllers(listOf(PadDevice("a", gamepad, false), PadDevice("v", gamepad, true))))

    @Test fun sameDescriptorCountsOnce() =
        assertEquals(1, CoopSetup.countControllers(listOf(PadDevice("a", gamepad, false), PadDevice("a", 0x01000010, false))))

    @Test fun keyboardsAreNotControllers() = assertEquals(0, CoopSetup.countControllers(listOf(PadDevice("k", keyboard, false))))

    @Test fun joysticksCount() =
        assertEquals(2, CoopSetup.countControllers(listOf(PadDevice("a", 0x01000010, false), PadDevice("b", gamepad, false))))

    // ---- Stutter prompt ----

    private val slow = List(8) { 40.0 }

    @Test fun slowScreenSkipsThePrompt() = assertFalse(StutterRules.shouldPrompt(slow, 60.0, screenFps = 50.0))

    @Test fun healthyScreenStillPrompts() = assertTrue(StutterRules.shouldPrompt(slow, 60.0, screenFps = 60.0))

    @Test fun unknownScreenStillPrompts() = assertTrue(StutterRules.shouldPrompt(slow, 60.0))

    @Test fun screenAtNinetyPercentStillPrompts() = assertTrue(StutterRules.shouldPrompt(slow, 60.0, screenFps = 54.0))

    @Test fun targetIsCappedByDisplayRate() {
        assertEquals(50.0, StutterRules.effectiveTarget(60.0, 50f)!!, 0.0)
        assertEquals(60.0, StutterRules.effectiveTarget(60.0, 120f)!!, 0.0)
        assertEquals(60.0, StutterRules.effectiveTarget(60.0, 0f)!!, 0.0)
        assertNull(StutterRules.effectiveTarget(null, 60f))
    }

    @Test fun fiftyHzDisplayDoesNotFlagFiftyFpsGame() =
        assertFalse(StutterRules.shouldPrompt(List(8) { 49.0 }, StutterRules.effectiveTarget(60.0, 50f)))

    // ---- Low-end detection ----

    @Test fun manyCoresAloneAreNotLowEnd() = assertFalse(DevicePerf.isLowEnd(8, 3000, false))

    @Test fun smallMemoryAloneIsNotLowEnd() = assertFalse(DevicePerf.isLowEnd(8, 4096, false))

    @Test fun fewCoresAloneAreNotLowEnd() = assertFalse(DevicePerf.isLowEnd(4, 6000, false))

    @Test fun fewCoresAndSmallMemoryAreLowEnd() = assertTrue(DevicePerf.isLowEnd(4, 2048, false))

    @Test fun lowRamFlagAlwaysCounts() = assertTrue(DevicePerf.isLowEnd(8, 8000, true))

    @Test fun unknownMemoryIsNotLowEnd() = assertFalse(DevicePerf.isLowEnd(2, 0, false))

    // ---- Smooth default vs. the owner's own choices ----

    @Test fun untouchedSettingsMayDefaultToSmooth() = assertTrue(DevicePerf.mayDefaultToSmooth("disabled", false, false))

    @Test fun ownFilterBlocksTheDefault() = assertFalse(DevicePerf.mayDefaultToSmooth("crt-easymode", false, false))

    @Test fun ownRewindBlocksTheDefault() = assertFalse(DevicePerf.mayDefaultToSmooth("disabled", true, false))

    @Test fun perGameFilterBlocksTheDefault() = assertFalse(DevicePerf.mayDefaultToSmooth("disabled", false, true))
}
