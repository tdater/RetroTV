package com.aiconsole.retrotv.browser

/** JavaScript the browser runs in pages. All are idempotent and safe to inject repeatedly. */
object PageScripts {

    /**
     * Scrolls whatever is scrollable under (x, y) CSS px by (dx, dy): the nearest scrollable
     * ancestor, else the page. Pages with inner scroll panes ignore WebView.scrollBy.
     */
    fun scrollAt(x: Float, y: Float, dx: Float, dy: Float) = """
(function () {
  var el = document.elementFromPoint($x, $y);
  while (el && el !== document.body && el !== document.documentElement) {
    var s = getComputedStyle(el);
    var canY = $dy !== 0 && /(auto|scroll|overlay)/.test(s.overflowY) && el.scrollHeight > el.clientHeight + 1;
    var canX = $dx !== 0 && /(auto|scroll|overlay)/.test(s.overflowX) && el.scrollWidth > el.clientWidth + 1;
    if (canY || canX) { el.scrollBy($dx, $dy); return 'el'; }
    el = el.parentElement;
  }
  var before = window.scrollY + window.scrollX;
  window.scrollBy($dx, $dy);
  return (window.scrollY + window.scrollX) === before ? 'none' : 'page';
})();
"""

    /** Hides common ad containers. Cosmetic only; the network blocker does the real work. */
    fun cosmetic(css: String) = """
(function () {
  if (document.getElementById('__retrotv_cosmetic')) return;
  var s = document.createElement('style'); s.id = '__retrotv_cosmetic';
  s.textContent = ${js(css)};
  (document.head || document.documentElement).appendChild(s);
})();
"""

    /**
     * "Focus game": blows the biggest game surface (canvas, emulator iframe, embed, video) up to
     * fill the screen, keeping its aspect ratio, or restores it when called again. Works without
     * the user gesture that Element.requestFullscreen() would need.
     */
    const val FOCUS_GAME = """
(function () {
  var on = document.getElementById('__retrotv_focus');
  if (on) {
    on.remove();
    var t = document.querySelector('[data-retrotv-focus]');
    if (t) t.removeAttribute('data-retrotv-focus');
    return 'off';
  }
  var best = null, area = 0;
  var list = document.querySelectorAll('canvas, iframe, embed, object, video, ruffle-player, ruffle-embed, #emulator, #game, #game-container, .emulator, [id*="emulator"], [id*="game"]');
  for (var i = 0; i < list.length; i++) {
    var r = list[i].getBoundingClientRect();
    var a = r.width * r.height;
    if (r.width < 160 || r.height < 120) continue;
    var tag = list[i].tagName;
    if (tag === 'IFRAME' && /ads|doubleclick|syndication/.test(list[i].src || '')) continue;
    if (tag === 'CANVAS' || tag === 'IFRAME' || tag === 'EMBED' || tag === 'OBJECT' || tag.indexOf('RUFFLE') === 0) a *= 1.5;
    if (a > area) { area = a; best = list[i]; }
  }
  if (!best) return 'none';
  best.setAttribute('data-retrotv-focus', '1');
  var s = document.createElement('style'); s.id = '__retrotv_focus';
  s.textContent = 'html,body{overflow:hidden!important}' +
    '[data-retrotv-focus]{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;' +
    'max-width:none!important;max-height:none!important;margin:0!important;border:0!important;z-index:2147483647!important;' +
    'background:#000!important;object-fit:contain!important;transform:none!important}';
  document.head.appendChild(s);
  window.scrollTo(0, 0);
  try { window.dispatchEvent(new Event('resize')); } catch (e) {}
  return 'on';
})();
"""

    /** Exits any HTML5 full screen the page entered. */
    /**
     * Desktop / TV view: sets the top page's layout width (`RetroTVView.width(host)`), so the
     * difference is visible on a TV, whose screen is already "desktop" sized for most sites.
     */
    const val VIEWPORT = """
(function () {
  if (window.top !== window || window.__retrotvView || typeof RetroTVView === 'undefined') return;
  window.__retrotvView = true;
  var w = 0;
  try { w = +RetroTVView.width(location.hostname); } catch (e) {}
  if (!w) return;
  var want = 'width=' + w;
  function apply(force) {
    var head = document.head || document.documentElement;
    if (!head) return;
    var all = document.querySelectorAll('meta[name="viewport" i]');
    var m = all[0];
    for (var i = 1; i < all.length; i++) all[i].remove();
    if (!m) { m = document.createElement('meta'); m.name = 'viewport'; head.appendChild(m); }
    // A change made while the page is still starting up can be dropped: force it again later.
    if (force && m.content === want) m.content = want + ',';
    if (m.content !== want) m.content = want;
  }
  apply();
  function again() { apply(true); }
  document.addEventListener('DOMContentLoaded', again);
  addEventListener('load', function () { again(); setTimeout(again, 1500); setTimeout(again, 4000); });
  // The document (documentElement may not exist yet at document start). Frameworks (Next.js,
  // React Helmet) put their own value back after hydrating, so keep watching.
  new MutationObserver(function () { apply(false); }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content', 'name'] });
})();
"""

    const val EXIT_FULLSCREEN = "try{document.fullscreenElement&&document.exitFullscreen()}catch(e){}"

    /** Reads a blob: URL in the page and hands it to the native side for saving. */
    fun saveBlob(url: String, token: String, fileName: String) = """
(function () {
  fetch(${js(url)}).then(function (r) { return r.blob(); }).then(function (b) {
    var fr = new FileReader();
    fr.onload = function () { RetroTVFiles.save(${js(token)}, ${js(fileName)}, b.type || '', String(fr.result).split(',')[1] || ''); };
    fr.readAsDataURL(b);
  }).catch(function (e) { RetroTVFiles.failed(${js(token)}, String(e)); });
})();
"""

    /** First `.user.js` link on the page (Greasy Fork / OpenUserJS script pages). */
    const val FIND_DOWNLOAD_LINK = """
(function () {
  var a = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
  var hit = a.find(function (x) { return /\.user\.js(\?|$)/.test(x.href); });
  return hit ? hit.href : '';
})();
"""

    fun js(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                ' ' -> append("\\u2028")
                ' ' -> append("\\u2029")
                '<' -> append("\\u003c")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
