package fi.marmorikatu.app.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class StartupState { Pending, Ok, Failed }

/**
 * Tracks the on-launch data fetches so the boot splash can show a checklist item
 * per operation, not just the two connection tiles. The fetches live in the
 * screen view-models (which compose underneath the splash and kick off at once),
 * so they report here and [App]'s BootSplash renders it alongside the MQTT /
 * server connection state.
 */
class StartupProgress {
    /** Ordered fetch tasks the splash lists after the two connection tiles. */
    private val _states = MutableStateFlow<Map<String, StartupState>>(
        linkedMapOf(
            KEY_WEATHER to StartupState.Pending,
            KEY_NEWS to StartupState.Pending,
            KEY_CALENDAR to StartupState.Pending,
            KEY_METRICS to StartupState.Pending,
        )
    )
    val states: StateFlow<Map<String, StartupState>> = _states.asStateFlow()

    fun mark(key: String, ok: Boolean) {
        _states.update { it + (key to if (ok) StartupState.Ok else StartupState.Failed) }
    }

    companion object {
        const val KEY_WEATHER = "weather"
        const val KEY_NEWS = "news"
        const val KEY_CALENDAR = "calendar"
        const val KEY_METRICS = "metrics"

        /** Finnish labels for the splash, in list order. */
        val LABELS = linkedMapOf(
            KEY_WEATHER to "Sää",
            KEY_NEWS to "Uutiset",
            KEY_CALENDAR to "Kalenteri",
            KEY_METRICS to "Mittarit",
        )
    }
}
