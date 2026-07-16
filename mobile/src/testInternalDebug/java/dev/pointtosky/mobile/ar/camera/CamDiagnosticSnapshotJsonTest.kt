package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [buildCamDiagnosticJson]/[buildCamDiagnosticJsonElement] (CAM diagnostic
 * export/freeze fix §5) - deterministic field names, explicit nulls, every one of the 9 sensor-to-buffer
 * matrix values preserved as a JSON number, and the real Pixel 9 logical-multi-camera evidence.
 */
class CamDiagnosticSnapshotJsonTest {
    private val defaultLocale: Locale = Locale.getDefault()

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    private val physicalSensorIntrinsics =
        CameraIntrinsics(
            horizontalFovDeg = 70.7,
            verticalFovDeg = 56.2,
            focalLengthMm = 6.81,
            sensorWidthMm = 9.80,
            sensorHeightMm = 7.35,
            principalPointXPx = null,
            principalPointYPx = null,
            source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            reference = CameraIntrinsicsReference.PhysicalSensor,
        )

    private val pixel9CharacteristicsSnapshot =
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm = floatArrayOf(6.81f),
            sensorPhysicalWidthMm = 9.80f,
            sensorPhysicalHeightMm = 7.35f,
            activeArrayLeftPx = 0,
            activeArrayTopPx = 0,
            activeArrayRightPx = 4080,
            activeArrayBottomPx = 3072,
            pixelArrayWidthPx = 4080,
            pixelArrayHeightPx = 3072,
            isLogicalMultiCamera = true,
            cameraId = "0",
            physicalCameraIds = setOf("2", "3", "4"),
        )

    private val pixel9Matrix = SensorToBufferMatrix3(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9)

    private fun pixel9IntrinsicsState(
        attempt: AnalysisBufferIntrinsicsResolution? =
            AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping(
                cameraId = "0",
                physicalCameraIdsForDiagnostics = setOf("4", "2", "3"),
            ),
        published: CoreCameraIntrinsicsResolution? = CoreCameraIntrinsicsResolution.Resolved(physicalSensorIntrinsics),
        characteristics: CameraCharacteristicsSnapshot? = pixel9CharacteristicsSnapshot,
        matrix: SensorToBufferMatrix3? = pixel9Matrix,
        transformClass: SensorToBufferTransformClass? = SensorToBufferTransformClass.AXIS_ALIGNED_0,
    ) = CameraSessionIntrinsicsDiagnosticState(
        analysisBufferAttempt = attempt,
        publishedIntrinsicsResolution = published,
        coordinatorState = CameraSessionIntrinsicsCoordinatorState.RESOLVED,
        cameraCharacteristicsSnapshot = characteristics,
        frameCounters =
            CameraSessionIntrinsicsFrameCounters(
                framesAnalyzed = 1115L,
                framesWithTransform = 1115L,
                framesWithNullTransform = 0L,
                framesWithUsableTransform = 1115L,
                coordinatorFramesWaited = 1,
                latestFrameTransform = matrix,
                latestFrameTransformClass = transformClass,
            ),
    )

    private fun snapshot(
        state: CameraSessionIntrinsicsDiagnosticState? = pixel9IntrinsicsState(),
        calibration: CameraCalibrationDiagnostics? = null,
        capturedAtEpochMillis: Long = 1_700_000_000_000L,
        sessionId: Long = 9L,
    ) = captureCamDiagnosticSnapshot(
        capturedAtEpochMillis = capturedAtEpochMillis,
        sessionId = sessionId,
        cam2bState = null,
        cameraGeometryState = null,
        cameraGeometryStatusTransitionCount = 0,
        cameraGeometryObservedFrameCount = 1115L,
        cameraGeometryReadyBundleCount = 0L,
        cameraIntrinsicsState = state,
        calibrationDiagnostics = calibration,
    )

    private val pixel9Snapshot = snapshot()

    @Test
    fun `the same snapshot always serializes to the exact same JSON string`() {
        val first = buildCamDiagnosticJson(pixel9Snapshot, CamDiagnosticLiveness.LIVE)
        val second = buildCamDiagnosticJson(pixel9Snapshot, CamDiagnosticLiveness.LIVE)

        assertEquals(first, second)
    }

    @Test
    fun `the top-level envelope matches schemaVersion 1 and the task's own worked example`() {
        val root = buildCamDiagnosticJsonElement(pixel9Snapshot, CamDiagnosticLiveness.LIVE)

        assertEquals(1, root["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals(1_700_000_000_000L, root["capturedAtEpochMillis"]!!.jsonPrimitive.long)
        assertEquals(9L, root["sessionId"]!!.jsonPrimitive.long)

        val cam2c = root["cam2c"]!!.jsonObject
        assertEquals("UnsupportedLogicalMultiCameraMapping", cam2c["attemptType"]!!.jsonPrimitive.content)
        assertEquals("0", cam2c["cameraId"]!!.jsonPrimitive.content)
        // Deterministic (sorted) regardless of the Set's own iteration order.
        assertEquals(listOf("2", "3", "4"), cam2c["physicalCameraIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `all 9 sensor-to-buffer matrix values are preserved as JSON numbers, not a formatted string`() {
        val root = buildCamDiagnosticJsonElement(pixel9Snapshot, CamDiagnosticLiveness.LIVE)

        val matrix = root["cam2c"]!!.jsonObject["frameTransform"]!!.jsonObject["matrix"]!!.jsonArray
        assertEquals(9, matrix.size)
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9), matrix.map { it.jsonPrimitive.double })
    }

    @Test
    fun `a null latest-frame transform serializes matrix as an explicit JSON null, never an empty or fabricated array`() {
        val stateWithNullMatrix = pixel9IntrinsicsState(matrix = null, transformClass = null)

        val root = buildCamDiagnosticJsonElement(snapshot(state = stateWithNullMatrix), CamDiagnosticLiveness.LIVE)
        val frameTransform = root["cam2c"]!!.jsonObject["frameTransform"]!!.jsonObject

        assertEquals(JsonNull, frameTransform["matrix"])
        assertEquals(false, frameTransform["present"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, frameTransform["transformClass"])
    }

    @Test
    fun `missing metadata serializes every metadata and camera field as an explicit JSON null`() {
        val emptyState =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = null,
                publishedIntrinsicsResolution = null,
                coordinatorState = CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_CAMERA_INFO,
                cameraCharacteristicsSnapshot = null,
                frameCounters =
                    CameraSessionIntrinsicsFrameCounters(
                        framesAnalyzed = 0L,
                        framesWithTransform = 0L,
                        framesWithNullTransform = 0L,
                        framesWithUsableTransform = 0L,
                        coordinatorFramesWaited = 0,
                        latestFrameTransform = null,
                        latestFrameTransformClass = null,
                    ),
            )
        val root = buildCamDiagnosticJsonElement(snapshot(state = emptyState, capturedAtEpochMillis = 0L, sessionId = 1L), CamDiagnosticLiveness.LIVE)
        val cam2c = root["cam2c"]!!.jsonObject

        assertEquals(JsonNull, cam2c["cameraId"])
        assertEquals(JsonNull, cam2c["physicalCameraIds"])
        assertEquals(JsonNull, cam2c["attemptType"])
        assertEquals(JsonNull, root["calibration"])

        val metadata = cam2c["metadata"]!!.jsonObject
        assertEquals(JsonNull, metadata["pixelArrayWidthPx"])
        assertEquals(JsonNull, metadata["sensorPhysicalWidthMm"])
        assertEquals(JsonNull, metadata["availableFocalLengthsMm"])
    }

    @Test
    fun `a successful calibrated snapshot serializes a non-null calibration object and resolved buffer K`() {
        val calibrationDiagnostics =
            CameraCalibrationDiagnostics(
                activeArrayWidthPx = 4080,
                activeArrayHeightPx = 3072,
                activeArrayLeftPx = 0.0,
                activeArrayTopPx = 0.0,
                activeArrayRightPx = 4080.0,
                activeArrayBottomPx = 3072.0,
                pixelArrayWidthPx = 4080,
                pixelArrayHeightPx = 3072,
                sensorWidthMm = 9.80,
                sensorHeightMm = 7.35,
                focalLengthMm = 6.81,
                activeFxPx = 2830.0,
                activeFyPx = 2830.0,
                activeCxPx = 2040.0,
                activeCyPx = 1536.0,
                principalPointBasis = CameraCalibrationDiagnostics.PRINCIPAL_POINT_BASIS_ACTIVE_ARRAY_LOCAL,
                focalDerivationBasis = CameraCalibrationDiagnostics.FOCAL_DERIVATION_BASIS_LENS_INTRINSIC_CALIBRATION,
                cropLeftPx = 0.0,
                cropTopPx = 0.0,
                cropRightPx = 4080.0,
                cropBottomPx = 3072.0,
                bufferFxPx = 443.75,
                bufferFyPx = 443.75,
                bufferCxPx = 320.0,
                bufferCyPx = 240.0,
                quality = CameraIntrinsicsQuality.CALIBRATED,
                sensorToBufferMappingSource = CameraCalibrationDiagnostics.SENSOR_TO_BUFFER_MAPPING_SOURCE,
                transformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
                cameraId = "0",
                isLogicalMultiCamera = false,
                physicalCameraIds = null,
            )
        val calibratedIntrinsics =
            physicalSensorIntrinsics.copy(
                reference = CameraIntrinsicsReference.AnalysisBuffer(640, 480),
                quality = CameraIntrinsicsQuality.CALIBRATED,
            )
        val resolvedAttempt = AnalysisBufferIntrinsicsResolution.Resolved(calibratedIntrinsics, calibrationDiagnostics)
        val state =
            pixel9IntrinsicsState(
                attempt = resolvedAttempt,
                published = CoreCameraIntrinsicsResolution.Resolved(calibratedIntrinsics),
                characteristics = pixel9CharacteristicsSnapshot.copy(isLogicalMultiCamera = false),
            )

        val root = buildCamDiagnosticJsonElement(snapshot(state = state, calibration = calibrationDiagnostics), CamDiagnosticLiveness.LIVE)

        val calibration = root["calibration"]!!.jsonObject
        assertEquals("CALIBRATED", calibration["quality"]!!.jsonPrimitive.content)
        assertEquals(443.75, calibration["bufferFxPx"]!!.jsonPrimitive.double)

        val resolvedBufferK = root["cam2c"]!!.jsonObject["resolvedBufferK"]!!.jsonObject
        assertEquals(443.75, resolvedBufferK["fxPx"]!!.jsonPrimitive.double)
        assertEquals(240.0, resolvedBufferK["cyPx"]!!.jsonPrimitive.double)
    }

    @Test
    fun `no numeric field is ever rendered as a locale-formatted string, regardless of the JVM default locale`() {
        Locale.setDefault(Locale.GERMANY)
        try {
            val json = buildCamDiagnosticJson(pixel9Snapshot, CamDiagnosticLiveness.LIVE)

            assertFalse(json.contains("\"0,1\""), "must never render a matrix value as a comma-decimal JSON string")
            assertTrue(json.contains("0.1"), "expected the dot-decimal matrix value 0.1 to be present")

            val root = buildCamDiagnosticJsonElement(pixel9Snapshot, CamDiagnosticLiveness.LIVE)
            val matrix = root["cam2c"]!!.jsonObject["frameTransform"]!!.jsonObject["matrix"]!!.jsonArray
            assertEquals(0.1, matrix[0].jsonPrimitive.double)
        } finally {
            Locale.setDefault(defaultLocale)
        }
    }

    @Test
    fun `never contains raw star catalog or image pixel keys`() {
        val json = buildCamDiagnosticJson(pixel9Snapshot, CamDiagnosticLiveness.LIVE)

        for (forbidden in listOf("catalog", "pixels", "bitmap", "screenshot")) {
            assertFalse(json.contains(forbidden, ignoreCase = true), "must never mention \"$forbidden\"")
        }
    }

    @Test
    fun `diagnostics liveness is carried through as an explicit string, distinguishing LIVE from FROZEN`() {
        val liveRoot = buildCamDiagnosticJsonElement(pixel9Snapshot, CamDiagnosticLiveness.LIVE)
        val frozenRoot = buildCamDiagnosticJsonElement(pixel9Snapshot, CamDiagnosticLiveness.FROZEN)

        assertEquals("LIVE", liveRoot["diagnostics"]!!.jsonPrimitive.content)
        assertEquals("FROZEN", frozenRoot["diagnostics"]!!.jsonPrimitive.content)
    }
}
