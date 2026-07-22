package fi.marmorikatu.core.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ClimateCancellationTest {
    @Test
    fun bestEffortDegradesOrdinaryFailures() = runTest {
        assertNull(bestEffortSuspend<Any> { error("offline") })
    }

    @Test
    fun bestEffortNeverSwallowsCancellation() = runTest {
        assertFailsWith<CancellationException> {
            bestEffortSuspend<Any> { throw CancellationException("superseded") }
        }
    }
}
