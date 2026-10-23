package com.aiconsole.retrotv.player

import android.app.ActivityManager
import android.content.Context

/** Decides whether this TV box is slow enough for Smooth mode to start on (pure, unit-tested). */
object DevicePerf {
    private const val MAX_CORES = 4
    private const val MAX_MEM_MB = 4096L

    /** Slow only with few cores AND little memory (or the system's low-RAM flag); unknown memory (0) doesn't count. */
    fun isLowEnd(cores: Int, totalMemMb: Long, lowRam: Boolean): Boolean =
        lowRam || (cores <= MAX_CORES && totalMemMb in 1..MAX_MEM_MB)

    /**
     * Whether the low-end default may switch Smooth mode on by itself: not when the owner already chose a
     * display filter or rewind (Smooth mode would silently strip them). [anyGameFilter] = some game has its own filter.
     */
    fun mayDefaultToSmooth(filter: String, rewind: Boolean, anyGameFilter: Boolean): Boolean =
        !rewind && !anyGameFilter && (filter.isBlank() || filter == "disabled")

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

    /** The rate the screen can show: the console's [target] capped by the display's refresh rate ([displayHz] <= 0 = unknown). */
    fun effectiveTarget(target: Double?, displayHz: Float): Double? =
        if (target == null) null else if (displayHz > 0f) minOf(target, displayHz.toDouble()) else target

    /**
     * True once the last [WINDOW] emulated-fps [samples] are all under 90% of [target]. Negative samples mean
     * "unknown". When the screen itself ([screenFps], measured, negative = unknown) can't reach 90% of the
     * target the TV is the limit, not the emulator, so no prompt.
     */
    fun shouldPrompt(samples: List<Double>, target: Double?, screenFps: Double = -1.0): Boolean {
        if (target == null || target <= 0 || samples.size < WINDOW) return false
        if (screenFps >= 0 && screenFps < target * SLOW) return false
        return samples.takeLast(WINDOW).all { it >= 0 && it < target * SLOW }
    }
}
