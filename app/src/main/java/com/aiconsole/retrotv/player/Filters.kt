package com.aiconsole.retrotv.player

/** Display filters: EmulatorJS 4.2.3 built-in shader presets with friendly names (pure, unit-tested). */
object Filters {

    val ALL: List<Pair<String, String>> = listOf(
        "disabled" to "Off",
        "crt-easymode.glslp" to "CRT",
        "crt-geom.glslp" to "CRT curved",
        "crt-lottes" to "CRT bright",
        "crt-zfast" to "CRT light (fast)",
        "crt-aperture.glslp" to "Aperture grille"
    )

    fun label(id: String): String = ALL.firstOrNull { it.first == id }?.second ?: "Off"

    /** The filter after [id], wrapping round; unknown ids go to the first CRT one. */
    fun next(id: String): String {
        val i = ALL.indexOfFirst { it.first == id }
        return ALL[if (i < 0) 1 else (i + 1) % ALL.size].first
    }
}
