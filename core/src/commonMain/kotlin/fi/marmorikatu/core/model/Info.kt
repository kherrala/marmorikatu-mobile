package fi.marmorikatu.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Weather via MCP `get_weather_forecast` (Finnish condition strings). */
@Serializable
data class WeatherForecast(
    val current: WeatherNow = WeatherNow(),
    @SerialName("hourly_next_4h") val hourlyNext4h: List<WeatherHour> = emptyList(),
    @SerialName("daily_forecast") val dailyForecast: List<WeatherDay> = emptyList(),
)

@Serializable
data class WeatherNow(
    val temperature: Double? = null,
    @SerialName("feels_like") val feelsLike: Double? = null,
    val humidity: Double? = null,
    @SerialName("wind_speed_ms") val windSpeedMs: Double? = null,
    @SerialName("wind_direction") val windDirection: Double? = null,
    val condition: String = "",
    @SerialName("weather_code") val weatherCode: Int? = null,
)

@Serializable
data class WeatherHour(
    val time: String,
    val temperature: Double? = null,
    val condition: String = "",
    @SerialName("precipitation_probability") val precipitationProbability: Double? = null,
)

@Serializable
data class WeatherDay(
    val date: String,
    val condition: String = "",
    @SerialName("temp_max") val tempMax: Double? = null,
    @SerialName("temp_min") val tempMin: Double? = null,
    @SerialName("precipitation_probability") val precipitationProbability: Double? = null,
)

/** Bus departure from the Nysse service `GET /api/departures`. */
@Serializable
data class BusDeparture(
    val lineRef: String,
    val destinationName: String = "",
    val stopName: String = "",
    val departureTimeMs: Long,
    val leaveByMs: Long? = null,
    val delaySeconds: Int? = null,
    val vehicleAtStop: Boolean = false,
    val source: String = "",
)

@Serializable
data class BusDepartures(
    val ok: Boolean = true,
    val departures: List<BusDeparture> = emptyList(),
)
