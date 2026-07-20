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
) {
    /**
     * Supply-side heat-recovery efficiency (%) from the live exchanger gradient:
     *   η = (supply_after_HRU − outdoor) / (extract − outdoor) × 100
     * Mirrors [fi.marmorikatu.core.repository.ClimateRepository]'s InfluxDB
     * computation (the PLC's own `hreefficiency` field is a not-connected sensor
     * that pins to 0, so it can't be trusted). Valid only with a real gradient —
     * in mild weather outdoor ≈ extract, so the result is left null ("Ei tietoa").
     */
    val recoveryEfficiencyPct: Double?
        get() {
            val o = outdoorC; val s = supplyPreHeatC; val e = extractC
            return if (o != null && s != null && e != null && e != o) {
                ((s - o) / (e - o) * 100.0).takeIf { it > 0.0 && it <= 100.0 }
            } else {
                null
            }
        }
}

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
    /**
     * Supply-air temperature in the delivery duct after the cooling battery
     * (`Tuloilmakanava`), calibrated — the third PT100 on the same analog module
     * as the two coil sensors, riding the same temperatures topic. This is the
     * air actually delivered to the rooms, the live source for the Tuloilma tile
     * when InfluxDB's snapshot is unavailable.
     */
    val supplyDuctC: Double? = null,
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
