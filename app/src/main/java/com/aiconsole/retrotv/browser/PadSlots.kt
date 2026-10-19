package com.aiconsole.retrotv.browser

/** Which controller is which player (pure, unit-tested). Maps are deviceId → slot 0–3. */
object PadSlots {

    /** [device] becomes player [slot]; whoever had it takes the device's old slot, or none. */
    fun claim(slots: Map<Int, Int>, device: Int, slot: Int): Map<Int, Int> {
        if (slot !in 0..3) return slots
        val out = slots.toMutableMap()
        val old = out[device]
        val holder = out.entries.firstOrNull { it.value == slot && it.key != device }?.key
        out[device] = slot
        if (holder != null) {
            if (old != null) out[holder] = old else out.remove(holder)
        }
        return out
    }
}
