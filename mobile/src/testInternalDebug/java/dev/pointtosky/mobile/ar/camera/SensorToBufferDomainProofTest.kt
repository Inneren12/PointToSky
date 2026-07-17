package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [SensorToBufferDomainProof.unlocksAnalysisBufferResolution] and
 * [evidenceOnlySensorToBufferDomainProof] (task: "preserve the existing whole-active-array hypothesis
 * diagnostic as evidence only; it must not become proof").
 */
class SensorToBufferDomainProofTest {
    @Test
    fun `only ProvenActiveArrayLocal unlocks AnalysisBuffer resolution`() {
        assertTrue(SensorToBufferDomainProof.ProvenActiveArrayLocal.unlocksAnalysisBufferResolution())
        assertFalse(SensorToBufferDomainProof.ProvenPreCorrectionActiveArrayLocal.unlocksAnalysisBufferResolution())
        assertFalse(SensorToBufferDomainProof.ProvenAnalysisSourceDomain("basis").unlocksAnalysisBufferResolution())
        assertFalse(SensorToBufferDomainProof.Unresolved.unlocksAnalysisBufferResolution())
        assertFalse(
            SensorToBufferDomainProof
                .HypothesisMismatch(WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH)
                .unlocksAnalysisBufferResolution(),
        )
    }

    @Test
    fun `a null matrix is Unresolved without attempting a hypothesis assessment`() {
        val proof =
            evidenceOnlySensorToBufferDomainProof(
                matrix = null,
                activeArrayWidthPx = 4032,
                activeArrayHeightPx = 3024,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        assertEquals(SensorToBufferDomainProof.Unresolved, proof)
    }

    @Test
    fun `a real-Pixel-9-shaped identity matrix mismatching the whole-active-array hypothesis is HypothesisMismatch`() {
        // docs/validation/cam_2c_pixel9_evidence.md §3: identity matrix, 4080x3072 active array, 640x480 buffer.
        val identityMatrix = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val proof =
            evidenceOnlySensorToBufferDomainProof(
                matrix = identityMatrix,
                activeArrayWidthPx = 4080,
                activeArrayHeightPx = 3072,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        val mismatch = assertIs<SensorToBufferDomainProof.HypothesisMismatch>(proof)
        assertEquals(WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH, mismatch.verdict)
        assertFalse(mismatch.unlocksAnalysisBufferResolution())
    }

    @Test
    fun `a matrix that matches the whole-active-array hypothesis is still Unresolved, never promoted to proof`() {
        // A uniform-scale, no-crop matrix that DOES match the hypothesis (task requirement: "preserve
        // the existing whole-active-array hypothesis diagnostic as evidence only; it must not become
        // proof" - even a match is not proof).
        val matchingMatrix =
            SensorToBufferMatrix3(
                m00 = 640.0 / 4032.0, m01 = 0.0, m02 = 0.0,
                m10 = 0.0, m11 = 480.0 / 3024.0, m12 = 0.0,
                m20 = 0.0, m21 = 0.0, m22 = 1.0,
            )

        val proof =
            evidenceOnlySensorToBufferDomainProof(
                matrix = matchingMatrix,
                activeArrayWidthPx = 4032,
                activeArrayHeightPx = 3024,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        assertEquals(SensorToBufferDomainProof.Unresolved, proof)
        assertFalse(proof.unlocksAnalysisBufferResolution())
    }

    @Test
    fun `missing active array dimensions are Unresolved, not a crash or a fabricated match`() {
        val identityMatrix = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

        val proof =
            evidenceOnlySensorToBufferDomainProof(
                matrix = identityMatrix,
                activeArrayWidthPx = null,
                activeArrayHeightPx = null,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        assertEquals(SensorToBufferDomainProof.Unresolved, proof)
    }
}
