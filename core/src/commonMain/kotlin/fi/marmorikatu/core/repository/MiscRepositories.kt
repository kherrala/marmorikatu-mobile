package fi.marmorikatu.core.repository

import fi.marmorikatu.core.model.BusDepartures
import fi.marmorikatu.core.model.EnergyReading
import fi.marmorikatu.core.model.ElectricityPrices
import fi.marmorikatu.core.model.PriceTier
import fi.marmorikatu.core.model.WeatherForecast
import fi.marmorikatu.core.transport.influx.FluxClient
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

    /**
     * Estimated cost + consumption for a Flux time range (`-24h`, `-7d`, `-30d`,
     * `-365d`): the backend `get_energy_cost` block with `estimated_total_eur`,
     * the average `total_price_c_kwh`, and a per-consumer `consumption_kwh`
     * breakdown. Drives the range-selectable Kulutus section.
     */
    suspend fun energyCost(range: String): JsonObject

    /**
     * Per-bucket metered energy (kWh, heat-pump + aux) over a Flux [range], for
     * the cost-trend sparkline. [every] is the bucket width. Empty on failure.
     */
    suspend fun energyCostTrend(range: String, every: String): List<Double>

    /**
     * A corrected sauna consumption estimate (kWh) over [range], replacing the
     * backend's over-counting `sauna_kwh`. Derived from Ruuvi temperature —
     * heating-hours (room > 60 °C) × the heater's ~6 kW. Null when the sensor
     * has no data (caller keeps the backend figure).
     */
    suspend fun saunaEstimateKwh(range: String): Double?

    /**
     * The authoritative current price band from the backend
     * `heating_optimizer.tier` (same source the announcements use), or null
     * when InfluxDB is unreachable — callers then fall back to classifying the
     * MCP price locally.
     */
    suspend fun priceTier(): PriceTier?

    /**
     * The heat-pump optimizer's current telemetry, read from the
     * `indoor_publisher` measurement: `total_bias` (the °C indoor-setpoint
     * correction it is applying) and its seasonal `cheap_threshold` /
     * `expensive_threshold` price boundaries. Read-only telemetry; empty on
     * failure.
     */
    suspend fun heatingOptimizer(): Map<String, Double>

    /** Light on-time today (hours) per floor name. Empty on failure. */
    suspend fun lightOnTimeByFloor(): Map<String, Double>

    /** Today's automatic light-off counts, keyed by optimizer category. Empty on failure. */
    suspend fun lightAutoOffCounts(): Map<String, Double>
}

class DefaultEnergyRepository(
    mqtt: MqttClient,
    private val mcp: McpApi,
    private val flux: FluxClient,
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

    override suspend fun energyCost(range: String): JsonObject = mcp.getEnergyCost(range)

    override suspend fun energyCostTrend(range: String, every: String): List<Double> =
        flux.energyKwhBuckets(range, every)

    override suspend fun saunaEstimateKwh(range: String): Double? =
        // ~6 kW heater; the room stays above 60 °C for roughly the heat-up +
        // maintenance span of a session, which offsets the heater's thermostatic
        // cycling well enough for an unmetered estimate.
        flux.saunaHeatingHours(range)?.let { it * 6.0 }

    override suspend fun priceTier(): PriceTier? =
        when (flux.latestString("heating_optimizer", "tier")?.trim()?.uppercase()) {
            "CHEAP" -> PriceTier.Cheap
            "EXPENSIVE" -> PriceTier.Expensive
            "NORMAL", "PRE_HEAT" -> PriceTier.Normal
            else -> null
        }

    override suspend fun heatingOptimizer(): Map<String, Double> =
        flux.latest("indoor_publisher", listOf("total_bias", "cheap_threshold", "expensive_threshold"))

    override suspend fun lightOnTimeByFloor(): Map<String, Double> = flux.lightOnTimeByFloorToday()

    override suspend fun lightAutoOffCounts(): Map<String, Double> = flux.lightAutoOffCountsToday()
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
