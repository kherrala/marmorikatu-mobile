package fi.marmorikatu.core.background

/**
 * iOS suspends an app's sockets shortly after it leaves the screen, so there is
 * no way to keep the announcements stream open the way the Android foreground
 * service does. Delivering events to a backgrounded iPhone requires the house
 * server to send an APNs push, which it does not do today (see the backend
 * follow-ups in the README).
 *
 * Reporting [supported] = false lets the settings UI say so plainly instead of
 * offering a switch that would quietly do nothing.
 */
actual class BackgroundMode actual constructor() {
    actual val supported: Boolean = false

    actual suspend fun ensurePermission(): Boolean = false

    actual fun setEnabled(enabled: Boolean) = Unit
}
