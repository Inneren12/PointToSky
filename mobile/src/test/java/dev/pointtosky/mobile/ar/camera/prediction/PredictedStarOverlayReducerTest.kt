package dev.pointtosky.mobile.ar.camera.prediction

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.FrameRotationPairingResult
import dev.pointtosky.core.astro.projection.camera.GeometryRejectionReason
import dev.pointtosky.core.astro.projection.camera.IntrinsicsUnavailableReason
import dev.pointtosky.core.astro.projection.camera.PixelPoint
import dev.pointtosky.core.astro.projection.camera.RotationUnavailableReason
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import dev.pointtosky.core.astro.projection.camera.TimestampSyncConfig
import dev.pointtosky.core.astro.projection.camera.createCameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.pairFrameToNearestRotation
import dev.pointtosky.core.astro.projection.camera.prediction.CameraDirectionSnapshot
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.IntrinsicsMappingUnavailableReason
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarProjection
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.prediction.StarProjectionContext
import dev.pointtosky.core.astro.projection.camera.prediction.projectStars
import dev.pointtosky.core.astro.projection.camera.prediction.summarizeStarPredictions
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticCategory
import dev.pointtosky.mobile.ar.camera.isDiagnosticsEnabled
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * JVM tests for the CAM-2b integration reducer (task §12): gate truth table, geometry-not-ready
 * mapping, CAM-2a-unavailable mapping, ready filtering/coordinate-pass-through/order-preservation,
 * declination ownership, and disposal/session-reset statelessness.
 */
class PredictedStarOverlayReducerTest {
    private val validStars = listOf(EquatorialStarDirection.of(catalogIndex = 1, rightAscensionRad = 0.1, declinationRad = 0.2, magnitude = 3.0))

    private fun readyGeometry(
        intrinsics: CameraIntrinsicsResolution,
        frameTimestampNanos: Long = 1_000L,
        rotationTimestampNanos: Long = 1_000L,
        bufferWidthPx: Int = 1000,
        bufferHeightPx: Int = 500,
        viewportWidthPx: Int = 1000,
        viewportHeightPx: Int = 500,
        rotationMatrix: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        rotationDegrees: Int = 0,
    ): CameraSessionGeometryResult.Ready {
        val frame =
            CameraFrameMetadata(
                timestampNanos = frameTimestampNanos,
                bufferWidthPx = bufferWidthPx,
                bufferHeightPx = bufferHeightPx,
                rotationDegrees = rotationDegrees,
            )
        val sample = TimedRotationSample(timestampNanos = rotationTimestampNanos, rotationMatrix = rotationMatrix)
        val pairingResult =
            assertIs<FrameRotationPairingResult.Paired>(
                pairFrameToNearestRotation(
                    frame = frame,
                    samples = listOf(sample),
                    maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                    clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
                ),
            )
        val result =
            createCameraSessionGeometry(
                frame = frame,
                pairingResult = pairingResult,
                intrinsicsResolution = intrinsics,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
            )
        return assertIs<CameraSessionGeometryResult.Ready>(result)
    }

    private fun exactMatchIntrinsics(
        widthPx: Int = 1000,
        heightPx: Int = 500,
    ): CameraIntrinsicsResolution.LegacyFallback =
        CameraIntrinsicsResolution.LegacyFallback(legacyFallbackCameraIntrinsics(widthPx, heightPx), reason = "test")

    private fun physicalSensorIntrinsics(): CameraIntrinsicsResolution.Resolved =
        CameraIntrinsicsResolution.Resolved(
            CameraIntrinsics(
                horizontalFovDeg = 60.0,
                verticalFovDeg = 45.0,
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                principalPointXPx = null,
                principalPointYPx = null,
                source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
                reference = CameraIntrinsicsReference.PhysicalSensor,
            ),
        )

    private fun reduce(
        gateEnabled: Boolean = true,
        geometryResult: CameraSessionGeometryResult = CameraSessionGeometryResult.MissingFrame(null),
        observerLatitudeDeg: Double? = 45.0,
        observerLongitudeDeg: Double? = 10.0,
        utcEpochMillis: Long? = 0L,
        magneticDeclinationDeg: Double? = 5.0,
        stars: List<EquatorialStarDirection> = validStars,
        intrinsicsMode: PredictedStarOverlayIntrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
    ): PredictedStarOverlayState =
        reducePredictedStarOverlayState(
            gateEnabled = gateEnabled,
            geometryResult = geometryResult,
            observerLatitudeDeg = observerLatitudeDeg,
            observerLongitudeDeg = observerLongitudeDeg,
            utcEpochMillis = utcEpochMillis,
            magneticDeclinationDeg = magneticDeclinationDeg,
            stars = stars,
            intrinsicsMode = intrinsicsMode,
        )

    // --- A. Gate behavior --------------------------------------------------------------------------

    @Test
    fun `gate truth table - debug plus internal enables, every other combination disables`() {
        val expectedEnabled =
            mapOf(
                (true to "internal") to true,
                (true to "public") to false,
                (false to "internal") to false,
                (false to "public") to false,
            )

        expectedEnabled.forEach { (combo, expected) ->
            val (debug, flavor) = combo
            val gateEnabled = isDiagnosticsEnabled(debug = debug, flavor = flavor)
            assertEquals(expected, gateEnabled, "debug=$debug flavor=$flavor")

            val result = reduce(gateEnabled = gateEnabled)
            if (expected) {
                assertTrue(result !is PredictedStarOverlayState.Disabled, "debug=$debug flavor=$flavor")
            } else {
                assertEquals(PredictedStarOverlayState.Disabled, result, "debug=$debug flavor=$flavor")
            }
        }
    }

    // --- B. Geometry not ready ----------------------------------------------------------------------

    @Test
    fun `MissingFrame maps to Waiting GeometryNotReady MISSING_FRAME`() {
        val result = reduce(geometryResult = CameraSessionGeometryResult.MissingFrame(viewportSize = null))
        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.MISSING_FRAME)),
            result,
        )
    }

    @Test
    fun `InvalidViewport maps to Waiting GeometryNotReady INVALID_VIEWPORT`() {
        val result = reduce(geometryResult = CameraSessionGeometryResult.InvalidViewport(0, 0))
        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.INVALID_VIEWPORT)),
            result,
        )
    }

    @Test
    fun `IntrinsicsUnavailable PENDING maps to Waiting GeometryNotReady INTRINSICS_PENDING`() {
        val result =
            reduce(geometryResult = CameraSessionGeometryResult.IntrinsicsUnavailable(IntrinsicsUnavailableReason.PENDING))
        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.INTRINSICS_PENDING)),
            result,
        )
    }

    @Test
    fun `RotationUnavailable NO_SAMPLES maps to Waiting GeometryNotReady ROTATION_NO_SAMPLES`() {
        val result =
            reduce(
                geometryResult =
                    CameraSessionGeometryResult.RotationUnavailable(
                        RotationUnavailableReason.NO_SAMPLES,
                        FrameRotationPairingResult.NoSamples(frameTimestampNanos = 1L),
                    ),
            )
        assertEquals(
            PredictedStarOverlayState.Waiting(
                PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.ROTATION_NO_SAMPLES),
            ),
            result,
        )
    }

    @Test
    fun `GeometryRejected PAIRING_FRAME_MISMATCH maps to Waiting GeometryNotReady PAIRING_FRAME_MISMATCH`() {
        val result =
            reduce(geometryResult = CameraSessionGeometryResult.GeometryRejected(GeometryRejectionReason.PAIRING_FRAME_MISMATCH))
        assertEquals(
            PredictedStarOverlayState.Waiting(
                PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.PAIRING_FRAME_MISMATCH),
            ),
            result,
        )
    }

    @Test
    fun `Disposed maps to Waiting GeometryNotReady DISPOSED`() {
        val result = reduce(geometryResult = CameraSessionGeometryResult.Disposed)
        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.DISPOSED)),
            result,
        )
    }

    // --- Other prerequisite waiting reasons ----------------------------------------------------------

    @Test
    fun `a null observer latitude reports ObserverLocationUnavailable`() {
        val geometry = readyGeometry(exactMatchIntrinsics())
        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.ObserverLocationUnavailable),
            reduce(geometryResult = geometry, observerLatitudeDeg = null),
        )
    }

    @Test
    fun `a non-finite observer longitude reports ObserverLocationUnavailable`() {
        val geometry = readyGeometry(exactMatchIntrinsics())
        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.ObserverLocationUnavailable),
            reduce(geometryResult = geometry, observerLongitudeDeg = Double.NaN),
        )
    }

    @Test
    fun `a null observation instant reports ObservationTimeUnavailable`() {
        val geometry = readyGeometry(exactMatchIntrinsics())
        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.ObservationTimeUnavailable),
            reduce(geometryResult = geometry, utcEpochMillis = null),
        )
    }

    @Test
    fun `a null declination never silently defaults to zero - reports MagneticDeclinationUnavailable`() {
        val geometry = readyGeometry(exactMatchIntrinsics())
        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.MagneticDeclinationUnavailable),
            reduce(geometryResult = geometry, magneticDeclinationDeg = null),
        )
    }

    @Test
    fun `a non-finite declination reports MagneticDeclinationUnavailable`() {
        val geometry = readyGeometry(exactMatchIntrinsics())
        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.MagneticDeclinationUnavailable),
            reduce(geometryResult = geometry, magneticDeclinationDeg = Double.POSITIVE_INFINITY),
        )
    }

    @Test
    fun `an empty bounded star subset reports NoStarsSelected`() {
        val geometry = readyGeometry(exactMatchIntrinsics())
        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.NoStarsSelected),
            reduce(geometryResult = geometry, stars = emptyList()),
        )
    }

    // --- C. CAM-2a unavailable ------------------------------------------------------------------------

    @Test
    fun `a physical-sensor-referenced intrinsics reports Unavailable PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED`() {
        val physicalSensor =
            CameraIntrinsicsResolution.Resolved(
                CameraIntrinsics(
                    horizontalFovDeg = 90.0,
                    verticalFovDeg = 90.0,
                    focalLengthMm = 4.0,
                    sensorWidthMm = 5.0,
                    sensorHeightMm = 4.0,
                    principalPointXPx = null,
                    principalPointYPx = null,
                    source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
                    reference = CameraIntrinsicsReference.PhysicalSensor,
                ),
            )

        val result = reduce(geometryResult = readyGeometry(physicalSensor))

        assertEquals(
            PredictedStarOverlayState.Unavailable(IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED),
            result,
        )
    }

    @Test
    fun `an unspecified-reference intrinsics reports Unavailable ANALYSIS_BUFFER_REFERENCE_MISSING`() {
        val unspecified =
            CameraIntrinsicsResolution.LegacyFallback(
                CameraIntrinsics(
                    horizontalFovDeg = 90.0,
                    verticalFovDeg = 90.0,
                    focalLengthMm = null,
                    sensorWidthMm = null,
                    sensorHeightMm = null,
                    principalPointXPx = null,
                    principalPointYPx = null,
                    source = CameraIntrinsicsSource.LEGACY_FALLBACK,
                    reference = CameraIntrinsicsReference.Unspecified,
                ),
                reason = "test",
            )

        val result = reduce(geometryResult = readyGeometry(unspecified))

        assertEquals(
            PredictedStarOverlayState.Unavailable(IntrinsicsMappingUnavailableReason.ANALYSIS_BUFFER_REFERENCE_MISSING),
            result,
        )
    }

    @Test
    fun `a mismatched analysis-buffer reference reports Unavailable ANALYSIS_BUFFER_DIMENSIONS_MISMATCH`() {
        val mismatched =
            CameraIntrinsicsResolution.LegacyFallback(
                CameraIntrinsics(
                    horizontalFovDeg = 90.0,
                    verticalFovDeg = 90.0,
                    focalLengthMm = null,
                    sensorWidthMm = null,
                    sensorHeightMm = null,
                    principalPointXPx = null,
                    principalPointYPx = null,
                    source = CameraIntrinsicsSource.LEGACY_FALLBACK,
                    reference = CameraIntrinsicsReference.AnalysisBuffer(widthPx = 999, heightPx = 499),
                ),
                reason = "test",
            )

        // readyGeometry defaults to a 1000x500 buffer - deliberately different from the 999x499 reference above.
        val result = reduce(geometryResult = readyGeometry(mismatched))

        assertEquals(
            PredictedStarOverlayState.Unavailable(IntrinsicsMappingUnavailableReason.ANALYSIS_BUFFER_DIMENSIONS_MISMATCH),
            result,
        )
    }

    // --- D/E/F. Ready filtering, coordinate pass-through, order preservation (toOverlayPoints) -------

    private fun cameraDirection() = CameraDirectionSnapshot(cameraX = 0.0, cameraY = 0.0, cameraZ = 1.0, normalizedX = 0.0, normalizedY = 0.0)

    private fun projection(
        catalogIndex: Int,
        classification: PredictedStarClassification,
        displayX: Double = 0.0,
        displayY: Double = 0.0,
    ): PredictedStarProjection =
        if (classification == PredictedStarClassification.BEHIND_CAMERA) {
            PredictedStarProjection(
                catalogIndex = catalogIndex,
                magnitude = null,
                classification = classification,
                cameraDirection = null,
                imagePoint = null,
                displayPoint = null,
            )
        } else {
            PredictedStarProjection(
                catalogIndex = catalogIndex,
                magnitude = null,
                classification = classification,
                cameraDirection = cameraDirection(),
                imagePoint = PixelPoint(displayX, displayY),
                displayPoint = PixelPoint(displayX, displayY),
            )
        }

    @Test
    fun `only VISIBLE_IN_VIEWPORT projections become drawable points`() {
        val projections =
            listOf(
                projection(1, PredictedStarClassification.BEHIND_CAMERA),
                projection(2, PredictedStarClassification.OUTSIDE_IMAGE),
                projection(3, PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT),
                projection(4, PredictedStarClassification.VISIBLE_IN_VIEWPORT),
            )

        val points = toOverlayPoints(projections)

        assertEquals(listOf(4), points.map { it.catalogIndex })
    }

    @Test
    fun `a CAM-2a display point becomes an overlay point at exactly the same coordinates`() {
        val projections = listOf(projection(1, PredictedStarClassification.VISIBLE_IN_VIEWPORT, displayX = 123.25, displayY = 456.75))

        val points = toOverlayPoints(projections)

        assertEquals(123.25, points.single().displayX)
        assertEquals(456.75, points.single().displayY)
    }

    @Test
    fun `drawable points preserve CAM-2a input-result order`() {
        val projections =
            listOf(
                projection(10, PredictedStarClassification.VISIBLE_IN_VIEWPORT),
                projection(5, PredictedStarClassification.BEHIND_CAMERA),
                projection(7, PredictedStarClassification.VISIBLE_IN_VIEWPORT),
                projection(2, PredictedStarClassification.OUTSIDE_IMAGE),
                projection(3, PredictedStarClassification.VISIBLE_IN_VIEWPORT),
            )

        val points = toOverlayPoints(projections)

        assertEquals(listOf(10, 7, 3), points.map { it.catalogIndex })
    }

    @Test
    fun `summary counters reflect the full batch, not just the visible subset`() {
        val geometry = readyGeometry(exactMatchIntrinsics())
        // Two arbitrary catalog stars - whatever their real classification turns out to be, the
        // summary's inputCount must always equal the number of stars submitted.
        val twoStars =
            listOf(
                EquatorialStarDirection.of(catalogIndex = 1, rightAscensionRad = 0.0, declinationRad = 0.0),
                EquatorialStarDirection.of(catalogIndex = 2, rightAscensionRad = 3.0, declinationRad = -0.5),
            )

        val result = assertIs<PredictedStarOverlayState.Ready>(reduce(geometryResult = geometry, stars = twoStars))

        assertEquals(2, result.summary.inputCount)
        assertEquals(result.summary.visibleInViewportCount, result.points.size)
    }

    // --- H. Magnetic-declination ownership -------------------------------------------------------------

    @Test
    fun `the reducer passes the raw geometry rotation plus a non-zero context declination - never a pre-corrected matrix`() {
        val geometry = readyGeometry(exactMatchIntrinsics())
        val declinationDeg = 12.5

        val reduced = assertIs<PredictedStarOverlayState.Ready>(reduce(geometryResult = geometry, magneticDeclinationDeg = declinationDeg))

        // The independent reference computation below feeds CAM-2a's projectStars directly with the
        // exact same, unmodified geometry.geometry (the raw rotation matrix, never run through
        // correctedForTrueNorth) and a StarProjectionContext carrying the non-zero declination - the
        // one-and-only place a true-north correction is expected to happen (§4.6). If the reducer had
        // instead pre-corrected the matrix (double-correction), these two computations would diverge.
        val referenceContext =
            StarProjectionContext.of(
                latitudeRad = Math.toRadians(45.0),
                longitudeRad = Math.toRadians(10.0),
                utcEpochMillis = 0L,
                magneticDeclinationRad = Math.toRadians(declinationDeg),
            )
        val reference =
            assertIs<StarPredictionBatchResult.Ready>(
                projectStars(stars = validStars, context = referenceContext, geometry = geometry.geometry),
            )

        assertEquals(summarizeStarPredictions(reference.projections), reduced.summary)
        assertEquals(toOverlayPoints(reference.projections), reduced.points)
    }

    // --- Intrinsics mode (follow-up task §2/§3) --------------------------------------------------------

    @Test
    fun `session intrinsics mode with a PhysicalSensor session reference preserves PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED`() {
        val geometry = readyGeometry(physicalSensorIntrinsics())

        val result = reduce(geometryResult = geometry, intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS)

        assertEquals(
            PredictedStarOverlayState.Unavailable(IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED),
            result,
        )
    }

    @Test
    fun `diagnostic fallback mode with a PhysicalSensor session reference becomes Ready with an AnalysisBuffer projection reference`() {
        val geometry = readyGeometry(physicalSensorIntrinsics(), bufferWidthPx = 1000, bufferHeightPx = 500)

        val result =
            assertIs<PredictedStarOverlayState.Ready>(
                reduce(geometryResult = geometry, intrinsicsMode = PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK),
            )

        assertEquals(PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK, result.metadata.intrinsicsMode)
        assertEquals("AnalysisBuffer(1000x500)", result.metadata.projectionIntrinsicsReference)
        assertEquals("LEGACY_FALLBACK", result.metadata.projectionIntrinsicsSource)
        // The session-level fields still describe the real, unsubstituted session intrinsics.
        assertEquals("PhysicalSensor", result.metadata.sessionIntrinsicsReference)
        assertEquals("CAMERA_CHARACTERISTICS", result.metadata.sessionIntrinsicsSource)
    }

    @Test
    fun `diagnostic fallback projection never mutates the original session geometry`() {
        val geometry = readyGeometry(physicalSensorIntrinsics(), bufferWidthPx = 1000, bufferHeightPx = 500)

        reduce(geometryResult = geometry, intrinsicsMode = PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK)

        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, geometry.geometry.intrinsics.intrinsics.source)
        assertIs<CameraIntrinsicsReference.PhysicalSensor>(geometry.geometry.intrinsics.intrinsics.reference)
    }

    @Test
    fun `diagnostic fallback reference matches the exact current frame dimensions for several sizes`() {
        listOf(1000 to 500, 1920 to 1080, 1080 to 1920).forEach { (widthPx, heightPx) ->
            val geometry =
                readyGeometry(
                    physicalSensorIntrinsics(),
                    bufferWidthPx = widthPx,
                    bufferHeightPx = heightPx,
                    viewportWidthPx = widthPx,
                    viewportHeightPx = heightPx,
                )

            val result =
                assertIs<PredictedStarOverlayState.Ready>(
                    reduce(geometryResult = geometry, intrinsicsMode = PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK),
                )

            assertEquals("AnalysisBuffer(${widthPx}x$heightPx)", result.metadata.projectionIntrinsicsReference, "for ${widthPx}x$heightPx")
        }
    }

    @Test
    fun `the default intrinsics mode is SESSION_INTRINSICS`() {
        val geometry = readyGeometry(physicalSensorIntrinsics())

        // intrinsicsMode is deliberately omitted here, relying on reducePredictedStarOverlayState's
        // own default parameter value rather than this test file's reduce() helper default.
        val result =
            reducePredictedStarOverlayState(
                gateEnabled = true,
                geometryResult = geometry,
                observerLatitudeDeg = 45.0,
                observerLongitudeDeg = 10.0,
                utcEpochMillis = 0L,
                magneticDeclinationDeg = 5.0,
                stars = validStars,
            )

        assertEquals(
            PredictedStarOverlayState.Unavailable(IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED),
            result,
        )
    }

    @Test
    fun `panel text distinguishes session and diagnostic-fallback intrinsics modes and never calls the fallback calibrated`() {
        val sessionReady =
            assertIs<PredictedStarOverlayState.Ready>(
                reduce(geometryResult = readyGeometry(exactMatchIntrinsics()), intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS),
            )
        val sessionText = buildPredictedStarOverlayDiagnosticText(sessionReady)
        assertTrue(sessionText.contains("intrinsics mode: session"))

        val fallbackGeometry = readyGeometry(physicalSensorIntrinsics(), bufferWidthPx = 1000, bufferHeightPx = 500)
        val fallbackReady =
            assertIs<PredictedStarOverlayState.Ready>(
                reduce(geometryResult = fallbackGeometry, intrinsicsMode = PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK),
            )
        val fallbackText = buildPredictedStarOverlayDiagnosticText(fallbackReady)
        assertTrue(fallbackText.contains("intrinsics mode: diagnostic fallback"))
        assertTrue(fallbackText.contains("session intrinsics: CAMERA_CHARACTERISTICS / PhysicalSensor"))
        assertTrue(fallbackText.contains("projection intrinsics: LEGACY_FALLBACK / AnalysisBuffer(1000x500)"))
        assertFalse(fallbackText.contains("calibrated", ignoreCase = true))
        assertFalse(fallbackText.contains("resolved physical", ignoreCase = true))
    }

    // --- I. Disposal / session reset -----------------------------------------------------------------

    @Test
    fun `a disposed geometry after a prior Ready call reports Waiting, never stale points`() {
        val geometry = readyGeometry(exactMatchIntrinsics())
        val firstCall = reduce(geometryResult = geometry)
        assertIs<PredictedStarOverlayState.Ready>(firstCall)

        val afterDisposal = reduce(geometryResult = CameraSessionGeometryResult.Disposed)

        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.DISPOSED)),
            afterDisposal,
        )
    }

    @Test
    fun `a new session's first non-ready geometry after a prior session's Ready never echoes the previous points`() {
        val previousSessionGeometry = readyGeometry(exactMatchIntrinsics(), frameTimestampNanos = 1_000L, rotationTimestampNanos = 1_000L)
        val previous = reduce(geometryResult = previousSessionGeometry)
        assertIs<PredictedStarOverlayState.Ready>(previous)

        val newSessionFirstResult = reduce(geometryResult = CameraSessionGeometryResult.MissingFrame(viewportSize = null))

        assertEquals(
            PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.MISSING_FRAME)),
            newSessionFirstResult,
        )
        assertFalse(newSessionFirstResult is PredictedStarOverlayState.Ready)
    }
}
