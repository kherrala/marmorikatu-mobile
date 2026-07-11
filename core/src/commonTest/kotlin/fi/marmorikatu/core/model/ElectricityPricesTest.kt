package fi.marmorikatu.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The kiosk must call dirt-cheap electricity "cheap". A purely relative rule
 * (day average + half the spread) tagged the priciest hour of a flat cheap day
 * as expensive, so 0.9 c/kWh read as expensive. Classification now mirrors the
 * backend heating_optimizer: percentile thresholds clamped to absolute cents.
 */
class ElectricityPricesTest {

    private fun prices(vararg cents: Double) = ElectricityPrices(
        currentCentsPerKwh = cents.firstOrNull(),
        today = cents.mapIndexed { i, c ->
            val hh = (i + 100).toString().takeLast(2)
            SpotPrice(time = "2026-07-11T$hh:00:00Z", centsPerKwh = c)
        },
    )

    @Test
    fun flatCheapDay_classifiesNearMaxHourAsCheap() {
        // A whole day between 0.1 and 0.9 c/kWh: even the priciest hour is cheap,
        // because the cheap threshold is clamped to a 3.0 c/kWh floor.
        val day = prices(0.1, 0.3, 0.5, 0.7, 0.9, 0.8, 0.6, 0.4)
        assertEquals(PriceTier.Cheap, day.tierOf(0.9))
        assertEquals(PriceTier.Cheap, day.currentTierFallback)
        assertEquals(false, day.isExpensiveNow)
    }

    @Test
    fun normalDay_classifiesTopHoursExpensive_bottomCheap() {
        // Spread from 1 to 20 c/kWh: bottom is cheap, top is expensive.
        val day = prices(1.0, 2.0, 3.0, 6.0, 10.0, 14.0, 18.0, 20.0)
        assertEquals(PriceTier.Cheap, day.tierOf(1.0))
        assertEquals(PriceTier.Expensive, day.tierOf(20.0))
    }

    @Test
    fun cheapThreshold_isClampedIntoAbsoluteBand() {
        // Even if the 25th percentile is near zero, the cheap line never drops
        // below the 3.0 floor; even if prices are huge it never exceeds 6.0.
        assertEquals(3.0, prices(0.1, 0.1, 0.1, 0.2).cheapThreshold)
        assertEquals(6.0, prices(30.0, 40.0, 50.0, 60.0).cheapThreshold)
    }

    @Test
    fun emptyDay_hasNoThresholds() {
        val empty = ElectricityPrices()
        assertNull(empty.cheapThreshold)
        assertNull(empty.currentTierFallback)
    }
}
