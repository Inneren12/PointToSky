package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pure JVM validation tests for [CameraIntrinsics]. Locks the CAM-1b contract: FOVs must be
 * finite and strictly within `0 < fov < 180`; the optional physical dimensions (focal length,
 * sensor width/height), when present, must be finite and *strictly positive* (a physical
 * dimension can never be zero); the optional principal-point image coordinates, when present,
 * must be finite and *non-negative* (an image coordinate legitimately starts at pixel `0`).
 * Invalid values are rejected eagerly (constructor throws), never clamped.
 */
class CameraIntrinsicsTest {
    /** The only [CameraIntrinsicsReference] each [CameraIntrinsicsSource] is allowed to carry (mirrors `CameraIntrinsics.init`). */
    private fun defaultReferenceFor(source: CameraIntrinsicsSource): CameraIntrinsicsReference =
        when (source) {
            CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, CameraIntrinsicsSource.CAMERA_INTRINSIC_CALIBRATION ->
                CameraIntrinsicsReference.PhysicalSensor
            CameraIntrinsicsSource.LEGACY_FALLBACK -> CameraIntrinsicsReference.AnalysisBuffer(1920, 1080)
        }

    private fun valid(
        horizontalFovDeg: Double = 60.0,
        verticalFovDeg: Double = 45.0,
        focalLengthMm: Double? = 4.25,
        sensorWidthMm: Double? = 5.76,
        sensorHeightMm: Double? = 4.29,
        principalPointXPx: Double? = 960.0,
        principalPointYPx: Double? = 540.0,
        source: CameraIntrinsicsSource = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
        reference: CameraIntrinsicsReference = defaultReferenceFor(source),
    ) = CameraIntrinsics(
        horizontalFovDeg = horizontalFovDeg,
        verticalFovDeg = verticalFovDeg,
        focalLengthMm = focalLengthMm,
        sensorWidthMm = sensorWidthMm,
        sensorHeightMm = sensorHeightMm,
        principalPointXPx = principalPointXPx,
        principalPointYPx = principalPointYPx,
        source = source,
        reference = reference,
    )

    @Test
    fun `valid intrinsics with all fields populated constructs successfully`() {
        val intrinsics = valid()
        assertEquals(60.0, intrinsics.horizontalFovDeg)
        assertEquals(45.0, intrinsics.verticalFovDeg)
        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, intrinsics.source)
    }

    @Test
    fun `optional physical fields may all be null`() {
        val intrinsics =
            valid(
                focalLengthMm = null,
                sensorWidthMm = null,
                sensorHeightMm = null,
                principalPointXPx = null,
                principalPointYPx = null,
                source = CameraIntrinsicsSource.LEGACY_FALLBACK,
            )
        assertEquals(null, intrinsics.focalLengthMm)
        assertEquals(null, intrinsics.sensorWidthMm)
        assertEquals(null, intrinsics.sensorHeightMm)
        assertEquals(null, intrinsics.principalPointXPx)
        assertEquals(null, intrinsics.principalPointYPx)
    }

    @Test
    fun `all CameraIntrinsicsSource values are constructible`() {
        CameraIntrinsicsSource.entries.forEach { source -> valid(source = source) }
    }

    @Test
    fun `horizontal FOV of zero, negative, 180, above 180, NaN, or infinite is rejected`() {
        listOf(0.0, -1.0, 180.0, 200.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("horizontalFovDeg=$bad") { valid(horizontalFovDeg = bad) }
        }
    }

    @Test
    fun `vertical FOV of zero, negative, 180, above 180, NaN, or infinite is rejected`() {
        listOf(0.0, -1.0, 180.0, 200.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("verticalFovDeg=$bad") { valid(verticalFovDeg = bad) }
        }
    }

    @Test
    fun `focal length of zero, negative, NaN, or infinite is rejected when present`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("focalLengthMm=$bad") { valid(focalLengthMm = bad) }
        }
    }

    @Test
    fun `sensor width and height of zero, negative, NaN, or infinite are rejected when present`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("sensorWidthMm=$bad") { valid(sensorWidthMm = bad) }
            assertFailsWith<IllegalArgumentException>("sensorHeightMm=$bad") { valid(sensorHeightMm = bad) }
        }
    }

    @Test
    fun `principal point of zero is accepted, unlike the strictly-positive physical fields`() {
        val xAtZero = valid(principalPointXPx = 0.0)
        assertEquals(0.0, xAtZero.principalPointXPx)

        val yAtZero = valid(principalPointYPx = 0.0)
        assertEquals(0.0, yAtZero.principalPointYPx)

        val bothAtZero = valid(principalPointXPx = 0.0, principalPointYPx = 0.0)
        assertEquals(0.0, bothAtZero.principalPointXPx)
        assertEquals(0.0, bothAtZero.principalPointYPx)
    }

    @Test
    fun `principal point of negative, NaN, or infinite is rejected when present`() {
        listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("principalPointXPx=$bad") { valid(principalPointXPx = bad) }
            assertFailsWith<IllegalArgumentException>("principalPointYPx=$bad") { valid(principalPointYPx = bad) }
        }
    }

    @Test
    fun `focal length and sensor dimensions continue rejecting zero unlike principal point`() {
        assertFailsWith<IllegalArgumentException> { valid(focalLengthMm = 0.0) }
        assertFailsWith<IllegalArgumentException> { valid(sensorWidthMm = 0.0) }
        assertFailsWith<IllegalArgumentException> { valid(sensorHeightMm = 0.0) }
    }

    // --- reference: source/reference cross-field consistency (CAM-2a intrinsics-provenance hardening) ---

    @Test
    fun `CAMERA_CHARACTERISTICS with PhysicalSensor reference is accepted`() {
        val intrinsics = valid(source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, reference = CameraIntrinsicsReference.PhysicalSensor)
        assertEquals(CameraIntrinsicsReference.PhysicalSensor, intrinsics.reference)
    }

    @Test
    fun `CAMERA_CHARACTERISTICS with an AnalysisBuffer reference is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            valid(source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, reference = CameraIntrinsicsReference.AnalysisBuffer(1920, 1080))
        }
    }

    @Test
    fun `CAMERA_INTRINSIC_CALIBRATION with an AnalysisBuffer reference is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            valid(source = CameraIntrinsicsSource.CAMERA_INTRINSIC_CALIBRATION, reference = CameraIntrinsicsReference.AnalysisBuffer(1920, 1080))
        }
    }

    @Test
    fun `LEGACY_FALLBACK with an AnalysisBuffer reference is accepted`() {
        val intrinsics =
            valid(source = CameraIntrinsicsSource.LEGACY_FALLBACK, reference = CameraIntrinsicsReference.AnalysisBuffer(1000, 500))
        assertEquals(CameraIntrinsicsReference.AnalysisBuffer(1000, 500), intrinsics.reference)
    }

    @Test
    fun `LEGACY_FALLBACK with an Unspecified reference is accepted`() {
        val intrinsics = valid(source = CameraIntrinsicsSource.LEGACY_FALLBACK, reference = CameraIntrinsicsReference.Unspecified)
        assertEquals(CameraIntrinsicsReference.Unspecified, intrinsics.reference)
    }

    @Test
    fun `LEGACY_FALLBACK with a PhysicalSensor reference is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            valid(source = CameraIntrinsicsSource.LEGACY_FALLBACK, reference = CameraIntrinsicsReference.PhysicalSensor)
        }
    }

    @Test
    fun `AnalysisBuffer rejects zero or negative widthPx or heightPx`() {
        assertFailsWith<IllegalArgumentException> { CameraIntrinsicsReference.AnalysisBuffer(0, 500) }
        assertFailsWith<IllegalArgumentException> { CameraIntrinsicsReference.AnalysisBuffer(1000, 0) }
        assertFailsWith<IllegalArgumentException> { CameraIntrinsicsReference.AnalysisBuffer(-1, 500) }
        assertFailsWith<IllegalArgumentException> { CameraIntrinsicsReference.AnalysisBuffer(1000, -1) }
    }

    @Test
    fun `AnalysisBuffer with equal widthPx and heightPx values are structurally equal`() {
        assertEquals(CameraIntrinsicsReference.AnalysisBuffer(1000, 500), CameraIntrinsicsReference.AnalysisBuffer(1000, 500))
    }
}
