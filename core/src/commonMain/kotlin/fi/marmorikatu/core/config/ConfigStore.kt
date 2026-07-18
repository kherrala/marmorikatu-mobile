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
        settings.putBoolean(KEY_DARK_THEME, new.darkTheme)
        settings.putBoolean(KEY_KID_MODE, new.kidMode)
        settings.putBoolean(KEY_NATIVE_STT, new.useNativeStt)
        settings.putBoolean(KEY_NATIVE_TTS, new.useNativeTts)
        settings.putString(KEY_ASSISTANT_GENDER, new.assistantGender.name)
        settings.putString(KEY_SPEECH_LANGUAGE, new.speechLanguage.name)
        settings.putBoolean(KEY_HAPTICS, new.hapticsEnabled)
        settings.putBoolean(KEY_BACKGROUND, new.backgroundEnabled)
        _config.value = new
    }

    private fun load(): AppConfig = AppConfig(
        serverHost = settings.getString(KEY_SERVER_HOST, AppConfig.DEFAULT_SERVER_HOST),
        mqttHost = settings.getString(KEY_MQTT_HOST, AppConfig.DEFAULT_MQTT_HOST),
        mqttPort = settings.getInt(KEY_MQTT_PORT, AppConfig.DEFAULT_MQTT_PORT),
        darkTheme = settings.getBoolean(KEY_DARK_THEME, false),
        kidMode = settings.getBoolean(KEY_KID_MODE, false),
        useNativeStt = settings.getBoolean(KEY_NATIVE_STT, true),
        useNativeTts = settings.getBoolean(KEY_NATIVE_TTS, true),
        assistantGender = AssistantGender.fromName(settings.getStringOrNull(KEY_ASSISTANT_GENDER)),
        speechLanguage = SpeechLanguage.fromName(settings.getStringOrNull(KEY_SPEECH_LANGUAGE)),
        hapticsEnabled = settings.getBoolean(KEY_HAPTICS, true),
        backgroundEnabled = settings.getBoolean(KEY_BACKGROUND, false),
    )

    private companion object {
        const val KEY_SERVER_HOST = "config.serverHost"
        const val KEY_MQTT_HOST = "config.mqttHost"
        const val KEY_MQTT_PORT = "config.mqttPort"
        const val KEY_DARK_THEME = "ui.darkTheme"
        const val KEY_KID_MODE = "ui.kidMode"
        const val KEY_NATIVE_STT = "ui.nativeStt"
        const val KEY_NATIVE_TTS = "ui.nativeTts"
        const val KEY_ASSISTANT_GENDER = "ui.assistantGender"
        const val KEY_SPEECH_LANGUAGE = "ui.speechLanguage"
        const val KEY_HAPTICS = "ui.haptics"
        const val KEY_BACKGROUND = "ui.background"
    }
}
