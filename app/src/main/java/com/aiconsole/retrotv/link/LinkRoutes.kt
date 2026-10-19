package com.aiconsole.retrotv.link

import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList

/** What the phone link answers (paths are relative to the token). */
class LinkRoutes(private val context: Context, private val server: LinkServer) {

    private val sockets = CopyOnWriteArrayList<LinkSocket>()

    fun handle(ex: Exchange) {
        when {
            ex.request.method == "GET" && (ex.rel == "/" || ex.rel == "/index.html") ->
                ex.send(200, "text/html; charset=utf-8", context.assets.open("phone/index.html").use { it.readBytes() })
            else -> ex.text("Not found", 404)
        }
    }

    fun socket(rel: String, ws: LinkSocket) {
        sockets += ws
        val previous = ws.onClose
        ws.onClose = { sockets -= ws; previous() }
    }

    fun hasSockets() = sockets.isNotEmpty()

    fun closeAll() {
        sockets.forEach { it.close() }
        sockets.clear()
    }
}
