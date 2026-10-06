package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** PTS-03 session accumulation: Group A identity over every matched frame, bounded raw records, attempt isolation. */
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

    private fun Pts03TruthSessionState.feed(
        timestamp: Long,
        activeId: String?,
    ): Pts03TruthSessionState =
        withMatchedFrame(
            Pts03Fixtures.frameMetadata(timestamp),
            Pts03Fixtures.captureResult(timestampNanos = timestamp, activePhysicalId = activeId),
        )

    @Test
    fun `the logical candidate is an A1 session with no requested physical ID and a stable session ID`() {
        val s = logicalSession()
        assertEquals(Pts03SessionClass.LOGICAL_UNPINNED, s.sessionClass)
        assertNull(s.requestedPhysicalCameraId)
        assertEquals("pts03-1700000000000-a3-logical", s.sessionId)
        assertEquals(
            Pts03ExplicitIdentityConfirmation.NOT_APPLICABLE_LOGICAL_SESSION,
            confirmPts03ExplicitIdentity(s.sessionClass, null, s.identity),
        )
    }

    @Test
    fun `identity summary counts every frame, nulls and transitions with timestamps and lighting`() {
        var s = logicalSession()
        s = s.feed(10, "2").feed(20, "2")
        s =
            s
                .copy(lighting = Pts03LightingLabel.LOW_LIGHT_INDOOR)
                .feed(30, "3")
                .feed(40, null)
                .feed(50, "3")

        val id = s.identity
        assertEquals(5L, id.matchedFrameCount)
        assertEquals(mapOf("2" to 2L, "3" to 2L, PTS03_ACTIVE_ID_NOT_REPORTED_KEY to 1L), id.framesByActivePhysicalId)
        assertEquals(1L, id.framesWithNullActivePhysicalId)
        assertEquals(3L, id.transitionCount)
        assertEquals(listOf(30L, 40L, 50L), id.transitions.map { it.sensorTimestampNanos })
        assertEquals("2", id.transitions[0].fromActivePhysicalCameraId)
        assertEquals("3", id.transitions[0].toActivePhysicalCameraId)
        assertEquals(Pts03LightingLabel.LOW_LIGHT_INDOOR, id.transitions[0].lighting)
        assertEquals(mapOf("2" to 2L), id.framesByLightingAndActivePhysicalId[Pts03LightingLabel.NORMAL_INDOOR])
        assertEquals(listOf(6.9f), id.focalLengthsMmByActivePhysicalId["3"])
        assertEquals(1, id.intrinsicsByActivePhysicalId["2"]!!.size)
    }

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
        assertEquals(1L, s.headRecords.first().sensorTimestampNanos)
        assertEquals(total.toLong(), s.tailRecords.last().sensorTimestampNanos)
    }

    @Test
    fun `explicit identity confirmation distinguishes confirmed, contradicted, partial and unreported`() {
        fun summary(vararg ids: String?): Pts03IdentitySummary {
            var s = logicalSession()
            ids.forEachIndexed { i, id -> s = s.feed(i.toLong() + 1, id) }
            return s.identity
        }
        val c = Pts03SessionClass.EXPLICIT_PHYSICAL
        assertEquals(
            Pts03ExplicitIdentityConfirmation.NO_MATCHED_FRAMES,
            confirmPts03ExplicitIdentity(c, "3", Pts03IdentitySummary()),
        )
        assertEquals(
            Pts03ExplicitIdentityConfirmation.CONFIRMED_BY_ACTIVE_PHYSICAL_ID,
            confirmPts03ExplicitIdentity(c, "3", summary("3", "3")),
        )
        assertEquals(
            Pts03ExplicitIdentityConfirmation.CONTRADICTED_BY_ACTIVE_PHYSICAL_ID,
            confirmPts03ExplicitIdentity(c, "3", summary("3", "2")),
        )
        assertEquals(
            Pts03ExplicitIdentityConfirmation.PARTIALLY_CONFIRMED_SOME_FRAMES_UNREPORTED,
            confirmPts03ExplicitIdentity(c, "3", summary("3", null)),
        )
        assertEquals(
            Pts03ExplicitIdentityConfirmation.ACTIVE_PHYSICAL_ID_NOT_REPORTED,
            confirmPts03ExplicitIdentity(c, "3", summary(null, null)),
        )
    }

    @Test
    fun `frame-content reducers keep the frame and its CaptureResult together and ignore other attempts`() {
        val state = initialFrameContentExperimentSessionState(attemptId = 5L, physicalCameraId = "3")
        val result = Pts03Fixtures.captureResult(timestampNanos = 42L)
        val detection = FrameContentDetectionResult.InsufficientOrAmbiguousGrid("none", 0)

        val next = state.reduceFrame(5L, Pts03Fixtures.frameMetadata(42L), detection, 0L, result)
        assertSame(result, next.latestCaptureResult)
        assertEquals(1L, next.pts03.totalFrameRecords)

        // A result whose timestamp is not this frame's is never attached to it.
        val mismatched = state.reduceFrame(5L, Pts03Fixtures.frameMetadata(43L), detection, 0L, result)
        assertNull(mismatched.latestCaptureResult)
        assertEquals(0L, mismatched.pts03.totalFrameRecords)

        // A late callback from another attempt changes nothing.
        assertSame(state, state.reduceFrame(4L, Pts03Fixtures.frameMetadata(42L), detection, 0L, result))
        assertSame(state, state.reducePts03JoinStatistics(4L, SkyJoinStatistics.EMPTY.copy(analysisFrameCount = 9)))
    }

    @Test
    fun `evidence captures refuse a snapshot without a joined CaptureResult and from another attempt`() {
        val state =
            initialFrameContentExperimentSessionState(attemptId = 1L, physicalCameraId = "3")
                .reducePts03Bound(1L, "0", Pts03Fixtures.characteristicsSet(), Pts03StreamConfiguration(null, null))

        assertSame(state.pts03, state.pts03.withEvidenceCapture(Pts03Fixtures.snapshot(captureResult = null)))
        assertSame(state, state.reducePts03AddEvidence(1L, Pts03Fixtures.snapshot(attemptId = 2L)))

        val added = state.reducePts03AddEvidence(1L, Pts03Fixtures.snapshot())
        val capture = added.pts03.evidenceCaptures.single()
        assertEquals(Pts03EvidenceOutcome.UNRESOLVED, capture.domainEvidence.outcome)
        assertTrue(capture.residualPoints.isNotEmpty())
        assertEquals(Pts03EffectiveDistortionMode.Fast, capture.effectiveDistortionMode)
    }

    @Test
    fun `distortion chains group by camera and effective mode and stay unresolved for one placement`() {
        var state =
            initialFrameContentExperimentSessionState(attemptId = 1L, physicalCameraId = "3")
                .reducePts03Bound(1L, "0", Pts03Fixtures.characteristicsSet(), Pts03StreamConfiguration(null, null))
        state = state.reducePts03AddEvidence(1L, Pts03Fixtures.snapshot())
        state =
            state.reducePts03AddEvidence(
                1L,
                Pts03Fixtures.snapshot(captureResult = Pts03Fixtures.captureResult(distortionMode = 0)),
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
