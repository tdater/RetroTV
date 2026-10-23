package com.aiconsole.retrotv.player

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceResponse
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves everything the built-in player loads, from `https://appassets.androidplatform.net`:
 * - `/player/…`: the player page (app assets);
 * - `/rom/<token>/<name>`: game files. Site files are downloaded here natively, with the site's
 *   cookies and referrer, because their servers don't allow other pages to read them (CORS); each
 *   finished download is kept in a size-capped cache so replays start instantly and work offline;
 * - EmulatorJS itself ([EJS_HOST] under [EJS_VERSION]), cached on disk after the first download.
 *
 * Called from the WebView's request threads.
 */
class GameFiles(context: Context) {

    private val appContext = context.applicationContext
    private val romDir = File(context.filesDir, "games").apply { mkdirs() }
    // Emulator files live in filesDir (cacheDir can be purged by Android), so played consoles keep working offline.
    private val ejsDir = File(context.filesDir, "emulatorjs").also { dir ->
        val old = File(context.cacheDir, "emulatorjs")
        if (old.isDirectory && !dir.exists()) old.renameTo(dir)
    }
    private val saveDir = SaveStore.rootDir(context.filesDir)

    private sealed interface Source {
        data class Remote(val url: String, val referer: String, val userAgent: String, val cookie: String?, val strict: Boolean = true) : Source
        data class Local(val file: File) : Source
    }

    private val tokens = ConcurrentHashMap<String, Source>()

    private class Incoming(val file: File, val size: Long) {
        var written = 0L
        var done = false
        var failed = false
    }

    private val incoming = ConcurrentHashMap<String, Incoming>()

    /**
     * Registers the request's files and returns their player URLs (`game`, `bios`, `parent`,
     * `patch`, `state`). Call on the main thread (cookies of a site app's profile).
     */
    fun prepare(req: GameRequest): Map<String, String> {
        if (tokens.size > 200) tokens.clear()
        problem = null
        val out = LinkedHashMap<String, String>()
        fun add(key: String, value: String?, fallbackName: String) {
            if (value.isNullOrBlank()) return
            val source = source(value, req, key == "game") ?: return
            val token = UUID.randomUUID().toString().replace("-", "")
            tokens[token] = source
            out[key] = "$ORIGIN/rom/$token/" + URLEncoder.encode(fileName(value, fallbackName), "UTF-8").replace("+", "%20")
        }
        add("game", req.game, req.gameName.ifBlank { "game" })
        add("bios", req.bios, "bios")
        add("parent", req.parent, "parent")
        add("patch", req.patch, "patch")
        add("state", req.state, "game.state")
        return out
    }

    /** The last reason a game download was refused (see [RomRules]); [takeProblem] reads and clears it. */
    @Volatile
    private var problem: String? = null

    fun takeProblem(): String? = problem.also { problem = null }

    /** True when [file] is a cached game that still looks like one; a bad copy is deleted so it is fetched again. */
    private fun cachedOk(file: File, strict: Boolean): Boolean {
        if (file.length() <= 0) return false
        val head = runCatching {
            FileInputStream(file).use { input ->
                val buf = ByteArray(RomRules.HEAD_BYTES)
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
        }.getOrNull() ?: return false
        if (RomRules.check(null, file.length(), head, strict) == null) return true
        file.delete()
        return false
    }

    private fun source(value: String, req: GameRequest, strict: Boolean): Source? = when {
        value.startsWith("http://") || value.startsWith("https://") -> {
            val cached = cacheFile(value)
            if (cachedOk(cached, strict)) Source.Local(cached)
            else Source.Remote(value, req.referer.ifBlank { req.page }, req.userAgent, cookieFor(value, req.profile), strict)
        }
        value.startsWith("blob:") -> blobFile(value.removePrefix("blob:"))?.let { Source.Local(it) }
        value.startsWith("file:") -> File(value.removePrefix("file:")).takeIf { it.isFile && it.canonicalPath.startsWith(romDir.canonicalPath) }?.let { Source.Local(it) }
        else -> null
    }

    private fun cookieFor(url: String, profile: String?): String? = runCatching {
        val manager = if (profile != null && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getProfile(profile)?.cookieManager ?: CookieManager.getInstance()
        } else {
            CookieManager.getInstance()
        }
        manager.getCookie(url)
    }.getOrNull()

    /** The file a game URL is cached as (exists once a download finished). */
    fun cacheFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
        return File(romDir, hash + "_" + safeName(fileName(url, "game")))
    }

    fun isAvailable(value: String): Boolean = when {
        value.startsWith("http") -> true
        value.startsWith("file:") -> File(value.removePrefix("file:")).isFile
        else -> false
    }

    /** Copies a user-picked ROM into the game folder; returns its `file:` value. Call off the main thread. */
    fun importRom(uri: Uri, name: String): String {
        val target = File(romDir, "own_" + System.currentTimeMillis().toString(36) + "_" + safeName(name))
        appContext.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            ?: throw IllegalStateException("Couldn't open the file")
        trim()
        return "file:" + target.absolutePath
    }

    /**
     * Saves a ROM sent from the phone link: written as `.part` and kept only when all [size]
     * bytes arrived. Returns its `file:` value. Call off the main thread.
     */
    fun receiveRom(input: InputStream, name: String, size: Long): String {
        val target = File(romDir, "own_phone_" + System.currentTimeMillis().toString(36) + "_" + safeName(name))
        val part = File(romDir, target.name + ".part")
        val written = FileOutputStream(part).use { out -> input.copyTo(out) }
        if (written != size || !part.renameTo(target)) {
            part.delete()
            throw IllegalStateException("The upload stopped before the whole file arrived")
        }
        trim()
        return "file:" + target.absolutePath
    }

    // ---- Files sent by a page (blob: game URLs), in base64 chunks ----

    fun blobOpen(name: String, size: Long): String? {
        if (size <= 0 || size > MAX_BLOB) return null
        val token = UUID.randomUUID().toString().replace("-", "")
        incoming[token] = Incoming(File(romDir, "page_" + token.take(12) + "_" + safeName(name)), size)
        return token
    }

    fun blobWrite(token: String, base64: String) {
        val inc = incoming[token] ?: return
        synchronized(inc) {
            if (inc.failed || inc.done) return
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            if (bytes == null || inc.written + bytes.size > inc.size) {
                inc.failed = true
                inc.file.delete()
                return
            }
            FileOutputStream(inc.file, true).use { it.write(bytes) }
            inc.written += bytes.size
        }
    }

    fun blobClose(token: String): Boolean {
        val inc = incoming[token] ?: return false
        synchronized(inc) {
            inc.done = !inc.failed && inc.written == inc.size
            if (!inc.done) inc.file.delete() else trim()
            return inc.done
        }
    }

    /** The finished file of a page upload, for `blob:<token>` values. */
    fun blobFile(token: String): File? = incoming[token]?.takeIf { it.done }?.file?.takeIf { it.isFile }

    // ---- Serving ----

    /** Answers a player request, or null when it isn't ours. */
    fun serve(url: Uri, method: String): WebResourceResponse? {
        val host = url.host ?: return null
        val path = url.path ?: return null
        return when {
            host == HOST && path.startsWith("/player/") -> serveAsset(path.removePrefix("/"), url.getQueryParameter("coi") == "1")
            host == HOST && path.startsWith("/rom/") -> serveRom(path, method)
            host == HOST && path.startsWith("/save/") -> serveSave(path)
            host == HOST -> status(404, "Not Found")
            host == EJS_HOST && path.startsWith("/$EJS_VERSION/") -> serveEmulator(url.toString(), path)
            else -> null
        }
    }

    private fun serveAsset(path: String, isolated: Boolean): WebResourceResponse {
        if (path.contains("..")) return status(404, "Not Found")
        val headers = HashMap<String, String>()
        headers["Cache-Control"] = "no-cache"
        if (isolated && path.endsWith(".html")) {
            // Threaded cores (PSP, DOS) need SharedArrayBuffer, which needs cross-origin isolation.
            headers["Cross-Origin-Opener-Policy"] = "same-origin"
            headers["Cross-Origin-Embedder-Policy"] = "require-corp"
        }
        return try {
            WebResourceResponse(mime(path), if (isText(path)) "utf-8" else null, 200, "OK", headers, appContext.assets.open(path))
        } catch (_: Exception) {
            status(404, "Not Found")
        }
    }

    private fun serveRom(path: String, method: String): WebResourceResponse {
        val token = path.removePrefix("/rom/").substringBefore('/')
        val source = tokens[token] ?: return status(404, "Not Found")
        val head = method.equals("HEAD", ignoreCase = true)
        return when (source) {
            is Source.Local -> fileResponse(source.file, head, emptyMap())
            is Source.Remote -> {
                val cached = cacheFile(source.url)
                if (cachedOk(cached, source.strict)) {
                    cached.setLastModified(System.currentTimeMillis())
                    fileResponse(cached, head, emptyMap())
                } else if (head) {
                    WebResourceResponse("application/octet-stream", null, 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                } else {
                    download(source.url, source.referer, source.userAgent, source.cookie, cached, emptyMap(), source.strict, true)
                }
            }
        }
    }

    /** `/save/<dir>/slot<N>.state|.png` from [SaveStore]; nothing else under it is reachable. */
    private fun serveSave(path: String): WebResourceResponse {
        val parts = path.removePrefix("/save/").split('/')
        if (parts.size != 2 || !SaveNames.isDir(parts[0]) || !SaveNames.isServed(parts[1])) return status(404, "Not Found")
        val file = File(File(saveDir, parts[0]), parts[1])
        return if (file.isFile) fileResponse(file, false, mapOf("Cache-Control" to "no-store")) else status(404, "Not Found")
    }

    private fun serveEmulator(url: String, path: String): WebResourceResponse {
        val cors = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cross-Origin-Resource-Policy" to "cross-origin",
            "Cache-Control" to "max-age=31536000"
        )
        val file = File(ejsDir, path.removePrefix("/"))
        if (path.contains("..")) return status(404, "Not Found", cors)
        if (file.length() > 0) return fileResponse(file, false, cors)
        file.parentFile?.mkdirs()
        return download(url, "", "", null, file, cors)
    }

    private fun fileResponse(file: File, head: Boolean, extra: Map<String, String>): WebResourceResponse {
        val headers = HashMap(extra)
        headers["Content-Length"] = file.length().toString()
        val body: InputStream = if (head) ByteArrayInputStream(ByteArray(0)) else FileInputStream(file)
        return WebResourceResponse(mime(file.name), if (isText(file.name)) "utf-8" else null, 200, "OK", headers, body)
    }

    /** Streams [url] to the page while saving it to [target]; the copy is kept only when complete. */
    private fun download(url: String, referer: String, userAgent: String, cookie: String?, target: File, extra: Map<String, String>, strict: Boolean = true, validateRom: Boolean = false): WebResourceResponse {
        var current = url
        val firstHost = Uri.parse(url).host
        repeat(6) {
            val conn = try {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", userAgent.ifBlank { DEFAULT_UA })
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (referer.startsWith("http")) setRequestProperty("Referer", referer)
                    if (!cookie.isNullOrBlank() && Uri.parse(current).host == firstHost) setRequestProperty("Cookie", cookie)
                }
            } catch (e: Exception) {
                return status(502, "Bad Gateway", extra)
            }
            val code = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                return status(502, "Bad Gateway", extra)
            }
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null) return status(502, "Bad Gateway", extra)
                current = URL(URL(current), location).toString()
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                // Only the game file itself is a "problem"; a missing optional file (bios, patch, state) isn't.
                if (validateRom && strict) problem = "The site refused the download (HTTP $code). Open the game on the site again, then press Play."
                return status(if (code in 400..599) code else 502, "Upstream error", extra)
            }
            val length = conn.contentLengthLong
            var stream: InputStream = try {
                conn.inputStream
            } catch (e: Exception) {
                conn.disconnect()
                return status(502, "Bad Gateway", extra)
            }
            if (validateRom) {
                // Never cache or hand over a challenge page / error JSON as the game.
                val pushback = PushbackInputStream(stream, RomRules.HEAD_BYTES)
                val buf = ByteArray(RomRules.HEAD_BYTES)
                var n = 0
                try {
                    while (n < buf.size) {
                        val r = pushback.read(buf, n, buf.size - n)
                        if (r < 0) break
                        n += r
                    }
                } catch (e: Exception) {
                    conn.disconnect()
                    return status(502, "Bad Gateway", extra)
                }
                val bad = RomRules.check(conn.contentType, length, buf.copyOf(n), strict)
                if (bad != null) {
                    if (strict) problem = bad.message
                    conn.disconnect()
                    target.delete()
                    return status(502, "Not a game file", extra)
                }
                pushback.unread(buf, 0, n)
                stream = pushback
            }
            val part = File(target.parentFile, target.name + ".part")
            val body = SavingStream(stream, part, length) { complete ->
                conn.disconnect()
                if (complete && part.renameTo(target)) {
                    if (target.parentFile == romDir) trim()
                } else {
                    part.delete()
                }
            }
            val headers = HashMap(extra)
            if (length >= 0) headers["Content-Length"] = length.toString()
            val type = conn.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "text/plain" } ?: mime(target.name)
            return WebResourceResponse(type, if (isText(target.name)) "utf-8" else null, 200, "OK", headers, body)
        }
        return status(502, "Too many redirects", extra)
    }

    /** Keeps the game cache under [MAX_CACHE], dropping the least recently played files. */
    private fun trim() {
        val files = romDir.listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        files.forEachIndexed { i, f ->
            total += f.length()
            if (i > 0 && total > MAX_CACHE && !f.name.startsWith("own_")) f.delete()
        }
    }

    fun cacheSize(): Long = romDir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Deletes cached game files (not the user's own ROMs, and not the emulators: see [clearEmulators]). */
    fun clearCache() {
        romDir.listFiles()?.forEach { if (!it.name.startsWith("own_")) it.delete() }
    }

    fun clearEmulators() {
        ejsDir.deleteRecursively()
    }

    fun emulatorSize(): Long = ejsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun emulatorFile(path: String) = File(ejsDir, "$EJS_VERSION/data/$path")

    /** True when every required file for [core] is on the TV ([CoreFiles]). */
    fun isOffline(core: String): Boolean = CoreFiles.forCore(core).all { CoreFiles.optional(it) || emulatorFile(it).length() > 0 }

    /**
     * Downloads what [core] needs to start without the internet. Finished files stay even when a
     * later one fails. Returns false when a required file couldn't be fetched. Call off the main thread.
     */
    fun prefetch(core: String, onProgress: (done: Int, total: Int) -> Unit): Boolean {
        val files = CoreFiles.forCore(core)
        files.forEachIndexed { i, path ->
            onProgress(i, files.size)
            val target = emulatorFile(path)
            if (target.length() > 0) return@forEachIndexed
            target.parentFile?.mkdirs()
            val response = download("$EJS_DATA$path", "", "", null, target, emptyMap())
            val ok = response.statusCode == 200 && runCatching {
                response.data.use { input -> val buf = ByteArray(64 * 1024); while (input.read(buf) >= 0) Unit }
            }.isSuccess && target.length() > 0
            if (!ok && !CoreFiles.optional(path)) return false
        }
        onProgress(files.size, files.size)
        return true
    }

    private class SavingStream(input: InputStream, private val file: File, private val expected: Long, private val onEnd: (Boolean) -> Unit) :
        FilterInputStream(input) {
        private val out = runCatching { FileOutputStream(file) }.getOrNull()
        private var written = 0L
        private var ended = false

        override fun read(): Int {
            val b = super.read()
            if (b < 0) finish(true) else {
                out?.write(b)
                written++
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = try {
                super.read(b, off, len)
            } catch (e: Exception) {
                finish(false)
                throw e
            }
            if (n < 0) finish(true) else if (n > 0) {
                out?.write(b, off, n)
                written += n
            }
            return n
        }

        override fun close() {
            finish(false)
            super.close()
        }

        private fun finish(eof: Boolean) {
            if (ended) return
            ended = true
            runCatching { out?.close() }
            onEnd(eof && out != null && (expected < 0 || written == expected))
        }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val ORIGIN = "https://$HOST"
        const val EJS_HOST = "cdn.emulatorjs.org"
        const val EJS_VERSION = "4.2.3"
        const val EJS_DATA = "https://$EJS_HOST/$EJS_VERSION/data/"
        private const val MAX_CACHE = 1_500_000_000L
        private const val MAX_BLOB = 700_000_000L
        private const val DEFAULT_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        private fun status(code: Int, reason: String, headers: Map<String, String> = emptyMap()) =
            WebResourceResponse("text/plain", "utf-8", code, reason, headers, ByteArrayInputStream(ByteArray(0)))

        fun safeName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._()\\- ]"), "_").trim().take(120).ifEmpty { "game" }

        /** The name a file is given in the player: the URL's file name, else [fallback]. */
        fun fileName(value: String, fallback: String): String {
            val last = Uri.decode(value.substringBefore('?').substringBefore('#').substringAfterLast('/'))
            return safeName(if (last.contains('.') && !last.startsWith(".")) last else fallback)
        }

        private fun isText(name: String) = name.endsWith(".html") || name.endsWith(".js") || name.endsWith(".css") || name.endsWith(".json")

        private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "wasm" -> "application/wasm"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
