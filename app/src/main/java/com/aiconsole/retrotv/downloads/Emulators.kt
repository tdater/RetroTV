package com.aiconsole.retrotv.downloads

import com.aiconsole.retrotv.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

/** Where an emulator's APK comes from. Resolved at tap time so links never go stale. */
sealed class ApkSource {
    /** Fixed URLs; the 64-bit one is used on 64-bit devices. */
    data class Direct(val url: String, val url64: String? = null) : ApkSource()
    /** The newest GitHub release's .apk asset; [prefer] picks among several. */
    data class GitHub(val repo: String, val prefer: String? = null, val avoid: String? = null) : ApkSource()
    /** The first link matching [pattern] on a download page. */
    data class Scrape(val page: String, val pattern: String) : ApkSource()
    /** Only a download page (no stable direct link); opens in the browser. */
    data class Page(val url: String) : ApkSource()
}

data class EmulatorApp(
    val name: String,
    val systems: String,
    val description: String,
    val source: ApkSource,
    val homepage: String
)

/** A place to get games to load into emulator apps. */
data class GameSource(val name: String, val url: String, val description: String)

object Emulators {

    val APPS: List<EmulatorApp> = listOf(
        EmulatorApp(
            "RetroArch", "Almost everything (NES to PS1, arcade, DOS)",
            "The all-in-one libretro front end. Nightly build from the official buildbot.",
            ApkSource.Direct(
                "https://buildbot.libretro.com/nightly/android/RetroArch.apk",
                "https://buildbot.libretro.com/nightly/android/RetroArch_aarch64.apk"
            ),
            "https://www.retroarch.com/?page=platforms"
        ),
        EmulatorApp(
            "Lemuroid", "NES, SNES, GB/GBA, Genesis, N64, PSX, PSP, DS, arcade",
            "Simple, controller-friendly multi-system emulator built on libretro.",
            ApkSource.GitHub("Swordfish90/Lemuroid", prefer = "free"),
            "https://github.com/Swordfish90/Lemuroid"
        ),
        EmulatorApp(
            "PPSSPP", "PlayStation Portable",
            "The PSP emulator, official APK from ppsspp.org.",
            ApkSource.Scrape("https://www.ppsspp.org/download/", "https://www\\.ppsspp\\.org/files/[\\w_]+/ppsspp\\.apk"),
            "https://www.ppsspp.org/"
        ),
        EmulatorApp(
            "Flycast", "Dreamcast, Naomi, Atomiswave",
            "Dreamcast and Sega arcade emulator.",
            ApkSource.GitHub("flyinghead/flycast"),
            "https://github.com/flyinghead/flycast"
        ),
        EmulatorApp(
            "melonDS", "Nintendo DS",
            "Accurate DS emulator for Android.",
            ApkSource.GitHub("rafaelvcaetano/melonDS-android"),
            "https://github.com/rafaelvcaetano/melonDS-android"
        ),
        EmulatorApp(
            "Azahar", "Nintendo 3DS",
            "Continuation of Citra / Lime3DS.",
            ApkSource.GitHub("azahar-emu/azahar", prefer = "vanilla"),
            "https://github.com/azahar-emu/azahar"
        ),
        EmulatorApp(
            "ARMSX2", "PlayStation 2",
            "PCSX2-based PS2 emulator for ARM64 (needs a strong device).",
            ApkSource.GitHub("ARMSX2/ARMSX2"),
            "https://github.com/ARMSX2/ARMSX2"
        ),
        EmulatorApp(
            "Dolphin", "GameCube, Wii",
            "Official download page (beta and dev builds).",
            ApkSource.Page("https://dolphin-emu.org/download/"),
            "https://dolphin-emu.org/"
        ),
        EmulatorApp(
            "ScummVM", "Classic point-and-click adventures",
            "Official download page.",
            ApkSource.Page("https://www.scummvm.org/downloads/"),
            "https://www.scummvm.org/"
        )
    )

    /** Free, legal places to get games: preservation archives and homebrew. Any URL also works. */
    val GAME_SOURCES: List<GameSource> = listOf(
        GameSource("Internet Archive: Software", "https://archive.org/details/software", "Preserved software collections, many downloadable"),
        GameSource("Internet Archive: Console Living Room", "https://archive.org/details/consolelivingroom", "Classic console library"),
        GameSource("PDRoms", "https://pdroms.de/", "Homebrew and public-domain ROMs for retro systems"),
        GameSource("Homebrew Hub (Game Boy)", "https://hh.gbdev.io/", "Hundreds of free Game Boy / Color homebrew games"),
        GameSource("itch.io: NES homebrew", "https://itch.io/games/tag-nes", "New games for the NES"),
        GameSource("itch.io: Game Boy homebrew", "https://itch.io/games/tag-gameboy", "New games for Game Boy"),
        GameSource("itch.io: Genesis homebrew", "https://itch.io/games/tag-sega-genesis", "New games for the Mega Drive / Genesis")
    )

    /** Turns a source into a direct APK URL. Throws [IOException] when nothing fits. */
    suspend fun resolve(source: ApkSource, sdk: Int, is64Bit: Boolean): String = withContext(Dispatchers.IO) {
        when (source) {
            is ApkSource.Direct -> if (is64Bit && source.url64 != null) source.url64 else source.url
            is ApkSource.Page -> source.url
            is ApkSource.Scrape -> {
                val html = Http.get(source.page, maxBytes = 3_000_000, accept = "text/html")
                Regex(source.pattern).find(html)?.value ?: throw IOException("No APK link found on ${source.page}")
            }
            is ApkSource.GitHub -> {
                val json = JSONObject(Http.get("https://api.github.com/repos/${source.repo}/releases/latest", accept = "application/vnd.github+json"))
                val assets = json.optJSONArray("assets") ?: throw IOException("Release has no files")
                val names = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .map { it.optString("name") to it.optString("browser_download_url") }
                pickApk(names, source.prefer, source.avoid, sdk, is64Bit) ?: throw IOException("The latest ${source.repo} release has no APK")
            }
        }
    }

    /**
     * Chooses the best APK among (name, url) assets: only .apk files, [avoid] dropped, then
     * [prefer] matches, then the highest `sdkNN` build the device can run, then the ABI that
     * fits (arm64 on 64-bit, else armeabi / universal).
     */
    fun pickApk(assets: List<Pair<String, String>>, prefer: String?, avoid: String?, sdk: Int, is64Bit: Boolean): String? {
        var apks = assets.filter { it.first.endsWith(".apk", true) }
        if (avoid != null) apks = apks.filterNot { it.first.contains(avoid, true) }.ifEmpty { apks }
        if (prefer != null) apks = apks.filter { it.first.contains(prefer, true) }.ifEmpty { apks }
        val sdkRe = Regex("sdk(\\d+)", RegexOption.IGNORE_CASE)
        val withSdk = apks.map { it to (sdkRe.find(it.first)?.groupValues?.get(1)?.toIntOrNull()) }
        if (withSdk.any { it.second != null }) {
            val runnable = withSdk.filter { (it.second ?: 0) <= sdk }
            apks = if (runnable.isNotEmpty()) {
                val best = runnable.maxOf { it.second ?: 0 }
                runnable.filter { (it.second ?: 0) == best }.map { it.first }
            } else listOf(withSdk.minBy { it.second ?: Int.MAX_VALUE }.first)
        }
        fun abiScore(name: String): Int {
            val n = name.lowercase()
            return when {
                "universal" in n -> 2
                is64Bit && ("arm64" in n || "aarch64" in n || "v8a" in n) -> 3
                !is64Bit && ("armeabi" in n || "v7a" in n) -> 3
                is64Bit && ("armeabi" in n || "v7a" in n) -> 1
                !is64Bit && ("arm64" in n || "aarch64" in n || "v8a" in n) -> 0
                "x86" in n -> 0
                else -> 2
            }
        }
        return apks.maxByOrNull { abiScore(it.first) }?.second
    }
}
