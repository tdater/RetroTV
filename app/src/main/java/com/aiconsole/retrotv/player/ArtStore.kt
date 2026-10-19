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

    private fun key(folder: String, title: String) = sha1(folder + "|" + ArtNames.normalize(title)).take(24)

    private fun file(folder: String, title: String) = File(dir, key(folder, title) + ".png")

    /** The art already on the TV, without touching the network. */
    fun cached(core: String, title: String): File? {
        val folder = ArtNames.folder(core) ?: return null
        return file(folder, title).takeIf { it.length() > 0 }
    }

    /** The art for a game, downloading it when needed; null when there is none. */
    suspend fun art(core: String, title: String): File? = withContext(Dispatchers.IO) {
        val folder = ArtNames.folder(core) ?: return@withContext null
        val target = file(folder, title)
        if (target.length() > 0) return@withContext target
        val miss = "miss_" + key(folder, title)
        if (System.currentTimeMillis() < prefs.getLong(miss, 0)) return@withContext null
        val names = listing(folder) ?: return@withContext remember(miss, HOUR)
        val name = ArtNames.best(title, names) ?: return@withContext remember(miss, MONTH)
        val bytes = get("$BASE/${encode(folder)}/Named_Boxarts/${encode(name)}.png") ?: return@withContext remember(miss, HOUR)
        val part = File(dir, target.name + ".part")
        runCatching { part.writeBytes(bytes) }
        if (part.renameTo(target)) target else remember(miss, HOUR).also { part.delete() }
    }

    private fun remember(miss: String, forMs: Long): File? {
        prefs.edit { putLong(miss, System.currentTimeMillis() + forMs) }
        return null
    }

    private suspend fun listing(folder: String): List<String>? = locks.getOrPut(folder) { Mutex() }.withLock {
        val file = File(lists, folder.replace(Regex("[^A-Za-z0-9 -]"), "_") + ".txt")
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < MONTH) return@withLock read(file)
        val names = get("$BASE/${encode(folder)}/Named_Boxarts/")?.toString(Charsets.UTF_8)?.let { ArtNames.parseListing(it) }
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
