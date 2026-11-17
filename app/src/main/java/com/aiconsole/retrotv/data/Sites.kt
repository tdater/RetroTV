package com.aiconsole.retrotv.data

/**
 * A game website shown on the home screen. Built-ins have a fixed [id] that matches an
 * `activity-alias` in the manifest (see `apps/SiteApps`), so they can be "installed" as their
 * own launcher app. User sites get a generated id and install as pinned shortcuts instead.
 *
 * Rich LaunchBox-style metadata includes supported consoles ([systems]), era badge ([era]),
 * character mascot icon ([mascotKey]), marquee franchises ([featuredGames]), and whether games
 * can stream directly into the built-in emulator ([directPlay]).
 */
data class Site(
    val id: String,
    val name: String,
    val url: String,
    val description: String,
    val color: Long,
    val builtIn: Boolean = true,
    val tags: List<String> = emptyList(),
    val systems: List<String> = emptyList(),
    val era: String = "",
    val mascotKey: String = "",
    val featuredGames: List<String> = emptyList(),
    val directPlay: Boolean = false
) {
    val host: String get() = UrlInput.host(url).orEmpty()
}

object BuiltInSites {

    val ALL: List<Site> = listOf(
        Site(
            id = "retrogames_cc",
            name = "RetroGames.cc",
            url = "https://www.retrogames.cc/",
            description = "Huge NES, SNES, Genesis, GBA, N64, PS1 and arcade library",
            color = 0xFFD84315,
            tags = listOf("Arcade", "Consoles"),
            systems = listOf("NES", "SNES", "Genesis", "GBA", "N64", "PS1", "Arcade"),
            era = "8-BIT · 16-BIT · 32/64-BIT",
            mascotKey = "mario_sonic",
            featuredGames = listOf("Super Mario World", "Sonic 2", "Pokemon Emerald", "Tekken 3"),
            directPlay = true
        ),
        Site(
            id = "playclassic",
            name = "PlayClassic.games",
            url = "https://playclassic.games/",
            description = "Classic console and DOS games in the browser",
            color = 0xFF6A1B9A,
            tags = listOf("Consoles", "DOS"),
            systems = listOf("DOS", "NES", "SNES", "Genesis", "Game Boy"),
            era = "PC DOS · 16-BIT CONSOLES",
            mascotKey = "doomguy",
            featuredGames = listOf("Doom", "Prince of Persia", "Wolfenstein 3D", "Oregon Trail"),
            directPlay = true
        ),
        Site(
            id = "classicreload",
            name = "ClassicReload",
            url = "https://classicreload.com/",
            description = "Thousands of DOS, Windows 3.1 and console classics",
            color = 0xFF2E7D32,
            tags = listOf("DOS", "PC"),
            systems = listOf("MS-DOS", "Win 3.1", "Amiga", "Arcade"),
            era = "VINTAGE PC & DOS (80s-90s)",
            mascotKey = "crt_dos",
            featuredGames = listOf("Monkey Island", "SimCity 2000", "Dune II", "Civilization"),
            directPlay = false
        ),
        Site(
            id = "emulatorgamer",
            name = "Emulator Gamer",
            url = "https://emulatorgamer.com/",
            description = "Play emulator games online for free",
            color = 0xFF1565C0,
            tags = listOf("Consoles"),
            systems = listOf("NES", "SNES", "GBA", "Genesis", "N64", "PS1"),
            era = "GOLDEN CONSOLE RETRO",
            mascotKey = "link",
            featuredGames = listOf("Zelda: A Link to the Past", "Chrono Trigger", "Super Metroid"),
            directPlay = true
        ),
        Site(
            id = "retrogamesnexus",
            name = "Retro Games Nexus",
            url = "https://retrogamesnexus.com/",
            description = "Classic retro and emulator games online",
            color = 0xFF00838F,
            tags = listOf("Consoles"),
            systems = listOf("GBA", "SNES", "Genesis", "NES", "Arcade"),
            era = "16-BIT & HANDHELD POWER",
            mascotKey = "pokemon",
            featuredGames = listOf("Pokemon FireRed", "Castlevania", "Mega Man X", "Street Fighter II"),
            directPlay = true
        ),
        Site(
            id = "internetarcade",
            name = "Internet Arcade",
            url = "https://archive.org/details/internetarcade",
            description = "Internet Archive's playable arcade machines",
            color = 0xFF37474F,
            tags = listOf("Arcade", "Archive"),
            systems = listOf("Coin-Op Arcade", "MAME", "Vector"),
            era = "COIN-OP GOLDEN AGE",
            mascotKey = "pacman",
            featuredGames = listOf("Pac-Man", "Galaga", "Donkey Kong", "Space Invaders"),
            directPlay = false
        ),
        Site(
            id = "oldgames",
            name = "OldGames.app",
            url = "https://oldgames.app/",
            description = "Classic retro games online",
            color = 0xFF8D6E63,
            tags = listOf("Consoles"),
            systems = listOf("NES", "SNES", "Genesis", "Game Boy", "GBC"),
            era = "CLASSIC 8 & 16-BIT",
            mascotKey = "gameboy",
            featuredGames = listOf("Tetris", "Super Mario Bros", "Contra", "DuckTales"),
            directPlay = true
        ),
        Site(
            id = "afterplay",
            name = "Afterplay.io",
            url = "https://afterplay.io/",
            description = "Indie game store · demos need a guest login",
            color = 0xFFC2185B,
            tags = listOf("Store"),
            systems = listOf("Cloud Emulation", "GBA", "SNES", "PS1"),
            era = "NEXT-GEN CLOUD RETRO",
            mascotKey = "cloud_controller",
            featuredGames = listOf("Cloud Sync", "Cross-Platform", "Homebrew Demos"),
            directPlay = false
        ),
        Site(
            id = "rec0ded88",
            name = "Rec0deD:88",
            url = "https://rec0ded88.com/",
            description = "Retro games online, many systems",
            color = 0xFF283593,
            tags = listOf("Consoles", "Arcade"),
            systems = listOf("NES", "SNES", "Genesis", "Master System", "Arcade"),
            era = "80s SYNTHWAVE ARCADE",
            mascotKey = "spaceinvader",
            featuredGames = listOf("OutRun", "Streets of Rage 2", "Double Dragon", "Shinobi"),
            directPlay = true
        ),
        Site(
            id = "retrogames_onl",
            name = "RetroGames.onl",
            url = "https://www.retrogames.onl/",
            description = "Online retro console games",
            color = 0xFFEF6C00,
            tags = listOf("Consoles"),
            systems = listOf("NES", "SNES", "Genesis", "GBA", "N64", "PS1"),
            era = "90s CONSOLE GENERATION",
            mascotKey = "crash",
            featuredGames = listOf("Crash Bandicoot", "Spyro the Dragon", "GoldenEye 007"),
            directPlay = true
        ),
        Site(
            id = "playretrogames",
            name = "PlayRetroGames",
            url = "https://www.playretrogames.com/",
            description = "Sega, Nintendo and Game Boy classics online",
            color = 0xFF558B2F,
            tags = listOf("Consoles"),
            systems = listOf("Sega Genesis", "Master System", "Game Gear", "NES", "SNES"),
            era = "SEGA & NINTENDO 16-BIT",
            mascotKey = "sonic",
            featuredGames = listOf("Sonic 3 & Knuckles", "Gunstar Heroes", "Golden Axe"),
            directPlay = true
        ),
        Site(
            id = "webrcade",
            name = "webЯcade",
            url = "https://play.webrcade.com/",
            description = "Gamepad-first emulation front end, built for TVs",
            color = 0xFF4527A0,
            tags = listOf("Consoles", "Controller"),
            systems = listOf("NES", "SNES", "Genesis", "GB", "GBA", "DOOM", "PCE"),
            era = "GAMEPAD-FIRST TV HUB",
            mascotKey = "gamepad_tv",
            featuredGames = listOf("Direct Gamepad", "TV Optimized", "Cloud Feeds"),
            directPlay = false
        ),
        Site(
            id = "free80sarcade",
            name = "Free 80s Arcade",
            url = "https://www.free80sarcade.com/",
            description = "NES, Atari, Sega and ColecoVision games",
            color = 0xFFAD1457,
            tags = listOf("Arcade", "Consoles"),
            systems = listOf("Atari 2600", "NES", "ColecoVision", "Sega"),
            era = "1980s 8-BIT NOSTALGIA",
            mascotKey = "atari_joystick",
            featuredGames = listOf("Pitfall!", "Space Invaders", "Frogger", "Asteroids"),
            directPlay = true
        ),
        Site(
            id = "gamonl",
            name = "GAM.ONL",
            url = "https://gam.onl/",
            description = "Play retro games online",
            color = 0xFF00695C,
            tags = listOf("Consoles"),
            systems = listOf("NES", "SNES", "GBA", "Genesis", "Arcade"),
            era = "ARCADE & CONSOLE HITS",
            mascotKey = "heart_powerup",
            featuredGames = listOf("Mega Man 2", "Final Fantasy VI", "Castlevania"),
            directPlay = true
        ),
        Site(
            id = "msdos",
            name = "MS-DOS Library",
            url = "https://archive.org/details/softwarelibrary_msdos_games",
            description = "Internet Archive: thousands of DOS games",
            color = 0xFF424242,
            tags = listOf("DOS", "Archive"),
            systems = listOf("MS-DOS PC", "PC Booter", "VGA/EGA"),
            era = "1980s-90s PC CLASSICS",
            mascotKey = "crt_dos",
            featuredGames = listOf("DOOM", "Prince of Persia", "SimCity", "Duke Nukem 3D"),
            directPlay = false
        ),
        Site(
            id = "consolelivingroom",
            name = "Console Living Room",
            url = "https://archive.org/details/consolelivingroom",
            description = "Internet Archive: Atari, ColecoVision, Odyssey and more",
            color = 0xFF5D4037,
            tags = listOf("Consoles", "Archive"),
            systems = listOf("Atari 2600", "ColecoVision", "Intellivision", "Odyssey"),
            era = "EARLY HOME CONSOLES",
            mascotKey = "woodgrain_console",
            featuredGames = listOf("Adventure", "Pong", "River Raid", "Yar's Revenge"),
            directPlay = false
        )
    )

    fun byId(id: String?): Site? = ALL.firstOrNull { it.id == id }
}
