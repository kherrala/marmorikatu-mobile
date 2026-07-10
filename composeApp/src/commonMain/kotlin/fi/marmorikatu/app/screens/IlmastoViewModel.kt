package fi.marmorikatu.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.TimeRangeOption
import fi.marmorikatu.core.model.AirQuality
import fi.marmorikatu.core.model.HeatPumpStatus
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.HvacSummary
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Ventilation
import fi.marmorikatu.core.repository.ClimateRepository
import fi.marmorikatu.core.transport.influx.FluxPoint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * State the Ilmasto screen renders. The three PLC StateFlows are surfaced
 * directly; the on-demand HVAC / air-quality snapshots and the temperature
 * history live in [snapshot], re-queried when the time range changes.
 */
data class IlmastoSnapshot(
    val hvac: HvacSummary? = null,
    val air: AirQuality? = null,
    val history: Map<String, List<FluxPoint>> = emptyMap(),
    val range: TimeRangeOption = TimeRangeOption.H24,
    val loading: Boolean = true,
    /** True once a load completed and produced no HVAC/air data at all. */
    val failed: Boolean = false,
)

/** Backs the Ilmasto (climate) screen: rooms, ventilation, air quality, history. */
class IlmastoViewModel(
    private val climate: ClimateRepository,
) : ViewModel() {

    val roomTemperatures: StateFlow<List<RoomTemperature>> = climate.roomTemperatures
    val heatingDemand: StateFlow<List<HeatingDemand>> = climate.heatingDemand
    val ventilation: StateFlow<Ventilation> = climate.ventilation
    val heatPump: StateFlow<HeatPumpStatus> = climate.heatPump

    private val _snapshot = MutableStateFlow(IlmastoSnapshot())
    val snapshot: StateFlow<IlmastoSnapshot> = _snapshot.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    init {
        // Room temperatures are a live PLC StateFlow; stamp freshness whenever a
        // real reading lands, independently of the on-demand summary fetch.
        viewModelScope.launch {
            climate.roomTemperatures.collect { temps ->
                if (temps.isNotEmpty()) _updatedAt.value = nowEpochSeconds()
            }
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds

    /** Reload the summaries and history (called from a LaunchedEffect). */
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                // ThermIQ isn't retained; a refresh pulls fresh heat-pump registers.
                launch { runCatching { climate.requestHeatPumpRead() } }
                coroutineScope {
                    launch { loadSummaries() }
                    launch { loadHistory(_snapshot.value.range) }
                }
                if (!_snapshot.value.failed) _updatedAt.value = nowEpochSeconds()
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Re-query the temperature history for a new window. */
    fun selectRange(range: TimeRangeOption) {
        if (range == _snapshot.value.range) return
        _snapshot.update { it.copy(range = range) }
        viewModelScope.launch { loadHistory(range) }
    }

    private suspend fun loadSummaries() {
        _snapshot.update { it.copy(loading = true) }
        val hvac = runCatching { climate.hvacSummary() }.getOrNull()
        val air = runCatching { climate.airQuality() }.getOrNull()
        _snapshot.update {
            it.copy(
                hvac = hvac,
                air = air,
                loading = false,
                failed = hvac == null && air == null,
            )
        }
    }

    private suspend fun loadHistory(range: TimeRangeOption) {
        val (flux, every) = range.fluxWindow()
        val history = runCatching { climate.temperatureHistory(flux, every) }
            .getOrElse { emptyMap() }
        _snapshot.update { it.copy(history = history) }
    }
}

/** Maps a UI time range onto a Flux `range` / `every` pair. */
private fun TimeRangeOption.fluxWindow(): Pair<String, String> = when (this) {
    TimeRangeOption.H6 -> "-6h" to "5m"
    TimeRangeOption.H24 -> "-24h" to "30m"
    TimeRangeOption.D7 -> "-7d" to "3h"
    TimeRangeOption.D30 -> "-30d" to "12h"
    TimeRangeOption.Y1 -> "-1y" to "1w"
}
