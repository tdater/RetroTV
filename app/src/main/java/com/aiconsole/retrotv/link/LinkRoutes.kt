package com.aiconsole.retrotv.link

import android.content.Context
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.data.Backup
import com.aiconsole.retrotv.data.BackupArchive
import com.aiconsole.retrotv.player.SaveNames
import com.aiconsole.retrotv.web.AdBlock
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

private const val SaveNamesLimit = SaveNames.MAX_STATE

/** A game file sent from the phone: its name and its `file:` value for the player. */
data class ReceivedRom(val name: String, val value: String)

/** What the phone link answers (paths are relative to the token). */
class LinkRoutes(private val context: Context, private val server: LinkServer) {

    private val sockets = CopyOnWriteArrayList<LinkSocket>()
    private val app get() = RetroApp.from(context)
    private val _received = MutableStateFlow(emptyList<ReceivedRom>())
    val received: StateFlow<List<ReceivedRom>> = _received.asStateFlow()

    fun handle(ex: Exchange) {
        when {
            ex.request.method == "GET" && (ex.rel == "/" || ex.rel == "/index.html") ->
                ex.send(200, "text/html; charset=utf-8", context.assets.open("phone/index.html").use { it.readBytes() })
            ex.request.method == "POST" && ex.rel == "/upload" -> upload(ex)
            ex.request.method == "GET" && ex.rel == "/saves/manifest" -> ex.text(SyncRules.encode(SaveSync.localManifest(app.saves.root())))
            ex.request.method == "GET" && ex.rel == "/saves/file" -> {
                val path = ex.request.query["p"].orEmpty()
                val file = File(app.saves.root(), path)
                if (SyncRules.isValid(path) && file.isFile) ex.file(file, "application/octet-stream") else ex.text("Not found", 404)
            }
            ex.request.method == "POST" && ex.rel == "/saves/file" -> {
                val path = ex.request.query["p"].orEmpty()
                val t = ex.request.query["t"]?.toLongOrNull() ?: System.currentTimeMillis()
                if (!SyncRules.isValid(path) || ex.contentLength !in 0..SaveNamesLimit) return ex.text("Not a save", 400)
                SaveSync.writeFile(app.saves.root(), path, t) { out -> ex.body().copyTo(out) }
                ex.text("Saved")
            }
            // Backups: the phone only (the token), never just the sync code.
            phone(ex) && ex.request.method == "GET" && ex.rel == "/backup.zip" -> ex.stream("application/zip", "retrotv-backup.zip") { out ->
                val urls = app.userscripts.scripts.value.mapNotNull { it.sourceUrl }
                BackupArchive.write(out, Backup.export(app.settings.value, app.sites.custom.value, urls), app.saves.root())
            }
            phone(ex) && ex.request.method == "POST" && ex.rel == "/restore" -> restore(ex)
            else -> ex.text("Not found", 404)
        }
    }

    private fun phone(ex: Exchange) = ex.request.path.startsWith("/${server.token}/")

    private fun restore(ex: Exchange) {
        val result = try {
            val input = ex.body().buffered()
            input.mark(4)
            val head = ByteArray(4).also { input.read(it) }
            input.reset()
            val json = if (BackupArchive.isZip(head)) BackupArchive.read(input, app.saves.root()) else input.readBytes().toString(Charsets.UTF_8)
            val restored = Backup.import(json, app.settings.value)
            app.settings.update { restored.prefs }
            AdBlock.setCustom(restored.prefs.customBlockList)
            restored.sites.forEach { (name, url) -> app.sites.add(name, url) }
            val scripts = runBlocking {
                restored.scriptUrls.count { url -> runCatching { app.userscripts.install(app.userscripts.fetch(url), url) }.isSuccess }
            }
            "Restored saves, ${restored.sites.size} sites and $scripts scripts"
        } catch (e: Exception) {
            return ex.text("Restore failed: ${e.message}", 400)
        }
        ex.text(result)
    }

    private fun upload(ex: Exchange) {
        val name = UploadRules.safeName(ex.request.query["name"].orEmpty())
        UploadRules.check(name, ex.contentLength)?.let { return ex.text(it, 400) }
        val value = try {
            app.games.receiveRom(ex.body(), name, ex.contentLength)
        } catch (e: Exception) {
            return ex.text(e.message ?: "The upload failed", 400)
        }
        _received.value = listOf(ReceivedRom(name, value)) + _received.value
        ex.text("On the TV: open Phone link there and press Play")
    }

    fun socket(rel: String, ws: LinkSocket) {
        sockets += ws
        val previous = ws.onClose
        ws.onClose = { sockets -= ws; previous() }
        if (rel == "/ws") PhonePads.attach(ws) else ws.close()
    }

    fun hasSockets() = sockets.isNotEmpty()

    fun closeAll() {
        sockets.forEach { it.close() }
        sockets.clear()
    }
}
