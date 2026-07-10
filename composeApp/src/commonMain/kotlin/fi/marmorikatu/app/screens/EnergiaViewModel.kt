package fi.marmorikatu.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.BarState
import fi.marmorikatu.app.components.MkPriceBar
import fi.marmorikatu.core.model.ElectricityPrices
import fi.marmorikatu.core.model.EnergyReading
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

    /** Pull today's spot curve. The screen also re-invokes this every 5 min. */
    @OptIn(ExperimentalTime::class)
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                launch {
                    runCatching { energyRepo.energyConsumption() }
                        .getOrNull()
                        ?.let { _consumption.value = parseConsumption(it) }
                }
                runCatching { energyRepo.electricityPrices() }
                    .onSuccess {
                        _prices.value = PriceState.Ready(it.toModel())
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
    private fun ElectricityPrices.toModel(): PriceModel {
        val threshold = expensiveThreshold
        val nowHour = resolveCurrentHour()
        val bars = today.map { sp ->
            val hour = hourOf(sp.time)
            val state = when {
                hour != null && hour < nowHour -> BarState.Past
                threshold != null && sp.centsPerKwh >= threshold -> BarState.Exp
                else -> BarState.Future
            }
            MkPriceBar(sp.centsPerKwh.toFloat(), state)
        }
        return PriceModel(
            bars = bars,
            currentCents = currentCentsPerKwh,
            isExpensiveNow = isExpensiveNow,
            minCents = minCentsPerKwh,
            maxCents = maxCentsPerKwh,
            avgCents = avgCentsPerKwh,
        )
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
    val isExpensiveNow: Boolean,
    val minCents: Double?,
    val maxCents: Double?,
    val avgCents: Double?,
)
