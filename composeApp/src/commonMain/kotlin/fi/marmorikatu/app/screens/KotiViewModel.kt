package fi.marmorikatu.app.screens

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.AttentionItem
import fi.marmorikatu.app.shell.StartupProgress
import kotlinx.coroutines.Job
import fi.marmorikatu.app.components.GarbagePickup
import fi.marmorikatu.app.components.MkClimateRoom
import fi.marmorikatu.app.components.MkStat
import fi.marmorikatu.app.components.MkStatStatus
import fi.marmorikatu.app.components.MkTagStatus
import fi.marmorikatu.app.components.TimeRangeOption
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.app.icons.MkIcons
import fi.marmorikatu.core.model.AirQuality
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.model.ElectricityPrices
import fi.marmorikatu.core.model.PriceTier
import fi.marmorikatu.core.model.HeatPumpAlarm
import fi.marmorikatu.core.model.HeatPumpStatus
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.HvacSummary
import fi.marmorikatu.core.model.Floor
import fi.marmorikatu.core.model.Light
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Rooms
import fi.marmorikatu.core.model.SpotPrice
import fi.marmorikatu.core.model.WeatherForecast
import fi.marmorikatu.core.repository.AnnouncementsRepository
import fi.marmorikatu.core.repository.ClimateRepository
import fi.marmorikatu.core.repository.EnergyRepository
import fi.marmorikatu.core.repository.InfoRepository
import fi.marmorikatu.core.model.RuuviReading
import fi.marmorikatu.core.model.RuuviSensors
import fi.marmorikatu.core.model.VentAlarm
import fi.marmorikatu.core.model.Ventilation
import fi.marmorikatu.core.repository.LightsRepository
import fi.marmorikatu.core.repository.SaunaHeatState
import fi.marmorikatu.core.repository.SaunaRepository
import fi.marmorikatu.core.speech.SpeechOutput
import fi.marmorikatu.core.transport.mcp.SaunaStatus
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Top news headline for the home dashboard's news card, with its summary. */
data class NewsHeadline(
    val title: String,
    val published: String,
    val description: String = "",
    val source: String = "",
) {
    /** The full read/expand text: headline then its summary. */
    val fullText: String get() = if (description.isBlank()) title else "$title\n\n$description"
}

/** One named outdoor-temperature reading (a sensor or the forecast API). */
data class OutdoorReading(val source: String, val celsius: Double)

/**
 * The outdoor temperature the weather card shows: the preferred [primary]
 * reading plus every other source currently available, so the card can name
 * where its number came from and show the alternatives for comparison.
 */
data class OutdoorTemp(
    val primary: OutdoorReading,
    val alternatives: List<OutdoorReading>,
)

/** One "restore this fixture to `on`" step of a scene's undo snapshot. */
@Serializable
data class SceneCmd(val id: Int, val on: Boolean)

private val SCENES_SERIALIZER =
    MapSerializer(String.serializer(), ListSerializer(SceneCmd.serializer()))

/**
 * The home dashboard's one-tap lighting scenes. Every scene is defined over the
 * shared living areas and deliberately never touches a bedroom (all bedroom
 * fixtures are named `MH…`), so a parent can set the mood without switching a
 * child's light. The house has no dimmer channel, so "dark" scenes are built by
 * leaving specific fixtures off rather than lowering brightness.
 */
enum class KotiScene(val label: String, val icon: ImageVector) {
    /** Morning: kitchen + living-room ceiling, staircase, foyer. LED strips stay off. */
    Aamuvalot("Aamuvalot", MkIcons.SunHorizon),
    /** Evening: living-room ceiling, kitchen, front yard, foyer. LED strips stay off. */
    Iltavalot("Iltavalot", MkIcons.MoonStars),
    /** Movie: only the basement billiard light; everything else in the common areas goes dark. */
    Elokuva("Elokuva", MkIcons.FilmSlate),
    /** Everything in the common areas off (bedrooms untouched). */
    KaikkiPois("Kaikki pois", MkIcons.Power),
}

/**
 * The common (non-bedroom) light ids that should be ON for a scene — the
 * exclusive target the Valot preset bar applies (everything else common goes
 * off). Mirrors the house rules in [KotiViewModel]'s scene logic: LED strips
 * never come on, Elokuva is billiard-only, Kaikki pois is empty.
 */
/**
 * A bedroom light — never touched by house-wide scenes/presets. The upstairs
 * rooms are named after their occupants in the MCP catalog (Aarni/Seela/
 * Aikuiset) while the MQTT fallback still uses the old "MH…" prefix, so both
 * naming schemes must be recognised or a preset would switch a child's room off.
 */
internal fun isBedroomLight(name: String): Boolean {
    val n = name.trim()
    return n.startsWith("MH", ignoreCase = true) ||
        listOf("Aarni", "Seela", "Aikuiset").any { n.contains(it, ignoreCase = true) }
}

internal fun sceneOnLightIds(scene: KotiScene, lights: List<Light>): Set<Int> {
    fun Light.has(vararg n: String) = n.any { name.contains(it, ignoreCase = true) }
    fun Light.isLed() = name.contains("ledi", ignoreCase = true)
    fun Light.kitchenCeiling() = has("Ruokailu") || (has("Keittiö") && has("atto"))
    fun Light.livingCeiling() = has("Olohuone") && has("katto")
    fun Light.foyer() = has("Eteinen", "Tuulikaappi")
    fun Light.stairs() = has("Portaikko", "Aula rappuset")
    val common = lights.filterNot { isBedroomLight(it.name) }
    return when (scene) {
        KotiScene.Aamuvalot -> common.filter { !it.isLed() && (it.kitchenCeiling() || it.livingCeiling() || it.stairs() || it.foyer()) }
        KotiScene.Iltavalot -> common.filter { !it.isLed() && (it.livingCeiling() || it.kitchenCeiling() || it.has("Sisäänkäynti") || it.foyer()) }
        // Elokuva is basement-only (see [sceneScopeLights]): the theater/biljard
        // light comes on, every other basement light goes off, and nothing
        // elsewhere in the house is touched.
        KotiScene.Elokuva -> common.filter { it.floor == Floor.KELLARI && it.has("biljard") }
        KotiScene.KaikkiPois -> emptyList()
    }.map { it.id }.toSet()
}

/**
 * The lights a [scene] is allowed to switch. Most scenes own the whole common
 * (non-bedroom) set — applying one turns its members on and every other common
 * light off. Elokuva is the exception: it governs only the basement, so starting
 * a movie never disturbs lights elsewhere in the house.
 */
internal fun sceneScopeLights(scene: KotiScene, lights: List<Light>): List<Light> {
    val common = lights.filterNot { isBedroomLight(it.name) }
    return when (scene) {
        KotiScene.Elokuva -> common.filter { it.floor == Floor.KELLARI }
        else -> common
    }
}

/** "At the door" banner content, surfaced only for a person announcement. */
data class DoorInfo(
    val title: String,
    val time: String,
    val subtitle: String,
    /** Base64 snapshot the announcer attached, when the camera sent one. */
    val image: String? = null,
)

/**
 * One KPI tile plus everything its detail view needs. Colours are intentionally
 * absent — the series line colour is theme-dependent and is applied in the
 * composable, so this holder stays free of Compose theme state.
 */
data class KotiKpi(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val value: String,
    val unit: String?,
    val statStatus: MkStatStatus,
    val tag: String?,
    val tagStatus: MkTagStatus,
    // Detail view.
    val detailStatus: String,
    val detailUnit: String,
    val seriesValues: List<Float>,
    val labels: List<String>,
    val stats: List<MkStat>,
    /**
     * Epoch-seconds of this tile's source data (a Ruuvi `ts`, the ThermIQ push
     * time, or the snapshot fetch): flashes the tile when it advances (a real new
     * reading, even if the value repeats) and dims it when it goes [stale].
     */
    val freshAsOf: Long? = null,
    /** True when [freshAsOf] is old enough that the feed looks dead; dims the tile. */
    val stale: Boolean = false,
    /** InfluxDB source for the detail chart's on-demand range history, or null. */
    val detailMeasurement: String? = null,
    val detailField: String? = null,
    val detailTagKey: String? = null,
    val detailTagValue: String? = null,
)

/** Everything the Koti screen renders, derived from live + snapshot sources. */
data class KotiUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val attention: List<AttentionItem> = emptyList(),
    val door: DoorInfo? = null,
    val rooms: List<MkClimateRoom> = emptyList(),
    val kpis: List<KotiKpi> = emptyList(),
    /** The 7 climate stat tiles for the tablet/kiosk dashboard's top row. */
    val kioskStats: List<KotiKpi> = emptyList(),
)

/** One temperature line for the tablet dashboard's 24 h chart. */
data class DashSeries(val name: String, val values: List<Float>)

/** One hourly spot-price bar for the tablet dashboard's price card. */
data class DashBar(val cents: Float, val past: Boolean, val expensive: Boolean, val cheap: Boolean = false)

/** One event-feed row for the tablet dashboard. */
data class DashEvent(val text: String, val time: String, val priority: Int)

/**
 * Home screen. Room temperatures / heating demand / announcements / heat pump
 * are live StateFlows; sauna, prices, HVAC and air quality are on-demand
 * snapshots refreshed via [refresh]. Absent sources render "Ei tietoa" rather
 * than a fabricated number — including the heat-pump tiles when the ThermIQ
 * register feed goes quiet.
 */
class KotiViewModel(
    private val climateRepo: ClimateRepository,
    private val energyRepo: EnergyRepository,
    private val saunaRepo: SaunaRepository,
    private val lightsRepo: LightsRepository,
    private val infoRepo: InfoRepository,
    private val tts: SpeechOutput,
    private val startupProgress: StartupProgress,
    announcementsRepo: AnnouncementsRepository,
    private val settings: Settings = Settings(),
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _weather = MutableStateFlow<WeatherForecast?>(null)
    /** Latest outdoor weather; null until loaded / if unavailable. */
    val weather: StateFlow<WeatherForecast?> = _weather.asStateFlow()

    private val _news = MutableStateFlow<List<NewsHeadline>>(emptyList())
    /** The latest news headlines (top first); empty until loaded / if unavailable. */
    val news: StateFlow<List<NewsHeadline>> = _news.asStateFlow()

    private val _nextGarbage = MutableStateFlow<GarbagePickup?>(null)
    /** Soonest upcoming waste pickup for the home-screen card; null until loaded / none. */
    val nextGarbage: StateFlow<GarbagePickup?> = _nextGarbage.asStateFlow()

    /**
     * The preset whose exact light set is live, if any — derived from the live
     * light state (not a tapped flag), so a chip highlights only when the house
     * actually matches it, and "Kaikki pois" only when every common light is off.
     */
    val activePreset: StateFlow<KotiScene?> =
        lightsRepo.lights.map { list ->
            KotiScene.entries.firstOrNull { scene ->
                val scopeIds = sceneScopeLights(scene, list).map { it.id }.toSet()
                val onInScope = list.filter { it.displayedOn && it.id in scopeIds }.map { it.id }.toSet()
                sceneOnLightIds(scene, list) == onInScope
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Apply a lighting preset: its named lights come on and every other light in
     * the scene's scope goes off. Bedrooms are never touched, and Elokuva's scope
     * is the basement only, so it leaves the rest of the house alone.
     */
    fun applyPreset(scene: KotiScene) {
        viewModelScope.launch {
            val list = lightsRepo.lights.value
            val onIds = sceneOnLightIds(scene, list)
            sceneScopeLights(scene, list)
                .forEach { l ->
                    val target = l.id in onIds
                    if (l.displayedOn != target) runCatching { lightsRepo.setLight(l.id, target) }
                }
        }
    }

    // Detail-view selection lives here (not in the composable) so it survives the
    // phone being turned to landscape: the pager recycles the Koti page across
    // that config change, discarding even rememberSaveable state.
    private val _detailKey = MutableStateFlow<String?>(null)
    val detailKey: StateFlow<String?> = _detailKey.asStateFlow()
    private val _roomDetailIndex = MutableStateFlow<Int?>(null)
    val roomDetailIndex: StateFlow<Int?> = _roomDetailIndex.asStateFlow()
    private val _detailRange = MutableStateFlow(TimeRangeOption.H24)
    val detailRange: StateFlow<TimeRangeOption> = _detailRange.asStateFlow()

    /**
     * Dashboard scroll offset, kept here for the same reason as the detail state:
     * the page is recreated when a detail chart forces landscape, so a plain
     * remembered ScrollState would snap back to the top on return.
     */
    var scrollOffset: Int = 0

    // The door-camera viewer also forces landscape, so its open flag must live in
    // the ViewModel too — a plain remember would be wiped by the page recreation
    // and the viewer would snap shut.
    private val _cameraOpen = MutableStateFlow(false)
    val cameraOpen: StateFlow<Boolean> = _cameraOpen.asStateFlow()

    fun openKpiDetail(key: String) { _roomDetailIndex.value = null; _detailKey.value = key }
    fun openRoomDetail(index: Int) { _detailKey.value = null; _roomDetailIndex.value = index }
    fun closeDetail() { _detailKey.value = null; _roomDetailIndex.value = null }
    fun setDetailRange(range: TimeRangeOption) { _detailRange.value = range }
    fun openCamera() { _cameraOpen.value = true }
    fun closeCamera() { _cameraOpen.value = false }

    // KPI detail chart: on-demand InfluxDB history for the selected time window.
    private val _kpiDetailSeries = MutableStateFlow<List<Float>>(emptyList())
    val kpiDetailSeries: StateFlow<List<Float>> = _kpiDetailSeries.asStateFlow()
    private val _kpiDetailLoading = MutableStateFlow(false)
    val kpiDetailLoading: StateFlow<Boolean> = _kpiDetailLoading.asStateFlow()

    /** Load a KPI's history for the detail chart at the chosen [range]. */
    fun loadKpiDetail(
        measurement: String,
        field: String,
        tagKey: String?,
        tagValue: String?,
        range: TimeRangeOption,
    ) {
        _kpiDetailSeries.value = emptyList()
        _kpiDetailLoading.value = true
        viewModelScope.launch {
            val (flux, every) = fluxRange(range)
            // "hvac_lto" is a computed series (see recoveryEfficiencyHistory), not a
            // real stored field, so it takes the dedicated query.
            _kpiDetailSeries.value = if (measurement == "hvac_lto") {
                climateRepo.recoveryEfficiencyHistory(flux, every)
            } else {
                climateRepo.metricHistory(measurement, field, flux, every, tagKey, tagValue)
            }
            _kpiDetailLoading.value = false
        }
    }

    fun clearKpiDetail() {
        _kpiDetailSeries.value = emptyList()
        _kpiDetailLoading.value = false
    }

    private val _newsReading = MutableStateFlow(false)
    /** True while the news digest is being read aloud; drives the Lue/Stop toggle. */
    val newsReading: StateFlow<Boolean> = _newsReading.asStateFlow()
    private var newsReadJob: Job? = null

    /**
     * Read the news aloud with the device voice, or stop if already reading —
     * the Lue button toggles. `speak()` suspends until the utterance finishes,
     * so the reading state flips back on its own when it ends; cancelling the
     * job stops the native engine mid-sentence.
     */
    fun toggleReadNews() {
        if (_newsReading.value) {
            newsReadJob?.cancel()
            tts.stop()
            _newsReading.value = false
            return
        }
        val items = _news.value
        if (items.isEmpty()) return
        val digest = items.joinToString(". ") { h ->
            if (h.description.isBlank()) h.title else "${h.title}. ${h.description}"
        }
        _newsReading.value = true
        newsReadJob = viewModelScope.launch {
            try {
                tts.speak(digest)
            } finally {
                _newsReading.value = false
            }
        }
    }

    private fun loadWeather() {
        viewModelScope.launch {
            val w = runCatching { infoRepo.weather() }.getOrNull()
            w?.let { _weather.value = it }
            startupProgress.mark(StartupProgress.KEY_WEATHER, w != null)
        }
    }

    /** Soonest waste pickup, from the same calendar feed the Kalenteri screen parses. */
    private fun loadGarbage() {
        viewModelScope.launch {
            val el = runCatching { infoRepo.calendar(CalendarParsing.CALENDAR_DAYS) }.getOrNull()
            if (el == null) { startupProgress.mark(StartupProgress.KEY_CALENDAR, false); return@launch }
            val parsed = CalendarParsing.parseCalendar(el)
            _nextGarbage.value = parsed.garbage.firstOrNull()
            snapshots.update { it.copy(calendarReminders = calendarReminders(parsed.days)) }
            startupProgress.mark(StartupProgress.KEY_CALENDAR, true)
        }
    }

    private fun loadNews() {
        viewModelScope.launch {
            val el = runCatching { infoRepo.news(8) }.getOrNull() as? JsonArray
            if (el == null) { startupProgress.mark(StartupProgress.KEY_NEWS, false); return@launch }
            startupProgress.mark(StartupProgress.KEY_NEWS, true)
            _news.value = el.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty()
                val title = str("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                NewsHeadline(
                    title = title,
                    published = str("published"),
                    description = str("description"),
                    source = str("source"),
                )
            }
                // The feed accumulates hourly bulletins, so the same headline can
                // recur — dedup by title so the list (and read-aloud) says it once.
                .distinctBy { it.title.trim().lowercase() }
        }
    }

    /** On-demand snapshots that back the strip + KPIs. */
    private data class Snapshots(
        val loading: Boolean = true,
        val error: String? = null,
        val sauna: SaunaStatus? = null,
        /** True while the sauna is on — climbing OR holding hot at setpoint. */
        val saunaOn: Boolean = false,
        /** Seconds the sauna has been on (since ignition), or null when off. */
        val saunaHeatingSeconds: Long? = null,
        val prices: ElectricityPrices? = null,
        /** Authoritative current price band from the backend optimizer, if reachable. */
        val priceTier: PriceTier? = null,
        val hvac: HvacSummary? = null,
        val air: AirQuality? = null,
        /** Latest outdoor temperature from the local Ruuvi sensor, if reachable. */
        val outdoorRuuviC: Double? = null,
        /** 24 h KPI sparkline series, keyed by KPI key (co2 / maalampo / vesi). */
        val history: Map<String, List<Float>> = emptyMap(),
        /** 24 h room temperature series, keyed by room display name, for the climate-card trend. */
        val roomHistory: Map<String, List<Float>> = emptyMap(),
        /** Today's + tomorrow's family calendar events, as "info" strip reminders. */
        val calendarReminders: List<AttentionItem> = emptyList(),
        /** Epoch seconds of this fetch; drives the per-refresh KPI flash. */
        val fetchedAt: Long = 0L,
    )

    private val snapshots = MutableStateFlow(Snapshots())

    /** 24 h room/outdoor temperature lines for the tablet dashboard chart. */
    private val _tempHistory = MutableStateFlow<List<DashSeries>>(emptyList())
    val tabletTempSeries: StateFlow<List<DashSeries>> = _tempHistory.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    /** When the KPI metrics were last fetched, for the Mittarit section freshness. */
    val metricsUpdatedAt: StateFlow<Long?> = snapshots
        .map { it.fetchedAt.takeIf { t -> t > 0L } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val uiState: StateFlow<KotiUiState> = combine(
        // Room temps, Ruuvi and ventilation are nested so the outer combine stays
        // at five typed flows; a Ruuvi push or a new ventilation alarm rebuilds the
        // state live.
        combine(climateRepo.roomTemperatures, climateRepo.ruuvi, climateRepo.ventilation) { t, r, v -> Triple(t, r, v) },
        climateRepo.heatingDemand,
        announcementsRepo.recent,
        snapshots,
        climateRepo.heatPump,
    ) { (temps, ruuvi, vent), demand, recent, snap, heatPump ->
        buildState(temps, ruuvi, vent, demand, recent, snap, freshHeatPump(heatPump))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KotiUiState())

    /**
     * Outdoor temperature for the weather card, preferring the on-site sensors
     * over the forecast API: the local Ruuvi (updates most often) first, then the
     * ventilation and heat-pump outdoor readings, then the API — each named so the
     * card can show where the reading came from.
     */
    val outdoorTemp: StateFlow<OutdoorTemp?> =
        combine(snapshots, climateRepo.heatPump, _weather) { snap, heatPump, weather ->
            // Ordered by trust: the on-site Ruuvi (updates most often) first, then
            // the ventilation and heat-pump outdoor readings, then the forecast API.
            val readings = buildList {
                snap.outdoorRuuviC?.let { add(OutdoorReading("Ruuvi", it)) }
                snap.hvac?.outdoorC?.let { add(OutdoorReading("IV-kone", it)) }
                freshHeatPump(heatPump).outdoorC?.let { add(OutdoorReading("Maalämpö", it)) }
                weather?.current?.temperature?.let { add(OutdoorReading("Ennuste", it)) }
            }
            readings.firstOrNull()?.let { OutdoorTemp(it, readings.drop(1)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Today's spot-price bars for the tablet dashboard (hourly), past/expensive tagged. */
    val tabletPriceBars: StateFlow<List<DashBar>> = snapshots
        .map { snap -> priceBars(snap.prices) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hour labels for the tablet price chart's x-axis. */
    val tabletPriceLabels: StateFlow<List<String>> = snapshots
        .map { snap -> priceAxisLabels(snap.prices) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Recent announcements as event-feed rows for the tablet dashboard. */
    val tabletEvents: StateFlow<List<DashEvent>> = announcementsRepo.recent
        .map { list -> list.take(8).map { DashEvent(it.text, Fmt.clock(it.ts), it.priority) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The ThermIQ feed is a live push; if it dies the last value would linger.
     * Treat a reading older than [HEAT_PUMP_STALE_SECONDS] as no data so the UI
     * says "Ei tietoa" rather than freezing a stale number as if it were live.
     */
    private fun freshHeatPump(hp: HeatPumpStatus): HeatPumpStatus {
        if (!hp.available) return hp
        val stampedAt = hp.updatedAtEpochSeconds ?: return hp
        val ageSeconds = nowEpochSeconds() - stampedAt
        return if (ageSeconds > HEAT_PUMP_STALE_SECONDS) HeatPumpStatus(available = false) else hp
    }

    init {
        refresh()
        loadNews()
        loadWeather()
        loadGarbage()
        // Room temperatures are a live MQTT StateFlow; stamp freshness whenever a
        // real reading lands, independently of the on-demand snapshot fetch.
        viewModelScope.launch {
            climateRepo.roomTemperatures.collect { temps ->
                if (temps.isNotEmpty()) _updatedAt.value = nowEpochSeconds()
            }
        }
        // Keep the KPI tiles live: silently re-fetch on a cadence so they pulse
        // with fresh data (new fetchedAt) without the pull-to-refresh spinner.
        viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_MS)
                refresh(showSpinner = false)
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

    // Sauna status carries no timestamp; latch when it starts heating so the strip
    // can escalate warn → alarm ("… pitkään") once it has run a long time.
    private var saunaHeatingSinceEpoch: Long? = null

    /** "2 t 10 min" style elapsed label for the escalated sauna alarm. */
    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "$h t $m min" else "$m min"
    }

    /** Re-pulls every on-demand snapshot concurrently; failures degrade quietly. */
    fun refresh() = refresh(showSpinner = true)

    /**
     * @param showSpinner drives the pull-to-refresh indicator. The periodic
     * auto-refresh passes false so it updates the tiles silently (they still
     * pulse on the new [Snapshots.fetchedAt]).
     */
    private fun refresh(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _refreshing.value = true
            // Weather is MCP-backed; refresh it on manual pulls, not the 12 s loop.
            if (showSpinner) {
                loadWeather()
                loadGarbage()
            }
            try {
                if (showSpinner) snapshots.update { it.copy(loading = true, error = null) }
                // Ask ThermIQ to publish now — its topic isn't retained, so a
                // refresh (incl. pull-to-refresh) is how we pull fresh registers.
                // Fire-and-forget so it never gates the on-demand snapshot below.
                viewModelScope.launch { runCatching { climateRepo.requestHeatPumpRead() } }
                val snap = coroutineScope {
                    val sauna = async { runCatching { saunaRepo.status() }.getOrNull() }
                    val prices = async { runCatching { energyRepo.electricityPrices() }.getOrNull() }
                    val priceTier = async { runCatching { energyRepo.priceTier() }.getOrNull() }
                    val hvac = async { runCatching { climateRepo.hvacSummary() }.getOrNull() }
                    val air = async { runCatching { climateRepo.airQuality() }.getOrNull() }
                    val ruuviOut = async { runCatching { climateRepo.outdoorRuuviTemp() }.getOrNull() }
                    // 24 h KPI sparkline series (metricHistory degrades to empty on failure).
                    val co2H = async { climateRepo.metricHistory("ruuvi", "co2") }
                    val indoorH = async { climateRepo.metricHistory("thermia", "indoor_temp") }
                    val powerH = async { climateRepo.metricHistory("hvac", "Lampopumppu_teho") }
                    val waterH = async { climateRepo.metricHistory("thermia", "hotwater_temp") }
                    val ulkoH = async { climateRepo.metricHistory("hvac", "Ulkolampotila") }
                    val kosteusH = async { climateRepo.metricHistory("hvac", "Suhteellinen_kosteus") }
                    val saunaH = async { climateRepo.metricHistory("ruuvi", "temperature", tagKey = "sensor_name", tagValue = "Sauna") }
                    val ltoH = async { climateRepo.recoveryEfficiencyHistory() }
                    // The sauna is "on" while it's climbing (the backend's
                    // is_heating) OR holding hot at setpoint — a plateau the
                    // temperature trend reveals but a single reading can't. Only pay
                    // for the history read when it might be on (climbing, already
                    // latched, or the last reading is warm), so a cold sauna doesn't
                    // query every refresh. The ignition is a one-shot: latch it from
                    // the history the first time we see it on, then count forward.
                    val saunaNow = sauna.await()
                    val climbing = saunaNow?.isHeating == true
                    val maybeOn = climbing || saunaHeatingSinceEpoch != null ||
                        (saunaNow?.currentTempC ?: 0.0) >= SAUNA_MAYBE_HOT_C
                    val heatState =
                        if (maybeOn) climateRepo.saunaHeatState() else SaunaHeatState(null, false)
                    val saunaOn = climbing || heatState.holding
                    saunaHeatingSinceEpoch =
                        if (saunaOn) saunaHeatingSinceEpoch ?: heatState.startEpoch else null
                    val saunaSecs = saunaHeatingSinceEpoch?.let { nowEpochSeconds() - it }
                    Snapshots(
                        loading = false,
                        sauna = saunaNow,
                        saunaOn = saunaOn,
                        saunaHeatingSeconds = saunaSecs,
                        prices = prices.await(),
                        priceTier = priceTier.await(),
                        hvac = hvac.await(),
                        air = air.await(),
                        outdoorRuuviC = ruuviOut.await(),
                        history = mapOf(
                            "co2" to co2H.await(),
                            "sisailma" to indoorH.await(),
                            "maalampo" to powerH.await(),
                            "vesi" to waterH.await(),
                            "ulko" to ulkoH.await(),
                            "kosteus" to kosteusH.await(),
                            "sauna" to saunaH.await(),
                            "lto" to ltoH.await(),
                        ),
                        // Carry the previous room history so the climate-card trend
                        // line doesn't collapse (and shift the layout) each refresh
                        // while the fresh series is still loading below.
                        roomHistory = snapshots.value.roomHistory,
                        // Preserve calendar reminders (loaded on a separate cadence).
                        calendarReminders = snapshots.value.calendarReminders,
                        fetchedAt = nowEpochSeconds(),
                    )
                }
                val allFailed = listOf(snap.sauna, snap.prices, snap.hvac, snap.air)
                    .all { it == null }
                snapshots.value = snap.copy(error = if (allFailed) "Tietojen haku epäonnistui" else null)
                if (!allFailed) _updatedAt.value = nowEpochSeconds()
                startupProgress.mark(StartupProgress.KEY_METRICS, !allFailed)

                // Tablet dashboard 24 h temperature lines (best-effort; the phone
                // layout ignores this, so a failure is silent).
                runCatching {
                    val hist = climateRepo.temperatureHistory("-24h", "30m")
                    _tempHistory.value = DASH_TEMP_ORDER.mapNotNull { name ->
                        hist[name]?.takeIf { it.isNotEmpty() }
                            ?.let { pts -> DashSeries(name, pts.map { it.value.toFloat() }) }
                    }
                    // Full per-room series (keyed by display name) for the climate-card trend line.
                    snapshots.update { s ->
                        s.copy(roomHistory = hist.mapValues { (_, pts) -> pts.map { it.value.toFloat() } })
                    }
                }
            } finally {
                if (showSpinner) _refreshing.value = false
            }
        }
    }

    private fun buildState(
        temps: List<RoomTemperature>,
        ruuvi: Map<String, RuuviReading>,
        vent: Ventilation,
        demand: List<HeatingDemand>,
        recent: List<Announcement>,
        snap: Snapshots,
        heatPump: HeatPumpStatus,
    ): KotiUiState = KotiUiState(
        loading = snap.loading,
        error = snap.error,
        attention = buildAttention(snap, ruuvi, vent, heatPump),
        door = buildDoor(recent),
        rooms = buildRooms(temps, demand, heatPump, snap.roomHistory),
        kpis = buildKpis(snap, heatPump, ruuvi),
        kioskStats = buildKioskStats(snap, heatPump),
    )

    // ── Attention strip: only REAL abnormal conditions ────────────────────────

    private fun buildAttention(
        snap: Snapshots,
        ruuvi: Map<String, RuuviReading>,
        vent: Ventilation,
        heatPump: HeatPumpStatus,
    ): List<AttentionItem> = buildList {
        // Sauna shows while it's on — climbing or holding hot at setpoint — and
        // escalates warn → alarm once it has been on a long time; the value
        // switches from temperature to elapsed time on the alarm.
        if (snap.saunaOn) {
            val secs = snap.saunaHeatingSeconds
            if (secs != null && secs >= SAUNA_LONG_SECONDS) {
                add(
                    AttentionItem(
                        status = "alarm",
                        icon = MkIcons.FlameFill,
                        text = "Sauna on ollut päällä pitkään",
                        value = formatDuration(secs),
                    ),
                )
            } else {
                add(
                    AttentionItem(
                        status = "warn",
                        icon = MkIcons.FlameFill,
                        text = "Sauna on päällä",
                        // Prefer elapsed on-time; fall back to temperature until the
                        // history read has pinned the ignition.
                        value = secs?.let { formatDuration(it) }
                            ?: snap.sauna?.currentTempC?.let { "${Fmt.oneDecimal(it)} °C" } ?: "",
                    ),
                )
            }
        }
        // The electric backup (aux) heater running is always worth flagging — it
        // means the heat pump alone can't keep up, and it burns expensive resistance
        // power. Show the current COP alongside when known.
        if (heatPump.auxHeaterActive && heatPump.available) {
            add(
                AttentionItem(
                    status = "warn",
                    icon = MkIcons.ThermometerHot,
                    text = "Maalämpö · lisävastus käytössä",
                    value = heatPump.cop?.let { "COP ${Fmt.oneDecimal(it)}" } ?: "",
                ),
            )
        }
        // Heat-pump fault codes (d19/d20) — always flagged; the design shows the
        // register:bit reference (e.g. "d19:3") as the value.
        if (heatPump.available) for (alarm in heatPump.alarms) add(hpAlarmItem(alarm))
        // Every live MVHR alarm from the ventilation unit, each with its own label.
        for (alarm in vent.alarms) add(ventAlarmItem(alarm))
        addAll(ruuviAlerts(ruuvi))
        // Today's / tomorrow's family calendar events, as info-tier rows (the strip
        // sorts these last, below any real alarms/warnings).
        addAll(snap.calendarReminders)
    }

    /**
     * Today's still-upcoming and tomorrow's family calendar events, rendered as
     * "info" strip rows (e.g. "Kesäjuhlat Sannalla" → "tänään 20:00"). Only the
     * two imminent days feed the strip — anything further out belongs on the
     * Kalenteri tab, not the attention row; today's already-passed events are
     * dropped, and the list is capped so a busy day can't flood the strip.
     */
    @OptIn(ExperimentalTime::class)
    private fun calendarReminders(days: List<CalendarDay>): List<AttentionItem> {
        val nowMinutes = Clock.System.now()
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .let { it.hour * 60 + it.minute }
        return days
            .filter { it.dayLabel.equals("Tänään", ignoreCase = true) || it.dayLabel.equals("Huomenna", ignoreCase = true) }
            .flatMap { day ->
                val isToday = day.dayLabel.equals("Tänään", ignoreCase = true)
                day.events
                    .filter { ev -> !isToday || (timeToMinutes(ev.time)?.let { it >= nowMinutes } ?: true) }
                    .map { ev ->
                        AttentionItem(
                            status = "info",
                            icon = MkIcons.Clock,
                            // Lead with the event title; the long location/address is
                            // left to the Kalenteri detail view.
                            text = ev.title,
                            value = "${day.dayLabel.lowercase()} ${ev.time}".trim(),
                        )
                    }
            }
            .take(3)
    }

    /** "HH:MM" → minutes-since-midnight, or null if it isn't a clock time. */
    private fun timeToMinutes(hhmm: String): Int? {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }

    /** One attention item per active ThermIQ [HeatPumpAlarm], Finnish label + code. */
    private fun hpAlarmItem(alarm: HeatPumpAlarm): AttentionItem {
        val (status, text) = when (alarm) {
            HeatPumpAlarm.HighPressure -> "alarm" to "Maalämpö · korkeapainevahti"
            HeatPumpAlarm.LowPressure -> "alarm" to "Maalämpö · matalapainevahti"
            HeatPumpAlarm.MotorBreaker -> "alarm" to "Maalämpö · moottorisuoja lauennut"
            HeatPumpAlarm.LowBrineFlow -> "alarm" to "Maalämpö · liuoksen virtaus matala"
            HeatPumpAlarm.LowBrineTemp -> "alarm" to "Maalämpö · liuoksen lämpötila matala"
            HeatPumpAlarm.Overheating -> "alarm" to "Maalämpö · ylikuumeneminen"
            HeatPumpAlarm.PhaseOrder -> "alarm" to "Maalämpö · väärä vaihejärjestys"
            HeatPumpAlarm.OutdoorSensor -> "warn" to "Maalämpö · ulkoanturin vika"
            HeatPumpAlarm.SupplySensor -> "warn" to "Maalämpö · menoanturin vika"
            HeatPumpAlarm.ReturnSensor -> "warn" to "Maalämpö · paluuanturin vika"
            HeatPumpAlarm.HotWaterSensor -> "warn" to "Maalämpö · käyttövesianturin vika"
            HeatPumpAlarm.IndoorSensor -> "warn" to "Maalämpö · sisäanturin vika"
        }
        return AttentionItem(status, MkIcons.ThermometerHot, text, alarm.code)
    }

    /** One attention item per active [VentAlarm], with its Finnish label + severity. */
    private fun ventAlarmItem(alarm: VentAlarm): AttentionItem = when (alarm) {
        VentAlarm.FreezingDanger ->
            AttentionItem("alarm", MkIcons.Snowflake, "Jäätymisvaara ilmanvaihdossa", "")
        VentAlarm.AfterheaterOverheat ->
            AttentionItem("alarm", MkIcons.ThermometerHot, "Ilmanvaihto · jälkilämmitin ylikuumeni", "")
        VentAlarm.SupplyFanFailure ->
            AttentionItem("alarm", MkIcons.Wind, "Ilmanvaihto · tulopuhaltimen vika", "")
        VentAlarm.ExhaustFanFailure ->
            AttentionItem("alarm", MkIcons.Wind, "Ilmanvaihto · poistopuhaltimen vika", "")
        VentAlarm.FilterGuard ->
            AttentionItem("warn", MkIcons.Wind, "Ilmanvaihto · suodatin vaihdettava", "IVK")
        VentAlarm.LowEfficiency ->
            AttentionItem("warn", MkIcons.FanFill, "Ilmanvaihto · hyötysuhde matala", "")
        VentAlarm.TempDeviation ->
            AttentionItem("warn", MkIcons.ThermometerHot, "Ilmanvaihto · lämpötilapoikkeama", "")
        VentAlarm.IrSensor ->
            AttentionItem("warn", MkIcons.Wind, "Ilmanvaihto · IR-anturin vika", "")
        VentAlarm.TempSensor ->
            AttentionItem("warn", MkIcons.ThermometerCold, "Ilmanvaihto · lämpöanturin vika", "")
        VentAlarm.ServiceReminder ->
            AttentionItem("warn", MkIcons.Wind, "Ilmanvaihto · huoltomuistutus", "")
    }

    /**
     * Alerts derived from the live Ruuvi tags: a warmed freezer/fridge, high CO₂
     * or PM2.5, a low tag battery, and a tag that has gone silent. Thresholds are
     * deliberately conservative so the strip flags real problems, not noise.
     */
    private fun ruuviAlerts(ruuvi: Map<String, RuuviReading>): List<AttentionItem> = buildList {
        val now = nowEpochSeconds()
        // A tag counts as reporting only if its reading is recent; value alerts
        // (warm/CO₂/battery) must not fire on a stale last reading — an offline tag
        // gets the "ei vastaa" item instead, never a contradictory value alarm.
        fun fresh(r: RuuviReading?): Boolean =
            r != null && r.tsEpoch > 0 && now - r.tsEpoch <= RUUVI_OFFLINE_SECONDS
        ruuvi[RuuviSensors.FREEZER]?.takeIf(::fresh)?.temperature?.let { t ->
            if (t > FREEZER_WARM_C) add(
                AttentionItem("alarm", MkIcons.Snowflake, "Pakastin lämmennyt", "${Fmt.oneDecimal(t)} °C"),
            )
        }
        ruuvi[RuuviSensors.FRIDGE]?.takeIf(::fresh)?.temperature?.let { t ->
            if (t > FRIDGE_WARM_C) add(
                AttentionItem("warn", MkIcons.ThermometerHot, "Jääkaappi lämmennyt", "${Fmt.oneDecimal(t)} °C"),
            )
        }
        ruuvi[RuuviSensors.AIR_QUALITY]?.takeIf(::fresh)?.let { air ->
            air.co2?.let { if (it > CO2_HIGH_PPM) add(
                AttentionItem("warn", MkIcons.Wind, "CO₂ koholla", "$it ppm"),
            ) }
            air.pm25?.let { if (it > PM25_HIGH) add(
                AttentionItem("warn", MkIcons.Wind, "Ilmanlaatu · hiukkasia", "${Fmt.oneDecimal(it)} µg/m³"),
            ) }
        }
        // Low battery — the weakest still-reporting tag, using a temperature-
        // compensated threshold (a cold freezer/outdoor tag reads low but healthy).
        ruuvi.values.filter { fresh(it) && batteryLow(it) }
            .minByOrNull { it.voltage ?: 9.0 }
            ?.let { add(AttentionItem("warn", MkIcons.ThermometerCold, "Vaihda paristo · ${it.sensorName}", "${Fmt.oneDecimal(it.voltage ?: 0.0)} V")) }
        // A tag that has stopped reporting (dead battery, out of range).
        ruuvi.values.filter { it.tsEpoch > 0 && now - it.tsEpoch > RUUVI_OFFLINE_SECONDS }
            .forEach { add(AttentionItem("warn", MkIcons.Wind, "${it.sensorName} · anturi ei vastaa", "")) }
    }

    /**
     * Temperature-compensated Ruuvi low-battery test. A CR2477 coin cell's voltage
     * sags in the cold, so a healthy battery in the freezer (~-18 °C) or outdoors
     * reads well under the room-temperature 2.5 V line — a flat threshold false-
     * alarms there. Thresholds follow Ruuvi's own cold-adjusted guidance.
     */
    private fun batteryLow(r: RuuviReading): Boolean {
        val v = r.voltage ?: return false
        val t = r.temperature ?: 20.0
        val threshold = when {
            t < -20.0 -> 2.0
            t < 0.0 -> 2.3
            t < 20.0 -> 2.4
            else -> 2.5
        }
        return v < threshold
    }

    // ── Door alert: only the newest "person" / "henkilö" announcement ─────────

    private fun buildDoor(recent: List<Announcement>): DoorInfo? {
        val newest = recent.firstOrNull() ?: return null
        val isPerson = newest.kind.startsWith("person", ignoreCase = true) ||
            newest.kind.contains("henkilö", ignoreCase = true) ||
            newest.text.contains("henkilö", ignoreCase = true)
        if (!isPerson) return null
        return DoorInfo(
            title = "Etupihalla henkilö",
            time = Fmt.clock(newest.ts),
            subtitle = newest.text,
            image = newest.image,
        )
    }

    // ── Climate card rooms ────────────────────────────────────────────────────

    private fun buildRooms(
        temps: List<RoomTemperature>,
        demand: List<HeatingDemand>,
        heatPump: HeatPumpStatus,
        roomHistory: Map<String, List<Float>>,
    ): List<MkClimateRoom> {
        // The heat pump is single-zone: one house-wide indoor target applies to
        // every room. No fallback — when the ThermIQ feed is down, target is null.
        val target = heatPump.indoorTargetC?.takeIf { heatPump.available }
        return Rooms.livingSpaces.mapNotNull { room ->
            // Only rooms with a real sensor reading — never invent one.
            val reading = temps.firstOrNull { it.key == room.mqttKey } ?: return@mapNotNull null
            val pct = demand.firstOrNull { it.key == room.heatingKey }?.percent
            val cool = reading.celsius < 19.0
            MkClimateRoom(
                name = room.displayName,
                icon = roomIcon(room.mqttKey),
                temp = Fmt.oneDecimal(reading.celsius),
                demand = pct,
                target = target,
                status = if (cool) "info" else "ok",
                statusLabel = if (cool) "Viileä" else null,
                historyField = room.influxField,
                history = roomHistory[room.displayName].orEmpty(),
            )
        }
    }

    private fun roomIcon(mqttKey: String): ImageVector = when {
        mqttKey == "keittio" -> MkIcons.CookingPotFill
        mqttKey.contains("aula") -> MkIcons.Stairs
        mqttKey.contains("eteinen") -> MkIcons.Door
        mqttKey in BEDROOM_KEYS -> MkIcons.BedFill
        else -> MkIcons.Thermometer
    }

    // ── KPI tiles ─────────────────────────────────────────────────────────────

    private fun buildKpis(
        snap: Snapshots,
        heatPump: HeatPumpStatus,
        ruuvi: Map<String, RuuviReading>,
    ): List<KotiKpi> {
        val now = nowEpochSeconds()
        val air = ruuvi[RuuviSensors.AIR_QUALITY]
        val saunaTag = ruuvi[RuuviSensors.SAUNA]
        val hpFresh = heatPump.updatedAtEpochSeconds?.takeIf { heatPump.available }
        // Each tile's freshness stamp: a Ruuvi `ts`, the ThermIQ push time, or —
        // for the snapshot-pulled tiles — the fetch time (always recent, so those
        // never dim; their death shows as "Ei tietoa" instead).
        fun freshFor(key: String): Long? = when (key) {
            "co2" -> air?.tsEpoch?.takeIf { it > 0 }
            "sauna" -> saunaTag?.tsEpoch?.takeIf { it > 0 }
            "sisailma", "maalampo", "vesi" -> hpFresh
            else -> snap.fetchedAt.takeIf { it > 0 }
        }
        // Tile order mirrors the design: Sähkö, Sauna, CO₂, Sisäilma, Maalämpö,
        // Ilmastointi, Käyttövesi, Kosteus (two per row, top-to-bottom).
        return listOf(
            electricityKpi(snap.prices, snap.priceTier),
            saunaKpi(snap.sauna, snap.history["sauna"].orEmpty(), snap.saunaOn),
            co2Kpi(snap.air, snap.history["co2"].orEmpty()),
            indoorKpi(heatPump, snap.history["sisailma"].orEmpty()),
            heatPumpKpi(heatPump, snap.hvac?.heatPumpPowerKw, snap.history["maalampo"].orEmpty()),
            ventilationKpi(),
            hotWaterKpi(heatPump, snap.history["vesi"].orEmpty()),
            humidityKpi(snap.hvac, snap.history["kosteus"].orEmpty()),
        ).map { kpi ->
            val fresh = freshFor(kpi.key)
            kpi.copy(
                freshAsOf = fresh,
                // Dim a tile whose live-sensor feed has gone quiet. Snapshot-timed
                // tiles carry a just-now fetch stamp, so they never trip this.
                stale = fresh != null && kpi.value != "Ei tietoa" &&
                    now - fresh > STALE_DIM_SECONDS,
            )
        }
    }

    /** Simple tile helper for the KPIs that have no detail chart of their own. */
    private fun plainKpi(
        key: String,
        icon: ImageVector,
        label: String,
        value: String?,
        unit: String?,
        spark: List<Float> = emptyList(),
        status: MkStatStatus = MkStatStatus.None,
        tag: String? = null,
        tagStatus: MkTagStatus = MkTagStatus.Neutral,
        detailMeasurement: String? = null,
        detailField: String? = null,
        detailTagKey: String? = null,
        detailTagValue: String? = null,
    ) = KotiKpi(
        key = key, icon = icon, label = label,
        value = value ?: "Ei tietoa", unit = if (value != null) unit else null,
        statStatus = status, tag = tag, tagStatus = tagStatus,
        detailStatus = "accent", detailUnit = unit ?: "",
        seriesValues = spark, labels = emptyList(), stats = emptyList(),
        detailMeasurement = detailMeasurement, detailField = detailField,
        detailTagKey = detailTagKey, detailTagValue = detailTagValue,
    )

    /** Cooling (ilmastointi): live from the `marmorikatu/cooling` pump state. */
    private fun ventilationKpi(): KotiKpi {
        val cooling = climateRepo.cooling.value
        val active = cooling.pumpCooling || cooling.coolingPump
        return plainKpi(
            key = "ilmastointi",
            icon = MkIcons.FanFill,
            label = "Ilmastointi",
            value = if (active) "Viilentää" else "Pois",
            unit = null,
            tag = if (active) "PÄÄLLÄ" else null,
            tagStatus = MkTagStatus.Ok,
        )
    }

    private fun indoorKpi(hp: HeatPumpStatus, spark: List<Float>): KotiKpi = plainKpi(
        key = "sisailma",
        icon = MkIcons.HouseFill,
        label = "Sisäilma",
        value = hp.indoorC?.takeIf { hp.available }?.let { Fmt.oneDecimal(it) },
        unit = "°C",
        spark = spark,
        detailMeasurement = "thermia",
        detailField = "indoor_temp",
    )

    private fun humidityKpi(hvac: HvacSummary?, history: List<Float>): KotiKpi = plainKpi(
        key = "kosteus",
        icon = MkIcons.DropHalf,
        label = "Kosteus",
        value = hvac?.humidityPct?.let { Fmt.int(it) },
        unit = "%",
        spark = history,
        detailMeasurement = "hvac",
        detailField = "Suhteellinen_kosteus",
    )

    private fun saunaKpi(sauna: SaunaStatus?, spark: List<Float>, on: Boolean): KotiKpi {
        return plainKpi(
            key = "sauna",
            icon = MkIcons.FlameFill,
            label = "Sauna",
            value = sauna?.currentTempC?.let { Fmt.oneDecimal(it) },
            unit = "°C",
            spark = spark,
            status = if (on) MkStatStatus.Warn else MkStatStatus.None,
            tag = if (on) "PÄÄLLÄ" else null,
            tagStatus = MkTagStatus.Warn,
            detailMeasurement = "ruuvi",
            detailField = "temperature",
            detailTagKey = "sensor_name",
            detailTagValue = "Sauna",
        )
    }

    /**
     * The design's kiosk top row: seven climate stat tiles. Each keeps a 24 h
     * sparkline where a single history series exists (outdoor, hot water,
     * humidity, LTO, CO₂); Sisällä and IVK have none. Absent data shows
     * "Ei tietoa" rather than a fabricated number.
     */
    private fun buildKioskStats(snap: Snapshots, hp: HeatPumpStatus): List<KotiKpi> {
        val h = snap.hvac
        val air = snap.air
        fun stat(
            key: String,
            icon: ImageVector,
            label: String,
            value: String?,
            unit: String?,
            spark: List<Float> = emptyList(),
            status: MkStatStatus = MkStatStatus.None,
            tag: String? = null,
            detailMeasurement: String? = null,
            detailField: String? = null,
        ) = KotiKpi(
            key = key,
            icon = icon,
            label = label,
            value = value ?: "Ei tietoa",
            unit = if (value != null) unit else null,
            statStatus = status,
            tag = tag,
            tagStatus = if (status == MkStatStatus.Warn) MkTagStatus.Warn else MkTagStatus.Neutral,
            detailStatus = "accent",
            detailUnit = unit ?: "",
            seriesValues = spark,
            labels = emptyList(),
            stats = emptyList(),
            detailMeasurement = detailMeasurement,
            detailField = detailField,
        )
        val co2 = air?.co2
        val ivkAlarm = h?.anyAlarm == true
        return listOf(
            stat("k_ulko", MkIcons.ThermometerCold, "Ulkona", h?.outdoorC?.let { Fmt.oneDecimal(it) }, "°C", snap.history["ulko"].orEmpty(), detailMeasurement = "hvac", detailField = "Ulkolampotila"),
            stat("k_sisalla", MkIcons.HouseFill, "Sisällä", hp.indoorC?.takeIf { hp.available }?.let { Fmt.oneDecimal(it) }, "°C", detailMeasurement = "thermia", detailField = "indoor_temp"),
            stat("k_vesi", MkIcons.DropFill, "Käyttövesi", hp.hotWaterC?.takeIf { hp.available }?.let { Fmt.oneDecimal(it) }, "°C", snap.history["vesi"].orEmpty(), detailMeasurement = "thermia", detailField = "hotwater_temp"),
            stat("k_kosteus", MkIcons.DropHalf, "Kosteus", h?.humidityPct?.let { Fmt.int(it) }, "%", snap.history["kosteus"].orEmpty(), detailMeasurement = "hvac", detailField = "Suhteellinen_kosteus"),
            stat("k_lto", MkIcons.FanFill, "LTO", h?.recoveryEfficiencyPct?.let { Fmt.int(it) }, "%", snap.history["lto"].orEmpty(), detailMeasurement = "hvac_lto", detailField = "lto"),
            stat(
                "k_co2", MkIcons.Wind, "CO₂", co2?.let { Fmt.int(it.value) }, co2?.unit ?: "ppm",
                snap.history["co2"].orEmpty(),
                status = when (air?.statusOf(co2)) {
                    "warn" -> MkStatStatus.Warn
                    "alarm" -> MkStatStatus.Alarm
                    else -> MkStatStatus.None
                },
                detailMeasurement = "ruuvi", detailField = "co2",
            ),
            stat(
                "k_ivk", MkIcons.FanFill, "IVK",
                value = if (h == null) null else if (ivkAlarm) "Tarkista" else "OK",
                unit = null,
                status = if (ivkAlarm) MkStatStatus.Warn else MkStatStatus.None,
                tag = if (ivkAlarm) "TARKISTA" else null,
            ),
        )
    }

    private fun electricityKpi(p: ElectricityPrices?, tier: PriceTier?): KotiKpi {
        val now = p?.currentCentsPerKwh
        // Backend optimizer band wins; local clamped classification is the fallback.
        val band = tier ?: p?.currentTierFallback
        val stats = buildList {
            p?.minCentsPerKwh?.let { add(MkStat("min", "${Fmt.comma(it, 1)} c")) }
            p?.maxCentsPerKwh?.let { add(MkStat("max", "${Fmt.comma(it, 1)} c")) }
            p?.avgCentsPerKwh?.let { add(MkStat("ka", "${Fmt.comma(it, 1)} c")) }
        }
        return KotiKpi(
            key = "sahko",
            icon = MkIcons.LightningFill,
            label = "Sähkö nyt",
            value = now?.let { Fmt.oneDecimal(it) } ?: "Ei tietoa",
            unit = if (now != null) "c/kWh" else null,
            statStatus = if (band == PriceTier.Expensive) MkStatStatus.Warn else MkStatStatus.None,
            tag = when (band) {
                PriceTier.Expensive -> "KALLIS"
                PriceTier.Cheap -> "EDULLINEN"
                else -> null
            },
            tagStatus = if (band == PriceTier.Cheap) MkTagStatus.Ok else MkTagStatus.Warn,
            detailStatus = if (band == PriceTier.Expensive) "warn" else "accent",
            detailUnit = "c/kWh",
            seriesValues = p?.today?.map { it.centsPerKwh.toFloat() } ?: emptyList(),
            labels = priceLabels(p?.today ?: emptyList()),
            stats = stats,
            detailMeasurement = "electricity",
            detailField = "price_with_tax",
        )
    }

    private fun co2Kpi(air: AirQuality?, history: List<Float>): KotiKpi {
        val co2 = air?.co2
        // The sensor location arrives as e.g. "Kitchen (Keittiö)"; prefer the
        // Finnish name in parentheses so the tile reads "CO₂ · Keittiö".
        val loc = air?.location?.takeIf { it.isNotBlank() }?.let { raw ->
            Regex("""\(([^)]+)\)""").find(raw)?.groupValues?.get(1)?.trim() ?: raw
        }
        val st = air?.statusOf(co2)
        return KotiKpi(
            key = "co2",
            icon = MkIcons.Wind,
            label = "CO₂" + (loc?.let { " · $it" } ?: ""),
            value = co2?.let { Fmt.int(it.value) } ?: "Ei tietoa",
            unit = co2?.unit,
            statStatus = when (st) {
                "warn" -> MkStatStatus.Warn
                "alarm" -> MkStatStatus.Alarm
                else -> MkStatStatus.None
            },
            tag = null,
            tagStatus = MkTagStatus.Neutral,
            detailStatus = st ?: "accent",
            detailUnit = co2?.unit ?: "ppm",
            // 24 h CO₂ trend from the Ruuvi `co2` field; empty hides the sparkline.
            seriesValues = history,
            labels = emptyList(),
            stats = emptyList(),
            detailMeasurement = "ruuvi",
            detailField = "co2",
        )
    }

    /**
     * Running state and temperatures are live from the ThermIQ register feed;
     * the power draw (kW) is merged in from the pump's own energy meter, which
     * the `hvac` measurement carries independently.
     */
    private fun heatPumpKpi(hp: HeatPumpStatus, powerKw: Double?, history: List<Float>): KotiKpi {
        val running = hp.running
        return KotiKpi(
            key = "maalampo",
            icon = MkIcons.ThermometerHot,
            label = "Maalämpö",
            value = when {
                !hp.available -> "Ei tietoa"
                running -> "Käy"
                else -> "Seis"
            },
            unit = null,
            statStatus = MkStatStatus.None,
            tag = when {
                !hp.available -> null
                hp.hotWaterActive -> "Käyttövesi"
                running && powerKw != null -> "${Fmt.oneDecimal(powerKw)} kW"
                else -> null
            },
            tagStatus = MkTagStatus.Ok,
            detailStatus = "accent",
            detailUnit = "kW",
            // 24 h heat-pump power draw from the `hvac` Lampopumppu_teho field.
            seriesValues = history,
            labels = emptyList(),
            detailMeasurement = "hvac",
            detailField = "Lampopumppu_teho",
            stats = buildList {
                if (powerKw != null) add(MkStat("TEHO", "${Fmt.oneDecimal(powerKw)} kW"))
                hp.brineDeltaC?.let { add(MkStat("LIUOS Δ", "${Fmt.oneDecimal(it)} °C")) }
                hp.outdoorC?.let { add(MkStat("ULKO", "${Fmt.oneDecimal(it)} °C")) }
            },
        )
    }

    private fun hotWaterKpi(hp: HeatPumpStatus, history: List<Float>): KotiKpi {
        val hw = hp.hotWaterC?.takeIf { hp.available }
        return KotiKpi(
            key = "vesi",
            icon = MkIcons.DropFill,
            label = "Käyttövesi",
            value = hw?.let { Fmt.oneDecimal(it) } ?: "Ei tietoa",
            unit = if (hw != null) "°C" else null,
            statStatus = MkStatStatus.None,
            tag = if (hp.available && hp.hotWaterActive) "Lämmitetään" else null,
            tagStatus = MkTagStatus.Ok,
            detailStatus = "accent",
            detailUnit = if (hw != null) "°C" else "",
            // 24 h hot-water temperature from the `thermia` hotwater_temp field.
            seriesValues = history,
            labels = emptyList(),
            stats = emptyList(),
            detailMeasurement = "thermia",
            detailField = "hotwater_temp",
        )
    }

    /**
     * Today's spot prices as dashboard bars. A bar is "past" once its ISO time
     * is at/behind the current hour (ISO strings sort chronologically), and its
     * band comes from the clamped cheap/expensive thresholds so a flat cheap day
     * still colours green rather than tagging its priciest hour as expensive.
     */
    private data class HourBar(val timeIso: String, val cents: Double, val past: Boolean, val tier: PriceTier)

    /**
     * Today's spot prices collapsed to whole-hour bars. The market moved to 15-min
     * slots (96/day), which is far too many bars for the dashboard; averaging each
     * hour's slots gives the 24 the design shows. Handles already-hourly data too
     * (each bucket is then a single slot).
     */
    private fun hourlyPrice(p: ElectricityPrices?): List<HourBar> {
        val today = p?.today ?: return emptyList()
        if (today.isEmpty()) return emptyList()
        val nowHour = p.currentHour?.take(13)   // "2026-07-11T20"
        return today.groupBy { it.time.take(13) }
            .entries.sortedBy { it.key }
            .map { (hourKey, slots) ->
                val avg = slots.map { it.centsPerKwh }.average()
                HourBar(
                    timeIso = slots.minByOrNull { it.time }?.time ?: hourKey,
                    cents = avg,
                    past = nowHour != null && hourKey < nowHour,
                    tier = p.tierOf(avg),
                )
            }
    }

    private fun priceBars(p: ElectricityPrices?): List<DashBar> = hourlyPrice(p).map { h ->
        DashBar(
            cents = h.cents.toFloat(),
            past = h.past,
            expensive = h.tier == PriceTier.Expensive,
            cheap = h.tier == PriceTier.Cheap,
        )
    }

    /** ~6 h clock labels for the price bar chart's x-axis, from the hourly buckets. */
    private fun priceAxisLabels(p: ElectricityPrices?): List<String> {
        val hours = hourlyPrice(p)
        if (hours.isEmpty()) return emptyList()
        val n = hours.size
        return listOf(0, n / 4, n / 2, 3 * n / 4, n - 1).distinct().map { Fmt.clock(hours[it].timeIso) }
    }

    /** ~6 h axis labels sampled from today's ISO spot-price times. */
    private fun priceLabels(today: List<SpotPrice>): List<String> {
        if (today.isEmpty()) return emptyList()
        val n = today.size
        return listOf(0, n / 4, n / 2, 3 * n / 4, n - 1)
            .distinct()
            .map { Fmt.clock(today[it].time) }
    }

    private companion object {
        val BEDROOM_KEYS = setOf("yk_aatu", "yk_onni", "yk_essi", "mh_ak")

        /** Sauna heating past this (2 h) escalates its strip item to an alarm. */
        const val SAUNA_LONG_SECONDS = 2L * 60L * 60L

        /**
         * A last-known Sauna reading at least this warm makes the refresh check the
         * plateau (a history read); below it the sauna is plainly off, so a cold
         * sauna doesn't query every cycle.
         */
        const val SAUNA_MAYBE_HOT_C = 45.0

        // ── Ruuvi alert + freshness thresholds ────────────────────────────────
        /** A live-sensor tile whose reading is older than this dims as stale. */
        const val STALE_DIM_SECONDS = 6L * 60L
        const val FREEZER_WARM_C = -15.0
        const val FRIDGE_WARM_C = 8.0
        const val CO2_HIGH_PPM = 1200
        const val PM25_HIGH = 25.0
        const val RUUVI_BATTERY_LOW_V = 2.5
        const val RUUVI_OFFLINE_SECONDS = 30L * 60L

        /**
         * Temperature lines shown on the tablet dashboard chart, in draw order.
         * The house has no living-room sensor (the design's "Olohuone"), so the
         * second line is the central upstairs hall instead — outdoor + a floor
         * apiece (upstairs, main, cellar).
         */
        val DASH_TEMP_ORDER = listOf("Ulko", "Yläkerta aula", "Keittiö", "Kellari")

        /**
         * ThermIQ publishes roughly every 15–60 s; past this the feed is
         * considered dead and the heat-pump tiles fall back to "Ei tietoa".
         */
        const val HEAT_PUMP_STALE_SECONDS = 30L * 60L

        /** Persisted active scenes + their undo snapshots. */
        const val KEY_SCENES = "koti.scenes"

        /** How often the KPI snapshots silently re-fetch, so the tiles stay live. */
        const val AUTO_REFRESH_MS = 12_000L

        /**
         * TimeRangeOption → (Flux range, every) for [ClimateRepository.temperatureHistory].
         * Kept per the screen contract; none of the four KPIs above is a room
         * temperature, so no detail currently pulls history and this stays unused.
         */
        @Suppress("unused")
        // Aggregation window per range, tuned for a smooth detail chart (~150–300
        // points). FluxClient reads InfluxDB directly (no 100-row cap), so a fine
        // grain is cheap and the blocky low-res look is gone.
        fun fluxRange(range: TimeRangeOption): Pair<String, String> = when (range) {
            TimeRangeOption.H6 -> "-6h" to "2m"     // 180 pts
            TimeRangeOption.H24 -> "-24h" to "5m"   // 288 pts
            TimeRangeOption.D7 -> "-7d" to "1h"     // 168 pts
            TimeRangeOption.D30 -> "-30d" to "4h"   // 180 pts
            TimeRangeOption.Y1 -> "-365d" to "1d"   // 365 pts
        }
    }
}
