package com.aiconsole.retrotv

import com.aiconsole.retrotv.player.RetryPlan
import com.aiconsole.retrotv.player.RetryStep
import com.aiconsole.retrotv.player.RomProblem
import com.aiconsole.retrotv.player.RomRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRobustnessTest {

    private fun text(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    /** Bytes that are neither text nor an archive signature, like a raw ROM. */
    private fun binary(size: Int) = ByteArray(size) { (it * 7 + 1).toByte() }

    private fun bytes(vararg head: Int, total: Int = 200): ByteArray {
        val out = ByteArray(total) { (it % 200 + 1).toByte() }
        head.forEachIndexed { i, b -> out[i] = b.toByte() }
        return out
    }

    private val challengePage = text("<!DOCTYPE html><html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>")

    // ---- RomRules ----

    @Test
    fun htmlContentTypeIsAWebPage() {
        assertEquals(RomProblem.WebPage, RomRules.check("text/html; charset=utf-8", challengePage.size.toLong(), challengePage))
    }

    @Test
    fun htmlBodyIsAWebPageEvenWithABinaryContentType() {
        assertEquals(RomProblem.WebPage, RomRules.check("application/octet-stream", -1, challengePage))
        val padded = text("  \n\t<html lang=en><body>Download limit reached for your IP address, come back tomorrow</body></html>")
        assertEquals(RomProblem.WebPage, RomRules.check(null, padded.size.toLong(), padded))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text("<!doctype html><title>Please log in to download this game file</title>")
        assertEquals(RomProblem.WebPage, RomRules.check("application/octet-stream", bom.size.toLong(), bom))
    }

    @Test
    fun errorJsonIsRefused() {
        val json = text("""{"error":"download limit reached for this address, please try again later ok"}""")
        assertEquals(RomProblem.ErrorData, RomRules.check("application/octet-stream", json.size.toLong(), json))
        assertEquals(RomProblem.ErrorData, RomRules.check("application/json", -1, binary(300)))
        assertEquals(RomProblem.ErrorData, RomRules.check("application/problem+json", -1, binary(300)))
        assertTrue(RomRules.looksLikeJson(text("{ \"a\": 1 }")))
        assertFalse(RomRules.looksLikeJson(text("{\u0001\u0002 binary")))
    }

    @Test
    fun emptyAndTinyFilesAreRefused() {
        assertEquals(RomProblem.TooSmall, RomRules.check("application/octet-stream", 0, ByteArray(0)))
        assertEquals(RomProblem.TooSmall, RomRules.check(null, -1, ByteArray(0)))
        assertEquals(RomProblem.TooSmall, RomRules.check("application/octet-stream", 10, binary(10)))
        assertEquals(RomProblem.TooSmall, RomRules.check(null, -1, binary(20)))
        assertEquals(RomProblem.TooSmall, RomRules.check(null, RomRules.MIN_BYTES - 1L, binary(RomRules.MIN_BYTES - 1)))
        assertNull(RomRules.check(null, RomRules.MIN_BYTES.toLong(), binary(RomRules.MIN_BYTES)))
    }

    @Test
    fun smallPatchesAreFineWhenNotStrict() {
        assertNull(RomRules.check("application/octet-stream", 10, binary(10), strict = false))
        assertEquals(RomProblem.TooSmall, RomRules.check("application/octet-stream", 0, ByteArray(0), strict = false))
        assertEquals(RomProblem.WebPage, RomRules.check(null, 10, text("<html></html>"), strict = false))
    }

    @Test
    fun archivesAndRomsAreAccepted() {
        assertNull(RomRules.check("application/zip", 200, bytes(0x50, 0x4B, 0x03, 0x04)))
        assertNull(RomRules.check("application/octet-stream", 200, bytes(0x50, 0x4B, 0x03, 0x04)))
        assertNull(RomRules.check("application/x-7z-compressed", 200, bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)))
        assertNull(RomRules.check("application/vnd.rar", 200, bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)))
        assertNull(RomRules.check("application/octet-stream", 1024, bytes('N'.code, 'E'.code, 'S'.code, 0x1A, total = 512)))
        assertNull(RomRules.check("binary/octet-stream", -1, binary(512)))
        assertNull(RomRules.check(null, 5_000_000, binary(512)))
    }

    @Test
    fun archiveSignatureBeatsAWrongContentType() {
        assertNull(RomRules.check("text/html", 200, bytes(0x50, 0x4B, 0x03, 0x04)))
        assertNull(RomRules.check("text/plain; charset=utf-8", 200, bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)))
        // ...but a tiny zip is still too small for a game.
        assertEquals(RomProblem.TooSmall, RomRules.check("application/zip", 10, bytes(0x50, 0x4B, 0x03, 0x04, total = 10)))
    }

    @Test
    fun plainTextIsAnErrorPageButBinaryLabelledPlainTextIsARom() {
        val message = text("Please wait while we verify that you are human. This takes a few seconds, thank you.")
        assertEquals(RomProblem.WebPage, RomRules.check("text/plain", message.size.toLong(), message))
        assertNull(RomRules.check("text/plain", 512, bytes(0x4E, 0x45, 0x53, 0x1A, 0x00, 0x02, total = 512)))
        assertFalse(RomRules.looksLikeText(ByteArray(0)))
    }

    @Test
    fun everyProblemHasAMessage() {
        RomProblem.entries.forEach { assertTrue(it.message.isNotBlank()) }
        assertNotNull(RomProblem.WebPage.message)
    }

    // ---- RetryPlan ----

    @Test
    fun firstStartIsAsRequested() {
        assertEquals(RetryStep.AsIs, RetryPlan.next(1, threaded = false, crossOriginIsolated = true))
        assertEquals(RetryStep.AsIs, RetryPlan.next(1, threaded = true, crossOriginIsolated = false))
        assertEquals(RetryStep.AsIs, RetryPlan.next(0, threaded = true, crossOriginIsolated = true))
    }

    @Test
    fun plainCoresTryLegacyThenTheSitePlayer() {
        assertEquals(RetryStep.Legacy, RetryPlan.next(2, threaded = false, crossOriginIsolated = true))
        assertEquals(RetryStep.Site, RetryPlan.next(3, threaded = false, crossOriginIsolated = true))
        assertEquals(RetryStep.Site, RetryPlan.next(9, threaded = false, crossOriginIsolated = true))
    }

    @Test
    fun threadedCoresWithoutAnotherBuildEndAtTheSitePlayer() {
        assertEquals(RetryStep.Legacy, RetryPlan.next(2, threaded = true, crossOriginIsolated = true))
        assertEquals(RetryStep.Site, RetryPlan.next(3, threaded = true, crossOriginIsolated = true))
    }

    @Test
    fun threadedCoreInAWebViewWithoutIsolationGivesUpAtOnce() {
        assertEquals(RetryStep.Site, RetryPlan.next(2, threaded = true, crossOriginIsolated = false))
    }

    @Test
    fun threadedCoreWithANonThreadBuildDropsThreads() {
        assertEquals(RetryStep.Legacy, RetryPlan.next(2, threaded = true, crossOriginIsolated = true, hasNonThreadBuild = true))
        assertEquals(RetryStep.NoThreads, RetryPlan.next(3, threaded = true, crossOriginIsolated = true, hasNonThreadBuild = true))
        assertEquals(RetryStep.Site, RetryPlan.next(4, threaded = true, crossOriginIsolated = true, hasNonThreadBuild = true))
        // Not isolated: threads never worked, so the single-thread build is the next try.
        assertEquals(RetryStep.NoThreads, RetryPlan.next(2, threaded = true, crossOriginIsolated = false, hasNonThreadBuild = true))
        assertEquals(RetryStep.Site, RetryPlan.next(3, threaded = true, crossOriginIsolated = false, hasNonThreadBuild = true))
    }

    @Test
    fun noCoreCurrentlyShipsANonThreadBuildOfAThreadedCore() {
        assertFalse(com.aiconsole.retrotv.player.CoreFiles.hasNonThreadBuild("psp"))
        assertFalse(com.aiconsole.retrotv.player.CoreFiles.hasNonThreadBuild("nes"))
    }
}
