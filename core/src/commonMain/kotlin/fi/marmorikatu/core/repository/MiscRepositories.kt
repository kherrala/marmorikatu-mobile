package fi.marmorikatu.core.repository

import fi.marmorikatu.core.model.BusDepartures
import fi.marmorikatu.core.model.EnergyReading
import fi.marmorikatu.core.model.ElectricityPrices
import fi.marmorikatu.core.model.WeatherForecast
import fi.marmorikatu.core.transport.mcp.McpApi
import fi.marmorikatu.core.transport.mcp.SaunaStatus
import fi.marmorikatu.core.transport.mqtt.MqttClient
import fi.marmorikatu.core.transport.mqtt.MqttTopics
import fi.marmorikatu.core.transport.mqtt.PlcPayloads
import fi.marmorikatu.core.transport.widgets.BusApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// --- Energy ------------------------------------------------------------------

interface EnergyRepository {
    /** Live meter readings keyed by meter name (`heatpump`, `extra`). */
    val liveEnergy: StateFlow<Map<String, EnergyReading>>

    suspend fun electricityPrices(): ElectricityPrices
    suspend fun energyConsumption(hours: Int = 24): JsonObject
}

class DefaultEnergyRepository(
    mqtt: MqttClient,
    private val mcp: McpApi,
    scope: CoroutineScope,
) : EnergyRepository {

    private val _liveEnergy = MutableStateFlow<Map<String, EnergyReading>>(emptyMap())
    override val liveEnergy: StateFlow<Map<String, EnergyReading>> = _liveEnergy.asStateFlow()

    init {
        scope.launch {
            mqtt.messages.collect { msg ->
                val meter = when (msg.topic) {
                    MqttTopics.ENERGY_HEATPUMP -> "heatpump"
                    MqttTopics.ENERGY_EXTRA -> "extra"
                    else -> return@collect
                }
                _liveEnergy.value = _liveEnergy.value +
                    (meter to PlcPayloads.parseEnergy(meter, msg.text()))
            }
        }
    }

    override suspend fun electricityPrices(): ElectricityPrices = mcp.getElectricityPrices()
    override suspend fun energyConsumption(hours: Int): JsonObject = mcp.getEnergyConsumption(hours)
}

// --- Sauna ---------------------------------------------------------------------

interface SaunaRepository {
    suspend fun status(): SaunaStatus
}

class DefaultSaunaRepository(private val mcp: McpApi) : SaunaRepository {
    override suspend fun status(): SaunaStatus = mcp.getSaunaStatus()
}

// --- TV (Harmony Hub via MCP; needs HARMONY_HUB_HOST configured server-side) ----

interface TvRepository {
    suspend fun activities(): List<String>
    suspend fun currentActivity(): String
    suspend fun startActivity(activity: String)
    suspend fun sendCommand(device: String, command: String)
    suspend fun powerOff()
}

class DefaultTvRepository(private val mcp: McpApi) : TvRepository {
    override suspend fun activities(): List<String> = mcp.harmonyListActivities()
    override suspend fun currentActivity(): String = mcp.harmonyCurrentActivity()
    override suspend fun startActivity(activity: String) = mcp.harmonyStartActivity(activity)
    override suspend fun sendCommand(device: String, command: String) =
        mcp.harmonySendCommand(device, command)
    override suspend fun powerOff() = mcp.harmonyPowerOff()
}

// --- Info (weather/news/calendar via MCP; bus direct) ----------------------------

interface InfoRepository {
    suspend fun weather(): WeatherForecast
    suspend fun news(count: Int = 5): JsonElement
    suspend fun calendar(days: Int = 7): JsonElement
    suspend fun busDepartures(): BusDepartures
}

class DefaultInfoRepository(
    private val mcp: McpApi,
    private val busApi: BusApi,
) : InfoRepository {
    override suspend fun weather(): WeatherForecast = mcp.getWeatherForecast()
    override suspend fun news(count: Int): JsonElement = mcp.getNewsHeadlines(count)
    override suspend fun calendar(days: Int): JsonElement = mcp.getCalendarEvents(days)
    override suspend fun busDepartures(): BusDepartures = busApi.departures()
}
