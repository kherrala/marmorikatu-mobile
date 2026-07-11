package fi.marmorikatu.core.model

/**
 * One Ruuvi tag reading, as the Ruuvi Gateway already decoded it and relayed on
 * `ruuvi/<gw>/<tag>` MQTT. The gateway parses the BLE advertisement into JSON
 * (temperature, CO₂, …), so the app reads these fields straight from the payload
 * rather than decoding hex.
 *
 * Fields are nullable because the two tag types report different sets: a basic
 * RuuviTag (dataFormat 5) has temperature/humidity/pressure/voltage; the air
 * sensor (dataFormat 225, the Keittiö tag) adds CO₂/PM/VOC/NOx.
 *
 * @param tsEpoch the gateway's original receive time (`ts`, epoch seconds) — the
 *   real measurement moment, used to drive per-tile freshness and stale-dimming.
 */
data class RuuviReading(
    val sensorName: String,
    val temperature: Double? = null,
    val humidity: Double? = null,
    val pressure: Double? = null,
    val co2: Int? = null,
    val pm25: Double? = null,
    val voc: Int? = null,
    val nox: Int? = null,
    val voltage: Double? = null,
    val rssi: Int? = null,
    val tsEpoch: Long = 0L,
)

/**
 * Ruuvi tag MAC → friendly (Finnish) name, mirroring the backend's
 * `ruuvi_mqtt_subscriber.py` map so the app and InfluxDB agree on names.
 */
object RuuviSensors {
    val NAMES: Map<String, String> = mapOf(
        "D1:86:61:6E:DF:E4" to "Sauna",
        "D3:1D:6A:1E:7C:4E" to "Takka",
        "D7:6C:BC:6D:29:46" to "Olohuone",
        "E6:DC:F8:EC:78:3B" to "Keittiö",
        "EE:3A:F4:B9:74:E5" to "Jääkaappi",
        "EF:AA:DF:C0:4F:8C" to "Pakastin",
        "F1:19:ED:0F:9A:F6" to "Ulkolämpötila",
    )

    /** The air-quality tag (dataFormat 225: CO₂/PM/VOC), hardwired to the kitchen. */
    const val AIR_QUALITY = "Keittiö"
    const val OUTDOOR = "Ulkolämpötila"
    const val SAUNA = "Sauna"
    const val FREEZER = "Pakastin"
    const val FRIDGE = "Jääkaappi"
}
