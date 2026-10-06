package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** PTS-03 export: versioned, deterministic, nulls honest, and safety facts always stated. */
class Pts03CameraTruthExportTest {
    private val environment =
        Pts03Environment(
            deviceManufacturer = "TestCo",
            deviceModel = "Model",
            deviceName = "device",
            buildFingerprint = "fp",
            androidRelease = "16",
            sdkInt = 36,
            securityPatch = null,
            appVersionName = "0.1.0",
            appVersionCode = 1,
        )

    private fun session(): Pts03TruthSessionState {
        var state =
            initialFrameContentExperimentSessionState(
                attemptId = 1L,
                physicalCameraId = "3",
                pts03Request =
                    Pts03AttemptRequest(
                        Pts03DistortionModeRequest.OFF,
                        Pts03LightingLabel.NORMAL_INDOOR,
                        1_000L,
                    ),
            ).reducePts03Bound(
                1L,
                "0",
                Pts03Fixtures.characteristicsSet(),
                Pts03StreamConfiguration(null, null),
            )
        val detection = FrameContentDetectionResult.InsufficientOrAmbiguousGrid("none", 0)
        state =
            state.reduceFrame(
                1L,
                Pts03Fixtures.frameMetadata(10L),
                detection,
                0L,
                Pts03Fixtures.captureResult(10L, activePhysicalId = null),
            )
        state = state.reducePts03AddEvidence(1L, Pts03Fixtures.snapshot())
        return state.pts03
    }

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `schema version is pinned`() {
        assertEquals(1, PTS03_CAMERA_TRUTH_JSON_SCHEMA_VERSION)
        val root = parse(buildPts03CameraTruthJson(session(), environment, 5L, includeAllRetainedFrameRecords = true))
        assertEquals("pointtosky.pts03.camera_truth", root["schema"]!!.jsonPrimitive.content)
        assertEquals(1, root["schemaVersion"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the export is deterministic and keeps a fixed top-level key order`() {
        val s = session()
        val a = buildPts03CameraTruthJson(s, environment, 5L, true)
        val b = buildPts03CameraTruthJson(s, environment, 5L, true)
        assertEquals(a, b)
        assertEquals(
            listOf(
                "schema",
                "schemaVersion",
                "sessionId",
                "exportedAtEpochMillis",
                "environment",
                "session",
                "safety",
                "streamConfiguration",
                "joinStatistics",
                "groupA_identity",
                "staticCharacteristics",
                "groupD_extrinsics",
                "groupB_and_C_targetEvidence",
                "groupC_distortionChains",
                "totalMatchedFrameRecords",
                "omittedFrameRecords",
                "rawFrameRecordsIncluded",
                "frameRecords",
            ),
            parse(a).keys.toList(),
        )
    }

    @Test
    fun `nulls are JSON null, unknown stream configuration is UNKNOWN, and safety facts are false`() {
        val root = parse(buildPts03CameraTruthJson(session(), environment, 5L, true))
        val frame = root["frameRecords"]!!.jsonArray.single().jsonObject
        val result = frame["captureResult"]!!.jsonObject
        assertIs<JsonNull>(result["activePhysicalCameraId"])
        assertEquals("NOT_REPORTED", result["activePhysicalCameraIdAvailability"]!!.jsonPrimitive.content)
        assertEquals(10L, result["sensorTimestampNanos"]!!.jsonPrimitive.long)
        assertIs<JsonNull>(root["environment"]!!.jsonObject["securityPatch"])
        assertEquals(
            "UNKNOWN",
            root["streamConfiguration"]!!.jsonObject["previewResolutionInfo"]!!.jsonPrimitive.content,
        )

        val safety = root["safety"]!!.jsonObject
        assertFalse(safety["sensorToBufferDomainProofProvenConstructed"]!!.jsonPrimitive.boolean)
        assertFalse(safety["analysisBufferIntrinsicsPublished"]!!.jsonPrimitive.boolean)
        assertFalse(safety["lensDistortionApplied"]!!.jsonPrimitive.boolean)
        assertFalse(safety["cameraStrategySelected"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `groups B C and D are exported conservatively`() {
        val root = parse(buildPts03CameraTruthJson(session(), environment, 5L, true))
        val capture = root["groupB_and_C_targetEvidence"]!!.jsonArray.single().jsonObject
        assertEquals("UNRESOLVED", capture["projectionDomain"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
        assertFalse(capture["projectionDomain"]!!.jsonObject["unlocksCalibratedProjection"]!!.jsonPrimitive.boolean)
        val chain = root["groupC_distortionChains"]!!.jsonArray.single().jsonObject
        assertFalse(chain["finalPixelDomainEstablished"]!!.jsonPrimitive.boolean)
        val extrinsics = root["groupD_extrinsics"]!!.jsonArray
        assertEquals(listOf("2", "3", "4"), extrinsics.map { it.jsonObject["cameraId"]!!.jsonPrimitive.content })
        assertTrue(
            extrinsics.all {
                it.jsonObject["platformPoseStatus"]!!.jsonPrimitive.content ==
                    "PLATFORM_POSE_PRESENT_BUT_UNVERIFIED"
            },
        )
        assertTrue(
            extrinsics.all {
                it.jsonObject["extrinsicInUseByPointToSky"]!!.jsonPrimitive.content ==
                    "NOMINAL_FALLBACK"
            },
        )
    }

    @Test
    fun `the share variant omits raw records and says so`() {
        val root = parse(buildPts03CameraTruthJson(session(), environment, 5L, includeAllRetainedFrameRecords = false))
        assertEquals(0, root["frameRecords"]!!.jsonArray.size)
        assertFalse(root["rawFrameRecordsIncluded"]!!.jsonPrimitive.boolean)
        assertEquals(1L, root["omittedFrameRecords"]!!.jsonPrimitive.long)
    }

    @Test
    fun `the declared CameraX version matches the version catalog`() {
        val catalog =
            listOf(
                File("../gradle/libs.versions.toml"),
                File("gradle/libs.versions.toml"),
            ).first { it.exists() }
        val declared =
            Regex(
                """^camerax\s*=\s*"([^"]+)"""",
                RegexOption.MULTILINE,
            ).find(catalog.readText())!!.groupValues[1]
        assertEquals(declared, PTS03_DECLARED_CAMERAX_VERSION)
    }

    @Test
    fun `frame-content export schema 5 carries the joined CaptureResult and keeps schema-4 keys`() {
        assertEquals(5, FRAME_CONTENT_EXPERIMENT_JSON_SCHEMA_VERSION)
        val withResult = parse(buildFrameContentCorrespondenceJson(Pts03Fixtures.snapshot()))
        assertEquals(5, withResult["schemaVersion"]!!.jsonPrimitive.int)
        assertEquals(
            "FAST",
            withResult["captureResult"]!!.jsonObject["effectiveDistortionMode"]!!.jsonPrimitive.content,
        )
        assertTrue("target" in withResult && "verdict" in withResult && "hypotheses" in withResult)

        val without = parse(buildFrameContentCorrespondenceJson(Pts03Fixtures.snapshot(captureResult = null)))
        assertIs<JsonNull>(without["captureResult"])
        assertTrue(
            buildFrameContentCorrespondenceReportText(
                Pts03Fixtures.snapshot(),
            ).contains("mode is NOT evidence that the YUV is corrected"),
        )
    }
}
