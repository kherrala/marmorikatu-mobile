package fi.marmorikatu.app.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The design system's signature effects. Glow is the futuristic accent — used
 * sparingly on the voice orb, focused controls and "latest" rings — and one
 * calm breathing pulse drives every live indicator.
 */

/** Soft radial glow behind a circular element (`--glow-accent`). */
fun Modifier.mkGlow(color: Color, radius: Dp = 20.dp, alpha: Float = 1f): Modifier = drawBehind {
    val r = (size.minDimension / 2f) + radius.toPx()
    drawCircle(
        brush = androidx.compose.ui.graphics.Brush.radialGradient(
            colors = listOf(color.copy(alpha = color.alpha * alpha), Color.Transparent),
            center = Offset(size.width / 2f, size.height / 2f),
            radius = r,
        ),
        radius = r,
        center = Offset(size.width / 2f, size.height / 2f),
    )
}

/** Press feedback: scale to `--press-scale` (0.96). */
@Composable
fun Modifier.mkPressScale(
    interactionSource: MutableInteractionSource,
    pressed: Float = MkMotion.PRESS_SCALE,
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = tween(MkMotion.FAST, easing = MkMotion.standard),
        label = "press",
    )
    return this.scale(scale)
}

/** `mk-breathe` — opacity 1 → 0.4 → 1. Live dots, listening mic. */
@Composable
fun rememberBreathe(periodMs: Int = 2000): Float {
    val transition = rememberInfiniteTransition(label = "breathe")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs / 2, easing = MkMotion.standard),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breatheAlpha",
    )
    return alpha
}

/** `mk-spin` — continuous rotation for the thinking spinner. */
@Composable
fun rememberSpin(periodMs: Int = 800): Float {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "spinAngle",
    )
    return angle
}

/**
 * `mk-pulse-ring` — a ring expanding from scale 1 → 1.6 while fading out.
 * Draw behind the orb; [periodMs] is 2600 for `active`, 1500 for `speaking`.
 */
@Composable
fun MkPulseRing(
    color: Color,
    size: Dp,
    periodMs: Int,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "pulseRing")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(periodMs, easing = MkMotion.easeOut),
        ),
        label = "pulseProgress",
    )
    Box(
        modifier = modifier
            .size(size)
            .scale(1f + progress * 0.6f)
            .drawBehind {
                drawCircle(
                    color = color.copy(alpha = 0.7f * (1f - progress)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()),
                )
            }
    )
}

/**
 * `mk-wave` — five bars breathing between 6 and 22 dp with staggered delays
 * (0, .15, .3, .1, .22 s). The speaking indicator.
 */
@Composable
fun rememberWaveHeights(): List<Dp> {
    val transition = rememberInfiniteTransition(label = "wave")
    val delays = listOf(0, 150, 300, 100, 220)
    return delays.map { delayMs ->
        val h by transition.animateFloat(
            initialValue = 6f,
            targetValue = 22f,
            animationSpec = infiniteRepeatable(
                animation = tween(450, delayMillis = delayMs, easing = MkMotion.standard),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "waveBar$delayMs",
        )
        h.dp
    }
}

/** A small round status dot; [breathing] applies the live pulse. */
@Composable
fun MkDot(color: Color, size: Dp = 8.dp, breathing: Boolean = false, modifier: Modifier = Modifier) {
    val alpha = if (breathing) rememberBreathe(if (size <= 8.dp) 1200 else 2000) else 1f
    Box(
        modifier = modifier
            .size(size)
            .background(color.copy(alpha = color.alpha * alpha), CircleShape)
    )
}

@Composable
fun rememberMkInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }
