package fi.marmorikatu.core.lifecycle

import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.log.logger
import fi.marmorikatu.core.repository.AnnouncementsRepository
import fi.marmorikatu.core.repository.LightsRepository
import fi.marmorikatu.core.transport.bridge.BridgeApi
import fi.marmorikatu.core.transport.mqtt.MqttClient
import fi.marmorikatu.core.transport.mqtt.MqttConnectionState
import fi.marmorikatu.core.transport.mqtt.MqttTopics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Foreground orchestration: while the app is visible, keep MQTT connected
 * (exponential backoff — this doubles as the VPN story: the tunnel comes up,
 * the next retry succeeds), the announcements SSE open, and a bridge health
 * poll running. On background, disconnect after a short grace so quick
 * app switches don't churn connections.
 */
class ConnectionManager(
    private val mqtt: MqttClient,
    private val bridge: BridgeApi,
    private val announcements: AnnouncementsRepository,
    private val lights: LightsRepository,
    private val appForeground: AppForeground,
    private val configStore: ConfigStore,
    private val scope: CoroutineScope,
    private val platformName: String,
) {
    private val log = logger("connections")

    private val _bridgeHealthy = MutableStateFlow<Boolean?>(null)
    val bridgeHealthy: StateFlow<Boolean?> = _bridgeHealthy.asStateFlow()

    val mqttState: StateFlow<MqttConnectionState> get() = mqtt.connectionState

    private val clientId = "mk-mobile-$platformName-${randomSuffix()}"
    private var running = false
    private val jobs = mutableListOf<Job>()

    fun start() {
        if (running) return
        running = true
        scope.launch {
            // collectLatest serializes start/stop and cancels a pending grace
            // delay when the app returns before it elapses. Cancelling jobs
            // only here (never on a separate coroutine that may not run) keeps
            // `jobs` an accurate record — otherwise a quick background/
            // foreground flip orphans a whole set of loops that can never be
            // cancelled, and duplicates pile up on every flip.
            appForeground.isForeground.collectLatest { foreground ->
                if (foreground) {
                    startConnections()
                } else {
                    delay(GRACE_MS)
                    stopConnections()
                }
            }
        }
    }

    private fun startConnections() {
        if (jobs.isNotEmpty()) return
        log.i { "foreground: starting connections as $clientId" }

        jobs += scope.launch { mqttLoop() }
        jobs += scope.launch { healthLoop() }
        jobs += scope.launch { catalogRefresh() }
        announcements.start()
    }

    private suspend fun stopConnections() {
        if (jobs.isEmpty()) return
        log.i { "background: dropping connections" }
        jobs.forEach { it.cancel() }
        jobs.clear()
        announcements.stop()
        // Teardown must finish even if the app comes back and cancels us.
        withContext(NonCancellable) { mqtt.disconnect() }
        _bridgeHealthy.value = null
    }

    /**
     * Reconnects with exponential backoff — which doubles as the VPN story:
     * once the tunnel is up, the next retry succeeds. Restarts from scratch
     * when the broker host is changed in settings.
     */
    private suspend fun mqttLoop() {
        configStore.config.collectLatest { config ->
            var backoffMs = 1_000L
            while (currentCoroutineContext().isActive) {
                try {
                    mqtt.connect(
                        host = config.mqttHost,
                        port = config.mqttPort,
                        clientId = clientId,
                        subscriptions = MqttTopics.STATE_SUBSCRIPTIONS,
                    )
                    backoffMs = 1_000L
                    // Park until the connection drops, then re-drive with backoff.
                    mqtt.connectionState.first {
                        it is MqttConnectionState.Failed || it is MqttConnectionState.Disconnected
                    }
                    log.d { "mqtt connection lost" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.d { "mqtt loop: ${e.message}; retry in ${backoffMs}ms" }
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun healthLoop() {
        while (currentCoroutineContext().isActive) {
            _bridgeHealthy.value = try {
                bridge.health().status == "ok"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            delay(30_000)
        }
    }

    private suspend fun catalogRefresh() {
        // Retry until the MCP catalog loads once per foreground session.
        var backoffMs = 2_000L
        while (currentCoroutineContext().isActive) {
            try {
                lights.refreshCatalog()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d { "catalog refresh failed: ${e.message}" }
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    private fun randomSuffix(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString { repeat(4) { append(chars[Random.nextInt(chars.length)]) } }
    }

    private companion object {
        /** Short background grace so app switches don't churn connections. */
        const val GRACE_MS = 5_000L
    }
}
