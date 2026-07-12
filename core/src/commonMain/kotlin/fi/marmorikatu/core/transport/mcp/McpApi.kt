package fi.marmorikatu.core.transport.mcp

import fi.marmorikatu.core.model.AirQuality
import fi.marmorikatu.core.model.AirReading
import fi.marmorikatu.core.model.ElectricityPrices
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.LightInfo
import fi.marmorikatu.core.model.SpotPrice
import fi.marmorikatu.core.model.WeatherForecast
import fi.marmorikatu.core.transport.http.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Typed wrappers over the building-automation MCP tools the app needs.
 * Add one wrapper at a time as the UI grows; everything else stays
 * reachable through [McpConnection.callToolJson].
 */
interface McpApi {
    val state: kotlinx.coroutines.flow.StateFlow<McpState>

    suspend fun listLights(): List<LightInfo>
    suspend fun setLight(idOrName: String, on: Boolean)
    suspend fun setAllLights(on: Boolean)
    suspend fun setLightsByFloor(floor: Int?, on: Boolean)

    suspend fun harmonyListActivities(): List<String>
    suspend fun harmonyCurrentActivity(): String
    suspend fun harmonyStartActivity(activity: String)
    suspend fun harmonySendCommand(device: String, command: String)
    suspend fun harmonyPowerOff()

    suspend fun getThermiaStatus(): JsonObject
    suspend fun getRoomTemperatures(): JsonObject
    suspend fun getSaunaStatus(): SaunaStatus
    suspend fun getElectricityPrices(): ElectricityPrices
    suspend fun getAirQuality(): AirQuality
    suspend fun getEnergyConsumption(hours: Int = 24): JsonObject

    /**
     * Estimated electricity cost + consumption over a Flux [timeRange]
     * (e.g. `-24h`, `-7d`, `-30d`, `-365d`): the `cost` block
     * (`estimated_total_eur`, `total_price_c_kwh` average) and the
     * `consumption_kwh` per-consumer breakdown.
     */
    suspend fun getEnergyCost(timeRange: String = "-24h"): JsonObject

    suspend fun getWeatherForecast(): WeatherForecast
    suspend fun getNewsHeadlines(count: Int = 5): JsonElement
    suspend fun getCalendarEvents(days: Int = 7): JsonElement
    suspend fun getDailyReport(): String
}

class DefaultMcpApi(private val connection: McpConnection) : McpApi {

    override val state get() = connection.state

    // --- Lights -----------------------------------------------------------

    override suspend fun listLights(): List<LightInfo> {
        val result = connection.callToolJson("list_lights").jsonObject
        return result["lights"]?.jsonArray.orEmpty().mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            LightInfo(
                id = id,
                name = name,
                floor = Floor.fromServer(obj["floor"]?.jsonPrimitive?.intOrNull),
            )
        }
    }

    override suspend fun setLight(idOrName: String, on: Boolean) {
        connection.callToolJson("set_light", mapOf("light" to idOrName, "on" to on))
    }

    /** Batch commands go via the server: it paces publishes per PLC cycle. */
    override suspend fun setAllLights(on: Boolean) {
        connection.callToolJson("set_all_lights", mapOf("on" to on))
    }

    override suspend fun setLightsByFloor(floor: Int?, on: Boolean) {
        connection.callToolJson("set_lights_by_floor", mapOf("floor" to floor, "on" to on))
    }

    // --- TV (Harmony Hub) --------------------------------------------------
    // The harmony tools answer in prose ("Harmony activities:\n1. Watch TV"),
    // not JSON — parse the numbered list.

    override suspend fun harmonyListActivities(): List<String> {
        val text = connection.callToolJson("harmony_list_activities").asPlainText()
        return text.lineSequence()
            .mapNotNull { line ->
                Regex("""^\s*\d+\.\s*(.+?)\s*$""").find(line)?.groupValues?.get(1)
            }
            .toList()
    }

    override suspend fun harmonyCurrentActivity(): String =
        connection.callToolJson("harmony_current_activity").asPlainText()

    override suspend fun harmonyStartActivity(activity: String) {
        connection.callToolJson("harmony_start_activity", mapOf("activity" to activity))
    }

    /** Relative action (VolumeUp, ChannelDown…): never replay it. */
    override suspend fun harmonySendCommand(device: String, command: String) {
        connection.callToolJson(
            "harmony_send_command",
            mapOf("device" to device, "command" to command),
            idempotent = false,
        )
    }

    override suspend fun harmonyPowerOff() {
        connection.callToolJson("harmony_power_off")
    }

    // --- Climate / sauna / energy -------------------------------------------

    /** Raw JSON from `get_thermia_status`; shape is rich, UI picks fields. */
    override suspend fun getThermiaStatus(): JsonObject =
        connection.callToolJson("get_thermia_status").jsonObject

    override suspend fun getRoomTemperatures(): JsonObject =
        connection.callToolJson("get_room_temperatures").jsonObject

    override suspend fun getSaunaStatus(): SaunaStatus {
        val obj = connection.callToolJson("get_sauna_status").jsonObject
        return SaunaStatus(
            currentTempC = obj["current_temp_c"]?.jsonPrimitive?.doubleOrNull,
            status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            isHeating = obj["is_heating"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    /**
     * `get_electricity_prices` answers `{current_price_c_kwh, today:{…},
     * today_prices:[{time, price_c_kwh}]}` — today at 15-minute resolution.
     */
    override suspend fun getElectricityPrices(): ElectricityPrices {
        val obj = connection.callToolJson("get_electricity_prices").jsonObject
        val today = obj["today"] as? JsonObject
        return ElectricityPrices(
            currentCentsPerKwh = obj["current_price_c_kwh"]?.jsonPrimitive?.doubleOrNull,
            currentHour = obj["current_hour"]?.jsonPrimitive?.contentOrNull,
            minCentsPerKwh = today?.get("min_c_kwh")?.jsonPrimitive?.doubleOrNull,
            maxCentsPerKwh = today?.get("max_c_kwh")?.jsonPrimitive?.doubleOrNull,
            avgCentsPerKwh = today?.get("avg_c_kwh")?.jsonPrimitive?.doubleOrNull,
            today = obj["today_prices"]?.jsonArray.orEmpty().mapNotNull { entry ->
                val e = entry as? JsonObject ?: return@mapNotNull null
                val time = e["time"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val price = e["price_c_kwh"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                SpotPrice(time, price)
            },
        )
    }

    override suspend fun getAirQuality(): AirQuality {
        val obj = connection.callToolJson("get_air_quality").jsonObject
        fun reading(key: String): AirReading? {
            val r = obj[key] as? JsonObject ?: return null
            val value = r["value"]?.jsonPrimitive?.doubleOrNull ?: return null
            return AirReading(
                value = value,
                unit = r["unit"]?.jsonPrimitive?.contentOrNull ?: "",
                status = r["status"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return AirQuality(
            co2 = reading("co2"),
            pm25 = reading("pm2_5"),
            voc = reading("voc"),
            nox = reading("nox"),
            humidity = reading("humidity"),
            temperature = reading("temperature"),
            location = obj["location"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    override suspend fun getEnergyConsumption(hours: Int): JsonObject =
        // The backend tool reads a Flux-style `time_range` string, not `hours` —
        // passing `hours` was silently ignored and it defaulted to a 7-day window.
        connection.callToolJson("get_energy_consumption", mapOf("time_range" to "-${hours}h")).jsonObject

    override suspend fun getEnergyCost(timeRange: String): JsonObject =
        connection.callToolJson("get_energy_cost", mapOf("time_range" to timeRange)).jsonObject

    // --- Info (weather/news/calendar proxied server-side) --------------------

    override suspend fun getWeatherForecast(): WeatherForecast {
        val element = connection.callToolJson("get_weather_forecast")
        return AppJson.decodeFromJsonElement(WeatherForecast.serializer(), element)
    }

    override suspend fun getNewsHeadlines(count: Int): JsonElement =
        connection.callToolJson("get_news_headlines", mapOf("count" to count))

    override suspend fun getCalendarEvents(days: Int): JsonElement =
        connection.callToolJson("get_calendar_events", mapOf("days" to days))

    override suspend fun getDailyReport(): String =
        connection.callToolJson("get_daily_report").asPlainText()

    // --- helpers -------------------------------------------------------------

    private fun JsonElement.asPlainText(): String = when (this) {
        is JsonObject -> toString()
        else -> jsonPrimitive.contentOrNull ?: toString()
    }
}

data class SaunaStatus(
    val currentTempC: Double?,
    val status: String,
    val isHeating: Boolean,
)
