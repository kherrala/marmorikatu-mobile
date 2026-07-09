package fi.marmorikatu.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkTheme
import kotlin.math.abs
import kotlin.math.max
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
enum class BarState { Past, Future, Exp }

/** One hourly spot-price bar. */
data class MkPriceBar(val value: Float, val state: BarState = BarState.Future)

/** One key/value cell in a [MkMetricDetail] stats row. */
data class MkStat(val k: String, val v: String)

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
) {
    val colors = MkTheme.colors
    val allValues = series.flatMap { it.values }
    val lo = min ?: (allValues.minOrNull() ?: 0f)
    val hiRaw = max ?: (allValues.maxOrNull() ?: 1f)
    val span = (hiRaw - lo).let { if (it == 0f) 1f else it }

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

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
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
        }

        if (labels.isNotEmpty()) {
            AxisLabels(labels)
        }
    }
}

/** Shared `mk-chart-axis` row: space-between mono 10sp axis labels. */
@Composable
private fun AxisLabels(labels: List<String>) {
    val colors = MkTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
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
            Row(
                modifier = Modifier.padding(bottom = 9.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    text = "$nowValue $nowUnit",
                    style = MkTheme.type.readout(24, FontWeight.SemiBold),
                    color = colors.inkHi,
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
                    BarState.Past -> colors.accent.copy(alpha = 0.38f)
                    BarState.Future -> colors.accent
                    BarState.Exp -> colors.warm
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
@Composable
fun MkMetricDetail(
    icon: ImageVector,
    label: String,
    value: String,
    unit: String,
    range: TimeRangeOption,
    onRangeChange: (TimeRangeOption) -> Unit,
    series: List<MkSeries>,
    labels: List<String>,
    stats: List<MkStat>,
    modifier: Modifier = Modifier,
    status: String = "accent",
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

    MkCard(modifier = modifier, padding = MkCardPadding.PadLg) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = lineColor,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(24.dp),
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
                    Text(
                        text = value,
                        style = MkTheme.type.readout(38),
                        color = lineColor,
                    )
                    Text(
                        text = unit,
                        style = MkTheme.type.readout(20),
                        color = colors.inkLo,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            }
            MkTimeRange(value = range, onChange = onRangeChange)
        }

        MkLineChart(
            series = series,
            labels = labels,
            showLegend = false,
        )

        if (stats.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .drawTopHairline(colors.borderSubtle)
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                stats.forEach { stat ->
                    Column {
                        Text(
                            text = stat.k.uppercase(),
                            style = MkTheme.type.readout(10).copy(letterSpacing = 0.1.em),
                            color = colors.inkLo,
                        )
                        Text(
                            text = stat.v,
                            style = MkTheme.type.readout(17),
                            color = colors.inkHi,
                        )
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
