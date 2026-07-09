package fi.marmorikatu.core.model

import kotlinx.serialization.Serializable

/** One room's temperature from the retained `marmorikatu/temperatures` topic. */
@Serializable
data class RoomTemperature(
    /** PLC payload key, e.g. `yk_aatu`. */
    val key: String,
    /** Finnish display name, e.g. `Aatun huone`. */
    val name: String,
    val floor: Floor,
    val celsius: Double,
)

/** Underfloor-heating PID output per room, 0–100 %. */
@Serializable
data class HeatingDemand(
    val key: String,
    val name: String,
    val percent: Int,
)

/**
 * Ventilation (MVHR) readings from `marmorikatu/ventilation`. The PLC
 * publishes English CamelCase keys; [supplyC] is the post-heater supply air
 * that actually reaches the rooms.
 */
@Serializable
data class Ventilation(
    val outdoorC: Double? = null,
    val supplyC: Double? = null,
    val supplyPreHeatC: Double? = null,
    val extractC: Double? = null,
    val exhaustC: Double? = null,
    val relativeHumidity: Double? = null,
    val freezingDanger: Boolean = false,
    /** Everything else on the topic, for the debug screen. */
    val raw: Map<String, Double> = emptyMap(),
)

/** Cooling pump states from `marmorikatu/cooling`. */
@Serializable
data class Cooling(
    val pumpCooling: Boolean = false,
    val coolingPump: Boolean = false,
)

/** PLC publisher health from `marmorikatu/status`. */
@Serializable
data class PlcStatus(
    val publishCount: Long = 0,
    val errorCount: Long = 0,
    val modbusConnected: Boolean = false,
    val commandsReceived: Long = 0,
    val commandsApplied: Long = 0,
    val commandsRejected: Long = 0,
)
