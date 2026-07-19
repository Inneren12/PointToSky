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

        assertEquals(DualBasisComparisonVerdict.MATCHES_BOTH_EQUAL_RECTS_NUMERICALLY_INDISTINGUISHABLE, evidence.comparisonVerdict)
        assertTrue(evidence.basesNumericallyIndistinguishable)
        // Identical rects -> identical residuals -> no "better" basis; a tie is never broken by guessing.
        assertNull(evidence.betterPredictingBasis)
        // The reason must carry the explicit not-proven-equal / not-frame-content caveat.
        assertTrue("NOT proof" in evidence.reason)
        assertTrue(evidence.evidenceLevels.contains(DualBasisEvidenceLevel.FRAME_CONTENT_CORRESPONDENCE_UNMEASURED))
        assertTrue(evidence.evidenceLevels.contains(DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH))
    }

    @Test
    fun `the verdict decision separates equal-rect dual matches from differing-rect ambiguous matches`() {
        // The pure verdict function covers all five outcomes, including the both-match/differing-
        // rects case that integer fixtures cannot reach through the model itself (two differing
        // integer rects always shift the prediction beyond the match tolerance — a real device is
        // not bound by that arithmetic, so the verdict must stay honest regardless).
        assertEquals(
            DualBasisComparisonVerdict.MATCHES_BOTH_EQUAL_RECTS_NUMERICALLY_INDISTINGUISHABLE,
            dualBasisComparisonVerdict(logicalMatches = true, physicalMatches = true, rectsEqual = true),
        )
        assertEquals(
            DualBasisComparisonVerdict.MATCHES_BOTH_DIFFERING_RECTS_WITHIN_TOLERANCE,
            dualBasisComparisonVerdict(logicalMatches = true, physicalMatches = true, rectsEqual = false),
        )
        assertEquals(
            DualBasisComparisonVerdict.MATCHES_LOGICAL_BASIS_ONLY,
            dualBasisComparisonVerdict(logicalMatches = true, physicalMatches = false, rectsEqual = false),
        )
        assertEquals(
            DualBasisComparisonVerdict.MATCHES_PHYSICAL_BASIS_ONLY,
            dualBasisComparisonVerdict(logicalMatches = false, physicalMatches = true, rectsEqual = false),
        )
        assertEquals(
            DualBasisComparisonVerdict.MATCHES_NEITHER_BASIS,
            dualBasisComparisonVerdict(logicalMatches = false, physicalMatches = false, rectsEqual = true),
        )
    }

    @Test
    fun `basesNumericallyIndistinguishable is pure rect identity, independent of match outcomes`() {
        // Equal rects + a matrix that matches NEITHER basis (identity): the flag must still be true —
        // it states the two candidate models are the same model, not that anything matched.
        val neitherMatches = assessDualBasisMatrixEvidence(identityMatrix, logical4080, physical4080, 640, 480)
        assertEquals(DualBasisComparisonVerdict.MATCHES_NEITHER_BASIS, neitherMatches.comparisonVerdict)
        assertTrue(neitherMatches.basesNumericallyIndistinguishable)

        // Differing rects: false, regardless of outcomes.
        val differingRects = assessDualBasisMatrixEvidence(pixel9Matrix, logical4080, physical4000, 640, 480)
        assertFalse(differingRects.basesNumericallyIndistinguishable)
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
    fun `a projective matrix with the predicted top two rows never matches the model`() {
        // P1 fix: upper rows identical to the real construction, but m20 != 0 — a projective map.
        // Comparing only the upper rows would give zero residual; the structural gate must refuse.
        val projectiveTopRowsMatch = pixel9Matrix.copy(m20 = 0.001)
        val evidence = assessDualBasisMatrixEvidence(projectiveTopRowsMatch, logical4080, physical4080, 640, 480)

        val logical = assertNotNull(evidence.logical)
        assertEquals(CameraX142ModelComparison.COMPARISON_UNSUPPORTED_STRUCTURE, logical.modelComparison)
        assertEquals(false, logical.matchesCameraX142Model)
        assertNull(logical.maxMappedPointResidualPx)
        // Coefficient residuals stay available and expose the out-of-scope term itself.
        assertEquals(0.001, logical.coefficientResiduals!![6], 1e-12)
        assertEquals(DualBasisComparisonVerdict.MATCHES_NEITHER_BASIS, evidence.comparisonVerdict)
        assertFalse(evidence.evidenceLevels.contains(DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH))
    }

    @Test
    fun `a non-unit m22 never matches the model`() {
        val nonUnitW = pixel9Matrix.copy(m22 = 1.001)
        val evidence = assessDualBasisMatrixEvidence(nonUnitW, logical4080, physical4080, 640, 480)
        assertEquals(CameraX142ModelComparison.COMPARISON_UNSUPPORTED_STRUCTURE, evidence.logical!!.modelComparison)
        assertFalse(evidence.evidenceLevels.contains(DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH))
    }

    @Test
    fun `a sheared or rotated matrix never matches the model`() {
        val sheared = pixel9Matrix.copy(m01 = 0.01)
        val evidence = assessDualBasisMatrixEvidence(sheared, logical4080, physical4080, 640, 480)
        assertEquals(CameraX142ModelComparison.COMPARISON_UNSUPPORTED_STRUCTURE, evidence.logical!!.modelComparison)
        assertEquals(false, evidence.logical!!.matchesCameraX142Model)
        assertEquals(DualBasisComparisonVerdict.MATCHES_NEITHER_BASIS, evidence.comparisonVerdict)
    }

    @Test
    fun `the real Pixel 9 axis-aligned fixture still matches after the structural gate`() {
        val evidence = assessDualBasisMatrixEvidence(pixel9Matrix, logical4080, physical4080, 640, 480)
        assertEquals(CameraX142ModelComparison.MATCHES_MODEL, evidence.logical!!.modelComparison)
        assertEquals(true, evidence.logical!!.matchesCameraX142Model)
        assertTrue(evidence.evidenceLevels.contains(DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH))
    }

    @Test
    fun `the mapped-point residual is Euclidean, not max-axis`() {
        // Shift the real construction by (3, 4) px: every mapped point displaces by exactly
        // hypot(3, 4) = 5 px. A Chebyshev/max-axis metric would report 4.
        val shifted = pixel9Matrix.copy(m02 = pixel9Matrix.m02 + 3.0, m12 = pixel9Matrix.m12 + 4.0)
        val evidence = assessDualBasisMatrixEvidence(shifted, logical4080, physical4080, 640, 480)
        assertEquals(5.0, evidence.logical!!.maxMappedPointResidualPx!!, 1e-9)
        assertEquals(CameraX142ModelComparison.DIFFERS_FROM_MODEL, evidence.logical!!.modelComparison)
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
