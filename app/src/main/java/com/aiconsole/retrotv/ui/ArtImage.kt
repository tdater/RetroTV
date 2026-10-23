package com.aiconsole.retrotv.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiconsole.retrotv.RetroApp
import com.aiconsole.retrotv.player.ArtKind
import com.aiconsole.retrotv.player.Systems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal object ArtMemory {
    val cache = LruCache<String, ImageBitmap>(60)

    /** Decodes [file] at most 512 px on its long side. */
    fun load(file: File): ImageBitmap? = cache.get(file.path) ?: runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()?.also { cache.put(file.path, it) }
}

/** A picture from a file on the TV (a save slot's screenshot); nothing is drawn while it loads or when it can't be read. */
@Composable
fun FileImage(path: String, description: String, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, path) { value = withContext(Dispatchers.IO) { ArtMemory.load(File(path)) } }
    val b = bitmap
    if (b != null) Image(b, description, modifier, contentScale = ContentScale.Fit)
}

/** A game's box art ([com.aiconsole.retrotv.player.ArtStore]), or a console-coloured placeholder when there is none. */
@Composable
fun ArtImage(core: String, title: String, modifier: Modifier = Modifier, fallbackColor: Color = Color(0xFF2A2340), kind: ArtKind = ArtKind.Box) {
    val app = RetroApp.from(LocalContext.current)
    val bitmap by produceState<ImageBitmap?>(null, core, title, kind) {
        value = withContext(Dispatchers.IO) { (app.art.cached(core, title, kind) ?: app.art.art(core, title, kind))?.let { ArtMemory.load(it) } }
    }
    Box(modifier.background(Brush.verticalGradient(listOf(fallbackColor, Color.Black)), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) {
            Image(b, title, Modifier.padding(4.dp), contentScale = ContentScale.Fit)
        } else {
            Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title.take(1).uppercase(), color = Color.White.copy(alpha = 0.35f), fontSize = 44.sp, fontWeight = FontWeight.Black)
                Text(Systems.label(core), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
