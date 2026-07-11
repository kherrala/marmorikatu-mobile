package fi.marmorikatu.app.shell

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Cross-cutting UI signals shared between the shell and the screens without
 * threading callbacks through the whole tree.
 *
 * [detailOpen] is set by a screen while it is showing a full-page detail chart.
 * The shell uses it to keep a phone on its phone surface (rather than swapping
 * to the kiosk dashboard) when a detail view forces landscape — so the chart
 * simply rotates to use the width, instead of the whole app changing layout.
 */
class UiSignals {
    val detailOpen = MutableStateFlow(false)

    /**
     * A pending navigation request from outside the Compose tree — e.g. a tapped
     * system notification, whose route is derived from the announcement kind. The
     * shell consumes it (switches tab, and opens the news reader for a news
     * route), then resets it to null so it fires exactly once.
     */
    val pendingNav = MutableStateFlow<String?>(null)

    /** Set by the shell for a news notification; the Koti screen opens its reader and clears it. */
    val openNewsReader = MutableStateFlow(false)

    fun requestNav(route: String) {
        pendingNav.value = route
    }
}
