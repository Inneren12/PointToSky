package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * PTS-03: the snapshot keeps the logical top-level result and per-physical results apart; physical entries
 * are used as frame truth only on an exact `SENSOR_TIMESTAMP` match; the API 35 active-physical-sensor crop
 * is recorded independently of crop/zoom.
 */
class Pts03PhysicalResultTest {
    @Test
    fun `logical and physical results are kept apart, physical IDs sorted and the map copied`() {
        val source = mutableListOf(Pts03Fixtures.physicalResult("4", 10L), Pts03Fixtures.physicalResult("2", 10L))
        val snapshot =
            skyCaptureResultSnapshot(Pts03Fixtures.exposure(10L), Pts03Fixtures.captureResult(10L).logicalTruth, source)
        source.clear()

        assertEquals(listOf("2", "4"), snapshot.physicalResultsByCameraId.keys.toList())
        assertEquals(6.9f, snapshot.logicalTruth.lensFocalLengthMm, "the top-level result is not replaced")
        assertEquals(
            2.2f,
            snapshot.physicalResultsByCameraId
                .getValue("2")
                .truth.lensFocalLengthMm,
        )
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (snapshot.physicalResultsByCameraId as MutableMap<String, Pts03PhysicalCaptureResult>).clear()
        }
    }

    @Test
    fun `an unsorted or mis-keyed physical map is refused`() {
        val logical = Pts03Fixtures.captureResult(10L).logicalTruth
        assertFailsWith<IllegalArgumentException> {
            SkyCaptureResultSnapshot(
                Pts03Fixtures.exposure(10L),
                logical,
                mapOf("2" to Pts03Fixtures.physicalResult("3", 10L)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SkyCaptureResultSnapshot(
                Pts03Fixtures.exposure(10L),
                logical,
                linkedMapOf(
                    "4" to Pts03Fixtures.physicalResult("4", 10L),
                    "2" to Pts03Fixtures.physicalResult("2", 10L),
                ),
            )
        }
    }

    @Test
    fun `logical timestamp equals the frame timestamp, and a physical entry with the same timestamp is usable`() {
        val snapshot = Pts03Fixtures.captureResult(timestampNanos = 50L, physicalIds = listOf("3"))
        assertEquals(50L, snapshot.sensorTimestampNanos)
        assertEquals(50L, snapshot.logicalTruth.sensorTimestampNanos)
        val lookup = snapshot.physicalResultFor("3", 50L)
        assertEquals(Pts03PhysicalResultStatus.PRESENT_TIMESTAMP_MATCHED, lookup.status)
        assertSame(snapshot.physicalResultsByCameraId.getValue("3"), lookup.usable)
        assertEquals(50L, lookup.entrySensorTimestampNanos)
    }

    @Test
    fun `a physical entry with a different timestamp is recorded as a mismatch and never used`() {
        val lookup =
            Pts03Fixtures
                .captureResult(
                    timestampNanos = 50L,
                    physicalIds = listOf("3"),
                    physicalTimestampNanos = 49L,
                ).physicalResultFor("3", 50L)
        assertEquals(Pts03PhysicalResultStatus.TIMESTAMP_MISMATCH, lookup.status)
        assertNull(lookup.usable)
        assertEquals(49L, lookup.entrySensorTimestampNanos)
    }

    @Test
    fun `a physical entry without a timestamp is recorded as missing and never used`() {
        val lookup =
            Pts03Fixtures
                .captureResult(
                    timestampNanos = 50L,
                    physicalIds = listOf("3"),
                    physicalTimestampNanos = null,
                ).physicalResultFor("3", 50L)
        assertEquals(Pts03PhysicalResultStatus.TIMESTAMP_MISSING, lookup.status)
        assertNull(lookup.usable)
    }

    @Test
    fun `an absent physical entry is NOT_REPORTED`() {
        val lookup =
            Pts03Fixtures
                .captureResult(
                    timestampNanos = 50L,
                    physicalIds = listOf("2"),
                ).physicalResultFor("3", 50L)
        assertEquals(Pts03PhysicalResultStatus.NOT_REPORTED, lookup.status)
        assertNull(lookup.usable)
    }

    // -----------------------------------------------------------------------------------------
    // API 35 LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION
    // -----------------------------------------------------------------------------------------

    private val cropField = Pts03CaptureResultField.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION

    @Test
    fun `active physical sensor crop is read on API 35, independently of SCALER_CROP_REGION and zoom`() {
        val crop = Pts03IntRect(100, 80, 3980, 2992)
        val truth =
            pts03CaptureTruthOf(Pts03Fixtures.reader(Pts03Fixtures.fullResultValues() + (cropField to crop)), 35)
        assertEquals(crop, truth.activePhysicalSensorCropRegion)
        assertEquals(Pts03KeyAvailability.REPORTED, truth.activePhysicalSensorCropRegionAvailability)
        assertEquals(Pts03IntRect(0, 0, 4080, 3072), truth.scalerCropRegion)
        assertEquals(1.0f, truth.controlZoomRatio)
    }

    @Test
    fun `active physical sensor crop is API gated below 35 and NOT_REPORTED when absent`() {
        val requested = mutableListOf<Pts03CaptureResultField>()
        val values = Pts03Fixtures.fullResultValues() + (cropField to Pts03IntRect(1, 2, 3, 4))
        val api34 =
            pts03CaptureTruthOf({ f ->
                requested += f
                values[f]
            }, 34)
        assertNull(api34.activePhysicalSensorCropRegion)
        assertEquals(Pts03KeyAvailability.API_UNSUPPORTED, api34.activePhysicalSensorCropRegionAvailability)
        assertFalse(cropField in requested)

        val absent = pts03CaptureTruthOf(Pts03Fixtures.reader(Pts03Fixtures.fullResultValues()), 35)
        assertNull(absent.activePhysicalSensorCropRegion)
        assertEquals(Pts03KeyAvailability.NOT_REPORTED, absent.activePhysicalSensorCropRegionAvailability)
    }

    @Test
    fun `serialization separates logicalTopLevelResult from physicalResultsByCameraId and records the crop`() {
        val crop = Pts03IntRect(100, 80, 3980, 2992)
        val logical =
            pts03CaptureTruthOf(
                Pts03Fixtures.reader(Pts03Fixtures.fullResultValues(timestampNanos = 10L) + (cropField to crop)),
                35,
            )
        val snapshot =
            skyCaptureResultSnapshot(
                Pts03Fixtures.exposure(10L),
                logical,
                listOf(Pts03Fixtures.physicalResult("3", 9L), Pts03Fixtures.physicalResult("2", 10L)),
            )
        val json = Json.parseToJsonElement(pts03CaptureResultJson(snapshot).toString()).jsonObject

        assertEquals(
            listOf("sensorTimestampNanos", "logicalTopLevelResult", "physicalResultsByCameraId"),
            json.keys.toList(),
        )
        val top = json.getValue("logicalTopLevelResult").jsonObject
        assertEquals(
            100,
            top
                .getValue("activePhysicalSensorCropRegion")
                .jsonObject
                .getValue("left")
                .jsonPrimitive.int,
        )
        assertEquals("REPORTED", top.getValue("activePhysicalSensorCropRegionAvailability").jsonPrimitive.content)
        val physical = json.getValue("physicalResultsByCameraId").jsonObject
        assertEquals(listOf("2", "3"), physical.keys.toList())
        assertEquals(
            9L,
            physical
                .getValue("3")
                .jsonObject
                .getValue("sensorTimestampNanos")
                .jsonPrimitive.long,
        )
        assertIs<JsonNull>(physical.getValue("3").jsonObject.getValue("activePhysicalCameraId"))
        assertIs<JsonNull>(physical.getValue("3").jsonObject.getValue("activePhysicalSensorCropRegion"))
    }

    @Test
    fun `physical candidates are ordered generically, no ID privileged`() {
        assertEquals(
            listOf("10", "2", "3", "4"),
            orderedPhysicalCameraCandidates(listOf(listOf("4", "3"), listOf("2", "10", "3"))),
        )
        assertEquals(listOf("a", "b"), orderedPhysicalCameraCandidates(listOf(listOf("b", "a"))))
    }
}
