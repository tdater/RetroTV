package com.aiconsole.retrotv.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/** Pure timing rule for [FreezeWatchdog] (unit-tested). */
object FreezeRules {
    /** The main thread counts as frozen once its last heartbeat is [thresholdMs] old. */
    fun frozen(lastBeatMs: Long, nowMs: Long, thresholdMs: Long): Boolean = nowMs - lastBeatMs >= thresholdMs

    /** One report per freeze: a new one only after the thread has run again. */
    fun shouldReport(frozen: Boolean, reportedThisFreeze: Boolean): Boolean = frozen && !reportedThisFreeze
}

/**
 * Records where the main thread was stuck whenever RetroTV freezes for a few seconds, so
 * "Report a problem" shows the cause (Android otherwise just closes the app, leaving nothing
 * behind). A background thread watches a heartbeat the main thread posts every second; nothing
 * runs on the main thread except that post.
 */
object FreezeWatchdog {

    private const val BEAT_MS = 1_000L
    private const val FREEZE_MS = 4_000L

    @Volatile private var lastBeat = SystemClock.uptimeMillis()

    /** What was on screen (set by the browser and player), included in a freeze report. */
    @Volatile var context: String = ""

    @Volatile private var started = false

    /** How long the main thread has gone without a heartbeat (about 0–1 s when it's fine). */
    fun mainStalledMs(): Long = if (started) SystemClock.uptimeMillis() - lastBeat else 0L

    fun start(appContext: Context) {
        if (started) return
        started = true
        val main = Handler(Looper.getMainLooper())
        val beat = object : Runnable {
            override fun run() {
                lastBeat = SystemClock.uptimeMillis()
                main.postDelayed(this, BEAT_MS)
            }
        }
        main.post(beat)
        Thread({
            var reported = false
            while (true) {
                try {
                    Thread.sleep(BEAT_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val frozen = FreezeRules.frozen(lastBeat, SystemClock.uptimeMillis(), FREEZE_MS)
                if (FreezeRules.shouldReport(frozen, reported)) {
                    reported = true
                    val stack = Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "\tat $it" }
                    val stuck = (SystemClock.uptimeMillis() - lastBeat) / 1000
                    Log.e("FreezeWatchdog", "Main thread blocked for $stuck s")
                    ProblemReport.recordFreeze(appContext, "Main thread blocked for $stuck s. $context\n$stack")
                } else if (!frozen) {
                    reported = false
                }
            }
        }, "retrotv-freeze-watchdog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }
}
