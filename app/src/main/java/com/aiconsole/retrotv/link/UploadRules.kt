package com.aiconsole.retrotv.link

import com.aiconsole.retrotv.player.Systems

/** What the phone may send to the TV's game folder (pure, unit-tested). */
object UploadRules {

    const val MAX_BYTES = 700_000_000L

    /** Null when the file is acceptable, else the reason shown on the phone. */
    fun check(name: String, size: Long): String? = when {
        size <= 0 -> "Empty file"
        !Systems.isRom(safeName(name)) -> "Not a game file"
        size > MAX_BYTES -> "Too large (max 700 MB)"
        else -> null
    }

    /** The file name alone: no folders, no `..`, only safe characters (as GameFiles.safeName). */
    fun safeName(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\').replace("..", "")
            .replace(Regex("[^A-Za-z0-9._()\\- ]"), "_").trim().take(120).ifEmpty { "game" }
}
