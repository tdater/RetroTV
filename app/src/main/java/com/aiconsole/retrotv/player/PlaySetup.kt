package com.aiconsole.retrotv.player

/** Auto-resume rules (pure, unit-tested). */
object AutoResume {
    /** `game_prefs` flag; off by default. */
    const val PREF = "auto_resume"

    /** How long the exit save may take before the player closes anyway. */
    const val EXIT_CAP_MS = 8_000L

    /**
     * What to load at start: the hidden auto slot ([SaveNames.AUTO]) when [autoTime] says one exists, else the
     * most recently saved manual slot (lowest number on a tie), or null when nothing is saved.
     */
    fun pickSlot(slotsWithTimestamps: Map<Int, Long>, autoTime: Long? = null): Int? =
        if (autoTime != null) SaveNames.AUTO else slotsWithTimestamps.entries
            .filter { it.key in 1..SaveNames.SLOTS }
            .sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key })
            .firstOrNull()?.key
}

/** What the co-op check needs to know about one input device. */
data class PadDevice(val descriptor: String, val sources: Int, val isVirtual: Boolean)

/** The optional "Players" screen shown before a multiplayer game starts (pure, unit-tested). */
object CoopSetup {

    // Same bits as android.view.InputDevice.SOURCE_GAMEPAD / SOURCE_JOYSTICK (the same test VirtualPads.isController uses).
    private const val SOURCE_GAMEPAD = 0x00000401
    private const val SOURCE_JOYSTICK = 0x01000010

    /** Real controllers: gamepad or joystick sources, no virtual devices, one per descriptor (a pad can expose several nodes). */
    fun countControllers(devices: List<PadDevice>): Int = devices
        .filter { !it.isVirtual && ((it.sources and SOURCE_GAMEPAD) == SOURCE_GAMEPAD || (it.sources and SOURCE_JOYSTICK) == SOURCE_JOYSTICK) }
        .map { it.descriptor }.distinct().size

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
