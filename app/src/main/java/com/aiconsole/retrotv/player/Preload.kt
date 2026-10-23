package com.aiconsole.retrotv.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import com.aiconsole.retrotv.RetroApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** What the library says about one game, reduced to what [PreloadRules] needs. */
data class CoreUsage(val core: String, val plays: Int, val playMs: Long)

/** Pure rules for "preload the emulators of your most-played consoles" (unit-tested). */
object PreloadRules {
    const val INTERVAL_MS = 12L * 60 * 60 * 1000
    const val RETRY_MS = 60L * 60 * 1000
    const val MAX_CORES = 2
    const val RUN_CAP_BYTES = 150L * 1000 * 1000
    const val MIN_FREE_BYTES = 500L * 1000 * 1000

    enum class Skip { Off, Recent, Metered, LowStorage }

    /** Why a run shouldn't start now, or null when it may. */
    fun skipReason(enabled: Boolean, unmetered: Boolean, now: Long, lastRun: Long, freeBytes: Long): Skip? = when {
        !enabled -> Skip.Off
        lastRun > 0 && now - lastRun in 0 until INTERVAL_MS -> Skip.Recent
        !unmetered -> Skip.Metered
        freeBytes < MIN_FREE_BYTES -> Skip.LowStorage
        else -> null
    }

    /**
     * The (at most [max]) consoles played the most, by play time then play count, as the first core
     * string seen for each. Consoles never actually played (no time, no plays) are left out.
     */
    fun topCores(usage: List<CoreUsage>, max: Int = MAX_CORES): List<String> {
        val byCore = LinkedHashMap<String, CoreUsage>()
        usage.forEach { u ->
            val name = CoreFiles.coreName(u.core)
            val old = byCore[name]
            byCore[name] = if (old == null) u else old.copy(plays = old.plays + u.plays, playMs = old.playMs + u.playMs)
        }
        return byCore.values.filter { it.plays > 0 || it.playMs > 0 }
            .sortedWith(compareByDescending<CoreUsage> { it.playMs }.thenByDescending { it.plays })
            .take(max).map { c -> usage.first { CoreFiles.coreName(it.core) == CoreFiles.coreName(c.core) }.core }
    }

    /** True when another core may still be downloaded after [downloaded] bytes this run. */
    fun mayContinue(downloaded: Long, freeBytes: Long): Boolean = downloaded < RUN_CAP_BYTES && freeBytes >= MIN_FREE_BYTES

    fun size(bytes: Long): String = if (bytes >= 1_000_000_000L) "%.1f GB".format(Locale.US, bytes / 1e9) else "%.0f MB".format(Locale.US, bytes / 1e6)

    /** The line shown in Settings. */
    fun status(enabled: Boolean, lastNames: List<String>, lastBytes: Long, waiting: Skip?, failed: Boolean = false): String = when {
        !enabled -> "Off"
        waiting == Skip.Metered -> "Waiting for Wi-Fi"
        waiting == Skip.LowStorage -> "Paused: low storage"
        failed -> "Couldn't finish, will try again later"
        lastNames.isEmpty() -> "Nothing yet: play a few games first"
        lastBytes <= 0 -> "Ready: " + lastNames.joinToString(", ")
        else -> "Preloaded: " + lastNames.joinToString(", ") + " · " + size(lastBytes)
    }
}

/**
 * Downloads the emulators of the most-played consoles in the background, on unmetered networks, at
 * most every 12 hours (see [PreloadRules]). Started from the home screen; uses [GameFiles.prefetch].
 */
class Preload private constructor(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("preload", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) {
            prefs.edit { putBoolean("enabled", v) }
            refreshStatus()
            if (v) start()
        }

    init { refreshStatus() }

    private fun names() = prefs.getString("names", "").orEmpty().split('|').filter { it.isNotBlank() }

    fun refreshStatus(failed: Boolean = false) {
        val waiting = PreloadRules.skipReason(true, isUnmetered(), 0, 0, freeBytes()).takeIf { it == PreloadRules.Skip.Metered || it == PreloadRules.Skip.LowStorage }
        _status.value = PreloadRules.status(enabled, names(), prefs.getLong("bytes", 0), waiting, failed)
    }

    /** Runs in the background if the rules allow it; safe to call often. */
    fun start() {
        scope.launch { runOnce() }
    }

    private fun freeBytes(): Long = app.filesDir.usableSpace

    private fun isUnmetered(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    private fun runOnce() {
        if (!running.compareAndSet(false, true)) return
        try {
            val now = System.currentTimeMillis()
            val skip = PreloadRules.skipReason(enabled, isUnmetered(), now, prefs.getLong("last", 0), freeBytes())
            if (skip != null) { refreshStatus(); return }
            val retro = RetroApp.from(app)
            val usage = retro.library.recent.value.map { CoreUsage(it.request.core, it.plays, it.playMs) }
            val targets = PreloadRules.topCores(usage)
            if (targets.isEmpty()) { refreshStatus(); return }
            var downloaded = 0L
            var failed = false
            for (core in targets) {
                if (retro.games.isOffline(core)) continue
                if (!PreloadRules.mayContinue(downloaded, freeBytes())) break
                _status.value = "Preloading " + label(core) + "…"
                val before = retro.games.emulatorSize()
                val ok = try {
                    retro.games.prefetch(core) { _, _ -> }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                downloaded += (retro.games.emulatorSize() - before).coerceAtLeast(0)
                if (!ok) { failed = true; break }
            }
            prefs.edit {
                // A failed run is retried after an hour rather than 12.
                putLong("last", if (failed) now - PreloadRules.INTERVAL_MS + PreloadRules.RETRY_MS else now)
                putString("names", targets.joinToString("|") { label(it) })
                putLong("bytes", downloaded)
            }
            refreshStatus(failed)
        } finally {
            running.set(false)
        }
    }

    private fun label(core: String) = Systems.byId(core)?.label ?: core

    companion object {
        @Volatile private var instance: Preload? = null
        fun from(context: Context): Preload = instance ?: synchronized(this) { instance ?: Preload(context).also { instance = it } }
    }
}
