package fi.marmorikatu.app.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.platform.LockLandscapeWhileVisible
import fi.marmorikatu.app.theme.MkDot
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.app.theme.mkGlow
import fi.marmorikatu.app.theme.mkPressScale
import fi.marmorikatu.app.theme.rememberMkInteractionSource

// ── Data models ──────────────────────────────────────────────────────────────

/** One abnormal KPI surfaced by [MkAttentionStrip]. `status` ∈ ok|warn|alarm|info. */
data class AttentionItem(
    val status: String,
    val icon: ImageVector,
    val text: String,
    val value: String,
)

/** One log line in an [MkEventFeed]. `priority` 0 (critical) → 3 (debug). */
data class EventEntry(
    val priority: Int,
    val text: String,
    val time: String,
    val onClick: (() -> Unit)? = null,
)

/** A person/object detection overlay for [MkCameraShot]. */
data class Detection(
    val label: String? = null,
    val icon: ImageVector? = null,
)

// ── Literal colours (camera + status borders, per ds_spec "hard-coded literal") ──

private val ShotTop = Color(0xFF0F151D)
private val ShotBottom = Color(0xFF0A0E13)
private val ShotLabelInk = Color(0xFFCDD6DF)
private val ShotRecInk = Color(0xFFFF9296)
private val ShotRecDot = Color(0xFFFF5B5B)
private val ShotTsInk = Color(0xFFDBE3EA)
private val DetectionIcon = Color(0xEBFFC982)
private val PillScrim = Color(0x80000000)

// abnormal-status border literals (alpha-over the status hue)
private val AlarmBorderChip = Color(0x52E5484D) // rgba(229,72,77,.32)
private val InfoBorderChip = Color(0x475AA2FF)   // rgba(90,162,255,.28)
private val InfoBorderBanner = Color(0x4D5AA2FF)  // rgba(90,162,255,.30)
private val AlarmTintP0 = Color(0x42E5484D)       // rgba(229,72,77,.26)
private val WarnTintP1 = Color(0x33FFB347)        // rgba(255,179,71,.20)

// ── AlertChip ────────────────────────────────────────────────────────────────

/** One abnormal-KPI line: icon · text · mono value, tinted by [status] (alarm|warn|info). */
@Composable
fun MkAlertChip(
    icon: ImageVector,
    text: String,
    value: String,
    modifier: Modifier = Modifier,
    status: String = "warn",
    onClick: (() -> Unit)? = null,
) {
    val colors = MkTheme.colors
    val bg = colors.statusDim(status)
    val border = when (status) {
        "alarm" -> AlarmBorderChip
        "info" -> InfoBorderChip
        else -> colors.warmBorder
    }
    // spec: info has no dedicated -ink token; fall back to the base info hue.
    val ink = when (status) {
        "alarm" -> colors.statusAlarmInk
        "info" -> colors.statusInfo
        else -> colors.statusWarnInk
    }

    val interaction = rememberMkInteractionSource()
    val clickMod = if (onClick != null) {
        Modifier
            .clickable(interaction, indication = null) { onClick() }
            .mkPressScale(interaction)
    } else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickMod)
            .background(bg, RoundedCornerShape(MkRadius.md))
            .border(1.dp, border, RoundedCornerShape(MkRadius.md))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(17.dp))
        Text(
            text,
            style = MkTheme.type.label.copy(color = colors.inkHi),
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MkTheme.type.readout(11).copy(color = ink))
    }
}

// ── AttentionStrip ───────────────────────────────────────────────────────────

/**
 * "Quiet until it matters": an empty [items] list collapses to one calm
 * "all clear" row; otherwise it surfaces the abnormal KPIs, alarm-first.
 */
@Composable
fun MkAttentionStrip(
    items: List<AttentionItem>,
    modifier: Modifier = Modifier,
    title: String = "Huomio",
    okLabel: String = "Kaikki kunnossa",
    updatedAt: String? = null,
    onItemClick: ((AttentionItem) -> Unit)? = null,
) {
    val colors = MkTheme.colors

    if (items.isEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(colors.accentDim, RoundedCornerShape(MkRadius.md))
                .border(1.dp, colors.borderSubtle, RoundedCornerShape(MkRadius.md))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // spec names ph-check-circle; the icon set only ships Check.
            Icon(MkIcons.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
            Text(okLabel, style = MkTheme.type.label.copy(color = colors.inkHi), modifier = Modifier.weight(1f))
            if (updatedAt != null) {
                Text(
                    "päivitetty $updatedAt",
                    style = MkTheme.type.readout(11).copy(color = colors.inkLo),
                )
            }
        }
        return
    }

    val rank = mapOf("alarm" to 0, "warn" to 1, "info" to 2)
    val sorted = items.sortedBy { rank[it.status] ?: 9 }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(MkIcons.Warning, contentDescription = null, tint = colors.warm, modifier = Modifier.size(16.dp))
            Text(
                title,
                style = MkTheme.type.heading.copy(fontSize = 13.sp, color = colors.inkMid),
            )
            Box(
                Modifier
                    .background(colors.track, RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp),
            ) {
                Text(sorted.size.toString(), style = MkTheme.type.readout(11).copy(color = colors.inkHi))
            }
        }
        sorted.forEach { item ->
            MkAlertChip(
                icon = item.icon,
                text = item.text,
                value = item.value,
                status = item.status,
                onClick = onItemClick?.let { { it(item) } },
            )
        }
    }
}

// ── Banner ───────────────────────────────────────────────────────────────────

/** Prominent inline alert (image-less), tinted by [status] (warn|alarm|info|ok). */
@Composable
fun MkBanner(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    status: String = "warn",
    onClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MkTheme.colors

    val bgBrush: Brush
    val border: Color
    val iconTint: Color
    when (status) {
        "alarm" -> {
            bgBrush = Brush.linearGradient(listOf(colors.statusAlarmDim, colors.surfaceCard))
            border = colors.statusAlarm
            iconTint = colors.statusAlarmInk
        }
        "info" -> {
            bgBrush = Brush.linearGradient(listOf(colors.statusInfoDim, colors.surfaceCard))
            border = InfoBorderBanner
            iconTint = colors.statusInfo // no info-ink token
        }
        "ok" -> {
            bgBrush = Brush.linearGradient(listOf(colors.surfaceCard, colors.surfaceCard))
            border = colors.borderSubtle
            iconTint = colors.statusOk
        }
        else -> { // warn
            bgBrush = Brush.linearGradient(listOf(colors.warmDim, colors.surfaceCard))
            border = colors.warmBorder
            iconTint = colors.warm
        }
    }

    val interaction = rememberMkInteractionSource()
    val clickMod = if (onClick != null) {
        Modifier.clickable(interaction, indication = null) { onClick() }.mkPressScale(interaction)
    } else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickMod)
            .background(bgBrush, RoundedCornerShape(MkRadius.lg))
            .border(1.dp, border, RoundedCornerShape(MkRadius.lg))
            .padding(horizontal = 15.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MkTheme.type.heading.copy(color = colors.inkHi))
            Text(
                text,
                style = MkTheme.type.label.copy(color = colors.inkMid),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

// ── EventItem ────────────────────────────────────────────────────────────────

/** One log row: priority dot · text · mono time. [tint] shades only p0/p1 rows. */
@Composable
fun MkEventItem(
    text: String,
    time: String,
    modifier: Modifier = Modifier,
    priority: Int = 2,
    tint: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MkTheme.colors

    // Priority tint applies only to p0/p1, and only when requested.
    val tinted = tint && (priority == 0 || priority == 1)
    val bg = when {
        tinted && priority == 0 -> colors.statusAlarmDim
        tinted && priority == 1 -> colors.statusWarnDim
        else -> colors.surfaceCard
    }
    val border = when {
        tinted && priority == 0 -> AlarmTintP0
        tinted && priority == 1 -> WarnTintP1
        else -> colors.borderSubtle
    }

    val interaction = rememberMkInteractionSource()
    val clickMod = if (onClick != null) {
        Modifier.clickable(interaction, indication = null) { onClick() }.mkPressScale(interaction)
    } else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickMod)
            .background(bg, RoundedCornerShape(MkRadius.md))
            .border(1.dp, border, RoundedCornerShape(MkRadius.md))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MkDot(color = colors.priority(priority), size = 9.dp)
        Text(
            text,
            style = MkTheme.type.label.copy(color = colors.inkHi),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(time, style = MkTheme.type.readout(11).copy(color = colors.inkLo))
    }
}

// ── EventFeed ────────────────────────────────────────────────────────────────

/** Poll-freshness log wrapper: a breathing "live" header over a list of [MkEventItem]s. */
@Composable
fun MkEventFeed(
    events: List<EventEntry>,
    modifier: Modifier = Modifier,
    live: Boolean = true,
    updatedLabel: String = "juuri nyt",
    tint: Boolean = true,
    onRefresh: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val colors = MkTheme.colors

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (live) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MkDot(color = colors.accent, size = 8.dp, breathing = true)
                Text(
                    "Elää · päivitetty $updatedLabel",
                    style = MkTheme.type.readout(11).copy(color = colors.inkMid),
                    modifier = Modifier.weight(1f),
                )
                if (onRefresh != null) {
                    val interaction = rememberMkInteractionSource()
                    Icon(
                        MkIcons.ArrowsClockwise,
                        contentDescription = "Päivitä",
                        tint = colors.inkLo,
                        modifier = Modifier
                            .size(15.dp)
                            .clickable(interaction, indication = null) { onRefresh() }
                            .mkPressScale(interaction),
                    )
                }
            }
        }
        header?.invoke()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content?.invoke()
            events.forEach { e ->
                MkEventItem(
                    text = e.text,
                    time = e.time,
                    priority = e.priority,
                    tint = tint,
                    onClick = e.onClick,
                )
            }
        }
    }
}

// ── CameraShot ───────────────────────────────────────────────────────────────

/**
 * The still/live camera frame. Pass a [painter] to show a real image, or `null`
 * to render the placeholder gradient + scanlines. [compact] is the DoorAlert thumb.
 */
@Composable
fun MkCameraShot(
    painter: Painter?,
    modifier: Modifier = Modifier,
    camera: String = "ETUPIHA",
    time: String = "",
    live: Boolean = true,
    detection: Detection? = null,
    height: Dp = 160.dp,
    compact: Boolean = false,
) {
    val colors = MkTheme.colors

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .background(Brush.verticalGradient(listOf(ShotTop, ShotBottom))),
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = camera,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            // spec: bottom radial glow ::before (only without an image). Exact colour is
            // external to the bundle; a faint lift keeps the "live sensor" feel.
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.035f), Color.Transparent),
                            center = Offset(size.width / 2f, size.height),
                            radius = size.width * 0.6f,
                        ),
                    )
                },
            )
        }

        // Scanline overlay: 1dp white line at 2.8% alpha every 3dp.
        Box(
            Modifier.matchParentSize().drawBehind {
                val line = 1.dp.toPx()
                val step = 3.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.028f),
                        topLeft = Offset(0f, y),
                        size = Size(size.width, line),
                    )
                    y += step
                }
            },
        )

        // Detection box: only when a detection exists and there is no real image.
        if (detection != null && painter == null) {
            val fx = if (compact) 0.26f else 0.36f
            val fy = if (compact) 0.18f else 0.24f
            val boxW = if (compact) 42.dp else 74.dp
            val boxH = if (compact) 62.dp else 104.dp
            val stroke = if (compact) 1.5.dp else 2.dp
            val iconSize = if (compact) 30.dp else 56.dp
            val offX = maxWidth * fx
            val offY = maxHeight * fy

            if (!compact && !detection.label.isNullOrEmpty()) {
                Box(
                    Modifier
                        .offset(x = offX, y = offY - 16.dp)
                        .background(colors.warm, RoundedCornerShape(MkRadius.xs))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        detection.label,
                        style = MkTheme.type.readout(9).copy(color = colors.inkOnWarm),
                    )
                }
            }

            Box(
                Modifier
                    .offset(x = offX, y = offY)
                    .size(boxW, boxH)
                    .mkGlow(colors.warm, radius = 16.dp, alpha = 0.45f)
                    .border(stroke, colors.warm, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    detection.icon ?: MkIcons.PersonFill,
                    contentDescription = null,
                    tint = DetectionIcon,
                    modifier = Modifier.size(iconSize),
                )
            }
        }

        if (!compact) {
            // Label pill — top-left.
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .background(PillScrim, RoundedCornerShape(MkRadius.xs))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(camera, style = MkTheme.type.tag.copy(color = ShotLabelInk))
            }

            // LIVE badge — top-right.
            if (live) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                        .background(PillScrim, RoundedCornerShape(MkRadius.xs))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MkDot(color = ShotRecDot, size = 6.dp, breathing = true)
                    Text("LIVE", style = MkTheme.type.tag.copy(color = ShotRecInk))
                }
            }

            // Timestamp — bottom-left.
            if (time.isNotEmpty()) {
                Text(
                    time,
                    style = MkTheme.type.readout(11).copy(color = ShotTsInk),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp),
                )
            }
        }
    }
}

// ── CameraCard ───────────────────────────────────────────────────────────────

/** A camera shot with an optional meta row; [latest] rings it warm. */
@Composable
fun MkCameraCard(
    painter: Painter?,
    modifier: Modifier = Modifier,
    camera: String = "Etupiha",
    shotLabel: String? = null,
    time: String = "",
    live: Boolean = true,
    detection: Detection? = null,
    shotHeight: Dp = 160.dp,
    title: String? = null,
    subtitle: String? = null,
    metaTime: String = "",
    priority: Int = 1,
    latest: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.lg)

    val interaction = rememberMkInteractionSource()
    val clickMod = if (onClick != null) {
        Modifier.clickable(interaction, indication = null) { onClick() }.mkPressScale(interaction)
    } else Modifier

    val glowMod = if (latest) {
        // spec: box-shadow 0 0 26px -8px rgba(255,179,71,.38)
        Modifier.shadow(18.dp, shape, ambientColor = colors.warm.copy(alpha = 0.38f), spotColor = colors.warm.copy(alpha = 0.38f))
    } else Modifier

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(clickMod)
            .then(glowMod)
            .background(colors.surfaceCard, shape)
            .border(1.dp, if (latest) colors.warmBorder else colors.borderSubtle, shape)
            .clipToBounds(),
    ) {
        MkCameraShot(
            painter = painter,
            camera = shotLabel ?: camera.uppercase(),
            time = time,
            live = live,
            detection = detection,
            height = shotHeight,
        )
        if (title != null || subtitle != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MkDot(color = colors.priority(priority), size = 9.dp)
                Column(Modifier.weight(1f)) {
                    if (title != null) {
                        Text(
                            title,
                            style = MkTheme.type.label.copy(fontWeight = FontWeight.SemiBold, color = colors.inkHi),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (subtitle != null) {
                        Text(
                            subtitle,
                            style = MkTheme.type.readout(11).copy(color = colors.inkLo),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (metaTime.isNotEmpty()) {
                    Text(metaTime, style = MkTheme.type.readout(11).copy(color = colors.inkLo))
                }
            }
        }
    }
}

// ── DoorAlert ────────────────────────────────────────────────────────────────

/** "At the door" banner: a compact camera thumb, title, and two actions. */
@Composable
fun MkDoorAlert(
    modifier: Modifier = Modifier,
    painter: Painter? = null,
    title: String = "Etupihalla henkilö",
    time: String = "",
    subtitle: String = "Etuvalo syttyi",
    detection: Detection? = Detection(label = ""),
    viewLabel: String = "Katso",
    dismissLabel: String = "Ohita",
    onView: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val colors = MkTheme.colors
    val shape = RoundedCornerShape(MkRadius.lg)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(colors.warmDim, colors.surfaceCard)), shape)
            .border(1.dp, colors.warmBorder, shape)
            .padding(11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(92.dp)
                .clipToBounds()
                .background(ShotBottom, RoundedCornerShape(MkRadius.sm)),
        ) {
            MkCameraShot(
                painter = painter,
                detection = detection,
                height = 92.dp,
                compact = true,
                modifier = Modifier.width(92.dp),
            )
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(MkIcons.DoorOpen, contentDescription = null, tint = colors.warm, modifier = Modifier.size(18.dp))
                Text(title, style = MkTheme.type.heading.copy(color = colors.inkHi))
            }
            Text(
                "$time · $subtitle",
                style = MkTheme.type.readout(11).copy(color = colors.inkMid),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DoorAlertButton(
                    label = viewLabel,
                    bg = colors.warm,
                    fg = colors.inkOnWarm,
                    onClick = onView,
                    modifier = Modifier.weight(1f),
                )
                DoorAlertButton(
                    label = dismissLabel,
                    bg = colors.track,
                    fg = colors.inkMid,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Full-bleed snapshot viewer opened by [MkDoorAlert]'s "Katso" action. A dark
 * scrim over the whole screen; tap anywhere or press "Sulje" to dismiss.
 *
 * Shows an honest "Ei kuvaa" when the announcement carried no image: the bridge
 * only broadcasts the camera still on the *live* event and strips it from the
 * history ring, so an event the app didn't see live (opened after the fact, or
 * replayed) legitimately has no picture to show.
 */
@Composable
fun MkCameraViewer(
    painter: Painter?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    time: String = "",
    camera: String = "ETUPIHA",
    onDismiss: () -> Unit,
) {
    val colors = MkTheme.colors
    // A wide 16:9 still is a thin strip in portrait; go landscape so it fills.
    LockLandscapeWhileVisible()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.94f))
                .clickable(rememberMkInteractionSource(), indication = null) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MkTheme.type.heading.copy(color = Color.White))
                    val caption = listOf(time, subtitle).filter { it.isNotBlank() }.joinToString(" · ")
                    if (caption.isNotEmpty()) {
                        Text(
                            caption,
                            style = MkTheme.type.readout(11).copy(color = Color.White.copy(alpha = 0.72f)),
                        )
                    }
                }

                // Fill the space between caption and button; ContentScale.Fit keeps
                // the aspect ratio and centres, so it's as large as the frame allows.
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (painter != null) {
                        Image(
                            painter = painter,
                            contentDescription = camera,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(MkRadius.lg)),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(MkRadius.lg))
                                .background(Brush.verticalGradient(listOf(ShotTop, ShotBottom))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Ei kuvaa",
                                style = MkTheme.type.readout(12).copy(color = Color.White.copy(alpha = 0.6f)),
                            )
                        }
                    }
                }

                DoorAlertButton(
                    label = "Sulje",
                    bg = colors.warm,
                    fg = colors.inkOnWarm,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The primary/ghost action button used inside [MkDoorAlert]. */
@Composable
private fun DoorAlertButton(
    label: String,
    bg: Color,
    fg: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val interaction = rememberMkInteractionSource()
    Box(
        modifier = modifier
            .clickable(interaction, indication = null) { onClick?.invoke() }
            .mkPressScale(interaction)
            .background(bg, RoundedCornerShape(MkRadius.sm))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MkTheme.type.label.copy(fontWeight = FontWeight.SemiBold, color = fg))
    }
}
