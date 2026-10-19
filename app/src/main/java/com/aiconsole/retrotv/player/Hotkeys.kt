package com.aiconsole.retrotv.player

import android.view.KeyEvent

enum class HotkeyAction { QuickSave, QuickLoad, RewindOn, RewindOff, FastForward }

/**
 * Player hotkeys (pure, unit-tested): with Select held, L1 saves, R1 loads, L2 rewinds while held,
 * R2 toggles fast-forward. The remote's Rewind key rewinds while held. Anything else is game input.
 */
object Hotkeys {

    fun action(selectHeld: Boolean, keyCode: Int, down: Boolean): HotkeyAction? {
        if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) return if (down) HotkeyAction.RewindOn else HotkeyAction.RewindOff
        if (!selectHeld) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1 -> HotkeyAction.QuickSave.takeIf { down }
            KeyEvent.KEYCODE_BUTTON_R1 -> HotkeyAction.QuickLoad.takeIf { down }
            KeyEvent.KEYCODE_BUTTON_L2 -> if (down) HotkeyAction.RewindOn else HotkeyAction.RewindOff
            KeyEvent.KEYCODE_BUTTON_R2 -> HotkeyAction.FastForward.takeIf { down }
            else -> null
        }
    }
}
