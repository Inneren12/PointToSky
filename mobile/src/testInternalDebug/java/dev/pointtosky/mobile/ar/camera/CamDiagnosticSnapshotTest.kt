package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionSummary
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayIntrinsicsMode
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayMetadata
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayPoint
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * `internalDebug`-only pure JVM tests for [captureCamDiagnosticSnapshot] (architecture fix §2) - proves
 * the snapshot is a **true**, deep-copied immutable value: mutating the original `FloatArray`/`Set`
 * inputs after capture must never change the already-captured [CamDiagnosticSnapshot], and a captured
 * CAM-2b `Ready` state must retain only diagnostic scalars, never the overlay `points`/`summary`
 * payload.
 */
class CamDiagnosticSnapshotTest {
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

    @Test
    fun `published intrinsics publication and reference are captured as plain strings`() {
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = null,
                publishedIntrinsicsResolution = CoreCameraIntrinsicsResolution.Resolved(physicalSensorIntrinsics),
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = null,
                frameCounters = CameraSessionIntrinsicsFrameCounters(0L, 0L, 0L, 0L, 0, null, null),
            )

        val snapshot =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 0L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = null,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 0L,
                cameraGeometryReadyBundleCount = 0L,
                cameraIntrinsicsState = state,
                calibrationDiagnostics = null,
            )

        assertEquals("Resolved", snapshot.cam2c.publishedIntrinsics.publication)
        assertEquals("PhysicalSensor", snapshot.cam2c.publishedIntrinsics.reference)
    }

    @Test
    fun `mutating the original focal-lengths FloatArray after capture does not change the captured snapshot`() {
        val mutableFocalLengths = floatArrayOf(6.81f)
        val characteristics =
            CameraCharacteristicsSnapshot(
                availableFocalLengthsMm = mutableFocalLengths,
                sensorPhysicalWidthMm = 9.80f,
                sensorPhysicalHeightMm = 7.35f,
                cameraId = "0",
            )
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = null,
                publishedIntrinsicsResolution = null,
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = characteristics,
                frameCounters = CameraSessionIntrinsicsFrameCounters(0L, 0L, 0L, 0L, 0, null, null),
            )

        val snapshot =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 0L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = null,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 0L,
                cameraGeometryReadyBundleCount = 0L,
                cameraIntrinsicsState = state,
                calibrationDiagnostics = null,
            )
        val textBefore = buildCamDiagnosticReportText(snapshot, CamDiagnosticLiveness.LIVE)
        val jsonBefore = buildCamDiagnosticJson(snapshot, CamDiagnosticLiveness.LIVE)
        // Float-to-Double widening does not re-round to the nearest decimal Double (6.81f.toDouble()
        // is not exactly the Double literal 6.81) - compare against the same widening the production
        // code performs, not a hand-typed Double literal.
        assertEquals(listOf(6.81f.toDouble()), snapshot.cam2c.camera.availableFocalLengthsMm)

        // Mutate the ORIGINAL array in place, after capture.
        mutableFocalLengths[0] = 999f

        // Float-to-Double widening does not re-round to the nearest decimal Double (6.81f.toDouble()
        // is not exactly the Double literal 6.81) - compare against the same widening the production
        // code performs, not a hand-typed Double literal.
        assertEquals(listOf(6.81f.toDouble()), snapshot.cam2c.camera.availableFocalLengthsMm)
        assertEquals(textBefore, buildCamDiagnosticReportText(snapshot, CamDiagnosticLiveness.LIVE))
        assertEquals(jsonBefore, buildCamDiagnosticJson(snapshot, CamDiagnosticLiveness.LIVE))
    }

    @Test
    fun `mutating the original physical-camera-ids Set after capture does not change the captured snapshot`() {
        val mutablePhysicalIds = mutableSetOf("2", "3", "4")
        val characteristics =
            CameraCharacteristicsSnapshot(
                availableFocalLengthsMm = null,
                sensorPhysicalWidthMm = null,
                sensorPhysicalHeightMm = null,
                isLogicalMultiCamera = true,
                cameraId = "0",
                physicalCameraIds = mutablePhysicalIds,
            )
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt =
                    AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping(
                        cameraId = "0",
                        physicalCameraIdsForDiagnostics = mutablePhysicalIds,
                    ),
                publishedIntrinsicsResolution = null,
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = characteristics,
                frameCounters = CameraSessionIntrinsicsFrameCounters(0L, 0L, 0L, 0L, 0, null, null),
            )

        val snapshot =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 0L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = null,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 0L,
                cameraGeometryReadyBundleCount = 0L,
                cameraIntrinsicsState = state,
                calibrationDiagnostics = null,
            )
        assertEquals(listOf("2", "3", "4"), snapshot.cam2c.camera.physicalCameraIds)

        // Mutate the ORIGINAL set in place, after capture - add and remove.
        mutablePhysicalIds.add("99")
        mutablePhysicalIds.remove("2")

        assertEquals(listOf("2", "3", "4"), snapshot.cam2c.camera.physicalCameraIds)
    }

    @Test
    fun `mutating a mutable physicalCameraIds Set on CameraCalibrationDiagnostics after capture does not change the captured snapshot`() {
        val mutableIds = mutableSetOf("5", "6")
        val calibration =
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
                quality = dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality.CALIBRATED,
                sensorToBufferMappingSource = CameraCalibrationDiagnostics.SENSOR_TO_BUFFER_MAPPING_SOURCE,
                transformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
                physicalCameraIds = mutableIds,
            )

        val snapshot =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 0L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = null,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 0L,
                cameraGeometryReadyBundleCount = 0L,
                cameraIntrinsicsState = null,
                calibrationDiagnostics = calibration,
            )
        assertEquals(listOf("5", "6"), snapshot.calibration?.physicalCameraIds)

        mutableIds.clear()
        mutableIds.add("nope")

        assertEquals(listOf("5", "6"), snapshot.calibration?.physicalCameraIds)
    }

    @Test
    fun `a captured CAM-2b Ready state retains only diagnostic scalars, never the overlay points or summary`() {
        val readyState =
            PredictedStarOverlayState.Ready.of(
                points =
                    listOf(
                        PredictedStarOverlayPoint(catalogIndex = 0, magnitude = 1.2, displayX = 500.0, displayY = 900.0),
                        PredictedStarOverlayPoint(catalogIndex = 1, magnitude = 2.0, displayX = 10.0, displayY = 20.0),
                    ),
                summary =
                    StarPredictionSummary(
                        inputCount = 5,
                        behindCameraCount = 1,
                        outsideImageCount = 1,
                        insideImageOutsideViewportCount = 1,
                        visibleInViewportCount = 2,
                    ),
                metadata =
                    PredictedStarOverlayMetadata(
                        inputCount = 5,
                        visibleCount = 2,
                        frameTimestampNanos = 1_000_000L,
                        rotationTimestampNanos = 990_000L,
                        pairDeltaNanos = 3_700_000L,
                        frameRotationDegrees = 90,
                        intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
                        sessionIntrinsicsSource = "CAMERA_CHARACTERISTICS",
                        sessionIntrinsicsReference = "PhysicalSensor",
                        projectionIntrinsicsSource = "CAMERA_CHARACTERISTICS",
                        projectionIntrinsicsReference = "PhysicalSensor",
                        magneticDeclinationDeg = 4.2,
                    ),
            )

        val snapshot =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 0L,
                sessionId = 1L,
                cam2bState = readyState,
                cameraGeometryState = null,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 0L,
                cameraGeometryReadyBundleCount = 0L,
                cameraIntrinsicsState = null,
                calibrationDiagnostics = null,
            )

        assertEquals("READY", snapshot.cam2b.status)
        assertEquals(5, snapshot.cam2b.inputCount)
        assertEquals(2, snapshot.cam2b.visibleCount)
        assertEquals("SESSION_INTRINSICS", snapshot.cam2b.intrinsicsMode)
        // Cam2bDiagnosticSnapshot's own type has no field capable of carrying points/summary/catalog
        // payload at all - the DTO's shape itself is the proof (compile-time, not just this test's
        // runtime assertions). The rendered report is checked too as a behavioral cross-check.
        val text = buildCamDiagnosticReportText(snapshot, CamDiagnosticLiveness.LIVE)
        assertFalse(text.contains("displayX"))
        assertFalse(text.contains("catalogIndex"))
    }

    @Test
    fun `missing CAM-1g geometry leaves every flattened field null, never a fabricated default`() {
        val snapshot =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 0L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = null,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 0L,
                cameraGeometryReadyBundleCount = 0L,
                cameraIntrinsicsState = null,
                calibrationDiagnostics = null,
            )

        assertNull(snapshot.geometry.rotationDegrees)
        assertNull(snapshot.geometry.bufferWidthPx)
        assertNull(snapshot.geometry.cropRect)
        assertNull(snapshot.calibration)
        assertEquals(0L, snapshot.cam2c.frameTransform.framesAnalyzed)
    }

    @Test
    fun `capturing the real Pixel 9 identity-matrix evidence computes WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH`() {
        // The exact real-device evidence from docs/validation/cam_2c_pixel9_evidence.md: a 4080x3072
        // active array, a 640x480 ImageAnalysis buffer (CAM-1g geometry), and an identity sensor-to-
        // buffer matrix - AXIS_ALIGNED_0 structurally, but not a match for the whole-active-array
        // hypothesis. This does NOT establish the matrix itself is broken/invalid/unusable - only that
        // this one, named hypothesis does not hold for it.
        val characteristics =
            CameraCharacteristicsSnapshot(
                availableFocalLengthsMm = null,
                sensorPhysicalWidthMm = null,
                sensorPhysicalHeightMm = null,
                activeArrayLeftPx = 0,
                activeArrayTopPx = 0,
                activeArrayRightPx = 4080,
                activeArrayBottomPx = 3072,
                cameraId = "0",
            )
        val identityMatrix = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val state =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = null,
                publishedIntrinsicsResolution = null,
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
                cameraCharacteristicsSnapshot = characteristics,
                frameCounters =
                    CameraSessionIntrinsicsFrameCounters(
                        framesAnalyzed = 1751L,
                        framesWithTransform = 1751L,
                        framesWithNullTransform = 0L,
                        framesWithUsableTransform = 1751L,
                        coordinatorFramesWaited = 1,
                        latestFrameTransform = identityMatrix,
                        latestFrameTransformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
                    ),
            )
        val geometry =
            CameraGeometryDiagnosticSnapshot(
                category = CameraGeometryDiagnosticCategory.READY_LEGACY_FALLBACK,
                quality = null,
                frameTimestampNanos = null,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
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

        val snapshot =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 0L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = geometry,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 1751L,
                cameraGeometryReadyBundleCount = 0L,
                cameraIntrinsicsState = state,
                calibrationDiagnostics = null,
            )

        // Structural classification says AXIS_ALIGNED_0 (the transform is a pure positive scale/translate)...
        assertEquals("AXIS_ALIGNED_0", snapshot.cam2c.frameTransform.transformClass)
        // ...but the whole-active-array-mapping hypothesis check says this matrix does not match it: an
        // identity matrix does not land a 4080x3072 domain onto a 640x480 buffer under that hypothesis.
        assertEquals(
            WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH.name,
            snapshot.cam2c.frameTransform.wholeActiveArrayHypothesisVerdict,
        )
        assertEquals(SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL.name, snapshot.cam2c.frameTransform.sourceDomainBasis)
        assertEquals(0.0, snapshot.cam2c.frameTransform.mappedAssumedSourceBoundsPx?.leftPx)
        assertEquals(4080.0, snapshot.cam2c.frameTransform.mappedAssumedSourceBoundsPx?.rightPx)
        assertEquals(640.0, snapshot.cam2c.frameTransform.expectedBufferBoundsPx?.rightPx)
        assertEquals(1751L, snapshot.cam2c.frameTransform.framesWithSupportedTransformClass)
        // The reason must never claim the matrix is broken/invalid/unusable - only that this one
        // hypothesis does not hold.
        val reason = snapshot.cam2c.frameTransform.hypothesisReason
        assertEquals(true, reason?.contains("hypothesis", ignoreCase = true))
        assertEquals(false, reason?.contains("matrix is invalid", ignoreCase = true))
        assertEquals(false, reason?.contains("matrix is broken", ignoreCase = true))
    }

    @Test
    fun `a snapshot captured earlier is unaffected by a later capture built from changed live inputs`() {
        val earlier =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 1L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = null,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 10L,
                cameraGeometryReadyBundleCount = 10L,
                cameraIntrinsicsState = null,
                calibrationDiagnostics = null,
            )
        val later =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 2L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = null,
                cameraGeometryStatusTransitionCount = 1,
                cameraGeometryObservedFrameCount = 20L,
                cameraGeometryReadyBundleCount = 20L,
                cameraIntrinsicsState = null,
                calibrationDiagnostics = null,
            )

        assertEquals(1L, earlier.capturedAtEpochMillis)
        assertEquals(10L, earlier.geometry.observedFrameCount)
        assertEquals(2L, later.capturedAtEpochMillis)
        assertEquals(20L, later.geometry.observedFrameCount)
    }
}
