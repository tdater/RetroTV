package com.aiconsole.retrotv.web

/** A Greasemonkey/Tampermonkey-style userscript: its metadata block plus the code. */
data class Userscript(
    val name: String,
    val namespace: String,
    val version: String,
    val description: String,
    val matches: List<String>,
    val includes: List<String>,
    val excludes: List<String>,
    val runAt: RunAt,
    val noFrames: Boolean,
    val source: String
) {
    enum class RunAt { DocumentStart, DocumentEnd, DocumentIdle }

    /** Stable file-safe id: the same script re-installed (e.g. an update) replaces itself. */
    val id: String
        get() = (namespace + "_" + name).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifEmpty { "script" }
}

/**
 * Plain-Kotlin userscript support: metadata parsing, @match/@include to regex conversion, and the
 * JavaScript wrapper that checks the URL and provides the common GM_* helpers. No Chrome
 * extension APIs exist in WebView, so only @grant none style scripts plus these shims work.
 */
object Userscripts {

    private val META = Regex("""//\s*==UserScript==(.*?)//\s*==/UserScript==""", RegexOption.DOT_MATCHES_ALL)
    private val LINE = Regex("""^\s*//\s*@([\w:-]+)(?:\s+(.*?))?\s*$""")

    fun parse(source: String): Userscript? {
        val block = META.find(source)?.groupValues?.get(1) ?: return null
        val values = mutableMapOf<String, MutableList<String>>()
        block.lines().forEach { line ->
            val m = LINE.find(line) ?: return@forEach
            values.getOrPut(m.groupValues[1].lowercase()) { mutableListOf() } += m.groupValues[2].trim()
        }
        fun first(key: String) = values[key]?.firstOrNull { it.isNotEmpty() }.orEmpty()
        val name = first("name").ifEmpty { return null }
        return Userscript(
            name = name,
            namespace = first("namespace"),
            version = first("version"),
            description = first("description"),
            matches = values["match"].orEmpty().filter { it.isNotEmpty() },
            includes = values["include"].orEmpty().filter { it.isNotEmpty() },
            excludes = (values["exclude"].orEmpty() + values["exclude-match"].orEmpty()).filter { it.isNotEmpty() },
            runAt = when (first("run-at")) {
                "document-start" -> Userscript.RunAt.DocumentStart
                "document-idle" -> Userscript.RunAt.DocumentIdle
                else -> Userscript.RunAt.DocumentEnd
            },
            noFrames = "noframes" in values,
            source = source
        )
    }

    /** Regex (JavaScript-compatible) for a @match pattern: scheme, host (optionally "*." prefixed) and path glob. */
    fun matchPatternRegex(pattern: String): String? {
        if (pattern == "<all_urls>") return "^https?://.*$"
        val m = Regex("""^(\*|https?|file)://([^/]*)(/.*)$""").find(pattern) ?: return null
        val (scheme, host, path) = m.destructured
        val schemeRe = if (scheme == "*") "https?" else scheme
        val hostRe = when {
            host == "*" -> "[^/]*"
            host.startsWith("*.") -> "([^/]*\\.)?" + escape(host.removePrefix("*."))
            else -> escape(host)
        }
        return "^$schemeRe://$hostRe${glob(path)}$"
    }

    /** Regex for an @include/@exclude: a `/regex/` literal, or a glob where `*` matches anything. */
    fun includeRegex(pattern: String): String =
        if (pattern.length > 2 && pattern.startsWith("/") && pattern.endsWith("/")) pattern.substring(1, pattern.length - 1)
        else "^${glob(pattern)}$"

    fun includePatterns(script: Userscript): List<String> {
        val all = script.matches.mapNotNull(::matchPatternRegex) + script.includes.map(::includeRegex)
        return if (script.matches.isEmpty() && script.includes.isEmpty()) listOf("^https?://.*$") else all
    }

    fun excludePatterns(script: Userscript): List<String> =
        script.excludes.map { matchPatternRegex(it) ?: includeRegex(it) }

    /** Same test the injected wrapper does, for Kotlin callers and tests. */
    fun appliesTo(script: Userscript, url: String): Boolean =
        includePatterns(script).any { Regex(it).containsMatchIn(url) } &&
            excludePatterns(script).none { Regex(it).containsMatchIn(url) }

    /**
     * The script wrapped for injection into every page: bails out unless the URL matches, runs at
     * most once per document, honours @noframes and @run-at, and defines GM_* helpers.
     */
    fun wrap(script: Userscript): String {
        val include = includePatterns(script).joinToString(",") { "new RegExp(${jsString(it)})" }
        val exclude = excludePatterns(script).joinToString(",") { "new RegExp(${jsString(it)})" }
        val id = jsString(script.id)
        val store = jsString("retrotv-us:${script.id}:")
        val start = when (script.runAt) {
            Userscript.RunAt.DocumentStart -> "run();"
            Userscript.RunAt.DocumentEnd -> "if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', run); else run();"
            Userscript.RunAt.DocumentIdle -> "if (document.readyState === 'complete') run(); else window.addEventListener('load', run);"
        }
        return """
(function () {
  var url = location.href;
  if (!([$include]).some(function (r) { return r.test(url); })) return;
  if (([$exclude]).some(function (r) { return r.test(url); })) return;
  ${if (script.noFrames) "if (window.top !== window.self) return;" else ""}
  var done = window.__retrotvUserscripts = window.__retrotvUserscripts || {};
  if (done[$id]) return;
  done[$id] = true;
  var unsafeWindow = window;
  var GM_info = { script: { name: ${jsString(script.name)}, version: ${jsString(script.version)} }, scriptHandler: 'RetroTV' };
  function GM_addStyle(css) {
    var s = document.createElement('style'); s.textContent = css;
    (document.head || document.documentElement).appendChild(s); return s;
  }
  function GM_getValue(k, d) {
    try { var v = localStorage.getItem($store + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; }
  }
  function GM_setValue(k, v) { try { localStorage.setItem($store + k, JSON.stringify(v)); } catch (e) {} }
  function GM_deleteValue(k) { try { localStorage.removeItem($store + k); } catch (e) {} }
  function GM_openInTab(u) { location.href = u; }
  function GM_listValues() {
    var out = [];
    try { for (var i = 0; i < localStorage.length; i++) { var k = localStorage.key(i); if (k.indexOf($store) === 0) out.push(k.substring($store.length)); } } catch (e) {}
    return out;
  }
  function GM_registerMenuCommand() { return 0; }
  function GM_unregisterMenuCommand() {}
  function GM_notification(d) { console.log('[RetroTV userscript] ' + (d && d.text ? d.text : d)); }
  function GM_setClipboard(t) { try { navigator.clipboard.writeText(String(t)); } catch (e) {} }
  function GM_xmlhttpRequest(d) {
    // Same-origin and CORS-enabled requests only: WebView has no privileged cross-origin fetch.
    var ctl = new AbortController();
    fetch(d.url, { method: d.method || 'GET', headers: d.headers || {}, body: d.data, signal: ctl.signal, credentials: 'include' })
      .then(function (r) { return r.text().then(function (t) {
        var resp = { status: r.status, statusText: r.statusText, responseText: t, response: t, finalUrl: r.url, responseHeaders: '' };
        if (d.responseType === 'json') { try { resp.response = JSON.parse(t); } catch (e) {} }
        if (d.onload) d.onload(resp);
      }); })
      .catch(function (e) { if (d.onerror) d.onerror({ error: String(e) }); });
    return { abort: function () { ctl.abort(); } };
  }
  var GM = {
    info: GM_info,
    addStyle: function (c) { return Promise.resolve(GM_addStyle(c)); },
    getValue: function (k, d) { return Promise.resolve(GM_getValue(k, d)); },
    setValue: function (k, v) { return Promise.resolve(GM_setValue(k, v)); },
    deleteValue: function (k) { return Promise.resolve(GM_deleteValue(k)); },
    listValues: function () { return Promise.resolve(GM_listValues()); },
    xmlHttpRequest: GM_xmlhttpRequest,
    registerMenuCommand: GM_registerMenuCommand,
    notification: GM_notification,
    setClipboard: GM_setClipboard,
    openInTab: GM_openInTab
  };
  function run() {
    try {
${script.source}
    } catch (e) { console.error('[RetroTV userscript] ' + GM_info.script.name, e); }
  }
  $start
})();
""".trimStart()
    }

    private fun glob(text: String): String = text.split('*').joinToString(".*") { escape(it) }

    private fun escape(text: String): String = buildString {
        for (c in text) {
            if (c in "\\^$.|?+()[]{}") append('\\')
            append(c)
        }
    }

    private fun jsString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
