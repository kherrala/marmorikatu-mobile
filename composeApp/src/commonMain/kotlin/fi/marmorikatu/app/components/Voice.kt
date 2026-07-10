package fi.marmorikatu.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkDot
import fi.marmorikatu.app.theme.MkPulseRing
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.app.theme.mkGlow
import fi.marmorikatu.app.theme.mkPressScale
import fi.marmorikatu.app.theme.rememberBreathe
import fi.marmorikatu.app.theme.rememberMkInteractionSource
import fi.marmorikatu.app.theme.rememberSpin
import fi.marmorikatu.app.theme.rememberWaveHeights
import androidx.compose.foundation.clickable

/** The four states of the persistent voice assistant. */
enum class VoiceState { Idle, Listening, Thinking, Speaking }

/** Size ladder shared by the voice controls (orb ignores [Kid]). */
enum class MkVoiceSize { Md, Lg, Kid }

/**
 * The living teal orb — a radial-gradient sphere with an accent ring that
 * pulses whenever the assistant is not idle.
 */
@Composable
fun MkVoiceOrb(
    state: VoiceState = VoiceState.Listening,
    size: MkVoiceSize = MkVoiceSize.Md,
    modifier: Modifier = Modifier,
) {
    val colors = MkTheme.colors
    val orbSize: Dp = if (size == MkVoiceSize.Md) 44.dp else 56.dp
    // teal-600 has no token; derive a darker teal from the theme accent.
    val darkTeal = lerp(colors.accent, Color.Black, 0.4f)
    val active = state != VoiceState.Idle
    val pulsePeriod = if (state == VoiceState.Speaking) 1500 else 2600

    Box(modifier = modifier.size(orbSize), contentAlignment = Alignment.Center) {
        if (active) {
            MkPulseRing(color = colors.accent, size = orbSize, periodMs = pulsePeriod)
        }
        Box(
            Modifier
                .size(orbSize)
                .mkGlow(colors.accentGlow, radius = 16.dp)
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0f to colors.accentStrong,
                                0.72f to darkTeal,
                                1f to darkTeal,
                            ),
                            center = Offset(this.size.width * 0.4f, this.size.height * 0.35f),
                            radius = this.size.minDimension,
                        ),
                    )
                }
                // spec: __ring — 1.5dp accent-border ring inset:0.
                .border(1.5.dp, colors.accentBorder, CircleShape),
        )
    }
}

/**
 * The idle mic FAB. Circular accent button; with a [label] it becomes a
 * captioned voice-fab. Breathes while [listening].
 */
@Composable
fun MkVoiceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: MkVoiceSize = MkVoiceSize.Lg,
    listening: Boolean = false,
    label: String? = null,
) {
    val colors = MkTheme.colors
    val (btnSize, iconSize) = when (size) {
        MkVoiceSize.Md -> 50.dp to 24.dp
        MkVoiceSize.Lg -> 62.dp to 29.dp
        MkVoiceSize.Kid -> 76.dp to 34.dp
    }
    val interaction = rememberMkInteractionSource()
    // mk-breathe 1.4s WHILE LISTENING only. Calling rememberBreathe
    // unconditionally would leave an infinite transition running on the idle
    // mic button — which sits on every screen — invalidating at the display
    // refresh rate forever. Gate the whole animation on `listening`.
    val breatheAlpha = if (listening) rememberBreathe(1400) else 1f

    val button: @Composable () -> Unit = {
        Box(
            Modifier
                .mkPressScale(interaction, pressed = 0.94f)
                .size(btnSize)
                .graphicsLayer { alpha = breatheAlpha }
                .mkGlow(colors.accentGlow, radius = 22.dp)
                .clip(CircleShape)
                .background(colors.accent)
                .clickable(interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MkIcons.MicrophoneFill,
                contentDescription = label ?: "Puhu Marmorille",
                tint = colors.inkOnAccent,
                modifier = Modifier.size(iconSize),
            )
        }
    }

    if (label == null) {
        Box(modifier) { button() }
    } else {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            button()
            Text(
                text = label,
                style = TextStyle(
                    fontFamily = MkTheme.type.mono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp,
                    letterSpacing = 0.08.em,
                    color = colors.inkMid,
                ),
                modifier = Modifier
                    .clip(RoundedCornerShape(MkRadius.round))
                    .background(colors.surfaceCard)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(MkRadius.round))
                    .padding(horizontal = 9.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The persistent voice bar. Idle collapses to a right-aligned mic; the active
 * states expand into an orb + transcript row with a live indicator.
 */
@Composable
fun MkVoiceDock(
    onMic: () -> Unit,
    modifier: Modifier = Modifier,
    state: VoiceState = VoiceState.Idle,
    prompt: String = "Puhu Marmorille",
    hint: String? = null,
    transcript: String? = null,
    size: MkVoiceSize = MkVoiceSize.Lg,
    showPrompt: Boolean = false,
) {
    val colors = MkTheme.colors

    if (state == VoiceState.Idle) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showPrompt) {
                Text(
                    text = prompt,
                    style = MkTheme.type.readout(11).copy(letterSpacing = 0.sp),
                    color = colors.inkLo,
                )
            }
            MkVoiceButton(onClick = onMic, size = size)
        }
        return
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.xxl))
            .background(colors.surfaceCard)
            .border(1.dp, colors.accentBorder, RoundedCornerShape(MkRadius.xxl))
            .padding(start = 18.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MkVoiceOrb(state = state, size = MkVoiceSize.Md)

        Column(modifier = Modifier.weight(1f)) {
            val line1 = when (state) {
                VoiceState.Listening -> "Kuuntelen…"
                VoiceState.Thinking -> "Käsittelen…"
                VoiceState.Speaking -> transcript ?: "Marmori"
                VoiceState.Idle -> ""
            }
            Text(
                text = line1,
                style = TextStyle(
                    fontFamily = MkTheme.type.ui,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                ),
                color = colors.inkHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (state == VoiceState.Listening) {
                    MkDot(color = colors.statusAlarm, size = 7.dp, breathing = true)
                }
                val line2 = when (state) {
                    VoiceState.Listening -> hint ?: "puhu nyt"
                    VoiceState.Thinking -> "hetki…"
                    VoiceState.Speaking -> hint ?: ""
                    VoiceState.Idle -> ""
                }
                Text(
                    text = line2,
                    style = MkTheme.type.readout(11),
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        if (state == VoiceState.Thinking) {
            VoiceSpinner()
        } else {
            VoiceWaveform()
        }
    }
}

/** Thinking indicator: a 22dp ring with an accent top arc, spinning. */
@Composable
private fun VoiceSpinner() {
    val colors = MkTheme.colors
    val angle = rememberSpin(800)
    Box(
        Modifier
            .size(22.dp)
            .rotate(angle)
            .drawBehind {
                val stroke = 2.dp.toPx()
                val inset = stroke / 2f
                drawCircle(
                    color = colors.track,
                    radius = size.minDimension / 2f - inset,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = colors.accent,
                    startAngle = -110f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - stroke,
                        size.height - stroke,
                    ),
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            },
    )
}

/** Speaking indicator: five staggered accent bars in a 24dp-tall row. */
@Composable
private fun VoiceWaveform() {
    val colors = MkTheme.colors
    val heights = rememberWaveHeights()
    Row(
        modifier = Modifier.height(24.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        heights.forEach { h ->
            Spacer(
                Modifier
                    .width(3.dp)
                    .height(h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.accent),
            )
        }
    }
}
