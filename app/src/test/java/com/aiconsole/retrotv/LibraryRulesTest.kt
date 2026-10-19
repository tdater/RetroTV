package com.aiconsole.retrotv

import com.aiconsole.retrotv.player.GameRequest
import com.aiconsole.retrotv.player.LibraryRules
import com.aiconsole.retrotv.player.LibrarySort
import com.aiconsole.retrotv.player.RecentGame
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryRulesTest {

    private fun game(title: String, core: String, at: Long, ms: Long = 0, fav: Boolean = false) =
        RecentGame(GameRequest(core = core, game = "https://x/$title.zip", title = title), playedAt = at, playMs = ms, favorite = fav)

    private val contra = game("Contra", "nes", at = 300, ms = 60_000)
    private val sonic = game("sonic", "segaMD", at = 100, ms = 900_000)
    private val zelda = game("Zelda", "fceumm", at = 200, ms = 10_000, fav = true)
    private val games = listOf(contra, sonic, zelda)

    private fun titles(list: List<RecentGame>) = list.map { it.request.displayTitle }

    @Test
    fun favouritesFirstThenTheChosenOrder() {
        assertEquals(listOf("Zelda", "Contra", "sonic"), titles(LibraryRules.sort(games, LibrarySort.Recent)))
        assertEquals(listOf("Zelda", "Contra", "sonic"), titles(LibraryRules.sort(games, LibrarySort.Name)))
        assertEquals(listOf("Zelda", "sonic", "Contra"), titles(LibraryRules.sort(games, LibrarySort.MostPlayed)))
    }

    @Test
    fun filtersBySystemAndText() {
        assertEquals(listOf("Contra", "Zelda"), titles(LibraryRules.filter(games, "nes", "")))
        assertEquals(listOf("sonic"), titles(LibraryRules.filter(games, null, "SON")))
        assertEquals(games, LibraryRules.filter(games, null, " "))
        assertEquals(listOf("nes", "segaMD"), LibraryRules.systems(games))
    }

    @Test
    fun playTimeReadsNaturally() {
        assertEquals("", LibraryRules.playTime(0))
        assertEquals("1 min", LibraryRules.playTime(90_000))
        assertEquals("1 h 30 min", LibraryRules.playTime(5_400_000))
    }
}
