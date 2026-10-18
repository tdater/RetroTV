package com.aiconsole.retrotv.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** The on-screen pointer. Draws only; input is handled by [CursorController]. */
class CursorView(context: Context) : View(context) {

    var px = 0f
        private set
    var py = 0f
        private set
    var sizeLevel = 2
    var accent = 0xFF9C6BFF.toInt()
    var isDown = false
        set(value) { field = value; invalidate() }
    var shown = true
        set(value) { field = value; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arrow = Path()

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun moveTo(x: Float, y: Float) {
        px = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        py = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0) moveTo(w / 2f, h / 2f) else moveTo(px, py)
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        val d = resources.displayMetrics.density
        val s = (10 + sizeLevel * 5) * d
        arrow.reset()
        arrow.moveTo(px, py)
        arrow.lineTo(px, py + s * 1.45f)
        arrow.lineTo(px + s * 0.38f, py + s * 1.08f)
        arrow.lineTo(px + s * 0.66f, py + s * 1.65f)
        arrow.lineTo(px + s * 0.88f, py + s * 1.55f)
        arrow.lineTo(px + s * 0.6f, py + s * 0.98f)
        arrow.lineTo(px + s * 1.08f, py + s * 0.98f)
        arrow.close()
        fill.color = if (isDown) accent else 0xFFFFFFFF.toInt()
        canvas.drawPath(arrow, fill)
        stroke.color = 0xFF000000.toInt()
        stroke.strokeWidth = 1.6f * d
        canvas.drawPath(arrow, stroke)
        // Accent halo so the pointer is visible on any page.
        stroke.color = accent
        stroke.alpha = if (isDown) 220 else 120
        stroke.strokeWidth = 2.2f * d
        canvas.drawCircle(px, py, s * 0.45f, stroke)
        stroke.alpha = 255
    }
}

/**
 * Moves the [CursorView] with the remote's D-pad (accelerating while held) or a stick, scrolls
 * the page when the pointer pushes against a screen edge, and turns OK into taps and drags on
 * whatever is under the pointer (web page or browser UI).
 */
class CursorController(
    private val view: CursorView,
    private val target: () -> ViewGroup,
    private val speedLevel: () -> Int,
    private val onEdgeScroll: (x: Float, y: Float, dx: Float, dy: Float) -> Unit,
    private val onHover: (x: Float, y: Float) -> Unit
) : Choreographer.FrameCallback {

    private val held = LinkedHashSet<Int>()
    private var heldSince = 0L
    private var stickX = 0f
    private var stickY = 0f
    private var running = false
    private var lastFrame = 0L

    private var pressing = false
    private var dragging = false
    private var pressDownTime = 0L
    private var pressX = 0f
    private var pressY = 0f
    private var scrollAccumX = 0f
    private var scrollAccumY = 0f
    private var lastHover = 0L

    val isPressing: Boolean get() = pressing
    val isDragging: Boolean get() = dragging

    fun press(direction: Int) {
        if (held.isEmpty()) heldSince = SystemClock.uptimeMillis()
        held += direction
        view.shown = true
        start()
    }

    fun release(direction: Int) {
        held -= direction
    }

    fun releaseAll() {
        held.clear()
        stickX = 0f
        stickY = 0f
        if (pressing) cancelPress()
    }

    /** Analog stick, -1..1 per axis. */
    fun stick(x: Float, y: Float) {
        stickX = if (abs(x) < 0.15f) 0f else x
        stickY = if (abs(y) < 0.15f) 0f else y
        if (stickX != 0f || stickY != 0f) {
            view.shown = true
            start()
        }
    }

    private fun start() {
        if (running) return
        running = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        val dtFrames = if (lastFrame == 0L) 1f else ((frameTimeNanos - lastFrame) / 16_666_667f).coerceIn(0.5f, 3f)
        lastFrame = frameTimeNanos
        val d = view.resources.displayMetrics.density
        val base = (3f + speedLevel().coerceIn(1, 5) * 2.2f) * d
        var dx = 0f
        var dy = 0f
        if (held.isNotEmpty()) {
            val t = (SystemClock.uptimeMillis() - heldSince) / 1000f
            val accel = min(3f, 0.45f + t * 2.6f)
            val step = base * accel * dtFrames
            if (DPAD_LEFT in held) dx -= step
            if (DPAD_RIGHT in held) dx += step
            if (DPAD_UP in held) dy -= step
            if (DPAD_DOWN in held) dy += step
        }
        if (stickX != 0f || stickY != 0f) {
            val mag = hypot(stickX, stickY).coerceAtMost(1f)
            val curve = mag * mag * 2.6f
            dx += stickX / mag * curve * base * dtFrames
            dy += stickY / mag * curve * base * dtFrames
        }
        if (dx == 0f && dy == 0f) {
            running = false
            return
        }
        val nx = view.px + dx
        val ny = view.py + dy
        view.moveTo(nx, ny)
        // Pushing past an edge scrolls instead.
        val overX = nx - view.px
        val overY = ny - view.py
        if (abs(overX) > 0.5f || abs(overY) > 0.5f) {
            scrollAccumX += overX * 2.2f
            scrollAccumY += overY * 2.2f
            if (abs(scrollAccumX) >= 6f || abs(scrollAccumY) >= 6f) {
                onEdgeScroll(view.px, view.py, scrollAccumX, scrollAccumY)
                scrollAccumX = 0f
                scrollAccumY = 0f
            }
        }
        if (pressing) {
            if (!dragging && hypot(view.px - pressX, view.py - pressY) > 6f * d) {
                dragging = true
                touch(MotionEvent.ACTION_DOWN, pressX, pressY, pressDownTime)
            }
            if (dragging) touch(MotionEvent.ACTION_MOVE, view.px, view.py, pressDownTime)
        } else {
            val now = SystemClock.uptimeMillis()
            if (now - lastHover > 50) {
                lastHover = now
                onHover(view.px, view.py)
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** OK held: remember where; a tap is sent on release, a drag starts if the pointer moves. */
    fun pressDown() {
        if (pressing) return
        pressing = true
        dragging = false
        pressDownTime = SystemClock.uptimeMillis()
        pressX = view.px
        pressY = view.py
        view.isDown = true
    }

    fun pressUp() {
        if (!pressing) return
        pressing = false
        view.isDown = false
        if (dragging) {
            touch(MotionEvent.ACTION_UP, view.px, view.py, pressDownTime)
        } else {
            tap(view.px, view.py)
        }
        dragging = false
    }

    /** Abandons a press (e.g. it turned into a long-press menu). */
    fun cancelPress() {
        if (dragging) touch(MotionEvent.ACTION_CANCEL, view.px, view.py, pressDownTime)
        pressing = false
        dragging = false
        view.isDown = false
    }

    fun tap(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x, y, down)
        view.postDelayed({ touch(MotionEvent.ACTION_UP, x, y, down) }, 40)
    }

    private fun touch(action: Int, x: Float, y: Float, downTime: Long) {
        val ev = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        target().dispatchTouchEvent(ev)
        ev.recycle()
    }

    companion object {
        const val DPAD_UP = 19
        const val DPAD_DOWN = 20
        const val DPAD_LEFT = 21
        const val DPAD_RIGHT = 22

        /** A mouse hover event at (x, y) in [view]'s coordinates, so pages show hover menus. */
        fun hoverEvent(x: Float, y: Float): MotionEvent {
            val now = SystemClock.uptimeMillis()
            val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
            val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 0f; size = 1f })
            return MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0)
        }
    }
}
