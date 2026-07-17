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
 * ties a verified [PhysicalCameraBindingResolution] to a real [AnalysisBufferIntrinsicsResolution]
 * attempt, without ever touching [resolveAnalysisBufferIntrinsics]'s own logical-multi-camera guard -
 * these tests exist specifically to prove that a *physical* camera's own (non-logical) snapshot
 * reaches a calibrated `Resolved` outcome exactly like any ordinary single-sensor device would, and
 * that nothing here silently substitutes the logical camera's characteristics.
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

    private fun boundResolution(snapshot: CameraCharacteristicsSnapshot) =
        PhysicalCameraBindingResolution.Bound(
            provenance =
                PhysicalCameraProvenance(
                    logicalCameraId = "0",
                    physicalCameraId = requireNotNull(snapshot.cameraId),
                    bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
                    confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
                ),
            physicalCharacteristicsSnapshot = snapshot,
        )

    @Test
    fun `a verified physical binding with a full frame transform resolves calibrated intrinsics`() {
        val result =
            resolveCam2cForExplicitPhysicalCamera(
                binding = boundResolution(physicalSnapshot()),
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
    fun `an unbound (non-Bound) binding resolution is reported as a binding failure, never resolved`() {
        val result =
            resolveCam2cForExplicitPhysicalCamera(
                binding = PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified,
                sensorToBufferTransform = fullFrameTransform,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
            )

        val failure = assertIs<Cam2cPhysicalCameraResolution.BindingFailure>(result)
        assertEquals(PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified, failure.binding)
    }

    @Test
    fun `a missing sensor-to-buffer transform does not publish calibrated intrinsics even with a verified binding`() {
        val result =
            resolveCam2cForExplicitPhysicalCamera(
                binding = boundResolution(physicalSnapshot()),
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
