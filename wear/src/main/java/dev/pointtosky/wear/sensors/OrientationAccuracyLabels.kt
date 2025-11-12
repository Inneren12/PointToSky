package dev.pointtosky.wear.sensors

import androidx.annotation.StringRes
import dev.pointtosky.wear.R
import dev.pointtosky.wear.sensors.orientation.OrientationAccuracy

@StringRes
fun orientationAccuracyStringRes(accuracy: OrientationAccuracy): Int =
    when (accuracy) {
        OrientationAccuracy.UNRELIABLE -> R.string.orientation_accuracy_unreliable
        OrientationAccuracy.LOW -> R.string.orientation_accuracy_low
        OrientationAccuracy.MEDIUM -> R.string.orientation_accuracy_medium
        OrientationAccuracy.HIGH -> R.string.orientation_accuracy_high
    }
