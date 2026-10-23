package com.aiconsole.retrotv.player

/** What the player does for a start attempt. */
enum class RetryStep {
    /** The core the way it was asked for. */
    AsIs,

    /** Compatibility (WebGL 1) cores. */
    Legacy,

    /** A threaded core's single-thread build (only when the core has one). */
    NoThreads,

    /** Give up in the player: offer the site's own player. */
    Site
}

/** The retry ladder for a game that fails to start. Pure, so it can be unit tested. */
object RetryPlan {
    /**
     * The step for start number [attempt] (1 = the first start). [threaded]: the core needs threads
     * (PSP, DOS); [crossOriginIsolated]: the player page is isolated, i.e. threads can work;
     * [hasNonThreadBuild]: the core also exists without threads.
     */
    fun next(attempt: Int, threaded: Boolean, crossOriginIsolated: Boolean, hasNonThreadBuild: Boolean = false): RetryStep {
        if (attempt <= 1) return RetryStep.AsIs
        // Without isolation a threaded core cannot run, and compatibility cores don't change that.
        val noThreadsPossible = threaded && !crossOriginIsolated
        if (attempt == 2) {
            return when {
                noThreadsPossible && hasNonThreadBuild -> RetryStep.NoThreads
                noThreadsPossible -> RetryStep.Site
                else -> RetryStep.Legacy
            }
        }
        if (attempt == 3 && threaded && crossOriginIsolated && hasNonThreadBuild) return RetryStep.NoThreads
        return RetryStep.Site
    }

    /** The message when a threaded core cannot run because the WebView isn't cross-origin isolated. */
    const val NO_THREADS_MESSAGE =
        "This TV's browser engine can't run this system (PSP and DOS need a WebView feature that is missing). " +
            "Updating Android System WebView may fix it."
}
