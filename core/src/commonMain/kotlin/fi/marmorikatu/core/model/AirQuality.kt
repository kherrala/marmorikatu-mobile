package fi.marmorikatu.core.model

import kotlinx.serialization.Serializable

/** One air-quality reading with the server's own good/moderate/poor verdict. */
@Serializable
data class AirReading(
    val value: Double,
    val unit: String,
    /** `good` | `moderate` | `poor`, or null for plain readings. */
    val status: String? = null,
)

/** Ruuvi air-quality snapshot from MCP `get_air_quality`. */
@Serializable
data class AirQuality(
    val co2: AirReading? = null,
    val pm25: AirReading? = null,
    val voc: AirReading? = null,
    val nox: AirReading? = null,
    val humidity: AirReading? = null,
    val temperature: AirReading? = null,
    /** e.g. "Kitchen (Keittiö)". */
    val location: String = "",
) {
    /** Maps the server's verdict onto the design system's status names. */
    fun statusOf(reading: AirReading?): String? = when (reading?.status) {
        "good" -> "ok"
        "moderate" -> "warn"
        "poor" -> "alarm"
        else -> null
    }
}

/**
 * Heat pump snapshot decoded from the live `ThermIQ/marmorikatu/data` register
 * dump. The ThermIQ bridge is a separate service that can stop publishing, so
 * [available] tells the UI to say "ei tietoa" rather than render a stale number
 * as if it were live; [updatedAtEpochSeconds] lets the caller judge staleness.
 *
 * [powerKw] and [cop] are not in the register feed — they come from the energy
 * meter / computed history and are merged in by the caller.
 */
data class HeatPumpStatus(
    val available: Boolean,
    val running: Boolean = false,
    val powerKw: Double? = null,
    val hotWaterC: Double? = null,
    val hotWaterActive: Boolean = false,
    val indoorTargetC: Double? = null,
    val indoorC: Double? = null,
    val outdoorC: Double? = null,
    val supplyC: Double? = null,
    val returnC: Double? = null,
    val brineInC: Double? = null,
    val brineOutC: Double? = null,
    val currentA: Double? = null,
    val cop: Double? = null,
    val updatedAtEpochSeconds: Long? = null,
) {
    /** Brine loop temperature drop across the ground collector, if both ends read. */
    val brineDeltaC: Double?
        get() = if (brineInC != null && brineOutC != null) brineInC - brineOutC else null
}

/** Ventilation heat-recovery summary drawn from the `hvac` measurement. */
data class HvacSummary(
    val outdoorC: Double? = null,
    val recoveryEfficiencyPct: Double? = null,
    val humidityPct: Double? = null,
    val heatPumpPowerKw: Double? = null,
    val freezingDanger: Boolean = false,
    val anyAlarm: Boolean = false,
)
