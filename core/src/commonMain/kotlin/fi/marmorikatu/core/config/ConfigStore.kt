package fi.marmorikatu.core.config

import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists [AppConfig] overrides and exposes the current config as a
 * [StateFlow]. Transports read the flow so a host change from the settings
 * UI takes effect on the next (re)connect without an app restart.
 */
class ConfigStore(private val settings: Settings = Settings()) {

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AppConfig> = _config.asStateFlow()

    fun update(transform: (AppConfig) -> AppConfig) {
        val new = transform(_config.value)
        settings.putString(KEY_SERVER_HOST, new.serverHost)
        settings.putString(KEY_MQTT_HOST, new.mqttHost)
        settings.putInt(KEY_MQTT_PORT, new.mqttPort)
        _config.value = new
    }

    private fun load(): AppConfig = AppConfig(
        serverHost = settings.getString(KEY_SERVER_HOST, AppConfig.DEFAULT_SERVER_HOST),
        mqttHost = settings.getString(KEY_MQTT_HOST, AppConfig.DEFAULT_MQTT_HOST),
        mqttPort = settings.getInt(KEY_MQTT_PORT, AppConfig.DEFAULT_MQTT_PORT),
    )

    private companion object {
        const val KEY_SERVER_HOST = "config.serverHost"
        const val KEY_MQTT_HOST = "config.mqttHost"
        const val KEY_MQTT_PORT = "config.mqttPort"
    }
}
