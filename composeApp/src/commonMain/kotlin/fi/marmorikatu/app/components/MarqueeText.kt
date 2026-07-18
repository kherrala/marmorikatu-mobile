package fi.marmorikatu.app.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * A single-line label that, when its text is wider than the space it's given,
 * scrolls left-to-right and pauses briefly at each end before repeating —
 * so a long headline can be read in full. When the text fits, it sits still.
 *
 * Driven off the raw frame clock (withFrameNanos + coroutine delays), NOT the
 * animation framework, so it keeps scrolling even when the device's "animator
 * duration scale" is off (which freezes Compose's built-in marquee/animations).
 */
@Composable
fun MarqueeText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var containerW by remember { mutableStateOf(0) }
    var textW by remember { mutableStateOf(0) }
    var offset by remember { mutableStateOf(0f) }
    val overflow = (textW - containerW).coerceAtLeast(0)
    val speedPx = with(LocalDensity.current) { 44.dp.toPx() } // ~44 dp/s

    LaunchedEffect(text, overflow) {
        offset = 0f
        if (overflow <= 0) return@LaunchedEffect
        while (true) {
            delay(END_PAUSE_MS)              // read the start
            var last = 0L
            while (offset < overflow) {
                withFrameNanos { now ->
                    if (last != 0L) {
                        offset = (offset + (now - last) / 1_000_000_000f * speedPx)
                            .coerceAtMost(overflow.toFloat())
                    }
                    last = now
                }
            }
            delay(END_PAUSE_MS)              // read the end
            offset = 0f                       // snap back and repeat
        }
    }

    Box(modifier = modifier.clipToBounds().onSizeChanged { containerW = it.width }) {
        Text(
            text = text,
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                // Let the line measure at its full natural width instead of being
                // clamped to (and ellipsised at) the container edge.
                .wrapContentWidth(align = Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = -offset }
                .onSizeChanged { textW = it.width },
        )
    }
}

private const val END_PAUSE_MS = 1400L
