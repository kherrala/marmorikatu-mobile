package fi.marmorikatu.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Decodes the base64 thumbnail an announcement carries (a UniFi Protect
 * snapshot) into a [Painter]. Returns null on anything unexpected — a camera
 * card with no image is a fine outcome; a crash is not.
 *
 * The bridge may hand us a bare base64 payload or a full `data:` URI.
 */
@OptIn(ExperimentalEncodingApi::class)
@Composable
fun rememberBase64Painter(base64: String?): Painter? = remember(base64) {
    if (base64.isNullOrBlank()) return@remember null
    runCatching {
        val payload = base64.substringAfter("base64,", base64).trim()
        BitmapPainter(Base64.decode(payload).decodeToImageBitmap())
    }.getOrNull()
}
