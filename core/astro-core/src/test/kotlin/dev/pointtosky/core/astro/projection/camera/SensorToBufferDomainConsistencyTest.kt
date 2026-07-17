package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pure JVM tests for [assessSensorToBufferDomainConsistency] (CAM-2c domain-consistency fix) — the
 * semantic source-to-buffer domain check kept deliberately separate from [classifySensorToBufferMatrix]'s
 * structural classification. The real Pixel 9 identity-matrix evidence
 * (`docs/validation/cam_2c_pixel9_evidence.md`) is the motivating fixture for the first two cases below.
 */
class SensorToBufferDomainConsistencyTest {
    private fun axisAlignedMatrix(
        scaleX: Double,
        scaleY: Double,
        translateX: Double = 0.0,
        translateY: Double = 0.0,
    ) = SensorToBufferMatrix3(
        m00 = scaleX, m01 = 0.0, m02 = translateX,
        m10 = 0.0, m11 = scaleY, m12 = translateY,
        m20 = 0.0, m21 = 0.0, m22 = 1.0,
    )

    @Test
    fun `an identity matrix over the real Pixel 9 domain is not Consistent`() {
        val identity = axisAlignedMatrix(1.0, 1.0)

        val result =
            assessSensorToBufferDomainConsistency(
                matrix = identity,
                sourceWidthPx = 4080,
                sourceHeightPx = 3072,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        assertEquals(SensorToBufferDomainConsistency.MAPPED_BOUNDS_MISMATCH, result.consistency)
        assertEquals(SensorToBufferDomainBounds(0.0, 0.0, 4080.0, 3072.0), result.mappedSourceBoundsPx)
        assertEquals(SensorToBufferDomainBounds(0.0, 0.0, 640.0, 480.0), result.expectedBufferBoundsPx)
    }

    @Test
    fun `the exact scale from the Pixel 9 active array to its analysis buffer is Consistent`() {
        val correctScale = axisAlignedMatrix(640.0 / 4080.0, 480.0 / 3072.0)

        val result =
            assessSensorToBufferDomainConsistency(
                matrix = correctScale,
                sourceWidthPx = 4080,
                sourceHeightPx = 3072,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        assertEquals(SensorToBufferDomainConsistency.CONSISTENT, result.consistency)
        assertNotNull(result.mappedSourceBoundsPx)
        val mapped = result.mappedSourceBoundsPx!!
        assertEquals(0.0, mapped.leftPx, DEFAULT_DOMAIN_CONSISTENCY_TOLERANCE_PX)
        assertEquals(0.0, mapped.topPx, DEFAULT_DOMAIN_CONSISTENCY_TOLERANCE_PX)
        assertEquals(640.0, mapped.rightPx, DEFAULT_DOMAIN_CONSISTENCY_TOLERANCE_PX)
        assertEquals(480.0, mapped.bottomPx, DEFAULT_DOMAIN_CONSISTENCY_TOLERANCE_PX)
    }

    @Test
    fun `a correct scale plus a translate representing a crop produces the exact expected mapped bounds`() {
        // A 2000x1000 source domain (e.g. an already-documented crop rectangle), scaled to a 500x300
        // buffer, plus a translate offset - checks the arithmetic, not the Consistent/Mismatch verdict.
        val scaleX = 500.0 / 2000.0
        val scaleY = 300.0 / 1000.0
        val matrix = axisAlignedMatrix(scaleX, scaleY, translateX = 10.0, translateY = 5.0)

        val result =
            assessSensorToBufferDomainConsistency(
                matrix = matrix,
                sourceWidthPx = 2000,
                sourceHeightPx = 1000,
                bufferWidthPx = 500,
                bufferHeightPx = 300,
            )

        val expectedMapped = SensorToBufferDomainBounds(10.0, 5.0, 510.0, 305.0)
        assertEquals(expectedMapped, result.mappedSourceBoundsPx)
        // The translate offset means the mapped bounds do not land on [0,0]-[500,300] - evidence only,
        // never proof the transform itself is wrong (see the function's own KDoc "known limitation").
        assertEquals(SensorToBufferDomainConsistency.MAPPED_BOUNDS_MISMATCH, result.consistency)
    }

    @Test
    fun `an extreme scale that overflows to a non-finite mapped coordinate is a typed rejection`() {
        val overflowing = axisAlignedMatrix(1.0e308, 1.0e308)

        val result =
            assessSensorToBufferDomainConsistency(
                matrix = overflowing,
                sourceWidthPx = 4080,
                sourceHeightPx = 3072,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        assertEquals(SensorToBufferDomainConsistency.NON_FINITE_MAPPED_BOUNDS, result.consistency)
        assertNull(result.mappedSourceBoundsPx)
        assertNull(result.expectedBufferBoundsPx)
    }

    @Test
    fun `a missing or non-positive source domain is reported as SOURCE_DOMAIN_UNAVAILABLE`() {
        val matrix = axisAlignedMatrix(1.0, 1.0)

        for (result in
            listOf(
                assessSensorToBufferDomainConsistency(matrix, null, 3072, 640, 480),
                assessSensorToBufferDomainConsistency(matrix, 4080, null, 640, 480),
                assessSensorToBufferDomainConsistency(matrix, 0, 3072, 640, 480),
                assessSensorToBufferDomainConsistency(matrix, 4080, -1, 640, 480),
            )
        ) {
            assertEquals(SensorToBufferDomainConsistency.SOURCE_DOMAIN_UNAVAILABLE, result.consistency)
            assertNull(result.mappedSourceBoundsPx)
            assertNull(result.expectedBufferBoundsPx)
        }
    }

    @Test
    fun `a missing or non-positive buffer domain is reported as BUFFER_DOMAIN_UNAVAILABLE`() {
        val matrix = axisAlignedMatrix(1.0, 1.0)

        for (result in
            listOf(
                assessSensorToBufferDomainConsistency(matrix, 4080, 3072, null, 480),
                assessSensorToBufferDomainConsistency(matrix, 4080, 3072, 640, null),
                assessSensorToBufferDomainConsistency(matrix, 4080, 3072, 0, 480),
                assessSensorToBufferDomainConsistency(matrix, 4080, 3072, 640, -1),
            )
        ) {
            assertEquals(SensorToBufferDomainConsistency.BUFFER_DOMAIN_UNAVAILABLE, result.consistency)
            assertNull(result.mappedSourceBoundsPx)
            assertNull(result.expectedBufferBoundsPx)
        }
    }

    @Test
    fun `a genuinely projective matrix is UNSUPPORTED_TRANSFORM_CLASS`() {
        val projective =
            SensorToBufferMatrix3(
                m00 = 1.0, m01 = 0.0, m02 = 0.0,
                m10 = 0.0, m11 = 1.0, m12 = 0.0,
                m20 = 0.001, m21 = 0.0, m22 = 1.0,
            )

        val result = assessSensorToBufferDomainConsistency(projective, 4080, 3072, 640, 480)

        assertEquals(SensorToBufferDomainConsistency.UNSUPPORTED_TRANSFORM_CLASS, result.consistency)
        assertNull(result.mappedSourceBoundsPx)
    }

    @Test
    fun `a mirrored or singular affine matrix is still forward-mappable, not UNSUPPORTED_TRANSFORM_CLASS`() {
        val mirrored = axisAlignedMatrix(-1.0, 1.0, translateX = 4080.0)
        val singular = axisAlignedMatrix(0.0, 1.0)

        val mirroredResult = assessSensorToBufferDomainConsistency(mirrored, 4080, 3072, 640, 480)
        val singularResult = assessSensorToBufferDomainConsistency(singular, 4080, 3072, 640, 480)

        assertEquals(SensorToBufferTransformClass.MIRRORED, classifySensorToBufferMatrix(mirrored))
        assertEquals(SensorToBufferTransformClass.SINGULAR, classifySensorToBufferMatrix(singular))
        assertEquals(SensorToBufferDomainConsistency.MAPPED_BOUNDS_MISMATCH, mirroredResult.consistency)
        assertEquals(SensorToBufferDomainConsistency.MAPPED_BOUNDS_MISMATCH, singularResult.consistency)
        assertNotNull(mirroredResult.mappedSourceBoundsPx)
        assertNotNull(singularResult.mappedSourceBoundsPx)
    }

    @Test
    fun `rejects a non-finite or negative tolerance`() {
        val matrix = axisAlignedMatrix(1.0, 1.0)
        assertFailsWith<IllegalArgumentException> {
            assessSensorToBufferDomainConsistency(matrix, 4080, 3072, 640, 480, tolerancePx = -1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            assessSensorToBufferDomainConsistency(matrix, 4080, 3072, 640, 480, tolerancePx = Double.NaN)
        }
    }
}
