package com.aiconsole.retrotv

import com.aiconsole.retrotv.downloads.OpenAction
import com.aiconsole.retrotv.downloads.OpenPolicy
import com.aiconsole.retrotv.player.DevicePerf
import com.aiconsole.retrotv.player.StutterRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFixesTest {

    // ---- DevicePerf ----

    @Test fun quadCoreSmallBoxIsLowEnd() = assertTrue(DevicePerf.isLowEnd(4, 3000, false))

    @Test fun lowRamFlagIsLowEnd() = assertTrue(DevicePerf.isLowEnd(8, 8000, true))

    @Test fun fourGigabytesIsLowEnd() = assertTrue(DevicePerf.isLowEnd(8, 4096, false))

    @Test fun eightCoresAndPlentyOfMemoryIsNot() = assertFalse(DevicePerf.isLowEnd(8, 8192, false))

    @Test fun unknownMemoryDoesNotCount() = assertFalse(DevicePerf.isLowEnd(8, 0, false))

    // ---- StutterRules ----

    private val slow = List(8) { 40.0 }

    @Test fun eightSlowSamplesPrompt() = assertTrue(StutterRules.shouldPrompt(slow, 60.0))

    @Test fun sevenSamplesAreNotEnough() = assertFalse(StutterRules.shouldPrompt(slow.take(7), 60.0))

    @Test fun oneGoodSampleResetsTheWindow() {
        assertFalse(StutterRules.shouldPrompt(List(7) { 40.0 } + 58.0, 60.0))
    }

    @Test fun ninetyPercentIsFine() = assertFalse(StutterRules.shouldPrompt(List(8) { 54.0 }, 60.0))

    @Test fun justUnderNinetyPercentPrompts() = assertTrue(StutterRules.shouldPrompt(List(8) { 53.9 }, 60.0))

    @Test fun onlyTheLastWindowCounts() {
        assertTrue(StutterRules.shouldPrompt(listOf(60.0, 60.0) + slow, 60.0))
        assertFalse(StutterRules.shouldPrompt(slow + listOf(60.0), 60.0))
    }

    @Test fun unknownTargetSkips() = assertFalse(StutterRules.shouldPrompt(slow, null))

    @Test fun unknownSamplesDoNotPrompt() = assertFalse(StutterRules.shouldPrompt(List(8) { -1.0 }, 60.0))

    @Test fun targets() {
        assertEquals(60.0, StutterRules.targetFps("nes")!!, 0.0)
        assertEquals(60.0, StutterRules.targetFps("segaMD")!!, 0.0)
        assertNull(StutterRules.targetFps("psx"))
        assertNull(StutterRules.targetFps(null))
    }

    // ---- OpenPolicy ----

    @Test fun romsPlayInRetroTV() {
        assertEquals(OpenAction.PlayInRetroTV, OpenPolicy.decide("Super Mario.nes", null))
        assertEquals(OpenAction.PlayInRetroTV, OpenPolicy.decide("sonic.md", "application/octet-stream"))
        assertEquals(OpenAction.PlayInRetroTV, OpenPolicy.decide("zelda.SFC", null))
    }

    @Test fun archivesAndDiscImagesPlayInRetroTV() {
        assertEquals(OpenAction.PlayInRetroTV, OpenPolicy.decide("game.zip", "application/zip"))
        assertEquals(OpenAction.PlayInRetroTV, OpenPolicy.decide("game.7z", null))
        assertEquals(OpenAction.PlayInRetroTV, OpenPolicy.decide("disc.chd", null))
    }

    @Test fun apksInstall() {
        assertEquals(OpenAction.InstallApk, OpenPolicy.decide("retroarch.apk", null))
        assertEquals(OpenAction.InstallApk, OpenPolicy.decide("download", "application/vnd.android.package-archive"))
    }

    @Test fun otherFilesOpenWith() {
        assertEquals(OpenAction.OpenWith, OpenPolicy.decide("manual.pdf", "application/pdf"))
        assertEquals(OpenAction.OpenWith, OpenPolicy.decide("cover.png", null))
        assertEquals(OpenAction.OpenWith, OpenPolicy.decide("noextension", null))
    }
}
