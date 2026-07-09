package fi.marmorikatu.core.repository

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Optimistic-write bookkeeping for control commands whose confirmation
 * arrives later via a retained MQTT state topic. The PLC republishes state
 * roughly every 13 s, so the default deadline is 20 s.
 *
 * NOT thread-safe: callers must confine access (see the state lock in
 * [DefaultLightsRepository]), because commands come from the UI coroutine
 * while confirmations arrive on the MQTT collector.
 */
@OptIn(ExperimentalTime::class)
class Reconciler<K, V>(
    private val deadline: Duration = 20.seconds,
    private val clock: Clock = Clock.System,
) {
    private data class Pending<V>(val desired: V, val expiresAt: Instant)

    private val pending = mutableMapOf<K, Pending<V>>()

    /** Record a command that was just sent. */
    fun commandSent(key: K, desired: V) {
        pending[key] = Pending(desired, clock.now() + deadline)
    }

    /** The optimistic value to display, or null when settled. */
    fun pendingValue(key: K): V? = pending[key]?.desired

    /**
     * Feed a confirmed state observation. Clears the pending entry when the
     * transport truth matches the desired value.
     */
    fun observed(key: K, actual: V) {
        val entry = pending[key] ?: return
        if (entry.desired == actual) pending.remove(key)
    }

    /** Abandons an optimistic entry, e.g. when the command failed to send. */
    fun cancel(key: K) {
        pending.remove(key)
    }

    /**
     * Drops expired entries and returns the keys that timed out without
     * confirmation — the UI reverts these to transport truth and can surface
     * a control failure.
     */
    fun expireOverdue(): List<K> {
        val now = clock.now()
        val overdue = pending.filterValues { it.expiresAt <= now }.keys.toList()
        overdue.forEach(pending::remove)
        return overdue
    }

    fun clear() = pending.clear()
}
