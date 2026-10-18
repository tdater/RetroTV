package com.aiconsole.retrotv.player

/**
 * The consoles RetroTV's built-in player (EmulatorJS) can run. Sites pass either a system id
 * (`segaMD`) or a core name (`genesis_plus_gx`); both work with EmulatorJS, these tables only
 * give them a friendly name and pick a system for the user's own ROM files.
 */
object Systems {

    data class GameSystem(val id: String, val label: String, val cores: List<String>, val extensions: Set<String> = emptySet())

    val ALL: List<GameSystem> = listOf(
        GameSystem("nes", "NES / Famicom", listOf("fceumm", "nestopia"), setOf("nes", "fds", "unf", "unif")),
        GameSystem("snes", "Super Nintendo", listOf("snes9x"), setOf("smc", "sfc", "fig", "swc", "bs")),
        GameSystem("n64", "Nintendo 64", listOf("mupen64plus_next", "parallel_n64"), setOf("n64", "z64", "v64")),
        GameSystem("gb", "Game Boy / Color", listOf("gambatte"), setOf("gb", "gbc", "dmg")),
        GameSystem("gba", "Game Boy Advance", listOf("mgba"), setOf("gba", "agb")),
        GameSystem("nds", "Nintendo DS", listOf("melonds", "desmume", "desmume2015"), setOf("nds")),
        GameSystem("vb", "Virtual Boy", listOf("beetle_vb"), setOf("vb", "vboy")),
        GameSystem("segaMD", "Genesis / Mega Drive", listOf("genesis_plus_gx", "picodrive"), setOf("md", "gen", "smd", "68k", "sgd")),
        GameSystem("segaMS", "Master System", listOf("smsplus", "genesis_plus_gx", "picodrive"), setOf("sms")),
        GameSystem("segaGG", "Game Gear", listOf("genesis_plus_gx"), setOf("gg")),
        GameSystem("segaCD", "Sega CD", listOf("genesis_plus_gx", "picodrive")),
        GameSystem("sega32x", "Sega 32X", listOf("picodrive"), setOf("32x")),
        GameSystem("segaSaturn", "Sega Saturn", listOf("yabause")),
        GameSystem("psx", "PlayStation", listOf("pcsx_rearmed", "mednafen_psx_hw"), setOf("pbp")),
        GameSystem("psp", "PSP", listOf("ppsspp"), setOf("cso")),
        GameSystem("arcade", "Arcade (FinalBurn Neo)", listOf("fbneo", "fbalpha2012_cps1", "fbalpha2012_cps2")),
        GameSystem("mame", "Arcade (MAME 2003)", listOf("mame2003_plus", "mame2003")),
        GameSystem("atari2600", "Atari 2600", listOf("stella2014"), setOf("a26")),
        GameSystem("atari5200", "Atari 5200", listOf("a5200"), setOf("a52")),
        GameSystem("atari7800", "Atari 7800", listOf("prosystem"), setOf("a78")),
        GameSystem("lynx", "Atari Lynx", listOf("handy"), setOf("lnx")),
        GameSystem("jaguar", "Atari Jaguar", listOf("virtualjaguar"), setOf("j64", "jag")),
        GameSystem("pce", "PC Engine / TurboGrafx-16", listOf("mednafen_pce"), setOf("pce", "sgx")),
        GameSystem("pcfx", "PC-FX", listOf("mednafen_pcfx")),
        GameSystem("ngp", "Neo Geo Pocket", listOf("mednafen_ngp"), setOf("ngp", "ngc")),
        GameSystem("ws", "WonderSwan", listOf("mednafen_wswan"), setOf("ws", "wsc")),
        GameSystem("coleco", "ColecoVision", listOf("gearcoleco"), setOf("col")),
        GameSystem("3do", "3DO", listOf("opera")),
        GameSystem("amiga", "Amiga", listOf("puae"), setOf("adf", "adz", "dms", "ipf")),
        GameSystem("c64", "Commodore 64", listOf("vice_x64sc"), setOf("d64", "t64", "prg", "crt", "tap")),
        GameSystem("dos", "MS-DOS", listOf("dosbox_pure"))
    )

    /** Cores EmulatorJS only runs with threads (SharedArrayBuffer). */
    private val THREADED = setOf("psp", "ppsspp", "dos", "dosbox_pure")

    /** File types that don't say which console they're for (CD images, archives). */
    val AMBIGUOUS = setOf("zip", "7z", "rar", "bin", "cue", "chd", "iso", "img", "ccd", "m3u", "mds")

    fun byId(id: String?): GameSystem? = ALL.firstOrNull { it.id.equals(id, ignoreCase = true) }

    /** The system a site's `EJS_core` value belongs to (system id or core name). */
    fun of(core: String): GameSystem? = byId(core) ?: ALL.firstOrNull { s -> s.cores.any { it.equals(core, ignoreCase = true) } }

    fun label(core: String): String = of(core)?.label ?: core

    fun needsThreads(core: String): Boolean = core.lowercase() in THREADED || of(core)?.id in THREADED

    fun extension(fileName: String): String = fileName.substringAfterLast('/').substringAfterLast('.', "").lowercase()

    /** The system for a ROM file name, or null when the extension doesn't say (ask the user). */
    fun forFile(fileName: String): GameSystem? {
        val ext = extension(fileName)
        if (ext.isEmpty() || ext in AMBIGUOUS) return null
        return ALL.firstOrNull { ext in it.extensions }
    }

    /** True for files that can be a game (used to offer "Play" on downloads). */
    fun isRom(fileName: String): Boolean {
        val ext = extension(fileName)
        return ext in AMBIGUOUS || ALL.any { ext in it.extensions }
    }
}
