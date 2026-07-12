package dev.pointtosky.mobile.ar.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

/**
 * Thin snapshot of the subset of Camera2 `CameraCharacteristics` [CameraIntrinsicsResolver] needs
 * (CAM-1b), decoupled from `CameraCharacteristics.Key` mechanics so resolution logic can be unit
 * tested with plain fakes — no real camera, no Robolectric.
 *
 * @property availableFocalLengthsMm raw `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, millimetres.
 * @property sensorPhysicalWidthMm raw `SENSOR_INFO_PHYSICAL_SIZE` width, millimetres.
 * @property sensorPhysicalHeightMm raw `SENSOR_INFO_PHYSICAL_SIZE` height, millimetres.
 */
data class CameraCharacteristicsSnapshot(
    val availableFocalLengthsMm: FloatArray?,
    val sensorPhysicalWidthMm: Float?,
    val sensorPhysicalHeightMm: Float?,
)

/**
 * Reads a [CameraCharacteristicsSnapshot] for one camera. Implementations may throw if the
 * underlying metadata is inaccessible; [CameraIntrinsicsResolver] treats any thrown exception as
 * "characteristics unavailable" and falls back to [dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics].
 */
fun interface CameraCharacteristicsSource {
    fun snapshot(): CameraCharacteristicsSnapshot
}

/**
 * Production [CameraCharacteristicsSource] backed by CameraX's Camera2 interop
 * (`Camera2CameraInfo.from(cameraInfo)`).
 *
 * [ExperimentalCamera2Interop] is AndroidX's legacy Java-based `@RequiresOptIn`-style experimental
 * marker (`androidx.annotation.RequiresOptIn`), not Kotlin's native one — so it must be suppressed
 * with `androidx.annotation.OptIn`, not `kotlin.OptIn`. The two have identical call syntax
 * (`@OptIn(ExperimentalCamera2Interop::class)`), so this is easy to get wrong silently:
 * `kotlin.OptIn` compiles cleanly and even passes plain JVM unit tests, but Android Lint's
 * `UnsafeOptInUsageError` check (which enforces this marker, not the Kotlin compiler) only
 * recognizes `androidx.annotation.OptIn` and fails `:mobile:lintInternalDebug` if it is missing.
 */
internal class Camera2CharacteristicsSource(
    private val cameraInfo: CameraInfo,
) : CameraCharacteristicsSource {
    @OptIn(ExperimentalCamera2Interop::class)
    override fun snapshot(): CameraCharacteristicsSnapshot {
        val characteristics = Camera2CameraInfo.from(cameraInfo)
        val physicalSize = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        return CameraCharacteristicsSnapshot(
            availableFocalLengthsMm =
                characteristics.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
            sensorPhysicalWidthMm = physicalSize?.width,
            sensorPhysicalHeightMm = physicalSize?.height,
        )
    }
}
