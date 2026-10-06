package dev.pointtosky.mobile.ar.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PTS-03 Group D: Camera2 `LENS_POSE_ROTATION` is `[x, y, z, w]` and rotates `S → Cphys`. The tests use a
 * rotation with no symmetry (60° about (1, 2, 3)/√14), whose expected matrix is built independently by
 * Rodrigues' formula — not by the code under test — so a wrong order or a wrong direction must fail.
 */
class Pts03LensPoseTest {
    private val axis =
        Pts03Vector3(1.0, 2.0, 3.0).let { v ->
            Pts03Vector3(
                v.x / v.norm(),
                v.y / v.norm(),
                v.z / v.norm(),
            )
        }
    private val angleRad = Math.toRadians(60.0)

    /** Camera2 order: `[x, y, z, w]`. */
    private val camera2Xyzw: List<Float> =
        listOf(
            (axis.x * sin(angleRad / 2)).toFloat(),
            (axis.y * sin(angleRad / 2)).toFloat(),
            (axis.z * sin(angleRad / 2)).toFloat(),
            cos(angleRad / 2).toFloat(),
        )

    /** Independent expected `R_Cphys_from_S` (Rodrigues), row-major. */
    private val expected: List<Double> =
        run {
            val (kx, ky, kz) = Triple(axis.x, axis.y, axis.z)
            val c = cos(angleRad)
            val s = sin(angleRad)
            val t = 1 - c
            listOf(
                c + kx * kx * t,
                kx * ky * t - kz * s,
                kx * kz * t + ky * s,
                ky * kx * t + kz * s,
                c + ky * ky * t,
                ky * kz * t - kx * s,
                kz * kx * t - ky * s,
                kz * ky * t + kx * s,
                c + kz * kz * t,
            )
        }

    private fun parsed(xyzw: List<Float> = camera2Xyzw): Camera2LensPoseRotation =
        assertIs<Camera2LensPoseRotationParse.Parsed>(parseCamera2LensPoseRotation(xyzw)).rotation

    private fun maxAbsDiff(
        a: List<Double>,
        b: List<Double>,
    ): Double = a.zip(b).maxOf { (x, y) -> abs(x - y) }

    @Test
    fun `xyzw input parses to the independently computed R_Cphys_from_S`() {
        val r = parsed()
        assertTrue(maxAbsDiff(r.rCphysFromS.toRowMajorList(), expected) < 1e-6, "matrix=${r.rCphysFromS}")
        assertEquals(cos(angleRad / 2), r.quaternionWxyz.w, 1e-6)
    }

    @Test
    fun `transformed basis vectors equal the expected matrix columns`() {
        val r = parsed()
        val ex = r.rCphysFromS.apply(Pts03Vector3(1.0, 0.0, 0.0))
        val ey = r.rCphysFromS.apply(Pts03Vector3(0.0, 1.0, 0.0))
        val ez = r.rCphysFromS.apply(Pts03Vector3(0.0, 0.0, 1.0))
        assertTrue(maxAbsDiff(ex.toList(), listOf(expected[0], expected[3], expected[6])) < 1e-6)
        assertTrue(maxAbsDiff(ey.toList(), listOf(expected[1], expected[4], expected[7])) < 1e-6)
        assertTrue(maxAbsDiff(ez.toList(), listOf(expected[2], expected[5], expected[8])) < 1e-6)
    }

    @Test
    fun `reading the same four numbers as wxyz gives a different matrix and fails the basis check`() {
        val wrong =
            Pts03QuaternionWxyz(
                w = camera2Xyzw[0].toDouble(),
                x = camera2Xyzw[1].toDouble(),
                y = camera2Xyzw[2].toDouble(),
                z = camera2Xyzw[3].toDouble(),
            ).toRotationMatrix()
        // The misread quaternion is still unit-norm, so only the expected-matrix check can catch it.
        assertTrue(maxAbsDiff(wrong.toRowMajorList(), expected) > 0.1, "a [w,x,y,z] misreading must not pass")
        val wrongEx = wrong.apply(Pts03Vector3(1.0, 0.0, 0.0))
        assertTrue(maxAbsDiff(wrongEx.toList(), listOf(expected[0], expected[3], expected[6])) > 0.1)
    }

    @Test
    fun `the named reorder adapter is the only bridge to scalar-first`() {
        val q = camera2XyzwToWxyz(listOf(0.1f, 0.2f, 0.3f, 0.9f))
        assertEquals(0.9f.toDouble(), q.w)
        assertEquals(0.1f.toDouble(), q.x)
        assertEquals(0.2f.toDouble(), q.y)
        assertEquals(0.3f.toDouble(), q.z)
    }

    @Test
    fun `S to Cphys and Cphys to S are distinct, inverse, and not interchangeable`() {
        val r = parsed()
        val vS = Pts03Vector3(0.3, -0.5, 0.8)
        val vC = r.rCphysFromS.apply(vS)
        val back = r.rSFromCphys.apply(vC)
        // float32 quaternion coefficients: orthogonality holds to ~1e-7.
        assertTrue(maxAbsDiff(back.toList(), vS.toList()) < 1e-6, "rSFromCphys must invert rCphysFromS")

        // Using the inverse where the forward map is meant gives a different answer for this rotation.
        val swapped = r.rSFromCphys.apply(vS)
        assertTrue(maxAbsDiff(swapped.toList(), vC.toList()) > 0.1, "a direction swap must be detectable")
        val expectedVc =
            Pts03Vector3(
                expected[0] * vS.x + expected[1] * vS.y + expected[2] * vS.z,
                expected[3] * vS.x + expected[4] * vS.y + expected[5] * vS.z,
                expected[6] * vS.x + expected[7] * vS.y + expected[8] * vS.z,
            )
        assertTrue(maxAbsDiff(vC.toList(), expectedVc.toList()) < 1e-6)
    }

    @Test
    fun `a camera rotated 180 degrees about S x looks along S minus z, the nominal rear optical axis`() {
        val r = parsed(listOf(1f, 0f, 0f, 0f))
        val z = r.cameraZAxisInS
        assertTrue(maxAbsDiff(z.toList(), listOf(0.0, 0.0, -1.0)) < 1e-9)
        val evidence = classifyPts03Extrinsics(characteristics(rotation = floatArrayOf(1f, 0f, 0f, 0f), reference = 1))
        assertEquals(0.0, assertNotNull(evidence.angleCameraZToNominalRearOpticalAxisDeg), 1e-6)
    }

    @Test
    fun `malformed rotations are rejected, never normalised`() {
        assertEquals(
            Pts03LensPoseRotationDefect.ABSENT,
            (parseCamera2LensPoseRotation(null) as Camera2LensPoseRotationParse.Invalid).defect,
        )
        assertEquals(
            Pts03LensPoseRotationDefect.WRONG_COEFFICIENT_COUNT,
            (parseCamera2LensPoseRotation(listOf(0f, 0f, 1f)) as Camera2LensPoseRotationParse.Invalid).defect,
        )
        assertEquals(
            Pts03LensPoseRotationDefect.NON_FINITE_COEFFICIENT,
            (
                parseCamera2LensPoseRotation(
                    listOf(0f, Float.NaN, 0f, 1f),
                ) as Camera2LensPoseRotationParse.Invalid
            ).defect,
        )
        assertEquals(
            Pts03LensPoseRotationDefect.NOT_UNIT_NORM,
            (parseCamera2LensPoseRotation(listOf(0f, 0f, 0f, 2f)) as Camera2LensPoseRotationParse.Invalid).defect,
        )
        assertEquals(
            Pts03LensPoseRotationDefect.NOT_UNIT_NORM,
            (parseCamera2LensPoseRotation(listOf(0f, 0f, 0f, 0f)) as Camera2LensPoseRotationParse.Invalid).defect,
        )
        // Within float tolerance is fine.
        parsed(listOf(0f, 0f, (sqrt(0.5)).toFloat(), (sqrt(0.5)).toFloat()))
    }

    // -----------------------------------------------------------------------------------------
    // Provenance
    // -----------------------------------------------------------------------------------------

    private fun characteristics(
        rotation: FloatArray?,
        reference: Int?,
    ): Pts03CameraStaticCharacteristics =
        pts03StaticCharacteristicsOf(
            cameraId = "3",
            role = Pts03CameraRole.DECLARED_PHYSICAL_CHILD,
            reader =
                Pts03Fixtures.characteristicsReader(
                    Pts03Fixtures.physicalCharacteristicsValues(rotation, reference),
                ),
            sdkInt = 35,
        )

    @Test
    fun `absent pose is unusable and PointToSky keeps the nominal fallback`() {
        val e = classifyPts03Extrinsics(characteristics(rotation = null, reference = 0))
        assertEquals(Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE, e.platformPoseStatus)
        assertEquals(Pts03ExtrinsicProvenance.NOMINAL_FALLBACK, e.extrinsicInUseByPointToSky)
        assertNull(e.rotation)
    }

    @Test
    fun `UNDEFINED, AUTOMOTIVE or missing reference is not calibrated truth`() {
        val valid = camera2Xyzw.toFloatArray()
        assertEquals(
            Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE,
            classifyPts03Extrinsics(characteristics(valid, 2)).platformPoseStatus,
        )
        assertEquals(
            Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE,
            classifyPts03Extrinsics(characteristics(valid, 3)).platformPoseStatus,
        )
        assertEquals(
            Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE,
            classifyPts03Extrinsics(characteristics(valid, null)).platformPoseStatus,
        )
    }

    @Test
    fun `non-unit or non-finite quaternion is unusable`() {
        assertEquals(
            Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE,
            classifyPts03Extrinsics(characteristics(floatArrayOf(0f, 0f, 0f, 1.5f), 0)).platformPoseStatus,
        )
        assertEquals(
            Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE,
            classifyPts03Extrinsics(
                characteristics(floatArrayOf(Float.POSITIVE_INFINITY, 0f, 0f, 1f), 0),
            ).platformPoseStatus,
        )
    }

    @Test
    fun `a valid pose with a useful reference stays PRESENT_BUT_UNVERIFIED without an independent check`() {
        for (reference in listOf(0, 1)) {
            val e = classifyPts03Extrinsics(characteristics(camera2Xyzw.toFloatArray(), reference))
            assertEquals(Pts03ExtrinsicProvenance.PLATFORM_POSE_PRESENT_BUT_UNVERIFIED, e.platformPoseStatus)
            assertEquals(Pts03ExtrinsicProvenance.NOMINAL_FALLBACK, e.extrinsicInUseByPointToSky)
            assertNotNull(e.rotation)
        }
    }

    @Test
    fun `only an independent device-frame agreement upgrades to CALIBRATED, and a disagreement makes it unusable`() {
        val c = characteristics(camera2Xyzw.toFloatArray(), 1)
        val agree =
            Pts03IndependentDeviceFrameAgreement(
                "star solution vs sensor attitude",
                angularDisagreementDeg = 0.2,
                toleranceDeg = 0.5,
            )
        val disagree = agree.copy(angularDisagreementDeg = 3.0)
        assertEquals(
            Pts03ExtrinsicProvenance.CALIBRATED_PLATFORM_POSE,
            classifyPts03Extrinsics(c, agree).platformPoseStatus,
        )
        assertEquals(
            Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE,
            classifyPts03Extrinsics(c, disagree).platformPoseStatus,
        )
    }
}
