package dev.pointtosky.wear.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.getSystemService

enum class HapticEvent { ENTER, LOCK, LOST }

class HapticPolicy(private val context: Context) {
    fun play(event: HapticEvent, enabled: Boolean = true) {
        if (!enabled) return
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService<Vibrator>()
        } ?: return
        if (!vibrator.hasVibrator()) return

        when (event) {
            HapticEvent.ENTER -> oneShot(vibrator, 60, VibrationEffect.DEFAULT_AMPLITUDE)
            HapticEvent.LOCK  -> tripleWave(vibrator)
            HapticEvent.LOST  -> oneShot(vibrator, 130, VibrationEffect.DEFAULT_AMPLITUDE)
        }
    }

    private fun oneShot(vibrator: Vibrator, millis: Long, amplitude: Int) {
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(millis, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(millis)
        }
    }

    private fun tripleWave(vibrator: Vibrator) {
        val timings = longArrayOf(0, 50, 50, 50, 50, 50)
        val amps = intArrayOf(0, 120, 0, 180, 0, 255)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amps, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }
}
