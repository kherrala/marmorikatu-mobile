package fi.marmorikatu.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// ---------------------------------------------------------------------------
// Data types
// ---------------------------------------------------------------------------

/** One line/area in a [MkLineChart]. */
data class MkSeries(
    val name: String?,
    val values: List<Float>,
    val color: Color,
    val area: Boolean = false,
)

/** Hour classification for a [MkPriceBar]. */
enum class BarState { Past, Future, Exp, Cheap }

/** One hourly spot-price bar. */
data class MkPriceBar(val value: Float, val state: BarState = BarState.Future)

/** One key/value cell in a [MkMetricDetail] stats row. */
data class MkStat(val k: String, val v: String)

// ---------------------------------------------------------------------------
// Sparkline
// ---------------------------------------------------------------------------

/**
 * A tiny inline trend line for stat tiles — no axes, no labels. Mirrors the
 * design's `<polyline>` in a 100×22 viewBox: the series is scaled to fill the
 * height with a small top/bottom inset. Renders an empty spacer for < 2 points
 * so the tile keeps a stable height whether or not history has loaded.
 */
@Composable
fun MkSparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 20.dp,
) {
    if (values.size < 2) {
        Spacer(modifier.height(height))
        return
    }
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val mn = values.min()
        val mx = values.max()
        val rg = (mx - mn).let { if (it == 0f) 1f else it }
        val inset = 2.dp.toPx()
        val usableH = size.height - inset * 2
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = inset + (1f - (v - mn) / rg) * usableH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

// ---------------------------------------------------------------------------
// LineChart
// ---------------------------------------------------------------------------

/** Multi-series line / area chart drawn on a [Canvas] (no chart library). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MkLineChart(
    series: List<MkSeries>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    min: Float? = null,
    max: Float? = null,
    height: Dp = 180.dp,
    grid: Int = 4,
    showLegend: Boolean = true,
    showYAxis: Boolean = true,
    /** Drag across the chart to read each sample: crosshair + value tooltip. */
    scrubbable: Boolean = false,
) {
    val colors = MkTheme.colors
    val allValues = series.flatMap { it.values }
    val lo = min ?: (allValues.minOrNull() ?: 0f)
    val hiRaw = max ?: (allValues.maxOrNull() ?: 1f)
    val span = (hiRaw - lo).let { if (it == 0f) 1f else it }
    val yAxisWidth = 34.dp
    // Fraction (0..1) of the chart width the finger is at, or null when not scrubbing.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val textMeasurer = rememberTextMeasurer()

    Column(modifier = modifier.fillMaxWidth()) {
        if (showLegend && series.any { it.name != null }) {
            FlowRow(
                modifier = Modifier.padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                series.forEach { s ->
                    val name = s.name ?: return@forEach
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Spacer(
                            Modifier
                                .size(width = 10.dp, height = 3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(s.color),
                        )
                        Text(
                            text = name,
                            style = MkTheme.type.readout(10),
                            color = colors.inkMid,
                        )
                    }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            if (showYAxis) {
                YAxisLabels(lo = lo, hi = hiRaw, ticks = grid + 1, height = height, width = yAxisWidth)
            }
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    // Keep the plot inside its box: an out-of-range value must never
                    // draw up over the legend or down past the axis.
                    .clipToBounds()
                    .then(
                        if (scrubbable && series.any { it.values.size > 1 }) {
                            Modifier.pointerInput(series) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    scrubFraction = (down.position.x / size.width).coerceIn(0f, 1f)
                                    down.consume()
                                    do {
                                        val event = awaitPointerEvent()
                                        event.changes.firstOrNull()?.let { c ->
                                            scrubFraction = (c.position.x / size.width).coerceIn(0f, 1f)
                                            c.consume()
                                        }
                                    } while (event.changes.any { it.pressed })
                                    scrubFraction = null
                                }
                            }
                        } else Modifier,
                    ),
            ) {
            val w = size.width
            val h = size.height

            // Gridlines: grid + 1 evenly spaced horizontal lines.
            val lines = grid + 1
            for (i in 0 until lines) {
                val y = if (lines == 1) 0f else i.toFloat() / (lines - 1) * h
                drawLine(
                    color = colors.vizGrid,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            fun px(i: Int, n: Int): Float = if (n <= 1) 0f else i.toFloat() / (n - 1) * w
            fun py(v: Float): Float = h - (v - lo) / span * h

            series.forEach { s ->
                val values = s.values
                if (values.isEmpty()) return@forEach
                val n = values.size

                if (s.area) {
                    val areaPath = Path()
                    if (n == 1) {
                        val y = py(values[0])
                        areaPath.moveTo(0f, y)
                        areaPath.lineTo(w, y)
                    } else {
                        areaPath.moveTo(px(0, n), py(values[0]))
                        for (i in 1 until n) areaPath.lineTo(px(i, n), py(values[i]))
                    }
                    areaPath.lineTo(w, h)
                    areaPath.lineTo(0f, h)
                    areaPath.close()
                    drawPath(areaPath, color = s.color.copy(alpha = 0.1f))
                }

                val linePath = Path()
                if (n == 1) {
                    val y = py(values[0])
                    linePath.moveTo(0f, y)
                    linePath.lineTo(w, y)
                } else {
                    linePath.moveTo(px(0, n), py(values[0]))
                    for (i in 1 until n) linePath.lineTo(px(i, n), py(values[i]))
                }
                drawPath(
                    path = linePath,
                    color = s.color,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }

            // Scrub crosshair + value tooltip at the nearest sample.
            val frac = scrubFraction
            val n = series.maxOfOrNull { it.values.size } ?: 0
            if (frac != null && n > 1) {
                val idx = (frac * (n - 1)).roundToInt().coerceIn(0, n - 1)
                val cx = px(idx, n)
                drawLine(colors.inkLo.copy(alpha = 0.45f), Offset(cx, 0f), Offset(cx, h), 1.dp.toPx())
                series.forEach { s ->
                    s.values.getOrNull(idx)?.let { v -> drawCircle(s.color, 4.dp.toPx(), Offset(cx, py(v))) }
                }
                val text = series.mapNotNull { s -> s.values.getOrNull(idx)?.let { formatDecimals(it, 1) } }
                    .joinToString("  ")
                if (text.isNotEmpty()) {
                    val layout = textMeasurer.measure(text, style = TextStyle(fontSize = 11.sp, color = colors.inkHi))
                    val pad = 6.dp.toPx()
                    val boxW = layout.size.width + pad * 2
                    val boxH = layout.size.height + pad
                    val bx = (cx - boxW / 2).coerceIn(0f, (w - boxW).coerceAtLeast(0f))
                    drawRoundRect(
                        color = colors.surfaceRaised,
                        topLeft = Offset(bx, 0f),
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                    drawText(layout, topLeft = Offset(bx + pad, pad / 2))
                }
            }
            }
        }

        if (labels.isNotEmpty()) {
            AxisLabels(labels, startPadding = if (showYAxis) yAxisWidth else 0.dp)
        }
    }
}

/**
 * Value axis down the left edge: [ticks] labels from [hi] (top) to [lo] (bottom),
 * spaced to line up with the chart's horizontal gridlines.
 */
@Composable
private fun YAxisLabels(lo: Float, hi: Float, ticks: Int, height: Dp, width: Dp) {
    val colors = MkTheme.colors
    val span = hi - lo
    val decimals = if (kotlin.math.abs(span) >= 20f || kotlin.math.abs(hi) >= 100f) 0 else 1
    Column(
        modifier = Modifier
            .width(width)
            .height(height)
            .padding(end = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        for (i in 0 until ticks) {
            val v = hi - i.toFloat() / (ticks - 1).coerceAtLeast(1) * span
            Text(
                text = formatDecimals(v, decimals),
                style = MkTheme.type.readout(9),
                color = colors.vizAxis,
                maxLines = 1,
            )
        }
    }
}

/** Shared `mk-chart-axis` row: space-between mono 10sp axis labels. */
@Composable
private fun AxisLabels(labels: List<String>, startPadding: Dp = 0.dp) {
    val colors = MkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding, top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        labels.forEach {
            Text(text = it, style = MkTheme.type.readout(10), color = colors.vizAxis)
        }
    }
}

// ---------------------------------------------------------------------------
// PriceBars
// ---------------------------------------------------------------------------

/** Spot-price hourly bars with a "now" readout. */
@Composable
fun MkPriceBars(
    bars: List<MkPriceBar>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    nowValue: String? = null,
    nowUnit: String = "c/kWh",
    nowTag: String? = null,
    nowStatus: String = "ok",
) {
    val colors = MkTheme.colors
    val maxVal = max(bars.maxOfOrNull { it.value } ?: 1f, 1f)

    Column(modifier = modifier.fillMaxWidth()) {
        if (nowValue != null) {
            // FlowRow so a narrow kiosk column drops the tag onto its own line
            // instead of squeezing (and clipping) it beside the value.
            FlowRow(
                modifier = Modifier.padding(bottom = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "$nowValue $nowUnit",
                    style = MkTheme.type.readout(24, FontWeight.SemiBold),
                    color = colors.inkHi,
                    maxLines = 1,
                )
                if (nowTag != null) {
                    PriceTag(text = nowTag, status = nowStatus)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            verticalAlignment = Alignment.Bottom,
            // A full day is 96 quarter-hours; a fixed 3dp gap would consume more
            // width than the card has and starve every bar of its weight.
            horizontalArrangement = Arrangement.spacedBy(
                when {
                    bars.size > 64 -> 1.dp
                    bars.size > 32 -> 2.dp
                    else -> 3.dp
                }
            ),
        ) {
            bars.forEach { bar ->
                val barColor = when (bar.state) {
                    // Past hours in a neutral grey so they read clearly as elapsed,
                    // distinct from the green/orange upcoming bars.
                    BarState.Past -> colors.inkMid.copy(alpha = 0.5f)
                    BarState.Future -> colors.accent
                    BarState.Exp -> colors.warm
                    BarState.Cheap -> colors.statusOk
                }
                val frac = (bar.value / maxVal).coerceIn(0f, 1f)
                val barHeight = (height * frac).coerceAtLeast(2.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(barHeight)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(barColor),
                )
            }
        }

        if (labels.isNotEmpty()) {
            AxisLabels(labels)
        }
    }
}

/** Inline mono tag pill (mirrors the Core `mk-tag` for the "now" readout). */
@Composable
private fun PriceTag(text: String, status: String) {
    val colors = MkTheme.colors
    Text(
        text = text,
        style = MkTheme.type.tag.copy(letterSpacing = 0.04.em),
        color = colors.status(status),
        // A tag is a single pill; never let a narrow column wrap it letter-by-letter.
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(MkRadius.xs))
            .background(colors.statusDim(status))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

// ---------------------------------------------------------------------------
// Gauge
// ---------------------------------------------------------------------------

/** 270° arc gauge with a centred mono readout. */
@Composable
fun MkGauge(
    value: Float,
    max: Float,
    label: String,
    modifier: Modifier = Modifier,
    unit: String = "%",
    size: Dp = 120.dp,
    status: String = "accent",
    decimals: Int = 0,
) {
    val colors = MkTheme.colors
    // Gauge alarm uses --status-alarm (not the ink variant — see MetricDetail).
    val progressColor = when (status) {
        "ok" -> colors.statusOk
        "warn" -> colors.warm
        "alarm" -> colors.statusAlarm
        "info" -> colors.statusInfo
        else -> colors.accent
    }
    val frac = if (max == 0f) 0f else (value / max).coerceIn(0f, 1f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(size)) {
                val stroke = 9.dp.toPx()
                val r = this.size.minDimension / 2f - 10.dp.toPx()
                val topLeft = Offset(this.size.width / 2f - r, this.size.height / 2f - r)
                val arcSize = Size(r * 2f, r * 2f)
                drawArc(
                    color = colors.track,
                    startAngle = 135f,
                    sweepAngle = 270f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                drawArc(
                    color = progressColor,
                    startAngle = 135f,
                    sweepAngle = 270f * frac,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatDecimals(value, decimals),
                    style = MkTheme.type.readout(24, FontWeight.SemiBold),
                    color = progressColor,
                )
                Text(
                    text = unit,
                    style = MkTheme.type.readout(13),
                    color = colors.inkLo,
                )
            }
        }
        Text(
            text = label,
            style = TextStyle(
                fontFamily = MkTheme.type.ui,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            ),
            color = colors.inkMid,
        )
    }
}

// ---------------------------------------------------------------------------
// MetricDetail
// ---------------------------------------------------------------------------

/**
 * KPI detail card: header with a big readout and range selector, a line chart,
 * and a stats row. Built on [MkCard] and [MkTimeRange].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MkMetricDetail(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    series: List<MkSeries>,
    labels: List<String>,
    stats: List<MkStat>,
    modifier: Modifier = Modifier,
    status: String = "accent",
    // Optional: only a metric with real range-dependent history shows the picker.
    range: TimeRangeOption? = null,
    onRangeChange: ((TimeRangeOption) -> Unit)? = null,
    /** When set, a "Takaisin" button rides the top row beside the range picker. */
    onBack: (() -> Unit)? = null,
) {
    val colors = MkTheme.colors
    // Note: MetricDetail alarm is the ink variant, unlike Gauge's --status-alarm.
    val lineColor = when (status) {
        "ok" -> colors.statusOk
        "warn" -> colors.warm
        "alarm" -> colors.statusAlarmInk
        "info" -> colors.statusInfo
        else -> colors.accent
    }

    // Enrich the caller's stats with min/max/avg read straight off the plotted
    // series, so the side panel carries more than the current reading. Skipped for
    // state metrics ("Käy"/"Seis") and for any key the caller already supplies
    // (e.g. the price detail's own min/max/ka), which keep the caller's formatting.
    val numericReadout = value.trimStart('-', '+', ' ').firstOrNull()?.isDigit() == true
    val mergedStats = remember(stats, series, unit, numericReadout) {
        val vals = series.firstOrNull()?.values.orEmpty()
        val derived = if (numericReadout && vals.size >= 2) {
            fun fmt(v: Float): String =
                if (unit.isBlank()) Fmt.oneDecimal(v.toDouble()) else "${Fmt.oneDecimal(v.toDouble())} $unit"
            listOf(
                MkStat("min", fmt(vals.min())),
                MkStat("max", fmt(vals.max())),
                MkStat("ka", fmt(vals.average().toFloat())),
            )
        } else {
            emptyList()
        }
        stats + derived.filter { d -> stats.none { it.k.equals(d.k, ignoreCase = true) } }
    }

    // Reusable pieces so the portrait (stacked) and landscape (side-by-side)
    // layouts share one definition.
    val header: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = lineColor,
                modifier = Modifier.padding(top = 2.dp).size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = TextStyle(
                        fontFamily = MkTheme.type.ui,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    ),
                    color = colors.inkMid,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(text = value, style = MkTheme.type.readout(38), color = lineColor)
                    // Only pair a unit with a numeric readout — a state word like
                    // "Käy" / "Seis" / "Ei tietoa" must not read "Käy kW".
                    val numeric = value.trimStart('-', '+', ' ').firstOrNull()?.isDigit() == true
                    if (numeric && unit.isNotBlank()) {
                        Text(
                            text = unit,
                            style = MkTheme.type.readout(20),
                            color = colors.inkLo,
                            modifier = Modifier.padding(start = 3.dp),
                        )
                    }
                }
            }
        }
    }
    val rangePicker: @Composable () -> Unit = {
        if (range != null && onRangeChange != null) {
            MkTimeRange(value = range, onChange = onRangeChange, modifier = Modifier.fillMaxWidth())
        }
    }
    val statsRow: @Composable (Boolean) -> Unit = { hairline ->
        if (mergedStats.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (hairline) Modifier.drawTopHairline(colors.borderSubtle).padding(top = 12.dp) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                mergedStats.forEach { stat ->
                    Column {
                        Text(
                            text = stat.k.uppercase(),
                            style = MkTheme.type.readout(10).copy(letterSpacing = 0.1.em),
                            color = colors.inkLo,
                        )
                        Text(text = stat.v, style = MkTheme.type.readout(17), color = colors.inkHi)
                    }
                }
            }
        }
    }
    // Vertical stat list for the wide layout's narrow side panel: stacking fits more
    // figures in less width, leaving the rest for a wider chart.
    val statsColumn: @Composable () -> Unit = {
        if (mergedStats.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                mergedStats.forEach { stat ->
                    Column {
                        Text(
                            text = stat.k.uppercase(),
                            style = MkTheme.type.readout(10).copy(letterSpacing = 0.1.em),
                            color = colors.inkLo,
                        )
                        Text(text = stat.v, style = MkTheme.type.readout(17), color = colors.inkHi)
                    }
                }
            }
        }
    }

    MkCard(modifier = modifier, padding = MkCardPadding.PadLg) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // Landscape / kiosk: put the readout + range + stats in a left column
            // and give the chart the whole right side, so rotating actually makes
            // the chart bigger instead of pushing it off the bottom.
            val wide = maxWidth >= 560.dp
            if (wide) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Back button (left) + range selector (right) share the top row,
                    // above the chart, so the whole detail fits without scrolling.
                    if (onBack != null || (range != null && onRangeChange != null)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (onBack != null) {
                                MkButton(
                                    text = "Takaisin",
                                    onClick = onBack,
                                    variant = MkButtonVariant.Ghost,
                                    size = MkButtonSize.Sm,
                                    icon = MkIcons.CaretLeft,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (range != null && onRangeChange != null) {
                                MkTimeRange(value = range, onChange = onRangeChange)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(
                            modifier = Modifier.weight(0.28f),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            header()
                            statsColumn()
                        }
                        MkLineChart(
                            series = series,
                            labels = labels,
                            height = 190.dp,
                            showLegend = false,
                            scrubbable = true,
                            modifier = Modifier.weight(0.72f),
                        )
                    }
                }
            } else {
                Column {
                    if (onBack != null) {
                        MkButton(
                            text = "Takaisin",
                            onClick = onBack,
                            variant = MkButtonVariant.Ghost,
                            size = MkButtonSize.Sm,
                            icon = MkIcons.CaretLeft,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    header()
                    Spacer(Modifier.height(14.dp))
                    if (range != null && onRangeChange != null) {
                        rangePicker()
                        Spacer(Modifier.height(12.dp))
                    }
                    MkLineChart(series = series, labels = labels, showLegend = false, scrubbable = true)
                    if (stats.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        statsRow(true)
                    }
                }
            }
        }
    }
}

/** A 1px hairline drawn along the top edge (spec: stats `border-top`). */
private fun Modifier.drawTopHairline(color: Color): Modifier =
    this.drawBehind {
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1.dp.toPx(),
        )
    }

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Format a float with a fixed number of decimals (no java.* / platform deps). */
private fun formatDecimals(value: Float, decimals: Int): String {
    if (decimals <= 0) return value.roundToLong().toString()
    var pow = 1L
    repeat(decimals) { pow *= 10 }
    val scaled = (value * pow).roundToLong()
    val neg = scaled < 0
    val a = abs(scaled)
    val intPart = a / pow
    val frac = (a % pow).toString().padStart(decimals, '0')
    return "${if (neg) "-" else ""}$intPart.$frac"
}
