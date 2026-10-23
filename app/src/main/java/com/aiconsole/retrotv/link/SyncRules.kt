package com.aiconsole.retrotv.link

import kotlin.math.abs

/** One save file, path relative to the saves folder (`<24 hex>/slot1.state`). */
data class SaveFileInfo(val path: String, val modified: Long, val size: Long)

/** Which saves two RetroTVs swap (pure, unit-tested): the newer copy wins, nothing is ever deleted. */
object SyncRules {

    private val VALID = Regex("[0-9a-f]{24}/((slot[1-4]|auto)\\.(state|png)|game\\.txt)")
    private const val SAME_MS = 2_000L

    fun isValid(path: String) = VALID.matches(path)

    fun encode(list: List<SaveFileInfo>): String = list.joinToString("\n") { "${it.path}\t${it.modified}\t${it.size}" }

    fun manifest(lines: String): List<SaveFileInfo> = lines.split('\n').mapNotNull { line ->
        val p = line.trim().split('\t')
        if (p.size != 3) return@mapNotNull null
        val t = p[1].toLongOrNull() ?: return@mapNotNull null
        val s = p[2].toLongOrNull() ?: return@mapNotNull null
        SaveFileInfo(p[0], t, s)
    }

    /** (paths to pull from the other TV, paths to push to it). */
    fun plan(local: List<SaveFileInfo>, remote: List<SaveFileInfo>): Pair<List<String>, List<String>> {
        val mine = local.filter { isValid(it.path) }.associateBy { it.path }
        val theirs = remote.filter { isValid(it.path) }.associateBy { it.path }
        val pull = mutableListOf<String>()
        val push = mutableListOf<String>()
        (mine.keys + theirs.keys).forEach { path ->
            val a = mine[path]
            val b = theirs[path]
            when {
                a == null -> pull += path
                b == null -> push += path
                a.size == b.size && abs(a.modified - b.modified) <= SAME_MS -> Unit
                b.modified > a.modified -> pull += path
                a.modified > b.modified -> push += path
            }
        }
        return pull to push
    }
}
