package fi.marmorikatu.app.format

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Number and time formatting for the readouts.
 *
 * The design system's rule: dense readouts use a dot (`21.4°`), Finnish prose
 * uses a comma (`2,74 €`). Everything measured is mono with tabular figures,
 * so widths stay stable as digits tick.
 */
object Fmt {

    /** `21.4` — one decimal, dot separator, for dense readouts. */
    fun oneDecimal(value: Double): String {
        val scaled = (value * 10).roundToInt()
        val whole = scaled / 10
        val frac = abs(scaled % 10)
        return "$whole.$frac"
    }

    /** `2,74` — comma separator, for prose and currency. */
    fun comma(value: Double, decimals: Int = 2): String {
        val text = fixed(value, decimals)
        return text.replace('.', ',')
    }

    fun fixed(value: Double, decimals: Int): String {
        if (decimals <= 0) return value.roundToInt().toString()
        var factor = 1.0
        repeat(decimals) { factor *= 10 }
        val scaled = (value * factor).roundToInt()
        val whole = scaled / factor.toInt()
        val frac = abs(scaled % factor.toInt()).toString().padStart(decimals, '0')
        return "$whole.$frac"
    }

    fun int(value: Double): String = value.roundToInt().toString()

    /** `19:32` from an ISO-8601 instant, in the house's local zone. */
    @OptIn(ExperimentalTime::class)
    fun clock(isoInstant: String): String = runCatching {
        val instant = kotlin.time.Instant.parse(isoInstant)
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "${local.hour.pad()}:${local.minute.pad()}"
    }.getOrElse { "--:--" }

    /** `19:32` from unix seconds. */
    @OptIn(ExperimentalTime::class)
    fun clock(unixSeconds: Double): String = runCatching {
        val instant = kotlin.time.Instant.fromEpochSeconds(unixSeconds.toLong())
        val local = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        "${local.hour.pad()}:${local.minute.pad()}"
    }.getOrElse { "--:--" }

    /** The current wall clock, `19:32`. */
    @OptIn(ExperimentalTime::class)
    fun now(): String {
        val local = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        return "${local.hour.pad()}:${local.minute.pad()}"
    }

    /** Time-aware Finnish greeting, per the design system's tone. */
    @OptIn(ExperimentalTime::class)
    fun greeting(): String {
        val hour = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour
        return when (hour) {
            in 5..9 -> "Huomenta"
            in 10..11 -> "Päivää"
            in 12..16 -> "Iltapäivää"
            in 17..22 -> "Iltaa"
            else -> "Yötä"
        }
    }

    /** `2 t 10 min`, `18 min`, `juuri nyt`. */
    fun duration(minutes: Int): String = when {
        minutes < 1 -> "juuri nyt"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} t"
        else -> "${minutes / 60} t ${minutes % 60} min"
    }

    /**
     * Freshness phrase for the screen headers: `juuri nyt`, `4 min sitten`,
     * `2 t sitten`, `3 pv sitten`. Deliberately coarse below a minute — a live
     * feed refreshes every ~13 s, and a per-second "12 s / 13 s / 14 s" counter
     * would redraw (and, on Android 15, log a frame-rate hint) every second for
     * no real information. The freshness dot's flash already signals each update.
     */
    @OptIn(ExperimentalTime::class)
    fun freshness(unixSeconds: Double): String {
        val seconds = (Clock.System.now().epochSeconds - unixSeconds.toLong()).coerceAtLeast(0)
        return when {
            seconds < 60 -> "juuri nyt"
            seconds < 3600 -> "${seconds / 60} min sitten"
            seconds < 86_400 -> "${seconds / 3600} t sitten"
            else -> "${seconds / 86_400} pv sitten"
        }
    }

    /** How long ago, from unix seconds: `12 s`, `4 min`, `2 t`. */
    @OptIn(ExperimentalTime::class)
    fun since(unixSeconds: Double): String {
        val seconds = (Clock.System.now().epochSeconds - unixSeconds.toLong()).coerceAtLeast(0)
        return when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min"
            seconds < 86_400 -> "${seconds / 3600} t"
            else -> "${seconds / 86_400} pv"
        }
    }

    /** Minutes until a future instant given in unix milliseconds. */
    @OptIn(ExperimentalTime::class)
    fun minutesUntil(unixMillis: Long): Int {
        val deltaMs = unixMillis - Clock.System.now().toEpochMilliseconds()
        return (deltaMs / 60_000).toInt()
    }

    private fun Int.pad(): String = toString().padStart(2, '0')
}
