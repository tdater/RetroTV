package com.aiconsole.retrotv.data

import android.content.Context

/**
 * Per-site compatibility notes: which "Fix it" steps were tried on a site and whether the game
 * then played. [CompatRules] is pure Kotlin (JVM-tested); [CompatLog] stores it in preferences.
 *
 * Fix ids: `view:desktop`, `view:tv`, `player:retrotv`, `player:site`, `adblock:off`,
 * `controller:keyboard|gamepad|both`, `reload`.
 */
object CompatRules {
    const val MAX_PER_HOST = 20
    const val MAX_HOSTS = 100

    /** The game played for this long after a fix (or launched) counts as "worked". */
    const val WORKED_AFTER_MS = 60_000L

    data class Entry(val host: String, val fix: String, val at: Long, val worked: Boolean)

    /** The combination the user kept: the latest outcome per fix group (view, player, ...) that worked. */
    fun keptFixes(entries: List<Entry>, host: String): List<String> {
        val latest = LinkedHashMap<String, Entry>()
        for (e in entries.filter { it.host == host }.sortedBy { it.at }) {
            latest.remove(group(e.fix))
            latest[group(e.fix)] = e
        }
        return latest.values.filter { it.worked }.map { it.fix }
    }

    /** How sure we are: worked records for the kept fixes (1 = likely, 2 or more = confirmed). */
    enum class Confidence { None, Likely, Confirmed }

    fun confidence(entries: List<Entry>, host: String): Confidence {
        val kept = keptFixes(entries, host).toSet()
        if (kept.isEmpty()) return Confidence.None
        val n = entries.count { it.host == host && it.worked && it.fix in kept }
        return if (n >= 2) Confidence.Confirmed else Confidence.Likely
    }

    /** Short badge text such as "Works: desktop view" or "Needs: ad block off"; null when nothing worked. */
    fun best(entries: List<Entry>, host: String): String? {
        val kept = keptFixes(entries, host).takeLast(2).reversed()
        if (kept.isEmpty()) return null
        val needs = kept.all { needsKind(it) }
        return (if (needs) "Needs: " else "Works: ") + kept.joinToString(" + ") { describe(it) }
    }

    fun describe(fix: String): String = when (fix) {
        "view:desktop" -> "desktop view"
        "view:tv" -> "TV view"
        "player:retrotv" -> "RetroTV player"
        "player:site" -> "site player"
        "adblock:off" -> "ad block off"
        "controller:keyboard" -> "keyboard keys"
        "controller:gamepad" -> "gamepad"
        "controller:both" -> "gamepad + keys"
        "reload" -> "reload"
        else -> fix
    }

    private fun needsKind(fix: String) = fix == "adblock:off" || fix.startsWith("controller:")

    private fun group(fix: String) = fix.substringBefore(':')

    /** Adds [entry]; keeps the newest [MAX_PER_HOST] per host and the [MAX_HOSTS] most recently used hosts. */
    fun add(entries: List<Entry>, entry: Entry): List<Entry> = prune(entries + entry)

    fun prune(entries: List<Entry>): List<Entry> {
        val byHost = entries.groupBy { it.host }
        val keepHosts = byHost.entries
            .sortedByDescending { (_, list) -> list.maxOf { it.at } }
            .take(MAX_HOSTS).map { it.key }.toSet()
        // List order is chronological: per host keep the last MAX_PER_HOST entries.
        val keepIdx = HashSet<Int>()
        val seen = HashMap<String, Int>()
        for (i in entries.indices.reversed()) {
            val h = entries[i].host
            if (h !in keepHosts) continue
            val c = seen.getOrDefault(h, 0)
            if (c < MAX_PER_HOST) {
                seen[h] = c + 1
                keepIdx.add(i)
            }
        }
        return entries.filterIndexed { i, _ -> i in keepIdx }
    }

    fun clear(entries: List<Entry>, host: String): List<Entry> = entries.filter { it.host != host }

    /** One entry per line: `host<TAB>fix<TAB>at<TAB>1|0`. */
    fun serialize(entries: List<Entry>): String = entries.joinToString("\n") {
        listOf(clean(it.host), clean(it.fix), it.at.toString(), if (it.worked) "1" else "0").joinToString("\t")
    }

    fun parse(text: String?): List<Entry> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size != 4 || f[0].isEmpty() || f[1].isEmpty()) return@mapNotNull null
            val at = f[2].toLongOrNull() ?: return@mapNotNull null
            val worked = when (f[3]) {
                "1" -> true
                "0" -> false
                else -> return@mapNotNull null
            }
            Entry(f[0], f[1], at, worked)
        }.toList()
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}

/** Stored in preferences; `RetroApp.from(context).compat`. */
class CompatLog(context: Context) {
    private val prefs = context.getSharedPreferences("compat_log", Context.MODE_PRIVATE)

    private fun load() = CompatRules.parse(prefs.getString(KEY, null))

    private fun save(entries: List<CompatRules.Entry>) {
        prefs.edit().putString(KEY, CompatRules.serialize(entries)).apply()
    }

    @Synchronized
    fun record(host: String, fixLabel: String, worked: Boolean) {
        if (host.isBlank() || fixLabel.isBlank()) return
        save(CompatRules.add(load(), CompatRules.Entry(host.lowercase(), fixLabel, System.currentTimeMillis(), worked)))
    }

    /** Badge text such as "Works: desktop view", or null when nothing is known to work. */
    @Synchronized
    fun best(host: String): String? = CompatRules.best(load(), host.lowercase())

    @Synchronized
    fun clear(host: String) = save(CompatRules.clear(load(), host.lowercase()))

    private companion object {
        const val KEY = "entries"
    }
}
