package fi.marmorikatu.app.shell

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    // Each detail-hosting screen contributes under its own key and the flag is
    // the OR. With a single shared boolean, a composed neighbour pager page
    // (its own detail closed) would keep writing false over the active page's
    // true — the shell would flip to the kiosk, dispose the detail's landscape
    // lock, and oscillate between the orientations.
    private var detailOwners = emptySet<String>()
    private val _detailOpen = MutableStateFlow(false)
    val detailOpen: StateFlow<Boolean> = _detailOpen.asStateFlow()

    /** Assert or clear [owner]'s full-page detail. Main-thread only (composition apply). */
    fun setDetailOpen(owner: String, open: Boolean) {
        detailOwners = if (open) detailOwners + owner else detailOwners - owner
        _detailOpen.value = detailOwners.isNotEmpty()
    }

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

    /**
     * A request to open the 3D house overlay from outside the tree — today the
     * voice commands "Näytä talo 3D:nä" / "Näytä yläkerta". [floor] is one of
     * all/kellari/alakerta/ylakerta (null = leave as-is); [spin] opens the
     * rotating presentation. Each request is a fresh instance so a repeat of the
     * same command still fires. The shell consumes it and resets to null.
     */
    val houseView = MutableStateFlow<HouseViewRequest?>(null)

    fun requestHouseView(floor: String?, spin: Boolean) {
        houseView.value = HouseViewRequest(floor, spin)
    }
}

data class HouseViewRequest(val floor: String?, val spin: Boolean)
