package com.aiconsole.retrotv.link

import java.net.URLDecoder
import java.security.MessageDigest

data class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

/** A WebSocket frame read from the phone: [length] is how many bytes it took. */
class WsFrame(val opcode: Int, val payload: ByteArray, val length: Int)

/** The small HTTP and WebSocket pieces [LinkServer] needs (pure, unit-tested). */
object LinkProtocol {

    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** Request line plus header lines (without the blank line); null when the request line is malformed. */
    fun parseRequestHead(lines: List<String>): HttpRequest? {
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) return null
        val target = parts[1]
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { kv ->
            decode(kv.substringBefore('=')) to decode(kv.substringAfter('=', ""))
        }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return HttpRequest(parts[0].uppercase(), target.substringBefore('?'), query, headers)
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    fun wsAccept(key: String): String = base64(MessageDigest.getInstance("SHA-1").digest((key.trim() + WS_GUID).toByteArray()))

    fun wsTextFrame(text: String): ByteArray {
        val payload = text.toByteArray()
        val head = when {
            payload.size < 126 -> byteArrayOf(0x81.toByte(), payload.size.toByte())
            payload.size <= 0xFFFF -> byteArrayOf(0x81.toByte(), 126, (payload.size shr 8).toByte(), payload.size.toByte())
            else -> throw IllegalArgumentException("Frame too large")
        }
        return head + payload
    }

    /** Decodes one (masked) client frame from the start of [bytes]; null until it has fully arrived. */
    fun wsDecode(bytes: ByteArray): WsFrame? {
        if (bytes.size < 2) return null
        val opcode = bytes[0].toInt() and 0x0F
        val masked = bytes[1].toInt() and 0x80 != 0
        var len = (bytes[1].toInt() and 0x7F).toLong()
        var pos = 2
        if (len == 126L) {
            if (bytes.size < 4) return null
            len = ((bytes[2].toInt() and 0xFF) shl 8 or (bytes[3].toInt() and 0xFF)).toLong()
            pos = 4
        } else if (len == 127L) {
            if (bytes.size < 10) return null
            len = 0
            for (i in 2 until 10) len = len shl 8 or (bytes[i].toLong() and 0xFF)
            pos = 10
        }
        if (len > 1_000_000) return null
        val mask = if (masked) {
            if (bytes.size < pos + 4) return null
            bytes.copyOfRange(pos, pos + 4).also { pos += 4 }
        } else null
        val end = pos + len.toInt()
        if (bytes.size < end) return null
        val payload = bytes.copyOfRange(pos, end)
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return WsFrame(opcode, payload, end)
    }

    /** The token as the first path segment (phone), or the sync code header (another TV). */
    fun authorized(req: HttpRequest, token: String, code: String): Boolean =
        req.path.split('/').getOrNull(1) == token || (code.isNotEmpty() && req.headers["x-retrotv-code"] == code)

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Base64 (java.util.Base64 needs API 26; RetroTV supports 24). */
    fun base64(data: ByteArray): String = buildString {
        for (i in data.indices step 3) {
            val b = (data[i].toInt() and 0xFF shl 16) or
                ((if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0) shl 8) or
                (if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0)
            append(B64[b shr 18 and 63]).append(B64[b shr 12 and 63])
            append(if (i + 1 < data.size) B64[b shr 6 and 63] else '=')
            append(if (i + 2 < data.size) B64[b and 63] else '=')
        }
    }
}
