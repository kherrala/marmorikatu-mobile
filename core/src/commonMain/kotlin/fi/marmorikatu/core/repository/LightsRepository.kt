package fi.marmorikatu.core.repository

import fi.marmorikatu.core.log.logger
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.Light
import fi.marmorikatu.core.model.LightInfo
import fi.marmorikatu.core.transport.mcp.McpApi
import fi.marmorikatu.core.transport.mqtt.MqttClient
import fi.marmorikatu.core.transport.mqtt.MqttConnectionState
import fi.marmorikatu.core.transport.mqtt.MqttTopics
import fi.marmorikatu.core.transport.mqtt.PlcPayloads
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

interface LightsRepository {
    /** Catalog joined with live state and optimistic pending commands. */
    val lights: StateFlow<List<Light>>

    /** Emitted when a command was not confirmed within the deadline. */
    val controlFailures: SharedFlow<Int>

    /** Refreshes the name/floor catalog from MCP `list_lights`. */
    suspend fun refreshCatalog()

    suspend fun setLight(id: Int, on: Boolean)
    suspend fun setAll(on: Boolean)
    suspend fun setFloor(floor: Floor, on: Boolean)
}

class DefaultLightsRepository(
    private val mqtt: MqttClient,
    private val mcp: McpApi,
    private val scope: CoroutineScope,
    private val settings: Settings = Settings(),
) : LightsRepository {

    private val log = logger("lights")
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Guards every mutable field below plus [reconciler]. Commands arrive on
     * the UI coroutine while confirmations arrive on the MQTT collector, so
     * without this the maps would be mutated from two threads.
     */
    private val stateLock = Mutex()

    private var catalog: Map<Int, LightInfo> = loadCachedCatalog()
    private var liveState: Map<Int, Boolean> = emptyMap()
    /** Names from the retained `marmorikatu/names/lights` topic. */
    private var mqttNames: Map<Int, String> = emptyMap()
    private val reconciler = Reconciler<Int, Boolean>()

    private val _lights = MutableStateFlow<List<Light>>(emptyList())
    override val lights: StateFlow<List<Light>> = _lights.asStateFlow()

    private val _controlFailures = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    override val controlFailures: SharedFlow<Int> = _controlFailures.asSharedFlow()

    init {
        scope.launch {
            mqtt.messages.collect { msg ->
                when (msg.topic) {
                    MqttTopics.LIGHTS -> stateLock.withLock {
                        liveState = PlcPayloads.parseLights(msg.text())
                        liveState.forEach { (id, on) -> reconciler.observed(id, on) }
                        publishLocked()
                    }
                    MqttTopics.LIGHT_NAMES -> stateLock.withLock {
                        mqttNames = PlcPayloads.parseLightNames(msg.text())
                        publishLocked()
                    }
                }
            }
        }
        scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(5_000)
                val overdue = stateLock.withLock {
                    reconciler.expireOverdue().also { if (it.isNotEmpty()) publishLocked() }
                }
                if (overdue.isNotEmpty()) {
                    overdue.forEach { _controlFailures.tryEmit(it) }
                    log.w { "commands not confirmed for lights: $overdue" }
                }
            }
        }
        scope.launch { stateLock.withLock { publishLocked() } }
    }

    override suspend fun refreshCatalog() {
        val infos = mcp.listLights()
        if (infos.isEmpty()) return
        settings.putString(
            KEY_CATALOG,
            json.encodeToString(ListSerializer(LightInfo.serializer()), infos),
        )
        stateLock.withLock {
            catalog = infos.associateBy { it.id }
            publishLocked()
        }
    }

    override suspend fun setLight(id: Int, on: Boolean) {
        stateLock.withLock {
            reconciler.commandSent(id, on)
            publishLocked()
        }
        try {
            if (mqtt.connectionState.value is MqttConnectionState.Connected) {
                mqtt.publish(MqttTopics.lightSet(id), MqttTopics.lightSetPayload(on), qos = 1)
            } else {
                mcp.setLight(id.toString(), on)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The command never left the device: drop the optimistic state.
            stateLock.withLock {
                reconciler.cancel(id)
                publishLocked()
            }
            throw e
        }
    }

    override suspend fun setAll(on: Boolean) =
        batch(on, select = { knownIds() }, send = { mcp.setAllLights(on) })

    override suspend fun setFloor(floor: Floor, on: Boolean) = batch(
        on,
        select = { knownIds().filter { catalog[it]?.floor == floor } },
        send = { mcp.setLightsByFloor(floor.toServer(), on) },
    )

    /** Batch commands go through MCP: the server paces them per PLC cycle. */
    private suspend fun batch(
        on: Boolean,
        select: () -> Collection<Int>,
        send: suspend () -> Unit,
    ) {
        val ids = stateLock.withLock {
            select().also { ids ->
                ids.forEach { reconciler.commandSent(it, on) }
                publishLocked()
            }
        }
        try {
            send()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stateLock.withLock {
                ids.forEach { reconciler.cancel(it) }
                publishLocked()
            }
            throw e
        }
    }

    private fun knownIds(): Set<Int> = catalog.keys.ifEmpty { liveState.keys }

    private fun Floor.toServer(): Int? = when (this) {
        Floor.KELLARI -> 0
        Floor.ALAKERTA -> 1
        Floor.YLAKERTA -> 2
        Floor.ULKO -> null
    }

    /**
     * Only indices that have a name are real fixtures. `marmorikatu/lights`
     * also carries gaps in the PLC's `Controls[]` array (e.g. 21 and 27,
     * whose labels are empty); rendering those as controllable lights would
     * let a user switch a phantom output. The server skips them too.
     *
     * Caller must hold [stateLock].
     */
    private fun publishLocked() {
        val ids = (catalog.keys + liveState.keys).sorted()
        _lights.value = ids.mapNotNull { id ->
            val info = catalog[id]
            val name = info?.name ?: mqttNames[id] ?: return@mapNotNull null
            Light(
                id = id,
                name = name,
                floor = info?.floor ?: Floor.ULKO,
                isOn = liveState[id] ?: false,
                pendingOn = reconciler.pendingValue(id),
            )
        }
    }

    private fun loadCachedCatalog(): Map<Int, LightInfo> {
        val cached = settings.getStringOrNull(KEY_CATALOG) ?: return emptyMap()
        return runCatching {
            json.decodeFromString(ListSerializer(LightInfo.serializer()), cached)
                .associateBy { it.id }
        }.getOrElse { emptyMap() }
    }

    private companion object {
        const val KEY_CATALOG = "lights.catalog"
    }
}
