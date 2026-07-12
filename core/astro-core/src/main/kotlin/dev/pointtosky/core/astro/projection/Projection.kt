package dev.pointtosky.core.astro.projection

import dev.pointtosky.core.astro.coord.Horizontal
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Pure, Android-independent AR projection math shared by the mobile AR overlay and future CAM
 * (camera-matching) work. Extracted verbatim from the private helpers that previously lived inside
 * `mobile/.../ar/ArScreen.kt` so the same formulas are reusable and JVM-testable outside the
 * Compose/Android UI layer.
 *
 * Conventions:
 *  - World frame is ENU-like: `x → East`, `y → North`, `z → up` (see [horizontalToVector]).
 *  - Device frame is display-aligned: `x → right`, `y → up`, `z → forward` with negative `z` in
 *    front of the camera (see [projectDeviceVector]).
 *  - 3x3 matrices are row-major `FloatArray(9)`; vectors are `FloatArray(3)`. These plain arrays
 *    match the Android `SensorManager` rotation-matrix layout used at the mobile boundary, so no
 *    conversion is needed there.
 *
 * Android/Compose types (`Offset`, `IntSize`) are intentionally kept out of this module; the mobile
 * layer converts [ViewportSize]/[ProjectedPoint] to/from Compose types at its boundary.
 */

/** Base vertical field of view in degrees; horizontal FOV is derived from the viewport aspect. */
const val VERTICAL_FOV_DEG: Double = 56.0

/** Maximum normalized radial screen distance beyond which a point is rejected as off-frame. */
const val MAX_SCREEN_DISTANCE: Double = 1.2

/** Plain viewport size in pixels; the mobile boundary maps `IntSize` onto this. */
data class ViewportSize(
    val width: Int,
    val height: Int,
)

/** Precomputed projection parameters for a given viewport. */
data class ProjectionParams(
    val tanHFov: Double,
    val tanVFov: Double,
    val halfWidth: Float,
    val halfHeight: Float,
)

/**
 * A device vector projected to screen pixels.
 *
 * @property x screen X in pixels (0 = left edge).
 * @property y screen Y in pixels (0 = top edge); note the screen Y flip in [projectDeviceVector].
 * @property distance normalized radial distance from screen centre (used for label declutter).
 */
data class ProjectedPoint(
    val x: Float,
    val y: Float,
    val distance: Double,
)

/**
 * Converts horizontal Alt/Az to a unit ENU vector.
 * Cardinal check: N horizon → (0,1,0), E → (1,0,0), S → (0,-1,0), W → (-1,0,0), zenith → (0,0,1).
 */
fun horizontalToVector(horizontal: Horizontal): FloatArray {
    val altRad = Math.toRadians(horizontal.altDeg)
    val azRad = Math.toRadians(horizontal.azDeg)
    val cosAlt = cos(altRad)
    return floatArrayOf(
        (cosAlt * sin(azRad)).toFloat(),
        (cosAlt * cos(azRad)).toFloat(),
        sin(altRad).toFloat(),
    )
}

/** Inverse of [horizontalToVector]: recovers Alt/Az (azimuth normalized to `0°..360°`). */
fun vectorToHorizontal(vector: FloatArray): Horizontal {
    val z = vector[2].toDouble().coerceIn(-1.0, 1.0)
    val altDeg = Math.toDegrees(asin(z))
    var azDeg = Math.toDegrees(atan2(vector[0].toDouble(), vector[1].toDouble()))
    if (azDeg < 0) {
        azDeg += 360.0
    }
    return Horizontal(azDeg = azDeg, altDeg = altDeg)
}

/** Transposes a row-major 3x3 matrix. For a rotation matrix this is its inverse. */
fun transpose(matrix: FloatArray): FloatArray {
    val result = FloatArray(9)
    for (i in 0 until 3) {
        for (j in 0 until 3) {
            result[i * 3 + j] = matrix[j * 3 + i]
        }
    }
    return result
}

/** Multiplies a row-major 3x3 matrix by a 3-vector. */
fun multiply(
    matrix: FloatArray,
    vector: FloatArray,
): FloatArray {
    val x = matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2]
    val y = matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2]
    val z = matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2]
    return floatArrayOf(x, y, z)
}

/**
 * Derives [ProjectionParams] from a viewport using the fixed [VERTICAL_FOV_DEG] and an
 * aspect-derived horizontal FOV (`tanHFov = tanVFov * width/height`). Portrait and landscape
 * viewports therefore produce different horizontal FOVs — this matches existing AR behavior.
 */
fun projectionParams(viewport: ViewportSize): ProjectionParams {
    val width = viewport.width.toFloat()
    val height = viewport.height.toFloat()
    val aspect = viewport.width.toDouble() / viewport.height.toDouble()
    val tanVFov = tan(Math.toRadians(VERTICAL_FOV_DEG / 2.0))
    val tanHFov = tanVFov * aspect
    return ProjectionParams(tanHFov, tanVFov, width / 2f, height / 2f)
}

/**
 * Projects a display-aligned device vector to screen pixels, or returns `null` when the point is
 * behind the camera (`z >= -0.01f`) or falls beyond [MAX_SCREEN_DISTANCE].
 *
 * deviceVec is expressed in display-aligned device coordinates:
 * x → right, y → up, z → forward (negative means in front of the camera).
 */
fun projectDeviceVector(
    deviceVec: FloatArray,
    params: ProjectionParams,
): ProjectedPoint? {
    val z = deviceVec[2]
    if (z >= -0.01f) return null

    val ndcX = (deviceVec[0].toDouble() / -z.toDouble()) / params.tanHFov
    val ndcY = (deviceVec[1].toDouble() / -z.toDouble()) / params.tanVFov
    val distance = sqrt(ndcX * ndcX + ndcY * ndcY)
    if (distance > MAX_SCREEN_DISTANCE) return null

    val screenX = params.halfWidth * (1f + ndcX.toFloat())
    val screenY = params.halfHeight * (1f - ndcY.toFloat())
    return ProjectedPoint(x = screenX, y = screenY, distance = distance)
}
