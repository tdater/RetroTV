package com.aiconsole.retrotv.browser

import android.view.InputDevice
import android.view.KeyEvent
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import com.aiconsole.retrotv.web.Gamepad
import java.util.Locale
import kotlin.math.abs

/**
 * Up to four virtual "standard" gamepads fed from Android controller events and read by pages
 * through [POLYFILL] (`navigator.getGamepads()`). The JS bridge is visible in every frame, so
 * emulators inside iframes see the pads too, whatever the device's WebView supports natively.
 */
class VirtualPads {

    private class Pad(val name: String, val remote: Boolean = false) {
        val buttons = FloatArray(17)
        val axes = FloatArray(4)
        var timestamp = 0L
    }

    private val lock = Any()
    private val pads = arrayOfNulls<Pad>(4)
    private val slots = HashMap<Int, Int>() // deviceId -> slot
    private val hat = HashMap<Int, Pair<Float, Float>>()

    /** When a page last read the pads (any frame). */
    @Volatile
    private var lastPollAt = 0L

    /** True while the page is reading gamepads, i.e. it's a gamepad-aware emulator. */
    val pagePolls: Boolean get() = lastPollAt != 0L && SystemClock.uptimeMillis() - lastPollAt < 2500

    private fun pad(device: InputDevice?, remote: Boolean = false): Pad? {
        val id = device?.id ?: return null
        synchronized(lock) {
            slots[id]?.let { return pads[it] }
            // A real controller takes over from the remote standing in as player 1.
            if (!remote) slots.entries.firstOrNull { pads[it.value]?.remote == true }?.let { e ->
                pads[e.value] = null
                slots.remove(e.key)
            }
            val slot = (0 until 4).firstOrNull { s -> pads[s] == null } ?: return null
            slots[id] = slot
            pads[slot] = Pad(if (remote) "TV remote" else device.name ?: "Controller", remote)
            return pads[slot]
        }
    }

    private fun hasController(): Boolean = synchronized(lock) { pads.any { it != null && !it.remote } }

    /**
     * The TV remote as player 1 while no controller is connected (built-in player): D-pad, OK = A,
     * Play/Pause = Start, Rewind = B, Fast-forward = X. Returns false for other keys.
     */
    fun onRemoteKey(event: KeyEvent): Boolean {
        val i = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 12
            KeyEvent.KEYCODE_DPAD_DOWN -> 13
            KeyEvent.KEYCODE_DPAD_LEFT -> 14
            KeyEvent.KEYCODE_DPAD_RIGHT -> 15
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> 9
            KeyEvent.KEYCODE_MEDIA_REWIND -> 1
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 2
            else -> return false
        }
        if (hasController()) return false
        val p = pad(event.device, remote = true) ?: return false
        synchronized(lock) {
            p.buttons[i] = if (event.action == KeyEvent.ACTION_DOWN) 1f else 0f
            p.timestamp = System.nanoTime()
        }
        return true
    }

    /** Standard-mapping button index for an Android key code, or -1. */
    private fun index(keyCode: Int): Int = when (keyCode) {
        Gamepad.BUTTON_A -> 0
        Gamepad.BUTTON_B -> 1
        Gamepad.BUTTON_X -> 2
        Gamepad.BUTTON_Y -> 3
        Gamepad.BUTTON_L1 -> 4
        Gamepad.BUTTON_R1 -> 5
        Gamepad.BUTTON_L2 -> 6
        Gamepad.BUTTON_R2 -> 7
        Gamepad.BUTTON_SELECT -> 8
        Gamepad.BUTTON_START -> 9
        Gamepad.BUTTON_THUMBL -> 10
        Gamepad.BUTTON_THUMBR -> 11
        Gamepad.DPAD_UP -> 12
        Gamepad.DPAD_DOWN -> 13
        Gamepad.DPAD_LEFT -> 14
        Gamepad.DPAD_RIGHT -> 15
        Gamepad.BUTTON_MODE -> 16
        else -> -1
    }

    /** Records a controller key. Returns false when it isn't a gamepad button. */
    fun onKey(event: KeyEvent): Boolean {
        val i = index(event.keyCode)
        if (i < 0) return false
        val p = pad(event.device) ?: return false
        synchronized(lock) {
            p.buttons[i] = if (event.action == KeyEvent.ACTION_DOWN) 1f else 0f
            p.timestamp = System.nanoTime()
        }
        return true
    }

    /** Records sticks, triggers and the hat switch from a joystick motion event. */
    fun onMotion(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_MOVE) return false
        val p = pad(event.device) ?: return false
        val device = event.device
        fun axis(a: Int): Float {
            val v = event.getAxisValue(a)
            val flat = device?.getMotionRange(a, event.source)?.flat ?: 0f
            return if (abs(v) <= maxOf(flat, 0.08f)) 0f else v
        }
        synchronized(lock) {
            p.axes[0] = axis(MotionEvent.AXIS_X)
            p.axes[1] = axis(MotionEvent.AXIS_Y)
            p.axes[2] = axis(MotionEvent.AXIS_Z)
            p.axes[3] = axis(MotionEvent.AXIS_RZ)
            val lt = maxOf(axis(MotionEvent.AXIS_LTRIGGER), axis(MotionEvent.AXIS_BRAKE))
            val rt = maxOf(axis(MotionEvent.AXIS_RTRIGGER), axis(MotionEvent.AXIS_GAS))
            if (lt > 0f || p.buttons[6] in 0.01f..0.99f) p.buttons[6] = lt
            if (rt > 0f || p.buttons[7] in 0.01f..0.99f) p.buttons[7] = rt
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val id = device?.id ?: 0
            val last = hat[id]
            if (last == null || last.first != hx || last.second != hy) {
                p.buttons[12] = if (hy < -0.5f) 1f else 0f
                p.buttons[13] = if (hy > 0.5f) 1f else 0f
                p.buttons[14] = if (hx < -0.5f) 1f else 0f
                p.buttons[15] = if (hx > 0.5f) 1f else 0f
                hat[id] = hx to hy
            }
            p.timestamp = System.nanoTime()
        }
        return true
    }

    fun releaseAll() = synchronized(lock) {
        pads.forEach { p -> p?.buttons?.fill(0f); p?.axes?.fill(0f) }
    }

    fun forget(deviceId: Int) = synchronized(lock) {
        slots.remove(deviceId)?.let { pads[it] = null }
        hat.remove(deviceId)
    }

    val connectedCount: Int get() = synchronized(lock) { pads.count { it != null && !it.remote } }

    /** JS bridge object, registered as `RetroTVPad`. */
    inner class Bridge {
        /** `slot~name~ts~b0,b1,..~a0,a1,a2,a3` for each pad, joined by `|`. */
        @JavascriptInterface
        fun state(): String {
            lastPollAt = SystemClock.uptimeMillis()
            return peek()
        }

        /** Same as [state] without counting as the page reading pads (connect/disconnect events). */
        @JavascriptInterface
        fun peek(): String = synchronized(lock) {
            buildString {
                pads.forEachIndexed { slot, p ->
                    if (p == null) return@forEachIndexed
                    if (isNotEmpty()) append('|')
                    append(slot).append('~').append(p.name.replace(Regex("[~|]"), " ")).append('~')
                    append(p.timestamp / 1_000_000).append('~')
                    p.buttons.joinTo(this, ",") { if (it == 0f) "0" else if (it == 1f) "1" else String.format(Locale.US, "%.3f", it) }
                    append('~')
                    p.axes.joinTo(this, ",") { if (it == 0f) "0" else String.format(Locale.US, "%.3f", it) }
                }
            }
        }
    }

    companion object {
        fun isController(event: KeyEvent): Boolean {
            val src = event.source
            return Gamepad.isGamepadButton(event.keyCode) ||
                (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        }

        fun isJoystick(event: MotionEvent): Boolean =
            (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

        /**
         * Replaces `navigator.getGamepads()` with the virtual pads and fires `gamepadconnected`
         * / `gamepaddisconnected`. Installed at document start in every frame.
         */
        const val POLYFILL = """
(function () {
  if (window.__retrotvPad || typeof RetroTVPad === 'undefined') return;
  window.__retrotvPad = true;
  var cache = null, cacheAt = 0, known = {};
  function makePad(slot, name, ts, b, a) {
    var buttons = b.map(function (v) { var n = +v; return { pressed: n > 0.5, touched: n > 0, value: n }; });
    return {
      id: name + ' (STANDARD GAMEPAD Vendor: 045e Product: 028e)', index: slot, connected: true,
      mapping: 'standard', timestamp: ts, buttons: buttons, axes: a.map(Number),
      vibrationActuator: null, hapticActuators: []
    };
  }
  function read(peek) {
    var now = performance.now();
    if (cache && now - cacheAt < 4) return cache;
    var out = [null, null, null, null], s = '';
    try { s = peek ? RetroTVPad.peek() : RetroTVPad.state(); } catch (e) {}
    if (s) s.split('|').forEach(function (p) {
      var f = p.split('~'); var slot = +f[0];
      out[slot] = makePad(slot, f[1], +f[2], f[3].split(','), f[4].split(','));
    });
    cache = out; cacheAt = now; return out;
  }
  try {
    Object.defineProperty(Navigator.prototype, 'getGamepads', { value: function () { return read(); }, configurable: true, writable: true });
  } catch (e) { navigator.getGamepads = function () { return read(); }; }
  function fire(type, pad) {
    var ev; try { ev = new Event(type); } catch (e) { ev = document.createEvent('Event'); ev.initEvent(type, false, false); }
    try { Object.defineProperty(ev, 'gamepad', { value: pad }); } catch (e) { ev.gamepad = pad; }
    window.dispatchEvent(ev);
  }
  setInterval(function () {
    var pads = read(true);
    for (var i = 0; i < 4; i++) {
      if (pads[i] && !known[i]) { known[i] = true; fire('gamepadconnected', pads[i]); }
      else if (!pads[i] && known[i]) { known[i] = false; fire('gamepaddisconnected', { index: i, connected: false }); }
    }
  }, 500);
})();
"""
    }
}
