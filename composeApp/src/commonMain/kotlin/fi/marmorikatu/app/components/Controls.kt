package fi.marmorikatu.app.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkMotion
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.app.theme.mkPressScale
import fi.marmorikatu.app.theme.rememberMkInteractionSource
import kotlin.math.abs
import kotlin.math.round

/** md/kid sizing shared by the control components. */
enum class MkControlSize { Md, Kid }

// ---------------------------------------------------------------------------
// Switch
// ---------------------------------------------------------------------------

/** A pill toggle with a white shadowed knob that overshoots into place. */
@Composable
fun MkSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: MkControlSize = MkControlSize.Md,
    label: String? = null,
    disabled: Boolean = false,
) {
    val colors = MkTheme.colors
    val kid = size == MkControlSize.Kid
    val trackW = if (kid) 62.dp else 46.dp
    val trackH = if (kid) 36.dp else 28.dp
    val knob = if (kid) 30.dp else 22.dp
    val inset = 3.dp
    // Off knob sits at `inset`; on-knob at 21 (md) / 29 (kid) — travel is the delta.
    val travel = trackW - knob - inset - inset

    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.track,
        animationSpec = MkMotion.overshoot(),
        label = "switchTrack",
    )
    val knobX by animateDpAsState(
        targetValue = if (checked) travel else 0.dp,
        animationSpec = MkMotion.overshoot(),
        label = "switchKnob",
    )
    val interaction = rememberMkInteractionSource()

    Row(
        modifier = modifier
            .alpha(if (disabled) 0.45f else 1f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !disabled,
            ) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(trackW, trackH)
                .background(trackColor, RoundedCornerShape(MkRadius.round)),
        ) {
            Box(
                modifier = Modifier
                    .padding(inset)
                    .offset(x = knobX)
                    .size(knob)
                    .shadow(2.dp, CircleShape)
                    .background(Color(0xFFFFFFFF), CircleShape), // spec: knob is a hard-coded literal #fff
            )
        }
        if (label != null) {
            Text(text = label, style = MkTheme.type.body.copy(color = colors.inkHi))
        }
    }
}

// ---------------------------------------------------------------------------
// SetpointControl
// ---------------------------------------------------------------------------

/** A −/+ adjuster with a mono accent readout, snapping the value to [step]. */
@Composable
fun MkSetpointControl(
    value: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    min: Double = 15.0,
    max: Double = 30.0,
    step: Double = 0.5,
    unit: String = "°",
    decimals: Int = 1,
    size: MkControlSize = MkControlSize.Md,
) {
    val colors = MkTheme.colors
    val kid = size == MkControlSize.Kid
    val btn = if (kid) 56.dp else 38.dp
    val iconSize = if (kid) 26.dp else 18.dp
    val valueSize = if (kid) 26 else 20
    val valueMinWidth = if (kid) 92.dp else 66.dp

    fun clamp(v: Double): Double = minOf(max, maxOf(min, round(v / step) * step))

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StepButton(
            icon = MkIcons.Minus,
            size = btn,
            iconSize = iconSize,
            enabled = value > min,
            onClick = { onChange(clamp(value - step)) },
        )
        Text(
            text = formatDecimals(value, decimals) + unit,
            style = MkTheme.type.readout(valueSize, FontWeight.SemiBold).copy(color = colors.accent),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = valueMinWidth),
        )
        StepButton(
            icon = MkIcons.Plus,
            size = btn,
            iconSize = iconSize,
            enabled = value < max,
            onClick = { onChange(clamp(value + step)) },
        )
    }
}

@Composable
private fun StepButton(
    icon: ImageVector,
    size: Dp,
    iconSize: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MkTheme.colors
    val interaction = rememberMkInteractionSource()
    Box(
        modifier = Modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.35f)
            .mkPressScale(interaction, pressed = 0.9f)
            .background(colors.track, CircleShape)
            .border(1.dp, colors.borderSubtle, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.inkHi,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** `Number.toFixed(decimals)` — common-Kotlin fixed-decimal formatting (no java). */
private fun formatDecimals(v: Double, decimals: Int): String {
    if (decimals <= 0) return round(v).toLong().toString()
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = round(v * factor).toLong()
    val negative = scaled < 0
    val digits = abs(scaled).toString().padStart(decimals + 1, '0')
    val whole = digits.dropLast(decimals)
    val frac = digits.takeLast(decimals)
    return (if (negative) "-" else "") + whole + "." + frac
}

// ---------------------------------------------------------------------------
// SceneSelector + LightLevel
// ---------------------------------------------------------------------------

/**
 * The lighting ladder off→dim→base→full, mirroring `LIGHT_LEVELS`.
 * `Off` wants a power glyph absent from MkIcons — substituting `X` for now.
 */
enum class LightLevel(val key: String, val label: String, val icon: ImageVector) {
    Off("off", "Pois", MkIcons.X), // TODO(icon): ph-power
    Dim("dim", "Himmeä", MkIcons.Moon),
    Base("base", "Perus", MkIcons.LightbulbFill),
    Full("full", "Täysi", MkIcons.Sun),
}

/** The exported light ladder, off→dim→base→full. */
val LIGHT_LEVELS: List<LightLevel> = LightLevel.entries.toList()

private val SceneDimGradient = Brush.verticalGradient(
    listOf(Color(0xFF2F9A8B), Color(0xFF20776A)), // spec: dim-active uses a literal gradient, not tokens
)
private val SceneDimText = Color(0xFFE2FBF5)

/** Segmented lighting-level control; the active `dim` segment gets a dimmer-teal gradient. */
@Composable
fun MkSceneSelector(
    value: LightLevel,
    onChange: (LightLevel) -> Unit,
    modifier: Modifier = Modifier,
    levels: List<LightLevel> = LIGHT_LEVELS,
    size: MkControlSize = MkControlSize.Md,
) {
    val colors = MkTheme.colors
    Row(
        modifier = modifier
            .background(colors.track, RoundedCornerShape(MkRadius.md))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        levels.forEach { level ->
            SceneSegment(
                level = level,
                active = level == value,
                size = size,
                onClick = { onChange(level) },
            )
        }
    }
}

@Composable
private fun RowScope.SceneSegment(
    level: LightLevel,
    active: Boolean,
    size: MkControlSize,
    onClick: () -> Unit,
) {
    val colors = MkTheme.colors
    val kid = size == MkControlSize.Kid
    val shape = RoundedCornerShape(MkRadius.sm)
    val interaction = rememberMkInteractionSource()

    val fg = when {
        active && level == LightLevel.Dim -> SceneDimText
        active -> colors.inkOnAccent
        else -> colors.inkMid
    }
    val bg = when {
        active && level == LightLevel.Dim -> Modifier.background(SceneDimGradient, shape)
        active -> Modifier.background(colors.accent, shape)
        else -> Modifier
    }
    val labelStyle = (if (kid) MkTheme.type.body else MkTheme.type.label)
        .copy(fontWeight = FontWeight.SemiBold, color = fg)

    Column(
        modifier = Modifier
            .weight(1f)
            .mkPressScale(interaction)
            .then(bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(
                if (kid) PaddingValues(horizontal = 4.dp, vertical = 14.dp)
                else PaddingValues(start = 2.dp, end = 2.dp, top = 9.dp, bottom = 7.dp),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (kid) 5.dp else 3.dp),
    ) {
        Icon(
            imageVector = level.icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(if (kid) 22.dp else 16.dp),
        )
        Text(text = level.label, style = labelStyle)
    }
}

// ---------------------------------------------------------------------------
// TimeRange + TimeRangeOption
// ---------------------------------------------------------------------------

/** History window options for [MkTimeRange], mirroring `TIME_RANGES`. */
enum class TimeRangeOption(val key: String, val label: String) {
    H6("6h", "6 t"),
    H24("24h", "24 t"),
    D7("7d", "7 pv"),
    D30("30d", "30 pv"),
    Y1("1y", "vuosi"),
}

/** The exported history windows. */
val TIME_RANGES: List<TimeRangeOption> = TimeRangeOption.entries.toList()

/** Compact inline mono segmented selector for a history time window. */
@Composable
fun MkTimeRange(
    value: TimeRangeOption,
    onChange: (TimeRangeOption) -> Unit,
    modifier: Modifier = Modifier,
    ranges: List<TimeRangeOption> = TIME_RANGES,
) {
    val colors = MkTheme.colors
    Row(
        modifier = modifier
            .background(colors.track, RoundedCornerShape(MkRadius.md))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ranges.forEach { range ->
            val active = range == value
            val interaction = rememberMkInteractionSource()
            val shape = RoundedCornerShape(MkRadius.sm)
            Box(
                modifier = Modifier
                    .then(if (active) Modifier.background(colors.surfaceRaised, shape) else Modifier)
                    .clickable(interactionSource = interaction, indication = null) { onChange(range) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = range.label,
                    style = MkTheme.type.readout(11).copy(
                        color = if (active) colors.inkHi else colors.inkMid,
                    ),
                )
            }
        }
    }
}
