package fi.marmorikatu.core.haptics

import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

/**
 * iOS has no arbitrary vibration API; the platform's feedback generators are
 * the sanctioned equivalent and match the system's haptic language.
 */
actual class Haptics actual constructor() {

    private val notification by lazy { UINotificationFeedbackGenerator() }
    private val impact by lazy {
        UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    }

    actual fun alert() {
        notification.prepare()
        notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
    }

    actual fun warn() {
        notification.prepare()
        notification.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
    }

    actual fun tick() {
        impact.prepare()
        impact.impactOccurred()
    }
}
