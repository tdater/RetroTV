package com.aiconsole.retrotv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** A QR code for [text], black on white with a quiet zone, scannable from a phone. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix: BitMatrix? = remember(text) {
        try {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
        } catch (_: WriterException) {
            null
        }
    }
    Canvas(modifier.background(Color.White).padding(10.dp)) {
        val m = matrix ?: return@Canvas
        val cell = minOf(size.width, size.height) / m.width
        val offsetX = (size.width - cell * m.width) / 2
        val offsetY = (size.height - cell * m.height) / 2
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m[x, y]) drawRect(Color.Black, Offset(offsetX + x * cell, offsetY + y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
