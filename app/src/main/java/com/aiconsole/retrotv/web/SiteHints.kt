package com.aiconsole.retrotv.web

/** Sites whose games need an extra click to start: a one-line hint, shown once per site per visit. */
object SiteHints {

    private const val TWO_CLICKS = "Press OK on Play, then OK again to start"

    private val HINTS = mapOf(
        "retrogamesnexus.com" to TWO_CLICKS,
        "playclassic.games" to TWO_CLICKS,
        "emulatorgamer.com" to TWO_CLICKS
    )

    /** The hint for a host (subdomains included), or null. */
    fun forHost(host: String?): String? {
        var h = host?.lowercase()?.trimEnd('.') ?: return null
        while (true) {
            HINTS[h]?.let { return it }
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndexOf('.')) return null
            h = h.substring(dot + 1)
        }
    }

    /** The key a hint is remembered under (so subdomains share one hint). */
    fun key(host: String?): String? {
        var h = host?.lowercase()?.trimEnd('.') ?: return null
        while (true) {
            if (h in HINTS) return h
            val dot = h.indexOf('.')
            if (dot < 0 || dot == h.lastIndexOf('.')) return null
            h = h.substring(dot + 1)
        }
    }
}
