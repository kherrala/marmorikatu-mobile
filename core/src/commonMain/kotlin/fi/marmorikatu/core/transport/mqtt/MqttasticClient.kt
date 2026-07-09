package fi.marmorikatu.core.transport.mqtt

import fi.marmorikatu.core.log.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Adapter over the MQTTastic library — the ONLY file that may import
 * `org.meshtastic.mqtt`. The library negotiates MQTT 5.0 with a 3.1.1
 * fallback, which covers whatever mosquitto version the NAS runs.
 */
class MqttasticClient(
    private val scope: CoroutineScope,
) : MqttClient {

    private val log = logger("mqtt")

    private val _connectionState =
        MutableStateFlow<MqttConnectionState>(MqttConnectionState.Disconnected)
    override val connectionState: StateFlow<MqttConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<MqttMessage>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val messages: SharedFlow<MqttMessage> = _messages.asSharedFlow()

    private var client: org.meshtastic.mqtt.MqttClient? = null
    private var collectJob: Job? = null

    /** Serializes connect/disconnect; several callers can race on reconnect. */
    private val mutex = Mutex()

    override suspend fun connect(
        host: String,
        port: Int,
        clientId: String,
        subscriptions: List<String>,
    ) = mutex.withLock {
        disconnectLocked()
        _connectionState.value = MqttConnectionState.Connecting
        val mqtt = org.meshtastic.mqtt.MqttClient(clientId) {
            transportFactory = org.meshtastic.mqtt.transport.tcp.TcpTransportFactory()
            keepAliveSeconds = 30
            autoReconnect = true
            defaultQos = org.meshtastic.mqtt.QoS.AT_LEAST_ONCE
        }
        try {
            mqtt.connect(org.meshtastic.mqtt.MqttEndpoint.Tcp(host = host, port = port))
            subscriptions.forEach { topic ->
                mqtt.subscribe(topic, org.meshtastic.mqtt.QoS.AT_MOST_ONCE)
            }
            client = mqtt
            collectJob = scope.launch {
                mqtt.messages.collect { msg ->
                    _messages.emit(
                        MqttMessage(
                            topic = msg.topic,
                            payload = msg.payload.toByteArray(),
                            retained = msg.retain,
                        )
                    )
                }
            }
            _connectionState.value = MqttConnectionState.Connected
            log.i { "connected to $host:$port as $clientId (${subscriptions.size} subscriptions)" }
        } catch (e: Exception) {
            // Close the half-open client, otherwise its socket and internal
            // reconnect loop outlive this failed attempt.
            runCatching { mqtt.close() }
            client = null
            _connectionState.value = MqttConnectionState.Failed(e.message ?: "connect failed")
            log.w(e) { "connect to $host:$port failed" }
            throw e
        }
    }

    override suspend fun publish(topic: String, payload: String, qos: Int, retain: Boolean) {
        val mqtt = client ?: error("MQTT not connected")
        mqtt.publish(
            org.meshtastic.mqtt.MqttMessage(
                topic = topic,
                payload = payload.encodeToByteArray(),
                qos = when (qos) {
                    0 -> org.meshtastic.mqtt.QoS.AT_MOST_ONCE
                    2 -> org.meshtastic.mqtt.QoS.EXACTLY_ONCE
                    else -> org.meshtastic.mqtt.QoS.AT_LEAST_ONCE
                },
                retain = retain,
            )
        )
        log.d { "published $topic <- $payload" }
    }

    override suspend fun disconnect() = mutex.withLock { disconnectLocked() }

    private suspend fun disconnectLocked() {
        collectJob?.cancelAndJoin()
        collectJob = null
        client?.let { runCatching { it.close() } }
        client = null
        _connectionState.value = MqttConnectionState.Disconnected
    }
}
