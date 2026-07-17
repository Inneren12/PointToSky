package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pure JVM tests for [resolveCam2cForExplicitPhysicalCamera] (task §5/§6/§11): the entry point that
 * ties a verified [PhysicalCameraBindingResolution] and a [SensorToBufferDomainProof] to a real
 * [AnalysisBufferIntrinsicsResolution] attempt, without ever touching [resolveAnalysisBufferIntrinsics]'s
 * own logical-multi-camera guard.
 *
 * These tests exist specifically to prove two independent things can never be conflated: (1) that a
 * *physical* camera's own (non-logical) snapshot reaches a calibrated `Resolved` outcome exactly like
 * an ordinary single-sensor device would, once its transform domain is *also* proven — physical-sensor
 * identity alone is never sufficient (fix for a P1 correctness gap: a verified binding with an
 * unresolved domain, even over a trivial identity matrix, must never resolve); and (2) that a binding
 * failure always wins over a domain-proof failure, never the reverse.
 */
class Cam2cPhysicalCameraResolutionTest {
    private val bufferWidthPx = 640
    private val bufferHeightPx = 480

    /** Pixel-9-like physical sub-camera: same round-number fixture family as
     * `AnalysisBufferIntrinsicsResolverTest`, but `isLogicalMultiCamera = false` and `cameraId` is a
     * *physical* id ("2") - never "0", the logical id `UnsupportedLogicalMultiCameraMapping` blocks. */
    private fun physicalSnapshot(
        cameraId: String = "2",
        isLogicalMultiCamera: Boolean = false,
    ) = CameraCharacteristicsSnapshot(
        availableFocalLengthsMm = floatArrayOf(3.6f),
        sensorPhysicalWidthMm = 6.4f,
        sensorPhysicalHeightMm = 4.8f,
        activeArrayLeftPx = 0,
        activeArrayTopPx = 0,
        activeArrayRightPx = 4032,
        activeArrayBottomPx = 3024,
        pixelArrayWidthPx = 4032,
        pixelArrayHeightPx = 3024,
        isLogicalMultiCamera = isLogicalMultiCamera,
        cameraId = cameraId,
    )

    private val fullFrameTransform =
        SensorToBufferMatrix3(
            m00 = bufferWidthPx / 4032.0, m01 = 0.0, m02 = 0.0,
            m10 = 0.0, m11 = bufferHeightPx / 3024.0, m12 = 0.0,
            m20 = 0.0, m21 = 0.0, m22 = 1.0,
        )

    private val identityMatrix = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    private fun boundResolution(snapshot: CameraCharacteristicsSnapshot) =
        PhysicalCameraBindingResolution.Bound(
            provenance =
                PhysicalCameraProvenance(
                    logicalCameraId = "0",
                    physicalCameraId = requireNotNull(snapshot.cameraId),
                    bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
                    bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
                    confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
                ),
            physicalCharacteristicsSnapshot = snapshot,
        )

    @Test
    fun `a verified physical binding with an explicitly proven domain and a valid transform resolves calibrated intrinsics`() {
        val result =
            resolveCam2cForExplicitPhysicalCamera(
                binding = boundResolution(physicalSnapshot()),
                domainProof = SensorToBufferDomainProof.ProvenActiveArrayLocal,
                sensorToBufferTransform = fullFrameTransform,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )

        val resolved = assertIs<Cam2cPhysicalCameraResolution.Resolved>(result)
        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, resolved.intrinsics.source)
        assertEquals(CameraIntrinsicsReference.AnalysisBuffer(bufferWidthPx, bufferHeightPx), resolved.intrinsics.reference)
        assertEquals(CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT, resolved.intrinsics.quality)
        assertEquals("2", resolved.provenance.physicalCameraId)
    }

    @Test
    fun `a verified physical binding with an unresolved domain is a typed domain failure, never Resolved`() {
        val result =
            resolveCam2cForExplicitPhysicalCamera(
                binding = boundResolution(physicalSnapshot()),
                domainProof = SensorToBufferDomainProof.Unresolved,
                sensorToBufferTransform = fullFrameTransform,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )

        val domainNotProven = assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(result)
        assertEquals(SensorToBufferDomainProof.Unresolved, domainNotProven.proof)
        assertEquals("2", domainNotProven.provenance.physicalCameraId)
    }

    @Test
    fun `a verified physical binding with a hypothesis mismatch is a typed domain failure, never Resolved`() {
        val mismatchProof = SensorToBufferDomainProof.HypothesisMismatch(WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH)

        val result =
            resolveCam2cForExplicitPhysicalCamera(
                binding = boundResolution(physicalSnapshot()),
                domainProof = mismatchProof,
                sensorToBufferTransform = fullFrameTransform,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )

        val domainNotProven = assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(result)
        assertEquals(mismatchProof, domainNotProven.proof)
    }

    @Test
    fun `an unresolved identity-matrix session never publishes AnalysisBuffer intrinsics despite a verified binding`() {
        // The exact real-Pixel-9 shape (docs/validation/cam_2c_pixel9_evidence.md §3): a trivial
        // identity sensor-to-buffer matrix that "looks fine" must never be treated as proof of its own
        // source domain - the domain proof is what gates resolution, not the matrix's own numbers.
        val result =
            resolveCam2cForExplicitPhysicalCamera(
                binding = boundResolution(physicalSnapshot()),
                domainProof = SensorToBufferDomainProof.Unresolved,
                sensorToBufferTransform = identityMatrix,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )

        assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(result)
    }

    @Test
    fun `a physical-camera-identity-proof-only domain (not active-array-local) still does not unlock resolution`() {
        // ProvenPreCorrectionActiveArrayLocal and ProvenAnalysisSourceDomain are proven bases, but not
        // the one resolveAnalysisBufferIntrinsics's own K-composition assumes - only
        // ProvenActiveArrayLocal does. Both must still be reported as DomainNotProven, not Resolved.
        val preCorrectionResult =
            resolveCam2cForExplicitPhysicalCamera(
                binding = boundResolution(physicalSnapshot()),
                domainProof = SensorToBufferDomainProof.ProvenPreCorrectionActiveArrayLocal,
                sensorToBufferTransform = fullFrameTransform,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )
        val generalResult =
            resolveCam2cForExplicitPhysicalCamera(
                binding = boundResolution(physicalSnapshot()),
                domainProof = SensorToBufferDomainProof.ProvenAnalysisSourceDomain("future basis"),
                sensorToBufferTransform = fullFrameTransform,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )

        assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(preCorrectionResult)
        assertIs<Cam2cPhysicalCameraResolution.DomainNotProven>(generalResult)
    }

    @Test
    fun `physical binding failure wins before domain resolution is even considered`() {
        val result =
            resolveCam2cForExplicitPhysicalCamera(
                binding = PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified,
                domainProof = SensorToBufferDomainProof.ProvenActiveArrayLocal,
                sensorToBufferTransform = fullFrameTransform,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )

        val failure = assertIs<Cam2cPhysicalCameraResolution.BindingFailure>(result)
        assertEquals(PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified, failure.binding)
    }

    @Test
    fun `a missing sensor-to-buffer transform does not publish calibrated intrinsics even with a proven domain`() {
        val result =
            resolveCam2cForExplicitPhysicalCamera(
                binding = boundResolution(physicalSnapshot()),
                domainProof = SensorToBufferDomainProof.ProvenActiveArrayLocal,
                sensorToBufferTransform = null,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )

        val failure = assertIs<Cam2cPhysicalCameraResolution.IntrinsicsFailure>(result)
        assertEquals(AnalysisBufferIntrinsicsResolution.MissingSensorToBufferTransform, failure.attempt)
        assertEquals("2", failure.provenance.physicalCameraId)
    }

    @Test
    fun `resolution uses the physical snapshot's own active array, not a substituted logical one`() {
        // A distinguishing active array (different from the shared 4032x3024 fixture) proves the exact
        // snapshot passed into Bound is the one resolveAnalysisBufferIntrinsics actually consumed -
        // never a different (e.g. logical-camera) snapshot silently substituted in.
        val distinguishingSnapshot =
            CameraCharacteristicsSnapshot(
                availableFocalLengthsMm = floatArrayOf(2.0f),
                sensorPhysicalWidthMm = 3.2f,
                sensorPhysicalHeightMm = 2.4f,
                activeArrayLeftPx = 0,
                activeArrayTopPx = 0,
                activeArrayRightPx = 2016,
                activeArrayBottomPx = 1512,
                pixelArrayWidthPx = 2016,
                pixelArrayHeightPx = 1512,
                isLogicalMultiCamera = false,
                cameraId = "4",
            )
        val halfFrameTransform =
            SensorToBufferMatrix3(
                m00 = bufferWidthPx / 2016.0, m01 = 0.0, m02 = 0.0,
                m10 = 0.0, m11 = bufferHeightPx / 1512.0, m12 = 0.0,
                m20 = 0.0, m21 = 0.0, m22 = 1.0,
            )

        val result =
            resolveCam2cForExplicitPhysicalCamera(
                binding = boundResolution(distinguishingSnapshot),
                domainProof = SensorToBufferDomainProof.ProvenActiveArrayLocal,
                sensorToBufferTransform = halfFrameTransform,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )

        val resolved = assertIs<Cam2cPhysicalCameraResolution.Resolved>(result)
        // fx for the 2.0mm/3.2mm-wide/2016px-wide fixture differs measurably from the 3.6mm/6.4mm/4032px
        // fixture used elsewhere in this file - if the wrong (logical/default) snapshot had been used,
        // this focal length would not match.
        assertEquals(2.0, resolved.intrinsics.focalLengthMm!!, 1e-9)
        assertEquals("4", resolved.provenance.physicalCameraId)
    }
}
