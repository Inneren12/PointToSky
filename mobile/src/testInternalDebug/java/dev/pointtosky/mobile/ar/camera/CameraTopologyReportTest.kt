package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure JVM tests for [buildCameraTopologyReportText]/[buildCameraTopologyJson] (task §3). */
class CameraTopologyReportTest {
    private fun pixel9LikeEntry() =
        CameraTopologyEntry(
            camera2Id = "0",
            lensFacing = "BACK",
            isLogicalMultiCamera = true,
            declaredPhysicalCameraIds = listOf("2", "3", "4"),
            focalLengthsMm = listOf(6.90f),
            sensorPhysicalWidthMm = 9.79f,
            sensorPhysicalHeightMm = 7.37f,
            pixelArrayWidthPx = 4080,
            pixelArrayHeightPx = 3072,
            activeArrayLeftPx = 0,
            activeArrayTopPx = 0,
            activeArrayRightPx = 4080,
            activeArrayBottomPx = 3072,
            preCorrectionActiveArrayLeftPx = null,
            preCorrectionActiveArrayTopPx = null,
            preCorrectionActiveArrayRightPx = null,
            preCorrectionActiveArrayBottomPx = null,
            hardwareLevel = "FULL",
            capabilities = listOf("BACKWARD_COMPATIBLE", "LOGICAL_MULTI_CAMERA"),
            imageAnalysisStreamConfigurationsPx = listOf("640x480", "1280x720"),
            physicalCameraCandidates =
                listOf(
                    PhysicalCameraTopologyEntry(
                        camera2Id = "2",
                        focalLengthsMm = listOf(6.90f),
                        sensorPhysicalWidthMm = 9.79f,
                        sensorPhysicalHeightMm = 7.37f,
                        pixelArrayWidthPx = 4080,
                        pixelArrayHeightPx = 3072,
                        activeArrayLeftPx = 0,
                        activeArrayTopPx = 0,
                        activeArrayRightPx = 4080,
                        activeArrayBottomPx = 3072,
                    ),
                ),
        )

    @Test
    fun `text report includes camera id, logical flag, and every declared physical id`() {
        val report = CameraTopologyReport(entries = listOf(pixel9LikeEntry()), boundPrimaryRearCamera2Id = "0")

        val text = buildCameraTopologyReportText(report)

        assertTrue(text.contains("cameraId=0"))
        assertTrue(text.contains("logicalMultiCamera=true"))
        assertTrue(text.contains("declaredPhysicalCameraIds=2,3,4"))
        assertTrue(text.contains("boundPrimaryRearCamera2Id: 0"))
        assertTrue(text.contains("physical id=2"))
    }

    @Test
    fun `empty report is explicit, never a blank string`() {
        val text = buildCameraTopologyReportText(CameraTopologyReport(entries = emptyList(), boundPrimaryRearCamera2Id = null))

        assertTrue(text.contains("no rear cameras discovered"))
        assertTrue(text.contains("boundPrimaryRearCamera2Id: unknown"))
    }

    @Test
    fun `json export round-trips every declared physical id and preserves schema version`() {
        val report = CameraTopologyReport(entries = listOf(pixel9LikeEntry()), boundPrimaryRearCamera2Id = "0")

        val json = buildCameraTopologyJsonElement(report)

        assertEquals(CAMERA_TOPOLOGY_JSON_SCHEMA_VERSION, json["schemaVersion"]?.toString()?.toInt())
        val entries = json["entries"]!!.toString()
        assertTrue(entries.contains("\"2\""))
        assertTrue(entries.contains("\"3\""))
        assertTrue(entries.contains("\"4\""))
        assertTrue(entries.contains("\"isLogicalMultiCamera\":true"))
    }
}
