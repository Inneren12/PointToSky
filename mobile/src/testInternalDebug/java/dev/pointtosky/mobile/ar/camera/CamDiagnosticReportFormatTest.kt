package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `internalDebug`-only pure JVM tests for [buildCamDiagnosticReportText]/[buildCamDiagnosticCompactSummaryText]
 * - deterministic text formatting, every required section header, [Locale.ROOT] number formatting, and
 * the real Pixel 9 logical-multi-camera evidence. Consumes exclusively the immutable
 * [CamDiagnosticSnapshot] DTO tree, never a raw runtime type.
 */
class CamDiagnosticReportFormatTest {
    private val defaultLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private val physicalSensorIntrinsics =
        CameraIntrinsics(
            horizontalFovDeg = 70.7,
            verticalFovDeg = 56.2,
            focalLengthMm = 6.81,
            sensorWidthMm = 9.80,
            sensorHeightMm = 7.35,
            principalPointXPx = null,
            principalPointYPx = null,
            source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            reference = CameraIntrinsicsReference.PhysicalSensor,
        )

    private val pixel9CharacteristicsSnapshot =
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = floatArrayOf(6.81f),
            sensorPhysicalWidthMm = 9.80f,
            sensorPhysicalHeightMm = 7.35f,
            activeArrayLeftPx = 0,
            activeArrayTopPx = 0,
            activeArrayRightPx = 4080,
            activeArrayBottomPx = 3072,
            pixelArrayWidthPx = 4080,
            pixelArrayHeightPx = 3072,
            isLogicalMultiCamera = true,
            cameraId = "0",
            physicalCameraIds = setOf("2", "3", "4"),
        )

    // A synthetic axis-aligned-scale fixture used across most tests below purely to exercise report
    // formatting (a non-identity, non-degenerate matrix). This is NOT the matrix actually observed on a
    // real Pixel 9 - the real, observed sensor-to-buffer matrix was the IDENTITY matrix (see
    // `docs/validation/cam_2c_pixel9_evidence.md`, and `identityMatrixOverThePixel9Domain` below for the
    // dedicated test using that real value).
    private val syntheticScaleFixtureMatrix = SensorToBufferMatrix3(0.15686, 0.0, 0.0, 0.0, 0.15686, 0.0, 0.0, 0.0, 1.0)

    /** The real matrix observed on a Pixel 9 (`docs/validation/cam_2c_pixel9_evidence.md`) - identity,
     * not a scale. */
    private val identityMatrixOverThePixel9Domain = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun pixel9IntrinsicsState(
        attempt: AnalysisBufferIntrinsicsResolution? =
            AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping(
                cameraId = "0",
                physicalCameraIdsForDiagnostics = setOf("2", "3", "4"),
            ),
        published: CoreCameraIntrinsicsResolution? = CoreCameraIntrinsicsResolution.Resolved(physicalSensorIntrinsics),
        characteristics: CameraCharacteristicsSnapshot? = pixel9CharacteristicsSnapshot,
        matrix: SensorToBufferMatrix3? = syntheticScaleFixtureMatrix,
        transformClass: SensorToBufferTransformClass? = SensorToBufferTransformClass.AXIS_ALIGNED_0,
    ) = CameraSessionIntrinsicsDiagnosticState(
        analysisBufferAttempt = attempt,
        publishedIntrinsicsResolution = published,
        coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
        cameraCharacteristicsSnapshot = characteristics,
        frameCounters =
            CameraSessionIntrinsicsFrameCounters(
                framesAnalyzed = 1115L,
                framesWithTransform = 1115L,
                framesWithNullTransform = 0L,
                framesWithUsableTransform = 1115L,
                coordinatorFramesWaited = 1,
                latestFrameTransform = matrix,
                latestFrameTransformClass = transformClass,
            ),
    )

    private fun snapshot(
        state: CameraSessionIntrinsicsDiagnosticState? = pixel9IntrinsicsState(),
        calibration: CameraCalibrationDiagnostics? = null,
        geometry: CameraGeometryDiagnosticSnapshot? = null,
        capturedAtEpochMillis: Long = 1_700_000_000_000L,
    ) = captureCamDiagnosticSnapshot(
        capturedAtEpochMillis = capturedAtEpochMillis,
        sessionId = 9L,
        cam2bState = null,
        cameraGeometryState = geometry,
        cameraGeometryStatusTransitionCount = 0,
        cameraGeometryObservedFrameCount = 1115L,
        cameraGeometryReadyBundleCount = 0L,
        cameraIntrinsicsState = state,
        calibrationDiagnostics = calibration,
    )

    private fun minimalGeometry(bufferWidthPx: Int?, bufferHeightPx: Int?) =
        CameraGeometryDiagnosticSnapshot(
            category = CameraGeometryDiagnosticCategory.READY_LEGACY_FALLBACK,
            quality = null,
            frameTimestampNanos = null,
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
            cropLeftPx = null,
            cropTopPx = null,
            cropRightPx = null,
            cropBottomPx = null,
            rotationDegrees = 90,
            viewportWidthPx = null,
            viewportHeightPx = null,
            pairDeltaNanos = null,
            intrinsicsSource = null,
            horizontalFovDeg = null,
            verticalFovDeg = null,
            uniformScale = null,
            displayOffsetX = null,
            displayOffsetY = null,
            centerProbe = null,
        )

    private val pixel9Snapshot = snapshot()

    private fun calibrationFixture(cameraId: String? = "0", isLogicalMultiCamera: Boolean = false) =
        CameraCalibrationDiagnostics(
            activeArrayWidthPx = 4080,
            activeArrayHeightPx = 3072,
            activeArrayLeftPx = 0.0,
            activeArrayTopPx = 0.0,
            activeArrayRightPx = 4080.0,
            activeArrayBottomPx = 3072.0,
            pixelArrayWidthPx = 4080,
            pixelArrayHeightPx = 3072,
            sensorWidthMm = 9.80,
            sensorHeightMm = 7.35,
            focalLengthMm = 6.81,
            activeFxPx = 2830.0,
            activeFyPx = 2830.0,
            activeCxPx = 2040.0,
            activeCyPx = 1536.0,
            principalPointBasis = CameraCalibrationDiagnostics.PRINCIPAL_POINT_BASIS_ACTIVE_ARRAY_LOCAL,
            focalDerivationBasis = CameraCalibrationDiagnostics.FOCAL_DERIVATION_BASIS_LENS_INTRINSIC_CALIBRATION,
            cropLeftPx = 0.0,
            cropTopPx = 0.0,
            cropRightPx = 4080.0,
            cropBottomPx = 3072.0,
            bufferFxPx = 443.75,
            bufferFyPx = 443.75,
            bufferCxPx = 320.0,
            bufferCyPx = 240.0,
            quality = CameraIntrinsicsQuality.CALIBRATED,
            sensorToBufferMappingSource = CameraCalibrationDiagnostics.SENSOR_TO_BUFFER_MAPPING_SOURCE,
            transformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
            cameraId = cameraId,
            isLogicalMultiCamera = isLogicalMultiCamera,
            physicalCameraIds = null,
        )

    @Test
    fun `the same snapshot always renders the exact same report text`() {
        val first = buildCamDiagnosticReportText(pixel9Snapshot, CamDiagnosticLiveness.LIVE)
        val second = buildCamDiagnosticReportText(pixel9Snapshot, CamDiagnosticLiveness.LIVE)

        assertEquals(first, second)
    }

    @Test
    fun `every required section header is present, in order, and the report never requires additional context to read`() {
        val text = buildCamDiagnosticReportText(pixel9Snapshot, CamDiagnosticLiveness.FROZEN)

        val headers =
            listOf(
                "POINTTOSKY CAM DIAGNOSTICS",
                "SESSION",
                "CAM-2B",
                "CAM-2C ATTEMPT",
                "PUBLISHED INTRINSICS",
                "CAMERA",
                "FRAME TRANSFORM",
                "METADATA",
                "BUFFER GEOMETRY",
                "CALIBRATION",
                "COUNTERS",
            )
        var lastIndex = -1
        for (header in headers) {
            val index = text.indexOf("\n$header\n").let { if (it >= 0) it else if (text.startsWith("$header\n")) 0 else -1 }
            assertTrue(index >= 0, "expected header \"$header\" to be present on its own line")
            assertTrue(index > lastIndex, "expected header \"$header\" to appear after the previous section")
            lastIndex = index
        }
        assertTrue(text.contains("diagnostics: FROZEN"))
    }

    @Test
    fun `the real Pixel 9 logical-multi-camera evidence renders the full root cause`() {
        val text = buildCamDiagnosticReportText(pixel9Snapshot, CamDiagnosticLiveness.LIVE)

        assertTrue(text.contains("attempt: UnsupportedLogicalMultiCameraMapping(cameraId=0, physicalIds=2, 3, 4)"))
        assertTrue(text.contains("id: 0"))
        assertTrue(text.contains("logical: true"))
        assertTrue(text.contains("physical IDs: 2, 3, 4"))
        assertTrue(text.contains("class: AXIS_ALIGNED_0"))
        assertTrue(text.contains("analyzed"))
        assertTrue(text.contains("reference: PhysicalSensor"))
    }

    @Test
    fun `an identity matrix over the real Pixel 9 domain reports AXIS_ALIGNED_0 and a non-Consistent domainConsistency on separate lines`() {
        val state =
            pixel9IntrinsicsState(
                attempt = null,
                published = null,
                matrix = identityMatrixOverThePixel9Domain,
                transformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
            )

        val text = buildCamDiagnosticReportText(snapshot(state = state, geometry = minimalGeometry(640, 480)), CamDiagnosticLiveness.LIVE)

        // Structural classification and semantic domain consistency are two distinct lines - a caller
        // reading only "class: AXIS_ALIGNED_0" must not conclude the mapping is usable.
        assertTrue(text.contains("class: AXIS_ALIGNED_0"))
        assertTrue(text.contains("domainConsistency: MAPPED_BOUNDS_MISMATCH"))
        assertTrue(text.contains("mappedSourceBounds: [0.0,0.0 — 4080.0,3072.0]"))
        assertTrue(text.contains("expectedBufferBounds: [0.0,0.0 — 640.0,480.0]"))
        assertFalse(text.contains("domainConsistency: CONSISTENT"))
    }

    @Test
    fun `a successful calibrated snapshot renders the CALIBRATION section instead of unavailable`() {
        val calibration = calibrationFixture()
        val calibratedIntrinsics =
            physicalSensorIntrinsics.copy(reference = CameraIntrinsicsReference.AnalysisBuffer(640, 480), quality = CameraIntrinsicsQuality.CALIBRATED)
        val state =
            pixel9IntrinsicsState(
                attempt = AnalysisBufferIntrinsicsResolution.Resolved(calibratedIntrinsics, calibration),
                published = CoreCameraIntrinsicsResolution.Resolved(calibratedIntrinsics),
                characteristics = pixel9CharacteristicsSnapshot.copy(isLogicalMultiCamera = false),
            )

        val text = buildCamDiagnosticReportText(snapshot(state = state, calibration = calibration), CamDiagnosticLiveness.LIVE)

        assertTrue(text.contains("CAM-2c calibration"))
        assertTrue(text.contains("quality: CALIBRATED"))
        assertTrue(text.contains("attempt: Resolved"))
        assertTrue(text.contains("resolved buffer K"))
        assertFalse(text.substringAfter("CALIBRATION\n").substringBefore("\n\nCOUNTERS").trim() == "unavailable")
    }

    @Test
    fun `missing metadata renders unavailable for every camera and metadata field, never a crash or fabricated value`() {
        val state = pixel9IntrinsicsState(attempt = null, published = null, characteristics = null, matrix = null, transformClass = null)

        val text = buildCamDiagnosticReportText(snapshot(state = state), CamDiagnosticLiveness.LIVE)

        assertTrue(text.contains("id: unavailable"))
        assertTrue(text.contains("physical IDs: unavailable"))
        assertTrue(text.contains("pixel array: unavailable"))
        assertTrue(text.contains("active rect: unavailable"))
        assertTrue(text.contains("focal length: unavailable"))
        assertTrue(text.contains("CALIBRATION\nunavailable"))
    }

    @Test
    fun `a null latest-frame transform renders present false and an unavailable matrix, never a fabricated 3x3`() {
        val state = pixel9IntrinsicsState(matrix = null, transformClass = null)

        val text = buildCamDiagnosticReportText(snapshot(state = state), CamDiagnosticLiveness.LIVE)

        assertTrue(text.contains("present: false"))
        assertTrue(text.contains("matrix[9]: unavailable"))
        assertTrue(text.contains("class: unavailable"))
    }

    @Test
    fun `all 9 sensor-to-buffer matrix values are preserved in the report text`() {
        val matrix = SensorToBufferMatrix3(1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.5, 9.5)
        val state = pixel9IntrinsicsState(matrix = matrix)

        val text = buildCamDiagnosticReportText(snapshot(state = state), CamDiagnosticLiveness.LIVE)

        for (value in listOf(1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.5, 9.5)) {
            assertTrue(text.contains(String.format(Locale.ROOT, "%.1f", value)), "expected matrix value $value in the report")
        }
    }

    @Test
    fun `numeric fields always use a dot decimal separator regardless of the JVM default locale`() {
        Locale.setDefault(Locale.GERMANY)
        try {
            val text = buildCamDiagnosticReportText(pixel9Snapshot, CamDiagnosticLiveness.LIVE)

            // Locale.GERMANY renders Double via a bare String.format("%.1f", ...) as "0,2" (comma
            // decimal) - this report must still read "0.2" (dot decimal) regardless, proving every
            // numeric formatter here is pinned to Locale.ROOT rather than the JVM default.
            assertTrue(text.contains("0.2, 0.0, 0.0"), "expected a dot-decimal matrix value even under a comma-decimal default locale")
            assertFalse(text.contains("0,2"), "must never render the German comma-decimal form of this matrix value")
            assertFalse(text.contains("9,80"), "must never render the German comma-decimal form of the sensor size")
        } finally {
            Locale.setDefault(defaultLocale)
        }
    }

    @Test
    fun `the compact summary names the logical-multi-camera root cause in a handful of lines`() {
        val text = buildCamDiagnosticCompactSummaryText(pixel9Snapshot)

        val lines = text.lines()
        assertTrue(lines.size <= 6, "expected a compact, few-line summary; was ${lines.size} lines")
        assertEquals("CAM-2c: BLOCKED", lines[0])
        assertTrue(text.contains("reason: logical multi-camera"))
        assertTrue(text.contains("camera: 0"))
        assertTrue(text.contains("physical: 2, 3, 4"))
        assertTrue(text.contains("matrix: AXIS_ALIGNED_0 · 1115/1115"))
        assertTrue(text.contains("published: PhysicalSensor"))
    }

    @Test
    fun `the compact summary omits the reason line once CAM-2c actually resolves`() {
        val calibration = calibrationFixture()
        val calibratedIntrinsics =
            physicalSensorIntrinsics.copy(reference = CameraIntrinsicsReference.AnalysisBuffer(640, 480), quality = CameraIntrinsicsQuality.CALIBRATED)
        val state =
            pixel9IntrinsicsState(
                attempt = AnalysisBufferIntrinsicsResolution.Resolved(calibratedIntrinsics, calibration),
                characteristics = pixel9CharacteristicsSnapshot.copy(isLogicalMultiCamera = false),
            )

        val text = buildCamDiagnosticCompactSummaryText(snapshot(state = state, calibration = calibration))

        assertEquals("CAM-2c: RESOLVED", text.lines()[0])
        assertFalse(text.contains("reason:"))
    }
}
