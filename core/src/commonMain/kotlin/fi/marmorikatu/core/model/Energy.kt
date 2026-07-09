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

/** One hour's spot electricity price (from MCP `get_electricity_prices`). */
@Serializable
data class SpotPrice(
    val time: String,
    val centsPerKwh: Double,
)
