package com.aiconsole.retrotv.link

/** Messages the phone controller page sends over its WebSocket. */
sealed interface PhoneMsg {
    data class Button(val index: Int, val down: Boolean) : PhoneMsg
    data class Stick(val x: Float, val y: Float) : PhoneMsg
    data object Menu : PhoneMsg
    data class Hello(val name: String) : PhoneMsg
}

/** Parses the phone page's messages (pure, unit-tested): `b,<0-16>,<0|1>`, `a,<x>,<y>`, `m`, `h,<name>`. */
object PhoneInput {

    fun parse(text: String): PhoneMsg? {
        val p = text.split(',', limit = 3)
        return when (p[0]) {
            "m" -> PhoneMsg.Menu
            "h" -> PhoneMsg.Hello(p.drop(1).joinToString(",").trim().take(24).ifEmpty { "Phone" })
            "b" -> {
                val i = p.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..16 } ?: return null
                PhoneMsg.Button(i, p.getOrNull(2) == "1")
            }
            "a" -> {
                val x = p.getOrNull(1)?.toFloatOrNull() ?: return null
                val y = p.getOrNull(2)?.toFloatOrNull() ?: return null
                PhoneMsg.Stick(x.coerceIn(-1f, 1f), y.coerceIn(-1f, 1f))
            }
            else -> null
        }
    }
}
