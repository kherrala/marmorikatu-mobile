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
     * Apply a scene level to an area: turn on the first N of [orderedIds], off
     * the rest, where N = 0 / 1 / ceil(size/2) / size. Only lights whose shown
     * state differs are commanded.
     */
    fun setAreaLevel(orderedIds: List<Int>, level: LightLevel) {
        viewModelScope.launch {
            val current = lights.lights.value.associateBy { it.id }
            val n = targetCount(level, orderedIds.size)
            orderedIds.forEachIndexed { i, id ->
                val light = current[id] ?: return@forEachIndexed
                val target = i < n
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
        Floor.entries.mapNotNull { floor ->
            val floorLights = list.filter { it.floor == floor }
            if (floorLights.isEmpty()) return@mapNotNull null

            val areas = LinkedHashMap<String, MutableList<Light>>()
            floorLights.forEach { light ->
                val area = light.name.trim().substringBefore(' ').ifBlank { light.name }
                areas.getOrPut(area) { mutableListOf() }.add(light)
            }

            val items = areas.map { (areaName, group) ->
                if (group.size >= 2) {
                    val onCount = group.count { it.displayedOn }
                    AreaUi.SceneArea(
                        floorLabel = floor.label,
                        name = areaName,
                        level = levelFor(onCount, group.size),
                        lights = group.toList(),
                    )
                } else {
                    AreaUi.SingleLight(floor.label, group.first())
                }
            }
            FloorSection(label = floor.label, areas = items)
        }

    private companion object {
        fun ceilHalf(size: Int): Int = (size + 1) / 2

        /** 0 → Off, 1 → Dim, ≤ ceil(size/2) → Base, else → Full. */
        fun levelFor(onCount: Int, size: Int): LightLevel = when {
            onCount <= 0 -> LightLevel.Off
            onCount == 1 -> LightLevel.Dim
            onCount <= ceilHalf(size) -> LightLevel.Base
            else -> LightLevel.Full
        }

        fun targetCount(level: LightLevel, size: Int): Int = when (level) {
            LightLevel.Off -> 0
            LightLevel.Dim -> 1
            LightLevel.Base -> ceilHalf(size)
            LightLevel.Full -> size
        }
    }
}
