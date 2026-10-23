package com.aiconsole.retrotv.player

/** The "how do I control this" text for the player (pure, unit-tested); mirrors PlayerActivity and [Hotkeys]. */
object ControlsHelp {

    const val TITLE = "Controls"
    const val DISMISS = "Press any button to close"

    fun lines(hasController: Boolean): List<String> = if (hasController) {
        listOf(
            "Select + Start (or the Mode button): game menu (save, load, exit)",
            "Select + L1: quick save · Select + R1: quick load",
            "Hold Select + L2: rewind (if turned on in Settings) · Select + R2: fast-forward",
            "Remote: Back or Menu also opens the game menu"
        )
    } else {
        listOf(
            "D-pad: move · OK: A button",
            "Play/Pause: Start · Rewind: B · Fast-forward: X",
            "Back or Menu: game menu (save, load, exit)",
            "Connect a controller or use the phone link for two or more players"
        )
    }
}
