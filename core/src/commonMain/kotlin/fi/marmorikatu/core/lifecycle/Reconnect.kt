package fi.marmorikatu.core.lifecycle

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Shared reconnect cadence for every retry loop (MQTT, announcements SSE, bridge
 * health, MCP catalog).
 *
 * It stays snappy for a transient drop *at home* — a broker restart, a Wi-Fi
 * handover — for the first minute, then relaxes to an infrequent periodic poll
 * once failures persist. Persistent failure is exactly the "phone is away from
 * home, the house is simply unreachable" case: a dead server can't be reached
 * any sooner by asking every 30 s, and a backgrounded app waking the radio that
 * often all day is the battery cost the relaxed tail removes (~120 → ~12 radio
 * wake-ups an hour). The plugged-in kiosk is unaffected in practice — it is
 * always reachable, so it never leaves the fast phase.
 *
 * @param failures consecutive failed attempts (1 for the first failure).
 * @param slow the far end of the curve; defaults to [SLOW_POLL].
 */
fun reconnectDelay(failures: Int, slow: Duration = SLOW_POLL): Duration {
    val f = failures.coerceAtLeast(1)
    return if (f <= FAST_ATTEMPTS) {
        (FAST_BASE * (1 shl (f - 1))).coerceAtMost(FAST_CAP)
    } else {
        slow
    }
}

/**
 * A connection that stayed up at least this long counts as proof we were
 * reachable: the next drop restarts from the snappy end of [reconnectDelay]
 * instead of inheriting a stale away-backoff. Comfortably past the 20 s SSE
 * keepalive, so a genuinely established stream always resets.
 */
val CONNECTED_HOLD: Duration = 45.seconds

/** Steady poll interval while the bridge health check is passing. */
val HEALTHY_POLL: Duration = 30.seconds

/** ~1 min of quick retries: 1, 2, 4, 8, 16, 30 s. */
private const val FAST_ATTEMPTS = 6
private val FAST_BASE: Duration = 1.seconds
private val FAST_CAP: Duration = 30.seconds

/** The battery-friendly periodic poll once a failure is clearly not transient. */
val SLOW_POLL: Duration = 5.minutes
