package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * PTS-03 session accumulation: Group A identity over every matched frame (transitions only between reported
 * IDs), A1/A2 attribution, bounded raw records, attempt isolation, finalize, and frame-frozen lighting.
 */
class Pts03CameraTruthSessionTest {
    private fun logicalSession(): Pts03TruthSessionState =
        initialFrameContentExperimentSessionState(
            attemptId = 3L,
            physicalCameraId = PTS03_LOGICAL_UNPINNED_CANDIDATE,
            pts03Request =
                Pts03AttemptRequest(
                    lighting = Pts03LightingLabel.NORMAL_INDOOR,
                    startedAtEpochMillis = 1_700_000_000_000L,
                ),
        ).pts03

    private fun explicitSession(id: String = "3"): Pts03TruthSessionState =
        initialFrameContentExperimentSessionState(attemptId = 4L, physicalCameraId = id).pts03

    private fun Pts03TruthSessionState.feed(
        timestamp: Long,
        activeId: String?,
        physicalIds: List<String> = emptyList(),
        physicalTimestamp: Long? = timestamp,
    ): Pts03TruthSessionState =
        withMatchedFrame(
            Pts03Fixtures.frameMetadata(timestamp),
            Pts03Fixtures.captureResult(
                timestampNanos = timestamp,
                activePhysicalId = activeId,
                physicalIds = physicalIds,
                physicalTimestampNanos = physicalTimestamp,
            ),
        )

    private fun Pts03TruthSessionState.feedIds(vararg ids: String?): Pts03TruthSessionState {
        var s = this
        ids.forEachIndexed { i, id -> s = s.feed((i + 1) * 10L, id) }
        return s
    }

    @Test
    fun `the logical candidate is an A1 session with no requested physical ID and a stable session ID`() {
        val s = logicalSession()
        assertEquals(Pts03SessionClass.LOGICAL_UNPINNED, s.sessionClass)
        assertNull(s.requestedPhysicalCameraId)
        assertEquals("pts03-1700000000000-a3-logical", s.sessionId)
        assertEquals(
            Pts03ExplicitPhysicalResultStatus.NOT_APPLICABLE_LOGICAL_SESSION,
            summarizePts03ExplicitPhysicalResults(s.sessionClass, null, s.identity),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Transitions: a missing active ID is a gap, not an identity
    // -----------------------------------------------------------------------------------------

    @Test
    fun `3 then missing then 3 is no transition, and the missing frame is still counted`() {
        val id = logicalSession().feedIds("3", null, "3").identity
        assertEquals(0L, id.transitionCount)
        assertTrue(id.transitions.isEmpty())
        assertEquals(1L, id.framesWithNullActivePhysicalId)
        assertEquals(mapOf("3" to 2L, PTS03_ACTIVE_ID_NOT_REPORTED_KEY to 1L), id.framesByLogicalActivePhysicalId)
        assertEquals(1L, id.activePhysicalIdAvailabilityCounts[Pts03KeyAvailability.NOT_REPORTED])
        assertEquals(2L, id.activePhysicalIdAvailabilityCounts[Pts03KeyAvailability.REPORTED])
    }

    @Test
    fun `3 then missing then 4 is one transition 3 to 4 stamped on the first frame reporting 4`() {
        var s = logicalSession().feed(10, "3").feed(20, null)
        s = s.copy(lighting = Pts03LightingLabel.LOW_LIGHT_INDOOR).feed(30, "4")
        val id = s.identity
        assertEquals(1L, id.transitionCount)
        val t = id.transitions.single()
        assertEquals("3", t.fromActivePhysicalCameraId)
        assertEquals("4", t.toActivePhysicalCameraId)
        assertEquals(30L, t.sensorTimestampNanos)
        assertEquals(2L, t.frameIndex)
        assertEquals(Pts03LightingLabel.LOW_LIGHT_INDOOR, t.lighting)
        assertEquals(1L, id.framesWithNullActivePhysicalId)
    }

    @Test
    fun `missing then 3 is no transition`() {
        val id = logicalSession().feedIds(null, "3").identity
        assertEquals(0L, id.transitionCount)
        assertEquals("3", id.lastReportedActivePhysicalCameraId)
    }

    @Test
    fun `3 then 4 is one transition`() {
        val id = logicalSession().feedIds("3", "4").identity
        assertEquals(1L, id.transitionCount)
        assertEquals(
            "3" to "4",
            id.transitions.single().let {
                it.fromActivePhysicalCameraId to
                    it.toActivePhysicalCameraId
            },
        )
    }

    @Test
    fun `raw frame records keep the missing-ID frame`() {
        val s = logicalSession().feedIds("3", null, "3")
        assertEquals(listOf("3", null, "3"), s.headRecords.map { it.captureResult.logicalTruth.activePhysicalCameraId })
    }

    // -----------------------------------------------------------------------------------------
    // A1 / A2 attribution
    // -----------------------------------------------------------------------------------------

    @Test
    fun `A1 active 3 attributes the frame and its top-level dynamic metadata to 3`() {
        val a =
            logicalSession().attribute(
                10L,
                Pts03Fixtures.captureResult(timestampNanos = 10L, activePhysicalId = "3"),
            )
        assertEquals("3", a.producingPhysicalCameraId)
        assertEquals(Pts03DynamicMetadataSource.LOGICAL_TOP_LEVEL_RESULT, a.dynamicMetadataSource)
        assertTrue(a.physicalDynamicMetadataResolved)
        assertNull(a.physicalResultStatus)
    }

    @Test
    fun `A1 with the active ID missing leaves the producer unknown and physical evidence unresolved`() {
        val a =
            logicalSession().attribute(
                10L,
                Pts03Fixtures.captureResult(timestampNanos = 10L, activePhysicalId = null),
            )
        assertNull(a.producingPhysicalCameraId)
        assertEquals(Pts03DynamicMetadataSource.LOGICAL_TOP_LEVEL_RESULT_PRODUCER_UNKNOWN, a.dynamicMetadataSource)
        assertTrue(!a.physicalDynamicMetadataResolved)
    }

    @Test
    fun `A2 requested 3, top-level active 2, physical result 3 present - uses result 3, no contradiction`() {
        val result =
            Pts03Fixtures.captureResult(
                timestampNanos = 10L,
                activePhysicalId = "2",
                physicalIds = listOf("3"),
            )
        val a = explicitSession("3").attribute(10L, result)
        assertEquals("3", a.producingPhysicalCameraId)
        assertEquals("2", a.logicalActivePhysicalCameraId)
        assertEquals(Pts03DynamicMetadataSource.PHYSICAL_RESULT_FOR_REQUESTED_ID, a.dynamicMetadataSource)
        assertEquals(Pts03PhysicalResultStatus.PRESENT_TIMESTAMP_MATCHED, a.physicalResultStatus)
        assertSame(result.physicalResultsByCameraId.getValue("3").truth, a.dynamicTruth)
        assertEquals(2.2f, a.dynamicTruth!!.lensFocalLengthMm, "the physical value, not the logical 6.9 mm")
        assertEquals(800, a.dynamicExposure!!.sensitivityIso)

        val s = explicitSession("3").feed(10, "2", physicalIds = listOf("3"))
        assertEquals(
            Pts03ExplicitPhysicalResultStatus.PHYSICAL_RESULT_PRESENT,
            summarizePts03ExplicitPhysicalResults(s.sessionClass, "3", s.identity),
        )
        assertEquals(listOf(2.2f), s.identity.focalLengthsMmByProducingPhysicalId["3"])
    }

    @Test
    fun `A2 requested 3, top-level active 2, no physical result 3 - producer 3, dynamic unresolved`() {
        val result =
            Pts03Fixtures.captureResult(
                timestampNanos = 10L,
                activePhysicalId = "2",
                physicalIds = listOf("2"),
            )
        val a = explicitSession("3").attribute(10L, result)
        assertEquals("3", a.producingPhysicalCameraId)
        assertEquals(Pts03DynamicMetadataSource.UNRESOLVED_PHYSICAL_RESULT_NOT_REPORTED, a.dynamicMetadataSource)
        assertNull(a.dynamicTruth, "logical (or another camera's) metadata is never substituted")
        assertEquals(Pts03EffectiveDistortionMode.PhysicalResultUnavailable, a.effectiveDistortionMode())

        val s = explicitSession("3").feed(10, "2", physicalIds = listOf("2"))
        assertEquals(
            Pts03ExplicitPhysicalResultStatus.PHYSICAL_RESULT_NOT_REPORTED,
            summarizePts03ExplicitPhysicalResults(s.sessionClass, "3", s.identity),
        )
        assertNull(s.identity.focalLengthsMmByProducingPhysicalId["3"])
        assertNull(s.identity.focalLengthsMmByProducingPhysicalId["2"])
        assertEquals(1L, s.identity.framesByPhysicalResultCameraId["2"])
    }

    @Test
    fun `A2 physical result with a mismatched or missing timestamp is never frame truth`() {
        val mismatch =
            explicitSession().attribute(
                10L,
                Pts03Fixtures.captureResult(10L, physicalIds = listOf("3"), physicalTimestampNanos = 11L),
            )
        assertEquals(Pts03PhysicalResultStatus.TIMESTAMP_MISMATCH, mismatch.physicalResultStatus)
        assertEquals(11L, mismatch.physicalResultSensorTimestampNanos)
        assertNull(mismatch.dynamicTruth)

        val missing =
            explicitSession().attribute(
                10L,
                Pts03Fixtures.captureResult(10L, physicalIds = listOf("3"), physicalTimestampNanos = null),
            )
        assertEquals(Pts03PhysicalResultStatus.TIMESTAMP_MISSING, missing.physicalResultStatus)
        assertNull(missing.dynamicTruth)

        val s = explicitSession().feed(10, "3", physicalIds = listOf("3"), physicalTimestamp = 11L)
        assertEquals(
            Pts03ExplicitPhysicalResultStatus.PHYSICAL_RESULT_TIMESTAMP_MISMATCH,
            summarizePts03ExplicitPhysicalResults(s.sessionClass, "3", s.identity),
        )
    }

    @Test
    fun `A2 summary distinguishes no frames and partial presence`() {
        val c = Pts03SessionClass.EXPLICIT_PHYSICAL
        assertEquals(
            Pts03ExplicitPhysicalResultStatus.NO_MATCHED_FRAMES,
            summarizePts03ExplicitPhysicalResults(c, "3", Pts03IdentitySummary()),
        )
        val s = explicitSession().feed(10, "3", physicalIds = listOf("3")).feed(20, "3")
        assertEquals(
            Pts03ExplicitPhysicalResultStatus.PHYSICAL_RESULT_PARTIALLY_PRESENT,
            summarizePts03ExplicitPhysicalResults(c, "3", s.identity),
        )
    }

    // -----------------------------------------------------------------------------------------
    // Records, reducers, finalize
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a frame record requires the exact SENSOR_TIMESTAMP join`() {
        assertFailsWith<IllegalArgumentException> {
            logicalSession().withMatchedFrame(
                Pts03Fixtures.frameMetadata(100),
                Pts03Fixtures.captureResult(timestampNanos = 101),
            )
        }
    }

    @Test
    fun `raw records are bounded to head plus tail while summaries cover everything`() {
        var s = logicalSession()
        val total = Pts03TruthSessionState.MAX_HEAD_RECORDS + Pts03TruthSessionState.MAX_TAIL_RECORDS + 25
        for (i in 1..total) s = s.feed(i.toLong(), "3")
        assertEquals(total.toLong(), s.totalFrameRecords)
        assertEquals(Pts03TruthSessionState.MAX_HEAD_RECORDS, s.headRecords.size)
        assertEquals(Pts03TruthSessionState.MAX_TAIL_RECORDS, s.tailRecords.size)
        assertEquals(25L, s.omittedFrameRecords)
    }

    @Test
    fun `frame-content reducers keep the frame and its CaptureResult together and ignore other attempts`() {
        val state = initialFrameContentExperimentSessionState(attemptId = 5L, physicalCameraId = "3")
        val result = Pts03Fixtures.captureResult(timestampNanos = 42L)
        val detection = FrameContentDetectionResult.InsufficientOrAmbiguousGrid("none", 0)

        val next = state.reduceFrame(5L, Pts03Fixtures.frameMetadata(42L), detection, 0L, result)
        assertSame(result, next.latestCaptureResult)
        assertEquals(1L, next.pts03.totalFrameRecords)

        val mismatched = state.reduceFrame(5L, Pts03Fixtures.frameMetadata(43L), detection, 0L, result)
        assertNull(mismatched.latestCaptureResult)
        assertEquals(0L, mismatched.pts03.totalFrameRecords)

        assertSame(state, state.reduceFrame(4L, Pts03Fixtures.frameMetadata(42L), detection, 0L, result))
        assertSame(state, state.reducePts03JoinStatistics(4L, SkyJoinStatistics.EMPTY.copy(analysisFrameCount = 9)))
    }

    @Test
    fun `finalize freezes statistics once, and later live statistics or frames do not change them`() {
        val state = initialFrameContentExperimentSessionState(attemptId = 5L, physicalCameraId = "3")
        val final = SkyJoinStatistics.EMPTY.copy(analysisFrameCount = 3, framesPendingAtStopCount = 1)
        val finalized = state.reducePts03Finalized(5L, final)
        assertTrue(finalized.pts03.finalized)
        assertEquals(final, finalized.pts03.joinStatistics)

        val again = finalized.reducePts03Finalized(5L, final.copy(framesPendingAtStopCount = 2))
        assertEquals(1L, again.pts03.joinStatistics.framesPendingAtStopCount, "a second finalize never re-counts")
        assertEquals(final, again.reducePts03JoinStatistics(5L, SkyJoinStatistics.EMPTY).pts03.joinStatistics)
        val detection = FrameContentDetectionResult.InsufficientOrAmbiguousGrid("none", 0)
        assertEquals(
            0L,
            again
                .reduceFrame(
                    5L,
                    Pts03Fixtures.frameMetadata(9L),
                    detection,
                    0L,
                    Pts03Fixtures.captureResult(9L),
                ).pts03.totalFrameRecords,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Evidence captures
    // -----------------------------------------------------------------------------------------

    private fun boundExplicit(): FrameContentExperimentSessionState =
        initialFrameContentExperimentSessionState(attemptId = 1L, physicalCameraId = "3")
            .reducePts03Bound(1L, "0", Pts03Fixtures.characteristicsSet(), Pts03StreamConfiguration(null, null))

    @Test
    fun `evidence captures refuse a snapshot without a joined CaptureResult and from another attempt`() {
        val state = boundExplicit()
        assertSame(state.pts03, state.pts03.withEvidenceCapture(Pts03Fixtures.snapshot(captureResult = null)))
        assertSame(state, state.reducePts03AddEvidence(1L, Pts03Fixtures.snapshot(attemptId = 2L)))

        val capture =
            state
                .reducePts03AddEvidence(1L, Pts03Fixtures.snapshot())
                .pts03.evidenceCaptures
                .single()
        assertEquals(Pts03EvidenceOutcome.UNRESOLVED, capture.domainEvidence.outcome)
        assertEquals("3", capture.domainEvidence.producingPhysicalCameraId)
        assertTrue(capture.residualPoints.isNotEmpty())
        assertEquals(Pts03EffectiveDistortionMode.Fast, capture.effectiveDistortionMode)
    }

    @Test
    fun `A2 evidence without a physical result keeps Group C mode unresolved instead of using the logical mode`() {
        val logicalOnly = Pts03Fixtures.captureResult(activePhysicalId = "2", distortionMode = 0)
        val capture =
            boundExplicit()
                .reducePts03AddEvidence(
                    1L,
                    Pts03Fixtures.snapshot(captureResult = logicalOnly),
                ).pts03.evidenceCaptures
                .single()
        assertEquals(Pts03EffectiveDistortionMode.PhysicalResultUnavailable, capture.effectiveDistortionMode)
        assertEquals("3", capture.attribution.producingPhysicalCameraId)
        assertEquals("2", capture.domainEvidence.logicalActivePhysicalCameraId)
    }

    @Test
    fun `a frozen NORMAL_INDOOR frame keeps its label when the session label changes before Add Evidence`() {
        // Frame arrives while NORMAL_INDOOR; the snapshot freezes that label with the frame.
        var state = boundExplicit().reducePts03Lighting(1L, Pts03LightingLabel.NORMAL_INDOOR)
        val frozen = Pts03Fixtures.snapshot(lightingAtCapture = state.pts03.lighting)
        // Operator switches to LOW_LIGHT_INDOOR, then presses Add Evidence on the frozen snapshot.
        state = state.reducePts03Lighting(1L, Pts03LightingLabel.LOW_LIGHT_INDOOR).reducePts03AddEvidence(1L, frozen)

        assertEquals(
            Pts03LightingLabel.NORMAL_INDOOR,
            state.pts03.evidenceCaptures
                .single()
                .lighting,
        )
        assertEquals(Pts03LightingLabel.LOW_LIGHT_INDOOR, state.pts03.lighting)
    }

    @Test
    fun `the snapshot built by the reducers carries the lighting active when its frame arrived`() {
        var state =
            initialFrameContentExperimentSessionState(
                attemptId = 1L,
                physicalCameraId = "3",
            ).reducePts03Lighting(1L, Pts03LightingLabel.NIGHT_SKY)
        val detection = FrameContentDetectionResult.InsufficientOrAmbiguousGrid("none", 0)
        state = state.reduceFrame(1L, Pts03Fixtures.frameMetadata(7L), detection, 0L, Pts03Fixtures.captureResult(7L))
        state = state.reducePts03Lighting(1L, Pts03LightingLabel.NORMAL_INDOOR)
        assertEquals(Pts03LightingLabel.NIGHT_SKY, state.latestFrameLighting)
    }

    @Test
    fun `distortion chains group by producing camera and effective mode`() {
        var state = boundExplicit()
        state = state.reducePts03AddEvidence(1L, Pts03Fixtures.snapshot())
        state =
            state.reducePts03AddEvidence(
                1L,
                Pts03Fixtures.snapshot(
                    captureResult = Pts03Fixtures.captureResult(distortionMode = 0, physicalIds = listOf("3")),
                ),
            )

        val chains = state.pts03.distortionChains()
        assertEquals(
            listOf("3" to listOf("FAST"), "3" to listOf("OFF")),
            chains.map { it.cameraId to it.effectiveModes },
        )
        assertTrue(
            chains.all { it.residual.verdict == Pts03ResidualDistortionVerdict.UNRESOLVED_INSUFFICIENT_EVIDENCE },
        )
        assertTrue(chains.none { it.finalPixelDomainEstablished })
    }
}
