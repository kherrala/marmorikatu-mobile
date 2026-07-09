package fi.marmorikatu.core.background

/**
 * Keeps the announcement stream alive while the app is not on screen, posting
 * a system notification for each event.
 *
 * Android does this with a foreground service. iOS gives an app no way to hold
 * a long-lived socket in the background — reaching a backgrounded iPhone needs
 * the house server to send an APNs push, which the backend does not do today.
 * [supported] therefore reports false on iOS and [setEnabled] is a no-op, so
 * the UI can offer the switch honestly rather than pretending.
 */
expect class BackgroundMode() {
    val supported: Boolean

    /** Whether the platform will let us post notifications at all. */
    suspend fun ensurePermission(): Boolean

    fun setEnabled(enabled: Boolean)
}
