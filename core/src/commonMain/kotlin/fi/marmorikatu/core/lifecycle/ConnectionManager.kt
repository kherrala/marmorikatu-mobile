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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Foreground orchestration: while the app is visible, keep MQTT connected
 * (adaptive backoff, see [reconnectDelay] — this doubles as the VPN story: the
 * tunnel comes up, the next retry succeeds), the announcements SSE open, and a
 * bridge health poll running. On background, disconnect after a short grace so
 * quick app switches don't churn connections. When home is unreachable every
 * loop relaxes to an infrequent poll so an away device isn't draining battery.
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

    /**
     * When true, connections are kept live even while the app is backgrounded —
     * for the always-on, plugged-in shelf-tablet kiosk, where instant light
     * state matters and there is no battery cost. The UI sets this for the
     * tablet surface. (On iOS a truly backgrounded app is still suspended by the
     * OS; a kiosk stays foregrounded, so this mainly benefits Android tablets.)
     */
    val keepAlive = MutableStateFlow(false)

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
            // Stay connected while foregrounded OR while kiosk keep-alive is on.
            combine(appForeground.isForeground, keepAlive) { fg, keep -> fg || keep }
                .collectLatest { active ->
                    if (active) {
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
        // Keep the announcements SSE open when the background service owns it: the
        // service shares this singleton repo, so stopping the stream here would
        // leave it with a dead feed — the reason background notifications never
        // arrived. MQTT and the health poll still drop to spare the radio; the
        // stream's own away-backoff keeps it cheap when home is unreachable.
        if (!configStore.config.value.backgroundEnabled) {
            announcements.stop()
        }
        // Teardown must finish even if the app comes back and cancels us.
        withContext(NonCancellable) { mqtt.disconnect() }
        _bridgeHealthy.value = null
    }

    /**
     * Reconnects with adaptive backoff (see [reconnectDelay]) — which doubles as
     * the VPN story: once the tunnel is up, the next retry succeeds. Restarts
     * from scratch when the broker host is changed in settings.
     */
    private suspend fun mqttLoop() {
        configStore.config.collectLatest { config ->
            var failures = 0
            while (currentCoroutineContext().isActive) {
                // Marked AFTER connect() returns so the hold time measures only the
                // established connection — a slow-FAILING connect (dropped SYN can
                // block for the TCP timeout, tens of seconds) must not be mistaken
                // for "we were reachable" and reset the away-battery backoff.
                var connectedAt: kotlin.time.TimeMark? = null
                try {
                    mqtt.connect(
                        host = config.mqttHost,
                        port = config.mqttPort,
                        clientId = clientId,
                        subscriptions = MqttTopics.STATE_SUBSCRIPTIONS,
                    )
                    connectedAt = TimeSource.Monotonic.markNow()
                    // Park until the connection drops, then re-drive with backoff.
                    mqtt.connectionState.first {
                        it is MqttConnectionState.Failed || it is MqttConnectionState.Disconnected
                    }
                    log.d { "mqtt connection lost" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.d { "mqtt loop: ${e.message}" }
                }
                // A connection that held a while means we are reachable — reconnect
                // fast. A connect that never established, or dropped quickly, ramps
                // toward the slow poll that spares the battery.
                val held = connectedAt?.let { it.elapsedNow() > CONNECTED_HOLD } ?: false
                failures = if (held) 0 else failures + 1
                delay(reconnectDelay(failures))
            }
        }
    }

    private suspend fun healthLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val ok = try {
                bridge.health().status == "ok"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            _bridgeHealthy.value = ok
            failures = if (ok) 0 else failures + 1
            // Steady keepalive while the house answers; once it stops (we are away)
            // relax to the shared away curve instead of probing every 30 s all day.
            delay(if (ok) HEALTHY_POLL else reconnectDelay(failures))
        }
    }

    private suspend fun catalogRefresh() {
        // Retry until the MCP catalog loads once per foreground session.
        var failures = 0
        while (currentCoroutineContext().isActive) {
            try {
                lights.refreshCatalog()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.d { "catalog refresh failed: ${e.message}" }
            }
            failures++
            delay(reconnectDelay(failures))
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
