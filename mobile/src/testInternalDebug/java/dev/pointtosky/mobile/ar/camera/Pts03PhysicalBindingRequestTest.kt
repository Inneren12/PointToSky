package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PTS-03 follow-up: A1 requests no physical ID anywhere; A2 requests the one explicit ID on the selector, the
 * Preview interop and the ImageAnalysis interop; the export records those as requested configuration; and the
 * device-result discriminator never turns the top-level logical active ID into physical truth.
 */
class Pts03PhysicalBindingRequestTest {
    private fun logicalSession(): Pts03TruthSessionState =
        initialFrameContentExperimentSessionState(
            attemptId = 1L,
            physicalCameraId = PTS03_LOGICAL_UNPINNED_CANDIDATE,
            pts03Request = Pts03AttemptRequest(startedAtEpochMillis = 1L),
        ).pts03

    private fun explicitSession(id: String): Pts03TruthSessionState =
        initialFrameContentExperimentSessionState(attemptId = 2L, physicalCameraId = id).pts03

    private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun export(session: Pts03TruthSessionState): JsonObject =
        parse(buildPts03CameraTruthJson(session, Pts03CameraTruthExportTest.TEST_ENVIRONMENT, 5L, true))

    // --- Binding request -------------------------------------------------------------------------------

    @Test
    fun `A1 pins nothing - no selector, Preview or ImageAnalysis physical ID`() {
        val request = logicalSession().physicalBindingRequest
        assertEquals(Pts03PhysicalBindingRequest.LOGICAL_UNPINNED, request)
        assertFalse(request.isExplicitPhysical)
        assertNull(request.selectorPhysicalCameraId)
        assertNull(request.previewInteropPhysicalCameraId)
        assertNull(request.analysisInteropPhysicalCameraId)
    }

    @Test
    fun `A2 requests the same arbitrary ID on the selector, Preview and ImageAnalysis`() {
        for (id in listOf("alpha", "42")) {
            val request = explicitSession(id).physicalBindingRequest
            assertTrue(request.isExplicitPhysical)
            assertEquals(id, request.selectorPhysicalCameraId)
            assertEquals(id, request.previewInteropPhysicalCameraId)
            assertEquals(id, request.analysisInteropPhysicalCameraId)
        }
    }

    @Test
    fun `the request is taken from the explicit physical ID, never from any other session field`() {
        val a = explicitSession("alpha")
        val b = explicitSession("42")
        assertEquals("alpha", a.physicalBindingRequest.explicitPhysicalCameraId)
        assertEquals("42", b.physicalBindingRequest.explicitPhysicalCameraId)
        // Feeding frames whose top-level logical active ID names another camera does not change the request.
        val fed =
            a.withMatchedFrame(
                Pts03Fixtures.frameMetadata(10L),
                Pts03Fixtures.captureResult(timestampNanos = 10L, activePhysicalId = "42"),
            )
        assertEquals(Pts03PhysicalBindingRequest("alpha"), fed.physicalBindingRequest)
    }

    // --- Export ------------------------------------------------------------------------------------------

    @Test
    fun `export records the three requested A2 IDs as requested bind configuration`() {
        val block = export(explicitSession("alpha"))["physicalBindingRequest"]!!.jsonObject
        assertEquals(
            listOf(
                "provenance",
                "selectorPhysicalCameraId",
                "previewInteropPhysicalCameraId",
                "analysisInteropPhysicalCameraId",
            ),
            block.keys.toList(),
        )
        assertEquals("REQUESTED_BIND_CONFIGURATION", block["provenance"]!!.jsonPrimitive.content)
        assertEquals("alpha", block["selectorPhysicalCameraId"]!!.jsonPrimitive.content)
        assertEquals("alpha", block["previewInteropPhysicalCameraId"]!!.jsonPrimitive.content)
        assertEquals("alpha", block["analysisInteropPhysicalCameraId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `export records JSON null for all three A1 IDs`() {
        val block = export(logicalSession())["physicalBindingRequest"]!!.jsonObject
        assertIs<JsonNull>(block["selectorPhysicalCameraId"])
        assertIs<JsonNull>(block["previewInteropPhysicalCameraId"])
        assertIs<JsonNull>(block["analysisInteropPhysicalCameraId"])
    }

    // --- Device-result discriminator --------------------------------------------------------------------

    private fun attribution(
        requested: String,
        topLevelActive: String?,
        physicalIds: List<String> = emptyList(),
        physicalTimestamp: Long? = 10L,
    ): Pts03FrameCameraAttribution =
        attributePts03Frame(
            Pts03SessionClass.EXPLICIT_PHYSICAL,
            requested,
            10L,
            Pts03Fixtures.captureResult(
                timestampNanos = 10L,
                activePhysicalId = topLevelActive,
                physicalIds = physicalIds,
                physicalTimestampNanos = physicalTimestamp,
            ),
        )

    @Test
    fun `requested 3 with a matched physical result for 3 is PRESENT and resolved`() {
        val a = attribution("3", topLevelActive = "2", physicalIds = listOf("3"))
        assertEquals(Pts03PhysicalResultObservation.PHYSICAL_RESULT_PRESENT, a.physicalResultObservation)
        assertTrue(a.physicalDynamicMetadataResolved)
        assertEquals(2.2f, a.dynamicTruth?.lensFocalLengthMm, "the physical entry's focal length, not the logical 6.9")
    }

    @Test
    fun `requested 3, no physical result, top-level 3 is NOT_REPORTED_TOP_LEVEL_MATCHES_REQUESTED and unresolved`() {
        val a = attribution("3", topLevelActive = "3")
        assertEquals(
            Pts03PhysicalResultObservation.PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_MATCHES_REQUESTED,
            a.physicalResultObservation,
        )
        assertFalse(a.physicalDynamicMetadataResolved)
        assertNull(a.dynamicTruth, "a matching top-level ID is not promoted to physical truth")
        assertEquals("3", a.producingPhysicalCameraId, "A2 producer stays the requested ID by configuration")
    }

    @Test
    fun `requested 3, no physical result, top-level 2 is NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED and unresolved`() {
        val a = attribution("3", topLevelActive = "2")
        assertEquals(
            Pts03PhysicalResultObservation.PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED,
            a.physicalResultObservation,
        )
        assertFalse(a.physicalDynamicMetadataResolved)
        assertNull(a.dynamicTruth, "the logical 6.9 mm result is never read as camera 3's")
        assertEquals("3", a.producingPhysicalCameraId, "the top-level camera 2 is not made the producer")
        assertEquals("2", a.logicalActivePhysicalCameraId)
        assertEquals(Pts03EffectiveDistortionMode.PhysicalResultUnavailable, a.effectiveDistortionMode())
    }

    @Test
    fun `missing top-level ID, mismatched and missing physical timestamps are their own observations`() {
        assertEquals(
            Pts03PhysicalResultObservation.PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_NOT_REPORTED,
            attribution("3", topLevelActive = null).physicalResultObservation,
        )
        val mismatch = attribution("3", topLevelActive = "3", physicalIds = listOf("3"), physicalTimestamp = 11L)
        assertEquals(Pts03PhysicalResultObservation.PHYSICAL_RESULT_TIMESTAMP_MISMATCH, mismatch.physicalResultObservation)
        assertFalse(mismatch.physicalDynamicMetadataResolved)
        val missing = attribution("3", topLevelActive = "3", physicalIds = listOf("3"), physicalTimestamp = null)
        assertEquals(Pts03PhysicalResultObservation.PHYSICAL_RESULT_TIMESTAMP_MISSING, missing.physicalResultObservation)
        assertFalse(missing.physicalDynamicMetadataResolved)
    }

    @Test
    fun `a physical result for another camera does not count for the requested one`() {
        val a = attribution("alpha", topLevelActive = "42", physicalIds = listOf("42"))
        assertEquals(
            Pts03PhysicalResultObservation.PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED,
            a.physicalResultObservation,
        )
        assertFalse(a.physicalDynamicMetadataResolved)
    }

    @Test
    fun `A1 frames carry no observation`() {
        val a =
            attributePts03Frame(
                Pts03SessionClass.LOGICAL_UNPINNED,
                null,
                10L,
                Pts03Fixtures.captureResult(timestampNanos = 10L, activePhysicalId = "2"),
            )
        assertNull(a.physicalResultObservation)
    }

    private fun Pts03TruthSessionState.feed(
        timestamp: Long,
        activeId: String?,
        physicalIds: List<String> = emptyList(),
    ): Pts03TruthSessionState =
        withMatchedFrame(
            Pts03Fixtures.frameMetadata(timestamp),
            Pts03Fixtures.captureResult(timestampNanos = timestamp, activePhysicalId = activeId, physicalIds = physicalIds),
        )

    private fun Pts03TruthSessionState.observation(): Pts03PhysicalResultObservation =
        summarizePts03PhysicalResultObservation(sessionClass, requestedPhysicalCameraId, identity)

    @Test
    fun `session discriminator - the Pixel 9 phys3 shape stays DIFFERS_FROM_REQUESTED and unresolved`() {
        val s = explicitSession("3").feed(10L, "2").feed(20L, "2").feed(30L, "2")
        assertEquals(Pts03PhysicalResultObservation.PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED, s.observation())
        assertEquals(
            Pts03ExplicitPhysicalResultStatus.PHYSICAL_RESULT_NOT_REPORTED,
            summarizePts03ExplicitPhysicalResults(s.sessionClass, s.requestedPhysicalCameraId, s.identity),
        )
        assertEquals(emptyMap(), s.identity.focalLengthsMmByProducingPhysicalId, "no logical focal length as camera 3's")
        assertEquals(emptyMap(), s.identity.framesByPhysicalResultCameraId)

        val identity = export(s)["groupA_identity"]!!.jsonObject["explicitPhysicalOutput"]!!.jsonObject
        assertEquals(
            "PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED",
            identity["physicalResultObservation"]!!.jsonPrimitive.content,
        )
        assertEquals(
            3L,
            identity["physicalResultObservationCounts"]!!
                .jsonObject["PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED"]!!
                .jsonPrimitive.long,
        )
        val frame = export(s)["frameRecords"]!!.jsonArray.first().jsonObject["attribution"]!!.jsonObject
        assertEquals(
            "PHYSICAL_RESULT_NOT_REPORTED_TOP_LEVEL_DIFFERS_FROM_REQUESTED",
            frame["physicalResultObservation"]!!.jsonPrimitive.content,
        )
        assertEquals("false", frame["physicalDynamicMetadataResolved"]!!.jsonPrimitive.content)
    }

    @Test
    fun `session discriminator - uniform PRESENT, MIXED, no frames and A1`() {
        assertEquals(
            Pts03PhysicalResultObservation.PHYSICAL_RESULT_PRESENT,
            explicitSession("3").feed(10L, "2", listOf("3")).feed(20L, "3", listOf("3")).observation(),
        )
        assertEquals(
            Pts03PhysicalResultObservation.MIXED,
            explicitSession("3").feed(10L, "2", listOf("3")).feed(20L, "2").observation(),
        )
        assertEquals(
            Pts03PhysicalResultObservation.MIXED,
            explicitSession("3").feed(10L, "2").feed(20L, "3").observation(),
        )
        assertEquals(Pts03PhysicalResultObservation.NO_MATCHED_FRAMES, explicitSession("3").observation())
        assertEquals(
            Pts03PhysicalResultObservation.NOT_APPLICABLE_LOGICAL_SESSION,
            logicalSession().feed(10L, "2").observation(),
        )
        assertEquals(emptyMap(), logicalSession().feed(10L, "2").identity.physicalResultObservationCounts)
    }
}
