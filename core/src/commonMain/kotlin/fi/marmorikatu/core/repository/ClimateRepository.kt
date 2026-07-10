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
import fi.marmorikatu.core.transport.mqtt.MqttConnectionState
import fi.marmorikatu.core.transport.mqtt.MqttTopics
import fi.marmorikatu.core.transport.mqtt.PlcPayloads
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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

    /**
     * Live heat-pump view, decoded from the retained-less `ThermIQ/marmorikatu/data`
     * register feed. Seeded on launch from the last cached reading so the tiles
     * aren't blank while waiting for the first publish; power/COP are merged in
     * by the caller from other sources.
     */
    val heatPump: StateFlow<HeatPumpStatus>

    /**
     * Ask the ThermIQ bridge to publish its registers now (pull-to-refresh).
     * Best effort: does nothing if MQTT isn't connected.
     */
    suspend fun requestHeatPumpRead()

    /** On-demand raw heat pump snapshot via MCP `get_thermia_status` (debug/history). */
    suspend fun heatPumpStatus(): JsonObject

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
    private val mqtt: MqttClient,
    private val mcp: McpApi,
    private val flux: FluxClient,
    scope: CoroutineScope,
    private val settings: Settings = Settings(),
) : ClimateRepository {

    private val json = Json { ignoreUnknownKeys = true }

    // Seed the live flows from the last payloads we saw so the climate widgets
    // render immediately on launch, before MQTT reconnects and re-delivers the
    // retained snapshot (a second or two later).
    private val _roomTemperatures = MutableStateFlow(loadCached(KEY_TEMPS, PlcPayloads::parseTemperatures))
    override val roomTemperatures: StateFlow<List<RoomTemperature>> = _roomTemperatures.asStateFlow()

    private val _heatingDemand = MutableStateFlow(loadCached(KEY_HEATING, PlcPayloads::parseHeating))
    override val heatingDemand: StateFlow<List<HeatingDemand>> = _heatingDemand.asStateFlow()

    private val _ventilation = MutableStateFlow(Ventilation())
    override val ventilation: StateFlow<Ventilation> = _ventilation.asStateFlow()

    private val _cooling = MutableStateFlow(Cooling())
    override val cooling: StateFlow<Cooling> = _cooling.asStateFlow()

    private val _plcStatus = MutableStateFlow(PlcStatus())
    override val plcStatus: StateFlow<PlcStatus> = _plcStatus.asStateFlow()

    private val _heatPump = MutableStateFlow(loadPersistedHeatPump())
    override val heatPump: StateFlow<HeatPumpStatus> = _heatPump.asStateFlow()

    init {
        scope.launch {
            mqtt.messages.collect { msg ->
                when (msg.topic) {
                    MqttTopics.TEMPERATURES -> {
                        val text = msg.text()
                        _roomTemperatures.value = PlcPayloads.parseTemperatures(text)
                        cache(KEY_TEMPS, text)
                    }
                    MqttTopics.HEATING -> {
                        val text = msg.text()
                        _heatingDemand.value = PlcPayloads.parseHeating(text)
                        cache(KEY_HEATING, text)
                    }
                    MqttTopics.VENTILATION ->
                        _ventilation.value = PlcPayloads.parseVentilation(msg.text())
                    MqttTopics.COOLING ->
                        _cooling.value = PlcPayloads.parseCooling(msg.text())
                    MqttTopics.STATUS ->
                        _plcStatus.value = PlcPayloads.parseStatus(msg.text())
                    MqttTopics.THERMIQ ->
                        PlcPayloads.parseThermiq(msg.text())?.let {
                            _heatPump.value = it
                            persistHeatPump(it)
                        }
                }
            }
        }
    }

    override suspend fun requestHeatPumpRead() {
        if (mqtt.connectionState.value is MqttConnectionState.Connected) {
            runCatching { mqtt.publish(MqttTopics.THERMIQ_READ, "", qos = 0) }
        }
    }

    /** Last heat-pump reading, cached so the tiles aren't blank on a cold start. */
    private fun loadPersistedHeatPump(): HeatPumpStatus {
        val cached = settings.getStringOrNull(KEY_HEAT_PUMP) ?: return HeatPumpStatus(available = false)
        return runCatching {
            json.decodeFromString(HeatPumpStatus.serializer(), cached)
        }.getOrElse { HeatPumpStatus(available = false) }
    }

    private fun persistHeatPump(status: HeatPumpStatus) {
        runCatching {
            settings.putString(KEY_HEAT_PUMP, json.encodeToString(HeatPumpStatus.serializer(), status))
        }
    }

    /** Re-parse the last cached raw payload for a live topic; empty if none/bad. */
    private fun <T> loadCached(key: String, parse: (String) -> List<T>): List<T> =
        settings.getStringOrNull(key)?.let { runCatching { parse(it) }.getOrNull() } ?: emptyList()

    private fun cache(key: String, payload: String) {
        runCatching { settings.putString(key, payload) }
    }

    override suspend fun heatPumpStatus(): JsonObject = mcp.getThermiaStatus()

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
        const val KEY_HEAT_PUMP = "climate.heatpump"
        const val KEY_TEMPS = "climate.temperatures"
        const val KEY_HEATING = "climate.heating"
    }
}
