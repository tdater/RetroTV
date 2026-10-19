package com.aiconsole.retrotv.data

import android.content.Context
import android.os.SystemClock
import android.webkit.WebSettings
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

enum class Health { Up, Slow, Down, Unknown }

/** When a site counts as up, slow or down (pure, unit-tested). */
object SiteHealth {
    const val SLOW_MS = 4_000L
    const val INTERVAL_MS = 6 * 3_600_000L

    /** [code] null means no connection or a timeout. Challenge pages (401/403/429/503) mean the server answered. */
    fun classify(code: Int?, ms: Long): Health = when {
        code == null -> Health.Down
        code in 200..399 -> if (ms >= SLOW_MS) Health.Slow else Health.Up
        code == 401 || code == 403 || code == 429 || code == 503 -> Health.Up
        else -> Health.Down
    }

    fun due(lastCheck: Long, now: Long): Boolean = now - lastCheck >= INTERVAL_MS
}

/** Checks the game sites in the background (at most every 6 h) so dead ones are marked on the home screen. */
class SiteChecker(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("health", Context.MODE_PRIVATE)
    private val _results = MutableStateFlow(load())
    val results: StateFlow<Map<String, Health>> = _results.asStateFlow()

    private fun load(): Map<String, Health> = prefs.all.mapNotNull { (k, v) ->
        if (k == LAST) null else Health.entries.firstOrNull { it.name == v }?.let { k to it }
    }.toMap()

    fun isDue(): Boolean = SiteHealth.due(prefs.getLong(LAST, 0), System.currentTimeMillis())

    suspend fun checkAll(sites: List<Site>) = withContext(Dispatchers.IO) {
        val ua = runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrDefault("Mozilla/5.0")
        val gate = Semaphore(4)
        val found = coroutineScope {
            sites.distinctBy { it.host }.map { site -> async { gate.withPermit { site.host to check(site.url, ua) } } }.awaitAll()
        }.toMap()
        _results.value = found
        prefs.edit {
            clear()
            found.forEach { (host, h) -> putString(host, h.name) }
            putLong(LAST, System.currentTimeMillis())
        }
    }

    private fun check(url: String, ua: String): Health {
        val start = SystemClock.elapsedRealtime()
        val code = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", ua)
            }
            try {
                conn.responseCode.also { runCatching { (conn.errorStream ?: conn.inputStream)?.use { it.read() } } }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        return SiteHealth.classify(code, SystemClock.elapsedRealtime() - start)
    }

    private companion object {
        const val LAST = "_last"
    }
}
