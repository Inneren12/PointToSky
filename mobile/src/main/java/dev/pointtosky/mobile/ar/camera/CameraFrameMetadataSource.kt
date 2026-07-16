package dev.pointtosky.mobile.ar.camera

import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3

/**
 * Metadata-only view of one analyzed camera frame (CAM-1c), decoupled from `ImageProxy` mechanics
 * so [toCameraFrameMetadata] and [CameraFrameAnalyzer] can be unit-tested with a plain fake — no
 * real camera, no `ImageProxy` mock. Mirrors [dev.pointtosky.mobile.ar.camera.CameraCharacteristicsSource]
 * from CAM-1b: production code adapts the real CameraX type ([ImageProxyFrameMetadataSource]),
 * resolution/mapping logic is tested against the interface.
 *
 * Implementations must expose only metadata already available on `ImageProxy` without touching
 * `planes`, `image`, or any pixel buffer/row/stride.
 */
interface CloseableFrameMetadataSource {
    val timestampNanos: Long
    val widthPx: Int
    val heightPx: Int
    val rotationDegrees: Int
    val cropRectLeftPx: Int?
    val cropRectTopPx: Int?
    val cropRectRightPx: Int?
    val cropRectBottomPx: Int?

    /**
     * (CAM-2c, fix §1) The real per-frame sensor-to-buffer mapping, preserving all 9 reported matrix
     * values — or `null` only when entirely unavailable/non-finite. See [ImageProxyFrameMetadataSource]'s
     * KDoc for how production code derives this from `ImageProxy.imageInfo.sensorToBufferTransformMatrix`.
     * A present-but-geometrically-unsupported matrix (rotated/mirrored/projective) is still returned
     * here in full — classification and rejection is
     * `dev.pointtosky.mobile.ar.camera.resolveAnalysisBufferIntrinsics`'s job, never this source's.
     */
    val sensorToBufferTransform: SensorToBufferMatrix3?

    /** Releases the underlying frame. Callers must invoke this exactly once, always in `finally`. */
    fun close()
}

/**
 * Pure mapping from raw [CloseableFrameMetadataSource] fields to a validated [CameraFrameMetadata].
 * Separated from [ImageProxyFrameMetadataSource] so this mapping — and the [CameraFrameMetadata]
 * validation it triggers — is testable with plain values, without a real `ImageProxy`.
 */
fun CloseableFrameMetadataSource.toCameraFrameMetadata(): CameraFrameMetadata =
    CameraFrameMetadata(
        timestampNanos = timestampNanos,
        bufferWidthPx = widthPx,
        bufferHeightPx = heightPx,
        rotationDegrees = rotationDegrees,
        cropRectLeftPx = cropRectLeftPx,
        cropRectTopPx = cropRectTopPx,
        cropRectRightPx = cropRectRightPx,
        cropRectBottomPx = cropRectBottomPx,
        sensorToBufferTransform = sensorToBufferTransform,
    )

/**
 * Pure conversion of an `android.graphics.Matrix`'s 9-element `getValues()` output — indices matching
 * `Matrix.MSCALE_X`/`MSKEW_X`/`MTRANS_X`/`MSKEW_Y`/`MSCALE_Y`/`MTRANS_Y`/`MPERSP_0`/`MPERSP_1`/`MPERSP_2`
 * exactly — into a plain, Android-independent [SensorToBufferMatrix3] (CAM-2c fix §1). Separated from
 * [androidMatrixToSensorToBufferTransform] so this conversion is unit-testable with a plain
 * `FloatArray`, without a real (non-Robolectric) `android.graphics.Matrix` instance — matching this
 * codebase's existing split-for-testability convention (e.g. [toCameraFrameMetadata] vs
 * [ImageProxyFrameMetadataSource]).
 *
 * Preserves all 9 values exactly — **no** longer rejects a non-zero skew/perspective component (the
 * pre-fix behavior assumed, without verifying, that Camera2's crop-then-scale pipeline could only
 * ever produce an axis-aligned scale+translate; see [SensorToBufferMatrix3]'s KDoc for why that
 * assumption is no longer baked into the type itself). Classifying whether the resulting matrix is
 * geometrically usable is entirely
 * `dev.pointtosky.core.astro.projection.camera.classifySensorToBufferMatrix`'s job, invoked by
 * `dev.pointtosky.mobile.ar.camera.resolveAnalysisBufferIntrinsics` — never guessed here.
 *
 * Returns `null`, rather than fabricating a value, only when any of the 9 values is non-finite (an
 * `android.graphics.Matrix` should never report one, but this function does not trust that blindly).
 *
 * @throws IllegalArgumentException if [values] does not have exactly 9 elements — a caller-contract
 *   violation ([Matrix.getValues] always fills exactly 9), not an expected runtime outcome.
 */
internal fun sensorToBufferTransformFromMatrixValues(values: FloatArray): SensorToBufferMatrix3? {
    require(values.size == 9) { "values must have exactly 9 elements (Matrix.getValues() layout); was ${values.size}" }
    if (values.any { !it.isFinite() }) return null

    return SensorToBufferMatrix3(
        m00 = values[MSCALE_X].toDouble(),
        m01 = values[MSKEW_X].toDouble(),
        m02 = values[MTRANS_X].toDouble(),
        m10 = values[MSKEW_Y].toDouble(),
        m11 = values[MSCALE_Y].toDouble(),
        m12 = values[MTRANS_Y].toDouble(),
        m20 = values[MPERSP_0].toDouble(),
        m21 = values[MPERSP_1].toDouble(),
        m22 = values[MPERSP_2].toDouble(),
    )
}

// Mirrors android.graphics.Matrix's own MSCALE_X/MSKEW_X/.../MPERSP_2 index constants exactly - not
// referenced from Matrix directly so sensorToBufferTransformFromMatrixValues stays plain-JVM
// testable (see that function's KDoc).
private const val MSCALE_X = 0
private const val MSKEW_X = 1
private const val MTRANS_X = 2
private const val MSKEW_Y = 3
private const val MSCALE_Y = 4
private const val MTRANS_Y = 5
private const val MPERSP_0 = 6
private const val MPERSP_1 = 7
private const val MPERSP_2 = 8

/**
 * Converts a real `android.graphics.Matrix` — `ImageProxy.imageInfo.getSensorToBufferTransformMatrix()`
 * — into a plain, Android-independent [SensorToBufferMatrix3] (CAM-2c fix §1). Thin wrapper around
 * [sensorToBufferTransformFromMatrixValues]; see that function's KDoc for the actual conversion rule.
 */
internal fun androidMatrixToSensorToBufferTransform(matrix: Matrix): SensorToBufferMatrix3? {
    val values = FloatArray(9)
    matrix.getValues(values)
    return sensorToBufferTransformFromMatrixValues(values)
}

/**
 * Production [CloseableFrameMetadataSource] wrapping a real [ImageProxy] (CAM-1c).
 *
 * Reads exactly [ImageProxy.imageInfo] (`timestamp`, `rotationDegrees`,
 * `getSensorToBufferTransformMatrix()` — CAM-2c), [ImageProxy.getWidth], [ImageProxy.getHeight], and
 * [ImageProxy.getCropRect] — never [ImageProxy.getPlanes], `ImageProxy.getImage()`, or any pixel
 * row/stride. [close] delegates to [ImageProxy.close].
 *
 * ## Why not `ImageProxy.cropRect`
 * `ImageProxy.getCropRect()` is a *buffer-space* rectangle — CameraX sets it from a bound `ViewPort`
 * (`UseCaseGroup`), and defaults to exactly `(0, 0, width, height)` — the full buffer, no crop at
 * all — when no `ViewPort` is configured, which is `CameraPreview.kt`'s exact binding
 * (`Preview` + `ImageAnalysis`, no `UseCaseGroup`). It is **never** a `SENSOR_INFO_ACTIVE_ARRAY_SIZE`-
 * space region, so it cannot answer "which part of the sensor maps into this buffer" — treating it
 * as such was CAM-2c's own scope guard against ("do not pretend it is active-array crop metadata").
 * `ImageInfo.getSensorToBufferTransformMatrix()` is the real, stable, official CameraX API for that
 * question instead: its own contract documents a mapping "from the value of
 * `CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE` to `(0, 0, image.getWidth, image.getHeight)`"
 * — present in `camera-core` since at least `1.1.0-beta01` (well before this project's
 * `androidx.camera:camera-core:1.3.4`), populated with a real, non-default value for every bound
 * `ImageAnalysis`/Camera2 session (`UseCase.setSensorToBufferTransformMatrix`), not merely the
 * interface's zero-arg default.
 */
internal class ImageProxyFrameMetadataSource(
    private val imageProxy: ImageProxy,
) : CloseableFrameMetadataSource {
    override val timestampNanos: Long get() = imageProxy.imageInfo.timestamp
    override val widthPx: Int get() = imageProxy.width
    override val heightPx: Int get() = imageProxy.height
    override val rotationDegrees: Int get() = imageProxy.imageInfo.rotationDegrees
    override val cropRectLeftPx: Int get() = imageProxy.cropRect.left
    override val cropRectTopPx: Int get() = imageProxy.cropRect.top
    override val cropRectRightPx: Int get() = imageProxy.cropRect.right
    override val cropRectBottomPx: Int get() = imageProxy.cropRect.bottom
    override val sensorToBufferTransform: SensorToBufferMatrix3?
        get() = androidMatrixToSensorToBufferTransform(imageProxy.imageInfo.sensorToBufferTransformMatrix)

    override fun close() = imageProxy.close()
}
