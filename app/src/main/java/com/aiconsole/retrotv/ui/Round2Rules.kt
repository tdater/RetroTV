package com.aiconsole.retrotv.ui

/** A user-made list of games ("Couch co-op", "Kids"), by [com.aiconsole.retrotv.player.GameRequest.key]. */
data class GameCollection(val id: String, val name: String, val keys: List<String> = emptyList())

/** Create, rename, fill and save game collections (pure, unit-tested). Every function returns the new list. */
object CollectionRules {
    const val MAX_COLLECTIONS = 20
    const val MAX_GAMES = 200
    const val MAX_NAME = 30

    /** Whitespace collapsed, trimmed, cut to [MAX_NAME]. */
    fun cleanName(raw: String): String = raw.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim()

    /** Adds an empty collection; unchanged for a blank or already used name (any case) or at the cap. */
    fun create(list: List<GameCollection>, name: String): List<GameCollection> {
        val n = cleanName(name)
        if (n.isEmpty() || list.size >= MAX_COLLECTIONS || list.any { it.name.equals(n, ignoreCase = true) }) return list
        val id = ((list.maxOfOrNull { it.id.toIntOrNull() ?: 0 } ?: 0) + 1).toString()
        return list + GameCollection(id, n)
    }

    fun rename(list: List<GameCollection>, id: String, name: String): List<GameCollection> {
        val n = cleanName(name)
        if (n.isEmpty() || list.none { it.id == id } || list.any { it.id != id && it.name.equals(n, ignoreCase = true) }) return list
        return list.map { if (it.id == id) it.copy(name = n) else it }
    }

    fun delete(list: List<GameCollection>, id: String): List<GameCollection> = list.filter { it.id != id }

    /** Appends the game to the end; unchanged when it is already in, blank or the collection is full. */
    fun add(list: List<GameCollection>, id: String, key: String): List<GameCollection> =
        list.map { c -> if (c.id == id && key.isNotBlank() && key !in c.keys && c.keys.size < MAX_GAMES) c.copy(keys = c.keys + key) else c }

    fun remove(list: List<GameCollection>, id: String, key: String): List<GameCollection> =
        list.map { c -> if (c.id == id) c.copy(keys = c.keys - key) else c }

    fun toggle(list: List<GameCollection>, id: String, key: String): List<GameCollection> =
        if (list.firstOrNull { it.id == id }?.keys?.contains(key) == true) remove(list, id, key) else add(list, id, key)

    fun containing(list: List<GameCollection>, key: String): List<GameCollection> = list.filter { key in it.keys }

    /** The items of [all] that are in the collection, in the collection's order; games gone from [all] are skipped. */
    fun <T> resolve(c: GameCollection, all: List<T>, key: (T) -> String): List<T> {
        val byKey = all.associateBy(key)
        return c.keys.mapNotNull { byKey[it] }
    }

    /** The collections that have at least one playable game (the home screen shows only these). */
    fun <T> nonEmpty(list: List<GameCollection>, all: List<T>, key: (T) -> String): List<GameCollection> {
        val known = all.mapTo(HashSet(), key)
        return list.filter { c -> c.keys.any { it in known } }
    }

    // One collection per line, fields separated by tabs: id, name, then the game keys.
    fun serialize(list: List<GameCollection>): String =
        list.joinToString("\n") { c -> (listOf(c.id, c.name) + c.keys).joinToString("\t") { escape(it) } }

    fun parse(text: String?): List<GameCollection> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split('\n').mapNotNull { line ->
            val f = line.split('\t').map { unescape(it) }
            if (f.size < 2 || f[0].isEmpty() || f[1].isEmpty()) null
            else GameCollection(f[0], f[1], f.drop(2).filter { it.isNotEmpty() }.distinct().take(MAX_GAMES))
        }.distinctBy { it.id }.take(MAX_COLLECTIONS)
    }

    private fun escape(s: String): String = buildString {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            '\t' -> append("\\t")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    '\\' -> append('\\')
                    else -> append(ch).append(s[i + 1])
                }
                i += 2
            } else {
                append(ch)
                i++
            }
        }
    }
}

/** Which home-screen rows show and in what order (pure, unit-tested). */
object HomeLayout {
    const val HERO = "hero"
    const val STATS = "stats"
    const val RECENT = "recent"
    const val VISITED = "visited"
    const val COLLECTIONS = "collections"
    const val SITES = "sites"

    val DEFAULT_ORDER = listOf(HERO, STATS, RECENT, VISITED, COLLECTIONS, SITES)

    data class Row(val id: String, val visible: Boolean = true)

    fun label(id: String): String = when (id) {
        HERO -> "Continue playing"
        STATS -> "Play stats"
        RECENT -> "Recent games"
        VISITED -> "Recently visited"
        COLLECTIONS -> "Collections"
        SITES -> "Game sites"
        else -> id
    }

    fun default(): List<Row> = DEFAULT_ORDER.map { Row(it) }

    /**
     * Drops unknown and repeated rows; a row that is missing (new in an update) is added, visible,
     * right after the row that precedes it in the default order.
     */
    fun normalize(rows: List<Row>): List<Row> {
        val out = rows.filter { it.id in DEFAULT_ORDER }.distinctBy { it.id }.toMutableList()
        DEFAULT_ORDER.forEachIndexed { i, id ->
            if (out.none { it.id == id }) {
                val before = if (i == 0) -1 else out.indexOfFirst { it.id == DEFAULT_ORDER[i - 1] }
                out.add(before + 1, Row(id))
            }
        }
        return out
    }

    fun toggle(rows: List<Row>, id: String): List<Row> = rows.map { if (it.id == id) it.copy(visible = !it.visible) else it }

    fun moveUp(rows: List<Row>, id: String): List<Row> = move(rows, id, -1)

    fun moveDown(rows: List<Row>, id: String): List<Row> = move(rows, id, 1)

    private fun move(rows: List<Row>, id: String, by: Int): List<Row> {
        val from = rows.indexOfFirst { it.id == id }
        val to = from + by
        if (from < 0 || to !in rows.indices) return rows
        return rows.toMutableList().also { java.util.Collections.swap(it, from, to) }
    }

    /** The ids to draw, in order. */
    fun visibleIds(rows: List<Row>): List<String> = rows.filter { it.visible }.map { it.id }

    /** "hero:1,stats:0,…" */
    fun serialize(rows: List<Row>): String = rows.joinToString(",") { it.id + ":" + (if (it.visible) "1" else "0") }

    fun parse(text: String?): List<Row> {
        if (text.isNullOrBlank()) return default()
        val rows = text.split(',').mapNotNull { part ->
            val id = part.substringBefore(':').trim()
            if (id.isEmpty()) null else Row(id, part.substringAfter(':', "1").trim() != "0")
        }
        return normalize(rows)
    }
}

/** Which pictures the attract mode may show for a game besides its box art. */
object AttractShots {
    /** The saved-state screenshot to use on [round]: rotates through the game's shots; null when it has none. */
    fun pick(shots: List<String>?, round: Int): String? = shots?.takeIf { it.isNotEmpty() }?.get(Math.floorMod(round, shots.size))
}
