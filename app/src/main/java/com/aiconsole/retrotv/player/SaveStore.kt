package com.aiconsole.retrotv.player

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Save states as real files under `filesDir/saves/<dir>/` ([SaveNames]), so they survive clearing
 * site data and travel with backups. The player page uploads a state in base64 chunks
 * ([open] / [write] / [close]); the old slot is replaced only once the new one arrived whole.
 * The page reads slots back from `/save/<dir>/slot<N>.state` ([GameFiles.serve]).
 */
class SaveStore(context: Context) {

    private val root = rootDir(context.filesDir).apply { mkdirs() }

    private class Upload(val dir: File, val slot: Int, val part: File, val size: Long) {
        var written = 0L
        var failed = false
    }

    private val uploads = ConcurrentHashMap<String, Upload>()

    fun root(): File = root

    private fun dir(gameKey: String) = File(root, SaveNames.dirName(gameKey))

    /** Starts an upload; null when the slot is out of range or the state is too large. */
    fun open(gameKey: String, slot: Int, size: Long): String? {
        if (slot !in SaveNames.AUTO..SaveNames.SLOTS || size <= 0 || size > SaveNames.MAX_STATE) return null
        val dir = dir(gameKey).apply { mkdirs() }
        File(dir, "game.txt").takeIf { !it.exists() }?.writeText(gameKey)
        val token = UUID.randomUUID().toString().replace("-", "")
        val part = File(dir, SaveNames.stem(slot) + ".state.part").apply { delete() }
        uploads[token] = Upload(dir, slot, part, size)
        return token
    }

    fun write(token: String, base64: String): Boolean {
        val up = uploads[token] ?: return false
        synchronized(up) {
            if (up.failed) return false
            val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
            val ok = bytes != null && up.written + bytes.size <= up.size &&
                runCatching { FileOutputStream(up.part, true).use { it.write(bytes) } }.isSuccess
            if (!ok) {
                up.failed = true
                up.part.delete()
                return false
            }
            up.written += bytes!!.size
            return true
        }
    }

    /** Commits the upload (and its screenshot) when every byte arrived; false leaves the old slot as it was. */
    fun close(token: String, pngBase64: String?): Boolean {
        val up = uploads.remove(token) ?: return false
        synchronized(up) {
            val stem = SaveNames.stem(up.slot)
            val target = File(up.dir, "$stem.state")
            // rename(2) replaces the old slot atomically, so a failed save never leaves it half-written.
            if (up.failed || up.written != up.size || !up.part.renameTo(target)) {
                up.part.delete()
                return false
            }
            val png = File(up.dir, "$stem.png")
            val bytes = pngBase64?.takeIf { it.isNotBlank() }?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
            if (bytes != null) runCatching { png.writeBytes(bytes) } else png.delete()
            return true
        }
    }

    fun file(gameKey: String, slot: Int): File? = File(dir(gameKey), SaveNames.stem(slot) + ".state").takeIf { it.isFile }

    fun thumbnail(gameKey: String, slot: Int): File? = File(dir(gameKey), SaveNames.stem(slot) + ".png").takeIf { it.isFile }

    /** When the auto-resume state was saved, or null when there is none. */
    fun autoTime(gameKey: String): Long? = file(gameKey, SaveNames.AUTO)?.lastModified()

    /** Filled slots of a game: slot → time saved. */
    fun slots(gameKey: String): Map<Int, Long> =
        dir(gameKey).listFiles().orEmpty().mapNotNull { f -> SaveNames.slotOf(f.name)?.let { it to f.lastModified() } }.toMap()

    companion object {
        fun rootDir(filesDir: File) = File(filesDir, "saves")
    }
}
