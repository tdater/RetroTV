package com.aiconsole.retrotv.player

/** Auto-resume rules (pure, unit-tested). */
object AutoResume {
    /** `game_prefs` flag; off by default. */
    const val PREF = "auto_resume"

    /** The most recently saved slot (lowest number on a tie), or null when nothing is saved. */
    fun pickSlot(slotsWithTimestamps: Map<Int, Long>): Int? =
        slotsWithTimestamps.entries
            .filter { it.key in 1..SaveNames.SLOTS }
            .sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
}

/** The optional "Players" screen shown before a multiplayer game starts (pure, unit-tested). */
object CoopSetup {

    /** Players each console supports in EmulatorJS (anything not listed is single player). */
    private val PLAYERS = mapOf(
        "nes" to 4, "snes" to 4, "n64" to 4, "segaMD" to 4, "segaMS" to 2, "segaCD" to 2, "sega32x" to 2,
        "segaSaturn" to 4, "psx" to 4, "arcade" to 4, "mame" to 4, "atari2600" to 2, "atari5200" to 4,
        "atari7800" to 2, "jaguar" to 2, "pce" to 4, "pcfx" to 2, "coleco" to 2, "3do" to 2, "amiga" to 2, "c64" to 2
    )

    fun maxPlayers(systemId: String?): Int = PLAYERS.entries.firstOrNull { it.key.equals(systemId, ignoreCase = true) }?.value ?: 1

    /** Only for consoles with more than one player, and when more than one controller (or phone) is connected. */
    fun shouldShow(systemId: String?, padCount: Int): Boolean = maxPlayers(systemId) > 1 && padCount > 1

    /** One line per player slot: "Player 2: Xbox Controller", "nobody" or the wait prompt for [claiming]. */
    fun labels(systemId: String?, names: List<String?>, claiming: Int?): List<String> =
        (0 until maxPlayers(systemId).coerceAtMost(4)).map { i ->
            "Player ${i + 1}: " + when {
                claiming == i -> "press Start on a controller…"
                else -> names.getOrNull(i) ?: "nobody"
            }
        }
}
