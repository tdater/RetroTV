package com.aiconsole.retrotv

import com.aiconsole.retrotv.data.BackupArchive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {

    private fun tempDir(): File = Files.createTempDirectory("rtv").toFile()

    @Test
    fun roundTripsSettingsAndSaves() {
        val from = tempDir()
        val state = byteArrayOf(1, 2, 3, 4, 5)
        File(from, "abc").mkdirs()
        File(from, "abc/slot1.state").writeBytes(state)
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, """{"format":"retrotv-backup-1"}""", from)

        val to = tempDir()
        val json = BackupArchive.read(ByteArrayInputStream(out.toByteArray()), to)
        assertEquals("""{"format":"retrotv-backup-1"}""", json)
        assertArrayEquals(state, File(to, "abc/slot1.state").readBytes())
    }

    @Test
    fun entriesOutsideSavesAreIgnored() {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("backup.json")); z.write("{}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("saves/../evil")); z.write(byteArrayOf(9)); z.closeEntry()
            z.putNextEntry(ZipEntry("other/x")); z.write(byteArrayOf(9)); z.closeEntry()
        }
        val parent = tempDir()
        val to = File(parent, "saves").apply { mkdirs() }
        BackupArchive.read(ByteArrayInputStream(zip.toByteArray()), to)
        assertFalse(File(parent, "evil").exists())
        assertFalse(File(to, "other").exists())
        assertEquals(0, to.listFiles()!!.size)
    }

    @Test
    fun recognisesZipFiles() {
        val out = ByteArrayOutputStream()
        BackupArchive.write(out, "{}", tempDir())
        assertTrue(BackupArchive.isZip(out.toByteArray()))
        assertFalse(BackupArchive.isZip("{".toByteArray()))
        assertFalse(BackupArchive.isZip(ByteArray(0)))
    }
}
