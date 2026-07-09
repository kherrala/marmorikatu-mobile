package fi.marmorikatu.core.background

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import fi.marmorikatu.core.platform.AndroidContext
import kotlinx.coroutines.CompletableDeferred

actual class BackgroundMode actual constructor() {

    actual val supported: Boolean = true

    actual suspend fun ensurePermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val granted = AndroidContext.app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return true

        val launcher = requestLauncher ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        launcher(Manifest.permission.POST_NOTIFICATIONS)
        return deferred.await()
    }

    actual fun setEnabled(enabled: Boolean) {
        if (enabled) AnnouncementService.start(AndroidContext.app)
        else AnnouncementService.stop(AndroidContext.app)
    }

    companion object {
        /** Set by the Activity, like the microphone permission launcher. */
        var requestLauncher: ((String) -> Unit)? = null
        private var pending: CompletableDeferred<Boolean>? = null

        fun onResult(granted: Boolean) {
            pending?.complete(granted)
            pending = null
        }
    }
}
