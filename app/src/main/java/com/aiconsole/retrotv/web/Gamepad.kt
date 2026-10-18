package com.aiconsole.retrotv.web

/**
 * Game-controller handling for web emulators. Two modes:
 * - **Native:** controller events go to the WebView untouched, so pages using the HTML5 Gamepad
 *   API (EmulatorJS, webЯcade, Internet Archive's emulators) see a real gamepad.
 * - **Keyboard:** buttons are turned into the keyboard keys browser emulators default to
 *   (arrows, Z/X/A/S, Enter, Shift), for pages that only listen to the keyboard.
 *
 * Android KeyEvent codes are inlined so this stays plain Kotlin and unit-testable.
 */
object Gamepad {

    // android.view.KeyEvent codes.
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val BUTTON_A = 96
    const val BUTTON_B = 97
    const val BUTTON_X = 99
    const val BUTTON_Y = 100
    const val BUTTON_L1 = 102
    const val BUTTON_R1 = 103
    const val BUTTON_L2 = 104
    const val BUTTON_R2 = 105
    const val BUTTON_THUMBL = 106
    const val BUTTON_THUMBR = 107
    const val BUTTON_START = 108
    const val BUTTON_SELECT = 109
    const val BUTTON_MODE = 110

    /** Controller buttons that can be remapped, in the order the settings screen lists them. */
    val BUTTONS: List<Pair<Int, String>> = listOf(
        DPAD_UP to "D-pad up", DPAD_DOWN to "D-pad down", DPAD_LEFT to "D-pad left", DPAD_RIGHT to "D-pad right",
        BUTTON_A to "A", BUTTON_B to "B", BUTTON_X to "X", BUTTON_Y to "Y",
        BUTTON_L1 to "L1", BUTTON_R1 to "R1", BUTTON_L2 to "L2", BUTTON_R2 to "R2",
        BUTTON_THUMBL to "L3", BUTTON_THUMBR to "R3", BUTTON_START to "Start", BUTTON_SELECT to "Select"
    )

    /** Keyboard keys a button can send (android.view.KeyEvent codes). */
    val KEYS: List<Pair<Int, String>> = listOf(
        0 to "None",
        DPAD_UP to "Arrow up", DPAD_DOWN to "Arrow down", DPAD_LEFT to "Arrow left", DPAD_RIGHT to "Arrow right",
        66 to "Enter", 62 to "Space", 60 to "Shift", 113 to "Ctrl", 57 to "Alt", 61 to "Tab", 111 to "Escape", 67 to "Backspace"
    ) + ('A'..'Z').map { (29 + (it - 'A')) to it.toString() } +
        ('0'..'9').map { (7 + (it - '0')) to it.toString() } +
        (1..12).map { (130 + it) to "F$it" }

    fun keyLabel(code: Int): String = KEYS.firstOrNull { it.first == code }?.second ?: "Key $code"
    fun buttonLabel(code: Int): String = BUTTONS.firstOrNull { it.first == code }?.second ?: "Button $code"

    private const val KEY_ENTER = 66
    private const val KEY_SHIFT_RIGHT = 60
    private const val KEY_Z = 54
    private const val KEY_X = 52
    private const val KEY_A = 29
    private const val KEY_S = 47
    private const val KEY_Q = 45
    private const val KEY_W = 51
    private const val KEY_E = 33
    private const val KEY_R = 46

    /** Controller key -> keyboard key. D-pad keys pass through as arrows. */
    val DEFAULT: Map<Int, Int> = mapOf(
        DPAD_UP to DPAD_UP,
        DPAD_DOWN to DPAD_DOWN,
        DPAD_LEFT to DPAD_LEFT,
        DPAD_RIGHT to DPAD_RIGHT,
        BUTTON_A to KEY_X,
        BUTTON_B to KEY_Z,
        BUTTON_X to KEY_S,
        BUTTON_Y to KEY_A,
        BUTTON_L1 to KEY_Q,
        BUTTON_R1 to KEY_W,
        BUTTON_L2 to KEY_E,
        BUTTON_R2 to KEY_R,
        BUTTON_START to KEY_ENTER,
        BUTTON_SELECT to KEY_SHIFT_RIGHT
    )

    fun isGamepadButton(keyCode: Int): Boolean = keyCode in BUTTON_A..BUTTON_MODE

    /** "96:52,97:54" — the stored form of a mapping. */
    fun encode(map: Map<Int, Int>): String = map.entries.joinToString(",") { "${it.key}:${it.value}" }

    fun decode(text: String?): Map<Int, Int> {
        if (text.isNullOrBlank()) return DEFAULT
        val parsed = text.split(',').mapNotNull { pair ->
            val (k, v) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
            val key = k.trim().toIntOrNull() ?: return@mapNotNull null
            val value = v.trim().toIntOrNull() ?: return@mapNotNull null
            key to value
        }.toMap()
        return parsed.ifEmpty { DEFAULT }
    }

    /** Analog stick to D-pad: which directions are held for stick position (x, y in -1..1). */
    fun stickDirections(x: Float, y: Float, deadZone: Float = 0.45f): Set<Int> = buildSet {
        if (x <= -deadZone) add(DPAD_LEFT)
        if (x >= deadZone) add(DPAD_RIGHT)
        if (y <= -deadZone) add(DPAD_UP)
        if (y >= deadZone) add(DPAD_DOWN)
    }
}
