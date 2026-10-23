package com.aiconsole.retrotv.player

import java.util.Locale

/** Text for the game download on the loading screen (pure, unit-tested). */
object ProgressText {

    const val LABEL = "Fetching game…"

    /** 0..1 when the total is known, else null (an indeterminate bar). */
    fun fraction(done: Long, total: Long): Float? =
        if (total > 0) (done.coerceAtLeast(0).toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null

    /** "Fetching game… 40%", or the size so far when the total is unknown, or just the label at zero. */
    fun format(done: Long, total: Long): String {
        if (total > 0) {
            val pct = (done.coerceAtLeast(0) * 100 / total).coerceIn(0, 100)
            return "$LABEL $pct%"
        }
        return if (done <= 0) LABEL else "$LABEL ${size(done)}"
    }

    /** 800 KB, 2.5 MB, 120 MB. */
    fun size(bytes: Long): String {
        val kb = 1024L
        val mb = kb * 1024
        return when {
            bytes < mb -> "${(bytes / kb).coerceAtLeast(1)} KB"
            bytes < 10 * mb -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / mb)
            else -> "${bytes / mb} MB"
        }
    }
}
