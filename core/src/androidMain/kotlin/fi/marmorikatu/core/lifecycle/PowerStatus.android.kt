package fi.marmorikatu.core.lifecycle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import fi.marmorikatu.core.platform.AndroidContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class PowerStatus actual constructor() {
    private val _isPluggedIn = MutableStateFlow(false)
    actual val isPluggedIn: StateFlow<Boolean> = _isPluggedIn.asStateFlow()

    init {
        val app = AndroidContext.app
        // Seed from the sticky battery intent (a null receiver just reads it), so
        // the state is right from launch without waiting for a plug event.
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        _isPluggedIn.value = (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        // Live plug/unplug updates. The receiver lives for the process lifetime
        // (this is a DI singleton), so it is intentionally never unregistered.
        app.registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_POWER_CONNECTED -> _isPluggedIn.value = true
                        Intent.ACTION_POWER_DISCONNECTED -> _isPluggedIn.value = false
                    }
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
        )
    }
}
