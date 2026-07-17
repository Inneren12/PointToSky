package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pure JVM tests for [ExperimentSessionState]'s reducers (task's "keep the pure state transition
 * separately unit-testable" - the Compose-level callback-freshness fix has its own instrumented test,
 * `ExperimentCallbackFreshnessTest`, `androidTestInternalDebug`).
 */
class ExperimentSessionStateTest {
    private val fullFrameTransform =
        SensorToBufferMatrix3(
            m00 = 640.0 / 4032.0, m01 = 0.0, m02 = 0.0,
            m10 = 0.0, m11 = 480.0 / 3024.0, m12 = 0.0,
            m20 = 0.0, m21 = 0.0, m22 = 1.0,
        )

    private fun frame(timestampNanos: Long = 1L) =
        CameraFrameMetadata(
            timestampNanos = timestampNanos,
            bufferWidthPx = 640,
            bufferHeightPx = 480,
            rotationDegrees = 0,
            sensorToBufferTransform = fullFrameTransform,
        )

    private fun physicalSnapshot(cameraId: String = "2") =
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = floatArrayOf(3.6f),
            sensorPhysicalWidthMm = 6.4f,
            sensorPhysicalHeightMm = 4.8f,
            activeArrayLeftPx = 0,
            activeArrayTopPx = 0,
            activeArrayRightPx = 4032,
            activeArrayBottomPx = 3024,
            pixelArrayWidthPx = 4032,
            pixelArrayHeightPx = 3024,
            isLogicalMultiCamera = false,
            cameraId = cameraId,
        )

    private fun boundResolution(cameraId: String = "2") =
        PhysicalCameraBindingResolution.Bound(
            provenance =
                PhysicalCameraProvenance(
                    logicalCameraId = "0",
                    physicalCameraId = cameraId,
                    bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
                    bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
                    confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
                ),
            physicalCharacteristicsSnapshot = physicalSnapshot(cameraId),
        )

    @Test
    fun `a fresh session has no binding, no frame, and no result`() {
        val state = initialExperimentSessionState(attemptId = 1L, physicalCameraId = "2")

        assertNull(state.bindingResolution)
        assertNull(state.latestFrame)
        assertNull(state.cam2cResult)
        assertEquals(false, state.isTerminallyFailed)
    }

    @Test
    fun `binding resolved before any frame retains the binding, cam2cResult stays awaiting`() {
        val binding = boundResolution()
        val state = initialExperimentSessionState(1L, "2").reduceCameraInfoResolved(1L, binding)

        assertSame(binding, state.bindingResolution)
        assertNull(state.latestFrame)
        assertNull(state.cam2cResult)
    }

    @Test
    fun `a frame arriving before any binding retains the frame, cam2cResult stays awaiting`() {
        // Task §1: "frames may arrive before onCameraInfo" - the first frame must not be discarded.
        val f = frame()
        val state = initialExperimentSessionState(1L, "2").reduceFrame(1L, f)

        assertSame(f, state.latestFrame)
        assertNull(state.bindingResolution)
        assertNull(state.cam2cResult)
    }

    @Test
    fun `once both binding and frame exist, cam2cResult is computed`() {
        val binding = boundResolution()
        val f = frame()
        val state =
            initialExperimentSessionState(1L, "2")
                .reduceCameraInfoResolved(1L, binding)
                .reduceFrame(1L, f)

        assertSame(binding, state.bindingResolution)
        assertSame(f, state.latestFrame)
        // No automatic domain proof this codebase can produce is ever Proven - see
        // SensorToBufferDomainProofTest - so the real, honest result here is DomainNotProven.
        assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(state.cam2cResult)
    }

    @Test
    fun `order independence - frame then binding reaches the same result as binding then frame`() {
        val binding = boundResolution()
        val f = frame()
        val frameFirst = initialExperimentSessionState(1L, "2").reduceFrame(1L, f).reduceCameraInfoResolved(1L, binding)
        val bindingFirst = initialExperimentSessionState(1L, "2").reduceCameraInfoResolved(1L, binding).reduceFrame(1L, f)

        assertSame(binding, frameFirst.bindingResolution)
        assertSame(binding, bindingFirst.bindingResolution)
        assertSame(f, frameFirst.latestFrame)
        assertSame(f, bindingFirst.latestFrame)
        assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(frameFirst.cam2cResult)
        assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(bindingFirst.cam2cResult)
    }

    @Test
    fun `a later frame refreshes latestFrame and cam2cResult without erasing the binding`() {
        val binding = boundResolution()
        val first = frame(timestampNanos = 1L)
        val second = frame(timestampNanos = 2L)
        val state =
            initialExperimentSessionState(1L, "2")
                .reduceCameraInfoResolved(1L, binding)
                .reduceFrame(1L, first)
                .reduceFrame(1L, second)

        assertSame(second, state.latestFrame)
        assertSame(binding, state.bindingResolution)
        assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(state.cam2cResult)
    }

    @Test
    fun `a resolved binding never regresses back to unbound once set`() {
        // Task §1: "Frame updates must preserve the currently verified binding and must not regress
        // Bound back to Binding." reduceFrame has no way to null out bindingResolution - proven here
        // by applying several frames after a binding and confirming it is still exactly the same value.
        val binding = boundResolution()
        var state = initialExperimentSessionState(1L, "2").reduceCameraInfoResolved(1L, binding)
        repeat(5) { i -> state = state.reduceFrame(1L, frame(timestampNanos = i.toLong())) }

        assertSame(binding, state.bindingResolution)
    }

    @Test
    fun `explicit bind failure is terminal - every later reducer call is a no-op`() {
        val failed = initialExperimentSessionState(1L, "2").reduceExplicitBindFailure(1L, "explicit_selector_zoom_failed")

        assertEquals(true, failed.isTerminallyFailed)
        assertEquals("explicit_selector_zoom_failed", failed.explicitBindFailureReason)

        val afterLateBinding = failed.reduceCameraInfoResolved(1L, boundResolution())
        val afterLateFrame = failed.reduceFrame(1L, frame())
        val afterSecondFailure = failed.reduceExplicitBindFailure(1L, "different_reason")

        assertEquals(failed, afterLateBinding)
        assertEquals(failed, afterLateFrame)
        assertEquals(failed, afterSecondFailure)
    }

    @Test
    fun `a callback for a superseded attemptId never mutates this session`() {
        // Task §1/§2: "callbacks from candidate A cannot mutate a later attempt for candidate B."
        val state = initialExperimentSessionState(attemptId = 2L, physicalCameraId = "3")

        val afterStaleBinding = state.reduceCameraInfoResolved(attemptId = 1L, binding = boundResolution("2"))
        val afterStaleFrame = state.reduceFrame(attemptId = 1L, frame = frame())
        val afterStaleFailure = state.reduceExplicitBindFailure(attemptId = 1L, reason = "stale")

        assertEquals(state, afterStaleBinding)
        assertEquals(state, afterStaleFrame)
        assertEquals(state, afterStaleFailure)
    }

    @Test
    fun `two independent attempts for different candidates never cross-contaminate`() {
        val attemptA = initialExperimentSessionState(1L, "2").reduceCameraInfoResolved(1L, boundResolution("2"))
        val attemptB = initialExperimentSessionState(2L, "3").reduceCameraInfoResolved(2L, boundResolution("3"))

        assertEquals("2", (attemptA.bindingResolution as PhysicalCameraBindingResolution.Bound).provenance.physicalCameraId)
        assertEquals("3", (attemptB.bindingResolution as PhysicalCameraBindingResolution.Bound).provenance.physicalCameraId)
    }
}
