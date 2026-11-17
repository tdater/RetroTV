package com.aiconsole.retrotv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * LaunchBox-style retro art, character mascots, CRT scanline effects, and console badges
 * for big-screen TVs.
 */
object RetroArt {

    val NeonCyan = Color(0xFF22D3EE)
    val NeonGreen = Color(0xFF3DDC84)
    val NeonYellow = Color(0xFFFFD23F)
    val NeonPink = Color(0xFFFF5CC8)
    val NeonPurple = Color(0xFF9C6BFF)
    val ArcadeRed = Color(0xFFFF4D5E)
    val ConsoleGold = Color(0xFFFFB300)
    val RetroGray = Color(0xFF9E9E9E)
}

/** Draws subtle CRT arcade monitor scanlines over a surface. */
@Composable
fun RetroScanlines(modifier: Modifier = Modifier, alpha: Float = 0.08f) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val step = 4.dp.toPx()
        val line = Color.Black.copy(alpha = alpha)
        var y = 0f
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
            y += step
        }
    }
}

/**
 * Draws iconic retro character mascots and gaming hardware silhouettes.
 */
@Composable
fun RetroMascotArt(mascotKey: String, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        when (mascotKey) {
            "mario_sonic", "mario" -> drawMarioEmblem(color)
            "sonic" -> drawSonicSpeedStar(color)
            "pacman" -> drawPacMan(color)
            "spaceinvader" -> drawSpaceInvader(color)
            "link" -> drawTriforce(color)
            "gameboy" -> drawGameBoy(color)
            "atari_joystick" -> drawAtariJoystick(color)
            "crt_dos", "dos" -> drawCrtDosTerminal(color)
            "doomguy" -> drawDoomGuyFloppy(color)
            "crash" -> drawPlayStationSymbols(color)
            "pokemon" -> drawPokeBall(color)
            "heart_powerup" -> drawPixelHeart(color)
            "gamepad_tv" -> drawGamepadTv(color)
            "woodgrain_console" -> drawWoodgrainConsole(color)
            "cloud_controller" -> drawCloudController(color)
            else -> drawArcadeCabinet(color)
        }
    }
}

private fun DrawScope.drawPacMan(color: Color) {
    val r = size.minDimension * 0.42f
    val center = Offset(size.width * 0.45f, size.height * 0.5f)
    // Pac-Man body with mouth open towards the right
    drawArc(
        color = color,
        startAngle = 35f,
        sweepAngle = 290f,
        useCenter = true,
        topLeft = Offset(center.x - r, center.y - r),
        size = Size(r * 2, r * 2)
    )
    // Eye
    drawCircle(
        color = Color.Black.copy(alpha = 0.75f),
        radius = r * 0.14f,
        center = Offset(center.x + r * 0.1f, center.y - r * 0.45f)
    )
    // Power pellet dot ahead of Pac-Man
    drawCircle(
        color = color,
        radius = r * 0.18f,
        center = Offset(size.width * 0.88f, center.y)
    )
}

private fun DrawScope.drawSpaceInvader(color: Color) {
    // 8x8 pixel-art space invader alien
    val grid = arrayOf(
        "  ████  ",
        " ██████ ",
        "████████",
        "██ ██ ██",
        "████████",
        "  █  █  ",
        " █ ██ █ ",
        "█ █  █ █"
    )
    val pixelSize = size.minDimension / 10f
    val offsetX = (size.width - pixelSize * 8f) / 2f
    val offsetY = (size.height - pixelSize * 8f) / 2f
    for (row in grid.indices) {
        for (col in grid[row].indices) {
            if (grid[row][col] == '█') {
                drawRect(
                    color = color,
                    topLeft = Offset(offsetX + col * pixelSize, offsetY + row * pixelSize),
                    size = Size(pixelSize * 0.92f, pixelSize * 0.92f)
                )
            }
        }
    }
}

private fun DrawScope.drawMarioEmblem(color: Color) {
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val r = size.minDimension * 0.44f
    // Emblem circle
    drawCircle(color = color, radius = r, center = center, style = Stroke(width = r * 0.18f))
    // Iconic Mario 'M'
    val path = Path().apply {
        val w = r * 0.9f
        val h = r * 0.9f
        moveTo(center.x - w * 0.7f, center.y + h * 0.5f)
        lineTo(center.x - w * 0.7f, center.y - h * 0.5f)
        lineTo(center.x - w * 0.25f, center.y - h * 0.5f)
        lineTo(center.x, center.y + h * 0.05f)
        lineTo(center.x + w * 0.25f, center.y - h * 0.5f)
        lineTo(center.x + w * 0.7f, center.y - h * 0.5f)
        lineTo(center.x + w * 0.7f, center.y + h * 0.5f)
        lineTo(center.x + w * 0.45f, center.y + h * 0.5f)
        lineTo(center.x + w * 0.45f, center.y - h * 0.1f)
        lineTo(center.x + w * 0.15f, center.y + h * 0.35f)
        lineTo(center.x - w * 0.15f, center.y + h * 0.35f)
        lineTo(center.x - w * 0.45f, center.y - h * 0.1f)
        lineTo(center.x - w * 0.45f, center.y + h * 0.5f)
        close()
    }
    drawPath(path, color = color, style = Fill)
}

private fun DrawScope.drawSonicSpeedStar(color: Color) {
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val outerR = size.minDimension * 0.44f
    val innerR = outerR * 0.65f
    // Sonic Golden Ring
    drawCircle(color = color, radius = outerR, center = center, style = Stroke(width = outerR - innerR))
    // Inner speed quill star accent
    val path = Path()
    val points = 5
    for (i in 0 until points * 2) {
        val angle = (i * Math.PI / points - Math.PI / 2).toFloat()
        val dist = if (i % 2 == 0) innerR * 0.75f else innerR * 0.35f
        val px = center.x + kotlin.math.cos(angle) * dist
        val py = center.y + kotlin.math.sin(angle) * dist
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, color = color, style = Fill)
}

private fun DrawScope.drawTriforce(color: Color) {
    val center = Offset(size.width * 0.5f, size.height * 0.55f)
    val s = size.minDimension * 0.42f
    val h = s * 0.866f // sqrt(3)/2

    fun triangle(cx: Float, cy: Float) {
        val p = Path().apply {
            moveTo(cx, cy - h / 2f)
            lineTo(cx + s / 2f, cy + h / 2f)
            lineTo(cx - s / 2f, cy + h / 2f)
            close()
        }
        drawPath(p, color = color, style = Fill)
    }
    // Top triangle (Power)
    triangle(center.x, center.y - h / 2f)
    // Bottom-left triangle (Wisdom)
    triangle(center.x - s / 2f, center.y + h / 2f)
    // Bottom-right triangle (Courage)
    triangle(center.x + s / 2f, center.y + h / 2f)
}

private fun DrawScope.drawGameBoy(color: Color) {
    val w = size.minDimension * 0.65f
    val h = size.minDimension * 0.9f
    val x = (size.width - w) / 2f
    val y = (size.height - h) / 2f
    // Main body with curved bottom-right
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.12f, w * 0.12f),
        style = Stroke(width = 3.dp.toPx())
    )
    // Screen bezel
    drawRoundRect(
        color = color,
        topLeft = Offset(x + w * 0.15f, y + h * 0.12f),
        size = Size(w * 0.7f, h * 0.38f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f, w * 0.06f),
        style = Fill
    )
    // Screen LCD cutout
    drawRect(
        color = Color.Black.copy(alpha = 0.7f),
        topLeft = Offset(x + w * 0.25f, y + h * 0.16f),
        size = Size(w * 0.5f, h * 0.28f)
    )
    // D-Pad cross
    val dpadX = x + w * 0.25f
    val dpadY = y + h * 0.66f
    val dpadSize = w * 0.18f
    drawRect(color = color, topLeft = Offset(dpadX - dpadSize / 3f, dpadY - dpadSize), size = Size(dpadSize * 0.66f, dpadSize * 2f))
    drawRect(color = color, topLeft = Offset(dpadX - dpadSize, dpadY - dpadSize / 3f), size = Size(dpadSize * 2f, dpadSize * 0.66f))
    // A & B Buttons (angled)
    drawCircle(color = color, radius = w * 0.08f, center = Offset(x + w * 0.78f, y + h * 0.62f))
    drawCircle(color = color, radius = w * 0.08f, center = Offset(x + w * 0.62f, y + h * 0.72f))
}

private fun DrawScope.drawAtariJoystick(color: Color) {
    val center = Offset(size.width * 0.5f, size.height * 0.55f)
    val baseW = size.minDimension * 0.75f
    val baseH = size.minDimension * 0.45f
    // Base block
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - baseW / 2f, center.y),
        size = Size(baseW, baseH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
        style = Fill
    )
    // Shaft stick
    drawRect(
        color = color,
        topLeft = Offset(center.x - 4.dp.toPx(), center.y - baseH * 0.85f),
        size = Size(8.dp.toPx(), baseH * 0.9f)
    )
    // Top knob ball
    drawCircle(color = color, radius = baseH * 0.32f, center = Offset(center.x, center.y - baseH * 0.85f))
    // Red action button in corner
    drawCircle(color = Color(0xFFFF4D5E), radius = baseH * 0.2f, center = Offset(center.x - baseW * 0.32f, center.y + baseH * 0.32f))
}

private fun DrawScope.drawCrtDosTerminal(color: Color) {
    val w = size.minDimension * 0.8f
    val h = size.minDimension * 0.65f
    val x = (size.width - w) / 2f
    val y = (size.height - h) / 2f - 6.dp.toPx()
    // CRT Monitor outline
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.1f, w * 0.1f),
        style = Stroke(width = 3.dp.toPx())
    )
    // Inner terminal screen
    drawRoundRect(
        color = color.copy(alpha = 0.25f),
        topLeft = Offset(x + 6.dp.toPx(), y + 6.dp.toPx()),
        size = Size(w - 12.dp.toPx(), h - 12.dp.toPx()),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.06f, w * 0.06f)
    )
    // Terminal cursor & prompt: >_
    val path = Path().apply {
        moveTo(x + 14.dp.toPx(), y + 16.dp.toPx())
        lineTo(x + 24.dp.toPx(), y + 24.dp.toPx())
        lineTo(x + 14.dp.toPx(), y + 32.dp.toPx())
    }
    drawPath(path, color = color, style = Stroke(width = 3.dp.toPx()))
    drawRect(color = color, topLeft = Offset(x + 30.dp.toPx(), y + 26.dp.toPx()), size = Size(10.dp.toPx(), 7.dp.toPx()))
    // Stand base
    drawRect(color = color, topLeft = Offset(size.width * 0.4f, y + h), size = Size(size.width * 0.2f, 8.dp.toPx()))
    drawRect(color = color, topLeft = Offset(size.width * 0.3f, y + h + 8.dp.toPx()), size = Size(size.width * 0.4f, 4.dp.toPx()))
}

private fun DrawScope.drawDoomGuyFloppy(color: Color) {
    val s = size.minDimension * 0.72f
    val x = (size.width - s) / 2f
    val y = (size.height - s) / 2f
    // 3.5" Floppy Disk body
    val p = Path().apply {
        moveTo(x, y)
        lineTo(x + s * 0.85f, y)
        lineTo(x + s, y + s * 0.15f) // top-right 45 deg chamfer
        lineTo(x + s, y + s)
        lineTo(x, y + s)
        close()
    }
    drawPath(p, color = color, style = Stroke(width = 3.dp.toPx()))
    // Metal shutter slider at top
    drawRect(
        color = color,
        topLeft = Offset(x + s * 0.25f, y + 2.dp.toPx()),
        size = Size(s * 0.5f, s * 0.36f)
    )
    // Label area
    drawRoundRect(
        color = color.copy(alpha = 0.25f),
        topLeft = Offset(x + s * 0.12f, y + s * 0.45f),
        size = Size(s * 0.76f, s * 0.45f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
    )
}

private fun DrawScope.drawPlayStationSymbols(color: Color) {
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val r = size.minDimension * 0.24f
    val sw = 3.5.dp.toPx()
    // Triangle (Green)
    val tri = Path().apply {
        moveTo(center.x - r, center.y - r * 1.5f)
        lineTo(center.x - r * 0.5f, center.y - r * 0.6f)
        lineTo(center.x - r * 1.5f, center.y - r * 0.6f)
        close()
    }
    drawPath(tri, color = Color(0xFF3DDC84), style = Stroke(width = sw))
    // Circle (Red)
    drawCircle(color = Color(0xFFFF4D5E), radius = r * 0.45f, center = Offset(center.x + r, center.y - r * 1.05f), style = Stroke(width = sw))
    // Cross / X (Blue)
    val xOffset = Offset(center.x + r, center.y + r * 1.05f)
    val xSize = r * 0.45f
    drawLine(Color(0xFF6EA8FF), Offset(xOffset.x - xSize, xOffset.y - xSize), Offset(xOffset.x + xSize, xOffset.y + xSize), strokeWidth = sw)
    drawLine(Color(0xFF6EA8FF), Offset(xOffset.x - xSize, xOffset.y + xSize), Offset(xOffset.x + xSize, xOffset.y - xSize), strokeWidth = sw)
    // Square (Pink)
    drawRect(
        color = Color(0xFFFF5CC8),
        topLeft = Offset(center.x - r * 1.45f, center.y + r * 0.6f),
        size = Size(r * 0.9f, r * 0.9f),
        style = Stroke(width = sw)
    )
}

private fun DrawScope.drawPokeBall(color: Color) {
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val r = size.minDimension * 0.42f
    val sw = 3.dp.toPx()
    // Outer circle
    drawCircle(color = color, radius = r, center = center, style = Stroke(width = sw))
    // Horizontal center divider line
    drawLine(color = color, start = Offset(center.x - r, center.y), end = Offset(center.x + r, center.y), strokeWidth = sw)
    // Outer center ring
    drawCircle(color = color, radius = r * 0.32f, center = center, style = Fill)
    // Center button cutout
    drawCircle(color = Color.Black, radius = r * 0.22f, center = center, style = Fill)
    drawCircle(color = color, radius = r * 0.12f, center = center, style = Fill)
}

private fun DrawScope.drawPixelHeart(color: Color) {
    val grid = arrayOf(
        " ██ ██ ",
        "███████",
        "███████",
        " █████ ",
        "  ███  ",
        "   █   "
    )
    val pixelSize = size.minDimension / 8f
    val offsetX = (size.width - pixelSize * 7f) / 2f
    val offsetY = (size.height - pixelSize * 6f) / 2f
    for (row in grid.indices) {
        for (col in grid[row].indices) {
            if (grid[row][col] == '█') {
                drawRect(
                    color = color,
                    topLeft = Offset(offsetX + col * pixelSize, offsetY + row * pixelSize),
                    size = Size(pixelSize * 0.95f, pixelSize * 0.95f)
                )
            }
        }
    }
}

private fun DrawScope.drawGamepadTv(color: Color) {
    val center = Offset(size.width * 0.5f, size.height * 0.5f)
    val w = size.minDimension * 0.85f
    val h = size.minDimension * 0.5f
    // Modern retro gamepad body
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - w / 2f, center.y - h / 2f),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.45f, h * 0.45f),
        style = Stroke(width = 3.dp.toPx())
    )
    // D-Pad on left
    val dpadCenter = Offset(center.x - w * 0.25f, center.y)
    val d = h * 0.28f
    drawRect(color = color, topLeft = Offset(dpadCenter.x - d / 3f, dpadCenter.y - d), size = Size(d * 0.66f, d * 2f))
    drawRect(color = color, topLeft = Offset(dpadCenter.x - d, dpadCenter.y - d / 3f), size = Size(d * 2f, d * 0.66f))
    // 4 Action buttons on right
    val btnCenter = Offset(center.x + w * 0.25f, center.y)
    val br = h * 0.1f
    val bDist = h * 0.22f
    drawCircle(color = color, radius = br, center = Offset(btnCenter.x, btnCenter.y - bDist))
    drawCircle(color = color, radius = br, center = Offset(btnCenter.x + bDist, btnCenter.y))
    drawCircle(color = color, radius = br, center = Offset(btnCenter.x, btnCenter.y + bDist))
    drawCircle(color = color, radius = br, center = Offset(btnCenter.x - bDist, btnCenter.y))
}

private fun DrawScope.drawWoodgrainConsole(color: Color) {
    val w = size.minDimension * 0.85f
    val h = size.minDimension * 0.55f
    val x = (size.width - w) / 2f
    val y = (size.height - h) / 2f
    // Console body
    drawRoundRect(
        color = color,
        topLeft = Offset(x, y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx()),
        style = Stroke(width = 3.dp.toPx())
    )
    // Front woodgrain horizontal slats
    val slatStep = h / 6f
    for (i in 1..4) {
        drawLine(
            color = color.copy(alpha = 0.5f),
            start = Offset(x + 8.dp.toPx(), y + i * slatStep),
            end = Offset(x + w - 8.dp.toPx(), y + i * slatStep),
            strokeWidth = 2.dp.toPx()
        )
    }
    // Cartridge slot at top
    drawRect(
        color = color,
        topLeft = Offset(x + w * 0.3f, y + 4.dp.toPx()),
        size = Size(w * 0.4f, 6.dp.toPx())
    )
}

private fun DrawScope.drawCloudController(color: Color) {
    val center = Offset(size.width * 0.5f, size.height * 0.48f)
    val r = size.minDimension * 0.28f
    // Cloud puffs
    drawCircle(color = color, radius = r, center = Offset(center.x - r * 0.6f, center.y + r * 0.2f), style = Stroke(width = 2.5.dp.toPx()))
    drawCircle(color = color, radius = r * 1.15f, center = Offset(center.x + r * 0.1f, center.y - r * 0.2f), style = Stroke(width = 2.5.dp.toPx()))
    drawCircle(color = color, radius = r * 0.85f, center = Offset(center.x + r * 0.8f, center.y + r * 0.2f), style = Stroke(width = 2.5.dp.toPx()))
    // Bottom flat line
    drawLine(color = color, start = Offset(center.x - r * 1.1f, center.y + r * 0.7f), end = Offset(center.x + r * 1.3f, center.y + r * 0.7f), strokeWidth = 2.5.dp.toPx())
}

private fun DrawScope.drawArcadeCabinet(color: Color) {
    val w = size.minDimension * 0.6f
    val h = size.minDimension * 0.9f
    val x = (size.width - w) / 2f
    val y = (size.height - h) / 2f
    val p = Path().apply {
        moveTo(x, y + h)
        lineTo(x, y + h * 0.15f)
        lineTo(x + w * 0.25f, y) // angled marquee
        lineTo(x + w, y)
        lineTo(x + w * 0.85f, y + h * 0.35f) // monitor slant
        lineTo(x + w * 0.95f, y + h * 0.55f) // control panel
        lineTo(x + w * 0.75f, y + h * 0.62f)
        lineTo(x + w * 0.75f, y + h)
        close()
    }
    drawPath(p, color = color, style = Stroke(width = 3.dp.toPx()))
    // Marquee box
    drawRect(color = color.copy(alpha = 0.35f), topLeft = Offset(x + w * 0.25f, y + 4.dp.toPx()), size = Size(w * 0.65f, h * 0.12f))
}

/**
 * LaunchBox-style Console & Platform Badge Chips shown on TV cards.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LaunchBoxConsoleChips(systems: List<String>, directPlay: Boolean, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 5
    ) {
        systems.take(5).forEach { sys ->
            Box(
                Modifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    sys.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.9f),
                    letterSpacing = 0.5.sp
                )
            }
        }
        if (directPlay) {
            Box(
                Modifier
                    .background(RetroArt.NeonCyan.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .border(1.dp, RetroArt.NeonCyan.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "⚡ DIRECT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = RetroArt.NeonCyan,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}
