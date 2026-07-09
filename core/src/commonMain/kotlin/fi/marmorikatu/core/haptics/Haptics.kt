package fi.marmorikatu.core.haptics

/**
 * Physical feedback for announcements.
 *
 * The house speaks through the kiosk; on a phone in a pocket the only channel
 * that reliably reaches a person is the motor. Critical alerts (priority 0 —
 * freezing danger, alarms) always vibrate, whatever the user's preference.
 */
expect class Haptics() {
    /** Priority 0: a long, unmistakable double buzz. */
    fun alert()

    /** Priority 1: a single short buzz. */
    fun warn()

    /** Confirmation of a control action. */
    fun tick()
}
