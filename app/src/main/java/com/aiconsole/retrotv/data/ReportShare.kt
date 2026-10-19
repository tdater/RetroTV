package com.aiconsole.retrotv.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pure helpers for [ReportShare] (unit-tested). */
object ReportShareRules {

    /**
     * The address a phone on the same Wi-Fi or hotspot can reach: a private IPv4 address,
     * preferring Wi-Fi and hotspot interfaces over others (mobile data, VPN).
     */
    fun pickAddress(candidates: List<Pair<String, String>>): String? {
        fun private(ip: String) = ip.startsWith("192.168.") || ip.startsWith("10.") ||
            Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(ip)
        val usable = candidates.filter { (_, ip) -> private(ip) }
        return (usable.firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") } ?: usable.firstOrNull())?.second
    }

    fun httpHeader(status: String, contentType: String, length: Long, fileName: String?): String = buildString {
        append("HTTP/1.1 $status\r\n")
        append("Content-Type: $contentType\r\n")
        append("Content-Length: $length\r\n")
        fileName?.let { append("Content-Disposition: attachment; filename=\"$it\"\r\n") }
        append("Connection: close\r\n\r\n")
    }

    /** The request path from an HTTP request line ("GET /abc HTTP/1.1"), or null. */
    fun requestPath(line: String?): String? = line?.split(' ')?.takeIf { it.size >= 2 && it[0] == "GET" }?.get(1)
}

/**
 * Getting a problem report off the TV without a USB stick: Android's share sheet
 * (Bluetooth, Nearby Share, email), or a QR code a phone on the same Wi-Fi or hotspot scans to
 * download it from a tiny one-file web server here. The server only answers a random,
 * unguessable link, serves nothing else, and shuts down after 10 minutes.
 */
class ReportShare(private val context: Context, private val scope: CoroutineScope) {

    private var server: ServerSocket? = null
    private var job: Job? = null

    /** Writes a fresh report to the cache. Call off the main thread. */
    fun writeReport(): File {
        val dir = File(context.cacheDir, "reports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "retrotv-report-${System.currentTimeMillis() / 1000}.txt")
        file.outputStream().use { ProblemReport.write(context, it) }
        return file
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, file.name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Send the report"
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Serves [file] and returns the link for the QR code, or null without a Wi-Fi/hotspot address. */
    suspend fun serve(file: File): String? = withContext(Dispatchers.IO) {
        stop()
        val address = localAddress() ?: return@withContext null
        val token = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val path = "/$token/${file.name}"
        val socket = try {
            ServerSocket().apply { bind(InetSocketAddress(address, 0)) }
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't open the report server", e)
            return@withContext null
        }
        server = socket
        job = scope.launch(Dispatchers.IO) {
            launch {
                delay(LIFETIME_MS)
                stop()
            }
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                }
                client.use { c ->
                    c.soTimeout = 10_000
                    val requested = ReportShareRules.requestPath(c.getInputStream().bufferedReader().readLine())
                    val out = c.getOutputStream()
                    if (requested == path && file.exists()) {
                        out.write(ReportShareRules.httpHeader("200 OK", "text/plain; charset=utf-8", file.length(), file.name).toByteArray())
                        file.inputStream().use { it.copyTo(out) }
                    } else {
                        val body = "Not found".toByteArray()
                        out.write(ReportShareRules.httpHeader("404 Not Found", "text/plain", body.size.toLong(), null).toByteArray())
                        out.write(body)
                    }
                    out.flush()
                }
            }
        }
        "http://$address:${socket.localPort}$path"
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        job?.cancel()
        job = null
    }

    private fun localAddress(): String? = try {
        val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nic.name to (it.hostAddress ?: "") } }
        ReportShareRules.pickAddress(candidates)
    } catch (e: SocketException) {
        null
    }

    private companion object {
        const val TAG = "ReportShare"
        const val LIFETIME_MS = 10 * 60_000L
    }
}
