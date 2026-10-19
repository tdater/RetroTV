package com.aiconsole.retrotv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.player.ArtKind
import com.aiconsole.retrotv.player.LibraryRules
import com.aiconsole.retrotv.player.Systems
import kotlinx.coroutines.delay

/**
 * Attract mode: when the home screen has sat idle, cycle through the library's games (box art,
 * an in-game screenshot, title, console, play time) like an arcade cabinet. The caller ends it on
 * any key ([MainActivity] swallows that key).
 */
@Composable
fun AttractMode(slides: List<Slide>) {
    if (slides.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(slides) {
        while (true) {
            delay(8_000)
            index = (index + 1) % slides.size
        }
    }
    val drift by rememberInfiniteTransition(label = "attract").animateFloat(
        1f, 1.06f, infiniteRepeatable(tween(8_000, easing = LinearEasing), RepeatMode.Reverse), label = "zoom"
    )
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = slides[index % slides.size], label = "slide") { slide ->
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = drift; scaleY = drift }.padding(48.dp), contentAlignment = Alignment.Center) {
                when (slide) {
                    is Slide.Game -> GameSlide(slide)
                    is Slide.SiteCard -> SiteSlide(slide)
                }
            }
        }
        Row(Modifier.align(Alignment.TopStart).padding(28.dp), verticalAlignment = Alignment.Bottom) {
            Text("RETRO", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Color.White, letterSpacing = 3.sp)
            Text("TV", fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, letterSpacing = 3.sp)
        }
        Text(
            "Press any button",
            color = Color.White.copy(alpha = 0.7f), fontSize = 18.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
        )
    }
}

@Composable
private fun GameSlide(slide: Slide.Game) {
    val r = slide.game.request
    Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
        ArtImage(r.core, r.displayTitle, Modifier.fillMaxHeight(0.8f).aspectRatio(3f / 4f))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ArtImage(r.core, r.displayTitle, Modifier.fillMaxWidth(0.9f).aspectRatio(4f / 3f), kind = ArtKind.Snap)
            Text(r.displayTitle, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(Systems.label(r.core), LibraryRules.playTime(slide.game.playMs)).filter { it.isNotEmpty() }.joinToString(" · "),
                color = TextDim, fontSize = 22.sp
            )
        }
    }
}

@Composable
private fun SiteSlide(slide: Slide.SiteCard) {
    val color = Color(slide.site.color)
    Column(
        Modifier.fillMaxWidth(0.7f).aspectRatio(16f / 9f).background(color.copy(alpha = 0.8f), RoundedCornerShape(28.dp)).padding(40.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Text(slide.site.name, color = Color.White, fontSize = 52.sp, fontWeight = FontWeight.Black)
        Text(slide.site.description, color = Color.White.copy(alpha = 0.9f), fontSize = 22.sp)
    }
}
