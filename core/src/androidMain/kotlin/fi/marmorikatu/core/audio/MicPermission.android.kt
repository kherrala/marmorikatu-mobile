package fi.marmorikatu.core.audio

import android.Manifest
import android.content.pm.PackageManager
import fi.marmorikatu.core.platform.AndroidContext
import kotlinx.coroutines.CompletableDeferred

/**
 * RECORD_AUDIO permission. The Activity registers itself as the launcher at
 * startup (see MainActivity); requests resolve through [onResult].
 */
actual class MicPermission actual constructor() {

    actual suspend fun ensureGranted(): Boolean {
        val granted = AndroidContext.app.checkSelfPermission(
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return true

        val launcher = requestLauncher ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        launcher(Manifest.permission.RECORD_AUDIO)
        return deferred.await()
    }

    companion object {
        /** Set by the Activity: launches the system permission dialog. */
        var requestLauncher: ((String) -> Unit)? = null
        private var pending: CompletableDeferred<Boolean>? = null

        /** Called by the Activity's ActivityResult callback. */
        fun onResult(granted: Boolean) {
            pending?.complete(granted)
            pending = null
        }
    }
}
