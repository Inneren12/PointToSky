package dev.pointtosky.mobile.ar

import android.hardware.SensorManager
import android.view.Surface

fun remapForDisplay(
    inR: FloatArray,
    displayRotation: Int,
    outR: FloatArray = FloatArray(9),
): FloatArray {
    val (axisX, axisY) = when (displayRotation) {
        Surface.ROTATION_0 ->       // экран как есть
            SensorManager.AXIS_X to SensorManager.AXIS_Y

        Surface.ROTATION_90 ->      // телефон повернули вправо
            SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X

        Surface.ROTATION_180 ->     // вверх ногами
            SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y

        Surface.ROTATION_270 ->     // повернули влево
            SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X

        else ->
            SensorManager.AXIS_X to SensorManager.AXIS_Y
    }
    SensorManager.remapCoordinateSystem(inR, axisX, axisY, outR)
    return outR
}
