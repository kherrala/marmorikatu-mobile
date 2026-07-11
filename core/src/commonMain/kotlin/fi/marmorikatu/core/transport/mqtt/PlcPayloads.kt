package fi.marmorikatu.core.transport.mqtt

import fi.marmorikatu.core.model.Cooling
import fi.marmorikatu.core.model.EnergyReading
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.PlcStatus
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Rooms
import fi.marmorikatu.core.model.VentAlarm
import fi.marmorikatu.core.model.Ventilation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Pure parsers for the retained PLC topics. Payload shapes were captured
 * from the live broker on 2026-07-09; every parser tolerates unknown keys
 * and skips malformed entries rather than throwing.
 */
object PlcPayloads {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Room identity lives in [Rooms] — the MQTT keys and the legacy InfluxDB
     * field names disagree, so both parsers must route through the same table.
     * Extra keys on the temperatures topic (supply duct, cooling battery) are
     * exposed via [parseExtraTemperatures].
     */
    private val ROOMS = Rooms.byMqttKey
    private val HEATING_NAMES = Rooms.byHeatingKey

    /**
     * `marmorikatu/lights` and `marmorikatu/outlets` values may be bool,
     * number, or string; the PLC currently sends booleans but the importer
     * historically saw all three.
     */
    fun toBool(value: JsonElement): Boolean {
        val p = value as? JsonPrimitive ?: return false
        p.booleanOrNull?.let { return it }
        p.doubleOrNull?.let { return it != 0.0 }
        return p.content.lowercase() in setOf("true", "1", "on", "yes")
    }

    /** `{"1":false,"17":true,...}` → index → on. Non-numeric keys skipped. */
    fun parseLights(payload: String): Map<Int, Boolean> = buildMap {
        forEachEntry(payload) { key, value ->
            key.toIntOrNull()?.let { put(it, toBool(value)) }
        }
    }

    /** `marmorikatu/names/lights`: `{"1":"Kylpyhuone",...}` — empty names are PLC array gaps. */
    fun parseLightNames(payload: String): Map<Int, String> = buildMap {
        forEachEntry(payload) { key, value ->
            val idx = key.toIntOrNull() ?: return@forEachEntry
            val name = (value as? JsonPrimitive)?.content.orEmpty()
            if (name.isNotBlank()) put(idx, name)
        }
    }

    /** `marmorikatu/outlets` is keyed by NAME, not index: `{"ulkopistorasia":false}`. */
    fun parseOutlets(payload: String): Map<String, Boolean> = buildMap {
        forEachEntry(payload) { key, value -> put(key, toBool(value)) }
    }

    fun parseTemperatures(payload: String): List<RoomTemperature> = buildList {
        forEachEntry(payload) { key, value ->
            val room = ROOMS[key] ?: return@forEachEntry
            val celsius = (value as? JsonPrimitive)?.doubleOrNull ?: return@forEachEntry
            add(RoomTemperature(key, room.displayName, room.floor, celsius))
        }
    }

    /** Non-room keys on the temperatures topic (supply duct, cooling battery…). */
    fun parseExtraTemperatures(payload: String): Map<String, Double> = buildMap {
        forEachEntry(payload) { key, value ->
            if (key in ROOMS) return@forEachEntry
            (value as? JsonPrimitive)?.doubleOrNull?.let { put(key, it) }
        }
    }

    fun parseHeating(payload: String): List<HeatingDemand> = buildList {
        forEachEntry(payload) { key, value ->
            val percent = (value as? JsonPrimitive)?.doubleOrNull ?: return@forEachEntry
            val name = HEATING_NAMES[key]?.displayName ?: key
            add(HeatingDemand(key, name, percent.toInt().coerceIn(0, 100)))
        }
    }

    /**
     * Keys are matched case-insensitively (mirrors the server's
     * `lookup_ventilation`) because they have varied across PLC firmware
     * revisions. Current firmware publishes English CamelCase.
     */
    fun parseVentilation(payload: String): Ventilation {
        val obj = parseObject(payload) ?: return Ventilation()
        val lowered = obj.entries.associate { (k, v) -> k.lowercase() to v }
        val numbers = lowered.mapNotNull { (k, v) ->
            (v as? JsonPrimitive)?.doubleOrNull?.let { k to it }
        }.toMap()

        fun pick(vararg candidates: String): Double? =
            candidates.firstNotNullOfOrNull { numbers[it.lowercase()] }

        fun alarm(key: String): Boolean = lowered[key.lowercase()]?.let(::toBool) ?: false

        val alarms = buildSet {
            if (alarm("AlarmFreezingDanger")) add(VentAlarm.FreezingDanger)
            if (alarm("AlarmFilterGuard")) add(VentAlarm.FilterGuard)
            if (alarm("AlarmOverheatAfter")) add(VentAlarm.AfterheaterOverheat)
            if (alarm("AlarmIRSensor")) add(VentAlarm.IrSensor)
            if (alarm("AlarmTempDeviation")) add(VentAlarm.TempDeviation)
            if (alarm("AlarmEfficiency")) add(VentAlarm.LowEfficiency)
            if (alarm("AlarmFanFailureSA")) add(VentAlarm.SupplyFanFailure)
            if (alarm("AlarmFanFailureEA")) add(VentAlarm.ExhaustFanFailure)
            if (alarm("AlarmServiceReminder")) add(VentAlarm.ServiceReminder)
            // AlarmTempSensor is a numeric fault code — non-zero means a fault.
            if ((numbers["alarmtempsensor"] ?: 0.0) != 0.0) add(VentAlarm.TempSensor)
        }

        return Ventilation(
            outdoorC = pick("OutdoorTemp", "Ulkolampotila"),
            supplyC = pick("SupplyTempPostHeat", "Tuloilma"),
            supplyPreHeatC = pick("SupplyTempPreHeat", "Tuloilma_ennen_lammitysta"),
            extractC = pick("ExtractTemp", "Poistoilma"),
            exhaustC = pick("ExhaustTemp", "Jateilma"),
            relativeHumidity = pick("RelativeHumidity", "IndoorRH"),
            freezingDanger = VentAlarm.FreezingDanger in alarms,
            alarms = alarms,
            raw = numbers,
        )
    }

    fun parseCooling(payload: String): Cooling {
        val obj = parseObject(payload) ?: return Cooling()
        return Cooling(
            pumpCooling = obj["pumppu_jaahdytys"]?.let(::toBool) ?: false,
            coolingPump = obj["jaahdytyspumppu"]?.let(::toBool) ?: false,
        )
    }

    fun parseStatus(payload: String): PlcStatus {
        val obj = parseObject(payload) ?: return PlcStatus()
        fun long(key: String): Long = (obj[key] as? JsonPrimitive)?.longOrNull ?: 0
        return PlcStatus(
            publishCount = long("PublishCount"),
            errorCount = long("ErrorCount"),
            modbusConnected = obj["ModbusConnected"]?.let(::toBool) ?: false,
            commandsReceived = long("CommandsReceived"),
            commandsApplied = long("CommandsApplied"),
            commandsRejected = long("CommandsRejected"),
        )
    }

    /** OR-WE-517 meter fields; keys are snake_case (`Total_Active_Power`). */
    fun parseEnergy(meter: String, payload: String): EnergyReading {
        val values = buildMap {
            forEachEntry(payload) { key, value ->
                (value as? JsonPrimitive)?.doubleOrNull?.let { put(key, it) }
            }
        }
        fun pick(vararg candidates: String): Double? = candidates.firstNotNullOfOrNull { c ->
            values.entries.firstOrNull { it.key.equals(c, ignoreCase = true) }?.value
        }
        return EnergyReading(
            meter = meter,
            powerKw = pick("Total_Active_Power"),
            energyKwh = pick("Total_Active_Energy"),
            raw = values,
        )
    }

    /**
     * `ThermIQ/marmorikatu/data` — the Thermia register dump. Registers arrive
     * as `dNN` (decimal index) or `rNN` (hex index) depending on the ThermIQ
     * REGFMT setting; both are handled. Returns null when the payload can't be
     * read as an object or carries no usable reading.
     *
     * Register map mirrors the backend (`scripts/thermiq_write.py`): plain
     * temperatures are direct °C; indoor and indoor-target combine an integer
     * register with a 0.1 °C decimal register; d16 is the component bitfield
     * (bit 1 = compressor, bit 3 = hot-water production). A few sensors report
     * a −40 "not connected" sentinel, which is filtered out.
     */
    fun parseThermiq(payload: String): fi.marmorikatu.core.model.HeatPumpStatus? {
        val obj = parseObject(payload) ?: return null

        fun reg(index: Int): Double? {
            (obj["d$index"] as? JsonPrimitive)?.doubleOrNull?.let { return it }
            val hex = "r" + index.toString(16).padStart(2, '0')
            return (obj[hex] as? JsonPrimitive)?.doubleOrNull
        }
        // The Thermia's disconnected-sensor sentinel; never a real temperature.
        fun temp(index: Int): Double? = reg(index)?.takeIf { it > -40.0 }
        fun combined(whole: Int, decimal: Int): Double? {
            val w = temp(whole) ?: return null
            return w + (reg(decimal) ?: 0.0) / 10.0
        }
        val bits = reg(16)?.toInt() ?: 0
        fun bit(n: Int): Boolean = (bits shr n) and 1 == 1
        // Register d13: bit 0 = 3 kW aux heater, bit 1 = 6 kW aux heater.
        val auxHeaterActive = ((reg(13)?.toInt() ?: 0) and 0b11) != 0
        // Alarm bitfields d19 (pressure/flow/brine/motor) and d20 (sensor faults).
        val d19 = reg(19)?.toInt() ?: 0
        val d20 = reg(20)?.toInt() ?: 0
        fun d19bit(n: Int) = (d19 shr n) and 1 == 1
        fun d20bit(n: Int) = (d20 shr n) and 1 == 1
        val hpAlarms = buildSet {
            if (d19bit(0)) add(fi.marmorikatu.core.model.HeatPumpAlarm.HighPressure)
            if (d19bit(1)) add(fi.marmorikatu.core.model.HeatPumpAlarm.LowPressure)
            if (d19bit(2)) add(fi.marmorikatu.core.model.HeatPumpAlarm.MotorBreaker)
            if (d19bit(3)) add(fi.marmorikatu.core.model.HeatPumpAlarm.LowBrineFlow)
            if (d19bit(4)) add(fi.marmorikatu.core.model.HeatPumpAlarm.LowBrineTemp)
            if (d20bit(0)) add(fi.marmorikatu.core.model.HeatPumpAlarm.OutdoorSensor)
            if (d20bit(1)) add(fi.marmorikatu.core.model.HeatPumpAlarm.SupplySensor)
            if (d20bit(2)) add(fi.marmorikatu.core.model.HeatPumpAlarm.ReturnSensor)
            if (d20bit(3)) add(fi.marmorikatu.core.model.HeatPumpAlarm.HotWaterSensor)
            if (d20bit(4)) add(fi.marmorikatu.core.model.HeatPumpAlarm.IndoorSensor)
            if (d20bit(5)) add(fi.marmorikatu.core.model.HeatPumpAlarm.PhaseOrder)
            if (d20bit(6)) add(fi.marmorikatu.core.model.HeatPumpAlarm.Overheating)
        }

        val hotWater = temp(7)
        val target = combined(3, 4)
        // Neither the hot-water tank nor the target read → not real ThermIQ data.
        if (hotWater == null && target == null) return null

        val current = reg(12)
        val running = bit(1) || (current ?: 0.0) > 0.5
        val supply = temp(5)
        val ret = temp(6)
        // Compressor-only COP with the previous kiosk's formula (Thermia Diplomat
        // 8, 2.3 kW fixed-speed compressor): P_heat = 1.965·(supply − return) kW,
        // COP = P_heat / 2.3. Only meaningful while the compressor is running and
        // actually delivering heat (supply > return); otherwise it is undefined.
        val cop = if (running && supply != null && ret != null && supply > ret) {
            1.965 * (supply - ret) / 2.3
        } else {
            null
        }
        return fi.marmorikatu.core.model.HeatPumpStatus(
            available = true,
            running = running,
            hotWaterActive = bit(3),
            hotWaterC = hotWater,
            indoorTargetC = target,
            indoorC = combined(1, 2),
            outdoorC = temp(0),
            supplyC = supply,
            returnC = ret,
            brineInC = temp(9),
            brineOutC = temp(8),
            currentA = current,
            cop = cop,
            auxHeaterActive = auxHeaterActive,
            alarms = hpAlarms,
            updatedAtEpochSeconds = (obj["timestamp"] as? JsonPrimitive)?.longOrNull,
        )
    }

    private fun parseObject(payload: String): JsonObject? =
        runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()

    private inline fun forEachEntry(payload: String, block: (String, JsonElement) -> Unit) {
        val obj = parseObject(payload) ?: return
        for ((key, value) in obj) {
            runCatching { block(key, value) }
        }
    }
}
