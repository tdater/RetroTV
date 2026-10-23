package com.aiconsole.retrotv.player

import java.security.MessageDigest

/** How save slots are named on disk (pure, unit-tested): `saves/<dirName(key)>/slot<N>.state|.png`. */
object SaveNames {
    const val SLOTS = 4
    const val MAX_STATE = 64L * 1024 * 1024

    private val STATE = Regex("slot([1-$SLOTS])\\.state")
    private val SERVED = Regex("(slot[1-$SLOTS]|auto)\\.(state|png)")

    /** Hidden extra slot for auto-resume (`auto.state|.png`): never shown as one of the [SLOTS], so manual saves are safe. */
    const val AUTO = 0

    /** File stem of a slot: `slot<N>`, or `auto` for [AUTO]. */
    fun stem(slot: Int): String = if (slot == AUTO) "auto" else "slot$slot"

    /** True for the auto-resume state file. */
    fun isAutoState(fileName: String) = fileName == "auto.state"
    private val DIR = Regex("[0-9a-f]{24}")

    /** One folder per game: the first 24 hex digits of the SHA-1 of its [GameRequest.key]. */
    fun dirName(gameKey: String): String =
        MessageDigest.getInstance("SHA-1").digest(gameKey.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

    /** The slot a state file holds, or null for any other name. */
    fun slotOf(fileName: String): Int? = STATE.matchEntire(fileName)?.groupValues?.get(1)?.toInt()

    fun isDir(name: String) = DIR.matches(name)

    fun isServed(fileName: String) = SERVED.matches(fileName)
}
