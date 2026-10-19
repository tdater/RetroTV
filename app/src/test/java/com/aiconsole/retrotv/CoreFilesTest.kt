package com.aiconsole.retrotv

import com.aiconsole.retrotv.player.CoreFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreFilesTest {

    @Test
    fun systemIdsUseTheirDefaultCore() {
        val nes = CoreFiles.forCore("nes")
        assertTrue(nes.toString(), "cores/fceumm-wasm.data" in nes)
        assertTrue("cores/fceumm-legacy-wasm.data" in nes)
        assertTrue("cores/reports/fceumm.json" in nes)
        assertTrue("loader.js" in nes)
        assertTrue("emulator.min.js" in nes)
        assertEquals(nes, CoreFiles.forCore("fceumm"))
    }

    @Test
    fun threadedCoresOnlyNeedTheirThreadBuild() {
        val psp = CoreFiles.forCore("psp")
        assertTrue(psp.toString(), "cores/ppsspp-thread-wasm.data" in psp)
        assertFalse("cores/ppsspp-wasm.data" in psp)
    }

    @Test
    fun listsAreCleanRelativePaths() {
        for (core in listOf("nes", "segaMD", "psx", "psp", "dos", "mgba")) {
            val files = CoreFiles.forCore(core)
            assertEquals(core, files.size, files.toSet().size)
            assertTrue(core, files.none { it.contains("..") || it.startsWith("/") })
        }
    }

    @Test
    fun legacyBuildsAreOptional() {
        assertTrue(CoreFiles.optional("cores/fceumm-legacy-wasm.data"))
        assertFalse(CoreFiles.optional("cores/fceumm-wasm.data"))
        assertFalse(CoreFiles.optional("loader.js"))
    }
}
