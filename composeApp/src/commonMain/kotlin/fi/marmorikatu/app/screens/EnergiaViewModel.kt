package fi.marmorikatu.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.BarState
import fi.marmorikatu.app.components.MkPriceBar
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.core.model.ElectricityPrices
import fi.marmorikatu.core.model.EnergyReading
import fi.marmorikatu.core.model.PriceTier
import fi.marmorikatu.core.repository.EnergyBreakdown
import fi.marmorikatu.core.repository.EnergyRepository
import fi.marmorikatu.core.repository.LightsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
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
) : ViewModel() {

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
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                coroutineScope {
                    // Fetch the independent sources concurrently, then fold them into
                    // the price card, the heating-optimization card, and the light-usage
                    // card. Each source degrades on its own — a missing one just leaves
                    // its part of a card empty.
                    val pricesD = async { runCatching { energyRepo.electricityPrices() }.getOrNull() }
                    val tierD = async { runCatching { energyRepo.priceTier() }.getOrNull() }
                    val optimizerD = async { runCatching { energyRepo.heatingOptimizer() }.getOrNull() }
                    val onTimeD = async { runCatching { energyRepo.lightOnTimeByFloor() }.getOrNull() }
                    val autoOffD = async { runCatching { energyRepo.lightAutoOffCounts() }.getOrNull() }
                    // The light card's "kWh tänään" is always today's lighting, kept
                    // independent of the Kulutus range selector.
                    val lightingKwhD = async { runCatching { energyRepo.lightingKwh("-24h") }.getOrNull() }
                    // Seed the meter card from InfluxDB so it isn't blank until MQTT connects.
                    val snapshotD = async { runCatching { energyRepo.latestMeterSnapshot() }.getOrNull() }

                    snapshotD.await()?.takeIf { it.isNotEmpty() }?.let { _meterSnapshot.value = it }

                    val prices = pricesD.await()
                    if (prices != null) {
                        // Authoritative band from the backend optimizer; fall back to a
                        // local clamped-percentile classification when InfluxDB is down.
                        latestPrices = prices
                        _prices.value = PriceState.Ready(prices.toModel(tierD.await()))
                        _updatedAt.value = Clock.System.now().epochSeconds
                        _heatingOpti.value = buildHeatingOpti(prices, optimizerD.await() ?: emptyMap())
                    } else if (_prices.value !is PriceState.Ready) {
                        // Keep prior data on a transient failure; only flag when we have none.
                        _prices.value = PriceState.Failed
                    }

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
                val breakdownD = async { runCatching { energyRepo.energyBreakdown(range.flux) }.getOrNull() }
                val trendD = async { runCatching { energyRepo.energyCostTrend(range.flux, range.every) }.getOrNull() }
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
        // Past detection on the full slot timestamp, not the hour-of-day: the old
        // `hour < nowHour` mislabeled the current hour's elapsed slots and — worse
        // — would paint a whole stale (yesterday's) curve as "future". Now every
        // slot before the one containing "now" is Past, so stale data shows up as
        // an all-grey chart instead of hiding.
        val now = Clock.System.now()
        val starts = today.map { runCatching { Instant.parse(it.time) }.getOrNull() }
        val currentStart = starts.filterNotNull().filter { it <= now }.maxOrNull()
        val bars = today.mapIndexed { i, sp ->
            val start = starts[i]
            val state = when {
                start != null && currentStart != null && start < currentStart -> BarState.Past
                tierOf(sp.centsPerKwh) == PriceTier.Expensive -> BarState.Exp
                tierOf(sp.centsPerKwh) == PriceTier.Cheap -> BarState.Cheap
                else -> BarState.Future
            }
            MkPriceBar(sp.centsPerKwh.toFloat(), state)
        }
        return PriceModel(
            bars = bars,
            currentCents = currentCentsPerKwh,
            // Backend tier wins; local classification is the offline fallback.
            nowTier = tier ?: currentTierFallback,
            minCents = minCentsPerKwh,
            maxCents = maxCentsPerKwh,
            avgCents = avgCentsPerKwh,
            cheapestWindow = cheapestWindow(),
        )
    }

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

/** Everything the price card renders, pre-computed from [ElectricityPrices]. */
data class PriceModel(
    val bars: List<MkPriceBar>,
    val currentCents: Double?,
    val nowTier: PriceTier?,
    val minCents: Double?,
    val maxCents: Double?,
    val avgCents: Double?,
    val cheapestWindow: CheapWindow?,
)

/** Cheapest contiguous hour block found in today's prices (the "Halvimmat tunnit" tip). */
data class CheapWindow(val startHour: Int, val endHour: Int, val minCents: Double, val maxCents: Double)
