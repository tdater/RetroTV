package com.aiconsole.retrotv.player

/** Why a downloaded "game file" is not a game. */
enum class RomProblem(val message: String) {
    WebPage("The site returned a web page instead of the game (a download limit, login or security check). Open the game on the site again, then press Play."),
    ErrorData("The site returned an error message instead of the game. Open the game on the site again, then press Play."),
    TooSmall("The file the site returned is empty or far too small to be a game. Try again later or play on the site.")
}

/**
 * Decides whether a response looks like a real game file. Pure Kotlin so it can be unit tested;
 * a Cloudflare challenge, a "download limit" page or an error JSON must never be cached as the ROM.
 */
object RomRules {
    /** Smallest plausible game (the game file itself; patches and states may be smaller). */
    const val MIN_BYTES = 64

    /** Bytes of the start of a file that [check] needs. */
    const val HEAD_BYTES = 512

    private val HTML_TYPES = setOf("text/html", "application/xhtml+xml")

    /**
     * Null when [head] (the first bytes, at most [HEAD_BYTES]) and the response headers look like a
     * game; otherwise the problem. [length] is the Content-Length or file size, -1 when unknown.
     * [strict] adds the minimum size ([MIN_BYTES]); only the game file needs it.
     */
    fun check(contentType: String?, length: Long, head: ByteArray, strict: Boolean = true): RomProblem? {
        if (length == 0L || head.isEmpty()) return RomProblem.TooSmall
        val tooSmall = strict && ((length >= 0 && length < MIN_BYTES) || (length < 0 && head.size < MIN_BYTES))
        // A known archive signature beats whatever the server claimed.
        if (hasArchiveMagic(head)) return if (tooSmall) RomProblem.TooSmall else null
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type in HTML_TYPES) return RomProblem.WebPage
        if (type == "application/json" || type.endsWith("+json")) return RomProblem.ErrorData
        if (looksLikeWebPage(head)) return RomProblem.WebPage
        if (looksLikeJson(head)) return RomProblem.ErrorData
        // Servers often label ROMs text/plain; only text-looking content is an error page.
        if (type == "text/plain" && looksLikeText(head)) return RomProblem.WebPage
        return if (tooSmall) RomProblem.TooSmall else null
    }

    /** Zip (also .nes-in-zip etc.), 7z, RAR, gzip. */
    fun hasArchiveMagic(head: ByteArray): Boolean {
        fun at(vararg bytes: Int) = head.size >= bytes.size && bytes.indices.all { head[it].toInt() and 0xFF == bytes[it] }
        return at(0x50, 0x4B, 0x03, 0x04) || at(0x50, 0x4B, 0x05, 0x06) ||
            at(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) || at(0x52, 0x61, 0x72, 0x21) || at(0x1F, 0x8B)
    }

    /** `<!doctype`, `<html`... after optional BOM and whitespace. */
    fun looksLikeWebPage(head: ByteArray): Boolean {
        val s = leadingText(head, 24)
        return s.startsWith("<!doctype") || s.startsWith("<html") || s.startsWith("<head") ||
            s.startsWith("<body") || s.startsWith("<?xml") || s.startsWith("<!--")
    }

    /** A JSON object such as `{"error": ...}`. */
    fun looksLikeJson(head: ByteArray): Boolean {
        val s = leadingText(head, 24)
        if (!s.startsWith("{")) return false
        val next = s.drop(1).trimStart()
        return next.startsWith("\"") || next.startsWith("}")
    }

    /** Printable ASCII / whitespace / UTF-8 throughout the head: text, not a binary ROM. */
    fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        return head.all { b ->
            val c = b.toInt() and 0xFF
            c == 9 || c == 10 || c == 13 || c in 32..126 || c >= 0x80 && c != 0xFF
        } && head.none { it.toInt() == 0 }
    }

    private fun leadingText(head: ByteArray, max: Int): String {
        var i = 0
        if (head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) i = 3
        while (i < head.size && (head[i].toInt() == 32 || head[i].toInt() in 9..13)) i++
        val end = minOf(head.size, i + max)
        val sb = StringBuilder()
        for (j in i until end) sb.append((head[j].toInt() and 0xFF).toChar().lowercaseChar())
        return sb.toString()
    }
}
