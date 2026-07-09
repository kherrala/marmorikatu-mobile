package fi.marmorikatu.core.repository

import fi.marmorikatu.core.model.Cooling
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.PlcStatus
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Ventilation
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
}

class DefaultClimateRepository(
    mqtt: MqttClient,
    private val mcp: McpApi,
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
}
