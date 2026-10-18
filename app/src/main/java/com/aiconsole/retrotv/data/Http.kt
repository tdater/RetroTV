package com.aiconsole.retrotv.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Tiny blocking HTTP GET for JSON and userscripts. Call from Dispatchers.IO. */
object Http {

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36 RetroTV"

    fun get(url: String, maxBytes: Int = 2_000_000, accept: String = "*/*"): String {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", accept)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) throw IOException("Response is too large")
                    }
                    return out.toString("UTF-8")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }
}
