package com.aiconsole.retrotv

import com.aiconsole.retrotv.player.SaveNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveRulesTest {

    @Test
    fun folderNamesAreStableHashes() {
        val a = SaveNames.dirName("nes|https://x/a.zip")
        assertTrue(a, a.matches(Regex("[0-9a-f]{24}")))
        assertEquals(a, SaveNames.dirName("nes|https://x/a.zip"))
        assertNotEquals(a, SaveNames.dirName("nes|https://x/b.zip"))
    }

    @Test
    fun onlyStateFilesOfRealSlotsCount() {
        assertEquals(1, SaveNames.slotOf("slot1.state"))
        assertEquals(4, SaveNames.slotOf("slot4.state"))
        assertNull(SaveNames.slotOf("slot4.png"))
        assertNull(SaveNames.slotOf("slot9.state"))
        assertNull(SaveNames.slotOf("slot0.state"))
        assertNull(SaveNames.slotOf("../slot1.state"))
    }

    @Test
    fun limits() {
        assertEquals(4, SaveNames.SLOTS)
        assertEquals(64L * 1024 * 1024, SaveNames.MAX_STATE)
    }
}
