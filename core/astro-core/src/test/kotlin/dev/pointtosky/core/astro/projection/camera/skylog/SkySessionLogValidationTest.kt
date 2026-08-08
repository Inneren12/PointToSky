package dev.pointtosky.core.astro.projection.camera.skylog

import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SKY-1: the narrow validation boundary — a value that cannot represent what it claims to must be
 * refused at construction, and a malformed *line* must surface as [SkySessionLogLine.Unreadable]
 * rather than as an exception escaping the parser.
 */
class SkySessionLogValidationTest {
    private val fixtures = SkySessionLogFixtures

    private fun rotationAboutZ(angleRad: Double): List<Double> =
        listOf(
            cos(angleRad),
            -sin(angleRad),
            0.0,
            sin(angleRad),
            cos(angleRad),
            0.0,
            0.0,
            0.0,
            1.0,
        )

    private fun pose(matrix: List<Double>) =
        SkyPoseSample(timestampNanos = 0L, rotationMatrix = matrix, frameToPoseDeltaNanos = 0L)

    // -----------------------------------------------------------------------------------------
    // Rotation matrices
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a real rotation is accepted`() {
        (0..35).forEach { step -> pose(rotationAboutZ(step * 10.0 * PI / 180.0)) }
    }

    @Test
    fun `an all-zero matrix is refused before it can become a quaternion`() {
        val exception = assertFailsWith<IllegalArgumentException> { pose(List(9) { 0.0 }) }

        assertTrue(exception.message.orEmpty().contains("unit length"))
    }

    @Test
    fun `a uniformly scaled rotation is refused`() {
        assertFailsWith<IllegalArgumentException> { pose(rotationAboutZ(0.3).map { it * 2.0 }) }
    }

    @Test
    fun `a reflection is refused rather than silently mirroring every star`() {
        // Negating one column turns a rotation into a reflection: rows stay unit length and mutually
        // orthogonal, so only the determinant check catches it.
        val reflected = rotationAboutZ(0.4).toMutableList()
        listOf(2, 5, 8).forEach { reflected[it] = -reflected[it] }

        val exception = assertFailsWith<IllegalArgumentException> { pose(reflected) }

        assertTrue(exception.message.orEmpty().contains("determinant"))
    }

    @Test
    fun `non-orthogonal rows are refused`() {
        val sheared = listOf(1.0, 0.0, 0.0, 0.5, 0.8660254037844386, 0.0, 0.0, 0.0, 1.0)

        assertFailsWith<IllegalArgumentException> { pose(sheared) }
    }

    @Test
    fun `a non-finite element is refused`() {
        assertFailsWith<IllegalArgumentException> { pose(List(9) { if (it == 4) Double.NaN else 0.0 }) }
        assertFailsWith<IllegalArgumentException> {
            pose(rotationAboutZ(0.0).toMutableList().also { it[0] = Double.POSITIVE_INFINITY })
        }
    }

    @Test
    fun `float-precision drift stays accepted`() {
        // What a real SensorManager matrix looks like after Float round-tripping: orthonormal to ~1e-7,
        // never exactly so. Rejecting this would reject every real device.
        val drifted = rotationAboutZ(0.7).map { it.toFloat().toDouble() }

        pose(drifted)
    }

    @Test
    fun `every accepted rotation derives a finite unit quaternion`() {
        val angles = (0..71).map { it * 5.0 * PI / 180.0 }

        angles.forEach { angle ->
            val q = pose(rotationAboutZ(angle)).quaternion
            val norm = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w
            assertTrue(q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite(), "angle=$angle")
            assertEquals(1.0, norm, absoluteTolerance = 1e-9, message = "angle=$angle")
        }
    }

    @Test
    fun `a matrix at the exact 180-degree branch derives a finite quaternion`() {
        // trace == -1 exactly: the branch where a naive derivation takes sqrt of a value that rounds
        // just below zero and returns NaN.
        val q = pose(listOf(-1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, 1.0)).quaternion

        assertTrue(q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite())
        assertEquals(1.0, q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w, absoluteTolerance = 1e-9)
    }

    @Test
    fun `a log line carrying an unusable rotation is unreadable, not an escaping exception`() {
        val valid = encodeSkyFrameLine(fixtures.frameRecord())
        val corrupted =
            valid.replace(
                Regex(""""rotationMatrix":\[[^\]]*\]"""),
                """"rotationMatrix":[0,0,0,0,0,0,0,0,0]""",
            )

        val line = parseSkySessionLogLine(corrupted, 3)

        assertEquals(3, assertIs<SkySessionLogLine.Unreadable>(line).lineNumber)
    }

    // -----------------------------------------------------------------------------------------
    // Finite numerics
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a non-finite predicted-star pixel coordinate is refused`() {
        listOf<(Double) -> SkyPredictedStar>(
            { v -> fixtures.predictedStar(imageXPx = v) },
            { v -> fixtures.predictedStar(imageYPx = v) },
            { v -> fixtures.predictedStar(displayXPx = v) },
            { v -> fixtures.predictedStar(displayYPx = v) },
        ).forEach { build ->
            assertFailsWith<IllegalArgumentException> { build(Double.NaN) }
            assertFailsWith<IllegalArgumentException> { build(Double.POSITIVE_INFINITY) }
        }
    }

    @Test
    fun `an absent predicted-star coordinate is still allowed`() {
        val star =
            fixtures.predictedStar(
                classification = PredictedStarClassification.BEHIND_CAMERA,
                imageXPx = null,
                imageYPx = null,
                displayXPx = null,
                displayYPx = null,
            )

        assertEquals(PredictedStarClassification.BEHIND_CAMERA, star.classification)
    }

    @Test
    fun `a log line carrying a NaN star coordinate is unreadable`() {
        val valid = encodeSkyFrameLine(fixtures.frameRecord(predictedStars = listOf(fixtures.predictedStar())))
        // JSON has no NaN literal, so a corrupted line spells it out; the decoder must refuse the line
        // rather than letting a String -> Double conversion smuggle a NaN through.
        val corrupted = valid.replace(Regex(""""xPx":[-0-9.eE]+"""), """"xPx":"NaN"""")

        assertIs<SkySessionLogLine.Unreadable>(parseSkySessionLogLine(corrupted, 1))
    }

    @Test
    fun `a non-finite calibration value is refused`() {
        assertFailsWith<IllegalArgumentException> { fixtures.calibration().copy(bufferFxPx = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { fixtures.calibration().copy(activeCyPx = Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `a non-finite intrinsics value is refused`() {
        val record = fixtures.intrinsics().toSkyIntrinsicsRecord()

        assertFailsWith<IllegalArgumentException> { record.copy(horizontalFovDeg = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { record.copy(focalLengthMm = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { record.copy(principalPointXPx = Double.NaN) }
    }

    @Test
    fun `a non-finite pinhole coefficient is refused`() {
        assertFailsWith<IllegalArgumentException> {
            SkyPinholeRecord(fxPx = Double.NaN, fyPx = 1.0, cxPx = 1.0, cyPx = 1.0)
        }
    }

    @Test
    fun `a header carrying a non-finite calibration value is unreadable`() {
        val valid = encodeSkySessionHeaderLine(fixtures.header(calibration = fixtures.calibration()))
        val corrupted = valid.replace(Regex(""""bufferFxPx":[-0-9.eE]+"""), """"bufferFxPx":"Infinity"""")

        assertIs<SkySessionLogLine.Unreadable>(parseSkySessionLogLine(corrupted, 1))
    }

    @Test
    fun `a truncated rotation matrix array is unreadable rather than an index exception`() {
        val valid = encodeSkyFrameLine(fixtures.frameRecord())
        val corrupted = valid.replace(Regex(""""rotationMatrix":\[[^\]]*\]"""), """"rotationMatrix":[1,0,0]""")

        assertIs<SkySessionLogLine.Unreadable>(parseSkySessionLogLine(corrupted, 1))
    }
}
