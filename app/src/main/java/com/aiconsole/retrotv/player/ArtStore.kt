package com.aiconsole.retrotv.player

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Which libretro picture: the box, or an in-game screenshot (the attract mode). */
enum class ArtKind(val folder: String) { Box("Named_Boxarts"), Snap("Named_Snaps") }

/** Keeps the art cache under a size limit by dropping the least recently used pictures (pure, unit-tested). */
object CachePolicy {
    /** The size limits Settings offers, in MB. */
    val CAPS_MB = listOf(50, 100, 250, 500)
    const val DEFAULT_CAP_MB = 100

    data class Entry(val name: String, val size: Long, val lastModified: Long)

    /** Names to delete, oldest use first, until what is left fits in [capBytes]; nothing when it already fits. */
    fun evict(entries: List<Entry>, capBytes: Long): List<String> {
        var total = entries.sumOf { it.size.coerceAtLeast(0) }
        val cap = capBytes.coerceAtLeast(0)
        if (total <= cap) return emptyList()
        val out = ArrayList<String>()
        for (e in entries.sortedWith(compareBy<Entry> { it.lastModified }.thenBy { it.name })) {
            if (total <= cap) break
            out += e.name
            total -= e.size.coerceAtLeast(0)
        }
        return out
    }

    /** "38 MB", "512 KB", "0 MB". */
    fun label(bytes: Long): String = when {
        bytes <= 0 -> "0 MB"
        bytes < 1_000_000 -> "${(bytes + 999) / 1000} KB"
        else -> "${bytes / 1_000_000} MB"
    }
}

/**
 * Box art from libretro's thumbnail server ([ArtNames]), cached under `filesDir/art/`. Each
 * console's list of names is downloaded once (refreshed monthly). A title with no match is
 * remembered for a month and a network failure for an hour, so nothing retries in a loop.
 */
class ArtStore(context: Context) {

    private val dir = File(context.filesDir, "art").apply { mkdirs() }
    private val lists = File(dir, "lists").apply { mkdirs() }
    private val prefs = context.applicationContext.getSharedPreferences("art", Context.MODE_PRIVATE)
    private val locks = ConcurrentHashMap<String, Mutex>()

    // Box art keeps its original key; screenshots get their own.
    private fun key(folder: String, title: String, kind: ArtKind) =
        sha1(folder + "|" + ArtNames.normalize(title) + (if (kind == ArtKind.Box) "" else "|" + kind.name)).take(24)

    private fun file(folder: String, title: String, kind: ArtKind) = File(dir, key(folder, title, kind) + ".png")

    /** The art already on the TV, without touching the network. */
    fun cached(core: String, title: String, kind: ArtKind = ArtKind.Box): File? {
        val folder = ArtNames.folder(core) ?: return null
        return file(folder, title, kind).takeIf { it.length() > 0 }?.also { touch(it) }
    }

    // Reading a cover marks it recently used (at most once an hour per file), so eviction drops the unused ones.
    private fun touch(f: File) {
        val now = System.currentTimeMillis()
        if (now - f.lastModified() > HOUR) f.setLastModified(now)
    }

    /** Bytes used by the art folder (covers, screenshots and name lists). */
    fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Size limit for the covers, in MB (one of [CachePolicy.CAPS_MB]). */
    var capMb: Int
        get() = prefs.getInt("cap_mb", CachePolicy.DEFAULT_CAP_MB)
        set(v) = prefs.edit { putInt("cap_mb", v) }

    /** Deletes the least recently used pictures until the cache fits the limit. */
    fun trim() {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }.orEmpty()
        val drop = CachePolicy.evict(files.map { CachePolicy.Entry(it.name, it.length(), it.lastModified()) }, capMb * 1_000_000L).toSet()
        if (drop.isNotEmpty()) files.filter { it.name in drop }.forEach { it.delete() }
    }

    /** Deletes every downloaded picture and name list, and forgets which titles had no art. */
    fun clear() {
        dir.walkBottomUp().filter { it.isFile }.forEach { it.delete() }
        prefs.edit { prefs.all.keys.filter { it.startsWith("miss_") }.forEach { remove(it) } }
    }

    /** The art for a game, downloading it when needed; null when there is none. */
    suspend fun art(core: String, title: String, kind: ArtKind = ArtKind.Box): File? = withContext(Dispatchers.IO) {
        val folder = ArtNames.folder(core) ?: return@withContext null
        val target = file(folder, title, kind)
        if (target.length() > 0) return@withContext target
        val miss = "miss_" + key(folder, title, kind)
        if (System.currentTimeMillis() < prefs.getLong(miss, 0)) return@withContext null
        val names = listing(folder, kind) ?: return@withContext remember(miss, HOUR)
        val name = ArtNames.best(title, names) ?: return@withContext remember(miss, MONTH)
        val bytes = get("$BASE/${encode(folder)}/${kind.folder}/${encode(name)}.png") ?: return@withContext remember(miss, HOUR)
        val part = File(dir, target.name + ".part")
        runCatching { part.writeBytes(bytes) }
        if (part.renameTo(target)) target.also { trim() } else remember(miss, HOUR).also { part.delete() }
    }

    private fun remember(miss: String, forMs: Long): File? {
        prefs.edit { putLong(miss, System.currentTimeMillis() + forMs) }
        return null
    }

    private suspend fun listing(folder: String, kind: ArtKind): List<String>? = locks.getOrPut(folder + kind.name) { Mutex() }.withLock {
        val suffix = if (kind == ArtKind.Box) "" else "_" + kind.name
        val file = File(lists, folder.replace(Regex("[^A-Za-z0-9 -]"), "_") + suffix + ".txt")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < MONTH) return@withLock read(file)
        val names = get("$BASE/${encode(folder)}/${kind.folder}/")?.toString(Charsets.UTF_8)?.let { ArtNames.parseListing(it) }
        when {
            !names.isNullOrEmpty() -> names.also { runCatching { file.writeText(it.joinToString("\n")) } }
            file.isFile -> read(file) // Stale but usable while the server is unreachable.
            else -> null
        }
    }

    private fun read(file: File) = file.readLines().filter { it.isNotBlank() }

    private fun get(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "RetroTV")
        }
        try {
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() } else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val BASE = "https://thumbnails.libretro.com"
        const val HOUR = 3_600_000L
        const val MONTH = 30L * 24 * HOUR
    }
}
