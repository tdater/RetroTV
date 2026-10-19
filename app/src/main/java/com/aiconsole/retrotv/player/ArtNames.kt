package com.aiconsole.retrotv.player

import java.net.URLDecoder

/**
 * Matching a game title to libretro's box art (pure, unit-tested). Art lives at
 * `thumbnails.libretro.com/<folder>/Named_Boxarts/<name>.png`, named after No-Intro/Redump titles
 * such as "Super Mario Bros. 3 (USA)"; sites call the same game "Super Mario Bros 3", so titles
 * are normalised and compared, and a weak match gives no art rather than the wrong cover.
 */
object ArtNames {

    private val FOLDERS = mapOf(
        "nes" to "Nintendo - Nintendo Entertainment System",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "n64" to "Nintendo - Nintendo 64",
        "gb" to "Nintendo - Game Boy",
        "gba" to "Nintendo - Game Boy Advance",
        "nds" to "Nintendo - Nintendo DS",
        "vb" to "Nintendo - Virtual Boy",
        "segaMD" to "Sega - Mega Drive - Genesis",
        "segaMS" to "Sega - Master System - Mark III",
        "segaGG" to "Sega - Game Gear",
        "segaCD" to "Sega - Mega-CD - Sega CD",
        "sega32x" to "Sega - 32X",
        "segaSaturn" to "Sega - Saturn",
        "psx" to "Sony - PlayStation",
        "psp" to "Sony - PlayStation Portable",
        "arcade" to "FBNeo - Arcade Games",
        "mame" to "MAME",
        "atari2600" to "Atari - 2600",
        "atari5200" to "Atari - 5200",
        "atari7800" to "Atari - 7800",
        "lynx" to "Atari - Lynx",
        "jaguar" to "Atari - Jaguar",
        "pce" to "NEC - PC Engine - TurboGrafx 16",
        "pcfx" to "NEC - PC-FX",
        "ngp" to "SNK - Neo Geo Pocket Color",
        "ws" to "Bandai - WonderSwan Color",
        "coleco" to "Coleco - ColecoVision",
        "3do" to "The 3DO Company - 3DO",
        "amiga" to "Commodore - Amiga",
        "c64" to "Commodore - 64",
        "dos" to "DOS"
    )

    private val ROM_EXTENSIONS: Set<String> = Systems.AMBIGUOUS + Systems.ALL.flatMap { it.extensions }
    private val GROUPS = Regex("""\([^)]*\)|\[[^]]*]""")
    private val HREF = Regex("""href="([^"]+)\.png"""")

    /** The libretro folder for a core or system id, or null when there's no art for it. */
    fun folder(core: String): String? = Systems.of(core)?.id?.let { FOLDERS[it] }

    fun normalize(title: String): String {
        var t = title.trim()
        val ext = t.substringAfterLast('.', "").lowercase()
        if (ext in ROM_EXTENSIONS) t = t.substringBeforeLast('.')
        t = t.lowercase().replace(GROUPS, " ").replace("&", " and ").replace("'", "").replace("’", "")
        t = t.replace(Regex("[^a-z0-9]+"), " ").trim()
        if (t.endsWith(" the")) t = "the " + t.removeSuffix(" the")
        return t.removePrefix("the ").trim()
    }

    /** The art name for [title] among [names], or null when nothing matches well enough. */
    fun best(title: String, names: List<String>): String? {
        val wanted = normalize(title)
        if (wanted.isEmpty()) return null
        val ranked = compareBy<String>({ region(it) }, { it.length })
        names.filter { normalize(it) == wanted }.minWithOrNull(ranked)?.let { return it }
        val tokens = wanted.split(' ').toSet()
        return names.map { it to jaccard(tokens, normalize(it).split(' ').toSet()) }
            .filter { it.second >= 0.75 }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { region(it.first) }.thenBy { it.first.length })
            .firstOrNull()?.first
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else (a intersect b).size.toDouble() / (a union b).size

    private fun region(name: String): Int = when {
        "(USA" in name -> 0
        "(World" in name -> 1
        "(Europe" in name -> 2
        else -> 3
    }

    /** The art names in one of libretro's folder listings. */
    fun parseListing(html: String): List<String> =
        HREF.findAll(html).map { URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8") }.toList()
}
