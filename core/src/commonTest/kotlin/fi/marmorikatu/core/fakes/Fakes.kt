package fi.marmorikatu.core.fakes

import fi.marmorikatu.core.transport.mqtt.MqttClient
import fi.marmorikatu.core.transport.mqtt.MqttConnectionState
import fi.marmorikatu.core.transport.mqtt.MqttMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeMqttClient : MqttClient {
    val published = mutableListOf<Triple<String, String, Boolean>>()

    private val _connectionState =
        MutableStateFlow<MqttConnectionState>(MqttConnectionState.Connected)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState

    // replay mimics retained topics: late subscribers still see prior state.
    private val _messages = MutableSharedFlow<MqttMessage>(replay = 16, extraBufferCapacity = 64)
    override val messages: SharedFlow<MqttMessage> = _messages

    override suspend fun connect(
        host: String, port: Int, clientId: String, subscriptions: List<String>,
    ) {
        _connectionState.value = MqttConnectionState.Connected
    }

    override suspend fun publish(topic: String, payload: String, qos: Int, retain: Boolean) {
        published += Triple(topic, payload, retain)
    }

    override suspend fun disconnect() {
        _connectionState.value = MqttConnectionState.Disconnected
    }

    fun setConnected(connected: Boolean) {
        _connectionState.value =
            if (connected) MqttConnectionState.Connected else MqttConnectionState.Disconnected
    }

    suspend fun emit(topic: String, payload: String, retained: Boolean = true) {
        _messages.emit(MqttMessage(topic, payload.encodeToByteArray(), retained))
    }
}
