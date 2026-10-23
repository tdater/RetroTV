package com.aiconsole.retrotv.ui

import android.view.KeyEvent
import com.aiconsole.retrotv.data.Site
import com.aiconsole.retrotv.player.RecentGame

/** One picture in the attract mode. */
sealed interface Slide {
    data class Game(val game: RecentGame) : Slide
    data class SiteCard(val site: Site) : Slide

    /** A screenshot the player saved with one of the game's save slots ([path] is the PNG). */
    data class Shot(val game: RecentGame, val path: String) : Slide
}

/** When the attract mode starts and what it shows (pure, unit-tested). */
object AttractRules {

    fun due(lastInputMs: Long, nowMs: Long, afterMin: Int): Boolean = afterMin > 0 && nowMs - lastInputMs >= afterMin * 60_000L

    /**
     * The library's games, favourites first then most played; the sites when there are no games.
     * A game with saved-state screenshots ([shots]: game key to PNG paths) is followed by one of
     * them (which one rotates with [round]), so box art and in-game pictures alternate.
     */
    fun slides(games: List<RecentGame>, sites: List<Site>, max: Int = 30, shots: Map<String, List<String>> = emptyMap(), round: Int = 0): List<Slide> =
        if (games.isEmpty()) sites.take(max).map { Slide.SiteCard(it) }
        else games.sortedWith(compareByDescending<RecentGame> { it.favorite }.thenByDescending { it.playMs }).flatMap { g ->
            listOfNotNull(Slide.Game(g), AttractShots.pick(shots[g.request.key], round)?.let { Slide.Shot(g, it) })
        }.take(max)
}

/** Names of controller buttons, for the setup's controller test (pure, unit-tested). */
object ButtonNames {
    fun label(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> "A"
        KeyEvent.KEYCODE_BUTTON_B -> "B"
        KeyEvent.KEYCODE_BUTTON_X -> "X"
        KeyEvent.KEYCODE_BUTTON_Y -> "Y"
        KeyEvent.KEYCODE_BUTTON_L1 -> "L1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "R1"
        KeyEvent.KEYCODE_BUTTON_L2 -> "L2"
        KeyEvent.KEYCODE_BUTTON_R2 -> "R2"
        KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
        KeyEvent.KEYCODE_BUTTON_START -> "Start"
        KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
        KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
        KeyEvent.KEYCODE_DPAD_UP -> "▲"
        KeyEvent.KEYCODE_DPAD_DOWN -> "▼"
        KeyEvent.KEYCODE_DPAD_LEFT -> "◀"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "▶"
        else -> null
    }
}

/** Big-screen readability settings (pure, unit-tested). */
object Readability {
    val LABELS = listOf("Normal", "Large", "Extra large")

    fun fontScale(level: Int): Float = when (level) {
        1 -> 1.15f
        2 -> 1.3f
        else -> 1.0f
    }

    /** Focus outline width in dp. */
    fun focusBorder(bold: Boolean): Int = if (bold) 6 else 3
}
