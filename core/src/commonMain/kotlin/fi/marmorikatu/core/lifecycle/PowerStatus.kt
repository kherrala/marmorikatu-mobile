package fi.marmorikatu.core.lifecycle

import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the device is plugged into external power. The wall-mounted kiosk is
 * always powered; a handheld on battery is not. Kiosk behaviours that cost
 * battery — keeping connections alive while backgrounded, the idle 3D
 * screensaver — are gated on this so a phone (or an unplugged tablet) behaves
 * like a normal app instead of draining. Android: battery broadcasts; iOS:
 * UIDevice battery monitoring.
 */
expect class PowerStatus() {
    val isPluggedIn: StateFlow<Boolean>
}
