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
    /** Every active MVHR alarm on the `marmorikatu/ventilation` topic. */
    val alarms: Set<VentAlarm> = emptySet(),
    /** Everything else on the topic, for the debug screen. */
    val raw: Map<String, Double> = emptyMap(),
)

/** The MVHR (ventilation unit) alarm bits published on `marmorikatu/ventilation`. */
enum class VentAlarm {
    FreezingDanger,       // AlarmFreezingDanger — heater coil / heat-exchanger freeze risk
    FilterGuard,          // AlarmFilterGuard — filter needs changing
    AfterheaterOverheat,  // AlarmOverheatAfter — after-heater over-temperature
    IrSensor,             // AlarmIRSensor
    TempDeviation,        // AlarmTempDeviation
    LowEfficiency,        // AlarmEfficiency
    SupplyFanFailure,     // AlarmFanFailureSA
    ExhaustFanFailure,    // AlarmFanFailureEA
    ServiceReminder,      // AlarmServiceReminder
    TempSensor,           // AlarmTempSensor (non-zero code)
}

/**
 * Cooling state: pump on/off from `marmorikatu/cooling`, plus the two cooling-
 * coil coolant temperatures (`jaahdpatteri_1/2`) that arrive on the temperatures
 * topic. The PLC names them only "coolant 1/2" with no documented supply/return
 * role, so [coolantSupplyC]/[coolantReturnC] assign meno/paluu by temperature:
 * on an active cooling coil the supply (meno) coolant is the colder side and the
 * return (paluu) is warmer after picking up heat from the air.
 */
@Serializable
data class Cooling(
    val pumpCooling: Boolean = false,
    val coolingPump: Boolean = false,
    val coilTemp1: Double? = null,
    val coilTemp2: Double? = null,
) {
    private val bothCoilTemps: Pair<Double, Double>?
        get() = coilTemp1?.let { a -> coilTemp2?.let { b -> a to b } }

    /** Supply (meno) coolant — the colder coil sensor; null until both read. */
    val coolantSupplyC: Double? get() = bothCoilTemps?.let { (a, b) -> minOf(a, b) }

    /** Return (paluu) coolant — the warmer coil sensor; null until both read. */
    val coolantReturnC: Double? get() = bothCoilTemps?.let { (a, b) -> maxOf(a, b) }

    /** Coolant temperature rise across the coil (paluu − meno), a cooling-load proxy. */
    val coolantDeltaC: Double? get() = bothCoilTemps?.let { (a, b) -> maxOf(a, b) - minOf(a, b) }
}

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
