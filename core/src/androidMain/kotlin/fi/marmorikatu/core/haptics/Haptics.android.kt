package fi.marmorikatu.core.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import fi.marmorikatu.core.platform.AndroidContext

actual class Haptics actual constructor() {

    private val vibrator: Vibrator? by lazy {
        val context = AndroidContext.app
        if (Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    actual fun alert() {
        // Two firm pulses: distinguishable from any ordinary notification.
        play(longArrayOf(0, 220, 130, 220), intArrayOf(0, 255, 0, 255))
    }

    actual fun warn() {
        play(longArrayOf(0, 130), intArrayOf(0, 180))
    }

    actual fun tick() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= 29) {
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        } else {
            @Suppress("DEPRECATION") v.vibrate(20)
        }
    }

    private fun play(timings: LongArray, amplitudes: IntArray) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (v.hasAmplitudeControl()) {
                v.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                v.vibrate(VibrationEffect.createWaveform(timings, -1))
            }
        }
    }
}
