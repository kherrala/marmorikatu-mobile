package fi.marmorikatu.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.TimeRangeOption
import fi.marmorikatu.core.model.AirQuality
import fi.marmorikatu.core.model.Cooling
import fi.marmorikatu.core.model.HeatPumpStatus
import fi.marmorikatu.core.model.RuuviReading
import fi.marmorikatu.core.model.HeatingDemand
import fi.marmorikatu.core.model.HvacSummary
import fi.marmorikatu.core.model.RoomTemperature
import fi.marmorikatu.core.model.Ventilation
import fi.marmorikatu.core.repository.ClimateRepository
import fi.marmorikatu.core.transport.influx.FluxPoint
import kotlinx.coroutines.Job
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
    val cooling: StateFlow<Cooling> = climate.cooling
    val heatPump: StateFlow<HeatPumpStatus> = climate.heatPump
    val ruuvi: StateFlow<Map<String, RuuviReading>> = climate.ruuvi

    private val _snapshot = MutableStateFlow(IlmastoSnapshot())
    val snapshot: StateFlow<IlmastoSnapshot> = _snapshot.asStateFlow()

    /** Compressor duty over the last 24 h (%), for the Maalämpö "Käyntiaika" tile. */
    private val _heatPumpDuty = MutableStateFlow<Double?>(null)
    val heatPumpDuty: StateFlow<Double?> = _heatPumpDuty.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    // Focus view: a single readout's 24 h history, loaded on demand when a tile
    // is tapped.
    private val _focusSeries = MutableStateFlow<List<Float>>(emptyList())
    val focusSeries: StateFlow<List<Float>> = _focusSeries.asStateFlow()

    private val _focusLoading = MutableStateFlow(false)
    val focusLoading: StateFlow<Boolean> = _focusLoading.asStateFlow()

    // Section-list scroll position, kept in the VM so it survives the composition
    // being recreated while a focus chart forces landscape — otherwise returning
    // from the chart would snap the list back to the top. Same rationale as [focus].
    var listScrollIndex: Int = 0
    var listScrollOffset: Int = 0

    // The focused readout lives here (not in the composable) so it survives the
    // phone turning to landscape for the chart — see KotiViewModel for why.
    private val _focus = MutableStateFlow<FocusMetric?>(null)
    val focus: StateFlow<FocusMetric?> = _focus.asStateFlow()

    /** The focus chart's selected time window, shared across focused readouts. */
    private val _focusRange = MutableStateFlow(TimeRangeOption.H24)
    val focusRange: StateFlow<TimeRangeOption> = _focusRange.asStateFlow()

    fun openFocus(metric: FocusMetric) {
        _focus.value = metric
        loadFocus(metric)
    }

    fun closeFocus() {
        _focus.value = null
        clearFocus()
    }

    /** Switch the focus chart's window and reload the open readout's history. */
    fun setFocusRange(range: TimeRangeOption) {
        if (range == _focusRange.value) return
        _focusRange.value = range
        _focus.value?.let { loadFocus(it) }
    }

    // The in-flight history query. Cancelled before each new load (and on clear)
    // so a slow response can never overwrite a newer metric/range's series.
    private var focusJob: Job? = null

    /** Load a readout's InfluxDB history for the focus chart at [focusRange]. */
    private fun loadFocus(metric: FocusMetric) {
        focusJob?.cancel()
        _focusSeries.value = emptyList()
        _focusLoading.value = true
        focusJob = viewModelScope.launch {
            // Fine-grained windows (same as the H24 chart) so the focus line is
            // smooth, not blocky. "hvac_lto" is a computed efficiency series.
            val (flux, every) = _focusRange.value.fluxWindow()
            val series = if (metric.measurement == "hvac_lto") {
                climate.recoveryEfficiencyHistory(flux, every)
            } else {
                climate.metricHistory(metric.measurement, metric.field, flux, every, metric.tagKey, metric.tagValue)
            }
            // Unit scaling (e.g. the Ruuvi pressure is stored in Pa, shown in hPa).
            _focusSeries.value = if (metric.scale == 1f) series else series.map { it * metric.scale }
            _focusLoading.value = false
        }
    }

    fun clearFocus() {
        focusJob?.cancel()
        _focusSeries.value = emptyList()
        _focusLoading.value = false
    }

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
                    launch { loadHeatPumpDuty() }
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

    /**
     * Silent background refresh of the on-demand InfluxDB summaries — the duct
     * temps (Tuloilmakanava), cooling and efficiency don't ride the live MQTT
     * flows, so they'd otherwise stay frozen at whatever they were when the tab
     * opened. Called on a short cadence while the screen is visible.
     */
    fun poll() {
        viewModelScope.launch {
            runCatching { climate.requestHeatPumpRead() }
            loadSummaries(silent = true)
            loadHeatPumpDuty()
            if (!_snapshot.value.failed) _updatedAt.value = nowEpochSeconds()
        }
    }

    private suspend fun loadSummaries(silent: Boolean = false) {
        if (!silent) _snapshot.update { it.copy(loading = true) }
        val hvac = runCatching { climate.hvacSummary() }.getOrNull()
        val air = runCatching { climate.airQuality() }.getOrNull()
        _snapshot.update {
            it.copy(
                hvac = hvac ?: it.hvac,
                air = air ?: it.air,
                loading = false,
                failed = !silent && hvac == null && air == null,
            )
        }
    }

    /**
     * Compressor duty cycle over 24 h: the mean of the 0/1 `thermia/compressor`
     * status bit (downsampled to 30-min bucket means), as a percentage. An empty
     * series leaves it null so the tile reads "Ei tietoa".
     */
    private suspend fun loadHeatPumpDuty() {
        val series = runCatching { climate.metricHistory("thermia", "compressor", "-24h", "30m") }
            .getOrDefault(emptyList())
        _heatPumpDuty.value = if (series.isEmpty()) null else series.average() * 100.0
    }

    private suspend fun loadHistory(range: TimeRangeOption) {
        val (flux, every) = range.fluxWindow()
        val history = runCatching { climate.temperatureHistory(flux, every) }
            .getOrElse { emptyMap() }
        _snapshot.update { it.copy(history = history) }
    }
}

/** Maps a UI time range onto a Flux `range` / `every` pair. */
// Aggregation window per range, tuned for smooth charts (~150–300 points).
// FluxClient reads InfluxDB directly (no 100-row cap), so a fine grain is cheap.
// Internal: the Energia focus charts share the same windows.
internal fun TimeRangeOption.fluxWindow(): Pair<String, String> = when (this) {
    TimeRangeOption.H6 -> "-6h" to "2m"     // 180 pts
    TimeRangeOption.H24 -> "-24h" to "5m"   // 288 pts
    TimeRangeOption.D7 -> "-7d" to "1h"     // 168 pts
    TimeRangeOption.D30 -> "-30d" to "4h"   // 180 pts
    TimeRangeOption.Y1 -> "-1y" to "1d"     // 365 pts
}
