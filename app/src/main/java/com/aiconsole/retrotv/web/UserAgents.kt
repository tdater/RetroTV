package com.aiconsole.retrotv.web

/** User-agent strings for the desktop / mobile switch, derived from the device's WebView UA. */
object UserAgents {

    private val CHROME = Regex("Chrome/(\\d+)")

    fun chromeMajor(defaultUa: String): String = CHROME.find(defaultUa)?.groupValues?.get(1) ?: "130"

    /** A current desktop Chrome on Linux: sites serve their full layout and keyboard controls. */
    fun desktop(defaultUa: String): String =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajor(defaultUa)}.0.0.0 Safari/537.36"

    /**
     * The device UA without WebView's `; wv` and `Version/4.0` markers, which some sites use to
     * refuse embedded browsers (sign-ins, some game hosts).
     */
    fun mobile(defaultUa: String): String =
        defaultUa.replace("; wv)", ")").replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "").replace(Regex("\\s{2,}"), " ").trim()
}
