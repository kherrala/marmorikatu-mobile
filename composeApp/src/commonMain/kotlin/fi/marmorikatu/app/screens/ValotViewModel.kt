package fi.marmorikatu.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.LightLevel
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.Light
import fi.marmorikatu.core.repository.LightsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Rendered state of the whole Valot screen. */
data class ValotUiState(
    val loading: Boolean = true,
    val floors: List<FloorSection> = emptyList(),
    /** How many areas have at least one light on — the header count. */
    val areasOn: Int = 0,
)

/** One floor's section: a header label and the areas under it. */
data class FloorSection(val label: String, val areas: List<AreaUi>)

/** Whether an area has any light on — a scene above Off, or a lit single fixture. */
internal fun AreaUi.isOn(): Boolean = when (this) {
    is AreaUi.SceneArea -> level != LightLevel.Off
    is AreaUi.SingleLight -> light.displayedOn
}

/** An area is either a multi-light scene card or a lone light row. */
sealed interface AreaUi {
    val key: String

    /**
     * Two or more lights sharing a first word. [level] is derived from how many
     * are on; [lights] are kept in stable id order so the scene ladder turns on
     * the first N.
     */
    data class SceneArea(
        val floorLabel: String,
        val name: String,
        val level: LightLevel,
        val lights: List<Light>,
    ) : AreaUi {
        override val key: String get() = "scene:$floorLabel:$name"
    }

    /** A single fixture rendered as a plain toggle row. */
    data class SingleLight(val floorLabel: String, val light: Light) : AreaUi {
        override val key: String get() = "single:$floorLabel:${light.id}"
    }
}

/**
 * Drives the Valot screen: groups the live light catalog by floor and area,
 * derives the scene ladder, and forwards optimistic control commands to the
 * [LightsRepository].
 */
class ValotViewModel(
    private val lights: LightsRepository,
) : ViewModel() {

    private val loaded = MutableStateFlow(false)

    val uiState: StateFlow<ValotUiState> =
        combine(lights.lights, loaded) { list, isLoaded ->
            val floors = buildFloors(list)
            ValotUiState(
                loading = !isLoaded && list.isEmpty(),
                floors = floors,
                areasOn = floors.sumOf { section -> section.areas.count { it.isOn() } },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ValotUiState())

    private val _failure = MutableStateFlow(false)

    /** True briefly after a command failed to confirm; drives the alarm banner. */
    val failure: StateFlow<Boolean> = _failure.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    private var clearFailureJob: Job? = null

    init {
        viewModelScope.launch {
            lights.controlFailures.collect {
                _failure.value = true
                clearFailureJob?.cancel()
                clearFailureJob = viewModelScope.launch {
                    delay(6_000)
                    _failure.value = false
                }
            }
        }
        // The catalog is a live StateFlow; stamp freshness whenever real light
        // data lands (StateFlow already dedupes identical emissions).
        viewModelScope.launch {
            lights.lights.collect { list ->
                if (list.isNotEmpty()) _updatedAt.value = nowEpochSeconds()
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

    /** Load the name/floor catalog. Called from the screen on first composition. */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                runCatching { lights.refreshCatalog() }
            } finally {
                loaded.value = true
                _refreshing.value = false
            }
        }
    }

    /** Toggle a lone light. Optimistic; a failure surfaces via [failure]. */
    fun toggle(id: Int, on: Boolean) {
        viewModelScope.launch { runCatching { lights.setLight(id, on) } }
    }

    /**
     * Apply a scene level to an area. Off clears it, Full lights everything, and
     * the Dim / Base steps light only the first 1 / ceil(n/2) of the *plain*
     * fixtures — the LED strips (`… ledi`) are glaring, so they only come on at
     * Full. Only lights whose shown state differs are commanded.
     */
    fun setAreaLevel(orderedIds: List<Int>, level: LightLevel) {
        viewModelScope.launch {
            val current = lights.lights.value.associateBy { it.id }
            fun isLed(id: Int) = current[id]?.name?.contains("ledi", ignoreCase = true) == true
            val onIds: Set<Int> = when (level) {
                LightLevel.Off -> emptySet()
                LightLevel.Full -> orderedIds.toSet()
                else -> {
                    val ladder = orderedIds.filterNot { isLed(it) }
                    val n = if (level == LightLevel.Dim) minOf(1, ladder.size) else ceilHalf(ladder.size)
                    ladder.take(n).toSet()
                }
            }
            orderedIds.forEach { id ->
                val light = current[id] ?: return@forEach
                val target = id in onIds
                if (light.displayedOn != target) {
                    runCatching { lights.setLight(id, target) }
                }
            }
        }
    }

    /** Turn every light off. */
    fun allOff() {
        viewModelScope.launch { runCatching { lights.setAll(false) } }
    }

    private fun buildFloors(list: List<Light>): List<FloorSection> =
        FLOOR_ORDER.mapNotNull { floor ->
            val floorLights = list.filter { it.floor == floor }
            if (floorLights.isEmpty()) return@mapNotNull null

            val areas = LinkedHashMap<String, MutableList<Light>>()
            floorLights.forEach { light ->
                val area = light.name.trim().substringBefore(' ').ifBlank { light.name }
                areas.getOrPut(area) { mutableListOf() }.add(light)
            }

            val items = areas.map { (areaName, group) ->
                if (group.size >= 2) {
                    AreaUi.SceneArea(
                        floorLabel = floor.label,
                        name = areaName,
                        level = levelFor(group),
                        lights = group.toList(),
                    )
                } else {
                    AreaUi.SingleLight(floor.label, group.first())
                }
            }
            FloorSection(label = floor.label, areas = items)
        }

    private companion object {
        /** Floor pager order — living floors first, cellar and outdoors after. */
        val FLOOR_ORDER = listOf(Floor.ALAKERTA, Floor.YLAKERTA, Floor.KELLARI, Floor.ULKO)

        fun ceilHalf(size: Int): Int = (size + 1) / 2

        fun Light.isLed(): Boolean = name.contains("ledi", ignoreCase = true)

        /**
         * Which ladder step the area's live state reflects. All on → Full; any
         * LED on reads as Full (LEDs only light at Full); otherwise gauge by how
         * many of the plain fixtures are on.
         */
        fun levelFor(group: List<Light>): LightLevel {
            val onCount = group.count { it.displayedOn }
            if (onCount == 0) return LightLevel.Off
            if (onCount == group.size) return LightLevel.Full
            if (group.any { it.displayedOn && it.isLed() }) return LightLevel.Full
            val nonLed = group.filterNot { it.isLed() }
            val nonLedOn = nonLed.count { it.displayedOn }
            return when {
                nonLedOn <= 1 -> LightLevel.Dim
                nonLedOn <= ceilHalf(nonLed.size) -> LightLevel.Base
                else -> LightLevel.Full
            }
        }
    }
}
