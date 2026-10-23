package com.aiconsole.retrotv.player

/**
 * Which EmulatorJS files (paths under `<version>/data/`) a console needs, so it can be downloaded
 * ahead of time and played without the internet (pure, unit-tested). Core builds are named after
 * the core (`fceumm`), not the system id (`nes`); threaded cores (PSP, DOS) only exist as `-thread`
 * builds. The `-legacy` builds (WebGL 1, older TV GPUs) are fetched too but may be missing.
 */
object CoreFiles {

    private val COMMON = listOf(
        "loader.js", "emulator.min.js", "emulator.min.css", "version.json", "localization/en-US.json",
        "compression/extract7z.js", "compression/extractzip.js", "compression/libunrar.js", "compression/libunrar.wasm"
    )

    /** The core EmulatorJS runs for [core] (a system id like `nes`, or already a core name). */
    fun coreName(core: String): String = Systems.byId(core)?.cores?.first() ?: core.lowercase()

    fun forCore(core: String): List<String> {
        val name = coreName(core)
        val builds = if (Systems.needsThreads(core)) listOf("$name-thread", "$name-thread-legacy") else listOf(name, "$name-legacy")
        return COMMON + "cores/reports/$name.json" + builds.map { "cores/$it-wasm.data" }
    }

    /** True when [core] also has a build without threads (none do today: threaded cores only ship `-thread` builds). */
    fun hasNonThreadBuild(core: String): Boolean =
        Systems.needsThreads(core) && forCore(core).any { it.endsWith("-wasm.data") && !it.contains("-thread") }

    /** Files that may not exist for every core; a missing one doesn't make the console unplayable. */
    fun optional(path: String): Boolean = path.contains("-legacy-")
}
