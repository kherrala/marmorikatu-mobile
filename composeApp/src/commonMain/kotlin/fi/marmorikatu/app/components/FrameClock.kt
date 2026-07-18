package fi.marmorikatu.app.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.sin

/**
 * Elapsed milliseconds since first composition, updated every frame off the raw
 * frame clock. Unlike Compose's animation APIs, [withFrameNanos] ignores the
 * system "animator duration scale" (dev options / battery saver), so anything
 * driven from this keeps moving even when that setting is off.
 */
@Composable
fun rememberFrameMillis(): State<Float> {
    val ms = remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = 0L
        while (true) {
            withFrameNanos { now ->
                if (start == 0L) start = now
                ms.value = (now - start) / 1_000_000f
            }
        }
    }
    return ms
}

/** Smooth 0→1→0 sine oscillation of the given period (ms) at time [ms]. */
fun frameOsc(ms: Float, periodMs: Float): Float =
    ((sin(ms / periodMs * 2.0 * PI).toFloat()) + 1f) / 2f
