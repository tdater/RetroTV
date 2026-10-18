package com.aiconsole.retrotv.data

import java.net.URLEncoder

/** Address-bar rules, plain Kotlin so they are unit-tested. */
object UrlInput {

    enum class SearchEngine(val label: String, val template: String) {
        Google("Google", "https://www.google.com/search?q=%s"),
        DuckDuckGo("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        Bing("Bing", "https://www.bing.com/search?q=%s"),
        Brave("Brave", "https://search.brave.com/search?q=%s")
    }

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val HOSTLIKE = Regex("^(localhost|[\\w-]+(\\.[\\w-]+)+|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$")

    /** What the user typed turned into a URL: a full URL, a bare domain, or a search. */
    fun toUrl(text: String, engine: SearchEngine = SearchEngine.Google): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
        if (t.startsWith("about:") || t.startsWith("file:")) return t
        if (' ' !in t && HOSTLIKE.matches(t)) return "https://$t"
        if (' ' !in t && SCHEME.containsMatchIn(t) && t.contains("://")) return t
        return engine.template.replace("%s", URLEncoder.encode(t, "UTF-8"))
    }

    /** Host of an http(s) URL, lower-case, without `www.`; null for anything else. */
    fun host(url: String?): String? {
        val m = Regex("^https?://([^/?#:]+)", RegexOption.IGNORE_CASE).find(url ?: return null) ?: return null
        return m.groupValues[1].lowercase().removePrefix("www.").trimEnd('.')
    }

    /** True when both URLs are on the same site (one host is the other or a subdomain of it). */
    fun sameSite(a: String?, b: String?): Boolean {
        val ha = host(a) ?: return false
        val hb = host(b) ?: return false
        return ha == hb || ha.endsWith(".$hb") || hb.endsWith(".$ha") || registrable(ha) == registrable(hb)
    }

    /** Rough registrable domain (last two labels, or three for short second-level like co.uk). */
    fun registrable(host: String): String {
        val parts = host.split('.')
        if (parts.size <= 2) return host
        val two = parts.takeLast(2)
        return if (two[0].length <= 3 && two[1].length == 2) parts.takeLast(3).joinToString(".") else two.joinToString(".")
    }

    /** File name a download link probably saves as. */
    fun guessFileName(url: String, contentDisposition: String?, mimeType: String?): String {
        val fromHeader = contentDisposition?.let {
            Regex("filename\\*=(?:UTF-8'')?([^;]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
                ?: Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }?.let { runCatching { java.net.URLDecoder.decode(it.trim(), "UTF-8") }.getOrDefault(it.trim()) }
        val fromUrl = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
            .let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
        val name = (fromHeader ?: fromUrl).replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { "download" }
        val needsExt = '.' !in name
        return when {
            !needsExt -> name
            mimeType == "application/vnd.android.package-archive" -> "$name.apk"
            mimeType == "application/zip" -> "$name.zip"
            else -> name
        }
    }

    fun isApk(fileName: String, mimeType: String?): Boolean =
        fileName.endsWith(".apk", true) || mimeType == "application/vnd.android.package-archive"
}
