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
        assertEquals(DualBasisComparisonVerdict.MATCHES_BOTH_EQUAL_RECTS_NUMERICALLY_INDISTINGUISHABLE, evidence.comparisonVerdict)
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
    fun `matrix stability counts frames, null transforms, bitwise changes, and geometric changes`() {
        var state = initialExperimentSessionState(1L, "2")
        state = state.reduceFrame(1L, frame(timestampNanos = 1L))
        state = state.reduceFrame(1L, frame(timestampNanos = 2L))
        state = state.reduceFrame(1L, frame(timestampNanos = 3L, matrix = null))

        assertEquals(3L, state.matrixStability.framesObserved)
        assertEquals(1L, state.matrixStability.framesWithNullTransform)
        assertEquals(0L, state.matrixStability.bitwiseMatrixChanges)
        assertEquals(0L, state.matrixStability.mappedDisplacementChangesBeyondTolerance)
        assertEquals(0.0, state.matrixStability.maxCoefficientDeltaFromFirst, 0.0)
        assertEquals(0.0, state.matrixStability.maxMappedDisplacementFromFirstPx, 0.0)
        assertEquals(pixel9Matrix, state.matrixStability.firstMatrix)
        assertEquals(pixel9Matrix, state.matrixStability.latestMatrix)

        // A ~0.44 px translation change is both a bitwise change and a geometrically meaningful one.
        val changed = pixel9Matrix.copy(m12 = -0.5)
        state = state.reduceFrame(1L, frame(timestampNanos = 4L, matrix = changed))
        assertEquals(1L, state.matrixStability.bitwiseMatrixChanges)
        assertEquals(1L, state.matrixStability.mappedDisplacementChangesBeyondTolerance)
        assertTrue(state.matrixStability.maxCoefficientDeltaFromFirst > 0.4)
        assertTrue(state.matrixStability.maxMappedDisplacementFromFirstPx > 0.4)
        // The first valid matrix is never overwritten.
        assertEquals(pixel9Matrix, state.matrixStability.firstMatrix)
        assertEquals(changed, state.matrixStability.latestMatrix)
    }

    @Test
    fun `a large-translation float32 ULP step is a bitwise change but not a geometric one`() {
        // P2 fix boundary: at translation magnitude ~-121.88 (the 16:9 fixture), one float32 ULP is
        // ~7.6e-6 — far above the old 1e-6 raw-coefficient bound, which would have mislabelled pure
        // float storage as "change beyond float noise". The new design counts it as a bitwise change
        // (the numbers really differ) but NOT as a geometrically meaningful mapped-pixel change.
        val bigTranslation = pixel9Matrix.copy(m12 = (-121.88235f).toDouble())
        val ulpStep = bigTranslation.copy(m12 = Math.nextUp((-121.88235f)).toDouble())

        var state = initialExperimentSessionState(1L, "2")
        state = state.reduceFrame(1L, frame(timestampNanos = 1L, matrix = bigTranslation))
        state = state.reduceFrame(1L, frame(timestampNanos = 2L, matrix = ulpStep))

        assertEquals(1L, state.matrixStability.bitwiseMatrixChanges)
        assertEquals(0L, state.matrixStability.mappedDisplacementChangesBeyondTolerance)
        assertTrue(state.matrixStability.maxMappedDisplacementFromFirstPx < MATRIX_STABILITY_MAPPED_DISPLACEMENT_TOLERANCE_PX)
    }

    @Test
    fun `mapped-displacement threshold boundary - just below stays geometric-stable, just above counts`() {
        val base = pixel9Matrix
        // Pure translation displaces every reference-rect point by exactly the translation delta.
        val justBelow = base.copy(m12 = base.m12 + MATRIX_STABILITY_MAPPED_DISPLACEMENT_TOLERANCE_PX * 0.5)
        val justAbove = base.copy(m12 = base.m12 + MATRIX_STABILITY_MAPPED_DISPLACEMENT_TOLERANCE_PX * 2.0)

        var state = initialExperimentSessionState(1L, "2")
        state = state.reduceFrame(1L, frame(timestampNanos = 1L, matrix = base))
        state = state.reduceFrame(1L, frame(timestampNanos = 2L, matrix = justBelow))
        assertEquals(1L, state.matrixStability.bitwiseMatrixChanges)
        assertEquals(0L, state.matrixStability.mappedDisplacementChangesBeyondTolerance)

        state = state.reduceFrame(1L, frame(timestampNanos = 3L, matrix = justAbove))
        assertEquals(2L, state.matrixStability.bitwiseMatrixChanges)
        assertEquals(1L, state.matrixStability.mappedDisplacementChangesBeyondTolerance)
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
        var uiModel = ExperimentUiModel().startAttempt("2", AnalysisResolutionCandidate(640, 480, AnalysisResolutionFamily.NEAR_4_3))
        val firstAttemptId = uiModel.session!!.attemptId
        uiModel =
            uiModel.updateSession(firstAttemptId) {
                it.reduceDualBasisBindingResolved(firstAttemptId, dualBinding(), 1.0f, 1.0f)
                    .reduceFrame(firstAttemptId, frame())
            }
        assertNotNull(uiModel.session!!.dualBasisEvidence)

        // Resolution/aspect switch = a NEW attempt (task §11) — never a mutation under an old snapshot.
        uiModel = uiModel.startAttempt("2", AnalysisResolutionCandidate(1280, 720, AnalysisResolutionFamily.NEAR_16_9))
        val second = uiModel.session!!
        assertTrue(second.attemptId != firstAttemptId)
        assertEquals(1280, second.requestedAnalysisResolutionWidthPx)
        assertEquals(720, second.requestedAnalysisResolutionHeightPx)
        assertEquals(AnalysisResolutionFamily.NEAR_16_9, second.requestedAnalysisResolutionFamily)
        assertNull(second.bindingResolution)
        assertNull(second.openedLogicalCamera)
        assertNull(second.dualBasisEvidence)
        assertEquals(0L, second.matrixStability.framesObserved)

        // A late frame from the first generation cannot touch the new one.
        val afterStale = uiModel.updateSession(firstAttemptId) { it.reduceFrame(firstAttemptId, frame()) }
        assertSame(uiModel.session, afterStale.session)
    }

    @Test
    fun `retry preserves the requested resolution AND its family in a fresh generation`() {
        // A non-exact near-16:9 size (848x480 fails width*9 == height*16) — the family must survive
        // retry from the stored request, never be re-inferred from the dimensions (P1 family fix).
        var uiModel = ExperimentUiModel().startAttempt("2", AnalysisResolutionCandidate(848, 480, AnalysisResolutionFamily.NEAR_16_9))
        val firstAttemptId = uiModel.session!!.attemptId
        uiModel = uiModel.retry()
        val retried = uiModel.session!!
        assertTrue(retried.attemptId != firstAttemptId)
        assertEquals(848, retried.requestedAnalysisResolutionWidthPx)
        assertEquals(480, retried.requestedAnalysisResolutionHeightPx)
        assertEquals(AnalysisResolutionFamily.NEAR_16_9, retried.requestedAnalysisResolutionFamily)
        assertEquals(0L, retried.matrixStability.framesObserved)
    }
}
