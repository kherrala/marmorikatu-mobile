package fi.marmorikatu.app.screens

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.AttentionItem
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
import fi.marmorikatu.core.model.HeatPumpStatus
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.HvacSummary
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Rooms
import fi.marmorikatu.core.model.SpotPrice
import fi.marmorikatu.core.repository.AnnouncementsRepository
import fi.marmorikatu.core.repository.ClimateRepository
import fi.marmorikatu.core.repository.EnergyRepository
import fi.marmorikatu.core.repository.SaunaRepository
import fi.marmorikatu.core.transport.mcp.SaunaStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
)

/** Everything the Koti screen renders, derived from live + snapshot sources. */
data class KotiUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val attention: List<AttentionItem> = emptyList(),
    val door: DoorInfo? = null,
    val rooms: List<MkClimateRoom> = emptyList(),
    val kpis: List<KotiKpi> = emptyList(),
)

/**
 * Home screen. Room temperatures / heating demand / announcements are live
 * StateFlows; sauna, prices, HVAC, heat pump and air quality are on-demand
 * snapshots refreshed via [refresh]. Absent sources render "Ei tietoa" rather
 * than a fabricated number (the ThermIQ feed — COP / hot water — is dead).
 */
class KotiViewModel(
    private val climateRepo: ClimateRepository,
    private val energyRepo: EnergyRepository,
    private val saunaRepo: SaunaRepository,
    announcementsRepo: AnnouncementsRepository,
) : ViewModel() {

    /** On-demand snapshots that back the strip + KPIs. */
    private data class Snapshots(
        val loading: Boolean = true,
        val error: String? = null,
        val sauna: SaunaStatus? = null,
        val prices: ElectricityPrices? = null,
        val hvac: HvacSummary? = null,
        val air: AirQuality? = null,
    )

    private val snapshots = MutableStateFlow(Snapshots())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    val uiState: StateFlow<KotiUiState> = combine(
        climateRepo.roomTemperatures,
        climateRepo.heatingDemand,
        announcementsRepo.recent,
        snapshots,
        climateRepo.heatPump,
    ) { temps, demand, recent, snap, heatPump ->
        buildState(temps, demand, recent, snap, freshHeatPump(heatPump))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KotiUiState())

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
        // Room temperatures are a live MQTT StateFlow; stamp freshness whenever a
        // real reading lands, independently of the on-demand snapshot fetch.
        viewModelScope.launch {
            climateRepo.roomTemperatures.collect { temps ->
                if (temps.isNotEmpty()) _updatedAt.value = nowEpochSeconds()
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

    /** Re-pulls every on-demand snapshot concurrently; failures degrade quietly. */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                snapshots.update { it.copy(loading = true, error = null) }
                val snap = coroutineScope {
                    val sauna = async { runCatching { saunaRepo.status() }.getOrNull() }
                    val prices = async { runCatching { energyRepo.electricityPrices() }.getOrNull() }
                    val hvac = async { runCatching { climateRepo.hvacSummary() }.getOrNull() }
                    val air = async { runCatching { climateRepo.airQuality() }.getOrNull() }
                    Snapshots(
                        loading = false,
                        sauna = sauna.await(),
                        prices = prices.await(),
                        hvac = hvac.await(),
                        air = air.await(),
                    )
                }
                val allFailed = listOf(snap.sauna, snap.prices, snap.hvac, snap.air)
                    .all { it == null }
                snapshots.value = snap.copy(error = if (allFailed) "Tietojen haku epäonnistui" else null)
                if (!allFailed) _updatedAt.value = nowEpochSeconds()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private fun buildState(
        temps: List<RoomTemperature>,
        demand: List<HeatingDemand>,
        recent: List<Announcement>,
        snap: Snapshots,
        heatPump: HeatPumpStatus,
    ): KotiUiState = KotiUiState(
        loading = snap.loading,
        error = snap.error,
        attention = buildAttention(snap),
        door = buildDoor(recent),
        rooms = buildRooms(temps, demand, heatPump),
        kpis = buildKpis(snap, heatPump),
    )

    // ── Attention strip: only REAL abnormal conditions ────────────────────────

    private fun buildAttention(snap: Snapshots): List<AttentionItem> = buildList {
        snap.sauna?.let { s ->
            if (s.isHeating) {
                add(
                    AttentionItem(
                        status = "warn",
                        icon = MkIcons.FlameFill,
                        text = "Sauna päällä",
                        value = s.currentTempC?.let { "${Fmt.oneDecimal(it)} °C" } ?: "",
                    ),
                )
            }
        }
        snap.prices?.let { p ->
            if (p.isExpensiveNow) {
                add(
                    AttentionItem(
                        status = "warn",
                        icon = MkIcons.LightningFill,
                        text = "Sähkö kallista juuri nyt",
                        value = "${p.currentCentsPerKwh?.let { Fmt.oneDecimal(it) } ?: "–"} c",
                    ),
                )
            }
        }
        snap.hvac?.let { h ->
            if (h.freezingDanger) {
                add(
                    AttentionItem(
                        status = "alarm",
                        icon = MkIcons.Snowflake,
                        text = "Jäätymisvaara ilmanvaihdossa",
                        value = "",
                    ),
                )
            }
            // Avoid a redundant generic warn when freezing already raised an alarm.
            if (h.anyAlarm && !h.freezingDanger) {
                add(
                    AttentionItem(
                        status = "warn",
                        icon = MkIcons.Wind,
                        text = "Ilmanvaihto vaatii huomiota",
                        value = "",
                    ),
                )
            }
        }
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

    private fun buildKpis(snap: Snapshots, heatPump: HeatPumpStatus): List<KotiKpi> = listOf(
        electricityKpi(snap.prices),
        co2Kpi(snap.air),
        heatPumpKpi(heatPump, snap.hvac?.heatPumpPowerKw),
        hotWaterKpi(heatPump),
    )

    private fun electricityKpi(p: ElectricityPrices?): KotiKpi {
        val now = p?.currentCentsPerKwh
        val expensive = p?.isExpensiveNow == true
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
            statStatus = if (expensive) MkStatStatus.Warn else MkStatStatus.None,
            tag = if (expensive) "KALLIS" else null,
            tagStatus = MkTagStatus.Warn,
            detailStatus = if (expensive) "warn" else "accent",
            detailUnit = "c/kWh",
            seriesValues = p?.today?.map { it.centsPerKwh.toFloat() } ?: emptyList(),
            labels = priceLabels(p?.today ?: emptyList()),
            stats = stats,
        )
    }

    private fun co2Kpi(air: AirQuality?): KotiKpi {
        val co2 = air?.co2
        val loc = air?.location?.takeIf { it.isNotBlank() }
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
            // No CO₂ history source exists; the detail chart hides gracefully.
            seriesValues = emptyList(),
            labels = emptyList(),
            stats = emptyList(),
        )
    }

    /**
     * Running state and temperatures are live from the ThermIQ register feed;
     * the power draw (kW) is merged in from the pump's own energy meter, which
     * the `hvac` measurement carries independently.
     */
    private fun heatPumpKpi(hp: HeatPumpStatus, powerKw: Double?): KotiKpi {
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
            detailUnit = "",
            seriesValues = emptyList(),
            labels = emptyList(),
            stats = buildList {
                if (powerKw != null) add(MkStat("TEHO", "${Fmt.oneDecimal(powerKw)} kW"))
                hp.brineDeltaC?.let { add(MkStat("LIUOS Δ", "${Fmt.oneDecimal(it)} °C")) }
                hp.outdoorC?.let { add(MkStat("ULKO", "${Fmt.oneDecimal(it)} °C")) }
            },
        )
    }

    private fun hotWaterKpi(hp: HeatPumpStatus): KotiKpi {
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
            seriesValues = emptyList(),
            labels = emptyList(),
            stats = emptyList(),
        )
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

        /**
         * ThermIQ publishes roughly every 15–60 s; past this the feed is
         * considered dead and the heat-pump tiles fall back to "Ei tietoa".
         */
        const val HEAT_PUMP_STALE_SECONDS = 30L * 60L

        /**
         * TimeRangeOption → (Flux range, every) for [ClimateRepository.temperatureHistory].
         * Kept per the screen contract; none of the four KPIs above is a room
         * temperature, so no detail currently pulls history and this stays unused.
         */
        @Suppress("unused")
        fun fluxRange(range: TimeRangeOption): Pair<String, String> = when (range) {
            TimeRangeOption.H6 -> "-6h" to "15m"
            TimeRangeOption.H24 -> "-24h" to "30m"
            TimeRangeOption.D7 -> "-7d" to "3h"
            TimeRangeOption.D30 -> "-30d" to "12h"
            TimeRangeOption.Y1 -> "-365d" to "7d"
        }
    }
}
