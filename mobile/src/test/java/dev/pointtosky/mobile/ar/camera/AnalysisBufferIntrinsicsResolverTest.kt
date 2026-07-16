package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

/**
 * Pure JVM tests for [resolveAnalysisBufferIntrinsics] and [resolveCameraIntrinsicsPreferringCalibration]
 * (CAM-2c). Uses a fake [CameraCharacteristicsSource] — a Pixel-9-like fixture, same round-number
 * active array/sensor/focal-length combination as the `:core:astro-core`
 * `CalibratedAnalysisBufferProjectionTest` fixture — so resolution logic is exercised without a real
 * camera, CameraX binding, or Robolectric.
 */
class AnalysisBufferIntrinsicsResolverTest {
    private val eps = 1e-6
    private val bufferWidthPx = 640
    private val bufferHeightPx = 480

    /** Maps the full 4032x3024 active array onto the 640x480 buffer: a pure uniform 0.15873x downscale, no crop. */
    private val fullFrameTransform =
        SensorToBufferMatrix3(
            m00 = bufferWidthPx / 4032.0, m01 = 0.0, m02 = 0.0,
            m10 = 0.0, m11 = bufferHeightPx / 3024.0, m12 = 0.0,
            m20 = 0.0, m21 = 0.0, m22 = 1.0,
        )

    private fun sourceOf(
        focalLengthsMm: FloatArray? = floatArrayOf(3.6f),
        sensorWidthMm: Float? = 6.4f,
        sensorHeightMm: Float? = 4.8f,
        activeArrayLeftPx: Int? = 0,
        activeArrayTopPx: Int? = 0,
        activeArrayRightPx: Int? = 4032,
        activeArrayBottomPx: Int? = 3024,
        pixelArrayWidthPx: Int? = 4032,
        pixelArrayHeightPx: Int? = 3024,
        preCorrectionActiveArrayLeftPx: Int? = null,
        preCorrectionActiveArrayTopPx: Int? = null,
        preCorrectionActiveArrayRightPx: Int? = null,
        preCorrectionActiveArrayBottomPx: Int? = null,
        lensIntrinsicCalibration: FloatArray? = null,
        isLogicalMultiCamera: Boolean = false,
        cameraId: String? = null,
        physicalCameraIds: Set<String>? = null,
    ) = CameraCharacteristicsSource {
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = focalLengthsMm,
            sensorPhysicalWidthMm = sensorWidthMm,
            sensorPhysicalHeightMm = sensorHeightMm,
            activeArrayLeftPx = activeArrayLeftPx,
            activeArrayTopPx = activeArrayTopPx,
            activeArrayRightPx = activeArrayRightPx,
            activeArrayBottomPx = activeArrayBottomPx,
            pixelArrayWidthPx = pixelArrayWidthPx,
            pixelArrayHeightPx = pixelArrayHeightPx,
            preCorrectionActiveArrayLeftPx = preCorrectionActiveArrayLeftPx,
            preCorrectionActiveArrayTopPx = preCorrectionActiveArrayTopPx,
            preCorrectionActiveArrayRightPx = preCorrectionActiveArrayRightPx,
            preCorrectionActiveArrayBottomPx = preCorrectionActiveArrayBottomPx,
            lensIntrinsicCalibration = lensIntrinsicCalibration,
            isLogicalMultiCamera = isLogicalMultiCamera,
            cameraId = cameraId,
            physicalCameraIds = physicalCameraIds,
        )
    }

    private fun resolve(
        source: CameraCharacteristicsSource,
        transform: SensorToBufferMatrix3? = fullFrameTransform,
    ) = resolveAnalysisBufferIntrinsics(source, transform, bufferWidthPx, bufferHeightPx)

    // --- Resolved path (no LENS_INTRINSIC_CALIBRATION - focal-length derived) ---

    @Test
    fun `fully valid metadata resolves a calibrated AnalysisBuffer intrinsics with APPROXIMATE_PRINCIPAL_POINT quality`() {
        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(sourceOf()))

        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, resolved.intrinsics.source)
        assertEquals(CameraIntrinsicsReference.AnalysisBuffer(bufferWidthPx, bufferHeightPx), resolved.intrinsics.reference)
        assertEquals(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT, resolved.intrinsics.quality)
        // No crop, uniform scale -> the active array's own geometric centre maps to the buffer's centre.
        assertEquals(bufferWidthPx / 2.0, resolved.intrinsics.principalPointXPx!!, eps)
        assertEquals(bufferHeightPx / 2.0, resolved.intrinsics.principalPointYPx!!, eps)
    }

    @Test
    fun `an asymmetric crop shifts the resolved principal point off the buffer centre`() {
        // Crop the right/bottom half of the active array (2016..4032, 1512..3024) onto the same buffer.
        val sx = bufferWidthPx / 2016.0
        val sy = bufferHeightPx / 1512.0
        val croppedTransform =
            SensorToBufferMatrix3(
                m00 = sx, m01 = 0.0, m02 = -(2016.0 * sx),
                m10 = 0.0, m11 = sy, m12 = -(1512.0 * sy),
                m20 = 0.0, m21 = 0.0, m22 = 1.0,
            )

        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(sourceOf(), croppedTransform))

        // The active-array centre (2016, 1512) is now the crop's own top-left corner, not its centre.
        assertEquals(0.0, resolved.intrinsics.principalPointXPx!!, eps)
        assertEquals(0.0, resolved.intrinsics.principalPointYPx!!, eps)
    }

    // --- Resolved path with a usable, coordinate-space-verified LENS_INTRINSIC_CALIBRATION ---

    @Test
    fun `a usable LENS_INTRINSIC_CALIBRATION with no pre-correction array is preferred and yields CALIBRATED quality`() {
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f, 0f)
        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(sourceOf(lensIntrinsicCalibration = calibration)))

        assertEquals(CameraIntrinsicsQuality.CALIBRATED, resolved.intrinsics.quality)
        val expectedCx = 2100.0 * fullFrameTransform.m00
        val expectedCy = 1400.0 * fullFrameTransform.m11
        assertEquals(expectedCx, resolved.intrinsics.principalPointXPx!!, eps)
        assertEquals(expectedCy, resolved.intrinsics.principalPointYPx!!, eps)
    }

    @Test
    fun `a usable LENS_INTRINSIC_CALIBRATION with a matching pre-correction array is also preferred`() {
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f, 0f)
        val source =
            sourceOf(
                lensIntrinsicCalibration = calibration,
                preCorrectionActiveArrayLeftPx = 0,
                preCorrectionActiveArrayTopPx = 0,
                preCorrectionActiveArrayRightPx = 4032,
                preCorrectionActiveArrayBottomPx = 3024,
            )

        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(source))
        assertEquals(CameraIntrinsicsQuality.CALIBRATED, resolved.intrinsics.quality)
    }

    @Test
    fun `a mismatched pre-correction active array rejects LENS_INTRINSIC_CALIBRATION and falls back to focal-length derivation`() {
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f, 0f)
        val source =
            sourceOf(
                lensIntrinsicCalibration = calibration,
                // A different (larger, shifted) pre-correction array - the coordinate space cannot
                // be verified against SENSOR_INFO_ACTIVE_ARRAY_SIZE, so the calibration must not be
                // trusted directly.
                preCorrectionActiveArrayLeftPx = 10,
                preCorrectionActiveArrayTopPx = 10,
                preCorrectionActiveArrayRightPx = 4042,
                preCorrectionActiveArrayBottomPx = 3034,
            )

        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(source))

        assertEquals(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT, resolved.intrinsics.quality)
        assertEquals(bufferWidthPx / 2.0, resolved.intrinsics.principalPointXPx!!, eps)
    }

    @Test
    fun `an invalid LENS_INTRINSIC_CALIBRATION array size or non-positive fx-fy falls back to focal-length derivation`() {
        val wrongSize = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f) // only 4 elements, not 5
        val nonPositiveFx = floatArrayOf(0f, 2760.3f, 2100.0f, 1400.0f, 0f)

        listOf(wrongSize, nonPositiveFx).forEach { bad ->
            val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(sourceOf(lensIntrinsicCalibration = bad)))
            assertEquals(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT, resolved.intrinsics.quality)
        }
    }

    // --- CAM-2c fix §P2: focal-derived fx/fy come from SENSOR_INFO_PIXEL_ARRAY_SIZE, not the active array ---

    @Test
    fun `the focal-derived fallback uses the pixel array, not the active array, for fx and fy when they differ`() {
        // Pixel array (4100x3100) larger than this fixture's usual 4032x3024 active array - a real,
        // documented Camera2 possibility (optically black/inactive border pixels excluded from the
        // active array).
        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolve(sourceOf(pixelArrayWidthPx = 4100, pixelArrayHeightPx = 3100)),
            )

        assertEquals(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT, resolved.intrinsics.quality)
        // sourceOf()'s focalLengthsMm/sensorWidthMm/sensorHeightMm are Float (matching real Camera2
        // metadata), converted via .toDouble() inside the resolver - so the expected value here must
        // go through the exact same Float->Double conversion, not the raw Double literals, to avoid a
        // spurious sub-epsilon mismatch from Float's reduced precision.
        val expectedActiveFx = 3.6f.toDouble() / 6.4f.toDouble() * 4100
        val expectedActiveFy = 3.6f.toDouble() / 4.8f.toDouble() * 3100
        assertEquals(expectedActiveFx, resolved.diagnostics.activeFxPx, eps)
        assertEquals(expectedActiveFy, resolved.diagnostics.activeFyPx, eps)
        assertEquals(
            CameraCalibrationDiagnostics.FOCAL_DERIVATION_BASIS_PIXEL_ARRAY,
            resolved.diagnostics.focalDerivationBasis,
        )
        // The old (incorrect) active-array-based formula would have produced measurably different,
        // smaller fx/fy values - explicitly assert this result is NOT that old value.
        val oldWrongActiveFx = 3.6f.toDouble() / 6.4f.toDouble() * 4032
        val oldWrongActiveFy = 3.6f.toDouble() / 4.8f.toDouble() * 3024
        assertTrue(kotlin.math.abs(oldWrongActiveFx - resolved.diagnostics.activeFxPx) > 1.0)
        assertTrue(kotlin.math.abs(oldWrongActiveFy - resolved.diagnostics.activeFyPx) > 1.0)
    }

    @Test
    fun `an off-axis ray projects using the pixel-array-derived focal length after the sensor-to-buffer mapping`() {
        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolve(sourceOf(pixelArrayWidthPx = 4100, pixelArrayHeightPx = 3100)),
            )

        val model =
            PinholeProjectionModel(
                focalLengthXPx = resolved.diagnostics.bufferFxPx,
                focalLengthYPx = resolved.diagnostics.bufferFyPx,
                principalPointXPx = resolved.diagnostics.bufferCxPx,
                principalPointYPx = resolved.diagnostics.bufferCyPx,
                imageWidthPx = bufferWidthPx.toDouble(),
                imageHeightPx = bufferHeightPx.toDouble(),
            )
        val projected = model.project(normalizedX = 0.1, normalizedY = 0.05)

        // fullFrameTransform's own scale (bufferWidthPx/4032.0, bufferHeightPx/3024.0 - the ACTIVE
        // array's own width/height, since the matrix's domain is active-array-local) composes with
        // the pixel-array-derived active fx/fy. Float->Double conversion matched to the resolver's
        // own path - see the comment in the previous test.
        val expectedBufferFx = (3.6f.toDouble() / 6.4f.toDouble() * 4100) * (bufferWidthPx / 4032.0)
        val expectedBufferFy = (3.6f.toDouble() / 4.8f.toDouble() * 3100) * (bufferHeightPx / 3024.0)
        val expectedX = expectedBufferFx * 0.1 + resolved.diagnostics.bufferCxPx
        val expectedY = expectedBufferFy * 0.05 + resolved.diagnostics.bufferCyPx
        assertEquals(expectedX, projected.x, eps)
        assertEquals(expectedY, projected.y, eps)

        // The old, active-array-derived focal length would have projected this same ray to a
        // measurably different pixel.
        val oldWrongBufferFx = (3.6f.toDouble() / 6.4f.toDouble() * 4032) * (bufferWidthPx / 4032.0)
        val oldWrongX = oldWrongBufferFx * 0.1 + resolved.diagnostics.bufferCxPx
        assertTrue(kotlin.math.abs(oldWrongX - projected.x) > 0.1)
    }

    @Test
    fun `a missing or invalid pixel array size is reported as MissingPixelArraySize when the focal-derived fallback is required`() {
        assertEquals(
            AnalysisBufferIntrinsicsResolution.MissingPixelArraySize,
            resolve(sourceOf(pixelArrayWidthPx = null)),
        )
        assertEquals(
            AnalysisBufferIntrinsicsResolution.MissingPixelArraySize,
            resolve(sourceOf(pixelArrayHeightPx = null)),
        )
        assertEquals(
            AnalysisBufferIntrinsicsResolution.MissingPixelArraySize,
            resolve(sourceOf(pixelArrayWidthPx = 0)),
        )
        assertEquals(
            AnalysisBufferIntrinsicsResolution.MissingPixelArraySize,
            resolve(sourceOf(pixelArrayHeightPx = -1)),
        )
    }

    @Test
    fun `a usable LENS_INTRINSIC_CALIBRATION resolves even when the pixel array size is missing`() {
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f, 0f)
        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolve(sourceOf(lensIntrinsicCalibration = calibration, pixelArrayWidthPx = null, pixelArrayHeightPx = null)),
            )

        assertEquals(CameraIntrinsicsQuality.CALIBRATED, resolved.intrinsics.quality)
        assertEquals(
            CameraCalibrationDiagnostics.FOCAL_DERIVATION_BASIS_LENS_INTRINSIC_CALIBRATION,
            resolved.diagnostics.focalDerivationBasis,
        )
        assertNull(resolved.diagnostics.pixelArrayWidthPx)
        assertNull(resolved.diagnostics.pixelArrayHeightPx)
    }

    @Test
    fun `a non-zero active array origin still resolves the pixel-array-derived focal length with cx-cy remaining active-array-local`() {
        // Same non-zero-origin active array [100,50]-[4132,3074] and pixel array (4100x3100) as the
        // standalone pixel-array-larger-than-active-array test above - combined here to prove §P1
        // (coordinate-origin) and §P2 (pixel-pitch) compose independently: pixel-array dimensions
        // affect fx/fy only, active-array-local dimensions affect cx/cy only, and activeLeft/activeTop
        // affect neither.
        val source =
            sourceOf(
                activeArrayLeftPx = 100,
                activeArrayTopPx = 50,
                activeArrayRightPx = 4132,
                activeArrayBottomPx = 3074,
                pixelArrayWidthPx = 4100,
                pixelArrayHeightPx = 3100,
            )
        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(source, fullFrameTransform))

        val expectedActiveFx = 3.6f.toDouble() / 6.4f.toDouble() * 4100
        val expectedActiveFy = 3.6f.toDouble() / 4.8f.toDouble() * 3100
        assertEquals(expectedActiveFx, resolved.diagnostics.activeFxPx, eps)
        assertEquals(expectedActiveFy, resolved.diagnostics.activeFyPx, eps)
        // cx/cy remain the active array's own local centre (4032/2, 3024/2) - never activeLeft/Top
        // translated, and unaffected by the pixel array's own, larger size.
        assertEquals(2016.0, resolved.diagnostics.activeCxPx, eps)
        assertEquals(1512.0, resolved.diagnostics.activeCyPx, eps)
        assertEquals(
            CameraCalibrationDiagnostics.PRINCIPAL_POINT_BASIS_ACTIVE_ARRAY_LOCAL,
            resolved.diagnostics.principalPointBasis,
        )
        assertEquals(
            CameraCalibrationDiagnostics.FOCAL_DERIVATION_BASIS_PIXEL_ARRAY,
            resolved.diagnostics.focalDerivationBasis,
        )
    }

    // --- Typed failure outcomes ---

    @Test
    fun `a logical multi-camera is rejected outright, before any other metadata is trusted`() {
        assertEquals(
            AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping(null, null),
            resolve(sourceOf(isLogicalMultiCamera = true)),
        )
    }

    @Test
    fun `a logical multi-camera rejection carries the camera id and physical camera ids for diagnostics`() {
        val result =
            resolve(
                sourceOf(isLogicalMultiCamera = true, cameraId = "0", physicalCameraIds = setOf("2", "3")),
            )
        assertEquals(
            AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping("0", setOf("2", "3")),
            result,
        )
    }

    @Test
    fun `a missing active array size is reported as MissingActiveArray`() {
        assertEquals(AnalysisBufferIntrinsicsResolution.MissingActiveArray, resolve(sourceOf(activeArrayLeftPx = null)))
        assertEquals(AnalysisBufferIntrinsicsResolution.MissingActiveArray, resolve(sourceOf(activeArrayRightPx = null)))
    }

    @Test
    fun `a degenerate active array size (right less than or equal to left) is reported as MissingActiveArray`() {
        assertEquals(
            AnalysisBufferIntrinsicsResolution.MissingActiveArray,
            resolve(sourceOf(activeArrayLeftPx = 100, activeArrayRightPx = 100)),
        )
        assertEquals(
            AnalysisBufferIntrinsicsResolution.MissingActiveArray,
            resolve(sourceOf(activeArrayLeftPx = 200, activeArrayRightPx = 100)),
        )
    }

    @Test
    fun `a missing or invalid physical sensor size is reported as MissingPhysicalSensorSize`() {
        assertEquals(AnalysisBufferIntrinsicsResolution.MissingPhysicalSensorSize, resolve(sourceOf(sensorWidthMm = null)))
        assertEquals(AnalysisBufferIntrinsicsResolution.MissingPhysicalSensorSize, resolve(sourceOf(sensorHeightMm = 0f)))
        assertEquals(AnalysisBufferIntrinsicsResolution.MissingPhysicalSensorSize, resolve(sourceOf(sensorWidthMm = Float.NaN)))
    }

    @Test
    fun `a missing, empty, all-invalid, or ambiguous focal length array is reported as MissingFocalLength`() {
        assertEquals(AnalysisBufferIntrinsicsResolution.MissingFocalLength, resolve(sourceOf(focalLengthsMm = null)))
        assertEquals(AnalysisBufferIntrinsicsResolution.MissingFocalLength, resolve(sourceOf(focalLengthsMm = floatArrayOf())))
        assertEquals(
            AnalysisBufferIntrinsicsResolution.MissingFocalLength,
            resolve(sourceOf(focalLengthsMm = floatArrayOf(2.0f, 6.0f))),
        )
    }

    @Test
    fun `a null sensor-to-buffer transform is reported as MissingSensorToBufferTransform`() {
        assertEquals(AnalysisBufferIntrinsicsResolution.MissingSensorToBufferTransform, resolve(sourceOf(), null))
    }

    @Test
    fun `an exception reading characteristics is reported as InvalidMetadata`() {
        val throwing = CameraCharacteristicsSource { throw IllegalStateException("camera service unavailable") }
        val result = assertIs<AnalysisBufferIntrinsicsResolution.InvalidMetadata>(resolve(throwing))
        assertEquals(AnalysisBufferIntrinsicsInvalidMetadataReason.CHARACTERISTICS_UNAVAILABLE, result.reason)
    }

    @Test
    fun `CancellationException reading characteristics propagates instead of being reported`() {
        val source = CameraCharacteristicsSource { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { resolve(source) }
    }

    @Test
    fun `a crop region outside the active array bounds is reported as InvalidMetadata`() {
        // A transform whose inverted crop region extends past the 4032-wide active array.
        val badTransform =
            SensorToBufferMatrix3(
                m00 = bufferWidthPx / 5000.0, m01 = 0.0, m02 = 0.0,
                m10 = 0.0, m11 = bufferHeightPx / 3024.0, m12 = 0.0,
                m20 = 0.0, m21 = 0.0, m22 = 1.0,
            )
        val result = assertIs<AnalysisBufferIntrinsicsResolution.InvalidMetadata>(resolve(sourceOf(), badTransform))
        assertEquals(AnalysisBufferIntrinsicsInvalidMetadataReason.CROP_REGION_INVALID, result.reason)
    }

    // --- CAM-2c fix §1: unsupported sensor-to-buffer transform classes ---

    @Test
    fun `a mirrored sensor-to-buffer matrix is reported as UnsupportedSensorToBufferTransform`() {
        val mirrored =
            SensorToBufferMatrix3(
                m00 = -(bufferWidthPx / 4032.0), m01 = 0.0, m02 = bufferWidthPx.toDouble(),
                m10 = 0.0, m11 = bufferHeightPx / 3024.0, m12 = 0.0,
                m20 = 0.0, m21 = 0.0, m22 = 1.0,
            )
        val result = assertIs<AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform>(resolve(sourceOf(), mirrored))
        assertEquals(SensorToBufferTransformClass.MIRRORED, result.transformClass)
    }

    @Test
    fun `a general-affine (sheared) sensor-to-buffer matrix is reported as UnsupportedSensorToBufferTransform`() {
        val sheared = SensorToBufferMatrix3(2.0, 1.0, 0.0, 1.0, 2.0, 0.0, 0.0, 0.0, 1.0)
        val result = assertIs<AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform>(resolve(sourceOf(), sheared))
        assertEquals(SensorToBufferTransformClass.GENERAL_AFFINE_UNSUPPORTED, result.transformClass)
    }

    @Test
    fun `a projective sensor-to-buffer matrix is reported as UnsupportedSensorToBufferTransform`() {
        val projective = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.01, 0.0, 1.0)
        val result = assertIs<AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform>(resolve(sourceOf(), projective))
        assertEquals(SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED, result.transformClass)
    }

    // --- CAM-2c fix round 3 §P1: a non-zero active-array origin never affects K composition ---

    /**
     * A non-zero-origin active array [100,50]-[4132,3074] (4032x3024, same size as the zero-origin
     * fixture above, shifted within the full pixel array). Per Android's own Camera2 contract (see
     * [dev.pointtosky.core.astro.projection.camera.ActiveArrayIntrinsics]'s KDoc for the AOSP-sourced
     * evidence), `LENS_INTRINSIC_CALIBRATION` and the CameraX sensor-to-buffer matrix's own domain are
     * both **active-array-local** — `(0, 0)` at this rectangle's own top-left — never translated by
     * `activeArrayLeftPx`/`activeArrayTopPx`. The matrix mapping this active array onto the buffer is
     * therefore [fullFrameTransform] itself, byte-for-byte identical to the zero-origin case: a
     * non-zero origin changes nothing about the matrix or the composed principal point. (A prior,
     * incorrect revision of this fix instead built a distinct `nonZeroOriginTransform` and added
     * `activeLeft`/`activeTop` to the principal point before composing — see the regression test
     * below for why that was wrong.)
     */
    private fun nonZeroOriginSourceOf(lensIntrinsicCalibration: FloatArray? = null) =
        sourceOf(
            activeArrayLeftPx = 100,
            activeArrayTopPx = 50,
            activeArrayRightPx = 4132,
            activeArrayBottomPx = 3074,
            lensIntrinsicCalibration = lensIntrinsicCalibration,
            preCorrectionActiveArrayLeftPx = if (lensIntrinsicCalibration != null) 100 else null,
            preCorrectionActiveArrayTopPx = if (lensIntrinsicCalibration != null) 50 else null,
            preCorrectionActiveArrayRightPx = if (lensIntrinsicCalibration != null) 4132 else null,
            preCorrectionActiveArrayBottomPx = if (lensIntrinsicCalibration != null) 3074 else null,
        )

    @Test
    fun `a non-zero-origin active array with no calibration resolves the focal-length-derived centre to the exact buffer centre, identically to the zero-origin case`() {
        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(nonZeroOriginSourceOf(), fullFrameTransform, bufferWidthPx, bufferHeightPx),
            )

        assertEquals(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT, resolved.intrinsics.quality)
        // Active-array-local centre is (4032/2, 3024/2) regardless of this rectangle's (100,50)
        // placement within the full pixel array - the exact same buffer centre as the zero-origin
        // fixture's own centred-projection test above.
        assertEquals(bufferWidthPx / 2.0, resolved.intrinsics.principalPointXPx!!, eps)
        assertEquals(bufferHeightPx / 2.0, resolved.intrinsics.principalPointYPx!!, eps)
    }

    @Test
    fun `a non-zero active array origin with a matching non-zero pre-correction origin yields CALIBRATED quality and the exact local centre`() {
        // Calibration cx/cy = the active-array-local centre (2016, 1512) - read directly, never
        // translated by activeArrayLeftPx/TopPx.
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2016.0f, 1512.0f, 0f)

        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(
                    nonZeroOriginSourceOf(calibration),
                    fullFrameTransform,
                    bufferWidthPx,
                    bufferHeightPx,
                ),
            )

        assertEquals(CameraIntrinsicsQuality.CALIBRATED, resolved.intrinsics.quality)
        assertEquals(bufferWidthPx / 2.0, resolved.intrinsics.principalPointXPx!!, eps)
        assertEquals(bufferHeightPx / 2.0, resolved.intrinsics.principalPointYPx!!, eps)
    }

    @Test
    fun `an off-centre calibrated principal point maps to the exact expected buffer point with no origin translation`() {
        // Active-array-local calibration cx/cy = (1000, 800), not the centre.
        val calibration = floatArrayOf(2760.3f, 2760.3f, 1000.0f, 800.0f, 0f)

        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(
                    nonZeroOriginSourceOf(calibration),
                    fullFrameTransform,
                    bufferWidthPx,
                    bufferHeightPx,
                ),
            )

        // fullFrameTransform is a pure scale (no translate term): bufferCx = 1000*sx, bufferCy =
        // 800*sy - identical formula to the zero-origin case, since the active array's own (100,50)
        // placement within the full pixel array never enters this computation.
        val sx = bufferWidthPx / 4032.0
        val sy = bufferHeightPx / 3024.0
        assertEquals(1000.0 * sx, resolved.intrinsics.principalPointXPx!!, eps)
        assertEquals(800.0 * sy, resolved.intrinsics.principalPointYPx!!, eps)
    }

    @Test
    fun `translating the local principal point by activeLeft-activeTop before composition would silently produce a wrong, off-centre buffer point - the fix is not a no-op`() {
        // Same fixture as "an off-centre calibrated principal point maps to the exact expected buffer
        // point" above: active-array-local calibration cx = 1000.0, active array left = 100.0.
        val calibration = floatArrayOf(2760.3f, 2760.3f, 1000.0f, 800.0f, 0f)

        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(
                    nonZeroOriginSourceOf(calibration),
                    fullFrameTransform,
                    bufferWidthPx,
                    bufferHeightPx,
                ),
            )

        val sx = bufferWidthPx / 4032.0
        // The (now-reverted) defect added activeLeft (100.0) to the already-local calibration cx
        // before composing through fullFrameTransform: bufferCx = (1000.0 + 100.0) * sx - a
        // different, wrong buffer point than the correct, untranslated one.
        val wronglyTranslatedBufferCx = (1000.0 + 100.0) * sx
        assertTrue(
            kotlin.math.abs(wronglyTranslatedBufferCx - resolved.intrinsics.principalPointXPx!!) > 1.0,
            "a wrongly origin-translated computation ($wronglyTranslatedBufferCx) must differ " +
                "measurably from the correct, untranslated one (${resolved.intrinsics.principalPointXPx})",
        )
    }

    @Test
    fun `the inverse-crop region recovers the active-array-local rectangle, not the full-pixel-array-relative one`() {
        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(nonZeroOriginSourceOf(), fullFrameTransform, bufferWidthPx, bufferHeightPx),
            )

        // Active-array-local: [0,0]-[4032,3024], identical to the zero-origin case - NOT the
        // full-pixel-array-relative [100,50]-[4132,3074] rectangle a prior (incorrect) revision of
        // this fix expected here.
        assertEquals(0.0, resolved.diagnostics.cropLeftPx, eps)
        assertEquals(0.0, resolved.diagnostics.cropTopPx, eps)
        assertEquals(4032.0, resolved.diagnostics.cropRightPx, eps)
        assertEquals(3024.0, resolved.diagnostics.cropBottomPx, eps)
        // The full-pixel-array-relative ground-truth rectangle (diagnostics/provenance only - see
        // ActiveArrayRect's own KDoc) is still tracked separately and unaffected by this correction.
        assertEquals(100.0, resolved.diagnostics.activeArrayLeftPx, eps)
        assertEquals(50.0, resolved.diagnostics.activeArrayTopPx, eps)
    }

    @Test
    fun `converting a local principal point to its full-pixel-array coordinate is a documented relationship, never fed into K`() {
        // Active-array-local calibration cx/cy = (2016, 1512) - the rectangle's own local centre.
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2016.0f, 1512.0f, 0f)

        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(
                    nonZeroOriginSourceOf(calibration),
                    fullFrameTransform,
                    bufferWidthPx,
                    bufferHeightPx,
                ),
            )

        // The local principal point actually used for K composition (buffer centre, as above).
        assertEquals(bufferWidthPx / 2.0, resolved.intrinsics.principalPointXPx!!, eps)
        // Its equivalent full-pixel-array coordinate - a documented conversion for cross-referencing
        // against raw Camera2 debug dumps, computed here from the diagnostics' own fields, never fed
        // back into the resolver or K composition.
        val fullArrayCx = resolved.diagnostics.activeArrayLeftPx + resolved.diagnostics.activeCxPx
        val fullArrayCy = resolved.diagnostics.activeArrayTopPx + resolved.diagnostics.activeCyPx
        assertEquals(2116.0, fullArrayCx, eps)
        assertEquals(1562.0, fullArrayCy, eps)
    }

    @Test
    fun `a non-zero active array origin with a differently-offset pre-correction origin rejects calibration - same size is not enough`() {
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f, 0f)
        val source =
            sourceOf(
                activeArrayLeftPx = 100,
                activeArrayTopPx = 50,
                activeArrayRightPx = 4132,
                activeArrayBottomPx = 3074,
                lensIntrinsicCalibration = calibration,
                // Same width/height (4032x3024) as the active array above, but a different origin.
                preCorrectionActiveArrayLeftPx = 90,
                preCorrectionActiveArrayTopPx = 40,
                preCorrectionActiveArrayRightPx = 4122,
                preCorrectionActiveArrayBottomPx = 3064,
            )

        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(source, fullFrameTransform, bufferWidthPx, bufferHeightPx),
            )
        assertEquals(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT, resolved.intrinsics.quality)
    }

    // --- CAM-2c fix round 2 §4: rotation ownership is unproven for anything but AXIS_ALIGNED_0 ---

    @Test
    fun `a 90-degree axis-permuted sensor-to-buffer matrix is reported as RotationOwnershipUnproven, not resolved`() {
        // bufferX = activeY * sy', bufferY = (4032 - activeX) * sx' - a genuine 90-degree position
        // rotation mapping the full 4032x3024 active array, with no crop, onto a 480x640 (portrait)
        // buffer. Note this portrait buffer's own width/height (480x640) - not this class's
        // landscape bufferWidthPx/bufferHeightPx (640x480) fields - drive the matrix below; mixing
        // the two up is exactly the kind of bug a genuinely swapped-axis matrix needs distinct
        // width/height scale factors to catch.
        val portraitBufferWidthPx = 480
        val portraitBufferHeightPx = 640
        val matrix =
            SensorToBufferMatrix3(
                m00 = 0.0, m01 = portraitBufferWidthPx / 3024.0, m02 = 0.0,
                m10 = -(portraitBufferHeightPx / 4032.0), m11 = 0.0, m12 = portraitBufferHeightPx.toDouble(),
                m20 = 0.0, m21 = 0.0, m22 = 1.0,
            )
        val result =
            resolveAnalysisBufferIntrinsics(sourceOf(), matrix, portraitBufferWidthPx, portraitBufferHeightPx)
        val unproven = assertIs<AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven>(result)
        assertEquals(SensorToBufferTransformClass.ORTHOGONAL_90, unproven.transformClass)
    }

    @Test
    fun `a 180-degree sensor-to-buffer matrix is also reported as RotationOwnershipUnproven`() {
        val matrix =
            SensorToBufferMatrix3(
                m00 = -(bufferWidthPx / 4032.0), m01 = 0.0, m02 = bufferWidthPx.toDouble(),
                m10 = 0.0, m11 = -(bufferHeightPx / 3024.0), m12 = bufferHeightPx.toDouble(),
                m20 = 0.0, m21 = 0.0, m22 = 1.0,
            )
        val result = resolve(sourceOf(), matrix)
        val unproven = assertIs<AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven>(result)
        assertEquals(SensorToBufferTransformClass.ORTHOGONAL_180, unproven.transformClass)
    }

    @Test
    fun `a 270-degree axis-permuted sensor-to-buffer matrix is also reported as RotationOwnershipUnproven`() {
        // The other 90-degree-like permutation (m01 less than 0, m10 greater than 0 - see
        // classifySensorToBufferMatrix's own worked example) mapping the full 4032x3024 active array
        // onto a 480x640 (portrait) buffer. As with the ORTHOGONAL_90 case, this portrait buffer's own
        // width/height (480x640) drive the matrix, not this class's landscape bufferWidthPx/bufferHeightPx.
        val portraitBufferWidthPx = 480
        val portraitBufferHeightPx = 640
        val matrix =
            SensorToBufferMatrix3(
                m00 = 0.0, m01 = -(portraitBufferWidthPx / 3024.0), m02 = portraitBufferWidthPx.toDouble(),
                m10 = portraitBufferHeightPx / 4032.0, m11 = 0.0, m12 = 0.0,
                m20 = 0.0, m21 = 0.0, m22 = 1.0,
            )
        val result =
            resolveAnalysisBufferIntrinsics(sourceOf(), matrix, portraitBufferWidthPx, portraitBufferHeightPx)
        val unproven = assertIs<AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven>(result)
        assertEquals(SensorToBufferTransformClass.ORTHOGONAL_270, unproven.transformClass)
    }

    // --- CAM-2c fix §3: LENS_INTRINSIC_CALIBRATION skew tolerance ---

    @Test
    fun `calibration skew within tolerance is used as CALIBRATED`() {
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f, 0.1f)
        assertTrue(kotlin.math.abs(calibration[4]) <= INTRINSIC_SKEW_TOLERANCE_PX)

        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(sourceOf(lensIntrinsicCalibration = calibration)))
        assertEquals(CameraIntrinsicsQuality.CALIBRATED, resolved.intrinsics.quality)
        assertNull(resolved.diagnostics.skewDiagnosticReason)
    }

    @Test
    fun `calibration skew exceeding tolerance rejects the calibrated K and records a diagnostic reason`() {
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f, 5.0f)
        assertTrue(kotlin.math.abs(calibration[4]) > INTRINSIC_SKEW_TOLERANCE_PX)

        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(sourceOf(lensIntrinsicCalibration = calibration)))
        assertEquals(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT, resolved.intrinsics.quality)
        assertEquals(CameraCalibrationDiagnosticReason.NON_ZERO_INTRINSIC_SKEW, resolved.diagnostics.skewDiagnosticReason)
        assertEquals(bufferWidthPx / 2.0, resolved.intrinsics.principalPointXPx!!, eps)
    }

    @Test
    fun `negative calibration skew exceeding tolerance is also rejected`() {
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f, -5.0f)
        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(sourceOf(lensIntrinsicCalibration = calibration)))
        assertEquals(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT, resolved.intrinsics.quality)
    }

    @Test
    fun `zero calibration skew is used as CALIBRATED`() {
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f, 0.0f)
        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(sourceOf(lensIntrinsicCalibration = calibration)))
        assertEquals(CameraIntrinsicsQuality.CALIBRATED, resolved.intrinsics.quality)
    }

    @Test
    fun `tiny float-noise calibration skew is still used as CALIBRATED`() {
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2100.0f, 1400.0f, 1e-4f)
        val resolved = assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(resolve(sourceOf(lensIntrinsicCalibration = calibration)))
        assertEquals(CameraIntrinsicsQuality.CALIBRATED, resolved.intrinsics.quality)
    }

    // --- resolveCameraIntrinsicsPreferringCalibration: calibrated-first orchestration ---

    @Test
    fun `preferring calibration returns the calibrated AnalysisBuffer result when it resolves`() {
        val result = resolveCameraIntrinsicsPreferringCalibration(sourceOf(), fullFrameTransform, bufferWidthPx, bufferHeightPx)

        assertNull(result.fallbackReason)
        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, result.intrinsics.source)
        assertEquals(CameraIntrinsicsReference.AnalysisBuffer(bufferWidthPx, bufferHeightPx), result.intrinsics.reference)
    }

    @Test
    fun `preferring calibration falls back to the CAM-1b PhysicalSensor path when the calibrated mapping is unavailable`() {
        // No sensor-to-buffer transform - the calibrated path cannot proceed, but CAM-1b's
        // focal-length/sensor-size metadata (present here) still resolves.
        val result = resolveCameraIntrinsicsPreferringCalibration(sourceOf(), null, bufferWidthPx, bufferHeightPx)

        assertNull(result.fallbackReason)
        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, result.intrinsics.source)
        assertEquals(CameraIntrinsicsReference.PhysicalSensor, result.intrinsics.reference)
    }

    @Test
    fun `preferring calibration falls back to the legacy fallback when neither path resolves`() {
        val result =
            resolveCameraIntrinsicsPreferringCalibration(
                sourceOf(focalLengthsMm = null),
                null,
                bufferWidthPx,
                bufferHeightPx,
            )

        assertEquals(CameraIntrinsicsSource.LEGACY_FALLBACK, result.intrinsics.source)
        assertEquals(CameraIntrinsicsFallbackReason.NO_VALID_FOCAL_LENGTH, result.fallbackReason)
    }

    @Test
    fun `preferring calibration skips straight to CAM-1b when buffer dimensions are null`() {
        val result = resolveCameraIntrinsicsPreferringCalibration(sourceOf(), fullFrameTransform, null, null)

        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, result.intrinsics.source)
        assertEquals(CameraIntrinsicsReference.PhysicalSensor, result.intrinsics.reference)
    }

    @Test
    fun `preferring calibration never downgrades a logical multi-camera to a calibrated result`() {
        val result =
            resolveCameraIntrinsicsPreferringCalibration(
                sourceOf(isLogicalMultiCamera = true),
                fullFrameTransform,
                bufferWidthPx,
                bufferHeightPx,
            )

        // Falls back to CAM-1b, which does not itself check isLogicalMultiCamera (unchanged CAM-1b
        // behaviour) but still correctly resolves a PhysicalSensor-referenced value here - never an
        // AnalysisBuffer reference built from a logical camera's possibly-wrong-physical-sensor metadata.
        assertEquals(CameraIntrinsicsReference.PhysicalSensor, result.intrinsics.reference)
    }
}
