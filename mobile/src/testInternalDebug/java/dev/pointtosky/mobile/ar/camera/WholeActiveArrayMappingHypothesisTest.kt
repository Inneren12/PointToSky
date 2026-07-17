package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.core.astro.projection.camera.classifySensorToBufferMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `internalDebug`-only pure JVM tests for [assessWholeActiveArrayMappingHypothesis] — a check of exactly
 * one named hypothesis (that a [SensorToBufferMatrix3]'s source domain is the *complete*
 * `SENSOR_INFO_ACTIVE_ARRAY_SIZE`-local rectangle), never a general validity or semantic-consistency
 * verdict on the matrix itself. The real Pixel 9 identity-matrix evidence
 * (`docs/validation/cam_2c_pixel9_evidence.md`) is the motivating fixture for the first two cases below.
 */
class WholeActiveArrayMappingHypothesisTest {
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
    fun `an identity matrix over the real Pixel 9 domain does not match the whole-active-array hypothesis`() {
        val identity = axisAlignedMatrix(1.0, 1.0)

        val result =
            assessWholeActiveArrayMappingHypothesis(
                matrix = identity,
                sourceWidthPx = 4080,
                sourceHeightPx = 3072,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        assertEquals(WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH, result.verdict)
        assertEquals(SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL, result.sourceDomainBasis)
        assertEquals(SensorToBufferDomainBounds(0.0, 0.0, 4080.0, 3072.0), result.mappedAssumedSourceBoundsPx)
        assertEquals(SensorToBufferDomainBounds(0.0, 0.0, 640.0, 480.0), result.expectedBufferBoundsPx)
    }

    @Test
    fun `a hypothesis mismatch never claims the matrix itself is invalid, unusable, or broken`() {
        val identity = axisAlignedMatrix(1.0, 1.0)

        val result =
            assessWholeActiveArrayMappingHypothesis(
                matrix = identity,
                sourceWidthPx = 4080,
                sourceHeightPx = 3072,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        assertEquals(WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH, result.verdict)
        // The reason legitimately discusses words like "invalid"/"unusable" only inside an explicit
        // disclaimer ("does not establish the matrix itself is ...") - so this checks for that disclaimer
        // being present, not for the bare absence of those words.
        assertTrue(
            result.reason.contains("does not establish the matrix itself is", ignoreCase = true),
            "reason should explicitly disclaim any judgement about the matrix itself; was: ${result.reason}",
        )
        for (bareClaim in listOf("matrix is invalid", "matrix is unusable", "matrix is broken", "known defect")) {
            assertFalse(
                result.reason.contains(bareClaim, ignoreCase = true),
                "reason must never assert a bare claim like \"$bareClaim\"; was: ${result.reason}",
            )
        }
        assertTrue(result.reason.contains("hypothesis", ignoreCase = true), "reason should name the hypothesis being tested; was: ${result.reason}")
    }

    @Test
    fun `the exact scale from the Pixel 9 active array to its analysis buffer matches the whole-active-array hypothesis`() {
        val correctScale = axisAlignedMatrix(640.0 / 4080.0, 480.0 / 3072.0)

        val result =
            assessWholeActiveArrayMappingHypothesis(
                matrix = correctScale,
                sourceWidthPx = 4080,
                sourceHeightPx = 3072,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        assertEquals(WholeActiveArrayHypothesisVerdict.MATCHES_WHOLE_ACTIVE_ARRAY_HYPOTHESIS, result.verdict)
        assertEquals(SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL, result.sourceDomainBasis)
        assertNotNull(result.mappedAssumedSourceBoundsPx)
        val mapped = result.mappedAssumedSourceBoundsPx!!
        assertEquals(0.0, mapped.leftPx, DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX)
        assertEquals(0.0, mapped.topPx, DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX)
        assertEquals(640.0, mapped.rightPx, DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX)
        assertEquals(480.0, mapped.bottomPx, DEFAULT_WHOLE_ACTIVE_ARRAY_HYPOTHESIS_TOLERANCE_PX)
    }

    @Test
    fun `a correct scale plus a translate representing a crop produces the exact expected mapped bounds`() {
        // A 2000x1000 source domain (e.g. an already-documented crop rectangle), scaled to a 500x300
        // buffer, plus a translate offset - checks the arithmetic, not the verdict.
        val scaleX = 500.0 / 2000.0
        val scaleY = 300.0 / 1000.0
        val matrix = axisAlignedMatrix(scaleX, scaleY, translateX = 10.0, translateY = 5.0)

        val result =
            assessWholeActiveArrayMappingHypothesis(
                matrix = matrix,
                sourceWidthPx = 2000,
                sourceHeightPx = 1000,
                bufferWidthPx = 500,
                bufferHeightPx = 300,
            )

        val expectedMapped = SensorToBufferDomainBounds(10.0, 5.0, 510.0, 305.0)
        assertEquals(expectedMapped, result.mappedAssumedSourceBoundsPx)
        // The translate offset means the mapped bounds do not land on [0,0]-[500,300] under this
        // hypothesis - evidence only, never proof the transform itself is wrong.
        assertEquals(WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH, result.verdict)
    }

    @Test
    fun `an extreme scale that overflows to a non-finite mapped coordinate is a typed rejection with buffer bounds still preserved`() {
        val overflowing = axisAlignedMatrix(1.0e308, 1.0e308)

        val result =
            assessWholeActiveArrayMappingHypothesis(
                matrix = overflowing,
                sourceWidthPx = 4080,
                sourceHeightPx = 3072,
                bufferWidthPx = 640,
                bufferHeightPx = 480,
            )

        assertEquals(WholeActiveArrayHypothesisVerdict.NON_FINITE_MAPPED_BOUNDS, result.verdict)
        assertNull(result.mappedAssumedSourceBoundsPx)
        // expectedBufferBoundsPx is preserved whenever the buffer domain itself is valid, regardless of
        // whether the source-side mapping could complete - see the assessment's own KDoc contract.
        assertEquals(SensorToBufferDomainBounds(0.0, 0.0, 640.0, 480.0), result.expectedBufferBoundsPx)
    }

    @Test
    fun `a missing or non-positive source domain is reported as SOURCE_METADATA_UNAVAILABLE, with buffer bounds still preserved`() {
        val matrix = axisAlignedMatrix(1.0, 1.0)

        for (result in
            listOf(
                assessWholeActiveArrayMappingHypothesis(matrix, null, 3072, 640, 480),
                assessWholeActiveArrayMappingHypothesis(matrix, 4080, null, 640, 480),
                assessWholeActiveArrayMappingHypothesis(matrix, 0, 3072, 640, 480),
                assessWholeActiveArrayMappingHypothesis(matrix, 4080, -1, 640, 480),
            )
        ) {
            assertEquals(WholeActiveArrayHypothesisVerdict.SOURCE_METADATA_UNAVAILABLE, result.verdict)
            assertNull(result.mappedAssumedSourceBoundsPx)
            // Buffer bounds are known-valid here (640x480), so they are preserved even though the
            // source-side assessment could not complete.
            assertEquals(SensorToBufferDomainBounds(0.0, 0.0, 640.0, 480.0), result.expectedBufferBoundsPx)
        }
    }

    @Test
    fun `a missing or non-positive buffer domain is reported as BUFFER_METADATA_UNAVAILABLE, with no buffer bounds to report`() {
        val matrix = axisAlignedMatrix(1.0, 1.0)

        for (result in
            listOf(
                assessWholeActiveArrayMappingHypothesis(matrix, 4080, 3072, null, 480),
                assessWholeActiveArrayMappingHypothesis(matrix, 4080, 3072, 640, null),
                assessWholeActiveArrayMappingHypothesis(matrix, 4080, 3072, 0, 480),
                assessWholeActiveArrayMappingHypothesis(matrix, 4080, 3072, 640, -1),
            )
        ) {
            assertEquals(WholeActiveArrayHypothesisVerdict.BUFFER_METADATA_UNAVAILABLE, result.verdict)
            assertNull(result.mappedAssumedSourceBoundsPx)
            assertNull(result.expectedBufferBoundsPx)
        }
    }

    @Test
    fun `when both source and buffer metadata are invalid, BUFFER_METADATA_UNAVAILABLE wins - never SOURCE_METADATA_UNAVAILABLE with a null buffer rectangle`() {
        val matrix = axisAlignedMatrix(1.0, 1.0)

        for (result in
            listOf(
                // source missing + buffer missing
                assessWholeActiveArrayMappingHypothesis(matrix, null, null, null, null),
                assessWholeActiveArrayMappingHypothesis(matrix, null, 3072, null, 480),
                assessWholeActiveArrayMappingHypothesis(matrix, 4080, null, 640, null),
                // source non-positive + buffer non-positive
                assessWholeActiveArrayMappingHypothesis(matrix, 0, -1, 0, -1),
                assessWholeActiveArrayMappingHypothesis(matrix, -1, 3072, -1, 480),
                assessWholeActiveArrayMappingHypothesis(matrix, 4080, 0, 640, 0),
            )
        ) {
            // Buffer-first precedence: BUFFER_METADATA_UNAVAILABLE must win whenever the buffer is
            // invalid, regardless of whether the source is also invalid - this is what
            // WholeActiveArrayMappingAssessment.expectedBufferBoundsPx's own contract ("null only for
            // BUFFER_METADATA_UNAVAILABLE") requires; SOURCE_METADATA_UNAVAILABLE must never be reached
            // with a null buffer rectangle.
            assertEquals(WholeActiveArrayHypothesisVerdict.BUFFER_METADATA_UNAVAILABLE, result.verdict)
            assertNull(result.mappedAssumedSourceBoundsPx)
            assertNull(result.expectedBufferBoundsPx)
        }
    }

    @Test
    fun `a genuinely projective matrix is UNSUPPORTED_TRANSFORM_CLASS, with buffer bounds still preserved`() {
        val projective =
            SensorToBufferMatrix3(
                m00 = 1.0, m01 = 0.0, m02 = 0.0,
                m10 = 0.0, m11 = 1.0, m12 = 0.0,
                m20 = 0.001, m21 = 0.0, m22 = 1.0,
            )

        val result = assessWholeActiveArrayMappingHypothesis(projective, 4080, 3072, 640, 480)

        assertEquals(WholeActiveArrayHypothesisVerdict.UNSUPPORTED_TRANSFORM_CLASS, result.verdict)
        assertNull(result.mappedAssumedSourceBoundsPx)
        assertEquals(SensorToBufferDomainBounds(0.0, 0.0, 640.0, 480.0), result.expectedBufferBoundsPx)
    }

    @Test
    fun `a mirrored or singular affine matrix is still forward-mappable, not UNSUPPORTED_TRANSFORM_CLASS`() {
        val mirrored = axisAlignedMatrix(-1.0, 1.0, translateX = 4080.0)
        val singular = axisAlignedMatrix(0.0, 1.0)

        val mirroredResult = assessWholeActiveArrayMappingHypothesis(mirrored, 4080, 3072, 640, 480)
        val singularResult = assessWholeActiveArrayMappingHypothesis(singular, 4080, 3072, 640, 480)

        assertEquals(SensorToBufferTransformClass.MIRRORED, classifySensorToBufferMatrix(mirrored))
        assertEquals(SensorToBufferTransformClass.SINGULAR, classifySensorToBufferMatrix(singular))
        assertEquals(WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH, mirroredResult.verdict)
        assertEquals(WholeActiveArrayHypothesisVerdict.WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH, singularResult.verdict)
        assertNotNull(mirroredResult.mappedAssumedSourceBoundsPx)
        assertNotNull(singularResult.mappedAssumedSourceBoundsPx)
    }

    @Test
    fun `every verdict carries the explicit assumed source-domain basis`() {
        val matrix = axisAlignedMatrix(1.0, 1.0)
        val fixtures =
            listOf(
                assessWholeActiveArrayMappingHypothesis(matrix, 4080, 3072, 640, 480),
                assessWholeActiveArrayMappingHypothesis(matrix, null, 3072, 640, 480),
                assessWholeActiveArrayMappingHypothesis(matrix, 4080, 3072, null, 480),
            )
        for (result in fixtures) {
            assertEquals(SourceDomainBasis.ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL, result.sourceDomainBasis)
        }
    }

    @Test
    fun `rejects a non-finite or negative tolerance`() {
        val matrix = axisAlignedMatrix(1.0, 1.0)
        assertFailsWith<IllegalArgumentException> {
            assessWholeActiveArrayMappingHypothesis(matrix, 4080, 3072, 640, 480, tolerancePx = -1.0)
        }
        assertFailsWith<IllegalArgumentException> {
            assessWholeActiveArrayMappingHypothesis(matrix, 4080, 3072, 640, 480, tolerancePx = Double.NaN)
        }
    }
}
