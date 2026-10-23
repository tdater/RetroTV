package com.aiconsole.retrotv

import com.aiconsole.retrotv.data.Health
import com.aiconsole.retrotv.ui.HealthChips
import com.aiconsole.retrotv.ui.HeroRules
import com.aiconsole.retrotv.ui.HomeUiRules
import com.aiconsole.retrotv.ui.PlayStats
import com.aiconsole.retrotv.ui.RandomPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RandomPickTest {

    @Test
    fun emptyGivesNull() {
        assertNull(RandomPick.choose(emptyList<String>(), null, { it }, Random(1)))
    }

    @Test
    fun neverRepeatsTheLastPick() {
        val items = listOf("a", "b", "c")
        val random = Random(42)
        var last: String? = null
        repeat(200) {
            val next = RandomPick.choose(items, last, { it }, random)!!
            assertNotEquals(last, next)
            last = next
        }
    }

    @Test
    fun singleChoiceIsAllowedToRepeat() {
        assertEquals("a", RandomPick.choose(listOf("a"), "a", { it }, Random(3)))
        assertEquals("a", RandomPick.choose(listOf("a", "a"), "a", { it }, Random(3)))
    }

    @Test
    fun sameSeedSamePicks() {
        val items = (1..20).map { "g$it" }
        val a = (1..10).map { RandomPick.choose(items, null, { it }, Random(7)) }
        val b = (1..10).map { RandomPick.choose(items, null, { it }, Random(7)) }
        assertEquals(a, b)
    }

    @Test
    fun everyItemCanBePicked() {
        val items = listOf("a", "b", "c", "d")
        val random = Random(5)
        val seen = (1..200).map { RandomPick.choose(items, null, { it }, random) }.toSet()
        assertEquals(items.toSet(), seen)
    }

    @Test
    fun favouritesAreListedTwice() {
        val w = RandomPick.weighted(listOf("a", "b", "c")) { it == "b" }
        assertEquals(listOf("a", "b", "c", "b"), w)
        assertEquals(emptyList<String>(), RandomPick.weighted(emptyList<String>()) { true })
    }
}

class PlayStatsTest {

    @Test
    fun durations() {
        assertEquals("0 min", PlayStats.duration(0))
        assertEquals("0 min", PlayStats.duration(-5))
        assertEquals("0 min", PlayStats.duration(59_999))
        assertEquals("1 min", PlayStats.duration(60_000))
        assertEquals("59 min", PlayStats.duration(59 * 60_000L))
        assertEquals("1 h", PlayStats.duration(60 * 60_000L))
        assertEquals("2 h 5 min", PlayStats.duration(125 * 60_000L))
    }

    @Test
    fun plurals() {
        assertEquals("game", PlayStats.plural(1, "game"))
        assertEquals("games", PlayStats.plural(0, "game"))
        assertEquals("games", PlayStats.plural(2, "game"))
        assertEquals("people", PlayStats.plural(3, "person", "people"))
    }

    @Test
    fun strip() {
        val s = PlayStats.strip(90 * 60_000L, 1, 0)
        assertEquals(listOf("1 h 30 min", "1", "0"), s.map { it.value })
        assertEquals(listOf("total play time", "game played", "favorites"), s.map { it.label })
        assertEquals("1 favorite", PlayStats.strip(0, 5, 1)[2].let { "${it.value} ${it.label}" })
        assertEquals("games played", PlayStats.strip(0, 5, 1)[1].label)
    }
}

class HeroRulesTest {
    private val min = 60_000L

    @Test
    fun agoWording() {
        val now = 1_000_000_000L
        assertEquals("just now", HeroRules.ago(now, now))
        assertEquals("just now", HeroRules.ago(now, now + 5 * min)) // clock skew
        assertEquals("5 min ago", HeroRules.ago(now, now - 5 * min))
        assertEquals("3 h ago", HeroRules.ago(now, now - 180 * min))
        assertEquals("yesterday", HeroRules.ago(now, now - 30 * 60 * min))
        assertEquals("4 days ago", HeroRules.ago(now, now - 4 * 24 * 60 * min))
    }

    @Test
    fun details() {
        val now = 1_000_000_000L
        assertEquals(
            "SNES · played 3× · 2 h 5 min · 4 days ago",
            HeroRules.details("SNES", 3, 125 * min, now, now - 4 * 24 * 60 * min)
        )
        assertEquals("NES · played 1× · just now", HeroRules.details("NES", 1, 10_000, now, now))
    }
}

class HealthChipsTest {

    @Test
    fun everyKnownStateHasIconAndWord() {
        assertEquals("✓ Online", HealthChips.chip(Health.Up)?.text)
        assertEquals("◔ Slow", HealthChips.chip(Health.Slow)?.text)
        assertEquals("✕ Down?", HealthChips.chip(Health.Down)?.text)
        assertEquals(HealthChips.Tone.Good, HealthChips.chip(Health.Up)?.tone)
        assertEquals(HealthChips.Tone.Warn, HealthChips.chip(Health.Slow)?.tone)
        assertEquals(HealthChips.Tone.Bad, HealthChips.chip(Health.Down)?.tone)
    }

    @Test
    fun unknownShowsNothing() {
        assertNull(HealthChips.chip(Health.Unknown))
    }

    @Test
    fun boldFocusZoomsMore() {
        assertTrue(HomeUiRules.focusScale(true) > HomeUiRules.focusScale(false))
        assertTrue(HomeUiRules.focusScale(false) > 1f)
    }
}
