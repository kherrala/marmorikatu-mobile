package fi.marmorikatu.core.repository

import fi.marmorikatu.core.transport.influx.FluxPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The sauna "how long has it been on" figure is only as good as the ignition
 * point pulled from the temperature history, so the detector is pinned here.
 */
class SaunaOnsetTest {

    private fun p(time: String, value: Double) = FluxPoint(time, value)

    @Test
    fun onsetIsWhereTheClimbLeavesTheIdleBaseline() {
        val points = listOf(
            p("t0", 20.0), p("t1", 20.5), p("t2", 19.8),   // idle baseline ~20 °C
            p("t3", 22.0),                                  // near baseline, under +3
            p("t4", 35.0), p("t5", 55.0), p("t6", 75.0),   // clearly heating
        )
        // baseline 19.8 → threshold 22.8; walking back, t3 (22.0) is the first below.
        assertEquals("t4", saunaHeatingOnsetIso(points))
    }

    @Test
    fun idleSeriesHasNoOnset() {
        val points = listOf(p("t0", 20.0), p("t1", 21.0), p("t2", 20.5), p("t3", 22.0))
        assertNull(saunaHeatingOnsetIso(points))
    }

    @Test
    fun onlyTheCurrentSessionCounts() {
        val points = listOf(
            p("a0", 20.0), p("a1", 60.0), p("a2", 70.0),   // an earlier session
            p("b0", 21.0), p("b1", 20.0),                  // cooled back to idle
            p("c0", 40.0), p("c1", 65.0),                  // the current session
        )
        assertEquals("c0", saunaHeatingOnsetIso(points))
    }

    @Test
    fun aDoorDipDoesNotResetTheOnset() {
        val points = listOf(
            p("t0", 20.0), p("t1", 45.0), p("t2", 70.0),
            p("t3", 55.0),                                  // door opened, still hot
            p("t4", 72.0),
        )
        // The run never drops back to idle, so the onset is its first hot sample.
        assertEquals("t1", saunaHeatingOnsetIso(points))
    }

    @Test
    fun tooFewPointsIsNull() {
        assertNull(saunaHeatingOnsetIso(listOf(p("t0", 20.0), p("t1", 70.0))))
    }
}
