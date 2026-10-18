package com.aiconsole.retrotv.web

/**
 * Network ad blocking by host. Only third-party sub-resources and pop-up/redirect targets are
 * checked; a page the user opens is never blocked, and the game hosts themselves (archive.org,
 * emulator CDNs) are on an allow-list so a game can't be broken by an over-eager rule.
 */
object AdBlock {

    private val HOSTS = setOf(
        // Ad networks and exchanges
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "googletagservices.com", "amazon-adsystem.com", "adnxs.com",
        "adsrvr.org", "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "rubiconproject.com",
        "pubmatic.com", "openx.net", "casalemedia.com", "smartadserver.com", "moatads.com", "yieldmo.com",
        "media.net", "33across.com", "sharethrough.com", "teads.tv", "zedo.com", "revcontent.com", "mgid.com",
        "adform.net", "adition.com", "advertising.com", "bidswitch.net", "contextweb.com", "districtm.io",
        "emxdgt.com", "gumgum.com", "indexww.com", "lijit.com", "sovrn.com", "spotxchange.com", "springserve.com",
        "triplelift.com", "undertone.com", "yieldlab.net", "adcolony.com", "applovin.com", "unityads.unity3d.com",
        "vungle.com", "inmobi.com", "ezoic.net", "ezoic.com", "ezodn.com", "adthrive.com", "mediavine.com",
        "raptive.com", "carbonads.net", "buysellads.com", "infolinks.com", "kargo.com", "nativo.com",
        "primis.tech", "connatix.com", "vidazoo.com", "aniview.com", "playwire.com", "venatus.com",
        "adinplay.com", "aditude.io", "freestar.com", "pbxai.com", "snigelweb.com", "setupad.net",
        "a-mo.net", "adpushup.com", "admixer.net", "adskeeper.com", "adtelligent.com", "betweendigital.com",
        // Pop-under, redirect and push-spam networks (the ones game sites use most)
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com", "adsterra.com",
        "adsterratech.com", "exoclick.com", "exosrv.com", "juicyads.com", "hilltopads.net", "hilltopads.com",
        "clickadu.com", "adcash.com", "admaven.com", "ad-maven.com", "galaksion.com", "monetag.com",
        "richpush.co", "pushground.com", "evadav.com", "rollerads.com", "trafficstars.com", "tsyndicate.com",
        "trafficjunky.net", "plugrush.com", "a-ads.com", "coinzilla.com", "bitmedia.io", "onclckmn.com",
        "onclickads.net", "onclickalgo.com", "onclkds.com", "clickaine.com", "popmyads.com", "poptm.com",
        "ad-score.com", "dolohen.com", "highperformanceformat.com", "highcpmgate.com", "profitablegatecpm.com",
        "effectiveratecpm.com", "displaycontentnetwork.com", "realsrv.com", "pemsrv.com", "mnaspm.com",
        "notifpush.com", "pushnami.com", "pushwoosh.com", "pushengage.com", "izooto.com", "aclib.net",
        "acscdn.com",
        // Trackers and analytics
        "google-analytics.com", "googletagmanager.com", "scorecardresearch.com", "quantserve.com",
        "quantcount.com", "hotjar.com", "mixpanel.com", "segment.io", "branch.io", "chartbeat.com",
        "newrelic.com", "nr-data.net", "onesignal.com", "facebook.net", "ads-twitter.com", "ads.linkedin.com",
        "bat.bing.com", "clarity.ms", "mc.yandex.ru", "adsco.re", "histats.com", "statcounter.com",
        "addthis.com", "sharethis.com", "crwdcntrl.net", "demdex.net", "everesttech.net", "agkn.com",
        "bluekai.com", "rlcdn.com", "tapad.com", "adsafeprotected.com", "doubleverify.com", "iasds01.com",
        "cookielaw.org", "fundingchoicesmessages.google.com"
    )

    /** Hosts that are never blocked: the game libraries and the emulator code they load. */
    private val ALLOW = setOf(
        "archive.org", "us.archive.org", "emulatorjs.org", "emulatorjs.com", "cdn.emulatorjs.org",
        "js-dos.com", "dos.zone", "jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com", "gstatic.com",
        "googleapis.com", "github.io", "githubusercontent.com", "webrcade.com", "greasyfork.org"
    )

    @Volatile
    private var extra: Set<String> = emptySet()

    /** User-added block rules (host suffixes, one per line, `#` comments allowed). */
    fun setCustom(lines: String) {
        extra = parseHosts(lines)
    }

    fun parseHosts(text: String): Set<String> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .map { line -> line.split(Regex("\\s+")).last() } // hosts-file "0.0.0.0 host" lines too
        .map { it.lowercase().removePrefix("*.").removePrefix("||").removeSuffix("^").trimEnd('.') }
        .filter { it.contains('.') && it != "localhost" && it != "0.0.0.0" }
        .toSet()

    fun isAllowed(host: String?): Boolean = matches(host, ALLOW)

    fun isBlocked(host: String?): Boolean {
        if (host == null || isAllowed(host)) return false
        return matches(host, HOSTS) || (extra.isNotEmpty() && matches(host, extra))
    }

    private fun matches(host: String?, set: Set<String>): Boolean {
        var candidate = host?.lowercase()?.trimEnd('.') ?: return false
        while (true) {
            if (candidate in set) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.lastIndexOf('.')) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    /**
     * Element-hiding CSS: common ad containers only. Selectors deliberately avoid anything
     * generic (`.banner`, `iframe`) that game sites reuse for the emulator or game list.
     */
    const val COSMETIC_CSS = """
ins.adsbygoogle, .adsbygoogle, [id^="google_ads_iframe"], [id^="div-gpt-ad"], [data-google-query-id],
iframe[src*="doubleclick.net"], iframe[src*="googlesyndication"], iframe[src*="exoclick"],
iframe[src*="adsterra"], iframe[src*="juicyads"], iframe[src*="popads"], iframe[src*="a-ads.com"],
[id^="taboola-"], .trc_related_container, .OUTBRAIN, [data-widget-id][class*="outbrain"],
.ezoic-ad, [id^="ezoic-pub-ad"], .adthrive-ad, .mv-ad-box, [class^="ad-container"], [id^="ad-container"],
.ad-slot, .ad-unit, .ad-banner, .ads-banner, #ad-banner, .advertisement, .sponsored-ads, .adBanner
{ display: none !important; }
"""
}
