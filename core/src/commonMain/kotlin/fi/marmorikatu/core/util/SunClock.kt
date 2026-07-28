package fi.marmorikatu.core.util

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sunrise/sunset for the fixed home location, from the standard sunrise equation
 * (the same maths the backend runs with `astral`). Everything is in absolute
 * epoch milliseconds, so day/night decisions are timezone-independent — a caller
 * that wants a wall-clock "HH:mm" converts the instant itself.
 *
 * This is the single source of truth: the weather footer reads [sunTimes] and the
 * auto-theme driver reads [phase]. Verified against the backend's astral output;
 * null only on polar day/night, which never happens at Tampere's latitude.
 */
object SunClock {
    const val HOME_LAT = 61.4978
    const val HOME_LON = 23.7610

    private const val DAY_MS = 86_400_000L

    /** Fractional day-count used to pick a solar day; matches the original equation. */
    private fun dayCount(nowMs: Long) = nowMs / 86_400_000.0 + 2440587.5 - 2451545.0 + 0.0008

    /** Sunrise/sunset (epoch ms) for the integer solar-day [n]; null on polar day/night. */
    private fun eventsForN(n: Double, lat: Double, lon: Double): Pair<Long, Long>? {
        fun rad(d: Double) = d * PI / 180.0
        val jStar = n - lon / 360.0
        val m = (357.5291 + 0.98560028 * jStar) % 360.0
        val cCenter = 1.9148 * sin(rad(m)) + 0.0200 * sin(rad(2 * m)) + 0.0003 * sin(rad(3 * m))
        val lambda = (m + cCenter + 180.0 + 102.9372) % 360.0
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(rad(m)) - 0.0069 * sin(rad(2 * lambda))
        val sinDec = sin(rad(lambda)) * sin(rad(23.4397))
        val cosDec = cos(asin(sinDec))
        val cosOmega = (sin(rad(-0.833)) - sin(rad(lat)) * sinDec) / (cos(rad(lat)) * cosDec)
        if (cosOmega < -1.0 || cosOmega > 1.0) return null
        val omega = acos(cosOmega) * 180.0 / PI / 360.0
        fun ms(jd: Double) = ((jd - 2440587.5) * 86_400_000.0).toLong()
        return ms(jTransit - omega) to ms(jTransit + omega)
    }

    /**
     * The upcoming solar day's sunrise/sunset as epoch millis (the weather footer's
     * "next sun event" wants the coming day). Null on polar day/night.
     */
    fun sunTimes(nowMs: Long, lat: Double = HOME_LAT, lon: Double = HOME_LON): Pair<Long, Long>? =
        eventsForN(ceil(dayCount(nowMs)), lat, lon)

    /**
     * The theme the sun implies right now: dark from sunset until the next sunrise,
     * light through the day. Unlike [sunTimes] this brackets `now` between the real
     * surrounding events (scanning the neighbouring solar days), so an afternoon is
     * correctly "light", not "before tomorrow's sunrise". [Phase.nextFlipMs] is when
     * the state next changes, so a driver can sleep until then instead of polling.
     */
    fun phase(nowMs: Long, lat: Double = HOME_LAT, lon: Double = HOME_LON): Phase {
        // (instant, isSunset) for the days around now — enough to bracket the
        // current interval whichever solar day `now` falls in.
        val base = ceil(dayCount(nowMs))
        val events = buildList {
            for (d in -2..2) eventsForN(base + d, lat, lon)?.let { (rise, set) ->
                add(rise to false)
                add(set to true)
            }
        }.sortedBy { it.first }
        if (events.isEmpty()) return Phase(dark = false, nextFlipMs = nowMs + DAY_MS) // polar
        val prev = events.lastOrNull { it.first <= nowMs }
        val next = events.firstOrNull { it.first > nowMs }
        // Dark if the most recent event was a sunset (or we're before any known sunrise).
        return Phase(dark = prev?.second ?: true, nextFlipMs = next?.first ?: (nowMs + DAY_MS))
    }

    data class Phase(val dark: Boolean, val nextFlipMs: Long)
}
