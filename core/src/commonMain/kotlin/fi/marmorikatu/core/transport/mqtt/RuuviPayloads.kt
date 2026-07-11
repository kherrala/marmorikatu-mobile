package fi.marmorikatu.core.transport.mqtt

import fi.marmorikatu.core.model.RuuviReading
import fi.marmorikatu.core.model.RuuviSensors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Parser for the Ruuvi Gateway's `ruuvi/<gw>/<tag>` JSON. The gateway already
 * decodes the BLE advertisement into fields, so this reads them directly — no
 * hex parsing. Payload shapes captured from the live broker on 2026-07-11.
 */
object RuuviPayloads {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * A [RuuviReading] for a decoded, mapped Ruuvi tag, or null otherwise — the
     * gateway also relays non-Ruuvi BLE devices (phones, etc.) that carry only
     * raw `data` hex and no `id`, and tags not in [RuuviSensors.NAMES] are
     * ignored. Malformed JSON returns null rather than throwing.
     */
    fun parse(payload: String): RuuviReading? {
        val obj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val name = RuuviSensors.NAMES[id] ?: return null
        fun d(key: String) = obj[key]?.jsonPrimitive?.doubleOrNull
        fun i(key: String) = obj[key]?.jsonPrimitive?.intOrNull
        return RuuviReading(
            sensorName = name,
            temperature = d("temperature"),
            humidity = d("humidity"),
            pressure = d("pressure"),
            co2 = i("CO2"),
            pm25 = d("PM2.5"),
            voc = i("VOC"),
            nox = i("NOx"),
            voltage = d("voltage"),
            rssi = i("rssi"),
            tsEpoch = obj["ts"]?.jsonPrimitive?.longOrNull ?: 0L,
        )
    }
}
