package com.aiconsole.retrotv

import com.aiconsole.retrotv.player.ArtNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtNamesTest {

    @Test
    fun consolesMapToLibretroFolders() {
        assertEquals("Nintendo - Nintendo Entertainment System", ArtNames.folder("nes"))
        assertEquals("Nintendo - Nintendo Entertainment System", ArtNames.folder("fceumm"))
        assertEquals("Sega - Mega Drive - Genesis", ArtNames.folder("segaMD"))
        assertNull(ArtNames.folder("unknown"))
    }

    @Test
    fun titlesAreNormalised() {
        assertEquals("legend of zelda", ArtNames.normalize("Legend of Zelda, The (USA) [!].nes"))
        assertEquals("ghosts n goblins", ArtNames.normalize("Ghosts 'n Goblins"))
        assertEquals("mario and luigi", ArtNames.normalize("Mario & Luigi"))
        assertEquals("legend of zelda", ArtNames.normalize("The Legend of Zelda"))
    }

    @Test
    fun bestMatchPrefersTheUsRelease() {
        val names = listOf("Super Mario Bros. 3 (Europe)", "Super Mario Bros. 3 (USA)", "Super Mario Bros. (World)")
        assertEquals("Super Mario Bros. 3 (USA)", ArtNames.best("Super Mario Bros 3", names))
        assertEquals("Contra (USA)", ArtNames.best("Contra", listOf("Super Contra (USA)", "Contra (USA)")))
    }

    @Test
    fun noMatchGivesNoArt() {
        assertNull(ArtNames.best("My Homebrew Thing", listOf("Contra (USA)")))
        assertNull(ArtNames.best("", listOf("Contra (USA)")))
    }

    @Test
    fun listingsGiveDecodedNames() {
        assertEquals(listOf("3 Ninjas Kick Back (USA)"), ArtNames.parseListing("""<a href="3%20Ninjas%20Kick%20Back%20(USA).png">x</a>"""))
        assertEquals(listOf("Mario & Luigi (USA)"), ArtNames.parseListing("""<a href="Mario%20%26%20Luigi%20(USA).png">"""))
    }
}
