package com.aiconsole.retrotv.player

data class Cheat(val name: String, val code: String, val on: Boolean = false)

/** Reading pasted cheat lists and storing a game's cheats (pure, unit-tested). */
object CheatRules {

    private val ALLOWED = Regex("[0-9A-Za-z:+\\-. ]{4,200}")
    private val GAME_GENIE = Regex("(?i)[APZLGITYEOXUKSVN]{6}|[APZLGITYEOXUKSVN]{8}")
    private val NAMED = Regex("""^(.+?)\s*[:=]\s+(.+)$""")

    /** A cheat code: allowed characters, and either a digit or a Game Genie letter code. */
    fun isCode(s: String): Boolean {
        val t = s.trim()
        return ALLOWED.matches(t) && (t.any { it.isDigit() } || GAME_GENIE.matches(t))
    }

    /** One cheat per line: `Name: CODE`, `Name = CODE`, `Name<tab>CODE`, `CODE Name` or a bare code. */
    fun parse(text: String): List<Cheat> {
        val out = mutableListOf<Cheat>()
        text.split('\n').map { it.trim().trimEnd('\r') }.forEach { line ->
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val cheat = when {
                '\t' in line -> line.split('\t', limit = 2).let { (a, b) -> named(a, b) ?: named(b, a) }
                // "Name: CODE" first: a whole named line can look like a code ("Lives: 00C-5D2").
                NAMED.matchEntire(line) != null -> NAMED.matchEntire(line)!!.let { m -> named(m.groupValues[1], m.groupValues[2]) }
                isCode(line) -> Cheat("", line)
                else -> line.split(' ', limit = 2).takeIf { it.size == 2 && isCode(it[0]) }?.let { Cheat(it[1].trim(), it[0]) }
            } ?: return@forEach
            out += cheat.copy(name = cheat.name.ifBlank { "Cheat ${out.size + 1}" })
        }
        return out
    }

    private fun named(name: String, code: String): Cheat? = if (isCode(code)) Cheat(name.trim(), code.trim()) else null

    fun encode(list: List<Cheat>): String = list.joinToString("\n") { c ->
        clean(c.name) + "\t" + clean(c.code) + "\t" + (if (c.on) "1" else "0")
    }

    fun decode(s: String): List<Cheat> = s.split('\n').mapNotNull { line ->
        val p = line.split('\t')
        if (p.size == 3 && p[1].isNotEmpty()) Cheat(p[0], p[1], p[2] == "1") else null
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
