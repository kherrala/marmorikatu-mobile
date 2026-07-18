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
    // --- UI preferences, persisted across launches -------------------------
    /** Light is the default; the kiosk's dark theme is a deliberate choice. */
    val darkTheme: Boolean = false,
    /** Kid mode survives a reboot: a child's phone should stay a child's phone. */
    val kidMode: Boolean = false,
    val useNativeStt: Boolean = true,
    val useNativeTts: Boolean = true,
    /** The assistant persona: avatar face + native TTS voice. */
    val assistantGender: AssistantGender = AssistantGender.Nainen,
    /** Language the assistant listens/speaks in (native STT + TTS). */
    val speechLanguage: SpeechLanguage = SpeechLanguage.Finnish,
    /** Vibrate on announcements. Priority-0 alerts ignore this and always buzz. */
    val hapticsEnabled: Boolean = true,
    /** Keep listening for events while the app is in the background. */
    val backgroundEnabled: Boolean = false,
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
