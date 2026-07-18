package fi.marmorikatu.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.WeatherDay
import fi.marmorikatu.core.model.WeatherForecast
import fi.marmorikatu.core.model.WeatherHour
import fi.marmorikatu.core.model.WeatherWarning
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Outdoor weather, per the design's header chip (a condition glyph, temperature
 * and a Finnish condition word). Expanded to a card: current conditions with a
 * feels-like / wind / humidity meta row and a compact next-hours strip, all from
 * [WeatherForecast]. Absent readings degrade to "–" rather than a fabricated
 * number, and the whole widget is meant to sit under a SectionLabel("Sää").
 */
/** A labelled outdoor-temperature reading for the weather card's source row. */
data class WeatherReading(val label: String, val celsius: Double)

@Composable
fun MkWeatherWidget(
    forecast: WeatherForecast,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** Opens the full 7-day forecast page from the footer "7 vrk" link. */
    onForecast: (() -> Unit)? = null,
    night: Boolean = isNightNow(),
    /**
     * On-site outdoor temperature that overrides the forecast's own reading for
     * the big readout (e.g. the local Ruuvi), with [outdoorSource] naming it and
     * [outdoorAlternatives] listing the other sensors for comparison.
     */
    outdoorTempOverride: Double? = null,
    outdoorSource: String? = null,
    outdoorAlternatives: List<WeatherReading> = emptyList(),
) {
    val c = MkTheme.colors
    val now = forecast.current
    val icon = weatherIcon(now.weatherCode, now.condition, night)
    // Sparse next-~24 h points (every ~3 h) from the backend; show up to eight.
    val hours = forecast.hourlyNext4h.take(8)
    val outdoorTemp = outdoorTempOverride ?: now.temperature

    MkCard(modifier = modifier, interactive = onClick != null, onClick = onClick) {
        // Weather warnings (helle / myrsky) are surfaced in the home attention strip
        // like every other alert, not inside this card (design).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MkSpacing.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Condition glyph on its own — no tile background (design).
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(icon, null, tint = c.accent, modifier = Modifier.size(40.dp))
            }
            // Big temperature with the condition beside it, then feels-like +
            // location and the on-site source badge underneath.
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = outdoorTemp?.let { Fmt.oneDecimal(it) } ?: "–",
                        style = MkTheme.type.readout(34),
                        color = c.inkHi,
                        modifier = Modifier.alignByBaseline(),
                        maxLines = 1,
                    )
                    Text(
                        text = "°",
                        style = MkTheme.type.readout(16),
                        color = c.inkLo,
                        modifier = Modifier.alignByBaseline(),
                        maxLines = 1,
                    )
                    // Marquee so a long condition ("Kevyttä tihkua") reads in full
                    // beside the temperature instead of being ellipsised.
                    MarqueeText(
                        text = now.condition.ifBlank { "Ulkosää" }.replaceFirstChar { it.uppercase() },
                        style = MkTheme.type.heading,
                        color = c.inkMid,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = listOfNotNull(
                            now.feelsLike?.let { "Tuntuu ${Fmt.oneDecimal(it)}°" },
                            HOME_CITY,
                        ).joinToString(" · "),
                        style = MkTheme.type.readout(11, FontWeight.Normal),
                        color = c.inkLo,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    outdoorSource?.let { WeatherSourceBadge(it) }
                }
            }
            // Today's high / low.
            val today = forecast.dailyForecast.firstOrNull()
            if (today?.tempMax != null || today?.tempMin != null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    today.tempMax?.let {
                        Text("↑${Fmt.int(it)}°", style = MkTheme.type.readout(13), color = c.warm, maxLines = 1)
                    }
                    today.tempMin?.let {
                        Text("↓${Fmt.int(it)}°", style = MkTheme.type.readout(13), color = c.accent, maxLines = 1)
                    }
                }
            }
        }

        // The other outdoor sensors, small, so their readings can be eyeballed
        // against the headline number without any interaction.
        if (outdoorAlternatives.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x2),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x4),
            ) {
                outdoorAlternatives.forEach { reading ->
                    Text(
                        text = "${reading.label} ${Fmt.oneDecimal(reading.celsius)}°",
                        style = MkTheme.type.readout(11),
                        color = c.inkLo,
                        maxLines = 1,
                    )
                }
            }
        }

        // Next-hours strip, set off from the top block by a divider (design).
        if (hours.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MkSpacing.x3)
                    .height(1.dp)
                    .background(c.borderSubtle),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                hours.forEach { hour -> WeatherHourCell(hour, night, Modifier.weight(1f)) }
            }
        }

        // Bottom meta: wind · humidity · the next sun event, then the 7-day link.
        val meta = buildList {
            // The backend's `wind_speed_ms` is mislabelled: Open-Meteo returns km/h
            // (its default), so convert to real m/s (the Finnish convention) here.
            now.windSpeedMs?.let { add(MkIcons.Wind to "${Fmt.oneDecimal(it / 3.6)} m/s") }
            now.humidity?.let { add(MkIcons.DropHalf to "${Fmt.int(it)} %") }
            // Only the sun event that's actually ahead — sunrise before it rises,
            // sunset through the day (design shows one, not both).
            homeSunTimes()?.let { (rise, set) -> add(MkIcons.SunHorizon to nextSunLabel(rise, set)) }
        }
        if (meta.isNotEmpty() || onForecast != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                meta.forEach { (metaIcon, text) -> WeatherMeta(metaIcon, text) }
                if (onForecast != null) {
                    Spacer(Modifier.weight(1f))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(MkRadius.round))
                            .clickable(onClick = onForecast)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("7 vrk", style = MkTheme.type.readout(12), color = c.accent, maxLines = 1)
                        Icon(MkIcons.CaretRight, null, tint = c.accent, modifier = Modifier.size(13.dp))
                    }
                }
            }
        }
    }
}

private const val HOME_CITY = "Tampere"
private const val HOME_LAT = 61.4978
private const val HOME_LON = 23.7610

/**
 * Local sunrise/sunset ("HH:mm") for the fixed home location, from the standard
 * sunrise equation. The backend computes the same thing with `astral` at these
 * coordinates; doing it on-device avoids depending on a field the weather API
 * proxy drops. Verified against the backend's astral output. Null on polar
 * day/night (never happens at Tampere's latitude in practice).
 */
@OptIn(ExperimentalTime::class)
private fun homeSunTimes(): Pair<String, String>? {
    fun rad(d: Double) = d * PI / 180.0
    val jNow = Clock.System.now().toEpochMilliseconds() / 86_400_000.0 + 2440587.5
    val n = ceil(jNow - 2451545.0 + 0.0008)
    val jStar = n - HOME_LON / 360.0
    val m = (357.5291 + 0.98560028 * jStar) % 360.0
    val cCenter = 1.9148 * sin(rad(m)) + 0.0200 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
    val lambda = (m + cCenter + 180.0 + 102.9372) % 360.0
    val jTransit = 2451545.0 + jStar + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
    val sinDec = sin(rad(lambda)) * sin(rad(23.4397))
    val cosDec = cos(asin(sinDec))
    val cosOmega = (sin(rad(-0.833)) - sin(rad(HOME_LAT)) * sinDec) / (cos(rad(HOME_LAT)) * cosDec)
    if (cosOmega < -1.0 || cosOmega > 1.0) return null
    val omegaDeg = acos(cosOmega) * 180.0 / PI
    val zone = TimeZone.of("Europe/Helsinki")
    fun clock(jd: Double): String {
        val ms = ((jd - 2440587.5) * 86_400_000.0).toLong()
        val ldt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
        return "${ldt.hour.toString().padStart(2, '0')}:${ldt.minute.toString().padStart(2, '0')}"
    }
    return clock(jTransit - omegaDeg / 360.0) to clock(jTransit + omegaDeg / 360.0)
}

/** Small accent pill naming which sensor the headline temperature came from. */
@Composable
private fun WeatherSourceBadge(source: String) {
    val c = MkTheme.colors
    Text(
        text = source,
        style = MkTheme.type.readout(10, FontWeight.Medium).copy(letterSpacing = 0.04.em),
        color = c.accent,
        maxLines = 1,
        modifier = Modifier
            .padding(top = 3.dp)
            .clip(RoundedCornerShape(MkRadius.round))
            .background(c.accentDim)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** One feels-like / wind / humidity chip: a muted glyph and its mono readout. */
@Composable
private fun WeatherMeta(icon: ImageVector, text: String) {
    val c = MkTheme.colors
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.inkMid, modifier = Modifier.size(16.dp))
        Text(
            text = text,
            style = MkTheme.type.readout(12),
            color = c.inkMid,
            maxLines = 1,
        )
    }
}

/** One column of the next-hours strip. */
@Composable
private fun WeatherHourCell(hour: WeatherHour, night: Boolean, modifier: Modifier = Modifier) {
    val c = MkTheme.colors
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = hourLabel(hour.time),
            style = MkTheme.type.readout(10).copy(letterSpacing = 0.06.em),
            color = c.inkLo,
            maxLines = 1,
        )
        Icon(
            weatherIcon(null, hour.condition, night),
            null,
            tint = c.inkMid,
            modifier = Modifier.size(18.dp),
        )
        // Forecast temperatures are whole degrees — no decimals (design).
        Text(
            text = hour.temperature?.let { "${Fmt.int(it)}°" } ?: "–",
            style = MkTheme.type.readout(13),
            color = c.inkHi,
            maxLines = 1,
        )
    }
}

/**
 * The next sun event as a signed clock — "↑HH:MM" for the coming sunrise,
 * "↓HH:MM" for the coming sunset — so the footer shows only the one still ahead
 * (design shows a single sun time, not both).
 */
@OptIn(ExperimentalTime::class)
private fun nextSunLabel(rise: String, set: String): String {
    fun mins(hhmm: String): Int? {
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }
    val nowMin = Clock.System.now()
        .toLocalDateTime(TimeZone.of("Europe/Helsinki"))
        .let { it.hour * 60 + it.minute }
    val r = mins(rise)
    val s = mins(set)
    return when {
        r != null && nowMin < r -> "↑$rise" // before dawn → sunrise is next
        s != null && nowMin < s -> "↓$set"  // daytime → sunset is next
        else -> "↑$rise"                     // after dusk → tomorrow's sunrise
    }
}

/**
 * Condition → glyph. Prefers the WMO [code] the backend sends (Open-Meteo), and
 * falls back to matching the Finnish condition word. The icon set has no fog /
 * plain-cloud glyph, so overcast and fog reuse the cloud-with-sun/moon pair.
 */

private fun weatherIcon(code: Int?, condition: String, night: Boolean): ImageVector {
    code?.let {
        return when (it) {
            0 -> if (night) MkIcons.Moon else MkIcons.Sun
            1, 2, 3 -> if (night) MkIcons.CloudMoon else MkIcons.CloudSun
            45, 48 -> if (night) MkIcons.CloudMoon else MkIcons.CloudSun
            in 51..67 -> MkIcons.Drop
            in 71..77 -> MkIcons.Snowflake
            in 80..82 -> MkIcons.Drop
            85, 86 -> MkIcons.Snowflake
            in 95..99 -> MkIcons.Lightning
            else -> if (night) MkIcons.CloudMoon else MkIcons.CloudSun
        }
    }
    val s = condition.lowercase()
    return when {
        s.contains("ukkon") -> MkIcons.Lightning
        s.contains("lumi") || s.contains("räntä") -> MkIcons.Snowflake
        s.contains("sade") || s.contains("kuuro") || s.contains("tihku") -> MkIcons.Drop
        s.contains("pilv") || s.contains("sumu") -> if (night) MkIcons.CloudMoon else MkIcons.CloudSun
        else -> if (night) MkIcons.Moon else MkIcons.Sun
    }
}

/** Backend hourly times arrive ISO-like ("2026-07-11T15:00"); show `HH:MM`. */
private fun hourLabel(time: String): String {
    val t = time.substringAfter('T', time)
    return if (t.length >= 5) t.substring(0, 5) else t
}

/** Simple day/night split for the glyph — the icon set has no twilight variant. */
@OptIn(ExperimentalTime::class)
private fun isNightNow(): Boolean {
    val hour = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour
    return hour < 6 || hour >= 21
}

// ── Full 7-day forecast page ─────────────────────────────────────────────────

/**
 * The full weather page the home widget's "7 vrk" link opens (design's `saa`
 * screen): the current-conditions [weatherCard] up top, the coming days as a
 * min–max temperature ladder, and the deduced weather warnings (or an all-clear).
 */
@Composable
fun MkWeatherForecastSheet(
    forecast: WeatherForecast,
    weatherCard: @Composable () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MkTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(c.appBg)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MkSpacing.pagePad, vertical = MkSpacing.x3),
            verticalArrangement = Arrangement.spacedBy(MkSpacing.stackGap),
        ) {
            MkButton(
                text = "Takaisin",
                onClick = onDismiss,
                variant = MkButtonVariant.Ghost,
                size = MkButtonSize.Sm,
                icon = MkIcons.CaretLeft,
            )

            weatherCard()

            val days = forecast.dailyForecast
            if (days.isNotEmpty()) {
                ForecastKicker("Seuraavat päivät")
                // One shared temperature span so every day's bar reads on the same
                // scale; guard the degenerate all-equal case so the bars still show.
                val lows = days.mapNotNull { it.tempMin }
                val highs = days.mapNotNull { it.tempMax }
                val span = ((lows + highs).minOrNull() ?: 0.0) to ((lows + highs).maxOrNull() ?: 1.0)
                MkCard(padding = MkCardPadding.None) {
                    Column(modifier = Modifier.padding(6.dp)) {
                        days.forEachIndexed { i, day -> ForecastDayRow(day, i, span) }
                    }
                }
            }

            ForecastKicker("Säävaroitukset")
            if (forecast.warnings.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MkRadius.md))
                        .background(c.surfaceCard)
                        .border(1.dp, c.borderSubtle, RoundedCornerShape(MkRadius.md))
                        .padding(horizontal = 13.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(MkIcons.Check, null, tint = c.statusOk, modifier = Modifier.size(18.dp))
                    Text("Ei voimassa olevia säävaroituksia", style = MkTheme.type.body, color = c.inkMid)
                }
            } else {
                forecast.warnings.forEach { ForecastWarningRow(it) }
            }
        }
    }
}

/** A mono uppercase section kicker for the forecast page. */
@Composable
private fun ForecastKicker(text: String) {
    Text(
        text = text.uppercase(),
        style = MkTheme.type.kicker,
        color = MkTheme.colors.inkLo,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/** One day of the 7-day ladder: name · glyph · rain% · low — bar — high. */
@Composable
private fun ForecastDayRow(day: WeatherDay, index: Int, span: Pair<Double, Double>) {
    val c = MkTheme.colors
    val (dow, date) = forecastDayLabels(day.date, index)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.width(74.dp)) {
            Text(dow, style = MkTheme.type.body.copy(fontSize = 13.sp), color = c.inkHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(date, style = MkTheme.type.readout(10, FontWeight.Normal), color = c.inkLo, maxLines = 1)
        }
        Icon(
            weatherIcon(null, day.condition, night = false),
            null,
            tint = c.inkMid,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = day.precipitationProbability?.let { "${Fmt.int(it)}%" }.orEmpty(),
            style = MkTheme.type.readout(10, FontWeight.Normal),
            color = c.accent,
            modifier = Modifier.width(30.dp),
            maxLines = 1,
        )
        Text(
            text = day.tempMin?.let { "${Fmt.int(it)}°" } ?: "–",
            style = MkTheme.type.readout(12, FontWeight.Normal),
            color = c.inkLo,
            modifier = Modifier.width(26.dp),
            maxLines = 1,
        )
        // Min–max bar, positioned within the week's shared span with weighted pads.
        val (lo, hi) = span
        val range = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val left = (((day.tempMin ?: lo) - lo) / range).toFloat().coerceIn(0f, 1f)
        val right = ((hi - (day.tempMax ?: hi)) / range).toFloat().coerceIn(0f, 1f)
        val mid = (1f - left - right).coerceIn(0.02f, 1f)
        Row(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(c.surfaceInset),
        ) {
            if (left > 0f) Spacer(Modifier.weight(left))
            Box(
                modifier = Modifier
                    .weight(mid)
                    .fillMaxSize()
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(c.accent, c.warm))),
            )
            if (right > 0f) Spacer(Modifier.weight(right))
        }
        Text(
            text = day.tempMax?.let { "${Fmt.int(it)}°" } ?: "–",
            style = MkTheme.type.readout(12),
            color = c.inkHi,
            modifier = Modifier.width(28.dp),
            maxLines = 1,
        )
    }
}

/** One forecast warning as a warm-tinted alert row (helle / myrsky). */
@Composable
private fun ForecastWarningRow(w: WeatherWarning) {
    val c = MkTheme.colors
    val icon = when (w.kind) {
        "helle" -> MkIcons.ThermometerHot
        "myrsky" -> MkIcons.Wind
        else -> MkIcons.Warning
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MkRadius.md))
            .background(c.warmDim)
            .border(1.dp, c.warmBorder, RoundedCornerShape(MkRadius.md))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.warm, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(w.title.ifBlank { "Säävaroitus" }, style = MkTheme.type.readout(13, FontWeight.SemiBold), color = c.inkHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (w.detail.isNotBlank()) {
                Text(w.detail, style = MkTheme.type.readout(11, FontWeight.Normal), color = c.inkMid, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Tänään / Huomenna / Ke" plus "d.M." for a forecast day's ISO date. */
private fun forecastDayLabels(dateIso: String, index: Int): Pair<String, String> {
    val date = runCatching { LocalDate.parse(dateIso) }.getOrNull()
    val dow = when (index) {
        0 -> "Tänään"
        1 -> "Huomenna"
        else -> date?.let { FIN_DOW.getOrNull(it.dayOfWeek.ordinal) } ?: ""
    }
    val label = date?.let { "${it.dayOfMonth}.${it.monthNumber}." } ?: ""
    return dow to label
}

/** Finnish weekday shorthands, Monday-first to match `DayOfWeek.ordinal`. */
private val FIN_DOW = listOf("Ma", "Ti", "Ke", "To", "Pe", "La", "Su")
