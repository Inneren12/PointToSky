package dev.pointtosky.wear.sensors.orientation

import android.hardware.SensorManager

internal fun applyRemap(sourceMatrix: FloatArray, tempMatrix: FloatArray, screenRotation: ScreenRotation): FloatArray {
    val success = SensorManager.remapCoordinateSystem(
        sourceMatrix,
        screenRotation.remapAxisX,
        screenRotation.remapAxisY,
        tempMatrix,
    )
    return if (success) tempMatrix else sourceMatrix
}

internal fun axisLabel(axis: Int): String = when (axis) {
    SensorManager.AXIS_X -> "X"
    SensorManager.AXIS_Y -> "Y"
    SensorManager.AXIS_Z -> "Z"
    SensorManager.AXIS_MINUS_X -> "-X"
    SensorManager.AXIS_MINUS_Y -> "-Y"
    SensorManager.AXIS_MINUS_Z -> "-Z"
    else -> axis.toString()
}

internal fun Float.toDegrees(): Float = Math.toDegrees(this.toDouble()).toFloat()

internal const val NANOS_IN_MILLI = 1_000_000L
