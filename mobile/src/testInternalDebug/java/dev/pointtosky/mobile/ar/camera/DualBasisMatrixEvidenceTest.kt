package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `internalDebug`-only pure JVM tests for [assessDualBasisMatrixEvidence] — the recon's dual-basis
 * comparison (which labelled candidate basis, if any, explains the observed matrix under the
 * CameraX 1.4.2 implementation model). Includes recon fixtures 15 (equal arrays — numerically
 * indistinguishable, never "proven equal") and 16 (differing arrays — residuals identify the
 * better-predicting metadata basis).
 */
class DualBasisMatrixEvidenceTest {
    private fun basis(
        cameraId: String,
        role: CameraBasisRole,
        width: Int,
        height: Int,
        left: Int = 0,
        top: Int = 0,
    ) = CameraCoordinateBasis(
        cameraId = cameraId,
        cameraRole = role,
        coordinateSpace = CameraBasisCoordinateSpace.ACTIVE_ARRAY_NATIVE,
        rect = CameraBasisRect(leftPx = left, topPx = top, rightPx = left + width, bottomPx = top + height),
        metadataSource = CameraBasisMetadataSource.TEST_FIXTURE,
    )

    private val logical4080 = basis("0", CameraBasisRole.OPENED_LOGICAL_CAMERA, 4080, 3072)
    private val physical4080 = basis("2", CameraBasisRole.SELECTED_PHYSICAL_CAMERA, 4080, 3072)
    private val physical4000 = basis("2", CameraBasisRole.SELECTED_PHYSICAL_CAMERA, 4000, 3000)

    /** The real Pixel 9 CameraX 1.4.2 matrix — exactly the 4080x3072 model's output. */
    private val pixel9Matrix =
        SensorToBufferMatrix3(
            m00 = 0.1568627506494522, m01 = 0.0, m02 = 0.0,
            m10 = 0.0, m11 = 0.1568627506494522, m12 = -0.9411764740943909,
            m20 = 0.0, m21 = 0.0, m22 = 1.0,
        )

    private val identityMatrix =
        SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    @Test
    fun `fixture 15 - equal logical and physical arrays match both bases and are marked numerically indistinguishable`() {
        val evidence = assessDualBasisMatrixEvidence(pixel9Matrix, logical4080, physical4080, 640, 480)

        assertEquals(DualBasisComparisonVerdict.MATCHES_BOTH_BASES_NUMERICALLY_INDISTINGUISHABLE, evidence.comparisonVerdict)
        assertTrue(evidence.basesNumericallyIndistinguishable)
        // Identical rects -> identical residuals -> no "better" basis; a tie is never broken by guessing.
        assertNull(evidence.betterPredictingBasis)
        // The reason must carry the explicit not-proven-equal / not-frame-content caveat.
        assertTrue("NOT proof" in evidence.reason)
        assertTrue(evidence.evidenceLevels.contains(DualBasisEvidenceLevel.FRAME_CONTENT_CORRESPONDENCE_UNMEASURED))
        assertTrue(evidence.evidenceLevels.contains(DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH))
    }

    @Test
    fun `fixture 16 - differing arrays identify the better-predicting basis via residuals`() {
        val evidence = assessDualBasisMatrixEvidence(pixel9Matrix, logical4080, physical4000, 640, 480)

        assertEquals(DualBasisComparisonVerdict.MATCHES_LOGICAL_BASIS_ONLY, evidence.comparisonVerdict)
        assertEquals(MatrixBasisLabel.LOGICAL_OPENED_CAMERA_BASIS, evidence.betterPredictingBasis)
        assertFalse(evidence.basesNumericallyIndistinguishable)
        val logical = assertNotNull(evidence.logical)
        val physical = assertNotNull(evidence.physical)
        assertTrue(logical.matchesCameraX142Model == true)
        assertTrue(physical.matchesCameraX142Model == false)
        assertTrue(logical.maxMappedPointResidualPx!! < physical.maxMappedPointResidualPx!!)
        // Every assessment is explicitly labelled — never an unlabelled generic "active array" result.
        assertEquals(MatrixBasisLabel.LOGICAL_OPENED_CAMERA_BASIS, logical.basisLabel)
        assertEquals(MatrixBasisLabel.SELECTED_PHYSICAL_CAMERA_BASIS, physical.basisLabel)
        assertTrue(logical.basis.label().contains("cameraId=0"))
    }

    @Test
    fun `an identity matrix matches neither basis and reports large explicit residuals`() {
        val evidence = assessDualBasisMatrixEvidence(identityMatrix, logical4080, physical4000, 640, 480)

        assertEquals(DualBasisComparisonVerdict.MATCHES_NEITHER_BASIS, evidence.comparisonVerdict)
        assertTrue(evidence.logical!!.maxMappedPointResidualPx!! > 1000.0)
        assertTrue(evidence.logical!!.maxAbsCoefficientResidual!! > 0.5)
        assertFalse(evidence.evidenceLevels.contains(DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH))
    }

    @Test
    fun `a missing basis stays missing - the other is assessed alone, nothing is substituted`() {
        val evidence = assessDualBasisMatrixEvidence(pixel9Matrix, null, physical4080, 640, 480)

        assertNull(evidence.logical)
        assertNotNull(evidence.physical)
        assertEquals(DualBasisComparisonVerdict.MATCHES_PHYSICAL_BASIS_ONLY, evidence.comparisonVerdict)
        assertNull(evidence.betterPredictingBasis)
    }

    @Test
    fun `no matrix or no bases is a typed insufficient-input result`() {
        assertEquals(
            DualBasisComparisonVerdict.INSUFFICIENT_INPUT,
            assessDualBasisMatrixEvidence(null, logical4080, physical4080, 640, 480).comparisonVerdict,
        )
        assertEquals(
            DualBasisComparisonVerdict.INSUFFICIENT_INPUT,
            assessDualBasisMatrixEvidence(pixel9Matrix, null, null, 640, 480).comparisonVerdict,
        )
        assertEquals(
            DualBasisComparisonVerdict.INSUFFICIENT_INPUT,
            assessDualBasisMatrixEvidence(pixel9Matrix, logical4080, physical4080, 0, 480).comparisonVerdict,
        )
    }

    @Test
    fun `every result carries the frame-content-unmeasured evidence level and the no-proof statement`() {
        val results =
            listOf(
                assessDualBasisMatrixEvidence(pixel9Matrix, logical4080, physical4080, 640, 480),
                assessDualBasisMatrixEvidence(identityMatrix, logical4080, physical4000, 640, 480),
                assessDualBasisMatrixEvidence(null, null, null, null, null),
            )
        for (result in results) {
            assertTrue(result.evidenceLevels.contains(DualBasisEvidenceLevel.FRAME_CONTENT_CORRESPONDENCE_UNMEASURED))
            assertTrue(result.evidenceLevels.contains(DualBasisEvidenceLevel.API_DECLARED_DOMAIN))
        }
        // Matching results state explicitly that no proof variant is constructed from them.
        assertTrue("no SensorToBufferDomainProof" in results[0].reason)
    }
}
