package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `internalDebug`-only pure JVM tests for the dual-basis extensions of [ExperimentSessionState] —
 * dual-basis binding (logical + physical snapshots, zoom evidence), matrix stability counters,
 * generation scoping, resolution identity, and the unchanged DomainNotProven safety outcome (task
 * §10/§15). Compose-level callback freshness has its own instrumented tests
 * (`ExperimentCallbackFreshnessTest`, `androidTestInternalDebug`), unchanged by this slice.
 */
class DualBasisExperimentSessionTest {
    /** The real Pixel 9 CameraX 1.4.2 matrix — the logical 4080x3072 model's own output. */
    private val pixel9Matrix =
        SensorToBufferMatrix3(
            m00 = 0.1568627506494522, m01 = 0.0, m02 = 0.0,
            m10 = 0.0, m11 = 0.1568627506494522, m12 = -0.9411764740943909,
            m20 = 0.0, m21 = 0.0, m22 = 1.0,
        )

    private fun frame(
        timestampNanos: Long = 1L,
        matrix: SensorToBufferMatrix3? = pixel9Matrix,
        bufferWidthPx: Int = 640,
        bufferHeightPx: Int = 480,
    ) = CameraFrameMetadata(
        timestampNanos = timestampNanos,
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
        rotationDegrees = 90,
        cropRectLeftPx = 0,
        cropRectTopPx = 0,
        cropRectRightPx = bufferWidthPx,
        cropRectBottomPx = bufferHeightPx,
        sensorToBufferTransform = matrix,
    )

    private fun snapshot(
        cameraId: String,
        activeArrayRight: Int = 4080,
        activeArrayBottom: Int = 3072,
        isLogical: Boolean = false,
    ) = CameraCharacteristicsSnapshot(
        availableFocalLengthsMm = floatArrayOf(6.9f),
        sensorPhysicalWidthMm = 9.8f,
        sensorPhysicalHeightMm = 7.3f,
        activeArrayLeftPx = 0,
        activeArrayTopPx = 0,
        activeArrayRightPx = activeArrayRight,
        activeArrayBottomPx = activeArrayBottom,
        pixelArrayWidthPx = activeArrayRight,
        pixelArrayHeightPx = activeArrayBottom,
        isLogicalMultiCamera = isLogical,
        cameraId = cameraId,
    )

    private fun dualBinding(
        physicalCameraId: String = "2",
        physicalActiveArrayRight: Int = 4080,
        physicalActiveArrayBottom: Int = 3072,
        logicalCaptured: Boolean = true,
    ): DualBasisBindingResolution {
        val binding =
            PhysicalCameraBindingResolution.Bound(
                provenance =
                    PhysicalCameraProvenance(
                        logicalCameraId = "0",
                        physicalCameraId = physicalCameraId,
                        bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
                        bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
                        confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
                    ),
                physicalCharacteristicsSnapshot =
                    snapshot(physicalCameraId, physicalActiveArrayRight, physicalActiveArrayBottom),
            )
        val logical =
            if (logicalCaptured) {
                OpenedLogicalCameraSnapshotResolution.Captured(
                    snapshot = snapshot("0", isLogical = true),
                    provenance = OpenedLogicalCameraProvenance.BOUND_CAMERA_INFO_IS_OPENED_LOGICAL_PARENT,
                )
            } else {
                OpenedLogicalCameraSnapshotResolution.Unavailable("bound CameraInfo is itself the physical camera")
            }
        return DualBasisBindingResolution(binding = binding, openedLogicalCamera = logical)
    }

    @Test
    fun `dual-basis binding before any frame retains both snapshots, evidence and cam2c stay awaiting`() {
        val state =
            initialExperimentSessionState(1L, "2")
                .reduceDualBasisBindingResolved(1L, dualBinding(), zoomTargetRatio = 1.0f, observedZoomRatio = 1.0f)

        assertIs<PhysicalCameraBindingResolution.Bound>(state.bindingResolution)
        assertIs<OpenedLogicalCameraSnapshotResolution.Captured>(state.openedLogicalCamera)
        assertEquals(1.0f, state.zoomTargetRatio)
        assertEquals(1.0f, state.observedZoomRatio)
        assertNull(state.cam2cResult)
        assertNull(state.dualBasisEvidence)
    }

    @Test
    fun `frame before binding retains the frame, then the late binding completes the dual-basis assessment`() {
        val afterFrame = initialExperimentSessionState(1L, "2").reduceFrame(1L, frame())
        assertNull(afterFrame.bindingResolution)
        assertNotNull(afterFrame.latestFrame)
        // A frame alone already yields a typed insufficient evidence result, never a guess.
        assertEquals(DualBasisComparisonVerdict.INSUFFICIENT_INPUT, afterFrame.dualBasisEvidence?.comparisonVerdict)

        val afterBoth = afterFrame.reduceDualBasisBindingResolved(1L, dualBinding(), 1.0f, 1.0f)
        val evidence = assertNotNull(afterBoth.dualBasisEvidence)
        assertEquals(DualBasisComparisonVerdict.MATCHES_BOTH_BASES_NUMERICALLY_INDISTINGUISHABLE, evidence.comparisonVerdict)
        assertTrue(evidence.basesNumericallyIndistinguishable)
    }

    @Test
    fun `differing logical and physical arrays let the evidence identify the logical basis`() {
        val state =
            initialExperimentSessionState(1L, "2")
                .reduceDualBasisBindingResolved(
                    1L,
                    dualBinding(physicalActiveArrayRight = 4000, physicalActiveArrayBottom = 3000),
                    1.0f,
                    1.0f,
                )
                .reduceFrame(1L, frame())

        val evidence = assertNotNull(state.dualBasisEvidence)
        assertEquals(DualBasisComparisonVerdict.MATCHES_LOGICAL_BASIS_ONLY, evidence.comparisonVerdict)
        assertEquals(MatrixBasisLabel.LOGICAL_OPENED_CAMERA_BASIS, evidence.betterPredictingBasis)
    }

    @Test
    fun `an unavailable logical parent stays typed-unavailable and only the physical basis is assessed`() {
        val state =
            initialExperimentSessionState(1L, "2")
                .reduceDualBasisBindingResolved(1L, dualBinding(logicalCaptured = false), 1.0f, null)
                .reduceFrame(1L, frame())

        assertIs<OpenedLogicalCameraSnapshotResolution.Unavailable>(state.openedLogicalCamera)
        val evidence = assertNotNull(state.dualBasisEvidence)
        assertNull(evidence.logical)
        assertNotNull(evidence.physical)
    }

    @Test
    fun `the CAM-2c outcome for a verified dual-basis binding remains DomainNotProven`() {
        val state =
            initialExperimentSessionState(1L, "2")
                .reduceDualBasisBindingResolved(1L, dualBinding(), 1.0f, 1.0f)
                .reduceFrame(1L, frame())

        // Even with a perfect logical-basis model match, the automatic domain proof can only ever be
        // Unresolved or HypothesisMismatch — the result stays DomainNotProven, and the dual-basis
        // evidence changes nothing about that gate.
        val result = assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(state.cam2cResult)
        assertTrue(
            result.proof is SensorToBufferDomainProof.Unresolved ||
                result.proof is SensorToBufferDomainProof.HypothesisMismatch,
        )
    }

    @Test
    fun `matrix stability counts frames, null transforms, and changes beyond float noise`() {
        var state = initialExperimentSessionState(1L, "2")
        state = state.reduceFrame(1L, frame(timestampNanos = 1L))
        state = state.reduceFrame(1L, frame(timestampNanos = 2L))
        state = state.reduceFrame(1L, frame(timestampNanos = 3L, matrix = null))

        assertEquals(3L, state.matrixStability.framesObserved)
        assertEquals(1L, state.matrixStability.framesWithNullTransform)
        assertEquals(0L, state.matrixStability.changesBeyondFloatNoise)
        assertEquals(0.0, state.matrixStability.maxCoefficientDeltaFromFirst, 0.0)
        assertEquals(pixel9Matrix, state.matrixStability.firstMatrix)
        assertEquals(pixel9Matrix, state.matrixStability.latestMatrix)

        val changed = pixel9Matrix.copy(m12 = -0.5)
        state = state.reduceFrame(1L, frame(timestampNanos = 4L, matrix = changed))
        assertEquals(1L, state.matrixStability.changesBeyondFloatNoise)
        assertTrue(state.matrixStability.maxCoefficientDeltaFromFirst > 0.4)
        // The first valid matrix is never overwritten.
        assertEquals(pixel9Matrix, state.matrixStability.firstMatrix)
        assertEquals(changed, state.matrixStability.latestMatrix)
    }

    @Test
    fun `a mid-session buffer-dimension change is flagged as an incoherent session`() {
        var state = initialExperimentSessionState(1L, "2")
        state = state.reduceFrame(1L, frame(timestampNanos = 1L))
        assertEquals(false, state.matrixStability.dimensionsCropOrRotationChanged)
        state = state.reduceFrame(1L, frame(timestampNanos = 2L, bufferWidthPx = 1280, bufferHeightPx = 720))
        assertEquals(true, state.matrixStability.dimensionsCropOrRotationChanged)
    }

    @Test
    fun `late dual-basis callbacks from a superseded attempt are ignored`() {
        val state = initialExperimentSessionState(2L, "3")
        val afterStale = state.reduceDualBasisBindingResolved(1L, dualBinding(), 1.0f, 1.0f)
        assertSame(state, afterStale)
        val afterStaleFrame = state.reduceFrame(1L, frame())
        assertSame(state, afterStaleFrame)
    }

    @Test
    fun `a terminally failed attempt ignores dual-basis binding and frames`() {
        val failed = initialExperimentSessionState(1L, "2").reduceExplicitBindFailure(1L, "explicit_selector_zoom_failed")
        assertSame(failed, failed.reduceDualBasisBindingResolved(1L, dualBinding(), 1.0f, 1.0f))
        assertSame(failed, failed.reduceFrame(1L, frame()))
    }

    @Test
    fun `a new attempt starts with clean snapshots, counters, and evidence - no cross-generation contamination`() {
        var uiModel = ExperimentUiModel().startAttempt("2", AnalysisResolutionCandidate(640, 480))
        val firstAttemptId = uiModel.session!!.attemptId
        uiModel =
            uiModel.updateSession(firstAttemptId) {
                it.reduceDualBasisBindingResolved(firstAttemptId, dualBinding(), 1.0f, 1.0f)
                    .reduceFrame(firstAttemptId, frame())
            }
        assertNotNull(uiModel.session!!.dualBasisEvidence)

        // Resolution/aspect switch = a NEW attempt (task §11) — never a mutation under an old snapshot.
        uiModel = uiModel.startAttempt("2", AnalysisResolutionCandidate(1280, 720))
        val second = uiModel.session!!
        assertTrue(second.attemptId != firstAttemptId)
        assertEquals(1280, second.requestedAnalysisResolutionWidthPx)
        assertEquals(720, second.requestedAnalysisResolutionHeightPx)
        assertNull(second.bindingResolution)
        assertNull(second.openedLogicalCamera)
        assertNull(second.dualBasisEvidence)
        assertEquals(0L, second.matrixStability.framesObserved)

        // A late frame from the first generation cannot touch the new one.
        val afterStale = uiModel.updateSession(firstAttemptId) { it.reduceFrame(firstAttemptId, frame()) }
        assertSame(uiModel.session, afterStale.session)
    }

    @Test
    fun `retry preserves the requested resolution in a fresh generation`() {
        var uiModel = ExperimentUiModel().startAttempt("2", AnalysisResolutionCandidate(1280, 720))
        val firstAttemptId = uiModel.session!!.attemptId
        uiModel = uiModel.retry()
        val retried = uiModel.session!!
        assertTrue(retried.attemptId != firstAttemptId)
        assertEquals(1280, retried.requestedAnalysisResolutionWidthPx)
        assertEquals(720, retried.requestedAnalysisResolutionHeightPx)
        assertEquals(0L, retried.matrixStability.framesObserved)
    }
}
