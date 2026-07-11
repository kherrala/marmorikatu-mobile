package fi.marmorikatu.core.repository

import fi.marmorikatu.core.transport.influx.FluxPoint
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Is the sauna on" hinges on telling a plateau (heater cycling at setpoint) from
 * a cool-down (heater off), which a single temperature can't — so the plateau
 * detector is pinned here.
 */
class SaunaHoldingTest {

    private fun series(vararg values: Double) = values.map { FluxPoint("t", it) }

    @Test
    fun holdingAtSetpointReadsOn() {
        // Cycling around ~71 °C: latest sits within margin of the recent peak.
        assertTrue(saunaHolding(series(70.0, 71.0, 70.0, 72.0, 71.0, 70.0, 71.0, 71.0)))
    }

    @Test
    fun stillClimbingReadsOn() {
        // The newest reading is the peak, trivially within margin.
        assertTrue(saunaHolding(series(50.0, 55.0, 60.0, 65.0, 68.0, 70.0, 71.0, 72.0)))
    }

    @Test
    fun coolingDownReadsOff() {
        // Latest (52) has fallen well below the recent peak (72) — heater is off.
        assertFalse(saunaHolding(series(72.0, 71.0, 68.0, 64.0, 60.0, 57.0, 54.0, 52.0)))
    }

    @Test
    fun slowCooldownStillClears() {
        // A gentle but steady decline after switch-off — the case that used to
        // stay stuck "on" under the old fall-margin logic.
        assertFalse(saunaHolding(series(72.0, 72.0, 72.0, 71.0, 70.0, 68.5)))
    }

    @Test
    fun lateStageSlowCooldownClears() {
        // Real captured data: past the fast phase, cooling ~1 °C per 5 min near
        // 60 °C. The old 2 °C/10 min rule mis-read this as "holding" and never
        // cleared; the peak-relative + still-falling check clears it.
        assertFalse(saunaHolding(series(64.0, 63.0, 63.0, 62.0, 61.0, 60.0, 60.0, 59.0, 58.0)))
    }

    @Test
    fun setpointJitterStaysOn() {
        // Held at setpoint with tiny drift (< the cool-down drop): still on.
        assertTrue(saunaHolding(series(72.2, 72.0, 71.8, 71.6)))
    }

    @Test
    fun aLoylyDipStillReadsOn() {
        // A brief dip to 66 stays within the 7 °C margin of the 72 °C peak.
        assertTrue(saunaHolding(series(70.0, 71.0, 72.0, 71.0, 72.0, 70.0, 66.0, 72.0)))
    }

    @Test
    fun coolAmbientReadsOff() {
        assertFalse(saunaHolding(series(24.0, 24.0, 23.0, 24.0)))
    }

    @Test
    fun emptySeriesReadsOff() {
        assertFalse(saunaHolding(emptyList()))
    }
}
