package com.aiconsole.retrotv.link

import android.content.Context
import com.aiconsole.retrotv.RetroApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

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
            else -> ex.text("Not found", 404)
        }
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
