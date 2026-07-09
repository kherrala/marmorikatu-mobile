package fi.marmorikatu.app.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkMotion
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.app.theme.mkGlow
import fi.marmorikatu.app.theme.mkPressScale
import fi.marmorikatu.app.theme.rememberMkInteractionSource
import kotlin.math.abs
import kotlin.math.round

/** A room shown in [MkRoomCarousel] — a name and an optional leading glyph. */
data class MkRoom(val name: String, val icon: ImageVector? = null)

/** One room's climate state for [MkClimateCard]. */
data class MkClimateRoom(
    val name: String,
    val icon: ImageVector? = null,
    val temp: String,
    val demand: Int? = null,
    /** Null when no setpoint source exists — rendered as "—", never guessed. */
    val target: Double?,
    val status: String? = null,
    val statusLabel: String? = null,
)

/** A single controllable light within [MkAreaLightCard]. */
data class MkLight(val name: String, val on: Boolean, val icon: ImageVector? = null)

/** Lighting interaction model for [MkAreaLightCard]. */
enum class MkLightMode { Scene, Switch }

/** ok→Mukava, warn→Viileä, alarm→Kylmä, info→Yö (default Mukava). */
private val STATUS_LABEL = mapOf(
    "ok" to "Mukava",
    "warn" to "Viileä",
    "alarm" to "Kylmä",
    "info" to "Yö",
)

/** Format a value to one decimal without java (mirrors JS `toFixed(1)`). */
private fun format1(v: Double): String {
    val scaled = round(v * 10.0).toInt()
    return "${scaled / 10}.${abs(scaled % 10)}"
}

/**
 * Room pager: prev/next chevrons around the current room name, plus an elongated
 * active dot. Wraps modulo the room count; the parent owns [index].
 */
@Composable
fun MkRoomCarousel(
    rooms: List<MkRoom>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    size: MkControlSize = MkControlSize.Md,
) {
    val colors = MkTheme.colors
    val kid = size == MkControlSize.Kid
    val n = rooms.size
    val cur = rooms.getOrNull(index)
    val go: (Int) -> Unit = { d -> if (n > 0) onIndexChange((index + d + n) % n) }

    val navSize = if (kid) 44.dp else 30.dp
    val navIcon = if (kid) 20.dp else 14.dp
    val nameSize = if (kid) 18.sp else 16.sp

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NavButton(MkIcons.CaretLeft, navSize, navIcon) { go(-1) }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    cur?.icon?.let {
                        Icon(it, null, tint = colors.inkMid, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = cur?.name ?: "",
                        style = MkTheme.type.body.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = nameSize,
                        ),
                        color = colors.inkHi,
                        maxLines = 1,
                    )
                }
                trailing?.invoke()
            }
            NavButton(MkIcons.CaretRight, navSize, navIcon) { go(1) }
        }

        if (n > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                rooms.forEachIndexed { i, _ ->
                    val active = i == index
                    // spec: active dot is a 14x5 accent pill, width animated with overshoot.
                    val w by animateDpAsState(
                        targetValue = if (active) 14.dp else 5.dp,
                        animationSpec = MkMotion.overshoot(),
                        label = "dotWidth",
                    )
                    Box(
                        Modifier
                            .width(w)
                            .height(5.dp)
                            .clip(RoundedCornerShape(if (active) 3.dp else MkRadius.round))
                            .background(if (active) colors.accent else colors.track)
                    )
                }
            }
        }
    }
}

@Composable
private fun NavButton(icon: ImageVector, box: androidx.compose.ui.unit.Dp, glyph: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val colors = MkTheme.colors
    val source = rememberMkInteractionSource()
    Box(
        modifier = Modifier
            .size(box)
            .clip(CircleShape)
            .background(colors.track)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .mkPressScale(source, pressed = 0.9f), // spec: nav :active scale(.9)
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = colors.inkMid, modifier = Modifier.size(glyph))
    }
}

/**
 * Hero climate widget: a room pager, a large temperature readout with an
 * accent-tinted degree sign, a heating-demand block, and a target setpoint.
 * When [targetEnabled] is false the setpoint is read-only (the real house has
 * no per-room write path).
 */
@Composable
fun MkClimateCard(
    rooms: List<MkClimateRoom>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onTargetChange: ((Int, Double) -> Unit)? = null,
    targetEnabled: Boolean = true,
    min: Double = 18.0,
    max: Double = 24.0,
    size: MkControlSize = MkControlSize.Md,
) {
    val colors = MkTheme.colors
    val cur = rooms.getOrNull(index) ?: return
    val st = cur.status ?: "ok"
    val label = cur.statusLabel ?: STATUS_LABEL[st] ?: "Mukava"
    // spec: the trailing status label rides a raw mk-tag span, not the Tag component.
    val tagStatus = when (st) {
        "alarm", "warn", "info" -> st
        else -> "ok"
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.xl))
            .background(colors.surfaceCard)
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(MkRadius.xl))
            .padding(20.dp), // mk-card--hero: --sp-5
    ) {
        MkRoomCarousel(
            rooms = rooms.map { MkRoom(it.name, it.icon) },
            index = index,
            onIndexChange = onIndexChange,
            size = size,
            trailing = { ClimateStatusTag(label, tagStatus) },
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = buildAnnotatedString {
                    append(cur.temp)
                    withStyle(SpanStyle(color = colors.accent)) { append("°") }
                },
                style = MkTheme.type.readout(52).copy(lineHeight = 46.8.sp), // line-height .9
                color = colors.inkHi,
            )
            if (cur.demand != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("Lämmitys", style = MkTheme.type.caption, color = colors.inkLo)
                    Text(
                        text = "${cur.demand} %",
                        style = MkTheme.type.readout(16),
                        color = colors.warm,
                    )
                }
            }
        }

        // Divider then the target row (border-top + padding-top:14).
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .height(1.dp)
                .background(colors.borderSubtle)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Tavoite",
                style = MkTheme.type.label,
                color = colors.inkMid,
            )
            if (targetEnabled && cur.target != null) {
                MkSetpointControl(
                    value = cur.target,
                    min = min,
                    max = max,
                    size = size,
                    onChange = { onTargetChange?.invoke(index, it) },
                )
            } else {
                ReadOnlySetpoint(cur.target, size)
            }
        }
    }
}

/** Read-only setpoint value: mono accent, no −/+ buttons (matches SetpointControl sizing). */
@Composable
private fun ReadOnlySetpoint(value: Double?, size: MkControlSize) {
    val colors = MkTheme.colors
    val kid = size == MkControlSize.Kid
    Box(
        modifier = Modifier.widthIn(min = if (kid) 92.dp else 66.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (value == null) "—" else "${format1(value)}°",
            style = MkTheme.type.readout(if (kid) 26 else 18, FontWeight.SemiBold),
            color = if (value == null) colors.inkLo else colors.accent,
        )
    }
}

/** Raw mk-tag status pill used inside the climate carousel (sentence-case label). */
@Composable
private fun ClimateStatusTag(label: String, status: String) {
    val colors = MkTheme.colors
    val fg = colors.status(status)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(MkRadius.xs))
            .background(colors.statusDim(status))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).background(fg, CircleShape))
        Text(
            text = label,
            style = MkTheme.type.tag.copy(letterSpacing = 0.04.em),
            color = fg,
        )
    }
}

/**
 * Per-area lighting. Scene mode shows a level selector and which lights are on;
 * Switch mode is plain on/off (a header toggle for one light, a divided column
 * of toggles for several). A teal LED glows while the area is lit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MkAreaLightCard(
    name: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = MkIcons.LightbulbFill,
    mode: MkLightMode = MkLightMode.Scene,
    level: LightLevel = LightLevel.Off,
    onLevelChange: (LightLevel) -> Unit = {},
    lights: List<MkLight> = emptyList(),
    levels: List<LightLevel>? = null,
    showLights: Boolean = true,
    on: Boolean = false,
    onToggle: (Boolean) -> Unit = {},
    onLightToggle: ((Int, Boolean) -> Unit)? = null,
    size: MkControlSize = MkControlSize.Md,
) {
    val colors = MkTheme.colors

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.lg))
            .background(colors.surfaceCard)
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(MkRadius.lg))
            .padding(20.dp), // mk-card--pad-lg: --sp-5
    ) {
        if (mode == MkLightMode.Switch) {
            val items = if (lights.isNotEmpty()) lights else listOf(MkLight("Valo", on))
            val single = items.size <= 1
            val anyOn = items.any { it.on }
            val toggle: (Int, Boolean) -> Unit = { i, v ->
                if (onLightToggle != null) onLightToggle(i, v) else onToggle(v)
            }

            AreaTopRow(icon, name, lit = anyOn) {
                if (single) {
                    MkSwitch(checked = items[0].on, onChange = { toggle(0, it) }, size = size)
                }
            }

            if (!single) {
                Column {
                    items.forEachIndexed { i, l ->
                        if (i > 0) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderSubtle))
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    l.icon ?: MkIcons.Lightbulb,
                                    null,
                                    tint = colors.inkLo,
                                    modifier = Modifier.size(17.dp),
                                )
                                Text(l.name, style = MkTheme.type.body, color = colors.inkHi)
                            }
                            MkSwitch(checked = l.on, onChange = { toggle(i, it) }, size = size)
                        }
                    }
                }
            }
        } else {
            val total = lights.size
            val onCount = lights.count { it.on }
            val lit = level != LightLevel.Off && onCount > 0

            AreaTopRow(icon, name, lit = lit) {
                if (level == LightLevel.Off || onCount == 0) {
                    Text("Pois", style = MkTheme.type.readout(13), color = colors.inkMid)
                } else {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = colors.accent, fontWeight = FontWeight.Medium)) {
                                append(onCount.toString())
                            }
                            append("/$total valoa")
                        },
                        style = MkTheme.type.readout(13),
                        color = colors.inkMid,
                    )
                }
            }

            Box(Modifier.padding(top = 13.dp)) {
                MkSceneSelector(
                    value = level,
                    levels = levels ?: LIGHT_LEVELS,
                    onChange = onLevelChange,
                    size = size,
                )
            }

            if (showLights && total > 0) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    lights.forEachIndexed { i, light ->
                        LightChip(
                            light = light,
                            onToggle = onLightToggle?.let { toggle -> { toggle(i, !light.on) } },
                        )
                    }
                }
            }
        }
    }
}

/** Shared area-card header: icon tile, name, status LED, and mode-specific trailing. */
@Composable
private fun AreaTopRow(
    icon: ImageVector,
    name: String,
    lit: Boolean,
    trailing: @Composable () -> Unit,
) {
    val colors = MkTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(MkRadius.sm))
                .background(if (lit) colors.accentDim else colors.track),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = if (lit) colors.accent else colors.inkLo,
                modifier = Modifier.size(19.dp),
            )
        }
        Text(
            text = name,
            modifier = Modifier.weight(1f),
            style = MkTheme.type.heading.copy(fontSize = 16.sp),
            color = colors.inkHi,
        )
        // LED: 10dp, on → accent + glow.
        Box(
            Modifier
                .size(10.dp)
                .then(if (lit) Modifier.mkGlow(colors.accentGlow, radius = 8.dp) else Modifier)
                .background(if (lit) colors.accent else colors.track, CircleShape)
        )
        trailing()
    }
}

/**
 * One light pill; on → accent-dim fill with a glowing accent dot.
 *
 * The design shows these as status only, but a scene ladder cannot express
 * "just this one light", so a tap toggles the individual light when the caller
 * supplies [onToggle].
 */
@Composable
private fun LightChip(light: MkLight, onToggle: (() -> Unit)? = null) {
    val colors = MkTheme.colors
    val on = light.on
    val source = rememberMkInteractionSource()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(MkRadius.round))
            .then(
                if (onToggle != null) {
                    Modifier
                        .mkPressScale(source)
                        .clickable(
                            interactionSource = source,
                            indication = null,
                            onClick = onToggle,
                        )
                } else {
                    Modifier
                }
            )
            .background(if (on) colors.accentDim else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                1.dp,
                if (on) colors.accentBorder else colors.borderSubtle,
                RoundedCornerShape(MkRadius.round),
            )
            .padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .then(if (on) Modifier.mkGlow(colors.accentGlow, radius = 6.dp) else Modifier)
                .background(if (on) colors.accent else colors.track, CircleShape)
        )
        Text(
            light.name,
            style = MkTheme.type.readout(11),
            color = if (on) colors.inkHi else colors.inkLo,
        )
    }
}

/**
 * A single light as a card row: icon tile, name with optional mono meta, and a
 * trailing on/off [MkSwitch].
 */
@Composable
fun MkLightRow(
    name: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    icon: ImageVector = MkIcons.LightbulbFill,
    on: Boolean = false,
    onToggle: (Boolean) -> Unit = {},
    size: MkControlSize = MkControlSize.Md,
) {
    val colors = MkTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MkRadius.lg))
            .background(colors.surfaceCard)
            .border(1.dp, colors.borderSubtle, RoundedCornerShape(MkRadius.lg))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(MkRadius.sm))
                .background(if (on) colors.accentDim else colors.track),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                null,
                tint = if (on) colors.accent else colors.inkLo,
                modifier = Modifier.size(17.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MkTheme.type.body.copy(fontWeight = FontWeight.Medium),
                color = colors.inkHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta != null) {
                Text(meta, style = MkTheme.type.readout(11), color = colors.inkLo)
            }
        }
        MkSwitch(checked = on, onChange = onToggle, size = size)
    }
}
