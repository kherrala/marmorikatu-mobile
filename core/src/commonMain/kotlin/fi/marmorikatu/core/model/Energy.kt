package fi.marmorikatu.core.model

import kotlinx.serialization.Serializable

/**
 * Live meter reading from `marmorikatu/energy/heatpump` or `energy/extra`
 * (OR-WE-517 meters behind the PLC).
 */
@Serializable
data class EnergyReading(
    /** Which meter: `heatpump` or `extra`. */
    val meter: String,
    val powerKw: Double? = null,
    val energyKwh: Double? = null,
    val raw: Map<String, Double> = emptyMap(),
)

/** One quarter-hour's spot electricity price (from MCP `get_electricity_prices`). */
@Serializable
data class SpotPrice(
    val time: String,
    val centsPerKwh: Double,
)

/** Today's spot prices plus the current hour, as the MCP tool reports them. */
@Serializable
data class ElectricityPrices(
    val currentCentsPerKwh: Double? = null,
    val currentHour: String? = null,
    val minCentsPerKwh: Double? = null,
    val maxCentsPerKwh: Double? = null,
    val avgCentsPerKwh: Double? = null,
    /** 15-minute resolution across the day. */
    val today: List<SpotPrice> = emptyList(),
) {
    /**
     * "Expensive right now" mirrors the announcer's own notion: at or above
     * the day's average plus half the spread to the maximum.
     */
    val expensiveThreshold: Double?
        get() {
            val avg = avgCentsPerKwh ?: return null
            val max = maxCentsPerKwh ?: return null
            return avg + (max - avg) / 2
        }

    val isExpensiveNow: Boolean
        get() {
            val now = currentCentsPerKwh ?: return false
            val threshold = expensiveThreshold ?: return false
            return now >= threshold
        }
}
