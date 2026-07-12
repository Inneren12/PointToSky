package dev.pointtosky.mobile.ar.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2CameraInfo
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
 */
internal class Camera2CharacteristicsSource(
    private val cameraInfo: CameraInfo,
) : CameraCharacteristicsSource {
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
