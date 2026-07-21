package fi.marmorikatu.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.BarState
import fi.marmorikatu.app.components.MkPriceBar
import fi.marmorikatu.app.components.TimeRangeOption
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.core.model.ElectricityPrices
import fi.marmorikatu.core.model.EnergyReading
import fi.marmorikatu.core.model.PriceTier
import fi.marmorikatu.core.repository.ClimateRepository
import fi.marmorikatu.core.repository.EnergyBreakdown
import fi.marmorikatu.core.repository.EnergyRepository
import fi.marmorikatu.core.repository.LightsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Energia-näyttö: pörssisähkön hintakäyrä ja mittareiden reaaliaikainen teho.
 * Hinnat haetaan MCP:stä (refresh); mittariteho tulee live-virtana repositorystä.
 */
class EnergiaViewModel(
    private val energyRepo: EnergyRepository,
    private val lightsRepo: LightsRepository,
    private val climateRepo: ClimateRepository,
) : ViewModel() {

    // ── Focus view: a tapped readout's full-page history chart ──────────────
    // Same shape as IlmastoViewModel's focus so the two tabs behave alike; the
    // selection lives in the VM so it survives the landscape recreation a phone
    // detail forces.

    private val _focus = MutableStateFlow<FocusMetric?>(null)
    val focus: StateFlow<FocusMetric?> = _focus.asStateFlow()

    private val _focusSeries = MutableStateFlow<List<Float>>(emptyList())
    val focusSeries: StateFlow<List<Float>> = _focusSeries.asStateFlow()

    private val _focusLoading = MutableStateFlow(false)
    val focusLoading: StateFlow<Boolean> = _focusLoading.asStateFlow()

    private val _focusRange = MutableStateFlow(TimeRangeOption.H24)
    val focusRange: StateFlow<TimeRangeOption> = _focusRange.asStateFlow()

    fun openFocus(metric: FocusMetric) {
        _focus.value = metric
        loadFocus(metric)
    }

    fun closeFocus() {
        focusJob?.cancel()
        _focus.value = null
        _focusSeries.value = emptyList()
        _focusLoading.value = false
    }

    fun setFocusRange(range: TimeRangeOption) {
        if (range == _focusRange.value) return
        _focusRange.value = range
        _focus.value?.let { loadFocus(it) }
    }

    // The in-flight history query. Cancelled before each new load (and on close)
    // so a slow response can never overwrite a newer metric/range's series.
    private var focusJob: Job? = null

    // Section-list scroll position, kept in the VM so it survives the forced-
    // landscape recreation a phone focus chart triggers — see IlmastoViewModel.
    var listScrollIndex: Int = 0
    var listScrollOffset: Int = 0

    private fun loadFocus(metric: FocusMetric) {
        // The Kulutus/Kustannus detail renders the already-loaded cost trend —
        // there is no single InfluxDB series behind it to query.
        if (metric.measurement == KULUTUS_TREND) return
        focusJob?.cancel()
        _focusSeries.value = emptyList()
        _focusLoading.value = true
        focusJob = viewModelScope.launch {
            val (flux, every) = _focusRange.value.fluxWindow()
            val series = climateRepo.metricHistory(
                metric.measurement, metric.field, flux, every, metric.tagKey, metric.tagValue,
            )
            _focusSeries.value = if (metric.scale == 1f) series else series.map { it * metric.scale }
            _focusLoading.value = false
        }
    }

    companion object {
        /**
         * Sentinel [FocusMetric.measurement] for the Kulutus/Kustannus tiles:
         * their detail charts the in-memory [cost] trend instead of InfluxDB.
         */
        const val KULUTUS_TREND = "kulutus_trend"

        /** Per-source fetch ceiling so a hung backend/InfluxDB call can't freeze a card on "loading". */
        private const val FETCH_TIMEOUT_MS = 8_000L
    }

    /** InfluxDB snapshot that seeds the meter card before the live MQTT topics arrive. */
    private val _meterSnapshot = MutableStateFlow<Map<String, EnergyReading>>(emptyMap())

    /**
     * Live meter readings, keyed `heatpump` | `extra`. The MQTT stream is
     * authoritative; the InfluxDB snapshot backfills each meter so the card shows
     * values immediately on cold start, before the retained topics land.
     */
    val liveEnergy: StateFlow<Map<String, EnergyReading>> =
        combine(energyRepo.liveEnergy, _meterSnapshot) { live, snap -> snap + live }
            .stateIn(viewModelScope, SharingStarted.Eagerly, energyRepo.liveEnergy.value)

    private val _prices = MutableStateFlow<PriceState>(PriceState.Loading)
    val prices: StateFlow<PriceState> = _prices.asStateFlow()

    /** Heating optimization: the price-tier forecast + heat-pump bias. Null until loaded. */
    private val _heatingOpti = MutableStateFlow<HeatingOpti?>(null)
    val heatingOpti: StateFlow<HeatingOpti?> = _heatingOpti.asStateFlow()

    // True once a refresh has completed, so the card can tell "still loading" from
    // "loaded, but no prices to optimize" (null opti) and stop spinning forever.
    private val _heatingOptiLoaded = MutableStateFlow(false)
    val heatingOptiLoaded: StateFlow<Boolean> = _heatingOptiLoaded.asStateFlow()

    /** Light usage: on/total, kWh, per-floor on-time, automation counts. Null until loaded. */
    private val _lightUsage = MutableStateFlow<LightUsage?>(null)
    val lightUsage: StateFlow<LightUsage?> = _lightUsage.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    /** The selected cost/consumption window (design: 24h / 7d / 30d / 12kk tabs). */
    private val _range = MutableStateFlow(EnergyRange.H24)
    val range: StateFlow<EnergyRange> = _range.asStateFlow()

    /** Cost + consumption for [range]: euros, average, extremes, consumers, trend. Null until loaded. */
    private val _cost = MutableStateFlow<CostView?>(null)
    val cost: StateFlow<CostView?> = _cost.asStateFlow()

    /** True while the cost/consumption for a range is being fetched (the backend call is slow on long windows). */
    private val _costLoading = MutableStateFlow(false)
    val costLoading: StateFlow<Boolean> = _costLoading.asStateFlow()

    /** Today's spot curve, cached so a range change can label peak/cheap without re-fetching prices. */
    private var latestPrices: ElectricityPrices? = null

    /** Pull today's spot curve. The screen also re-invokes this every 5 min. */
    @OptIn(ExperimentalTime::class)
    /**
     * Runs a repository fetch with a ceiling so a hung backend call (e.g. an
     * InfluxDB query that never returns) can't stall the whole refresh's
     * [coroutineScope] and leave the cards spinning "loading" forever — it just
     * degrades that one source to null.
     */
    private suspend fun <T> timed(block: suspend () -> T): T? =
        withTimeoutOrNull(FETCH_TIMEOUT_MS) { runCatching { block() }.getOrNull() }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                coroutineScope {
                    // Fetch the independent sources concurrently, then fold them into
                    // the price card, the heating-optimization card, and the light-usage
                    // card. Each source degrades on its own — a missing one just leaves
                    // its part of a card empty.
                    val pricesD = async { timed { energyRepo.electricityPrices() } }
                    val tierD = async { timed { energyRepo.priceTier() } }
                    val optimizerD = async { timed { energyRepo.heatingOptimizer() } }
                    val onTimeD = async { timed { energyRepo.lightOnTimeByFloor() } }
                    val autoOffD = async { timed { energyRepo.lightAutoOffCounts() } }
                    // The light card's "kWh tänään" is always today's lighting, kept
                    // independent of the Kulutus range selector.
                    val lightingKwhD = async { timed { energyRepo.lightingKwh("-24h") } }
                    // Seed the meter card from InfluxDB so it isn't blank until MQTT connects.
                    val snapshotD = async { timed { energyRepo.latestMeterSnapshot() } }

                    snapshotD.await()?.takeIf { it.isNotEmpty() }?.let { _meterSnapshot.value = it }

                    val prices = pricesD.await()
                    if (prices != null) {
                        // Authoritative band from the backend optimizer; fall back to a
                        // local clamped-percentile classification when InfluxDB is down.
                        latestPrices = prices
                        _prices.value = PriceState.Ready(prices.toModel(tierD.await()))
                        _updatedAt.value = Clock.System.now().epochSeconds
                    } else if (_prices.value !is PriceState.Ready) {
                        // Keep prior data on a transient failure; only flag when we have none.
                        _prices.value = PriceState.Failed
                    }
                    // Rebuild the heating-optimization card from the freshest prices
                    // we have (this fetch, else the last good one). When there are no
                    // prices at all it stays null, but the card now reads that as
                    // "Ei tietoa" (see [heatingOptiLoaded]) rather than spinning
                    // "loading" forever.
                    (prices ?: latestPrices)?.let {
                        _heatingOpti.value = buildHeatingOpti(it, optimizerD.await() ?: emptyMap())
                    }
                    _heatingOptiLoaded.value = true

                    _lightUsage.value = buildLightUsage(
                        lights = lightsRepo.lights.value,
                        lightingKwh = lightingKwhD.await(),
                        onTimeByFloor = onTimeD.await() ?: emptyMap(),
                        autoOffCounts = autoOffD.await() ?: emptyMap(),
                    )
                }
                // Cost + trend for the current window; factored out so a range switch
                // reloads just this part instead of the whole screen.
                loadCost(_range.value)
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Switch the Kulutus/cost window and reload just its cost + trend. */
    fun setRange(range: EnergyRange) {
        if (range == _range.value && _cost.value != null) return
        _range.value = range
        viewModelScope.launch { loadCost(range) }
    }

    private suspend fun loadCost(range: EnergyRange) {
        _costLoading.value = true
        try {
            coroutineScope {
                val breakdownD = async { timed { energyRepo.energyBreakdown(range.flux) } }
                val trendD = async { timed { energyRepo.energyCostTrend(range.flux, range.every) } }
                val built = buildCost(range, breakdownD.await(), latestPrices, trendD.await() ?: emptyList())
                // Guard against a stale write if the user tapped another range mid-fetch.
                if (_range.value == range) _cost.value = built
            }
        } finally {
            if (_range.value == range) _costLoading.value = false
        }
    }

    /** Fold the local [EnergyBreakdown] + the derived kWh trend into the [CostView]. */
    private fun buildCost(
        range: EnergyRange,
        breakdown: EnergyBreakdown?,
        prices: ElectricityPrices?,
        trend: List<Double>,
    ): CostView {
        val consumers = breakdown?.let {
            listOf(
                EnergyComponent("Maalämpö", it.heatPumpKwh),
                EnergyComponent("Sauna", it.saunaKwh),
                EnergyComponent("Valaistus", it.lightingKwh),
                EnergyComponent("Ilmanvaihto", it.fanKwh),
            ).filter { c -> c.kwh > 0.0 }.sortedByDescending { c -> c.kwh }
        }.orEmpty()
        val total = breakdown?.totalKwh
        // Peak/cheap only for today — the longer windows have no per-hour price curve.
        val (peak, cheap) = if (range == EnergyRange.H24) priceExtremes(prices?.today.orEmpty()) else null to null
        return CostView(
            window = range.window,
            kwh = total?.let { if (it >= 100) Fmt.int(it) else Fmt.oneDecimal(it) },
            cost = breakdown?.estimatedEur?.let { Fmt.comma(it, 2) },
            avg = breakdown?.avgSpotCents?.let { Fmt.oneDecimal(it) },
            peak = peak,
            cheap = cheap,
            consumers = consumers,
            trend = trend.map { it.toFloat() },
        )
    }

    @OptIn(ExperimentalTime::class)
    private fun ElectricityPrices.toModel(tier: PriceTier?): PriceModel {
        // Render from a combined today+tomorrow timeline keyed on each slot's real
        // instant, windowed to the current local day forward. Anchoring the window
        // to the device clock — not the backend's day split — is what lets the user
        // "see prices past midnight": in the hours just after midnight the backend
        // still reports the previous calendar day as `today` (its boundary is UTC,
        // so 00–03 Helsinki lands in `tomorrow`), yet the chart shows the real
        // current day; and once tomorrow's prices publish (~14:00) the curve carries
        // straight on past midnight into them.
        val now = Clock.System.now()
        val combined = (today + tomorrow)
            .mapNotNull { sp -> runCatching { Instant.parse(sp.time) }.getOrNull()?.let { it to sp.centsPerKwh } }
            .associate { it }                 // dedup by instant; a later array wins ties
            .toList()
            .sortedBy { it.first }
        if (combined.isEmpty()) {
            return PriceModel(emptyList(), currentCentsPerKwh, tier ?: currentTierFallback,
                minCentsPerKwh, maxCentsPerKwh, avgCentsPerKwh, cheapestWindow(), PRICE_AXIS_DAY, false)
        }
        val startOfDay = now.toLocalDateTime(chartZone).date.atStartOfDayIn(chartZone)
        val windowed = combined.filter { it.first >= startOfDay }.ifEmpty { combined }

        // A day is 96 quarter-hours; once tomorrow extends the window past one day,
        // collapse to hourly averages so ~2 days still fit legibly on a phone.
        val hourly = windowed.size > 120
        val points = if (hourly) {
            windowed.groupBy { hourStart(it.first) }
                .map { (h, v) -> h to v.map { it.second }.average() }
                .sortedBy { it.first }
        } else {
            windowed
        }
        val slotSpan = if (hourly) 3_600L else 900L

        val bars = points.map { (start, cents) ->
            val state = when {
                start.epochSeconds + slotSpan <= now.epochSeconds -> BarState.Past
                tierOf(cents) == PriceTier.Expensive -> BarState.Exp
                tierOf(cents) == PriceTier.Cheap -> BarState.Cheap
                else -> BarState.Future
            }
            MkPriceBar(cents.toFloat(), state)
        }

        // The price of the slot containing "now" — the honest readout even when the
        // backend's `current_price` is stale across the midnight boundary.
        val nowCents = points.lastOrNull { it.first <= now }?.second ?: currentCentsPerKwh
        val spansTomorrow =
            points.last().first.toLocalDateTime(chartZone).date != now.toLocalDateTime(chartZone).date
        return PriceModel(
            bars = bars,
            currentCents = nowCents,
            // Backend tier wins; local classification of the live slot is the fallback.
            nowTier = tier ?: nowCents?.let { tierOf(it) },
            minCents = minCentsPerKwh,
            maxCents = maxCentsPerKwh,
            avgCents = avgCentsPerKwh,
            cheapestWindow = cheapestWindow(),
            axisLabels = axisLabels(points, slotSpan),
            spansTomorrow = spansTomorrow,
        )
    }

    /**
     * Five hour ticks spread evenly across the window's *time* span (not its bar
     * index, which lands on ragged hours like 05/11/17 for a 96-slot day). A tick
     * on a midnight boundary reads 24 rather than 00 so the day's end is clear.
     */
    @OptIn(ExperimentalTime::class)
    private fun axisLabels(points: List<Pair<Instant, Double>>, slotSpan: Long): List<String> {
        if (points.isEmpty()) return PRICE_AXIS_DAY
        val startSec = points.first().first.epochSeconds
        val endSec = points.last().first.epochSeconds + slotSpan
        return (0..4).map { k ->
            val sec = startSec + (endSec - startSec) * k / 4
            val h = Instant.fromEpochSeconds(sec).toLocalDateTime(chartZone).hour
            if (k == 4 && h == 0) "24" else h.toString().padStart(2, '0')
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun hourStart(i: Instant): Instant = Instant.fromEpochSeconds((i.epochSeconds / 3600) * 3600)

    /** The Finnish spot-price day is Helsinki-based, whatever the device's own zone. */
    private val chartZone = TimeZone.of("Europe/Helsinki")

    /**
     * Cheapest contiguous [windowHours]-hour block in today's spot curve, for the
     * "Halvimmat tunnit tänään" tip. Requires near-full-day coverage (at least 20
     * distinct hours with data) so a partial fetch can't surface a misleading
     * window — real, not fabricated: derived straight from today's own prices.
     */
    @OptIn(ExperimentalTime::class)
    private fun ElectricityPrices.cheapestWindow(windowHours: Int = 3): CheapWindow? {
        val byHour = today.mapNotNull { sp -> hourOf(sp.time)?.let { it to sp.centsPerKwh } }
            .groupBy({ it.first }, { it.second })
        if (byHour.size < 20) return null
        var best: CheapWindow? = null
        var bestAvg = Double.MAX_VALUE
        for (start in 0..24 - windowHours) {
            val values = (start until start + windowHours).flatMap { byHour[it].orEmpty() }
            if (values.size < windowHours) continue
            val avg = values.average()
            if (avg < bestAvg) {
                bestAvg = avg
                best = CheapWindow(start, start + windowHours, values.min(), values.max())
            }
        }
        return best
    }

    @OptIn(ExperimentalTime::class)
    private fun ElectricityPrices.resolveCurrentHour(): Int {
        currentHour?.let { raw ->
            val trimmed = raw.trim()
            trimmed.toIntOrNull()?.let { return it }
            runCatching { Instant.parse(trimmed).toLocalDateTime(zone).hour }.getOrNull()?.let { return it }
            trimmed.substringBefore(':').toIntOrNull()?.let { return it }
        }
        return Clock.System.now().toLocalDateTime(zone).hour
    }

    @OptIn(ExperimentalTime::class)
    private fun hourOf(iso: String): Int? =
        runCatching { Instant.parse(iso).toLocalDateTime(zone).hour }.getOrNull()

    private val zone get() = TimeZone.currentSystemDefault()
}

/** One estimated consumer for the "Kulutus laitteittain" list. */
data class EnergyComponent(val name: String, val kwh: Double)

/** Loading / ready / failed for the spot-price fetch. */
sealed interface PriceState {
    data object Loading : PriceState
    data object Failed : PriceState
    data class Ready(val model: PriceModel) : PriceState
}

/** Default single-day hour ticks, used before any prices arrive. */
val PRICE_AXIS_DAY = listOf("00", "06", "12", "18", "24")

/** Everything the price card renders, pre-computed from [ElectricityPrices]. */
data class PriceModel(
    val bars: List<MkPriceBar>,
    val currentCents: Double?,
    val nowTier: PriceTier?,
    val minCents: Double?,
    val maxCents: Double?,
    val avgCents: Double?,
    val cheapestWindow: CheapWindow?,
    /** Hour ticks under the chart; five labels spanning the rendered window. */
    val axisLabels: List<String> = PRICE_AXIS_DAY,
    /** True when the window reaches into tomorrow, so the card drops "tänään". */
    val spansTomorrow: Boolean = false,
)

/** Cheapest contiguous hour block found in today's prices (the "Halvimmat tunnit" tip). */
data class CheapWindow(val startHour: Int, val endHour: Int, val minCents: Double, val maxCents: Double)
