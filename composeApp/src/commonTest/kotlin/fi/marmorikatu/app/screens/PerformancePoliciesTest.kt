package fi.marmorikatu.app.screens

import fi.marmorikatu.app.components.MkSeries
import fi.marmorikatu.app.components.seriesValueBounds
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerformancePoliciesTest {
    @Test
    fun dashboardHistoryIsForcedOnEntryThenThrottled() {
        assertTrue(shouldRefreshDashboardHistory(true, 9_900, 10_000))
        assertTrue(shouldRefreshDashboardHistory(false, 0, 10_000))
        assertFalse(shouldRefreshDashboardHistory(false, 9_900, 10_000, intervalSeconds = 300))
        assertTrue(shouldRefreshDashboardHistory(false, 9_700, 10_000, intervalSeconds = 300))
    }

    @Test
    fun manualRefreshQueuesBehindSilentPollOnly() {
        assertTrue(shouldQueueForcedRefresh(true, false, true))
        assertFalse(shouldQueueForcedRefresh(true, true, true))
        assertFalse(shouldQueueForcedRefresh(true, false, false))
        assertFalse(shouldQueueForcedRefresh(false, false, true))
    }

    @Test
    fun chartBoundsScanAllSeriesWithoutFlattening() {
        assertEquals(
            -4f to 12f,
            seriesValueBounds(
                listOf(
                    MkSeries("one", listOf(2f, 12f), Color.Red),
                    MkSeries("two", listOf(-4f, 3f), Color.Blue),
                ),
            ),
        )
        assertEquals(0f to 1f, seriesValueBounds(emptyList()))
    }
}
