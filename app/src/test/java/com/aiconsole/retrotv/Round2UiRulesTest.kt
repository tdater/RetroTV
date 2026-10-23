package com.aiconsole.retrotv

import com.aiconsole.retrotv.data.BuiltInSites
import com.aiconsole.retrotv.player.CachePolicy
import com.aiconsole.retrotv.player.GameRequest
import com.aiconsole.retrotv.player.RecentGame
import com.aiconsole.retrotv.ui.AttractRules
import com.aiconsole.retrotv.ui.AttractShots
import com.aiconsole.retrotv.ui.CollectionRules
import com.aiconsole.retrotv.ui.GameCollection
import com.aiconsole.retrotv.ui.HomeLayout
import com.aiconsole.retrotv.ui.Slide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionRulesTest {
    @Test
    fun createRejectsBlankDuplicateAndTooMany() {
        val one = CollectionRules.create(emptyList(), "  Couch   co-op ")
        assertEquals(listOf("Couch co-op"), one.map { it.name })
        assertSame(one, CollectionRules.create(one, "   "))
        assertSame(one, CollectionRules.create(one, "couch CO-OP"))
        var many = emptyList<GameCollection>()
        repeat(CollectionRules.MAX_COLLECTIONS + 5) { many = CollectionRules.create(many, "C$it") }
        assertEquals(CollectionRules.MAX_COLLECTIONS, many.size)
        assertEquals(many.size, many.map { it.id }.toSet().size)
    }

    @Test
    fun namesAreCapped() {
        val c = CollectionRules.create(emptyList(), "x".repeat(100))
        assertEquals(CollectionRules.MAX_NAME, c.single().name.length)
    }

    @Test
    fun idsAreNotReusedWhileOthersExist() {
        var l = CollectionRules.create(CollectionRules.create(emptyList(), "A"), "B")
        l = CollectionRules.delete(l, l[0].id)
        l = CollectionRules.create(l, "C")
        assertEquals(2, l.map { it.id }.toSet().size)
    }

    @Test
    fun renameAndDelete() {
        var l = CollectionRules.create(CollectionRules.create(emptyList(), "A"), "B")
        val a = l[0].id
        val b = l[1].id
        l = CollectionRules.rename(l, a, "Kids")
        assertEquals(listOf("Kids", "B"), l.map { it.name })
        assertSame(l, CollectionRules.rename(l, a, "b")) // taken by another collection
        assertSame(l, CollectionRules.rename(l, a, " "))
        assertEquals(listOf("Kidz"), CollectionRules.rename(CollectionRules.delete(l, b), a, "Kidz").map { it.name })
        assertEquals(listOf("Kids"), CollectionRules.delete(l, b).map { it.name })
    }

    @Test
    fun addKeepsOrderIgnoresDuplicatesAndCaps() {
        var l = CollectionRules.create(emptyList(), "A")
        val id = l[0].id
        l = CollectionRules.add(CollectionRules.add(CollectionRules.add(l, id, "z"), id, "a"), id, "z")
        assertEquals(listOf("z", "a"), l[0].keys)
        assertEquals(l, CollectionRules.add(l, id, " "))
        repeat(CollectionRules.MAX_GAMES + 10) { l = CollectionRules.add(l, id, "g$it") }
        assertEquals(CollectionRules.MAX_GAMES, l[0].keys.size)
        assertEquals(l, CollectionRules.add(l, "nope", "q"))
    }

    @Test
    fun removeToggleAndContaining() {
        var l = CollectionRules.create(CollectionRules.create(emptyList(), "A"), "B")
        val a = l[0].id
        val b = l[1].id
        l = CollectionRules.toggle(l, a, "g1")
        l = CollectionRules.toggle(l, b, "g1")
        assertEquals(listOf("A", "B"), CollectionRules.containing(l, "g1").map { it.name })
        l = CollectionRules.toggle(l, a, "g1")
        assertEquals(listOf("B"), CollectionRules.containing(l, "g1").map { it.name })
        assertEquals(emptyList<String>(), CollectionRules.remove(l, b, "g1")[1].keys)
    }

    @Test
    fun resolveFollowsCollectionOrderAndSkipsMissingGames() {
        val c = GameCollection("1", "A", listOf("c", "gone", "a"))
        assertEquals(listOf("c", "a"), CollectionRules.resolve(c, listOf("a", "b", "c")) { it })
        assertEquals(emptyList<GameCollection>(), CollectionRules.nonEmpty(listOf(c), listOf("b")) { it })
        assertEquals(listOf(c), CollectionRules.nonEmpty(listOf(c), listOf("b", "a")) { it })
    }

    @Test
    fun serializeRoundTripsAwkwardText() {
        val l = listOf(
            GameCollection("1", "Couch co-op", listOf("nes|mario bros.", "snes|a\tb", "psx|line\nbreak", "x|back\\slash\\t", "x|cr\r")),
            GameCollection("7", "Kids ★ Я", emptyList())
        )
        assertEquals(l, CollectionRules.parse(CollectionRules.serialize(l)))
        assertEquals(emptyList<GameCollection>(), CollectionRules.parse(CollectionRules.serialize(emptyList())))
    }

    @Test
    fun parseToleratesGarbage() {
        assertEquals(emptyList<GameCollection>(), CollectionRules.parse(null))
        assertEquals(emptyList<GameCollection>(), CollectionRules.parse("junk\n\n\t\n1"))
        val parsed = CollectionRules.parse("1\tA\tk\tk\n1\tDup\n2\tB")
        assertEquals(listOf("A", "B"), parsed.map { it.name })
        assertEquals(listOf("k"), parsed[0].keys)
    }
}

class HomeLayoutTest {
    private val ids get() = HomeLayout.DEFAULT_ORDER

    @Test
    fun defaultShowsEverythingInOrder() {
        assertEquals(ids, HomeLayout.visibleIds(HomeLayout.default()))
        assertEquals(HomeLayout.SITES, ids.last())
    }

    @Test
    fun toggleHidesAndShows() {
        val hidden = HomeLayout.toggle(HomeLayout.default(), HomeLayout.STATS)
        assertTrue(HomeLayout.STATS !in HomeLayout.visibleIds(hidden))
        assertEquals(ids.size, hidden.size)
        assertEquals(ids, HomeLayout.visibleIds(HomeLayout.toggle(hidden, HomeLayout.STATS)))
    }

    @Test
    fun moveUpAndDownStopAtTheEnds() {
        val d = HomeLayout.default()
        assertEquals(d, HomeLayout.moveUp(d, ids.first()))
        assertEquals(d, HomeLayout.moveDown(d, ids.last()))
        assertEquals(d, HomeLayout.moveUp(d, "nope"))
        val up = HomeLayout.moveUp(d, HomeLayout.COLLECTIONS)
        assertEquals(HomeLayout.COLLECTIONS, up[3].id)
        assertEquals(HomeLayout.VISITED, up[4].id)
        val down = HomeLayout.moveDown(up, HomeLayout.COLLECTIONS)
        assertEquals(d, down)
    }

    @Test
    fun serializeRoundTripKeepsOrderAndVisibility() {
        var rows = HomeLayout.moveDown(HomeLayout.default(), HomeLayout.HERO)
        rows = HomeLayout.toggle(rows, HomeLayout.RECENT)
        assertEquals(rows, HomeLayout.parse(HomeLayout.serialize(rows)))
    }

    @Test
    fun parseFixesUnknownDuplicateAndMissingRows() {
        assertEquals(HomeLayout.default(), HomeLayout.parse(null))
        assertEquals(HomeLayout.default(), HomeLayout.parse(""))
        val rows = HomeLayout.parse("sites:1,bogus:1,hero:0,sites:0")
        assertEquals(ids.size, rows.size)
        assertEquals(ids.toSet(), rows.map { it.id }.toSet())
        assertEquals(HomeLayout.SITES, rows.first().id)
        assertTrue(rows.first { it.id == HomeLayout.SITES }.visible)
        assertTrue(!rows.first { it.id == HomeLayout.HERO }.visible)
        // Rows added by an update show up, visible, next to their default neighbour.
        val old = HomeLayout.normalize(listOf(HomeLayout.Row(HomeLayout.SITES), HomeLayout.Row(HomeLayout.HERO)))
        assertEquals(ids.toSet(), old.map { it.id }.toSet())
        assertTrue(old.all { it.visible })
        assertTrue(old.indexOfFirst { it.id == HomeLayout.STATS } == old.indexOfFirst { it.id == HomeLayout.HERO } + 1)
    }
}

class AttractShotsTest {
    private fun game(t: String, ms: Long = 0, fav: Boolean = false) =
        RecentGame(GameRequest(core = "nes", game = "https://x/$t.zip", title = t), playedAt = 1, playMs = ms, favorite = fav)

    @Test
    fun pickRotatesAndHandlesNone() {
        assertNull(AttractShots.pick(null, 0))
        assertNull(AttractShots.pick(emptyList(), 3))
        assertEquals("b", AttractShots.pick(listOf("a", "b"), 1))
        assertEquals("a", AttractShots.pick(listOf("a", "b"), 2))
        assertEquals("b", AttractShots.pick(listOf("a", "b"), -1))
    }

    @Test
    fun screenshotsFollowTheirGameAndMixWithBoxArt() {
        val a = game("A", 10)
        val b = game("B", 5_000)
        val slides = AttractRules.slides(listOf(a, b), BuiltInSites.ALL.take(2), shots = mapOf(a.request.key to listOf("/s/1.png", "/s/2.png")), round = 1)
        assertEquals(3, slides.size)
        assertTrue(slides[0] is Slide.Game && (slides[0] as Slide.Game).game == b)
        assertTrue(slides[1] is Slide.Game && (slides[1] as Slide.Game).game == a)
        assertEquals(Slide.Shot(a, "/s/2.png"), slides[2])
    }

    @Test
    fun maxCountsShotsToo() {
        val a = game("A", 10)
        val b = game("B", 5)
        val shots = mapOf(a.request.key to listOf("/a.png"), b.request.key to listOf("/b.png"))
        assertEquals(3, AttractRules.slides(listOf(a, b), emptyList(), max = 3, shots = shots).size)
    }
}

class CachePolicyTest {
    private fun e(n: String, size: Long, t: Long) = CachePolicy.Entry(n, size, t)

    @Test
    fun nothingEvictedWhenItFits() {
        assertEquals(emptyList<String>(), CachePolicy.evict(emptyList(), 100))
        assertEquals(emptyList<String>(), CachePolicy.evict(listOf(e("a", 60, 1), e("b", 40, 2)), 100))
    }

    @Test
    fun dropsLeastRecentlyUsedFirstUntilItFits() {
        val entries = listOf(e("new", 40, 30), e("old", 40, 10), e("mid", 40, 20))
        assertEquals(listOf("old"), CachePolicy.evict(entries, 80))
        assertEquals(listOf("old", "mid"), CachePolicy.evict(entries, 40))
        assertEquals(listOf("old", "mid", "new"), CachePolicy.evict(entries, 0))
    }

    @Test
    fun tiesBreakByNameAndNegativeCapIsZero() {
        val entries = listOf(e("b", 10, 5), e("a", 10, 5))
        assertEquals(listOf("a"), CachePolicy.evict(entries, 10))
        assertEquals(listOf("a", "b"), CachePolicy.evict(entries, -5))
    }

    @Test
    fun labelsAndChoices() {
        assertEquals("0 MB", CachePolicy.label(0))
        assertEquals("512 KB", CachePolicy.label(512_000))
        assertEquals("38 MB", CachePolicy.label(38_400_000))
        assertEquals(listOf(50, 100, 250, 500), CachePolicy.CAPS_MB)
        assertTrue(CachePolicy.DEFAULT_CAP_MB in CachePolicy.CAPS_MB)
    }
}
