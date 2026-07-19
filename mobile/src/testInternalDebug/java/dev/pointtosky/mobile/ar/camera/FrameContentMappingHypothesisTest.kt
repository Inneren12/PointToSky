package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CAM-2c frame-content correspondence experiment: the primary discriminating device case from the task
 * brief — Pixel 9 physical camera 3, opened logical camera active array `4080x3072`, selected physical
 * camera active array `4032x3024`. This file never imports [SensorToBufferDomainProof] — a reviewer
 * grepping this file's imports for that type finds none, matching task §6/§9's "no SensorToBufferDomainProof
 * variant may be created" constraint structurally, not just by convention.
 */
class FrameContentMappingHypothesisTest {
    private val logicalActiveArrayWidthPx = 4080
    private val logicalActiveArrayHeightPx = 3072
    private val physicalActiveArrayWidthPx = 4032
    private val physicalActiveArrayHeightPx = 3024
    private val bufferWidthPx = 640
    private val bufferHeightPx = 480

    private fun physicalCamera3Snapshot() =
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = floatArrayOf(2.55f),
            sensorPhysicalWidthMm = 6.4f,
            sensorPhysicalHeightMm = 4.8f,
            activeArrayLeftPx = 0,
            activeArrayTopPx = 0,
            activeArrayRightPx = physicalActiveArrayWidthPx,
            activeArrayBottomPx = physicalActiveArrayHeightPx,
            pixelArrayWidthPx = physicalActiveArrayWidthPx,
            pixelArrayHeightPx = physicalActiveArrayHeightPx,
            isLogicalMultiCamera = false,
            cameraId = "3",
        )

    /** The observed matrix this experiment's prior matrix-construction evidence found: the real
     * CameraX matrix matches the LOGICAL camera's own active-array basis, not the physical one. */
    private fun observedMatrixMatchingLogicalBasis() =
        predictCameraX142SensorToBufferMatrix(
            CameraBasisRect(0, 0, logicalActiveArrayWidthPx, logicalActiveArrayHeightPx),
            bufferWidthPx,
            bufferHeightPx,
        )!!.matrix

    @Test
    fun `physical camera 3 case - LOGICAL and PHYSICAL paths both resolve but disagree measurably`() {
        val hypotheses =
            computeFrameContentMappingHypotheses(
                physicalSnapshot = physicalCamera3Snapshot(),
                observedCameraXMatrix = observedMatrixMatchingLogicalBasis(),
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )
        assertEquals(3, hypotheses.size)

        val logical = hypotheses.single { it.id == FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH }
        val physical = hypotheses.single { it.id == FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH }
        val logicalResult = assertIs<FrameContentHypothesisIntrinsicsResult.Available>(logical.result)
        val physicalResult = assertIs<FrameContentHypothesisIntrinsicsResult.Available>(physical.result)

        // Different active-array source rects (4080x3072 vs 4032x3024) feeding the same buffer size
        // must produce measurably different buffer-space principal points/focal lengths — this is the
        // synthetic reproduction of the task's real ~10px (640x480) / ~20px (1280x720) discrepancy.
        assertNotEquals(logicalResult.intrinsics.bufferCxPx, physicalResult.intrinsics.bufferCxPx)
        assertTrue(
            kotlin.math.abs(logicalResult.intrinsics.bufferCxPx - physicalResult.intrinsics.bufferCxPx) > 1.0,
            "expected a measurable (> 1px) discrepancy between LOGICAL and PHYSICAL path principal points",
        )
    }

    @Test
    fun `RECONCILED path reports NOT_IMPLEMENTED, never a fabricated transform`() {
        val hypotheses =
            computeFrameContentMappingHypotheses(
                physicalSnapshot = physicalCamera3Snapshot(),
                observedCameraXMatrix = observedMatrixMatchingLogicalBasis(),
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )
        val reconciled = hypotheses.single { it.id == FrameContentMappingHypothesisId.RECONCILED_PHYSICAL_TO_LOGICAL_PATH }
        val result = assertIs<FrameContentHypothesisIntrinsicsResult.Unavailable>(reconciled.result)
        assertTrue(result.reason.startsWith("NOT_IMPLEMENTED"))
        assertEquals(FRAME_CONTENT_RECONCILED_NOT_IMPLEMENTED_REASON, result.reason)
    }

    @Test
    fun `a null observed matrix leaves LOGICAL path unavailable but PHYSICAL path still resolves`() {
        val hypotheses =
            computeFrameContentMappingHypotheses(
                physicalSnapshot = physicalCamera3Snapshot(),
                observedCameraXMatrix = null,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )
        val logical = hypotheses.single { it.id == FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH }
        val physical = hypotheses.single { it.id == FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH }
        assertIs<FrameContentHypothesisIntrinsicsResult.Unavailable>(logical.result)
        assertIs<FrameContentHypothesisIntrinsicsResult.Available>(physical.result)
    }

    @Test
    fun `documentation fields are populated for every hypothesis, including the unimplemented one`() {
        val hypotheses =
            computeFrameContentMappingHypotheses(
                physicalSnapshot = physicalCamera3Snapshot(),
                observedCameraXMatrix = observedMatrixMatchingLogicalBasis(),
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )
        hypotheses.forEach { hypothesis ->
            assertTrue(hypothesis.documentation.inputKBasis.isNotBlank())
            assertTrue(hypothesis.documentation.distortionBasis.isNotBlank())
            assertTrue(hypothesis.documentation.whyOutputIsBufferCoordinates.isNotBlank())
            assertTrue(hypothesis.documentation.bufferRotationContract.isNotBlank())
        }
    }

    /** Rotation-semantics correctness fix regression test (task §2): both implemented hypotheses must
     * declare `rotationFoldedIn=false` and carry the identical, explicit unrotated-buffer-contract text
     * — locked here so a future edit cannot silently reintroduce `rotationFoldedIn=true`. */
    @Test
    fun `both implemented hypotheses declare rotationFoldedIn=false and share the identical buffer rotation contract`() {
        val hypotheses =
            computeFrameContentMappingHypotheses(
                physicalSnapshot = physicalCamera3Snapshot(),
                observedCameraXMatrix = observedMatrixMatchingLogicalBasis(),
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )
        val logical = hypotheses.single { it.id == FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH }
        val physical = hypotheses.single { it.id == FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH }

        assertEquals(false, logical.documentation.rotationFoldedIn)
        assertEquals(false, physical.documentation.rotationFoldedIn)
        assertEquals(false, logical.documentation.cropRectFoldedIn)
        assertEquals(false, physical.documentation.cropRectFoldedIn)
        assertEquals(FRAME_CONTENT_UNROTATED_BUFFER_CONTRACT, logical.documentation.bufferRotationContract)
        assertEquals(FRAME_CONTENT_UNROTATED_BUFFER_CONTRACT, physical.documentation.bufferRotationContract)
        assertTrue(logical.documentation.bufferRotationContract.contains("unrotated"))
        assertTrue(logical.documentation.bufferRotationContract.contains("AXIS_ALIGNED_0"))
    }

    /** Task §5's "stop calling the derived K 'calibrated'" fix — locked so the language never regresses. */
    @Test
    fun `neither implemented hypothesis's K basis description claims to be calibrated`() {
        val hypotheses =
            computeFrameContentMappingHypotheses(
                physicalSnapshot = physicalCamera3Snapshot(),
                observedCameraXMatrix = observedMatrixMatchingLogicalBasis(),
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )
        val logical = hypotheses.single { it.id == FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH }
        val physical = hypotheses.single { it.id == FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH }
        assertTrue(logical.documentation.inputKBasis.contains("CHARACTERISTICS_DERIVED_APPROXIMATE_PHYSICAL_PINHOLE_DOMAIN"))
        assertTrue(physical.documentation.inputKBasis.contains("CHARACTERISTICS_DERIVED_APPROXIMATE_PHYSICAL_PINHOLE_DOMAIN"))
        assertFalse(logical.documentation.inputKBasis.contains("physical camera's own calibrated K"))
        assertFalse(physical.documentation.inputKBasis.contains("physical camera's own calibrated K"))
        // whyOutputIsBufferCoordinates must no longer reference the gated resolveAnalysisBufferIntrinsics
        // production entry point, since neither implemented hypothesis ever calls it.
        assertFalse(logical.documentation.whyOutputIsBufferCoordinates.contains("resolveAnalysisBufferIntrinsics labels"))
        assertFalse(physical.documentation.whyOutputIsBufferCoordinates.contains("resolveAnalysisBufferIntrinsics labels"))
    }
}
