package com.aiconsole.retrotv.data

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash capture and the "Report a problem" file: device and app details, recent crashes and
 * freezes, settings, storage use and this app's own recent log output. Built for a TV where adb
 * isn't available. Everything written goes through [redact], so no site addresses with their
 * query strings, tokens or cookies end up in a report.
 */
object ProblemReport {

    private const val MAX_CRASHES = 5

    private val URL_TAIL = Regex("""(https?://[^\s?#"'<>]+)[?#][^\s"'<>]*""")
    private val COOKIE = Regex("""(?i)(cookie\s*[:=]\s*)[^\n]*""")
    private val SECRET = Regex("""(?i)\b(token|key|password|passwd|auth|sid|session)=([^&\s;"']+)""")

    /** Strips URL query strings and fragments, cookie values and `token=`-style secrets. */
    fun redact(text: String): String =
        text.replace(URL_TAIL, "$1").replace(COOKIE, "$1[removed]").replace(SECRET, "$1=[removed]")

    private fun crashDir(context: Context) = File(context.filesDir, "crashes")

    /** Saves uncaught exceptions to a file before the process dies, then lets Android handle them. */
    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                save(appContext, "Thread: ${thread.name}\n$trace")
            } catch (_: Throwable) {
                // Never let crash logging hide the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Saves a main-thread freeze (see [FreezeWatchdog]) next to the crashes. */
    fun recordFreeze(context: Context, details: String) {
        try {
            save(context.applicationContext, "FREEZE (app not responding)\n$details")
        } catch (_: Throwable) {
            // Best effort only.
        }
    }

    private fun save(context: Context, text: String) {
        val dir = crashDir(context).apply { mkdirs() }
        File(dir, "crash-${System.currentTimeMillis()}.txt").writeText(text)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_CRASHES)?.forEach { it.delete() }
    }

    fun crashCount(context: Context): Int = crashDir(context).listFiles()?.size ?: 0

    /**
     * Writes the report section by section, flushing as it goes, so a failure in one part (or the
     * slow log read) can never leave an empty file.
     */
    fun write(context: Context, out: OutputStream) {
        val writer = out.bufferedWriter(Charsets.UTF_8)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
        fun section(title: String, body: StringBuilder.() -> Unit) {
            val text = try {
                buildString(body)
            } catch (e: Throwable) {
                "(section failed: ${e.javaClass.simpleName}: ${e.message})\n"
            }
            writer.write("== $title ==\n")
            writer.write(redact(text))
            writer.write("\n")
            writer.flush()
        }
        section("RETROTV PROBLEM REPORT") {
            appendLine("Created: ${stamp.format(Date())}")
            val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            appendLine("App: ${context.packageName} ${pkg?.versionName ?: "?"}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Build: ${Build.FINGERPRINT}")
            val webView = runCatching { androidx.webkit.WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
            appendLine("WebView: ${webView?.packageName ?: "?"} ${webView?.versionName ?: "?"}")
            appendLine("Last on screen: ${FreezeWatchdog.context.ifBlank { "home" }}")
        }
        section("Recent crashes and freezes") {
            val crashes = crashDir(context).listFiles()?.sortedByDescending { it.name }.orEmpty()
            if (crashes.isEmpty()) appendLine("None recorded.")
            crashes.forEach { f ->
                val at = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull()
                appendLine("-- ${at?.let { stamp.format(Date(it)) } ?: f.name}")
                appendLine(f.readText().take(8_000))
            }
        }
        section("Storage") {
            fun size(dir: File) = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_048_576
            appendLine("Saves: ${size(File(context.filesDir, "saves"))} MB")
            appendLine("Game files: ${size(File(context.filesDir, "games"))} MB")
            appendLine("Emulator files: ${size(File(context.filesDir, "emulatorjs")) + size(File(context.cacheDir, "emulatorjs"))} MB")
            appendLine("Free on device: ${context.filesDir.usableSpace / 1_048_576} MB")
        }
        section("Settings") {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE).all.toSortedMap().forEach { (k, v) ->
                appendLine("$k = ${v.toString().replace('\n', ' ').take(200)}")
            }
        }
        section("App log (this app only)") { append(appLog()) }
    }

    /** This process's recent logcat output; apps may read their own logs without extra permission. */
    private fun appLog(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", "3000", "--pid=${Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().useLines { lines ->
            lines.filterNot { "Davey! duration=" in it }.joinToString("\n")
        }
        process.waitFor()
        output.ifBlank { "(empty)\n" }
    } catch (e: IOException) {
        "(couldn't read the log: ${e.message})\n"
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        "(interrupted)\n"
    }
}
