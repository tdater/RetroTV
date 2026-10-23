package com.aiconsole.retrotv.player

/** What the player page found out about this WebView before any emulator file is downloaded. */
data class Caps(
    val webgl2: Boolean,
    val sharedArrayBuffer: Boolean,
    val crossOriginIsolated: Boolean,
    val wasmThreads: Boolean
)

sealed interface Verdict {
    /** Start normally. */
    data object Ok : Verdict

    /** Start, but tell the user what changes (e.g. compatibility mode). */
    data class Warn(val message: String) : Verdict

    /** Don't start: the game cannot run here. [suggestSitePlayer]: the site's own player might. */
    data class Block(val message: String, val suggestSitePlayer: Boolean) : Verdict
}

/** The pre-flight check (pure, unit-tested): decides before cores are downloaded. */
object Preflight {

    const val HINT = "Update Android System WebView, or play on the site."

    const val NO_WEBGL2 = "This TV's graphics don't support WebGL 2, so the game starts in compatibility mode."

    fun verdict(core: String, caps: Caps): Verdict {
        if (Systems.needsThreads(core)) {
            val system = Systems.label(core)
            when {
                !caps.wasmThreads ->
                    return Verdict.Block("$system needs WebAssembly threads and this TV's browser engine doesn't have them. $HINT", true)
                !caps.sharedArrayBuffer || !caps.crossOriginIsolated ->
                    return Verdict.Block("$system needs a browser feature (shared memory) that is switched off in this TV's browser engine. $HINT", true)
            }
        }
        if (!caps.webgl2) return Verdict.Warn(NO_WEBGL2)
        return Verdict.Ok
    }
}
