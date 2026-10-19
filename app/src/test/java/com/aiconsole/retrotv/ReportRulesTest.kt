package com.aiconsole.retrotv

import com.aiconsole.retrotv.browser.RendererRules
import com.aiconsole.retrotv.data.FreezeRules
import com.aiconsole.retrotv.data.ProblemReport
import com.aiconsole.retrotv.data.ReportShareRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRulesTest {

    @Test
    fun reportsDropQueriesTokensAndCookies() {
        assertEquals("https://a.com/x", ProblemReport.redact("https://a.com/x?token=abc#f"))
        assertEquals("open https://a.com/x now", ProblemReport.redact("open https://a.com/x?id=1 now"))
        assertFalse(ProblemReport.redact("cookie: sid=1").contains("sid=1"))
        assertFalse(ProblemReport.redact("Cookie=sid=1; other=2").contains("sid=1"))
        assertFalse(ProblemReport.redact("key=SECRET123").contains("SECRET123"))
        assertEquals("plain text stays", ProblemReport.redact("plain text stays"))
    }

    @Test
    fun hungPagesAreStoppedOnlyWhenReallyStuck() {
        assertFalse(RendererRules.shouldTerminate(unresponsiveForMs = 5_000, launcherStalledMs = 500))
        assertFalse(RendererRules.shouldTerminate(unresponsiveForMs = 15_000, launcherStalledMs = 2_000))
        assertTrue(RendererRules.shouldTerminate(unresponsiveForMs = 20_000, launcherStalledMs = 0))
        assertTrue(RendererRules.shouldTerminate(unresponsiveForMs = 5_000, launcherStalledMs = 3_000))
    }

    @Test
    fun freezeIsReportedOncePerFreeze() {
        assertFalse(FreezeRules.frozen(lastBeatMs = 10_000, nowMs = 13_999, thresholdMs = 4_000))
        assertTrue(FreezeRules.frozen(lastBeatMs = 10_000, nowMs = 14_000, thresholdMs = 4_000))
        assertTrue(FreezeRules.shouldReport(frozen = true, reportedThisFreeze = false))
        assertFalse(FreezeRules.shouldReport(frozen = true, reportedThisFreeze = true))
    }

    @Test
    fun qrLinkUsesTheWifiAddress() {
        assertEquals("192.168.1.20", ReportShareRules.pickAddress(listOf("rmnet0" to "10.0.0.5", "wlan0" to "192.168.1.20")))
        assertNull(ReportShareRules.pickAddress(listOf("rmnet0" to "100.64.1.1")))
        assertEquals("/abc/x.txt", ReportShareRules.requestPath("GET /abc/x.txt HTTP/1.1"))
        assertNull(ReportShareRules.requestPath("POST / HTTP/1.1"))
    }
}
