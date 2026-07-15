package fi.marmorikatu.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.app.theme.MkRadius
import fi.marmorikatu.app.theme.MkSpacing
import fi.marmorikatu.app.theme.MkTheme
import fi.marmorikatu.core.model.WeatherForecast
import fi.marmorikatu.core.model.WeatherWarning
import fi.marmorikatu.core.model.WeatherHour
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
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
    val hours = forecast.hourlyNext4h.take(4)
    val outdoorTemp = outdoorTempOverride ?: now.temperature

    MkCard(modifier = modifier, interactive = onClick != null, onClick = onClick) {
        // Deduced weather warnings (helle / myrsky) sit atop the card so they're
        // the first thing read — empty on a calm day, so nothing shows.
        if (forecast.warnings.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(bottom = MkSpacing.x2),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                forecast.warnings.forEach { WeatherWarningBanner(it) }
            }
        }
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
                    Text(
                        text = now.condition.ifBlank { "Ulkosää" }.replaceFirstChar { it.uppercase() },
                        style = MkTheme.type.heading,
                        color = c.inkMid,
                        modifier = Modifier.alignByBaseline().padding(start = 8.dp).weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                hours.forEach { hour -> WeatherHourCell(hour, night) }
            }
        }

        // Bottom meta: wind · humidity · sun times.
        val meta = buildList {
            // The backend's `wind_speed_ms` is mislabelled: Open-Meteo returns km/h
            // (its default), so convert to real m/s (the Finnish convention) here.
            now.windSpeedMs?.let { add(MkIcons.Wind to "${Fmt.oneDecimal(it / 3.6)} m/s") }
            now.humidity?.let { add(MkIcons.DropHalf to "${Fmt.int(it)} %") }
            homeSunTimes()?.let { (rise, set) -> add(MkIcons.Sun to "↑$rise ↓$set") }
        }
        if (meta.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = MkSpacing.x3),
                horizontalArrangement = Arrangement.spacedBy(MkSpacing.x5),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                meta.forEach { (metaIcon, text) -> WeatherMeta(metaIcon, text) }
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
private fun WeatherHourCell(hour: WeatherHour, night: Boolean) {
    val c = MkTheme.colors
    Column(
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
        Text(
            text = hour.temperature?.let { "${Fmt.oneDecimal(it)}°" } ?: "–",
            style = MkTheme.type.readout(13),
            color = c.inkHi,
            maxLines = 1,
        )
    }
}

/**
 * Condition → glyph. Prefers the WMO [code] the backend sends (Open-Meteo), and
 * falls back to matching the Finnish condition word. The icon set has no fog /
 * plain-cloud glyph, so overcast and fog reuse the cloud-with-sun/moon pair.
 */
/** A helle / myrsky warning banner shown atop the weather card. */
@Composable
private fun WeatherWarningBanner(w: WeatherWarning) {
    val c = MkTheme.colors
    val icon = when (w.kind) {
        "helle" -> MkIcons.ThermometerHot
        "myrsky" -> MkIcons.Wind
        else -> MkIcons.Warning
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MkRadius.sm))
            .background(c.warmDim)
            .border(1.dp, c.warmBorder, RoundedCornerShape(MkRadius.sm))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = c.warm, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = w.title.ifBlank { "Säävaroitus" },
                style = MkTheme.type.readout(13, FontWeight.SemiBold),
                color = c.inkHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (w.detail.isNotBlank()) {
                Text(
                    text = w.detail,
                    style = MkTheme.type.readout(11, FontWeight.Normal),
                    color = c.inkMid,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

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
