package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PTS-03: per-`CaptureResult` extraction. Every optional key must stay nullable, "not reported" must never
 * read as zero/default, API-gated keys must not even be requested below their level, and arrays/rects must be
 * copied so a later mutation of the HAL-owned value cannot rewrite recorded evidence.
 */
class Pts03CaptureResultTruthTest {
    @Test
    fun `every field present is extracted with raw values and constant names`() {
        val truth = pts03CaptureTruthOf(Pts03Fixtures.reader(Pts03Fixtures.fullResultValues()), sdkInt = 35)

        assertEquals(1_000L, truth.sensorTimestampNanos)
        assertEquals("3", truth.activePhysicalCameraId)
        assertEquals(Pts03KeyAvailability.REPORTED, truth.activePhysicalCameraIdAvailability)
        assertEquals(Pts03EnumValue(1, "FAST"), truth.distortionCorrectionMode)
        assertEquals(Pts03KeyAvailability.REPORTED, truth.distortionCorrectionModeAvailability)
        assertEquals(6.9f, truth.lensFocalLengthMm)
        assertEquals(listOf(5000f, 5001f, 2040f, 1530f, 0f), truth.lensIntrinsicCalibration)
        assertEquals(0.25f, truth.lensFocusDistanceDiopters)
        assertEquals(Pts03EnumValue(0, "STATIONARY"), truth.lensState)
        assertEquals(Pts03EnumValue(4, "CONTINUOUS_PICTURE"), truth.controlAfMode)
        assertEquals(Pts03EnumValue(2, "PASSIVE_FOCUSED"), truth.controlAfState)
        assertEquals(Pts03IntRect(0, 0, 4080, 3072), truth.scalerCropRegion)
        assertEquals(1.0f, truth.controlZoomRatio)
        assertEquals(Pts03KeyAvailability.REPORTED, truth.controlZoomRatioAvailability)
        assertEquals(Pts03EnumValue(1, "ON"), truth.lensOpticalStabilizationMode)
        assertEquals(Pts03EnumValue(0, "OFF"), truth.controlVideoStabilizationMode)
        assertEquals(Pts03EnumValue(1, "FAST"), truth.hotPixelMode)
        assertEquals(Pts03EnumValue(2, "HIGH_QUALITY"), truth.noiseReductionMode)
        assertEquals(Pts03EnumValue(1, "FAST"), truth.edgeMode)
    }

    @Test
    fun `every optional field missing stays null and is reported NOT_REPORTED, never zero or a default`() {
        val truth = pts03CaptureTruthOf(Pts03Fixtures.reader(emptyMap()), sdkInt = 35)

        assertNull(truth.sensorTimestampNanos)
        assertNull(truth.activePhysicalCameraId)
        assertEquals(Pts03KeyAvailability.NOT_REPORTED, truth.activePhysicalCameraIdAvailability)
        assertNull(truth.distortionCorrectionMode)
        assertEquals(Pts03KeyAvailability.NOT_REPORTED, truth.distortionCorrectionModeAvailability)
        assertEquals(Pts03EffectiveDistortionMode.NotReported, truth.effectiveDistortionMode(), "absent is not OFF")
        assertNull(truth.lensFocalLengthMm)
        assertNull(truth.lensIntrinsicCalibration)
        assertNull(truth.lensFocusDistanceDiopters)
        assertNull(truth.lensState)
        assertNull(truth.controlAfMode)
        assertNull(truth.controlAfState)
        assertNull(truth.scalerCropRegion)
        assertNull(truth.controlZoomRatio)
        assertEquals(Pts03KeyAvailability.NOT_REPORTED, truth.controlZoomRatioAvailability)
        assertNull(truth.lensOpticalStabilizationMode)
        assertNull(truth.controlVideoStabilizationMode)
        assertNull(truth.hotPixelMode)
        assertNull(truth.noiseReductionMode)
        assertNull(truth.edgeMode)
    }

    @Test
    fun `active physical ID is API gated at 29 and never requested below it`() {
        val requested = mutableListOf<Pts03CaptureResultField>()
        val values = Pts03Fixtures.fullResultValues()
        val reader =
            Pts03CaptureResultReader { field ->
                requested += field
                values[field]
            }

        val truth = pts03CaptureTruthOf(reader, sdkInt = 28)

        assertNull(truth.activePhysicalCameraId)
        assertEquals(Pts03KeyAvailability.API_UNSUPPORTED, truth.activePhysicalCameraIdAvailability)
        assertFalse(Pts03CaptureResultField.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID in requested)
        assertFalse(Pts03CaptureResultField.CONTROL_ZOOM_RATIO in requested, "zoom ratio is API 30")
        assertEquals(Pts03KeyAvailability.API_UNSUPPORTED, truth.controlZoomRatioAvailability)
        // API 28 still reads the distortion mode.
        assertEquals(Pts03EnumValue(1, "FAST"), truth.distortionCorrectionMode)

        val api27 = pts03CaptureTruthOf(reader, sdkInt = 27)
        assertEquals(Pts03KeyAvailability.API_UNSUPPORTED, api27.distortionCorrectionModeAvailability)
        assertEquals(Pts03EffectiveDistortionMode.ApiUnsupported, api27.effectiveDistortionMode())
    }

    @Test
    fun `API 29 reports the active physical ID, and a null value at 29 is NOT_REPORTED`() {
        assertEquals(
            "3",
            pts03CaptureTruthOf(Pts03Fixtures.reader(Pts03Fixtures.fullResultValues()), 29).activePhysicalCameraId,
        )
        val missing =
            pts03CaptureTruthOf(Pts03Fixtures.reader(Pts03Fixtures.fullResultValues(activePhysicalId = null)), 29)
        assertEquals(Pts03KeyAvailability.NOT_REPORTED, missing.activePhysicalCameraIdAvailability)
    }

    @Test
    fun `unknown enum values are preserved raw and named UNKNOWN, never coerced to a known mode`() {
        val values =
            Pts03Fixtures.fullResultValues(distortionMode = 7) +
                mapOf(Pts03CaptureResultField.CONTROL_AF_STATE to 42, Pts03CaptureResultField.EDGE_MODE to 9)
        val truth = pts03CaptureTruthOf(Pts03Fixtures.reader(values), 35)

        assertEquals(Pts03EnumValue(7, "UNKNOWN_7"), truth.distortionCorrectionMode)
        assertEquals(Pts03EffectiveDistortionMode.UnknownValue(7), truth.effectiveDistortionMode())
        assertEquals(Pts03MetadataCoordinateBasis.UNKNOWN, truth.effectiveDistortionMode().metadataCoordinateBasis())
        assertEquals(Pts03EnumValue(42, "UNKNOWN_42"), truth.controlAfState)
        assertEquals(Pts03EnumValue(9, "UNKNOWN_9"), truth.edgeMode)
    }

    @Test
    fun `arrays and rects are copied, so mutating the source cannot rewrite the record`() {
        val intrinsics = floatArrayOf(1f, 2f, 3f, 4f, 0f)
        val rect = Pts03IntRect(1, 2, 3, 4)
        val truth =
            pts03CaptureTruthOf(
                Pts03Fixtures.reader(
                    mapOf(
                        Pts03CaptureResultField.LENS_INTRINSIC_CALIBRATION to intrinsics,
                        Pts03CaptureResultField.SCALER_CROP_REGION to rect,
                    ),
                ),
                35,
            )
        intrinsics[0] = 999f

        assertEquals(listOf(1f, 2f, 3f, 4f, 0f), truth.lensIntrinsicCalibration)
        assertEquals(rect, truth.scalerCropRegion)
        assertFalse(truth.scalerCropRegion === rect, "the rect is copied, not aliased")
    }

    @Test
    fun `a value of the wrong type or a throwing reader costs one field, not the extraction`() {
        val reader =
            Pts03CaptureResultReader { field ->
                when (field) {
                    Pts03CaptureResultField.LENS_FOCAL_LENGTH -> "not a float"
                    Pts03CaptureResultField.LENS_STATE -> throw IllegalStateException("OEM key read failed")
                    Pts03CaptureResultField.SENSOR_TIMESTAMP -> 5L
                    else -> null
                }
            }
        val truth = pts03CaptureTruthOf(reader, 35)

        assertEquals(5L, truth.sensorTimestampNanos)
        assertNull(truth.lensFocalLengthMm)
        assertNull(truth.lensState)
    }

    @Test
    fun `one snapshot holds both halves of one result and refuses halves from different frames`() {
        val snapshot = Pts03Fixtures.captureResult(timestampNanos = 77L)
        assertEquals(77L, snapshot.sensorTimestampNanos)
        assertEquals(77L, snapshot.logicalTruth.sensorTimestampNanos)

        assertFailsWith<IllegalArgumentException> {
            SkyCaptureResultSnapshot(
                exposure = Pts03Fixtures.exposure(77L),
                logicalTruth = Pts03Fixtures.captureResult(timestampNanos = 78L).logicalTruth,
            )
        }
    }

    @Test
    fun `the SKY-1 exposure half is untouched by the PTS-03 extension`() {
        val snapshot = Pts03Fixtures.captureResult(timestampNanos = 1L)
        assertEquals(Pts03Fixtures.exposure(1L), snapshot.exposure)
        assertTrue(
            validateSkyManualExposure(snapshot.exposure, 1L) is SkyExposureValidation.Rejected,
            "AE ON is still rejected",
        )
    }
}
