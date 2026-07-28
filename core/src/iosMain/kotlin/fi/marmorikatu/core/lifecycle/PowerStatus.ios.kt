package fi.marmorikatu.core.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryStateDidChangeNotification
import platform.UIKit.UIDeviceBatteryState

actual class PowerStatus actual constructor() {
    private val _isPluggedIn = MutableStateFlow(false)
    actual val isPluggedIn: StateFlow<Boolean> = _isPluggedIn.asStateFlow()

    init {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        fun refresh() {
            // Charging or full both mean "on external power".
            _isPluggedIn.value = device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateCharging ||
                device.batteryState == UIDeviceBatteryState.UIDeviceBatteryStateFull
        }
        refresh()
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIDeviceBatteryStateDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> refresh() }
    }
}
