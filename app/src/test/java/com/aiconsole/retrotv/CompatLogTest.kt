package com.aiconsole.retrotv

import com.aiconsole.retrotv.browser.FixLadder
import com.aiconsole.retrotv.data.CompatRules
import com.aiconsole.retrotv.data.CompatRules.Entry
import com.aiconsole.retrotv.data.CompatRules.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatLogTest {

    private fun e(fix: String, at: Long, worked: Boolean, host: String = "a.com") = Entry(host, fix, at, worked)

    @Test
    fun nothingWorkedGivesNoBadge() {
        assertNull(CompatRules.best(emptyList(), "a.com"))
        assertNull(CompatRules.best(listOf(e("view:desktop", 1, false)), "a.com"))
        assertEquals(Confidence.None, CompatRules.confidence(listOf(e("view:desktop", 1, false)), "a.com"))
    }

    @Test
    fun badgeNamesTheKeptFix() {
        assertEquals("Works: desktop view", CompatRules.best(listOf(e("view:desktop", 1, true)), "a.com"))
        assertEquals("Works: site player", CompatRules.best(listOf(e("player:site", 1, true)), "a.com"))
        assertEquals("Needs: ad block off", CompatRules.best(listOf(e("adblock:off", 1, true)), "a.com"))
    }

    @Test
    fun otherHostsAreIgnored() {
        val list = listOf(e("view:desktop", 1, true, "b.com"))
        assertNull(CompatRules.best(list, "a.com"))
        assertEquals("Works: desktop view", CompatRules.best(list, "b.com"))
    }

    @Test
    fun laterFailureOfSameGroupRemovesIt() {
        val list = listOf(e("view:desktop", 1, true), e("view:tv", 2, false))
        assertNull(CompatRules.best(list, "a.com"))
    }

    @Test
    fun combinationKeepsNewestFirst() {
        val list = listOf(e("view:desktop", 1, true), e("player:site", 2, true))
        assertEquals("Works: site player + desktop view", CompatRules.best(list, "a.com"))
    }

    @Test
    fun confidenceGrowsWithRepeats() {
        val one = listOf(e("view:desktop", 1, true))
        assertEquals(Confidence.Likely, CompatRules.confidence(one, "a.com"))
        assertEquals(Confidence.Confirmed, CompatRules.confidence(one + e("view:desktop", 2, true), "a.com"))
    }

    @Test
    fun perHostCapKeepsNewest() {
        var list = emptyList<Entry>()
        for (i in 1..30) list = CompatRules.add(list, e("reload", i.toLong(), false))
        assertEquals(CompatRules.MAX_PER_HOST, list.size)
        assertEquals(11L, list.first().at)
        assertEquals(30L, list.last().at)
    }

    @Test
    fun hostCapDropsLeastRecentHost() {
        var list = emptyList<Entry>()
        for (i in 1..105) list = CompatRules.add(list, e("reload", i.toLong(), true, "h$i.com"))
        assertEquals(CompatRules.MAX_HOSTS, list.map { it.host }.distinct().size)
        assertTrue(list.none { it.host == "h1.com" })
        assertTrue(list.any { it.host == "h105.com" })
    }

    @Test
    fun clearRemovesOnlyThatHost() {
        val list = listOf(e("reload", 1, true), e("reload", 2, true, "b.com"))
        assertEquals(listOf("b.com"), CompatRules.clear(list, "a.com").map { it.host })
    }

    @Test
    fun serializeRoundTrips() {
        val list = listOf(e("view:desktop", 5, true), e("player:site", 9, false, "b.com"))
        assertEquals(list, CompatRules.parse(CompatRules.serialize(list)))
        assertEquals(emptyList<Entry>(), CompatRules.parse(CompatRules.serialize(emptyList())))
        assertEquals(emptyList<Entry>(), CompatRules.parse(null))
    }

    @Test
    fun parseSkipsDamagedLines() {
        val text = "a.com\tview:desktop\t5\t1\ngarbage\na.com\tx\tnotanumber\t1\na.com\ty\t3\t7\n"
        assertEquals(listOf(e("view:desktop", 5, true)), CompatRules.parse(text))
    }

    @Test
    fun separatorsInFieldsAreSanitised() {
        val back = CompatRules.parse(CompatRules.serialize(listOf(e("a\tb\nc", 1, true))))
        assertEquals(listOf(e("a b c", 1, true)), back)
    }

    @Test
    fun fixLadderCompatIds() {
        val s = FixLadder.State(desktop = true, adBlockOn = true, builtInPlayer = true, controller = FixLadder.AUTO)
        assertEquals("view:tv", FixLadder.compatId(FixLadder.Step.ToggleView, s))
        assertEquals("view:desktop", FixLadder.compatId(FixLadder.Step.ToggleView, s.copy(desktop = false)))
        assertEquals("player:site", FixLadder.compatId(FixLadder.Step.TogglePlayer, s))
        assertEquals("player:retrotv", FixLadder.compatId(FixLadder.Step.TogglePlayer, s.copy(builtInPlayer = false)))
        assertEquals("adblock:off", FixLadder.compatId(FixLadder.Step.AdBlockOff, s))
        assertNull(FixLadder.compatId(FixLadder.Step.Report, s))
    }
}
