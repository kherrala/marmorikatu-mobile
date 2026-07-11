package fi.marmorikatu.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.BarState
import fi.marmorikatu.app.components.MkPriceBar
import fi.marmorikatu.core.model.ElectricityPrices
import fi.marmorikatu.core.model.EnergyReading
import fi.marmorikatu.core.model.PriceTier
import fi.marmorikatu.core.repository.EnergyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
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
) : ViewModel() {

    /** Live meter power / cumulative readings, keyed `heatpump` | `extra`. */
    val liveEnergy: StateFlow<Map<String, EnergyReading>> = energyRepo.liveEnergy

    private val _prices = MutableStateFlow<PriceState>(PriceState.Loading)
    val prices: StateFlow<PriceState> = _prices.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    /** Estimated consumption by component (kWh), largest first. Null until loaded. */
    private val _consumption = MutableStateFlow<List<EnergyComponent>?>(null)
    val consumption: StateFlow<List<EnergyComponent>?> = _consumption.asStateFlow()

    /**
     * Backend's own `total_kwh` for the window (design: "Kulutus tänään"). Can
     * exceed the sum of [consumption]'s named components, since it also covers
     * load that isn't attributed to any of them. Null until loaded.
     */
    private val _totalConsumptionKwh = MutableStateFlow<Double?>(null)
    val totalConsumptionKwh: StateFlow<Double?> = _totalConsumptionKwh.asStateFlow()

    /** Pull today's spot curve. The screen also re-invokes this every 5 min. */
    @OptIn(ExperimentalTime::class)
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                launch {
                    runCatching { energyRepo.energyConsumption() }
                        .getOrNull()
                        ?.let { obj ->
                            _consumption.value = parseConsumption(obj)
                            _totalConsumptionKwh.value = (obj["total_kwh"] as? JsonPrimitive)?.doubleOrNull
                        }
                }
                runCatching { energyRepo.electricityPrices() }
                    .onSuccess { prices ->
                        // Authoritative band from the backend optimizer; fall back
                        // to a local clamped-percentile classification when
                        // InfluxDB is unreachable.
                        val tier = runCatching { energyRepo.priceTier() }.getOrNull()
                        _prices.value = PriceState.Ready(prices.toModel(tier))
                        _updatedAt.value = Clock.System.now().epochSeconds
                    }
                    .onFailure {
                        // Keep any prior data on a transient failure; only flag error
                        // when we have nothing to show yet.
                        if (_prices.value !is PriceState.Ready) _prices.value = PriceState.Failed
                    }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Fold the MCP `get_energy_consumption` breakdown into display components. */
    private fun parseConsumption(obj: JsonObject): List<EnergyComponent> {
        fun kwh(key: String) = (obj[key] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        return listOf(
            EnergyComponent("Maalämpö", kwh("heat_pump_compressor_kwh") + kwh("heat_pump_aux_heaters_kwh")),
            EnergyComponent("Valaistus", kwh("lighting_kwh")),
            EnergyComponent("Sauna", kwh("sauna_kwh")),
            EnergyComponent("Ilmanvaihto", kwh("hvac_fan_kwh")),
        ).filter { it.kwh > 0.0 }.sortedByDescending { it.kwh }
    }

    @OptIn(ExperimentalTime::class)
    private fun ElectricityPrices.toModel(tier: PriceTier?): PriceModel {
        val nowHour = resolveCurrentHour()
        val bars = today.map { sp ->
            val hour = hourOf(sp.time)
            val state = when {
                hour != null && hour < nowHour -> BarState.Past
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
