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
    /** Every area currently on, house-wide — the permanent "Aktiiviset" section. */
    val activeAreas: List<AreaUi> = emptyList(),
    val floors: List<FloorSection> = emptyList(),
    /** How many areas have at least one light on — the header count. */
    val areasOn: Int = 0,
    /** The preset whose exact light set is live, if any — highlights its chip. */
    val activePreset: KotiScene? = null,
    /** Additive area presets whose lights are all currently on. */
    val activeAreaPresets: Set<LightAreaPreset> = emptySet(),
)

/** One floor's section: the floor, a header label, and the areas under it. */
data class FloorSection(val floor: Floor, val label: String, val areas: List<AreaUi>)

/** Whether an area has any light on. */
internal fun AreaUi.isOn(): Boolean = when (this) {
    is AreaUi.SceneArea -> level != LightLevel.Off
    is AreaUi.ToggleGroup -> lights.any { it.displayedOn }
    is AreaUi.SingleLight -> light.displayedOn
}

/** An area is a dimmable scene card, a plain on/off group, or a lone light row. */
sealed interface AreaUi {
    val key: String

    /**
     * Two or more lights sharing a first word, controlled by a dimmer ladder.
     * [level] is derived from how many are on; [lights] are kept in stable id
     * order so the ladder turns on the first N.
     */
    data class SceneArea(
        val floorLabel: String,
        val name: String,
        val level: LightLevel,
        val lights: List<Light>,
    ) : AreaUi {
        override val key: String get() = "scene:$floorLabel:$name"
    }

    /**
     * A group with only plain on/off switches (no dimmer) — utility rooms (WC,
     * bathrooms, bedrooms) and the decorative window lights, where levels make
     * no sense. Each fixture toggles independently.
     */
    data class ToggleGroup(
        val floorLabel: String,
        val name: String,
        val lights: List<Light>,
    ) : AreaUi {
        override val key: String get() = "group:$floorLabel:$name"
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
            val floors = buildFloorSections(list)
            val active = floors.flatMap { it.areas }.filter { it.isOn() }
            // A preset is "active" when the live common-on set exactly equals its
            // target — so its chip highlights, and Kaikki pois when all are off.
            val onCommon = list
                .filterNot { isBedroomLight(it.name) }
                .filter { it.displayedOn }.map { it.id }.toSet()
            val activePreset = KotiScene.entries.firstOrNull { sceneOnLightIds(it, list) == onCommon }
            // An additive area preset is "on" when every one of its lights is on.
            val onIds = list.filter { it.displayedOn }.map { it.id }.toSet()
            val activeAreaPresets = LightAreaPreset.entries.filter { preset ->
                val ids = areaPresetLightIds(preset, list)
                ids.isNotEmpty() && ids.all { it in onIds }
            }.toSet()
            ValotUiState(
                loading = !isLoaded && list.isEmpty(),
                activeAreas = active,
                floors = floors,
                areasOn = active.size,
                activePreset = activePreset,
                activeAreaPresets = activeAreaPresets,
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

    /**
     * Apply a lighting preset: its named areas come on (per the house rules),
     * every other common light goes off. Bedrooms are never touched.
     */
    fun applyPreset(scene: KotiScene) {
        viewModelScope.launch {
            val list = lights.lights.value
            val onIds = sceneOnLightIds(scene, list)
            list.filterNot { isBedroomLight(it.name) }
                .forEach { l ->
                    val target = l.id in onIds
                    if (l.displayedOn != target) runCatching { lights.setLight(l.id, target) }
                }
        }
    }

    /**
     * Toggle an additive area preset: if all its lights are already on, turn the
     * whole area off; otherwise turn it on. The rest of the house is untouched.
     */
    fun toggleAreaPreset(preset: LightAreaPreset) {
        viewModelScope.launch {
            val list = lights.lights.value
            val ids = areaPresetLightIds(preset, list)
            if (ids.isEmpty()) return@launch
            val byId = list.associateBy { it.id }
            val allOn = ids.all { byId[it]?.displayedOn == true }
            val target = !allOn
            ids.forEach { id ->
                if (byId[id]?.displayedOn != target) runCatching { lights.setLight(id, target) }
            }
        }
    }

    /** Turn off every light on one floor (the floor header's "Sammuta"). */
    fun floorOff(floor: Floor) {
        viewModelScope.launch {
            lights.lights.value
                .filter { it.floor == floor && it.displayedOn }
                .forEach { runCatching { lights.setLight(it.id, false) } }
        }
    }

    private fun buildFloorSections(list: List<Light>): List<FloorSection> =
        FLOOR_ORDER.mapNotNull { floor ->
            val floorLights = list.filter { it.floor == floor }
            if (floorLights.isEmpty()) return@mapNotNull null

            // Decorative window lights are pulled out into their own group.
            val windows = floorLights.filter { it.isWindow() }
            val rest = floorLights.filterNot { it.isWindow() }

            // The sauna only merges the bathroom on the floor it actually lives on.
            val saunaFloor = rest.any { it.name.contains("sauna", ignoreCase = true) }
            val areas = LinkedHashMap<String, MutableList<Light>>()
            rest.forEach { light ->
                areas.getOrPut(areaNameFor(light, saunaFloor)) { mutableListOf() }.add(light)
            }

            val items = mutableListOf<AreaUi>()
            areas.forEach { (areaName, group) ->
                items += when {
                    group.size == 1 -> AreaUi.SingleLight(floor.label, group.first())
                    isPlainGroup(areaName) -> AreaUi.ToggleGroup(floor.label, areaName, group.toList())
                    else -> AreaUi.SceneArea(floor.label, areaName, levelFor(group), group.toList())
                }
            }
            if (windows.isNotEmpty()) {
                items += AreaUi.ToggleGroup(floor.label, "Ikkunavalot", windows.toList())
            }

            // Dimmable rooms first for clarity, then plain on/off groups, then
            // lone fixtures; the decorative window group always sits last.
            FloorSection(floor = floor, label = floor.label, areas = items.sortedBy { tierOf(it) })
        }

    private companion object {
        /** Floor pager order — living floors first, cellar and outdoors after. */
        val FLOOR_ORDER = listOf(Floor.ALAKERTA, Floor.YLAKERTA, Floor.KELLARI, Floor.ULKO)

        fun ceilHalf(size: Int): Int = (size + 1) / 2

        fun Light.isLed(): Boolean = name.contains("ledi", ignoreCase = true)

        /** Decorative window lights — grouped apart from a room's normal lighting. */
        fun Light.isWindow(): Boolean =
            name.contains("ikkuna", ignoreCase = true)

        /**
         * The area a fixture belongs to. Beyond the first-word default this
         * folds the entry hall together (foyer + vestibule + storage → Eteinen),
         * puts the dining lights under the kitchen, and merges the bathroom into
         * the sauna on the floor the sauna is on.
         */
        fun areaNameFor(light: Light, saunaFloor: Boolean): String {
            val n = light.name
            val first = n.trim().substringBefore(' ').ifBlank { n }
            return when {
                n.contains("Eteinen", ignoreCase = true) ||
                    n.contains("Tuulikaappi", ignoreCase = true) ||
                    (first.equals("Varasto", ignoreCase = true) && !n.contains("ulko", ignoreCase = true)) -> "Eteinen"
                n.contains("Ruokailu", ignoreCase = true) -> "Keittiö"
                first.equals("Sauna", ignoreCase = true) || first.equals("Saunan", ignoreCase = true) -> "Sauna"
                saunaFloor && (first.startsWith("Kylpy", ignoreCase = true) || first.startsWith("Kylpu", ignoreCase = true)) -> "Sauna"
                // Fold the upstairs hall into one card: "Yläkerta aula …" and
                // "Aula …" would otherwise split into a floor-named area + a lone
                // "Aula" (see audit #8).
                n.contains("aula", ignoreCase = true) -> "Aula"
                else -> first
            }
        }

        /** Utility rooms get plain on/off switches, never a dimmer ladder. */
        fun isPlainGroup(area: String): Boolean {
            val a = area.lowercase()
            return a.startsWith("wc") || a.startsWith("kylpy") || a.startsWith("kylpu") ||
                a.startsWith("mh") || a.startsWith("sauna") || a.startsWith("tekninen") ||
                a.startsWith("kodinhoito")
        }

        /** Render order within a floor: dimmers → on/off groups → singles → windows. */
        fun tierOf(area: AreaUi): Int = when (area) {
            is AreaUi.SceneArea -> 0
            is AreaUi.ToggleGroup -> if (area.name == "Ikkunavalot") 3 else 1
            is AreaUi.SingleLight -> 2
        }

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
