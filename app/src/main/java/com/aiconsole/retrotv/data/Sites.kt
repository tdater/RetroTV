package com.aiconsole.retrotv.data

/**
 * A game website shown on the home screen. Built-ins have a fixed [id] that matches an
 * `activity-alias` in the manifest (see `apps/SiteApps`), so they can be "installed" as their
 * own launcher app. User sites get a generated id and install as pinned shortcuts instead.
 */
data class Site(
    val id: String,
    val name: String,
    val url: String,
    val description: String,
    val color: Long,
    val builtIn: Boolean = true,
    val tags: List<String> = emptyList()
) {
    val host: String get() = UrlInput.host(url).orEmpty()
}

object BuiltInSites {

    val ALL: List<Site> = listOf(
        Site("retrogames_cc", "RetroGames.cc", "https://www.retrogames.cc/", "Huge NES, SNES, Genesis, GBA, N64, PS1 and arcade library", 0xFFD84315, tags = listOf("Arcade", "Consoles")),
        Site("playclassic", "PlayClassic.games", "https://playclassic.games/", "Classic console and DOS games in the browser", 0xFF6A1B9A, tags = listOf("Consoles", "DOS")),
        Site("classicreload", "ClassicReload", "https://classicreload.com/", "Thousands of DOS, Windows 3.1 and console classics", 0xFF2E7D32, tags = listOf("DOS", "PC")),
        Site("emulatorgamer", "Emulator Gamer", "https://emulatorgamer.com/", "Play emulator games online for free", 0xFF1565C0, tags = listOf("Consoles")),
        Site("retrogamesnexus", "Retro Games Nexus", "https://retrogamesnexus.com/", "Classic retro and emulator games online", 0xFF00838F, tags = listOf("Consoles")),
        Site("internetarcade", "Internet Arcade", "https://archive.org/details/internetarcade", "Internet Archive's playable arcade machines", 0xFF37474F, tags = listOf("Arcade", "Archive")),
        Site("oldgames", "OldGames.app", "https://oldgames.app/", "Classic retro games online", 0xFF8D6E63, tags = listOf("Consoles")),
        Site("afterplay", "Afterplay.io", "https://afterplay.io/", "Retro games on any device", 0xFFC2185B, tags = listOf("Consoles")),
        Site("rec0ded88", "Rec0deD:88", "https://rec0ded88.com/", "Retro games online, many systems", 0xFF283593, tags = listOf("Consoles", "Arcade")),
        Site("retrogames_onl", "RetroGames.onl", "https://www.retrogames.onl/", "Online retro console games", 0xFFEF6C00, tags = listOf("Consoles")),
        Site("playretrogames", "PlayRetroGames", "https://www.playretrogames.com/", "Sega, Nintendo and Game Boy classics online", 0xFF558B2F, tags = listOf("Consoles")),
        Site("webrcade", "webЯcade", "https://play.webrcade.com/", "Gamepad-first emulation front end, built for TVs", 0xFF4527A0, tags = listOf("Consoles", "Controller")),
        Site("free80sarcade", "Free 80s Arcade", "https://www.free80sarcade.com/", "NES, Atari, Sega and ColecoVision games", 0xFFAD1457, tags = listOf("Arcade", "Consoles")),
        Site("gamonl", "GAM.ONL", "https://gam.onl/", "Play retro games online", 0xFF00695C, tags = listOf("Consoles")),
        Site("msdos", "MS-DOS Library", "https://archive.org/details/softwarelibrary_msdos_games", "Internet Archive: thousands of DOS games", 0xFF424242, tags = listOf("DOS", "Archive")),
        Site("consolelivingroom", "Console Living Room", "https://archive.org/details/consolelivingroom", "Internet Archive: Atari, ColecoVision, Odyssey and more", 0xFF5D4037, tags = listOf("Consoles", "Archive"))
    )

    fun byId(id: String?): Site? = ALL.firstOrNull { it.id == id }
}
