package com.aiconsole.retrotv

import com.aiconsole.retrotv.data.Health
import com.aiconsole.retrotv.data.SiteHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteHealthTest {

    @Test
    fun answersAreUpUnlessSlow() {
        assertEquals(Health.Up, SiteHealth.classify(200, 300))
        assertEquals(Health.Slow, SiteHealth.classify(200, 5_000))
        assertEquals(Health.Up, SiteHealth.classify(301, 100))
    }

    @Test
    fun cloudflareChallengesCountAsUp() {
        assertEquals(Health.Up, SiteHealth.classify(403, 100))
        assertEquals(Health.Up, SiteHealth.classify(429, 100))
        assertEquals(Health.Up, SiteHealth.classify(503, 100))
    }

    @Test
    fun errorsAndSilenceAreDown() {
        assertEquals(Health.Down, SiteHealth.classify(404, 100))
        assertEquals(Health.Down, SiteHealth.classify(500, 100))
        assertEquals(Health.Down, SiteHealth.classify(null, 8_000))
    }

    @Test
    fun checksRunAtMostEverySixHours() {
        assertTrue(SiteHealth.due(0, SiteHealth.INTERVAL_MS))
        assertFalse(SiteHealth.due(1, 2))
    }
}
