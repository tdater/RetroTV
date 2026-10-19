package com.aiconsole.retrotv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.aiconsole.retrotv.data.ACCENTS
import com.aiconsole.retrotv.data.Backdrop
import com.aiconsole.retrotv.data.Prefs

val Ink = Color(0xFF0B0B14)
val Panel = Color(0xFF171726)
val PanelHigh = Color(0xFF22223A)
val TextDim = Color(0xFFB4B4CC)

fun accentOf(prefs: Prefs): Color = Color(ACCENTS[prefs.accent.coerceIn(0, ACCENTS.lastIndex)].second)

@Composable
fun RetroTheme(prefs: Prefs, content: @Composable () -> Unit) {
    val accent = accentOf(prefs)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * Readability.fontScale(prefs.textSize)),
        LocalBoldFocus provides prefs.boldFocus
    ) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = accent,
                onPrimary = Color.Black,
                secondary = accent,
                background = Ink,
                surface = Panel,
                surfaceVariant = PanelHigh,
                onSurface = Color.White,
                onBackground = Color.White,
                onSurfaceVariant = TextDim
            ),
            content = content
        )
    }
}

/** Thicker focus outlines (Settings: Bold focus), read by [TvClickable]. */
val LocalBoldFocus = staticCompositionLocalOf { false }

/** The home screen backdrop chosen in Settings → Appearance. */
@Composable
fun RetroBackground(prefs: Prefs, content: @Composable BoxScope.() -> Unit) {
    val accent = accentOf(prefs)
    val base = when (prefs.backdrop) {
        Backdrop.Black -> Modifier.background(Color.Black)
        Backdrop.Dark -> Modifier.background(Ink)
        Backdrop.Gradient -> Modifier.background(
            Brush.linearGradient(listOf(accent.copy(alpha = 0.35f).blendOver(Ink), Ink, Color(0xFF05050A)))
        )
        Backdrop.Arcade -> Modifier
            .background(Brush.verticalGradient(listOf(Color(0xFF12002A), Color(0xFF05050A))))
            .drawBehind {
                val line = accent.copy(alpha = 0.18f)
                val step = size.height / 14f
                var y = size.height * 0.55f
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y), 2f)
                    y += step * ((y - size.height * 0.5f) / (size.height * 0.5f) + 0.25f)
                }
                val cx = size.width / 2f
                for (i in -12..12) {
                    drawLine(line, Offset(cx, size.height * 0.55f), Offset(cx + i * size.width / 8f, size.height), 2f)
                }
            }
    }
    Box(Modifier.fillMaxSize().then(base), content = content)
}

private fun Color.blendOver(background: Color): Color {
    val a = alpha
    return Color(
        red = red * a + background.red * (1 - a),
        green = green * a + background.green * (1 - a),
        blue = blue * a + background.blue * (1 - a),
        alpha = 1f
    )
}
