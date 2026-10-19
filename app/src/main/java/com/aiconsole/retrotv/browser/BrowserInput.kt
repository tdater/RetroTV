package com.aiconsole.retrotv.browser

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.aiconsole.retrotv.web.Gamepad

// Remote and controller helpers: key classes, controller browsing, stick-to-keys and pointer scrolling.

internal fun BrowserActivity.isMenuKey(code: Int) = code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_INFO ||
    code == KeyEvent.KEYCODE_GUIDE || code == KeyEvent.KEYCODE_SETTINGS

internal fun BrowserActivity.isConfirm(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

internal fun BrowserActivity.isArrow(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
    code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

/** "Pad moves pointer": the controller drives the browser instead of the game. */
internal fun BrowserActivity.controllerBrowse(event: KeyEvent): Boolean {
    val code = event.keyCode
    val down = event.action == KeyEvent.ACTION_DOWN
    when (code) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
            if (down) cursor.press(code) else cursor.release(code)
        KeyEvent.KEYCODE_BUTTON_A -> if (down) {
            if (event.repeatCount == 0) cursor.pressDown()
        } else cursor.pressUp()
        KeyEvent.KEYCODE_BUTTON_B -> if (!down) handleBack()
        KeyEvent.KEYCODE_BUTTON_Y -> if (!down) openMenu()
        KeyEvent.KEYCODE_BUTTON_X -> if (!down) toggleControllerBrowse()
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> if (down) pageScroll(-1)
        KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> if (down) pageScroll(1)
    }
    return true
}

/** Keyboard mode: hat switch and left stick become the mapped D-pad keys. */
internal fun BrowserActivity.stickToKeys(ev: MotionEvent) {
    val hx = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
    val hy = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
    val now = Gamepad.stickDirections(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y)) +
        Gamepad.stickDirections(hx, hy, 0.5f)
    val mapping = settings.value.mapping
    val t = SystemClock.uptimeMillis()
    (now - stickKeys).forEach { dir -> mapping[dir]?.takeIf { it != 0 }?.let { dispatchToPage(keyboardEvent(t, KeyEvent.ACTION_DOWN, it)) } }
    (stickKeys - now).forEach { dir -> mapping[dir]?.takeIf { it != 0 }?.let { dispatchToPage(keyboardEvent(t, KeyEvent.ACTION_UP, it)) } }
    stickKeys = now
}

internal fun BrowserActivity.keyboardEvent(time: Long, action: Int, code: Int) =
    KeyEvent(time, time, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

internal fun BrowserActivity.sendKey(src: KeyEvent, code: Int) {
    dispatchToPage(
        KeyEvent(src.downTime, src.eventTime, src.action, code, src.repeatCount, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
    )
}

internal fun BrowserActivity.dispatchToPage(event: KeyEvent) {
    (customView ?: webView)?.dispatchKeyEvent(event)
}

internal fun BrowserActivity.releaseStickKeys() {
    if (stickKeys.isEmpty()) return
    val t = SystemClock.uptimeMillis()
    val mapping = settings.value.mapping
    stickKeys.forEach { dir -> mapping[dir]?.takeIf { it != 0 }?.let { dispatchToPage(keyboardEvent(t, KeyEvent.ACTION_UP, it)) } }
    stickKeys = emptySet()
}

internal fun BrowserActivity.releaseKeys() {
    releaseStickKeys()
    val t = SystemClock.uptimeMillis()
    val mapping = settings.value.mapping
    autoKeys.forEach { code -> mapping[code]?.takeIf { it != 0 }?.let { dispatchToPage(keyboardEvent(t, KeyEvent.ACTION_UP, it)) } }
    autoKeys.clear()
    pads.releaseAll()
    cursor.releaseAll()
}

@Suppress("DEPRECATION")
internal fun BrowserActivity.cssScale(): Float = webView?.scale?.takeIf { it > 0f } ?: resources.displayMetrics.density

internal fun BrowserActivity.edgeScroll(x: Float, y: Float, dx: Float, dy: Float) {
    if (customView != null) return
    val wv = webView ?: return
    val s = cssScale()
    val lx = (x - webFrame.left).coerceIn(0f, wv.width - 1f)
    val ly = (y - webFrame.top).coerceIn(0f, wv.height - 1f)
    wv.evaluateJavascript(PageScripts.scrollAt(lx / s, ly / s, dx / s, dy / s), null)
}

internal fun BrowserActivity.scrollBy(dx: Float, dy: Float) = edgeScroll(cursorView.px, cursorView.py, dx, dy)

internal fun BrowserActivity.pageScroll(direction: Int) {
    val wv = webView ?: return
    edgeScroll(cursorView.px, cursorView.py, 0f, direction * wv.height * 0.8f)
}

internal fun BrowserActivity.hover(x: Float, y: Float) {
    if (customView != null) return
    val wv = webView ?: return
    val lx = x - webFrame.left
    val ly = y - webFrame.top
    if (lx < 0 || ly < 0 || lx > wv.width || ly > wv.height) return
    val ev = CursorController.hoverEvent(lx, ly)
    wv.dispatchGenericMotionEvent(ev)
    ev.recycle()
}
