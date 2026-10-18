package com.aiconsole.retrotv.web

/**
 * Stand-ins served instead of blocked ad and analytics scripts. Game sites often wait for an ad
 * library before starting a game (an `adBreak` callback, an ad player's "complete" event, a
 * tracking callback). An empty response would leave them waiting forever, so these keep the API
 * and report "no ad" straight away, the way uBlock Origin's resources do.
 */
object Surrogates {

    /** The stand-in for a blocked script request, or null to answer with an empty script. */
    fun find(host: String, path: String): String? {
        val h = host.lowercase()
        val p = path.lowercase()
        return when {
            h.endsWith("googlesyndication.com") && p.contains("adsbygoogle") -> ADSBYGOOGLE
            h.endsWith("googletagmanager.com") && (p.contains("/gtag/js") || p.contains("/gtm.js")) -> GTAG
            h.endsWith("google-analytics.com") && (p.endsWith("/analytics.js") || p.endsWith("/ga.js")) -> ANALYTICS
            (h.endsWith("googletagservices.com") || h.endsWith("doubleclick.net")) && p.contains("gpt") -> GPT
            h.endsWith("adinplay.com") -> AIPTAG
            else -> null
        }
    }

    /** Whether a blocked request is a script, judged by its path (WebView doesn't say). */
    fun looksLikeScript(path: String, accept: String?): Boolean {
        val p = path.lowercase()
        return p.endsWith(".js") || p.contains(".js?") || p.contains("/js/") || p.contains("/gtag/") ||
            p.contains("/tag/") || p.contains("adsbygoogle") || accept?.contains("javascript") == true
    }

    const val EMPTY = "/* blocked by RetroTV */"

    /** AdSense, including the H5 games API (adBreak / adConfig) that game pages wait on. */
    private const val ADSBYGOOGLE = """(function () {
  function handle(o) {
    try {
      if (!o || typeof o !== 'object') return;
      if (typeof o.onReady === 'function') setTimeout(o.onReady, 0);
      if (o.type || o.adBreakDone || o.beforeAd || o.afterAd || o.beforeReward) {
        setTimeout(function () {
          if (typeof o.adBreakDone === 'function') o.adBreakDone({ breakType: o.type || 'start', breakName: o.name || '', breakFormat: o.type === 'reward' ? 'reward' : 'interstitial', breakStatus: 'notReady' });
        }, 0);
      }
    } catch (e) {}
  }
  var old = window.adsbygoogle;
  var a = { loaded: true, push: function (o) { handle(o); return 1; } };
  if (old && old.length) for (var i = 0; i < old.length; i++) handle(old[i]);
  window.adsbygoogle = a;
})();"""

    /** gtag.js / GTM: run event callbacks so pages that wait for them carry on. */
    private const val GTAG = """(function () {
  var w = window;
  function call(o) {
    try {
      if (!o) return;
      var cb = o.eventCallback || o.event_callback;
      if (typeof cb === 'function') setTimeout(cb, 1);
      if (o.length >= 3 && o[2] && typeof o[2] === 'object') {
        var c = o[2].event_callback || o[2].eventCallback;
        if (typeof c === 'function') setTimeout(c, 1);
      }
    } catch (e) {}
  }
  var dl = w.dataLayer = w.dataLayer || [];
  try { if (dl.hide && typeof dl.hide.end === 'function') { dl.hide.end(); dl.hide.end = function () {}; } } catch (e) {}
  for (var i = 0; i < dl.length; i++) call(dl[i]);
  var push = dl.push;
  dl.push = function () { for (var j = 0; j < arguments.length; j++) call(arguments[j]); return push.apply(dl, arguments); };
  w.google_tag_manager = w.google_tag_manager || {};
})();"""

    /** analytics.js / ga.js: hit callbacks fire at once. */
    private const val ANALYTICS = """(function () {
  var w = window, q = (w.ga && w.ga.q) || [];
  var tracker = { get: function () {}, set: function () {}, send: function () {} };
  function ga() {
    var a = arguments, last = a[a.length - 1];
    if (typeof a[0] === 'function') { try { a[0](tracker); } catch (e) {} }
    if (last && typeof last === 'object' && typeof last.hitCallback === 'function') setTimeout(last.hitCallback, 1);
  }
  ga.create = function () { return tracker; };
  ga.getAll = function () { return [tracker]; };
  ga.getByName = function () { return tracker; };
  ga.loaded = true;
  w.ga = ga;
  w[w.GoogleAnalyticsObject || 'ga'] = ga;
  w._gaq = w._gaq || [];
  for (var i = 0; i < q.length; i++) ga.apply(null, q[i]);
})();"""

    /** Google Publisher Tag: queued commands run, every call is a no-op that chains. */
    private const val GPT = """(function () {
  var self = function () { return this; };
  var empty = function () { return []; };
  var nul = function () { return null; };
  var slot = { addService: self, defineSizeMapping: self, setTargeting: self, clearTargeting: self, setCollapseEmptyDiv: self,
    setConfig: self, setClickUrl: self, set: self, updateTargetingFromMap: self, setForceSafeFrame: self, setSafeFrameConfig: self,
    get: nul, getTargeting: empty, getTargetingKeys: empty, getResponseInformation: nul,
    getSlotElementId: function () { return ''; }, getAdUnitPath: function () { return ''; } };
  var pubads = { addEventListener: self, removeEventListener: self, enableSingleRequest: self, enableLazyLoad: self,
    collapseEmptyDivs: self, disableInitialLoad: self, refresh: self, clear: self, setTargeting: self, clearTargeting: self,
    setPrivacySettings: self, setRequestNonPersonalizedAds: self, setCentering: self, enableVideoAds: self, display: self,
    setForceSafeFrame: self, setSafeFrameConfig: self, setPublisherProvidedId: self, set: self, updateCorrelator: self,
    setLocation: self, setVideoContent: self, enableAsyncRendering: self, setCookieOptions: self,
    setTagForChildDirectedTreatment: self, getTargeting: empty, getTargetingKeys: empty, getSlots: empty, get: nul,
    isInitialLoadDisabled: function () { return false; } };
  var g = window.googletag = window.googletag || {};
  var queue = g.cmd || [];
  g.apiReady = true;
  g.pubadsReady = true;
  g.pubads = function () { return pubads; };
  g.companionAds = g.content = g.pubads;
  g.defineSlot = g.defineOutOfPageSlot = function () { return slot; };
  g.destroySlots = function () { return true; };
  g.display = g.enableServices = g.disablePublisherConsole = g.openConsole = g.setAdIframeTitle = g.setConfig = function () {};
  g.sizeMapping = function () { var b = { addSize: function () { return b; }, build: function () { return []; } }; return b; };
  g.getVersion = function () { return ''; };
  g.enums = { OutOfPageFormat: {} };
  g.cmd = { push: function (f) { try { f.call(window); } catch (e) {} return 1; } };
  for (var i = 0; i < queue.length; i++) { try { queue[i].call(window); } catch (e) {} }
})();"""

    /** AdinPlay (aiptag): pre-rolls and rewarded ads complete at once. */
    private const val AIPTAG = """(function () {
  var w = window, t = w.aiptag = w.aiptag || {};
  t.cmd = t.cmd || [];
  t.cmd.display = t.cmd.display || [];
  t.cmd.player = t.cmd.player || [];
  function later(f, a) { if (typeof f === 'function') setTimeout(function () { try { f(a); } catch (e) {} }, 0); }
  w.aipPlayer = function (o) {
    o = o || {};
    this.startPreRoll = function () { later(o.AIP_COMPLETE, 'blocked'); later(o.AIP_REMOVE); };
    this.startMidRoll = this.startPreRoll;
    this.startRewardedAd = function () { later(o.AIP_REWARDEDCOMPLETE, 'timeout'); later(o.AIP_REMOVE); };
    this.showVideo = this.startPreRoll;
  };
  w.aipDisplayTag = { display: function () {}, destroy: function () {}, clear: function () {}, refresh: function () {} };
  function run(q) {
    var list = Array.prototype.slice.call(q);
    q.length = 0;
    q.push = function (f) { try { f(); } catch (e) {} return 1; };
    list.forEach(function (f) { try { f(); } catch (e) {} });
  }
  run(t.cmd.player);
  run(t.cmd.display);
  run(t.cmd);
})();"""
}
