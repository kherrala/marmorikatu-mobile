package fi.marmorikatu.core.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SunClockTest {

    private val helsinki = TimeZone.of("Europe/Helsinki")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime(y, mo, d, h, min, 0).toInstant(helsinki).toEpochMilliseconds()

    @Test
    fun classifiesDayAndNightAcrossSeasons() {
        // Summer (Tampere): sun up ~04:15–22:40 local.
        assertTrue(!SunClock.phase(ms(2026, 7, 15, 13, 0)).dark, "summer midday is light")
        assertTrue(SunClock.phase(ms(2026, 7, 15, 3, 0)).dark, "summer 3am is dark")
        // Winter: sun up ~09:40–15:15 local.
        assertTrue(!SunClock.phase(ms(2026, 1, 15, 12, 0)).dark, "winter midday is light")
        assertTrue(SunClock.phase(ms(2026, 1, 15, 18, 0)).dark, "winter evening is dark")
        assertTrue(SunClock.phase(ms(2026, 1, 15, 7, 0)).dark, "winter pre-dawn is dark")
    }

    @Test
    fun nextFlipIsAlwaysAheadAndSwitchesTheState() {
        // Every 90 min across four days, the next flip is in the future and lands on
        // an actual state change.
        val start = ms(2026, 3, 20, 0, 0) // near equinox — rapidly moving sun times
        for (step in 0 until 64) {
            val now = start + step * 90 * 60_000L
            val phase = SunClock.phase(now)
            assertTrue(phase.nextFlipMs > now, "flip must be ahead (step=$step)")
            // Immediately after the flip the state must be the opposite.
            val after = SunClock.phase(phase.nextFlipMs + 60_000L)
            assertEquals(!phase.dark, after.dark, "state must invert at the flip (step=$step)")
        }
    }

    @Test
    fun sunTimesGivesAnOrderedRiseThenSet() {
        val (rise, set) = SunClock.sunTimes(ms(2026, 7, 15, 12, 0))!!
        assertTrue(rise < set, "sunrise precedes sunset")
    }
}
