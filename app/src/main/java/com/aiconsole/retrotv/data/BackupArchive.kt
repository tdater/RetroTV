package com.aiconsole.retrotv.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The backup file: a zip of `backup.json` ([Backup]) plus every save under `saves/` ([com.aiconsole.retrotv.player.SaveStore]).
 * Older backups are plain JSON; [isZip] tells them apart.
 */
object BackupArchive {

    private const val JSON = "backup.json"
    private const val SAVES = "saves/"

    fun write(out: OutputStream, json: String, savesRoot: File) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(JSON))
            zip.write(json.toByteArray())
            zip.closeEntry()
            savesRoot.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") }.forEach { f ->
                zip.putNextEntry(ZipEntry(SAVES + f.relativeTo(savesRoot).invariantSeparatorsPath).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /**
     * Restores the saves into [savesRoot] and returns the settings JSON. A save already on the TV
     * that is newer than the backup's copy is kept; entries outside `saves/` are ignored.
     */
    fun read(input: InputStream, savesRoot: File): String {
        var json = "{}"
        val root = savesRoot.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                when {
                    entry.isDirectory -> Unit
                    name == JSON -> json = zip.readBytes().toString(Charsets.UTF_8)
                    name.startsWith(SAVES) -> {
                        val rel = name.removePrefix(SAVES)
                        val target = File(root, rel).canonicalFile
                        val safe = rel.isNotEmpty() && !rel.contains("..") && target.path.startsWith(root.path + File.separator)
                        if (safe && !(target.exists() && entry.time > 0 && target.lastModified() > entry.time)) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                            if (entry.time > 0) target.setLastModified(entry.time)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return json
    }

    fun isZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()
}
