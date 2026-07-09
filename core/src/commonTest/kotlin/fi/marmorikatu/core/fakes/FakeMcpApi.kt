package fi.marmorikatu.core.fakes

import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.LightInfo
import fi.marmorikatu.core.model.AirQuality
import fi.marmorikatu.core.model.ElectricityPrices
import fi.marmorikatu.core.model.WeatherForecast
import fi.marmorikatu.core.transport.mcp.McpApi
import fi.marmorikatu.core.transport.mcp.McpState
import fi.marmorikatu.core.transport.mcp.SaunaStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

class FakeMcpApi : McpApi {
    var catalog: List<LightInfo> = listOf(
        LightInfo(1, "Kylpyhuone", Floor.ALAKERTA),
        LightInfo(43, "Olohuone", Floor.ALAKERTA),
        LightInfo(51, "Biljardipöytä", Floor.KELLARI),
    )
    val calls = mutableListOf<String>()

    override val state: StateFlow<McpState> =
        MutableStateFlow(McpState.Connected(lastLatencyMs = 5))

    override suspend fun listLights(): List<LightInfo> {
        calls += "list_lights"
        return catalog
    }

    override suspend fun setLight(idOrName: String, on: Boolean) {
        calls += "set_light:$idOrName:$on"
    }

    override suspend fun setAllLights(on: Boolean) {
        calls += "set_all_lights:$on"
    }

    override suspend fun setLightsByFloor(floor: Int?, on: Boolean) {
        calls += "set_lights_by_floor:$floor:$on"
    }

    override suspend fun harmonyListActivities(): List<String> = listOf("Watch TV")
    override suspend fun harmonyCurrentActivity(): String = "PowerOff"
    override suspend fun harmonyStartActivity(activity: String) { calls += "harmony:$activity" }
    override suspend fun harmonySendCommand(device: String, command: String) {}
    override suspend fun harmonyPowerOff() { calls += "harmony_off" }

    override suspend fun getThermiaStatus(): JsonObject = buildJsonObject {}
    override suspend fun getRoomTemperatures(): JsonObject = buildJsonObject {}
    override suspend fun getSaunaStatus(): SaunaStatus = SaunaStatus(null, "idle", false)
    override suspend fun getElectricityPrices(): ElectricityPrices = ElectricityPrices()
    override suspend fun getAirQuality(): AirQuality = AirQuality()
    override suspend fun getEnergyConsumption(hours: Int): JsonObject = buildJsonObject {}
    override suspend fun getWeatherForecast(): WeatherForecast = WeatherForecast()
    override suspend fun getNewsHeadlines(count: Int): JsonElement = JsonNull
    override suspend fun getCalendarEvents(days: Int): JsonElement = JsonNull
    override suspend fun getDailyReport(): String = ""
}
