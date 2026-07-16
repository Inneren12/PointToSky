package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
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

    // --- CAM-2c fix round 2 §1/§2/§3: non-zero active-array origin, exact principal-point values ---

    /**
     * A non-zero-origin active array [100,50]-[4132,3074] (4032x3024, same size as the zero-origin
     * fixture above, shifted) mapped onto the 640x480 buffer with no crop beyond the origin shift
     * itself: `bufferX = (activeX − 100) · sx`, `bufferY = (activeY − 50) · sy`. Used to prove the
     * principal point is translated into this matrix's own (sensor matrix space, i.e. *not*
     * rectangle-local) coordinates before composition (CAM-2c fix round 2 §1) — omitting that
     * translation would silently reuse `activeLeft`/`activeTop` as if they were `0`.
     */
    private val originSx = bufferWidthPx / 4032.0
    private val originSy = bufferHeightPx / 3024.0
    private val nonZeroOriginTransform =
        SensorToBufferMatrix3(
            m00 = originSx, m01 = 0.0, m02 = -(100.0 * originSx),
            m10 = 0.0, m11 = originSy, m12 = -(50.0 * originSy),
            m20 = 0.0, m21 = 0.0, m22 = 1.0,
        )

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
    fun `a non-zero-origin active array with no calibration resolves the focal-length-derived centre to the exact buffer centre`() {
        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(nonZeroOriginSourceOf(), nonZeroOriginTransform, bufferWidthPx, bufferHeightPx),
            )

        assertEquals(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT, resolved.intrinsics.quality)
        // Active-array centre in sensor matrix space is (100 + 4032/2, 50 + 3024/2) = (2116, 1562);
        // (2116-100)*sx = 2016*sx = 320.0 exactly, (1562-50)*sy = 1512*sy = 240.0 exactly.
        assertEquals(320.0, resolved.intrinsics.principalPointXPx!!, eps)
        assertEquals(240.0, resolved.intrinsics.principalPointYPx!!, eps)
    }

    @Test
    fun `a non-zero active array origin with a matching non-zero pre-correction origin yields CALIBRATED quality and the exact translated centre`() {
        // Calibration cx/cy = the pre-correction rectangle's own local centre (2016, 1512).
        val calibration = floatArrayOf(2760.3f, 2760.3f, 2016.0f, 1512.0f, 0f)

        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(
                    nonZeroOriginSourceOf(calibration),
                    nonZeroOriginTransform,
                    bufferWidthPx,
                    bufferHeightPx,
                ),
            )

        assertEquals(CameraIntrinsicsQuality.CALIBRATED, resolved.intrinsics.quality)
        // Same absolute centre as the focal-derived case above: (100+2016, 50+1512) = (2116, 1562).
        assertEquals(320.0, resolved.intrinsics.principalPointXPx!!, eps)
        assertEquals(240.0, resolved.intrinsics.principalPointYPx!!, eps)
    }

    @Test
    fun `an off-centre calibrated principal point translates to the exact expected buffer point`() {
        // Local (rectangle-relative) calibration cx/cy = (1000, 800), not the centre.
        val calibration = floatArrayOf(2760.3f, 2760.3f, 1000.0f, 800.0f, 0f)

        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(
                    nonZeroOriginSourceOf(calibration),
                    nonZeroOriginTransform,
                    bufferWidthPx,
                    bufferHeightPx,
                ),
            )

        // Absolute (sensor-matrix-space) centre: (100+1000, 50+800) = (1100, 850).
        // Buffer point: (1100-100)*sx = 1000*sx, (850-50)*sy = 800*sy.
        assertEquals(1000.0 * originSx, resolved.intrinsics.principalPointXPx!!, eps)
        assertEquals(800.0 * originSy, resolved.intrinsics.principalPointYPx!!, eps)
    }

    @Test
    fun `omitting the active array origin would silently produce a different, wrong principal point - the fix is not a no-op`() {
        // Same fixture as "an off-centre calibrated principal point translates to the exact expected
        // buffer point" above: local calibration cx = 1000.0, active array left = 100.0.
        val calibration = floatArrayOf(2760.3f, 2760.3f, 1000.0f, 800.0f, 0f)

        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(
                    nonZeroOriginSourceOf(calibration),
                    nonZeroOriginTransform,
                    bufferWidthPx,
                    bufferHeightPx,
                ),
            )

        // The pre-fix defect read calibration[2] (1000.0) directly as cxPx, without adding
        // activeLeft (100.0) first, then composed it through the SAME matrix: bufferCx = m00*1000.0 +
        // m02 = originSx*1000.0 - originSx*100.0 = originSx*900.0 - a different, wrong buffer point.
        val preFixWrongBufferCx = originSx * (1000.0 - 100.0)
        // The fixed result instead translates to the absolute (1000+100=1100) point first: bufferCx =
        // originSx*(1100.0-100.0) = originSx*1000.0.
        val fixedBufferCx = originSx * 1000.0
        assertEquals(fixedBufferCx, resolved.intrinsics.principalPointXPx!!, eps)
        assertTrue(
            kotlin.math.abs(preFixWrongBufferCx - resolved.intrinsics.principalPointXPx!!) > 1.0,
            "the pre-fix (un-translated) computation ($preFixWrongBufferCx) must differ measurably " +
                "from the fixed one (${resolved.intrinsics.principalPointXPx})",
        )
    }

    @Test
    fun `the inverse-crop region recovers the exact non-zero-origin active array rectangle, not a zero-based one`() {
        val resolved =
            assertIs<AnalysisBufferIntrinsicsResolution.Resolved>(
                resolveAnalysisBufferIntrinsics(nonZeroOriginSourceOf(), nonZeroOriginTransform, bufferWidthPx, bufferHeightPx),
            )

        assertEquals(100.0, resolved.diagnostics.cropLeftPx, eps)
        assertEquals(50.0, resolved.diagnostics.cropTopPx, eps)
        assertEquals(4132.0, resolved.diagnostics.cropRightPx, eps)
        assertEquals(3074.0, resolved.diagnostics.cropBottomPx, eps)
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
                resolveAnalysisBufferIntrinsics(source, nonZeroOriginTransform, bufferWidthPx, bufferHeightPx),
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
