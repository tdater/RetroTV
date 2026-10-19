package com.aiconsole.retrotv

import com.aiconsole.retrotv.link.LinkProtocol
import com.aiconsole.retrotv.link.PhoneInput
import com.aiconsole.retrotv.link.PhoneMsg
import com.aiconsole.retrotv.link.SaveFileInfo
import com.aiconsole.retrotv.link.SyncRules
import com.aiconsole.retrotv.link.UploadRules
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkProtocolTest {

    @Test
    fun websocketHandshakeKey() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", LinkProtocol.wsAccept("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun requestHeads() {
        val r = LinkProtocol.parseRequestHead(listOf("GET /abc/pad?x=1&n=a%20b HTTP/1.1", "Upgrade: websocket", "Content-Length: 5"))!!
        assertEquals("GET", r.method)
        assertEquals("/abc/pad", r.path)
        assertEquals(mapOf("x" to "1", "n" to "a b"), r.query)
        assertEquals("websocket", r.headers["upgrade"])
        assertEquals("5", r.headers["content-length"])
        assertNull(LinkProtocol.parseRequestHead(listOf("nonsense")))
    }

    @Test
    fun clientFramesUnmask() {
        val mask = byteArrayOf(1, 2, 3, 4)
        val payload = "hi".toByteArray()
        val masked = ByteArray(2) { (payload[it].toInt() xor mask[it].toInt()).toByte() }
        val frame = byteArrayOf(0x81.toByte(), 0x82.toByte()) + mask + masked
        val f = LinkProtocol.wsDecode(frame)!!
        assertEquals(1, f.opcode)
        assertEquals("hi", String(f.payload))
        assertEquals(8, f.length)
        assertNull(LinkProtocol.wsDecode(frame.copyOf(5)))
    }

    @Test
    fun serverFrames() {
        assertArrayEquals(byteArrayOf(0x81.toByte(), 2, 'h'.code.toByte(), 'i'.code.toByte()), LinkProtocol.wsTextFrame("hi"))
        val long = LinkProtocol.wsTextFrame("x".repeat(200))
        assertEquals(126, long[1].toInt())
        assertEquals(204, long.size)
    }

    @Test
    fun onlyTokenOrCodeGetsIn() {
        val withToken = LinkProtocol.parseRequestHead(listOf("GET /TOKEN/x HTTP/1.1"))!!
        val withCode = LinkProtocol.parseRequestHead(listOf("GET /saves/manifest HTTP/1.1", "X-RetroTV-Code: 123456"))!!
        val other = LinkProtocol.parseRequestHead(listOf("GET /other/x HTTP/1.1"))!!
        assertTrue(LinkProtocol.authorized(withToken, "TOKEN", "123456"))
        assertTrue(LinkProtocol.authorized(withCode, "TOKEN", "123456"))
        assertFalse(LinkProtocol.authorized(other, "TOKEN", "123456"))
        assertFalse(LinkProtocol.authorized(withCode, "TOKEN", ""))
    }
}

class PhoneInputTest {
    @Test
    fun messages() {
        assertEquals(PhoneMsg.Button(0, true), PhoneInput.parse("b,0,1"))
        assertEquals(PhoneMsg.Button(16, false), PhoneInput.parse("b,16,0"))
        assertEquals(PhoneMsg.Stick(-0.5f, 1f), PhoneInput.parse("a,-0.5,3"))
        assertEquals(PhoneMsg.Menu, PhoneInput.parse("m"))
        assertEquals(PhoneMsg.Hello("Pixel 8"), PhoneInput.parse("h,Pixel 8"))
        assertEquals(24, (PhoneInput.parse("h," + "n".repeat(40)) as PhoneMsg.Hello).name.length)
        assertNull(PhoneInput.parse("b,17,1"))
        assertNull(PhoneInput.parse("zzz"))
        assertNull(PhoneInput.parse("a,x,y"))
    }
}

class UploadRulesTest {
    @Test
    fun checks() {
        assertNull(UploadRules.check("mario.nes", 40_000))
        assertNull(UploadRules.check("game.zip", 40_000))
        assertEquals("Not a game file", UploadRules.check("x.exe", 10))
        assertEquals("Too large (max 700 MB)", UploadRules.check("big.iso", 800_000_000))
        assertEquals("Empty file", UploadRules.check("a.zip", 0))
        assertEquals("x.sfc", UploadRules.safeName("../../etc/x.sfc"))
        assertEquals("x.sfc", UploadRules.safeName("C:\\games\\x.sfc"))
    }
}

class SyncRulesTest {
    private val dir = "0123456789abcdef01234567"
    private fun f(slot: Int, t: Long, size: Long = 100) = SaveFileInfo("$dir/slot$slot.state", t, size)

    @Test
    fun newerWinsAndMissingFilesCopy() {
        val (pull, push) = SyncRules.plan(
            local = listOf(f(1, 1_000), f(2, 10_000_000), f(3, 5_000)),
            remote = listOf(f(1, 3_600_000), f(2, 1_000), f(4, 1_000), f(3, 6_500))
        )
        assertEquals(listOf("$dir/slot1.state", "$dir/slot4.state"), pull.sorted())
        assertEquals(listOf("$dir/slot2.state"), push)
    }

    @Test
    fun localOnlyIsPushedAndOddPathsIgnored() {
        val (pull, push) = SyncRules.plan(
            local = listOf(f(1, 1), SaveFileInfo("../x", 9, 1), SaveFileInfo("abc/slot9.state", 9, 1), SaveFileInfo("$dir/game.txt", 5, 3)),
            remote = emptyList()
        )
        assertEquals(emptyList<String>(), pull)
        assertEquals(listOf("$dir/game.txt", "$dir/slot1.state"), push.sorted())
    }

    @Test
    fun manifestRoundTrips() {
        val list = listOf(f(1, 123), SaveFileInfo("$dir/slot1.png", 456, 7))
        assertEquals(list, SyncRules.manifest(SyncRules.encode(list)))
        assertEquals(emptyList<SaveFileInfo>(), SyncRules.manifest(""))
    }
}
