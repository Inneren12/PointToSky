package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [buildCameraSessionIntrinsicsDiagnosticText] (CAM-2c runtime integration fix
 * §5) - the central acceptance requirement is that the CAM-2c attempt line is never omitted, whether
 * that attempt succeeded, failed with a typed reason, or was never made at all.
 */
class CameraSessionIntrinsicsDiagnosticFormatTest {
    private val physicalSensorIntrinsics =
        CameraIntrinsics(
            horizontalFovDeg = 60.0,
            verticalFovDeg = 45.0,
            focalLengthMm = 4.25,
            sensorWidthMm = 5.76,
            sensorHeightMm = 4.29,
            principalPointXPx = null,
            principalPointYPx = null,
            source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            reference = CameraIntrinsicsReference.PhysicalSensor,
        )

    private val snapshot =
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = floatArrayOf(4.25f),
            sensorPhysicalWidthMm = 5.76f,
            sensorPhysicalHeightMm = 4.29f,
            activeArrayLeftPx = 0,
            activeArrayTopPx = 0,
            activeArrayRightPx = 4032,
            activeArrayBottomPx = 3024,
            pixelArrayWidthPx = 4032,
            pixelArrayHeightPx = 3024,
            isLogicalMultiCamera = true,
            cameraId = "0",
            physicalCameraIds = setOf("1", "2"),
        )

    private fun frameCounters(
        transform: SensorToBufferMatrix3? = null,
        transformClass: SensorToBufferTransformClass? = null,
    ) = CameraSessionIntrinsicsFrameCounters(
        framesAnalyzed = 12L,
        framesWithTransform = 10L,
        framesWithNullTransform = 2L,
        framesWithUsableTransform = 9L,
        coordinatorFramesWaited = 3,
        latestFrameTransform = transform,
        latestFrameTransformClass = transformClass,
    )

    @Test
    fun `text always includes the coordinator state and CAM-2c attempt lines, even for a null attempt`() {
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = null,
                publishedIntrinsicsResolution = null,
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_FRAME,
                cameraCharacteristicsSnapshot = null,
                frameCounters = frameCounters(),
            )

        val text = buildCameraSessionIntrinsicsDiagnosticText(state)

        assertTrue(text.contains("CAM-2c coordinator: WAITING_FOR_FRAME"))
        assertTrue(text.contains("CAM-2c attempt: not attempted"))
    }

    @Test
    fun `an unresolved publication renders every published-intrinsics field as unavailable`() {
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = null,
                publishedIntrinsicsResolution = null,
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVING,
                cameraCharacteristicsSnapshot = null,
                frameCounters = frameCounters(),
            )

        val text = buildCameraSessionIntrinsicsDiagnosticText(state)

        assertTrue(text.contains("publication: unavailable"))
        assertTrue(text.contains("source: unavailable"))
        assertTrue(text.contains("reference: unavailable"))
        assertTrue(text.contains("intrinsics quality: unavailable"))
    }

    @Test
    fun `a logical-multi-camera rejection names the exact reason, camera id and physical ids - never collapsed to just the fallback`() {
        val attempt =
            AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping(
                cameraId = "0",
                physicalCameraIdsForDiagnostics = setOf("1", "2"),
            )
        val published = CoreCameraIntrinsicsResolution.Resolved(physicalSensorIntrinsics)
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = attempt,
                publishedIntrinsicsResolution = published,
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = snapshot,
                frameCounters = frameCounters(),
            )

        val text = buildCameraSessionIntrinsicsDiagnosticText(state)

        // This is the exact device-acceptance scenario from the runtime integration fix (§8, "safe
        // block"): the published reference is PhysicalSensor, but the CAM-2c root cause must still
        // be visible on the same panel.
        assertTrue(text.contains("CAM-2c attempt: UnsupportedLogicalMultiCameraMapping(cameraId=0, physicalIds=1, 2)"))
        // Publication status (Resolved) and intrinsic calibration quality are distinct, separately
        // labelled fields (CAM-2c runtime integration fix P2) - "Resolved" describes only that CAM-1b
        // successfully published a PhysicalSensor value, never that any calibration quality was
        // achieved. physicalSensorIntrinsics carries no CameraIntrinsicsQuality at all (always the
        // case for a PhysicalSensor-referenced CAM-1b value), so the quality line reads "unavailable".
        assertTrue(text.contains("publication: Resolved"))
        assertTrue(text.contains("reference: PhysicalSensor"))
        assertTrue(text.contains("intrinsics quality: unavailable"))
        assertFalse(text.contains("quality: Resolved"), "must never render the publication subtype as if it were a calibration quality")
        assertTrue(text.contains("id: 0"))
        assertTrue(text.contains("logical: true"))
        assertTrue(text.contains("physical IDs: 1, 2"))
        // No "resolved buffer K" section for a failed attempt.
        assertFalse(text.contains("resolved buffer K"))
    }

    @Test
    fun `a MissingSensorToBufferTransform attempt survives alongside a PhysicalSensor fallback`() {
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = AnalysisBufferIntrinsicsResolution.MissingSensorToBufferTransform,
                publishedIntrinsicsResolution = CoreCameraIntrinsicsResolution.Resolved(physicalSensorIntrinsics),
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = snapshot,
                frameCounters = frameCounters(),
            )

        val text = buildCameraSessionIntrinsicsDiagnosticText(state)

        assertTrue(text.contains("CAM-2c attempt: MissingSensorToBufferTransform"))
        assertTrue(text.contains("publication: Resolved"))
        assertTrue(text.contains("reference: PhysicalSensor"))
        assertTrue(text.contains("intrinsics quality: unavailable"))
    }

    @Test
    fun `a legacy fallback publication renders publication status and fallback reason on separate lines from intrinsics quality`() {
        val fallbackIntrinsics = legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = 1080)
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = AnalysisBufferIntrinsicsResolution.MissingFocalLength,
                publishedIntrinsicsResolution = CoreCameraIntrinsicsResolution.LegacyFallback(fallbackIntrinsics, "no_valid_focal_length"),
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = null,
                frameCounters = frameCounters(),
            )

        val text = buildCameraSessionIntrinsicsDiagnosticText(state)

        assertTrue(text.contains("CAM-2c attempt: MissingFocalLength"))
        assertTrue(text.contains("publication: LegacyFallback"))
        assertTrue(text.contains("fallback reason: no_valid_focal_length"))
        assertTrue(text.contains("source: LEGACY_FALLBACK"))
        // legacyFallbackCameraIntrinsics never carries a CameraIntrinsicsQuality (only meaningful for
        // CAMERA_CHARACTERISTICS) - the quality line must read "unavailable", never the fallback reason.
        assertTrue(text.contains("intrinsics quality: unavailable"))
        assertFalse(text.contains("quality: LegacyFallback"), "publication status and calibration quality must never share one label again")
    }

    @Test
    fun `a Resolved attempt renders the resolved buffer K section`() {
        val calibrated =
            physicalSensorIntrinsics.copy(
                reference = CameraIntrinsicsReference.AnalysisBuffer(640, 480),
                quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
            )
        val calibrationDiagnostics =
            CameraCalibrationDiagnostics(
                activeArrayWidthPx = 4032,
                activeArrayHeightPx = 3024,
                activeArrayLeftPx = 0.0,
                activeArrayTopPx = 0.0,
                activeArrayRightPx = 4032.0,
                activeArrayBottomPx = 3024.0,
                pixelArrayWidthPx = 4032,
                pixelArrayHeightPx = 3024,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                focalLengthMm = 4.25,
                activeFxPx = 2988.0,
                activeFyPx = 2988.0,
                activeCxPx = 2016.0,
                activeCyPx = 1512.0,
                principalPointBasis = CameraCalibrationDiagnostics.PRINCIPAL_POINT_BASIS_ACTIVE_ARRAY_LOCAL,
                focalDerivationBasis = CameraCalibrationDiagnostics.FOCAL_DERIVATION_BASIS_PIXEL_ARRAY,
                cropLeftPx = 0.0,
                cropTopPx = 0.0,
                cropRightPx = 4032.0,
                cropBottomPx = 3024.0,
                bufferFxPx = 474.0,
                bufferFyPx = 474.0,
                bufferCxPx = 320.0,
                bufferCyPx = 240.0,
                quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
                sensorToBufferMappingSource = CameraCalibrationDiagnostics.SENSOR_TO_BUFFER_MAPPING_SOURCE,
                transformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
            )
        val attempt = AnalysisBufferIntrinsicsResolution.Resolved(calibrated, calibrationDiagnostics)
        val transform = SensorToBufferMatrix3(0.15873, 0.0, 0.0, 0.0, 0.15873, 0.0, 0.0, 0.0, 1.0)
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = attempt,
                publishedIntrinsicsResolution = CoreCameraIntrinsicsResolution.Resolved(calibrated),
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = snapshot,
                frameCounters = frameCounters(transform = transform, transformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0),
            )

        val text = buildCameraSessionIntrinsicsDiagnosticText(state)

        assertTrue(text.contains("CAM-2c attempt: Resolved"))
        assertTrue(text.contains("publication: Resolved"))
        assertTrue(text.contains("reference: AnalysisBuffer(640x480)"))
        // The CAM-2c calibration quality this attempt actually achieved must be visible, distinct
        // from the "publication: Resolved" line above (CAM-2c runtime integration fix P2).
        assertTrue(text.contains("intrinsics quality: APPROXIMATE_PRINCIPAL_POINT"))
        assertTrue(text.contains("resolved buffer K:"))
        assertTrue(text.contains("474.0"))
        assertTrue(text.contains("AnalysisBuffer(640,480)"))
        assertTrue(text.contains("present: true"))
        assertTrue(text.contains("class: AXIS_ALIGNED_0"))
    }

    @Test
    fun `a CALIBRATED CAM-2c resolution renders CALIBRATED, distinct from APPROXIMATE_PRINCIPAL_POINT`() {
        val calibrated =
            physicalSensorIntrinsics.copy(
                reference = CameraIntrinsicsReference.AnalysisBuffer(640, 480),
                quality = CameraIntrinsicsQuality.CALIBRATED,
            )
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = null,
                publishedIntrinsicsResolution = CoreCameraIntrinsicsResolution.Resolved(calibrated),
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = snapshot,
                frameCounters = frameCounters(),
            )

        val text = buildCameraSessionIntrinsicsDiagnosticText(state)

        assertTrue(text.contains("intrinsics quality: CALIBRATED"))
        assertFalse(text.contains("intrinsics quality: APPROXIMATE_PRINCIPAL_POINT"))
    }

    @Test
    fun `a CAM-1b PhysicalSensor resolution always renders an unavailable intrinsics quality`() {
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = AnalysisBufferIntrinsicsResolution.MissingActiveArray,
                publishedIntrinsicsResolution = CoreCameraIntrinsicsResolution.Resolved(physicalSensorIntrinsics),
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = null,
                frameCounters = frameCounters(),
            )

        val text = buildCameraSessionIntrinsicsDiagnosticText(state)

        assertTrue(text.contains("publication: Resolved"))
        assertTrue(text.contains("reference: PhysicalSensor"))
        assertTrue(text.contains("intrinsics quality: unavailable"))
    }

    @Test
    fun `frame transform section reports null when the latest frame carried none`() {
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = null,
                publishedIntrinsicsResolution = null,
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_USABLE_SENSOR_TO_BUFFER_TRANSFORM,
                cameraCharacteristicsSnapshot = null,
                frameCounters = frameCounters(transform = null, transformClass = null),
            )

        val text = buildCameraSessionIntrinsicsDiagnosticText(state)

        assertTrue(text.contains("present: false"))
        assertTrue(text.contains("analyzed: 12, withTransform: 10, nullTransform: 2, supportedClassAxisAligned0: 9"))
        assertTrue(text.contains("coordinator frames waited: 3"))
    }
}
