package fi.marmorikatu.app.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.theme.MkColors
import fi.marmorikatu.app.theme.MkMotion
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.app.theme.mkGlow
import fi.marmorikatu.app.theme.mkPressScale
import fi.marmorikatu.app.theme.rememberMkInteractionSource

/**
 * Card elevation per the design's `--shadow-card` token. Dark is the primary
 * theme and cards are deliberately flat there — the translucent border does the
 * work — so no shadow is drawn. Light theme gets a soft, diffuse slate shadow,
 * much subtler than Material's default black drop shadow. [raised] popovers keep
 * a light shadow on both themes so they lift off the surface.
 */
private fun Modifier.mkCardShadow(c: MkColors, shape: Shape, raised: Boolean = false): Modifier {
    if (c.isDark && !raised) return this
    val elevation = if (raised) 12.dp else 6.dp
    val color = if (c.isDark) Color(0x59000000) else Color(0x2814283C)
    return this.shadow(elevation, shape, clip = false, ambientColor = color, spotColor = color)
}

// ── Button ────────────────────────────────────────────────────────────────

enum class MkButtonVariant { Primary, Warm, Secondary, Ghost, Danger }
enum class MkButtonSize { Sm, Md, Lg, Kid }

/** The primary tappable action; five weights from teal Primary to red Danger. */
@Composable
fun MkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: MkButtonVariant = MkButtonVariant.Secondary,
    size: MkButtonSize = MkButtonSize.Md,
    icon: ImageVector? = null,
    iconRight: ImageVector? = null,
    block: Boolean = false,
    enabled: Boolean = true,
) {
    val c = MkTheme.colors
    val source = rememberMkInteractionSource()

    val height: Dp = when (size) {
        MkButtonSize.Sm -> 36.dp
        MkButtonSize.Md -> 44.dp
        MkButtonSize.Lg -> 52.dp
        MkButtonSize.Kid -> 64.dp
    }
    val padX: Dp = when (size) {
        MkButtonSize.Sm -> 14.dp
        MkButtonSize.Md -> 20.dp
        MkButtonSize.Lg -> 26.dp
        MkButtonSize.Kid -> 32.dp
    }
    val fontSize = when (size) {
        MkButtonSize.Sm -> 13.sp
        MkButtonSize.Md -> 15.sp
        MkButtonSize.Lg -> 17.sp
        MkButtonSize.Kid -> 20.sp
    }
    // icon font-size is 1.25em of the label
    val iconSize: Dp = when (size) {
        MkButtonSize.Sm -> 16.dp
        MkButtonSize.Md -> 19.dp
        MkButtonSize.Lg -> 21.dp
        MkButtonSize.Kid -> 25.dp
    }
    val radius = when (size) {
        MkButtonSize.Sm -> MkRadius.sm
        MkButtonSize.Kid -> MkRadius.lg
        else -> MkRadius.md
    }
    val shape = RoundedCornerShape(radius)

    // --_bg / --_fg / --_bd, defaulting to secondary.
    val bg: Color
    val fg: Color
    val border: Color
    when (variant) {
        MkButtonVariant.Primary -> { bg = c.accent; fg = c.inkOnAccent; border = Color.Transparent }
        MkButtonVariant.Warm -> { bg = c.warm; fg = c.inkOnWarm; border = Color.Transparent }
        // --surface-3 has no exact token; surfaceRaised is the closest raised neutral.
        MkButtonVariant.Secondary -> { bg = c.surfaceRaised; fg = c.inkHi; border = c.borderStrong }
        MkButtonVariant.Ghost -> { bg = Color.Transparent; fg = c.inkMid; border = Color.Transparent }
        MkButtonVariant.Danger -> { bg = c.statusAlarmDim; fg = c.statusAlarmInk; border = c.statusAlarm }
    }

    var box = modifier
        .then(if (block) Modifier.fillMaxWidth() else Modifier)
        .height(height)
        .alpha(if (enabled) 1f else 0.42f)
    if (enabled) box = box.mkPressScale(source)
    if (variant == MkButtonVariant.Primary) {
        // spec: primary carries the soft accent glow instead of a hover brighten
        box = box.shadow(10.dp, shape, spotColor = c.accentGlow, ambientColor = c.accentGlow)
    }
    box = box
        .clip(shape)
        .background(bg, shape)
        .then(if (border != Color.Transparent) Modifier.border(1.dp, border, shape) else Modifier)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
        .padding(horizontal = padX)

    Row(
        modifier = box,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(iconSize))
        Text(
            text = text,
            style = MkTheme.type.body.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = fontSize,
            ),
            color = fg,
            maxLines = 1,
        )
        if (iconRight != null) Icon(iconRight, null, tint = fg, modifier = Modifier.size(iconSize))
    }
}

// ── IconButton ──────────────────────────────────────────────────────────────

enum class MkIconButtonSize { Md, Lg, Kid }
enum class MkIconButtonVariant { Plain, Accent, Solid }

/** A square icon-only tap target, with an optional alarm count badge. */
@Composable
fun MkIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    size: MkIconButtonSize = MkIconButtonSize.Md,
    variant: MkIconButtonVariant = MkIconButtonVariant.Plain,
    round: Boolean = false,
    badge: String? = null,
    enabled: Boolean = true,
) {
    val c = MkTheme.colors
    val source = rememberMkInteractionSource()

    val box: Dp = when (size) {
        MkIconButtonSize.Md -> 38.dp
        MkIconButtonSize.Lg -> 52.dp
        MkIconButtonSize.Kid -> 64.dp
    }
    val iconSize: Dp = when (size) {
        MkIconButtonSize.Md -> 18.dp
        MkIconButtonSize.Lg -> 22.dp
        MkIconButtonSize.Kid -> 28.dp
    }
    val radius = if (round) MkRadius.round else when (size) {
        MkIconButtonSize.Md -> MkRadius.sm
        MkIconButtonSize.Lg -> MkRadius.md
        MkIconButtonSize.Kid -> MkRadius.lg
    }
    val shape = RoundedCornerShape(radius)

    val bg: Color
    val fg: Color
    val border: Color
    when (variant) {
        // --surface-2 has no exact token; track is the closest subtle neutral fill.
        MkIconButtonVariant.Plain -> { bg = c.track; fg = c.inkMid; border = c.borderSubtle }
        MkIconButtonVariant.Accent -> { bg = c.accentDim; fg = c.accent; border = c.accentBorder }
        MkIconButtonVariant.Solid -> { bg = c.accent; fg = c.inkOnAccent; border = Color.Transparent }
    }

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(box)) {
        var inner = Modifier
            .size(box)
            .alpha(if (enabled) 1f else 0.42f)
        if (enabled) inner = inner.mkPressScale(source)
        if (variant == MkIconButtonVariant.Solid) {
            inner = inner.shadow(8.dp, shape, spotColor = c.accentGlow, ambientColor = c.accentGlow)
        }
        inner = inner
            .clip(shape)
            .background(bg, shape)
            .then(if (border != Color.Transparent) Modifier.border(1.dp, border, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)

        Box(modifier = inner, contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(iconSize))
        }

        if (!badge.isNullOrEmpty()) {
            // spec: absolute top -5 / right -5, 16dp round, alarm bg, 2dp app-bg border, mono 9sp #fff
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 5.dp, y = (-5).dp)
                    .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                    .height(16.dp)
                    .clip(RoundedCornerShape(MkRadius.round))
                    .background(c.statusAlarm, RoundedCornerShape(MkRadius.round))
                    .border(2.dp, c.appBg, RoundedCornerShape(MkRadius.round))
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = badge,
                    style = MkTheme.type.readout(9, FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

// ── Card + CardHead ───────────────────────────────────────────────────────

enum class MkCardStatus { None, Warn, Alarm, Accent }
enum class MkCardPadding { None, Pad, PadLg }

/** The surface primitive: a bordered panel that can flag warn / alarm / accent status. */
@Composable
fun MkCard(
    modifier: Modifier = Modifier,
    padding: MkCardPadding = MkCardPadding.Pad,
    status: MkCardStatus = MkCardStatus.None,
    raised: Boolean = false,
    inset: Boolean = false,
    interactive: Boolean = false,
    hero: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val c = MkTheme.colors
    val source = rememberMkInteractionSource()

    val radius = if (hero) MkRadius.xl else MkRadius.lg
    val shape = RoundedCornerShape(radius)
    val contentPad: Dp = when {
        hero -> MkSpacing.x5
        padding == MkCardPadding.Pad -> MkSpacing.x4
        padding == MkCardPadding.PadLg -> MkSpacing.x5
        else -> 0.dp
    }

    val baseBg: Color = when {
        raised -> c.surfaceRaised
        inset -> c.surfaceInset
        else -> c.surfaceCard
    }
    val border: Color = when (status) {
        MkCardStatus.Warn -> c.warmBorder
        MkCardStatus.Alarm -> c.statusAlarm
        MkCardStatus.Accent -> c.accentBorder
        MkCardStatus.None -> c.borderSubtle
    }
    // warn / alarm paint a vertical gradient from the status-dim tint into the card.
    val gradient: Brush? = when (status) {
        MkCardStatus.Warn -> Brush.verticalGradient(listOf(c.warmDim, c.surfaceCard))
        MkCardStatus.Alarm -> Brush.verticalGradient(listOf(c.statusAlarmDim, c.surfaceCard))
        else -> null
    }

    var box = modifier
    if (interactive && onClick != null) box = box.mkPressScale(source, pressed = 0.99f)
    box = box.mkCardShadow(c, shape, raised = raised)
    if (status == MkCardStatus.Accent) box = box.mkGlow(c.accentGlow)
    box = box.clip(shape)
    box = if (gradient != null) box.background(gradient, shape) else box.background(baseBg, shape)
    box = box.border(1.dp, border, shape)
    if (onClick != null) {
        box = box.clickable(interactionSource = source, indication = null, onClick = onClick)
    }
    box = box.padding(contentPad)

    Column(modifier = box, content = content)
}

/** Card header: a display title on the left with an optional action slot on the right. */
@Composable
fun MkCardHead(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = MkSpacing.x3),
        horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MkTheme.type.heading.copy(letterSpacing = (-0.02).em),
            color = MkTheme.colors.inkHi,
            modifier = Modifier.weight(1f),
        )
        if (action != null) action()
    }
}

// ── Badge ─────────────────────────────────────────────────────────────────

enum class MkBadgeVariant { Alarm, Accent, Neutral }

/** A tiny count / status pip; the [dot] form is a bare 10dp circle. */
@Composable
fun MkBadge(
    modifier: Modifier = Modifier,
    text: String? = null,
    variant: MkBadgeVariant = MkBadgeVariant.Alarm,
    dot: Boolean = false,
) {
    val c = MkTheme.colors
    val bg = when (variant) {
        MkBadgeVariant.Alarm -> c.statusAlarm
        MkBadgeVariant.Accent -> c.accent
        MkBadgeVariant.Neutral -> c.track
    }
    val fg = when (variant) {
        MkBadgeVariant.Alarm -> Color.White
        MkBadgeVariant.Accent -> c.inkOnAccent
        MkBadgeVariant.Neutral -> c.inkHi
    }
    val shape = RoundedCornerShape(MkRadius.round)

    if (dot) {
        Box(modifier = modifier.size(10.dp).background(bg, shape))
        return
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 18.dp)
            .height(18.dp)
            .clip(shape)
            .background(bg, shape)
            .padding(horizontal = 5.dp),
    ) {
        Text(
            text = text.orEmpty(),
            style = MkTheme.type.readout(10, FontWeight.SemiBold),
            color = fg,
            maxLines = 1,
        )
    }
}

// ── Tag ───────────────────────────────────────────────────────────────────

enum class MkTagStatus { Neutral, Ok, Warn, Alarm, Info, Accent }

/** A mono micro-label for state words (VALMIS, KALLIS); optional dot / icon. */
@Composable
fun MkTag(
    text: String,
    modifier: Modifier = Modifier,
    status: MkTagStatus = MkTagStatus.Neutral,
    dot: Boolean = false,
    icon: ImageVector? = null,
) {
    val c = MkTheme.colors
    val bg = when (status) {
        MkTagStatus.Neutral -> c.track
        MkTagStatus.Ok -> c.statusOkDim
        MkTagStatus.Warn -> c.statusWarnDim
        MkTagStatus.Alarm -> c.statusAlarmDim
        MkTagStatus.Info -> c.statusInfoDim
        MkTagStatus.Accent -> c.accentDim
    }
    // fg is also the "currentColor" the leading dot inherits.
    val fg = when (status) {
        MkTagStatus.Neutral -> c.inkMid
        MkTagStatus.Ok -> c.statusOk
        MkTagStatus.Warn -> c.statusWarnInk
        MkTagStatus.Alarm -> c.statusAlarmInk
        MkTagStatus.Info -> c.statusInfo
        MkTagStatus.Accent -> c.accent
    }
    val shape = RoundedCornerShape(MkRadius.xs)

    Row(
        modifier = modifier
            .clip(shape)
            .background(bg, shape)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) Box(modifier = Modifier.size(6.dp).clip(RoundedCornerShape(MkRadius.round)).background(fg))
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp))
        Text(
            text = text,
            style = MkTheme.type.tag.copy(letterSpacing = 0.04.em),
            color = fg,
            maxLines = 1,
        )
    }
}

// ── StatTile ──────────────────────────────────────────────────────────────

enum class MkStatStatus { None, Warn, Alarm }
enum class MkStatSize { Md, Lg }

/** "Quiet until it matters": a labelled mono readout that only tints on warn / alarm. */
@Composable
fun MkStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    icon: ImageVector? = null,
    status: MkStatStatus = MkStatStatus.None,
    tag: String? = null,
    tagStatus: MkTagStatus = MkTagStatus.Neutral,
    size: MkStatSize = MkStatSize.Md,
    /** Optional 24 h history trend drawn as a sparkline under the value. */
    spark: List<Float>? = null,
    /**
     * Changes each time fresh data arrives (e.g. a fetch timestamp), so the tile
     * pulses on every refresh even when the value string is unchanged. Falls back
     * to [value] when null, so a tile without a pulse key still flashes on change.
     */
    pulseKey: Any? = null,
    /** Dims the whole tile when its data feed has gone stale (source stopped). */
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val c = MkTheme.colors
    val source = rememberMkInteractionSource()
    val shape = RoundedCornerShape(MkRadius.md)

    // Fresh-data flash: a brief pulse toward accent whenever the value changes,
    // so a refresh is noticeable at a glance. It settles back to 0 and is not an
    // infinite animation, so it adds no idle redraws once the pulse finishes.
    // Flash on every fresh data arrival: keyed on [pulseKey] (a fetch timestamp)
    // when supplied, so identical values still pulse; otherwise on the value.
    val flashSignal = pulseKey ?: value
    val flash = remember { Animatable(0f) }
    var seenSignal by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(flashSignal) {
        if (seenSignal != null && seenSignal != flashSignal) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(MkMotion.SLOW, easing = MkMotion.easeOut))
        }
        seenSignal = flashSignal
    }

    val border: Color = when (status) {
        MkStatStatus.Warn -> c.warmBorder
        MkStatStatus.Alarm -> c.statusAlarm
        MkStatStatus.None -> lerp(c.borderSubtle, c.accent, flash.value)
    }
    val gradient: Brush? = when (status) {
        MkStatStatus.Warn -> Brush.verticalGradient(listOf(c.warmDim, c.surfaceCard))
        MkStatStatus.Alarm -> Brush.verticalGradient(listOf(c.statusAlarmDim, c.surfaceCard))
        MkStatStatus.None -> null
    }
    val iconTint: Color = when (status) {
        MkStatStatus.Warn -> c.warm
        MkStatStatus.Alarm -> c.statusAlarmInk
        MkStatStatus.None -> c.accent
    }

    var box = modifier
        .alpha(if (dimmed) 0.45f else 1f)
        .mkCardShadow(c, shape)
        .clip(shape)
    box = if (gradient != null) box.background(gradient, shape) else box.background(c.surfaceCard, shape)
    box = box.border(1.dp, border, shape)
    if (onClick != null) {
        box = box
            .mkPressScale(source, pressed = 0.99f)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    }
    box = box.padding(MkSpacing.x3)

    val valueSize = if (size == MkStatSize.Lg) 30 else 24

    Column(modifier = box) {
        // __top: icon left, optional tag right (min-height 20dp)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(19.dp))
            } else {
                Spacer(Modifier.size(0.dp))
            }
            if (tag != null) MkTag(text = tag, status = tagStatus)
        }
        Text(
            text = label,
            style = MkTheme.type.label,
            color = c.inkMid,
            modifier = Modifier.padding(top = MkSpacing.x2),
            maxLines = 1,
        )
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = value,
                style = MkTheme.type.readout(valueSize),
                color = lerp(c.inkHi, c.accent, flash.value),
                modifier = Modifier.alignByBaseline(),
                maxLines = 1,
            )
            if (unit != null) {
                Text(
                    text = unit,
                    style = MkTheme.type.readout(11),
                    color = c.inkLo,
                    modifier = Modifier.alignByBaseline().padding(start = 3.dp),
                    maxLines = 1,
                )
            }
        }
        if (spark != null) {
            MkSparkline(
                values = spark,
                color = iconTint,
                modifier = Modifier.padding(top = MkSpacing.x2),
            )
        }
    }
}
