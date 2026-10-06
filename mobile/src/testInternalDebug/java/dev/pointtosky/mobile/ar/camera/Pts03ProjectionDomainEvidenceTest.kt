package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/**
 * PTS-03 Group B: a hypothesis that matches, a small frame-content residual, or a known physical ID must never
 * become `ProvenActiveArrayLocal`, and the CAM-2c unlock gate stays exactly as strict as before.
 */
class Pts03ProjectionDomainEvidenceTest {
    @Test
    fun `only an explicit Proven proof classifies as PROVEN`() {
        assertEquals(
            Pts03EvidenceOutcome.PROVEN,
            classifyPts03ProjectionDomain(SensorToBufferDomainProof.ProvenActiveArrayLocal),
        )
        assertEquals(
            Pts03EvidenceOutcome.PROVEN,
            classifyPts03ProjectionDomain(SensorToBufferDomainProof.ProvenPreCorrectionActiveArrayLocal),
        )
        assertEquals(
            Pts03EvidenceOutcome.PROVEN,
            classifyPts03ProjectionDomain(SensorToBufferDomainProof.ProvenAnalysisSourceDomain("x")),
        )
        assertEquals(
            Pts03EvidenceOutcome.UNRESOLVED,
            classifyPts03ProjectionDomain(SensorToBufferDomainProof.Unresolved),
        )
        assertEquals(
            Pts03EvidenceOutcome.MISMATCH,
            classifyPts03ProjectionDomain(
                SensorToBufferDomainProof.HypothesisMismatch(
                    WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH,
                ),
            ),
        )
    }

    @Test
    fun `a matched whole-active-array hypothesis plus a perfect frame-content fit stays UNRESOLVED`() {
        // Noiseless synthetic snapshot: the physical hypothesis matches and residuals are ~0.
        val snapshot = Pts03Fixtures.snapshot()
        val evidence =
            buildPts03ProjectionDomainEvidence(
                matrix = snapshot.sensorToBufferTransformMatrix,
                bufferWidthPx = snapshot.bufferWidthPx,
                bufferHeightPx = snapshot.bufferHeightPx,
                logical = Pts03Fixtures.logicalSnapshot(),
                physical = Pts03Fixtures.physicalSnapshot(),
                producingPhysicalCameraId = "3",
                logicalActivePhysicalCameraId = "2",
                requestedPhysicalCameraId = "3",
                frameContentVerdict = snapshot.verdict.verdict,
            )

        assertEquals(
            WholeActiveArrayHypothesisVerdict.MATCHES_WHOLE_ACTIVE_ARRAY_HYPOTHESIS,
            evidence.physicalWholeActiveArrayVerdict,
        )
        assertEquals(Pts03EvidenceOutcome.UNRESOLVED, evidence.outcome)
        assertEquals(SensorToBufferDomainProof.Unresolved, evidence.domainProof)
        assertFalse(evidence.unlocksCalibratedProjection)
    }

    @Test
    fun `PTS-03 evidence never unlocks resolveCam2cForExplicitPhysicalCamera`() {
        val snapshot = Pts03Fixtures.snapshot()
        val evidence =
            buildPts03ProjectionDomainEvidence(
                snapshot.sensorToBufferTransformMatrix,
                snapshot.bufferWidthPx,
                snapshot.bufferHeightPx,
                Pts03Fixtures.logicalSnapshot(),
                Pts03Fixtures.physicalSnapshot(),
                "3",
                null,
                "3",
                snapshot.verdict.verdict,
            )
        val binding =
            PhysicalCameraBindingResolution.Bound(
                Pts03Fixtures.provenance(),
                Pts03Fixtures.physicalSnapshot(),
            )
        val resolution =
            resolveCam2cForExplicitPhysicalCamera(
                binding = binding,
                domainProof = evidence.domainProof,
                sensorToBufferTransform = snapshot.sensorToBufferTransformMatrix,
                bufferWidthPx = snapshot.bufferWidthPx,
                bufferHeightPx = snapshot.bufferHeightPx,
            )
        assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(resolution)
    }

    @Test
    fun `a mismatching matrix is recorded as MISMATCH`() {
        val logicalMatrix = Pts03Fixtures.snapshot().sensorToBufferTransformMatrix!!
        // Physical arrays twice the size: the matrix (built for 4032x3024) maps them far outside the buffer.
        val bigPhysical = Pts03Fixtures.physicalSnapshot().copy(activeArrayRightPx = 8064, activeArrayBottomPx = 6048)
        val evidence =
            buildPts03ProjectionDomainEvidence(
                logicalMatrix,
                640,
                480,
                Pts03Fixtures.logicalSnapshot(),
                bigPhysical,
                "3",
                null,
                "3",
                null,
            )
        assertEquals(Pts03EvidenceOutcome.MISMATCH, evidence.outcome)
        assertFalse(evidence.unlocksCalibratedProjection)
    }

    @Test
    fun `an unidentified producing camera is UNRESOLVED even when the logical hypothesis is assessable`() {
        val matrix = Pts03Fixtures.snapshot().sensorToBufferTransformMatrix
        val evidence =
            buildPts03ProjectionDomainEvidence(
                matrix,
                640,
                480,
                Pts03Fixtures.logicalSnapshot(),
                null,
                null,
                null,
                null,
                null,
            )
        assertEquals(Pts03EvidenceOutcome.UNRESOLVED, evidence.outcome)
    }
}
