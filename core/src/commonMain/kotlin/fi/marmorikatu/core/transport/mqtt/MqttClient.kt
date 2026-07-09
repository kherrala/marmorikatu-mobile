package fi.marmorikatu.core.transport.mqtt

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only MQTT surface the rest of the app sees. Implemented by
 * [MqttasticClient]; swapping the underlying library touches one file.
 */
interface MqttClient {
    val connectionState: StateFlow<MqttConnectionState>
    val messages: SharedFlow<MqttMessage>

    /**
     * Connects and subscribes. Retained topics replay the full house state
     * immediately after subscribe, so a clean session is always used and no
     * local persistence is needed.
     */
    suspend fun connect(host: String, port: Int, clientId: String, subscriptions: List<String>)

    suspend fun publish(topic: String, payload: String, qos: Int = 1, retain: Boolean = false)

    suspend fun disconnect()
}

sealed interface MqttConnectionState {
    data object Disconnected : MqttConnectionState
    data object Connecting : MqttConnectionState
    data object Connected : MqttConnectionState
    data class Failed(val message: String) : MqttConnectionState
}

data class MqttMessage(
    val topic: String,
    val payload: ByteArray,
    val retained: Boolean = false,
) {
    fun text(): String = payload.decodeToString()

    override fun equals(other: Any?): Boolean =
        other is MqttMessage && topic == other.topic &&
            payload.contentEquals(other.payload) && retained == other.retained

    override fun hashCode(): Int =
        31 * (31 * topic.hashCode() + payload.contentHashCode()) + retained.hashCode()
}
