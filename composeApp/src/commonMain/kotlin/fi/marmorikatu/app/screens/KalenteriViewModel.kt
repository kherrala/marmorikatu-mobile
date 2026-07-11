package fi.marmorikatu.app.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.marmorikatu.app.components.GarbagePickup
import fi.marmorikatu.core.repository.InfoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** One calendar entry, already formatted for the day it belongs to. */
data class CalendarEvent(
    val time: String,
    val title: String,
    val who: String,
)

/** A day heading (`Tänään` · `ke 18.2.`) and its events, in time order. */
data class CalendarDay(
    val dayLabel: String,
    val dateLabel: String,
    val events: List<CalendarEvent>,
)

/** Loading / failed / loaded family calendar, plus the parsed waste pickup schedule. */
data class KalenteriUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val days: List<CalendarDay> = emptyList(),
    val garbage: List<GarbagePickup> = emptyList(),
)

/**
 * Fetches the merged family-calendar + PJHOY garbage-collection feed via
 * [InfoRepository] (backed by the MCP `get_calendar_events` tool, which itself
 * proxies `marmorikatu-home-automation`'s calendar_server.py) and splits it
 * into the two things this screen shows.
 *
 * The backend's own event objects carry a `type` field (`"garbage"` vs
 * `"calendar"`/`"school"`), but `get_calendar_events` (scripts/mcp_tools/
 * external.py `handle_get_calendar_events`) re-groups everything by date and
 * rebuilds each entry as `{summary, time="koko päivä" | start/end "HH:MM",
 * location?}` — `type` does NOT survive that reshaping. So garbage entries
 * are told apart from family events the same way the calendar_server.py kiosk
 * page does it (templates/calendar/app.js `renderGarbageCountdown`): by the
 * emoji PJHOY's `PRODUCT_GROUPS` always prefixes onto the summary.
 */
class KalenteriViewModel(
    private val infoRepo: InfoRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(KalenteriUiState())
    val state: StateFlow<KalenteriUiState> = _state.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _updatedAt = MutableStateFlow<Long?>(null)
    val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    @OptIn(ExperimentalTime::class)
    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                runCatching { infoRepo.calendar(CalendarParsing.CALENDAR_DAYS) }
                    .onSuccess { element ->
                        val parsed = runCatching { CalendarParsing.parseCalendar(element) }.getOrDefault(ParsedCalendar.EMPTY)
                        _state.value = _state.value.copy(
                            loading = false,
                            failed = false,
                            days = parsed.days,
                            garbage = parsed.garbage,
                        )
                        _updatedAt.value = Clock.System.now().epochSeconds
                    }
                    .onFailure {
                        _state.value = _state.value.copy(loading = false, failed = true)
                    }
            } finally {
                _refreshing.value = false
            }
        }
    }
}
