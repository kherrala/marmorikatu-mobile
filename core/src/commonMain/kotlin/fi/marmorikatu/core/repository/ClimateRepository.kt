package fi.marmorikatu.core.repository

import fi.marmorikatu.core.model.AirQuality
import fi.marmorikatu.core.model.Cooling
import fi.marmorikatu.core.model.HeatPumpStatus
import fi.marmorikatu.core.model.HvacSummary
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.PlcStatus
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Rooms
import fi.marmorikatu.core.model.Ventilation
import fi.marmorikatu.core.transport.influx.FluxClient
import fi.marmorikatu.core.transport.influx.FluxPoint
import fi.marmorikatu.core.transport.mcp.McpApi
import fi.marmorikatu.core.transport.mqtt.MqttClient
import fi.marmorikatu.core.transport.mqtt.MqttTopics
import fi.marmorikatu.core.transport.mqtt.PlcPayloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Rooms, ventilation, and heat pump — READ ONLY in v1 by design.
 *
 * There is deliberately no heat-pump write path here: the backend `indoor`
 * service republishes `INDR_T` to the Thermia every 60 s (an app write would
 * be overwritten within a minute), and persistent register writes wear the
 * pump's flash. A safe setpoint/bias knob is a planned backend follow-up;
 * when it lands it becomes one new method backed by MCP.
 */
interface ClimateRepository {
    val roomTemperatures: StateFlow<List<RoomTemperature>>
    val heatingDemand: StateFlow<List<HeatingDemand>>
    val ventilation: StateFlow<Ventilation>
    val cooling: StateFlow<Cooling>
    val plcStatus: StateFlow<PlcStatus>

    /** On-demand heat pump snapshot via MCP `get_thermia_status`. */
    suspend fun heatPumpStatus(): JsonObject

    /** Typed heat-pump view; reports unavailable when the ThermIQ feed is stale. */
    suspend fun heatPump(): HeatPumpStatus

    /** Ruuvi air quality (CO₂, PM2.5, VOC) via MCP. */
    suspend fun airQuality(): AirQuality

    /** Outdoor temperature, heat-recovery efficiency and MVHR alarms. */
    suspend fun hvacSummary(): HvacSummary

    /**
     * Temperature history for the charts. Keys are Finnish display names.
     * @param range Flux duration, e.g. `-24h`.
     */
    suspend fun temperatureHistory(range: String, every: String): Map<String, List<FluxPoint>>
}

class DefaultClimateRepository(
    mqtt: MqttClient,
    private val mcp: McpApi,
    private val flux: FluxClient,
    scope: CoroutineScope,
) : ClimateRepository {

    private val _roomTemperatures = MutableStateFlow<List<RoomTemperature>>(emptyList())
    override val roomTemperatures: StateFlow<List<RoomTemperature>> = _roomTemperatures.asStateFlow()

    private val _heatingDemand = MutableStateFlow<List<HeatingDemand>>(emptyList())
    override val heatingDemand: StateFlow<List<HeatingDemand>> = _heatingDemand.asStateFlow()

    private val _ventilation = MutableStateFlow(Ventilation())
    override val ventilation: StateFlow<Ventilation> = _ventilation.asStateFlow()

    private val _cooling = MutableStateFlow(Cooling())
    override val cooling: StateFlow<Cooling> = _cooling.asStateFlow()

    private val _plcStatus = MutableStateFlow(PlcStatus())
    override val plcStatus: StateFlow<PlcStatus> = _plcStatus.asStateFlow()

    init {
        scope.launch {
            mqtt.messages.collect { msg ->
                when (msg.topic) {
                    MqttTopics.TEMPERATURES ->
                        _roomTemperatures.value = PlcPayloads.parseTemperatures(msg.text())
                    MqttTopics.HEATING ->
                        _heatingDemand.value = PlcPayloads.parseHeating(msg.text())
                    MqttTopics.VENTILATION ->
                        _ventilation.value = PlcPayloads.parseVentilation(msg.text())
                    MqttTopics.COOLING ->
                        _cooling.value = PlcPayloads.parseCooling(msg.text())
                    MqttTopics.STATUS ->
                        _plcStatus.value = PlcPayloads.parseStatus(msg.text())
                }
            }
        }
    }

    override suspend fun heatPumpStatus(): JsonObject = mcp.getThermiaStatus()

    /**
     * The ThermIQ bridge publishes independently of the PLC; when it stops,
     * `get_thermia_status` answers with empty groups rather than an error.
     * Treat that as "no data" instead of showing a stale reading as live.
     *
     * Compressor state still has a live proxy: the heat pump's own energy
     * meter, which the PLC publishes on the `hvac` measurement.
     */
    override suspend fun heatPump(): HeatPumpStatus {
        val powerKw = runCatching {
            flux.latest("hvac", listOf(FIELD_HEAT_PUMP_POWER))[FIELD_HEAT_PUMP_POWER]
        }.getOrNull()

        val thermia = runCatching { mcp.getThermiaStatus() }.getOrNull()
        val temperatures = thermia?.get("temperatures") as? JsonObject
        val settings = thermia?.get("settings") as? JsonObject
        val performance = thermia?.get("performance") as? JsonObject
        val hasThermia = !temperatures.isNullOrEmpty()

        return HeatPumpStatus(
            available = hasThermia,
            running = (powerKw ?: 0.0) > HEAT_PUMP_RUNNING_KW,
            powerKw = powerKw,
            hotWaterC = temperatures?.get("hotwater_temp")?.jsonPrimitive?.doubleOrNull,
            indoorTargetC = settings?.get("indoor_requested_t")?.jsonPrimitive?.doubleOrNull,
            cop = performance?.get("cop")?.jsonPrimitive?.doubleOrNull,
        )
    }

    override suspend fun airQuality(): AirQuality = mcp.getAirQuality()

    override suspend fun hvacSummary(): HvacSummary {
        val values = runCatching {
            flux.latest(
                "hvac",
                listOf(
                    "Ulkolampotila", "LTO_hyotysuhde", "Suhteellinen_kosteus",
                    FIELD_HEAT_PUMP_POWER, "Alarm_freezing_danger",
                    "Alarm_filter_guard", "Alarm_fan_failure_supply",
                    "Alarm_fan_failure_extract", "Alarm_service_reminder",
                ),
            )
        }.getOrElse { emptyMap() }

        val alarmFields = listOf(
            "Alarm_freezing_danger", "Alarm_filter_guard",
            "Alarm_fan_failure_supply", "Alarm_fan_failure_extract",
            "Alarm_service_reminder",
        )
        return HvacSummary(
            outdoorC = values["Ulkolampotila"],
            recoveryEfficiencyPct = values["LTO_hyotysuhde"],
            humidityPct = values["Suhteellinen_kosteus"],
            heatPumpPowerKw = values[FIELD_HEAT_PUMP_POWER],
            freezingDanger = (values["Alarm_freezing_danger"] ?: 0.0) > 0.0,
            anyAlarm = alarmFields.any { (values[it] ?: 0.0) > 0.0 },
        )
    }

    override suspend fun temperatureHistory(
        range: String,
        every: String,
    ): Map<String, List<FluxPoint>> {
        val outdoor = runCatching {
            flux.history("hvac", listOf("Ulkolampotila"), range, every)
        }.getOrElse { emptyMap() }
        val rooms = runCatching {
            flux.history("rooms", Rooms.ALL.map { it.influxField }, range, every)
        }.getOrElse { emptyMap() }

        return buildMap {
            outdoor["Ulkolampotila"]?.let { put("Ulko", it) }
            rooms.forEach { (field, points) ->
                // Influx field names are legacy and mis-assigned; Rooms is truth.
                Rooms.byInfluxField[field]?.let { put(it.displayName, points) }
            }
        }
    }

    private companion object {
        const val FIELD_HEAT_PUMP_POWER = "Lampopumppu_teho"

        /** Above this the compressor is drawing real power, i.e. it is running. */
        const val HEAT_PUMP_RUNNING_KW = 0.3

    }
}
