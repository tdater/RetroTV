package com.aiconsole.retrotv.player

import android.app.ActivityManager
import android.content.Context

/** Decides whether this TV box is slow enough for Smooth mode to start on (pure, unit-tested). */
object DevicePerf {
    private const val MAX_CORES = 4
    private const val MAX_MEM_MB = 4096L

    fun isLowEnd(cores: Int, totalMemMb: Long, lowRam: Boolean): Boolean =
        lowRam || cores <= MAX_CORES || (totalMemMb in 1..MAX_MEM_MB)

    /** Reads this device's cores and memory; false when it can't be read. */
    fun detect(context: Context): Boolean = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        isLowEnd(Runtime.getRuntime().availableProcessors(), info.totalMem / 1_048_576, am.isLowRamDevice)
    }.getOrDefault(false)
}

/** Spots a game that runs slower than the console (pure, unit-tested). */
object StutterRules {
    /** Seconds of one-per-second samples that must all be slow. */
    const val WINDOW = 8
    private const val SLOW = 0.9

    private val SIXTY = setOf("nes", "snes", "segaMD", "segaMS", "segaGG", "gb", "gba", "pce")

    /** The console's frames per second, or null when unsure (PAL or unlisted systems are skipped). */
    fun targetFps(systemId: String?): Double? = if (systemId in SIXTY) 60.0 else null

    /** True once the last [WINDOW] emulated-fps [samples] are all under 90% of [target]. Negative samples mean "unknown". */
    fun shouldPrompt(samples: List<Double>, target: Double?): Boolean {
        if (target == null || target <= 0 || samples.size < WINDOW) return false
        return samples.takeLast(WINDOW).all { it >= 0 && it < target * SLOW }
    }
}
