package com.aiconsole.retrotv.player

enum class LibrarySort(val label: String) { Recent("Recently played"), Name("A–Z"), MostPlayed("Most played") }

/** Sorting, filtering and wording for "My games" (pure, unit-tested). Favourites always come first. */
object LibraryRules {

    fun sort(games: List<RecentGame>, by: LibrarySort): List<RecentGame> {
        val order = when (by) {
            LibrarySort.Recent -> compareByDescending<RecentGame> { it.playedAt }
            LibrarySort.Name -> compareBy { it.request.displayTitle.lowercase() }
            LibrarySort.MostPlayed -> compareByDescending { it.playMs }
        }
        return games.sortedWith(compareByDescending<RecentGame> { it.favorite }.then(order))
    }

    /** Games of [system] (a [Systems] id; null = all) whose title contains [query]. */
    fun filter(games: List<RecentGame>, system: String?, query: String): List<RecentGame> {
        val q = query.trim()
        return games.filter { g ->
            (system == null || systemOf(g) == system) && (q.isEmpty() || g.request.displayTitle.contains(q, ignoreCase = true))
        }
    }

    /** The consoles in the library, in first-seen order. */
    fun systems(games: List<RecentGame>): List<String> = games.map { systemOf(it) }.distinct()

    private fun systemOf(g: RecentGame): String = Systems.of(g.request.core)?.id ?: g.request.core

    fun playTime(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes <= 0 -> ""
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }
}
