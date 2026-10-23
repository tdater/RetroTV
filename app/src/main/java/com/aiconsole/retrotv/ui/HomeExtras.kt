package com.aiconsole.retrotv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.data.Health
import com.aiconsole.retrotv.player.RecentGame
import com.aiconsole.retrotv.player.Systems

/**
 * Top of the home screen: the last game with big box art and a focused Resume button.
 * With no game yet it offers a random site and the ROM picker instead.
 */
@Composable
fun ContinueHero(
    game: RecentGame?,
    now: Long,
    resumeFocus: FocusRequester,
    onResume: () -> Unit,
    onSurprise: () -> Unit,
    onPlayRom: () -> Unit
) {
    Column {
        SectionTitle(if (game != null) "Continue playing" else "Get started")
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (game != null) {
                val r = game.request
                ArtImage(r.core, r.displayTitle, Modifier.width(130.dp).aspectRatio(3f / 4f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        (if (game.favorite) "★ " else "") + r.displayTitle,
                        color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        HeroRules.details(Systems.label(r.core), game.plays, game.playMs, now, game.playedAt),
                        color = TextDim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        TvButton("Resume", onResume, Modifier.focusRequester(resumeFocus), icon = "▶")
                        TvButton("Surprise me", onSurprise, icon = "🎲")
                    }
                }
            } else {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Nothing played yet", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Pick a site below, play a ROM from your TV, or let RetroTV choose a site for you.",
                        color = TextDim, fontSize = 16.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                        TvButton("Surprise me", onSurprise, Modifier.focusRequester(resumeFocus), icon = "🎲")
                        TvButton("Play a ROM file", onPlayRom, icon = "🎮")
                    }
                }
            }
        }
    }
}

/** Total play time, games played and favourites as three read-only tiles. */
@Composable
fun StatsStrip(playMs: Long, games: Int, favorites: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PlayStats.strip(playMs, games, favorites).forEach { s ->
            Column(
                Modifier
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(s.value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(s.label, color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Health pill with icon and word, never colour alone; nothing until the site was checked. */
@Composable
fun HealthChip(health: Health) {
    val chip = HealthChips.chip(health) ?: return
    Pill(
        chip.text,
        when (chip.tone) {
            HealthChips.Tone.Good -> Color(0xFF3DDC84)
            HealthChips.Tone.Warn -> Color(0xFFFFB300)
            HealthChips.Tone.Bad -> Color(0xFFFF5252)
        }
    )
}
