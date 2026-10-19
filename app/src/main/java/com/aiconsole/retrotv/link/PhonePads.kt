package com.aiconsole.retrotv.link

import com.aiconsole.retrotv.browser.VirtualPads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phones as controllers: each phone's WebSocket becomes a virtual pad in whichever [VirtualPads]
 * is on screen ([target], set by the browser and the player). A phone that disconnects has its
 * pad removed, so nothing stays pressed.
 */
object PhonePads {

    @Volatile var target: VirtualPads? = null

    /** Opens the current screen's menu (called on a socket thread; post to the main thread). */
    @Volatile var onMenu: (() -> Unit)? = null

    private val counter = AtomicInteger(0)
    private val _connected = MutableStateFlow(0)
    val connected: StateFlow<Int> = _connected.asStateFlow()

    fun attach(ws: LinkSocket) {
        val id = -100 - counter.getAndIncrement()
        var name = "Phone"
        _connected.value++
        ws.send("s,Connected · press Start on the TV's game to play")
        ws.onMessage = { text ->
            when (val m = PhoneInput.parse(text)) {
                is PhoneMsg.Hello -> name = "Phone (${m.name})"
                is PhoneMsg.Button -> if (target?.virtualKey(id, name, m.index, if (m.down) 1f else 0f) == false && m.down) {
                    ws.send("s,All 4 players are taken")
                }
                is PhoneMsg.Stick -> target?.virtualAxes(id, name, m.x, m.y)
                PhoneMsg.Menu -> onMenu?.invoke()
                null -> Unit
            }
        }
        val previous = ws.onClose
        ws.onClose = {
            target?.forget(id)
            _connected.value = (_connected.value - 1).coerceAtLeast(0)
            previous()
        }
    }
}
