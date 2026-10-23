package com.aiconsole.retrotv.ui

import com.aiconsole.retrotv.data.Health
import kotlin.random.Random

/** "Surprise me": a seedable random pick that avoids repeating the last one (pure, unit-tested). */
object RandomPick {

    /** Candidates with favourites listed twice, so they come up about twice as often. */
    fun <T> weighted(all: List<T>, isFavorite: (T) -> Boolean): List<T> = all + all.filter(isFavorite)

    /**
     * One of [items], never the one whose key is [lastKey] unless it is the only choice.
     * Null for an empty list.
     */
    fun <T> choose(items: List<T>, lastKey: String?, key: (T) -> String, random: Random = Random.Default): T? {
        if (items.isEmpty()) return null
        val others = if (lastKey == null) items else items.filter { key(it) != lastKey }
        val pool = others.ifEmpty { items }
        return pool[random.nextInt(pool.size)]
    }
}

/** Numbers for the home screen's stats strip. */
object PlayStats {

    data class Stat(val value: String, val label: String)

    /** "0 min", "45 min", "2 h", "2 h 5 min". Unlike the library's tile text, zero is spelled out. */
    fun duration(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(0)
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    fun plural(n: Int, one: String, many: String = one + "s"): String = if (n == 1) one else many

    fun strip(playMs: Long, games: Int, favorites: Int): List<Stat> = listOf(
        Stat(duration(playMs), "total play time"),
        Stat(games.toString(), plural(games, "game") + " played"),
        Stat(favorites.toString(), plural(favorites, "favorite"))
    )
}

/** Wording for the "Continue playing" card. */
object HeroRules {

    /** "just now", "5 min ago", "3 h ago", "yesterday", "4 days ago". */
    fun ago(now: Long, then: Long): String {
        val minutes = ((now - then) / 60_000).coerceAtLeast(0)
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours h ago"
            days == 1L -> "yesterday"
            else -> "$days days ago"
        }
    }

    /** "SNES · played 3× · 2 h 5 min · 4 days ago"; the play time is left out while it is zero. */
    fun details(console: String, plays: Int, playMs: Long, now: Long, playedAt: Long): String {
        val time = if (playMs >= 60_000) PlayStats.duration(playMs) else ""
        return listOf(console, "played $plays×", time, ago(now, playedAt)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

/** Health chip for a site card: icon and word as well as colour (pure, unit-tested). */
object HealthChips {

    enum class Tone { Good, Warn, Bad }

    data class Chip(val text: String, val tone: Tone)

    /** Null while a site hasn't been checked yet. */
    fun chip(health: Health): Chip? = when (health) {
        Health.Up -> Chip("✓ Online", Tone.Good)
        Health.Slow -> Chip("◔ Slow", Tone.Warn)
        Health.Down -> Chip("✕ Down?", Tone.Bad)
        Health.Unknown -> null
    }
}

object HomeUiRules {

    /** Focus zoom for a card: a little more when "Bold focus" is on. */
    fun focusScale(bold: Boolean, base: Float = 1.08f): Float = if (bold) base + 0.04f else base
}
