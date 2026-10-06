package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PTS-03: static characteristics for the logical camera and every declared child, discovered — never hard-coded. */
class Pts03StaticCharacteristicsTest {
    @Test
    fun `children are discovered from the logical camera and listed in sorted order`() {
        val set = Pts03Fixtures.characteristicsSet()
        assertEquals(listOf("2", "3", "4"), set.declaredPhysicalCameraIds)
        assertEquals(listOf("2", "3", "4"), set.physicalChildren.map { it.cameraId })
        assertTrue(set.logical.snapshot!!.isLogicalMultiCamera)
        assertEquals(Pts03CameraRole.OPENED_LOGICAL, set.logical.role)
        assertTrue(set.physicalChildren.all { it.role == Pts03CameraRole.DECLARED_PHYSICAL_CHILD })
    }

    @Test
    fun `different child IDs work just the same`() {
        val set =
            buildPts03CameraCharacteristicsSet(
                logicalCameraId = "7",
                provider = { id ->
                    Pts03Fixtures.characteristicsReader(
                        if (id ==
                            "7"
                        ) {
                            Pts03Fixtures.logicalCharacteristicsValues(setOf("11", "10"))
                        } else {
                            Pts03Fixtures.physicalCharacteristicsValues()
                        },
                    )
                },
                sdkInt = 35,
            )
        assertEquals(listOf("10", "11"), set.physicalChildren.map { it.cameraId })
    }

    @Test
    fun `an unreadable child is still listed with a read failure, never silently dropped`() {
        val set =
            buildPts03CameraCharacteristicsSet(
                logicalCameraId = "0",
                provider = { id ->
                    when (id) {
                        "0" -> Pts03Fixtures.characteristicsReader(Pts03Fixtures.logicalCharacteristicsValues())
                        "4" -> null
                        else -> Pts03Fixtures.characteristicsReader(Pts03Fixtures.physicalCharacteristicsValues())
                    }
                },
                sdkInt = 35,
            )
        val four = assertNotNull(set.forCameraId("4"))
        assertNotNull(four.readFailure)
        assertNull(four.snapshot)
    }

    @Test
    fun `every PTS-03 field is read, with arrays copied`() {
        val values = Pts03Fixtures.physicalCharacteristicsValues()
        val c =
            pts03StaticCharacteristicsOf(
                "3",
                Pts03CameraRole.DECLARED_PHYSICAL_CHILD,
                Pts03Fixtures.characteristicsReader(values),
                35,
            )
        (values[Pts03CharacteristicsField.LENS_INTRINSIC_CALIBRATION] as FloatArray)[0] = -1f

        val s = assertNotNull(c.snapshot)
        assertEquals(5000f, s.lensIntrinsicCalibration!![0], "copied, not aliased")
        assertEquals(8, s.activeArrayLeftPx)
        assertEquals(0, s.preCorrectionActiveArrayLeftPx)
        assertEquals(4048, s.pixelArrayWidthPx)
        assertEquals(listOf("OFF", "FAST", "HIGH_QUALITY"), c.distortionCorrectionAvailableModes!!.map { it.name })
        assertEquals(listOf(0f, 0f, 0f, 1f), c.lensPoseRotationXyzw)
        assertEquals(Pts03EnumValue(0, "PRIMARY_CAMERA"), c.lensPoseReference)
        assertEquals(90, c.sensorOrientationDegrees)
        assertEquals(Pts03EnumValue(1, "BACK"), c.lensFacing)
        assertEquals(Pts03EnumValue(3, "LEVEL_3"), c.supportedHardwareLevel)
        assertEquals(Pts03EnumValue(1, "REALTIME"), c.timestampSource)
        assertEquals(listOf("BACKWARD_COMPATIBLE", "MANUAL_SENSOR"), c.availableCapabilities!!.map { it.name })
    }

    @Test
    fun `API-gated static keys are not read below their level`() {
        val c =
            pts03StaticCharacteristicsOf(
                "3",
                Pts03CameraRole.DECLARED_PHYSICAL_CHILD,
                Pts03Fixtures.characteristicsReader(Pts03Fixtures.logicalCharacteristicsValues()),
                sdkInt = 27,
            )
        assertNull(c.distortionCorrectionAvailableModes)
        assertNull(c.lensPoseReference)
        assertNull(c.snapshot!!.lensDistortion)
        assertNull(c.snapshot!!.physicalCameraIds)
        assertNotNull(c.lensPoseRotationXyzw, "LENS_POSE_ROTATION is API 23")
    }
}
