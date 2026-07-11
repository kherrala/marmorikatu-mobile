package fi.marmorikatu.core.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The reconnect curve is the battery contract: quick retries for a transient
 * home blip, then an infrequent poll once the house is clearly unreachable.
 */
class ReconnectTest {

    @Test
    fun firstMinuteRetriesQuicklyThenCapsAtThirtySeconds() {
        assertEquals(1.seconds, reconnectDelay(1))
        assertEquals(2.seconds, reconnectDelay(2))
        assertEquals(4.seconds, reconnectDelay(3))
        assertEquals(8.seconds, reconnectDelay(4))
        assertEquals(16.seconds, reconnectDelay(5))
        // 6th attempt would be 32 s exponentially; capped to the 30 s fast ceiling.
        assertEquals(30.seconds, reconnectDelay(6))
    }

    @Test
    fun sustainedFailureRelaxesToTheSlowPoll() {
        assertEquals(SLOW_POLL, reconnectDelay(7))
        assertEquals(SLOW_POLL, reconnectDelay(50))
        assertEquals(5.minutes, SLOW_POLL)
    }

    @Test
    fun nonPositiveFailureCountIsTreatedAsTheFirstAttempt() {
        assertEquals(1.seconds, reconnectDelay(0))
        assertEquals(1.seconds, reconnectDelay(-3))
    }

    @Test
    fun aShorterSlowCapCanBeRequested() {
        assertEquals(1.minutes, reconnectDelay(9, slow = 1.minutes))
        // The fast phase is unaffected by the slow-cap override.
        assertEquals(4.seconds, reconnectDelay(3, slow = 1.minutes))
    }
}
