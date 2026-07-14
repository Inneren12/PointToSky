package dev.pointtosky.mobile.ar

import dev.pointtosky.core.astro.projection.camera.prediction.DeviceVector
import dev.pointtosky.core.astro.projection.camera.prediction.LocalSkyDirection
import dev.pointtosky.core.astro.projection.camera.prediction.localSkyDirectionFromHorizontal
import dev.pointtosky.core.astro.projection.camera.prediction.trueEnuToMagneticEnu
import dev.pointtosky.core.astro.projection.camera.prediction.worldToDeviceVector
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves CAM-2a's [trueEnuToMagneticEnu] is the algebraically-equivalent counterpart to the *actual
 * production* [RotationFrame.correctedForTrueNorth] — not a reimplementation of it. This file lives in
 * `:mobile` specifically so it can call the real `correctedForTrueNorth` directly (`:core:astro-core`
 * is a pure JVM module and cannot depend on `:mobile`/Android; see
 * `TrueNorthToMagneticNorthTransformTest` in `:core:astro-core` for the companion test that pins the
 * same equivalence against a literal, documented reimplementation of this same algebra).
 *
 * Required relationship (see [trueEnuToMagneticEnu]'s KDoc for the full derivation):
 * ```text
 * Rᵀ · trueEnuToMagneticEnu(v_true, d)  ≈  correctedForTrueNorth(R, d).rotationMatrixᵀ · v_true
 * ```
 * where `R` is the raw, magnetic-north-relative device→world matrix CAM-2a's
 * `geometry.pairedRotation.rotationMatrix` is expressed in (the same matrix `RotationFrame`'s sensor
 * listener produces, before `correctedForTrueNorth` is ever applied — see `ArScreen.kt`'s
 * `rememberRotationFrame(onRotationSample = ...)` call site, which feeds the *raw* matrix into CAM-1d's
 * `TimedRotationSample` history, and `calculateOverlay`'s separate, later `correctedForTrueNorth` call
 * for the legacy renderer's own pixel math).
 */
class RotationFrameTrueNorthEquivalenceTest {
    private val eps = 1e-6

    /** A handful of literal, row-major device→world matrices — the same convention CAM-2a's own test fixtures use. */
    private val rawMatrices =
        listOf(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), // identity
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f), // upright, facing north (forwardWorld = (0,1,0))
            floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f), // upright, facing east (forwardWorld = (1,0,0))
        )

    private fun rotationFrameFor(rawMatrix: FloatArray): RotationFrame =
        RotationFrame(
            rotationMatrix = rawMatrix.copyOf(),
            forwardWorld = floatArrayOf(-rawMatrix[2], -rawMatrix[5], -rawMatrix[8]),
            timestampNanos = 0L,
        )

    private fun assertDeviceVectorsClose(expected: DeviceVector, actual: DeviceVector, message: String) {
        assertTrue(abs(expected.x - actual.x) < eps, "$message: x expected ${expected.x} but was ${actual.x}")
        assertTrue(abs(expected.y - actual.y) < eps, "$message: y expected ${expected.y} but was ${actual.y}")
        assertTrue(abs(expected.z - actual.z) < eps, "$message: z expected ${expected.z} but was ${actual.z}")
    }

    @Test
    fun `trueEnuToMagneticEnu matches the real correctedForTrueNorth across matrices, directions, and declinations`() {
        val trueDirections =
            listOf(
                LocalSkyDirection(0.0, 1.0, 0.0), // true North
                LocalSkyDirection(1.0, 0.0, 0.0), // true East
                LocalSkyDirection(-1.0, 0.0, 0.0), // true West
                localSkyDirectionFromHorizontal(azimuthRad = Math.toRadians(52.0), altitudeRad = Math.toRadians(28.0)),
                localSkyDirectionFromHorizontal(azimuthRad = Math.toRadians(210.0), altitudeRad = Math.toRadians(-12.0)),
            )
        val declinationsDeg = listOf(-20.0, -5.0, 0.0, 5.0, 20.0)

        for (rawMatrix in rawMatrices) {
            val frame = rotationFrameFor(rawMatrix)
            for (vTrue in trueDirections) {
                for (declinationDeg in declinationsDeg) {
                    val declinationRad = Math.toRadians(declinationDeg)

                    // Actual: raw (uncorrected) matrix + CAM-2a's true-to-magnetic conversion.
                    val vMagnetic = trueEnuToMagneticEnu(vTrue, declinationRad)
                    val actual = worldToDeviceVector(rotationMatrix = rawMatrix, world = vMagnetic)

                    // Expected: the REAL production correctedForTrueNorth, applied to the true vector directly.
                    val correctedFrame = frame.correctedForTrueNorth(declinationDeg)
                    val expected = worldToDeviceVector(rotationMatrix = correctedFrame.rotationMatrix, world = vTrue)

                    assertDeviceVectorsClose(expected, actual, "rawMatrix=${rawMatrix.toList()} vTrue=$vTrue declinationDeg=$declinationDeg")
                }
            }
        }
    }

    @Test
    fun `reversing the CAM-2a declination sign breaks equivalence with the real correctedForTrueNorth`() {
        val declinationDeg = 20.0
        val vTrue = localSkyDirectionFromHorizontal(azimuthRad = Math.toRadians(52.0), altitudeRad = Math.toRadians(28.0))
        val rawMatrix = rawMatrices[1]
        val frame = rotationFrameFor(rawMatrix)

        val wrongSignMagnetic = trueEnuToMagneticEnu(vTrue, Math.toRadians(-declinationDeg)) // deliberately wrong sign
        val actual = worldToDeviceVector(rotationMatrix = rawMatrix, world = wrongSignMagnetic)

        val correctedFrame = frame.correctedForTrueNorth(declinationDeg)
        val expected = worldToDeviceVector(rotationMatrix = correctedFrame.rotationMatrix, world = vTrue)

        val dx = abs(actual.x - expected.x)
        val dy = abs(actual.y - expected.y)
        val dz = abs(actual.z - expected.z)
        assertTrue(dx > 0.01 || dy > 0.01 || dz > 0.01, "expected the wrong-sign conversion to diverge from the real correctedForTrueNorth; actual=$actual expected=$expected")
    }

    @Test
    fun `zero declination is a no-op on both sides of the equivalence`() {
        val vTrue = LocalSkyDirection(0.0, 1.0, 0.0)
        val rawMatrix = rawMatrices[2]
        val frame = rotationFrameFor(rawMatrix)

        val actual = worldToDeviceVector(rotationMatrix = rawMatrix, world = trueEnuToMagneticEnu(vTrue, 0.0))
        val expected = worldToDeviceVector(rotationMatrix = frame.correctedForTrueNorth(0.0).rotationMatrix, world = vTrue)

        assertDeviceVectorsClose(expected, actual, "zero declination")
    }
}
