package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins [trueEnuToMagneticEnu] against the exact algebra of the existing production correction it must
 * be the inverse-consistent counterpart to: `RotationFrame.correctedForTrueNorth`
 * (`mobile/.../ar/RotationFrame.kt`). `:core:astro-core` cannot depend on `:mobile` (Android), so
 * [referenceCorrectedForTrueNorthMatrix] below is a literal, line-for-line reimplementation of that
 * function's pure row-major matrix math, kept purely for this equivalence test — see
 * `RotationFrameTrueNorthEquivalenceTest` (`:mobile`) for the companion test that calls the *actual*
 * production `correctedForTrueNorth` function directly, not a reimplementation.
 */
class TrueNorthToMagneticNorthTransformTest {
    private val eps = 1e-9

    private fun assertVectorEquals(
        expected: LocalSkyDirection,
        actual: LocalSkyDirection,
        message: String,
        tolerance: Double = eps,
    ) {
        assertTrue(abs(expected.x - actual.x) < tolerance, "$message: x expected ${expected.x} but was ${actual.x}")
        assertTrue(abs(expected.y - actual.y) < tolerance, "$message: y expected ${expected.y} but was ${actual.y}")
        assertTrue(abs(expected.z - actual.z) < tolerance, "$message: z expected ${expected.z} but was ${actual.z}")
    }

    // --- A. Zero declination: true ENU direction unchanged -------------------------------------------

    @Test
    fun `zero declination leaves true North unchanged`() {
        val north = LocalSkyDirection(0.0, 1.0, 0.0)
        val result = trueEnuToMagneticEnu(north, magneticDeclinationRad = 0.0)
        assertVectorEquals(north, result, "north at d=0")
    }

    @Test
    fun `zero declination leaves true East unchanged`() {
        val east = LocalSkyDirection(1.0, 0.0, 0.0)
        val result = trueEnuToMagneticEnu(east, magneticDeclinationRad = 0.0)
        assertVectorEquals(east, result, "east at d=0")
    }

    @Test
    fun `zero declination leaves an arbitrary normalized vector unchanged, bit-for-bit`() {
        // cos(0.0)=1.0 and sin(0.0)=0.0 exactly in IEEE 754 double, so this is an exact identity, not
        // merely within tolerance - existing CAM-2a callers relying on the magneticDeclinationRad=0.0
        // default must see their outputs completely unaffected by this fix (task requirement G).
        val arbitrary = LocalSkyDirection(0.6, 0.8, 0.0) // unit length: 0.6^2+0.8^2=1
        val result = trueEnuToMagneticEnu(arbitrary, magneticDeclinationRad = 0.0)
        assertTrue(arbitrary.x == result.x && arbitrary.y == result.y && arbitrary.z == result.z, "expected bit-for-bit identity, was $result")
    }

    // --- B. Positive declination cardinal behavior (literal expected vectors, not computed) ----------
    //
    // Formula: x' = cos(d)*x - sin(d)*y, y' = sin(d)*x + cos(d)*y, z'=z.
    // For d = +30 deg: sin(30 deg) = 0.5, cos(30 deg) = sqrt(3)/2 ~ 0.8660254.
    // True North (0,1,0) -> magnetic ENU (-sin d, cos d, 0) = (-0.5, 0.8660254, 0):
    //   azimuth (atan2(x,y)) = atan2(-0.5, 0.8660254) = -30 deg, i.e. true north sits 30 deg WEST of
    //   magnetic-frame zero - consistent with "magnetic north is 30 deg EAST of true north" (d>0).
    // True East (1,0,0) -> magnetic ENU (cos d, sin d, 0) = (0.8660254, 0.5, 0): azimuth = 60 deg
    //   (90 deg - d), matching az_magnetic = az_true - d.

    @Test
    fun `+30 degree declination maps true North to the literal expected magnetic-ENU vector`() {
        val north = LocalSkyDirection(0.0, 1.0, 0.0)
        val d = Math.toRadians(30.0)
        val expected = LocalSkyDirection(x = -0.5, y = 0.8660254037844387, z = 0.0)
        val result = trueEnuToMagneticEnu(north, magneticDeclinationRad = d)
        assertVectorEquals(expected, result, "north at d=+30deg", tolerance = 1e-9)
    }

    @Test
    fun `+30 degree declination maps true East to the literal expected magnetic-ENU vector`() {
        val east = LocalSkyDirection(1.0, 0.0, 0.0)
        val d = Math.toRadians(30.0)
        val expected = LocalSkyDirection(x = 0.8660254037844387, y = 0.5, z = 0.0)
        val result = trueEnuToMagneticEnu(east, magneticDeclinationRad = d)
        assertVectorEquals(expected, result, "east at d=+30deg", tolerance = 1e-9)
    }

    // --- C. Negative declination - catches a sign inversion -------------------------------------------
    //
    // For d = -30 deg: sin(-30 deg) = -0.5, cos(-30 deg) = 0.8660254.
    // True North (0,1,0) -> (-sin d, cos d, 0) = (0.5, 0.8660254, 0) - the MIRROR of the +30 deg case
    // (x flips sign). A reversed-sign implementation of trueEnuToMagneticEnu would instead produce
    // (-0.5, 0.8660254, 0) here - the +30 deg answer - so this test fails if the sign is backwards.

    @Test
    fun `-30 degree declination maps true North to the literal mirrored expected vector`() {
        val north = LocalSkyDirection(0.0, 1.0, 0.0)
        val d = Math.toRadians(-30.0)
        val expected = LocalSkyDirection(x = 0.5, y = 0.8660254037844387, z = 0.0)
        val result = trueEnuToMagneticEnu(north, magneticDeclinationRad = d)
        assertVectorEquals(expected, result, "north at d=-30deg", tolerance = 1e-9)
    }

    @Test
    fun `-30 degree declination maps true East to the literal mirrored expected vector`() {
        val east = LocalSkyDirection(1.0, 0.0, 0.0)
        val d = Math.toRadians(-30.0)
        val expected = LocalSkyDirection(x = 0.8660254037844387, y = -0.5, z = 0.0)
        val result = trueEnuToMagneticEnu(east, magneticDeclinationRad = d)
        assertVectorEquals(expected, result, "east at d=-30deg", tolerance = 1e-9)
    }

    // --- D. Unit-length preservation -------------------------------------------------------------------

    @Test
    fun `unit length is preserved across several directions and declinations`() {
        val directions =
            listOf(
                LocalSkyDirection(0.0, 1.0, 0.0),
                LocalSkyDirection(1.0, 0.0, 0.0),
                LocalSkyDirection(0.6, 0.8, 0.0),
                LocalSkyDirection(0.0, 0.0, 1.0),
                localSkyDirectionFromHorizontal(azimuthRad = Math.toRadians(37.0), altitudeRad = Math.toRadians(-15.0)),
            )
        val declinationsDeg = listOf(-45.0, -20.0, -5.0, 0.0, 5.0, 20.0, 45.0, 179.9)

        for (direction in directions) {
            for (declinationDeg in declinationsDeg) {
                val result = trueEnuToMagneticEnu(direction, Math.toRadians(declinationDeg))
                val length = sqrt(result.x * result.x + result.y * result.y + result.z * result.z)
                assertTrue(
                    abs(length - 1.0) < 1e-9,
                    "direction=$direction declinationDeg=$declinationDeg expected unit length, was $length ($result)",
                )
            }
        }
    }

    // --- Validation -------------------------------------------------------------------------------------

    @Test
    fun `non-finite declination is rejected`() {
        val north = LocalSkyDirection(0.0, 1.0, 0.0)
        assertFailsWith<IllegalArgumentException> { trueEnuToMagneticEnu(north, Double.NaN) }
        assertFailsWith<IllegalArgumentException> { trueEnuToMagneticEnu(north, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { trueEnuToMagneticEnu(north, Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `output is always finite for finite, in-range inputs`() {
        for (declinationDeg in listOf(-179.0, -90.0, -1.0, 0.0, 1.0, 90.0, 179.0)) {
            val result = trueEnuToMagneticEnu(LocalSkyDirection(0.3, 0.4, sqrt(1.0 - 0.09 - 0.16)), Math.toRadians(declinationDeg))
            assertTrue(result.x.isFinite() && result.y.isFinite() && result.z.isFinite(), "non-finite result for declinationDeg=$declinationDeg: $result")
        }
    }

    // --- E. Matrix/vector equivalence with the existing correctedForTrueNorth algebra -----------------
    //
    // Required relationship: projecting a true-north sky vector through the raw magnetic-north sensor
    // matrix, after converting it via trueEnuToMagneticEnu, must produce the same device vector as
    // projecting the unmodified true-north vector through the existing true-north-corrected rotation
    // matrix:
    //   Rᵀ · trueEnuToMagneticEnu(v_true, d)  ≈  correctedForTrueNorth(R, d)ᵀ · v_true
    //
    // referenceCorrectedForTrueNorthMatrix below is a literal, line-for-line port of
    // RotationFrame.correctedForTrueNorth's own row-major matrix formula (mobile/.../ar/RotationFrame.kt),
    // kept in Double here (the production function works in Float) purely so this equivalence can be
    // checked to tight tolerance. It is not a re-derivation: every line mirrors the production formula
    // verbatim. See RotationFrameTrueNorthEquivalenceTest (:mobile) for the same equivalence proven
    // against the actual production function, not this reimplementation.

    /** Line-for-line port of `RotationFrame.correctedForTrueNorth`'s row-major matrix math, in Double. */
    private fun referenceCorrectedForTrueNorthMatrix(rotationMatrix: FloatArray, declinationRad: Double): FloatArray {
        val c = cos(declinationRad)
        val s = sin(declinationRad)
        val r = DoubleArray(9) { rotationMatrix[it].toDouble() }
        val cm =
            doubleArrayOf(
                c * r[0] + s * r[3], c * r[1] + s * r[4], c * r[2] + s * r[5],
                -s * r[0] + c * r[3], -s * r[1] + c * r[4], -s * r[2] + c * r[5],
                r[6], r[7], r[8],
            )
        return FloatArray(9) { cm[it].toFloat() }
    }

    @Test
    fun `E - equivalence with the existing true-north matrix correction, across several matrices, directions, and declinations`() {
        val matrices =
            listOf(
                IDENTITY_ROTATION_MATRIX,
                NORTH_UPRIGHT_ROTATION_MATRIX,
                NORTHEAST_UPRIGHT_ROTATION_MATRIX,
            )
        val trueDirections =
            listOf(
                LocalSkyDirection(0.0, 1.0, 0.0),
                LocalSkyDirection(1.0, 0.0, 0.0),
                LocalSkyDirection(-1.0, 0.0, 0.0),
                LocalSkyDirection(0.0, -1.0, 0.0),
                localSkyDirectionFromHorizontal(azimuthRad = Math.toRadians(52.0), altitudeRad = Math.toRadians(28.0)),
                localSkyDirectionFromHorizontal(azimuthRad = Math.toRadians(200.0), altitudeRad = Math.toRadians(-10.0)),
            )
        val declinationsDeg = listOf(-20.0, -5.0, 0.0, 5.0, 20.0)

        for (rawMatrix in matrices) {
            for (vTrue in trueDirections) {
                for (declinationDeg in declinationsDeg) {
                    val d = Math.toRadians(declinationDeg)

                    val vMagnetic = trueEnuToMagneticEnu(vTrue, d)
                    val actual = worldToDeviceVector(rotationMatrix = rawMatrix, world = vMagnetic)

                    val correctedMatrix = referenceCorrectedForTrueNorthMatrix(rawMatrix, d)
                    val expected = worldToDeviceVector(rotationMatrix = correctedMatrix, world = vTrue)

                    val context = "matrix=${rawMatrix.toList()} vTrue=$vTrue declinationDeg=$declinationDeg"
                    assertTrue(abs(actual.x - expected.x) < 1e-6, "$context: x expected ${expected.x} but was ${actual.x}")
                    assertTrue(abs(actual.y - expected.y) < 1e-6, "$context: y expected ${expected.y} but was ${actual.y}")
                    assertTrue(abs(actual.z - expected.z) < 1e-6, "$context: z expected ${expected.z} but was ${actual.z}")
                }
            }
        }
    }

    @Test
    fun `E is sign-sensitive - reversing the CAM-2a declination sign breaks the equivalence`() {
        // Regression guard: proves the equivalence test above actually exercises the sign, not just
        // magnitude. Using -d instead of the correct +d in trueEnuToMagneticEnu must diverge materially
        // from correctedForTrueNorth(R, +d) for a non-trivial declination and an off-axis direction.
        val d = Math.toRadians(20.0)
        val vTrue = localSkyDirectionFromHorizontal(azimuthRad = Math.toRadians(52.0), altitudeRad = Math.toRadians(28.0))

        val wrongSignMagnetic = trueEnuToMagneticEnu(vTrue, -d) // deliberately wrong sign
        val actual = worldToDeviceVector(rotationMatrix = NORTHEAST_UPRIGHT_ROTATION_MATRIX, world = wrongSignMagnetic)

        val correctedMatrix = referenceCorrectedForTrueNorthMatrix(NORTHEAST_UPRIGHT_ROTATION_MATRIX, d)
        val expected = worldToDeviceVector(rotationMatrix = correctedMatrix, world = vTrue)

        val dx = abs(actual.x - expected.x)
        val dy = abs(actual.y - expected.y)
        val dz = abs(actual.z - expected.z)
        assertTrue(dx > 0.01 || dy > 0.01 || dz > 0.01, "expected the wrong-sign conversion to diverge materially, was actual=$actual expected=$expected")
    }
}
