package fi.marmorikatu.app.screens

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/** Rendered state of the Valot screen: the four floor sections, stacked. */
data class ValotUiState(
    val loading: Boolean = true,
    val floors: List<ValotFloor> = emptyList(),
    /** The scene preset whose live light set matches exactly, for chip highlighting. */
    val activePreset: KotiScene? = null,
)

/** One floor section: heading + its area cards. */
data class ValotFloor(
    val floor: Floor,
    val name: String,
    val icon: ImageVector,
    val areas: List<ValotArea>,
    /** How many areas have at least one light on — the "N päällä" header count. */
    val areasOn: Int,
)

/** One collapsible area card: a named group of fixtures with live per-light state. */
data class ValotArea(
    val key: String,
    val name: String,
    val icon: ImageVector,
    val lights: List<ValotLight>,
    val onCount: Int,
) {
    val total: Int get() = lights.size
    val isOn: Boolean get() = onCount > 0
}

/** One fixture row inside an area card. */
data class ValotLight(val id: Int, val label: String, val on: Boolean, val pending: Boolean)

/**
 * Drives the Valot screen. The area grouping, labels and icons come from the
 * static [LIGHT_AREAS] table (transcribed from the design); this maps the live
 * light catalog onto it by id and forwards optimistic control commands.
 */
class ValotViewModel(
    private val lights: LightsRepository,
) : ViewModel() {

    private val loaded = MutableStateFlow(false)

    val uiState: StateFlow<ValotUiState> =
        combine(lights.lights, loaded) { list, isLoaded ->
            val byId = list.associateBy { it.id }
            val floors = VALOT_FLOOR_ORDER.mapNotNull { floor ->
                val (name, icon) = valotFloorMeta(floor)
                val areas = LIGHT_AREAS.filter { it.floor == floor }.map { area ->
                    val fixtures = area.lights.map { al ->
                        val live = byId[al.id]
                        ValotLight(
                            id = al.id,
                            label = al.label,
                            on = live?.displayedOn ?: false,
                            pending = live?.pendingOn != null,
                        )
                    }
                    ValotArea(area.key, area.name, area.icon, fixtures, fixtures.count { it.on })
                }
                if (areas.isEmpty()) null
                else ValotFloor(floor, name, icon, areas, areas.count { it.isOn })
            }
            // A preset chip highlights when the live light set exactly matches it;
            // Elokuva's scope is basement-only (user rule) so it matches regardless
            // of the rest of the house.
            val activePreset = KotiScene.entries.firstOrNull { sceneMatches(it, list) }
            ValotUiState(
                loading = !isLoaded && list.isEmpty(),
                floors = floors,
                activePreset = activePreset,
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

    /** Toggle a single fixture. Optimistic; a failure surfaces via [failure]. */
    fun toggle(id: Int, on: Boolean) {
        viewModelScope.launch { runCatching { lights.setLight(id, on) } }
    }

    /** Turn every light off. */
    fun allOff() {
        viewModelScope.launch { runCatching { lights.setAll(false) } }
    }

    /** Turn off every fixture on one floor (the floor header's "Sammuta"). */
    fun floorOff(floor: Floor) {
        viewModelScope.launch {
            val ids = LIGHT_AREAS.filter { it.floor == floor }.flatMap { it.lights }.map { it.id }.toSet()
            val byId = lights.lights.value.associateBy { it.id }
            ids.forEach { id ->
                if (byId[id]?.displayedOn == true) runCatching { lights.setLight(id, false) }
            }
        }
    }

    /**
     * Apply a scene preset: its lights come on and every other light in the
     * scene's scope goes off. Bedrooms are never touched, and Elokuva's scope is
     * the basement only (user rule), so it leaves the rest of the house alone.
     * Pressing a preset that is already active toggles it off — every light in
     * its scope goes dark.
     */
    fun applyPreset(scene: KotiScene) {
        viewModelScope.launch {
            val list = lights.lights.value
            // Toggle: an already-active preset clears its scope instead of re-applying.
            val onIds = if (sceneMatches(scene, list)) emptySet() else sceneOnLightIds(scene, list)
            sceneScopeLights(scene, list).forEach { l ->
                val target = l.id in onIds
                if (l.displayedOn != target) runCatching { lights.setLight(l.id, target) }
            }
        }
    }

    /**
     * Whether the live light state within [scene]'s scope exactly matches the
     * scene — the same test that highlights its chip, reused so the toggle and
     * the highlight can never disagree.
     */
    private fun sceneMatches(scene: KotiScene, list: List<Light>): Boolean {
        val scopeIds = sceneScopeLights(scene, list).map { it.id }.toSet()
        val onInScope = list.filter { it.displayedOn && it.id in scopeIds }.map { it.id }.toSet()
        return sceneOnLightIds(scene, list) == onInScope
    }
}
