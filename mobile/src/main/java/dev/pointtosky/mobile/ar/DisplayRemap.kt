package dev.pointtosky.mobile.ar

import android.hardware.SensorManager
import android.view.Surface

fun remapForDisplay(
    inR: FloatArray,
    displayRotation: Int,
    outR: FloatArray = FloatArray(9),
): FloatArray {
    val (axisX, axisY) =
        when (displayRotation) {
            Surface.ROTATION_0 -> SensorManager.AXIS_X to SensorManager.AXIS_Z
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_Z
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_Z
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_Z
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
        }
    SensorManager.remapCoordinateSystem(inR, axisX, axisY, outR)
    return outR
}
