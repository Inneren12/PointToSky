package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure JVM tests for [captureCamDiagnosticSnapshot] (CAM diagnostic export/freeze fix §1) - proves the
 * snapshot is a bounded, immutable value built entirely from its parameters, never a second read of a
 * live provider/coordinator, and that its flattened buffer/crop/rotation/viewport fields always agree
 * with the [CameraGeometryDiagnosticSnapshot] they were copied from.
 */
class CamDiagnosticSnapshotTest {
    private val readyGeometry =
        CameraGeometryDiagnosticSnapshot(
            category = CameraGeometryDiagnosticCategory.READY_CALIBRATED,
            quality = CameraGeometryQuality.CALIBRATED,
            frameTimestampNanos = 1_000_000L,
            bufferWidthPx = 640,
            bufferHeightPx = 480,
            cropLeftPx = 10,
            cropTopPx = 20,
            cropRightPx = 630,
            cropBottomPx = 470,
            rotationDegrees = 90,
            viewportWidthPx = 1080,
            viewportHeightPx = 2424,
            pairDeltaNanos = 3_700_000L,
            intrinsicsSource = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            horizontalFovDeg = 70.7,
            verticalFovDeg = 56.2,
            uniformScale = 3.788,
            displayOffsetX = -369.0,
            displayOffsetY = 0.0,
            centerProbe = null,
        )

    private val physicalSensorIntrinsics =
        CameraIntrinsics(
            horizontalFovDeg = 70.7,
            verticalFovDeg = 56.2,
            focalLengthMm = 4.25,
            sensorWidthMm = 5.76,
            sensorHeightMm = 4.29,
            principalPointXPx = null,
            principalPointYPx = null,
            source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            reference = CameraIntrinsicsReference.PhysicalSensor,
        )

    /** Mirrors the real Pixel 9 evidence: cameraId=0, logical=true, physicalIds=2,3,4, matrix present
     * and AXIS_ALIGNED_0 on every observed frame, CAM-2c blocked on UnsupportedLogicalMultiCameraMapping. */
    private val pixel9Snapshot =
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

    private val pixel9Matrix = SensorToBufferMatrix3(0.15686, 0.0, 0.0, 0.0, 0.15686, 0.0, 0.0, 0.0, 1.0)

    private val pixel9IntrinsicsState =
        CameraSessionIntrinsicsDiagnosticState(
            analysisBufferAttempt =
                AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping(
                    cameraId = "0",
                    physicalCameraIdsForDiagnostics = setOf("2", "3", "4"),
                ),
            publishedIntrinsicsResolution = CoreCameraIntrinsicsResolution.Resolved(physicalSensorIntrinsics),
            coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
            cameraCharacteristicsSnapshot = pixel9Snapshot,
            frameCounters =
                CameraSessionIntrinsicsFrameCounters(
                    framesAnalyzed = 1115L,
                    framesWithTransform = 1115L,
                    framesWithNullTransform = 0L,
                    framesWithUsableTransform = 1115L,
                    coordinatorFramesWaited = 1,
                    latestFrameTransform = pixel9Matrix,
                    latestFrameTransformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
                ),
        )

    @Test
    fun `flattened buffer, crop, rotation and viewport fields are copied verbatim from the CAM-1g geometry snapshot`() {
        val snapshot =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 42L,
                sessionId = 7L,
                cam2bState = null,
                cameraGeometryState = readyGeometry,
                cameraGeometryStatusTransitionCount = 2,
                cameraGeometryObservedFrameCount = 500L,
                cameraGeometryReadyBundleCount = 480L,
                cameraIntrinsicsState = null,
                calibrationDiagnostics = null,
            )

        assertEquals(42L, snapshot.capturedAtEpochMillis)
        assertEquals(7L, snapshot.sessionId)
        assertEquals(90, snapshot.rotationDegrees)
        assertEquals(640, snapshot.bufferWidthPx)
        assertEquals(480, snapshot.bufferHeightPx)
        assertEquals(CamDiagnosticCropRect(leftPx = 10, topPx = 20, rightPx = 630, bottomPx = 470), snapshot.cropRect)
        assertEquals(1080, snapshot.viewportWidthPx)
        assertEquals(2424, snapshot.viewportHeightPx)
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

        assertNull(snapshot.rotationDegrees)
        assertNull(snapshot.bufferWidthPx)
        assertNull(snapshot.bufferHeightPx)
        assertNull(snapshot.cropRect)
        assertNull(snapshot.viewportWidthPx)
        assertNull(snapshot.viewportHeightPx)
        assertNull(snapshot.cameraGeometryState)
        assertNull(snapshot.cameraIntrinsicsState)
        assertNull(snapshot.calibrationDiagnostics)
    }

    @Test
    fun `a partial crop rect - only some of the four edges known - resolves to a null cropRect, never a partially-fabricated one`() {
        val partialCrop = readyGeometry.copy(cropRightPx = null)

        val snapshot =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 0L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = partialCrop,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 0L,
                cameraGeometryReadyBundleCount = 0L,
                cameraIntrinsicsState = null,
                calibrationDiagnostics = null,
            )

        assertNull(snapshot.cropRect)
        // The other flattened fields, unrelated to the crop rect, are unaffected.
        assertEquals(640, snapshot.bufferWidthPx)
    }

    @Test
    fun `the logical-multi-camera Pixel 9 evidence carries the full CAM-2c attempt, camera id and physical ids through unchanged`() {
        val snapshot =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 100L,
                sessionId = 3L,
                cam2bState = null,
                cameraGeometryState = null,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 0L,
                cameraGeometryReadyBundleCount = 0L,
                cameraIntrinsicsState = pixel9IntrinsicsState,
                calibrationDiagnostics = null,
            )

        val attempt = snapshot.cameraIntrinsicsState?.analysisBufferAttempt
        check(attempt is AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping)
        assertEquals("0", attempt.cameraId)
        assertEquals(setOf("2", "3", "4"), attempt.physicalCameraIdsForDiagnostics)
        assertEquals(pixel9Matrix, snapshot.cameraIntrinsicsState?.frameCounters?.latestFrameTransform)
        assertEquals(SensorToBufferTransformClass.AXIS_ALIGNED_0, snapshot.cameraIntrinsicsState?.frameCounters?.latestFrameTransformClass)
        // CAM-2c blocks the calibrated mapping entirely on a logical-multi-camera device - no
        // successful calibration diagnostics exist for this session.
        assertNull(snapshot.calibrationDiagnostics)
    }

    @Test
    fun `a null latest-frame transform is carried through as null, never substituted with a fabricated matrix`() {
        val stateWithNullMatrix =
            pixel9IntrinsicsState.copy(
                frameCounters =
                    pixel9IntrinsicsState.frameCounters.copy(
                        latestFrameTransform = null,
                        latestFrameTransformClass = null,
                    ),
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
                cameraIntrinsicsState = stateWithNullMatrix,
                calibrationDiagnostics = null,
            )

        assertNull(snapshot.cameraIntrinsicsState?.frameCounters?.latestFrameTransform)
        assertNull(snapshot.cameraIntrinsicsState?.frameCounters?.latestFrameTransformClass)
    }

    @Test
    fun `a snapshot captured earlier is unaffected by a later capture built from changed live inputs`() {
        val earlier =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 1L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = readyGeometry,
                cameraGeometryStatusTransitionCount = 0,
                cameraGeometryObservedFrameCount = 10L,
                cameraGeometryReadyBundleCount = 10L,
                cameraIntrinsicsState = null,
                calibrationDiagnostics = null,
            )

        // Simulate the live coordinator/provider advancing between captures - a realistic recomposition
        // a moment later, with more frames observed and a different geometry bundle.
        val changedGeometry = readyGeometry.copy(bufferWidthPx = 1920, bufferHeightPx = 1080, rotationDegrees = 0)
        val later =
            captureCamDiagnosticSnapshot(
                capturedAtEpochMillis = 2L,
                sessionId = 1L,
                cam2bState = null,
                cameraGeometryState = changedGeometry,
                cameraGeometryStatusTransitionCount = 1,
                cameraGeometryObservedFrameCount = 20L,
                cameraGeometryReadyBundleCount = 20L,
                cameraIntrinsicsState = null,
                calibrationDiagnostics = null,
            )

        // The earlier snapshot's own fields never change - it is a plain immutable value, not a view
        // over the live coordinator.
        assertEquals(1L, earlier.capturedAtEpochMillis)
        assertEquals(640, earlier.bufferWidthPx)
        assertEquals(480, earlier.bufferHeightPx)
        assertEquals(90, earlier.rotationDegrees)
        assertEquals(10L, earlier.cameraGeometryObservedFrameCount)

        assertEquals(2L, later.capturedAtEpochMillis)
        assertEquals(1920, later.bufferWidthPx)
        assertEquals(1080, later.bufferHeightPx)
        assertEquals(0, later.rotationDegrees)
        assertEquals(20L, later.cameraGeometryObservedFrameCount)
    }
}
