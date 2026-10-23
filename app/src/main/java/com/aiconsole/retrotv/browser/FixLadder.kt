package com.aiconsole.retrotv.browser

/**
 * "Game not working? Fix it": the remedies tried in order, one per tap, for the current site.
 * Pure Kotlin so the sequencing is unit-tested; [BrowserActivity] applies the chosen step.
 */
object FixLadder {

    enum class Step(val label: String, val persists: Boolean) {
        Reload("Reload the page", false),
        TogglePlayer("Switch game player", true),
        ToggleView("Switch desktop / TV view", true),
        AdBlockOff("Turn ad blocking off for this site", true),
        KeyboardKeys("Controller: keyboard keys", true),
        GamepadApi("Controller: gamepad API", true),
        BothControllers("Controller: gamepad + keyboard", true),
        Report("Report a problem", false)
    }

    /** Controller mode names as in `ControllerMode` (kept as text so this stays Android-free). */
    const val AUTO = "Auto"
    const val NATIVE = "Native"
    const val KEYBOARD = "Keyboard"
    const val BOTH = "Both"

    data class State(
        val desktop: Boolean,
        val adBlockOn: Boolean,
        val builtInPlayer: Boolean,
        val controller: String
    )

    /** A step that would change nothing right now is skipped. */
    fun applicable(step: Step, state: State): Boolean = when (step) {
        Step.AdBlockOff -> state.adBlockOn
        Step.KeyboardKeys -> state.controller != KEYBOARD
        Step.GamepadApi -> state.controller != NATIVE
        Step.BothControllers -> state.controller != BOTH
        else -> true
    }

    /**
     * The compatibility-log id of what [step] changes when applied in [current] (see `CompatRules`),
     * or null for steps that say nothing about the site (report a problem).
     */
    fun compatId(step: Step, current: State): String? = when (step) {
        Step.Reload -> "reload"
        Step.TogglePlayer -> if (current.builtInPlayer) "player:site" else "player:retrotv"
        Step.ToggleView -> if (current.desktop) "view:tv" else "view:desktop"
        Step.AdBlockOff -> "adblock:off"
        Step.KeyboardKeys -> "controller:keyboard"
        Step.GamepadApi -> "controller:gamepad"
        Step.BothControllers -> "controller:both"
        Step.Report -> null
    }

    /** The next remedy to try, or null when everything has been tried. */
    fun next(tried: Set<Step>, state: State): Step? =
        Step.entries.firstOrNull { it !in tried && applicable(it, state) }

    /** "Try [first] of [second]": position of the next step among the steps tried plus those left. */
    fun progress(tried: Set<Step>, state: State): Pair<Int, Int> {
        val left = Step.entries.count { it !in tried && applicable(it, state) }
        return (tried.size + 1) to (tried.size + left)
    }

    /** Text for the menu button: the step that a tap would apply. */
    fun hint(tried: Set<Step>, state: State): String {
        val step = next(tried, state) ?: return "All fixes tried · tap to reset"
        val (n, total) = progress(tried, state)
        return "Try $n of $total: ${step.label}"
    }
}
