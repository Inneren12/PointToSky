package dev.pointtosky.mobile.ar

import android.hardware.SensorManager
import android.view.Surface
import androidx.annotation.VisibleForTesting

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

/**
 * Android-independent equivalent of the axis permutation [remapForDisplay] asks
 * [SensorManager.remapCoordinateSystem] to perform, for the same four display rotations and the
 * same AXIS_* mapping used there. Pure arithmetic only — no Android framework call — so it stays
 * meaningful under `:mobile` JVM unit tests, where SensorManager is a stubbed no-op and silently
 * leaves outR zeroed (`unitTests.isReturnDefaultValues = true`; see docs/cam_0a_recon.md).
 *
 * Not wired into the production path: [remapForDisplay] keeps calling the framework API there
 * unchanged. This function exists solely to give tests a real seam to exercise the intended remap
 * contract against, without depending on Android framework runtime behavior.
 *
 * Each display rotation is a rotation about the device's own Z axis (the axis pointing out of the
 * screen), so the Z column of [inR] (forward/back) is always carried through unchanged; only the X
 * and Y columns (screen right/up) are permuted and/or sign-flipped, per this table:
 *  - ROTATION_0:   X' =  X,  Y' =  Y
 *  - ROTATION_90:  X' =  Y,  Y' = -X
 *  - ROTATION_180: X' = -X,  Y' = -Y
 *  - ROTATION_270: X' = -Y,  Y' =  X
 * Values are snapshotted per row before writing, so it is safe to pass the same array as both
 * inR and outR (matching how [remapForDisplay] is used in RotationFrame.kt).
 */
@VisibleForTesting
internal fun remapRotationMatrixForDisplay(
    inR: FloatArray,
    displayRotation: Int,
    outR: FloatArray = FloatArray(9),
): FloatArray {
    for (row in 0 until 3) {
        val o = row * 3
        val x = inR[o]
        val y = inR[o + 1]
        val z = inR[o + 2]
        when (displayRotation) {
            Surface.ROTATION_90 -> {
                outR[o] = y
                outR[o + 1] = -x
                outR[o + 2] = z
            }
            Surface.ROTATION_180 -> {
                outR[o] = -x
                outR[o + 1] = -y
                outR[o + 2] = z
            }
            Surface.ROTATION_270 -> {
                outR[o] = -y
                outR[o + 1] = x
                outR[o + 2] = z
            }
            else -> {
                outR[o] = x
                outR[o + 1] = y
                outR[o + 2] = z
            }
        }
    }
    return outR
}
