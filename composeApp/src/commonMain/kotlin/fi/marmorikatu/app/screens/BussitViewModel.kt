package fi.marmorikatu.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.format.Fmt
import fi.marmorikatu.core.model.BusDeparture
import fi.marmorikatu.core.repository.InfoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Loading / failed / loaded, plus the sorted, still-relevant departures. */
data class BussitUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val departures: List<BusDeparture> = emptyList(),
    /**
     * The departure to promote to the big card: the soonest bus you can still
     * reach at a walking pace (its leave-by hasn't passed), not merely the next
     * one to arrive — a bus already too close to walk to belongs in the list,
     * not as a "catch me" headline. Null only when there are no departures.
     */
    val featured: BusDeparture? = null,
)

/**
 * Collects live Nysse departures from [InfoRepository] and keeps them fresh.
 *
 * The screen owns visibility: it calls [refresh] from a LaunchedEffect and the
 * 30 s auto-refresh loop lives in the same coroutine, so it stops when the
 * screen leaves composition.
 */
class BussitViewModel(
    private val infoRepo: InfoRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BussitUiState())
    val state: StateFlow<BussitUiState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()
    private var refreshJob: Job? = null

    @OptIn(ExperimentalTime::class)
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _refreshing.value = true
            try {
                val result = runCatching { infoRepo.busDepartures() }
                result.onSuccess { data ->
                    if (!data.ok) {
                        _state.value = _state.value.copy(loading = false, failed = true)
                        return@onSuccess
                    }
                    // Sort ascending; drop anything gone by more than a minute.
                    val live = data.departures
                        .filter { Fmt.minutesUntil(it.departureTimeMs) >= -1 }
                        .sortedBy { it.departureTimeMs }
                    // Promote the soonest bus you can still catch on foot: its
                    // leave-by (walk time already baked in by Nysse) is still in the
                    // future. Compare raw millis, not Fmt.minutesUntil, whose toInt()
                    // truncates a "-0.5 min" leave-by to 0 and would call a just-missed
                    // bus catchable. Fall back to the soonest overall so the board
                    // always has a headline (e.g. when every leave-by has passed).
                    val nowMs = Clock.System.now().toEpochMilliseconds()
                    val featured = live.firstOrNull { d ->
                        val lb = d.leaveByMs   // local: leaveByMs is a cross-module nullable, no smart-cast
                        lb == null || lb >= nowMs
                    } ?: live.firstOrNull()
                    _state.value = BussitUiState(
                        loading = false, failed = false, departures = live, featured = featured,
                    )
                    _updatedAt.value = Clock.System.now().epochSeconds
                }.onFailure {
                    _state.value = _state.value.copy(loading = false, failed = true)
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Refresh now, then every 30 s until the caller's scope is cancelled. */
    suspend fun autoRefresh() {
        while (true) {
            refresh()
            delay(30_000)
        }
    }
}
