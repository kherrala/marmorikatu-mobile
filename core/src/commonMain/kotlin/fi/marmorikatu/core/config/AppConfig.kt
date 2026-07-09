package fi.marmorikatu.core.config

/**
 * Connection endpoints for the home automation system.
 *
 * All services are reached over plain HTTP on the LAN; away from home the
 * UniFi gateway VPN puts the phone on the same network, so the app never
 * needs a second set of URLs. The LAN is the security boundary by design —
 * none of the backend services authenticate.
 *
 * [mqttHost] is separate from [serverHost] because the broker runs on the
 * NAS, not the Docker host. If `freenas.kherrala.fi` fails to resolve over
 * VPN, override it with the NAS LAN IP in settings.
 */
data class AppConfig(
    val serverHost: String = DEFAULT_SERVER_HOST,
    val mqttHost: String = DEFAULT_MQTT_HOST,
    val mqttPort: Int = DEFAULT_MQTT_PORT,
) {
    val mcpUrl: String get() = "http://$serverHost:3001/mcp"
    val bridgeUrl: String get() = "http://$serverHost:3002"
    val busUrl: String get() = "http://$serverHost:3010"
    val influxUrl: String get() = "http://$serverHost:8086"

    companion object {
        const val DEFAULT_SERVER_HOST = "192.168.1.160"
        const val DEFAULT_MQTT_HOST = "freenas.kherrala.fi"
        const val DEFAULT_MQTT_PORT = 1883
    }
}
