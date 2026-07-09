package fi.marmorikatu.core.repository

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTime::class)
private class FakeClock(var current: Instant = Instant.fromEpochSeconds(1_000_000)) : Clock {
    override fun now(): Instant = current
    fun advanceSeconds(seconds: Int) {
        current += seconds.seconds
    }
}

@OptIn(ExperimentalTime::class)
class ReconcilerTest {

    @Test
    fun confirmedCommandSettles() {
        val reconciler = Reconciler<Int, Boolean>()
        reconciler.commandSent(43, true)
        assertEquals(true, reconciler.pendingValue(43))

        reconciler.observed(43, true)
        assertNull(reconciler.pendingValue(43))
    }

    @Test
    fun mismatchedObservationKeepsPending() {
        val reconciler = Reconciler<Int, Boolean>()
        reconciler.commandSent(43, true)
        // A stale retained snapshot with the old value must not clear optimism.
        reconciler.observed(43, false)
        assertEquals(true, reconciler.pendingValue(43))
    }

    @Test
    fun overdueCommandsExpireAndReport() {
        val clock = FakeClock()
        val reconciler = Reconciler<Int, Boolean>(deadline = 20.seconds, clock = clock)
        reconciler.commandSent(1, true)
        reconciler.commandSent(2, false)

        clock.advanceSeconds(10)
        assertEquals(emptyList(), reconciler.expireOverdue())

        clock.advanceSeconds(11)
        assertEquals(setOf(1, 2), reconciler.expireOverdue().toSet())
        assertNull(reconciler.pendingValue(1))
    }

    @Test
    fun newerCommandExtendsDeadline() {
        val clock = FakeClock()
        val reconciler = Reconciler<Int, Boolean>(deadline = 20.seconds, clock = clock)
        reconciler.commandSent(1, true)
        clock.advanceSeconds(15)
        reconciler.commandSent(1, false) // user toggled again
        clock.advanceSeconds(10)
        assertEquals(emptyList(), reconciler.expireOverdue())
        assertEquals(false, reconciler.pendingValue(1))
    }
}
