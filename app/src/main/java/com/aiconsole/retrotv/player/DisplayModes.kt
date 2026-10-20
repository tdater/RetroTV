package com.aiconsole.retrotv.player

import android.app.Activity
import android.os.Build
import android.view.Display

/** Pure display-mode choice (unit-tested). */
object DisplayRules {

    data class Mode(val id: Int, val width: Int, val height: Int, val hz: Float)

    fun isSixty(hz: Float) = hz in 58.5f..61.5f

    /**
     * EmulatorJS runs one game frame per screen refresh: at 50 Hz games run slow with crackling
     * sound, at 100/120 Hz too fast. A ~60 Hz mode at the same resolution, or null to keep the
     * current one (already ~60 Hz, or the TV has none).
     */
    fun sixtyHz(current: Mode, modes: List<Mode>): Mode? {
        if (isSixty(current.hz)) return null
        return modes.filter { it.width == current.width && it.height == current.height && isSixty(it.hz) }
            .minByOrNull { kotlin.math.abs(it.hz - 60f) }
    }
}

object DisplayModes {

    /** Asks for a ~60 Hz mode while [activity] is in front; returns the refresh rate in use for display. */
    @Suppress("DEPRECATION")
    fun preferSixtyHz(activity: Activity): Float {
        val display: Display = activity.windowManager.defaultDisplay ?: return 0f
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return display.refreshRate
        val cur = display.mode
        fun Display.Mode.rule() = DisplayRules.Mode(modeId, physicalWidth, physicalHeight, refreshRate)
        val best = DisplayRules.sixtyHz(cur.rule(), display.supportedModes.map { it.rule() }) ?: return cur.refreshRate
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = best.id }
        return best.hz
    }
}
