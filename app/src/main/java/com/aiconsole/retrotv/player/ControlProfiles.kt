package com.aiconsole.retrotv.player

/**
 * Button layouts per console (pure, unit-tested). A layout is EmulatorJS's control map serialised
 * as JSON text; this object never parses it. Stored in `game_prefs`: `controls_<game key>` for a
 * game's own layout, `profile_<system id>` for all games of a console. Resolution: game > system > default.
 */
object ControlProfiles {

    /** Largest layout text kept (a 4-player map is a few KB). */
    const val MAX_LENGTH = 64 * 1024

    enum class Source { Game, System, Default }

    fun gameKey(gameKey: String) = "controls_$gameKey"

    /** The pref key of a console's layout, or null when the system is unknown. */
    fun systemKey(systemId: String?): String? =
        systemId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { "profile_$it" }

    /** Looks like a JSON object of sane size; anything else is ignored rather than handed to the emulator. */
    fun isValid(profile: String?): Boolean {
        val t = profile?.trim() ?: return false
        return t.length in 2..MAX_LENGTH && t.startsWith("{") && t.endsWith("}")
    }

    /** The layout to start with, or null for EmulatorJS's defaults. */
    fun resolve(game: String?, system: String?): String? =
        game?.takeIf { isValid(it) }?.trim() ?: system?.takeIf { isValid(it) }?.trim()

    fun source(game: String?, system: String?): Source = when {
        isValid(game) -> Source.Game
        isValid(system) -> Source.System
        else -> Source.Default
    }

    /** [prefs] with the game's layout also saved as the console's; unchanged when the game has none. */
    fun copyToSystem(prefs: Map<String, String>, gameKey: String, systemId: String?): Map<String, String> {
        val target = systemKey(systemId) ?: return prefs
        val layout = prefs[gameKey(gameKey)]?.takeIf { isValid(it) } ?: return prefs
        return prefs + (target to layout.trim())
    }

    /** [prefs] without the console's layout. */
    fun reset(prefs: Map<String, String>, systemId: String?): Map<String, String> {
        val target = systemKey(systemId) ?: return prefs
        return prefs - target
    }
}
