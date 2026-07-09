package fi.marmorikatu.core.lifecycle

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the app is in the foreground. Android: ProcessLifecycleOwner;
 * iOS: UIApplication active/resign notifications.
 */
expect class AppForeground() {
    val isForeground: StateFlow<Boolean>
}
