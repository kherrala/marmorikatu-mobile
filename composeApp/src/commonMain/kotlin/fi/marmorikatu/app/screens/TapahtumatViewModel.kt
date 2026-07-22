package fi.marmorikatu.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.repository.AnnouncementsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Loading / loaded / error snapshot for the event log. */
data class TapahtumatUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val streaming: Boolean = false,
    val events: List<Announcement> = emptyList(),
)

/**
 * Feeds the Tapahtumat (event log) screen: the live announcement ring merged
 * with the loaded backlog, deduped by id, newest first.
 *
 * The backlog is a one-shot `history(50)` fetch; the live ring keeps updating
 * as the SSE stream (opened by the app shell) pushes new events.
 */
class TapahtumatViewModel(
    private val announcementsRepo: AnnouncementsRepository,
) : ViewModel() {

    private val history = MutableStateFlow<List<Announcement>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow(false)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()
    private var refreshJob: Job? = null

    val uiState: StateFlow<TapahtumatUiState> =
        combine(
            announcementsRepo.recent,
            history,
            announcementsRepo.streaming,
            loading,
            error,
        ) { recent, backlog, streaming, isLoading, isError ->
            // Live ring first so a dedup keeps the freshest copy of a repeated id.
            val merged = (recent + backlog)
                .distinctBy { it.id }
                .sortedByDescending { it.ts }
            TapahtumatUiState(
                loading = isLoading,
                error = isError,
                streaming = streaming,
                events = merged,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TapahtumatUiState())

    @OptIn(ExperimentalTime::class)
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _refreshing.value = true
            error.value = false
            try {
                runCatching { announcementsRepo.history(50) }
                    .onSuccess {
                        history.value = it
                        loading.value = false
                        _updatedAt.value = Clock.System.now().epochSeconds
                    }
                    .onFailure {
                        error.value = true
                        loading.value = false
                    }
            } finally {
                _refreshing.value = false
            }
        }
    }
}
