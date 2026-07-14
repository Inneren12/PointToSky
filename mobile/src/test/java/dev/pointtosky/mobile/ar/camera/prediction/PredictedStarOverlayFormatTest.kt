package dev.pointtosky.mobile.ar.camera.prediction

import dev.pointtosky.core.astro.projection.camera.prediction.IntrinsicsMappingUnavailableReason
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionSummary
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticCategory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JVM tests for [buildPredictedStarOverlayDiagnosticText] (task §9): bounded, deterministic, multi-line status text. */
class PredictedStarOverlayFormatTest {
    private fun sessionModeMetadata() =
        PredictedStarOverlayMetadata(
            inputCount = 5,
            visibleCount = 2,
            frameTimestampNanos = 1_000L,
            rotationTimestampNanos = 990L,
            pairDeltaNanos = 10L,
            frameRotationDegrees = 90,
            intrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
            sessionIntrinsicsSource = "LEGACY_FALLBACK",
            sessionIntrinsicsReference = "AnalysisBuffer(1000x500)",
            projectionIntrinsicsSource = "LEGACY_FALLBACK",
            projectionIntrinsicsReference = "AnalysisBuffer(1000x500)",
            magneticDeclinationDeg = 6.5,
        )

    private fun readyState(metadata: PredictedStarOverlayMetadata) =
        PredictedStarOverlayState.Ready.of(
            points =
                listOf(
                    PredictedStarOverlayPoint(catalogIndex = 1, magnitude = 2.0, displayX = 10.0, displayY = 20.0),
                    PredictedStarOverlayPoint(catalogIndex = 2, magnitude = 3.0, displayX = 30.0, displayY = 40.0),
                ),
            summary =
                StarPredictionSummary(
                    inputCount = 5,
                    behindCameraCount = 1,
                    outsideImageCount = 1,
                    insideImageOutsideViewportCount = 1,
                    visibleInViewportCount = 2,
                ),
            metadata = metadata,
        )

    @Test
    fun `Disabled state renders a disabled status line`() {
        val text = buildPredictedStarOverlayDiagnosticText(PredictedStarOverlayState.Disabled)
        assertTrue(text.contains("status: disabled"))
    }

    @Test
    fun `Waiting state renders its category, not a generic loading line`() {
        val text =
            buildPredictedStarOverlayDiagnosticText(
                PredictedStarOverlayState.Waiting(
                    PredictedStarOverlayWaitingReason.GeometryNotReady(CameraGeometryDiagnosticCategory.MISSING_FRAME),
                ),
            )
        assertTrue(text.contains("MISSING_FRAME"))
    }

    @Test
    fun `Unavailable state renders the categorized reason`() {
        val text =
            buildPredictedStarOverlayDiagnosticText(
                PredictedStarOverlayState.Unavailable(IntrinsicsMappingUnavailableReason.ANALYSIS_BUFFER_DIMENSIONS_MISMATCH),
            )
        assertTrue(text.contains("ANALYSIS_BUFFER_DIMENSIONS_MISMATCH"))
    }

    @Test
    fun `Ready state renders every required panel field, never one line per star`() {
        val state = readyState(sessionModeMetadata())

        val text = buildPredictedStarOverlayDiagnosticText(state)

        assertTrue(text.contains("input stars: 5"))
        assertTrue(text.contains("visible: 2"))
        assertTrue(text.contains("behind camera: 1"))
        assertTrue(text.contains("outside image: 1"))
        assertTrue(text.contains("inside image, outside viewport: 1"))
        assertTrue(text.contains("1000"))
        assertTrue(text.contains("990"))
        assertTrue(text.contains("90"))
        assertTrue(text.contains("LEGACY_FALLBACK"))
        assertTrue(text.contains("AnalysisBuffer(1000x500)"))
        assertTrue(text.contains("6.5"))
        // Never one line per star: the number of lines must not scale with points.size.
        assertFalse(text.lines().size > 15)
    }

    @Test
    fun `session intrinsics mode renders a single intrinsics line, labeled session`() {
        val text = buildPredictedStarOverlayDiagnosticText(readyState(sessionModeMetadata()))

        assertTrue(text.contains("intrinsics mode: session"))
        assertFalse(text.contains("session intrinsics:"))
        assertFalse(text.contains("projection intrinsics:"))
    }

    @Test
    fun `diagnostic fallback mode renders both the session and projection intrinsics lines, never labeled calibrated`() {
        val metadata =
            sessionModeMetadata().copy(
                intrinsicsMode = PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK,
                sessionIntrinsicsSource = "CAMERA_CHARACTERISTICS",
                sessionIntrinsicsReference = "PhysicalSensor",
                projectionIntrinsicsSource = "LEGACY_FALLBACK",
                projectionIntrinsicsReference = "AnalysisBuffer(1920x1080)",
            )

        val text = buildPredictedStarOverlayDiagnosticText(readyState(metadata))

        assertTrue(text.contains("intrinsics mode: diagnostic fallback"))
        assertTrue(text.contains("session intrinsics: CAMERA_CHARACTERISTICS / PhysicalSensor"))
        assertTrue(text.contains("projection intrinsics: LEGACY_FALLBACK / AnalysisBuffer(1920x1080)"))
        assertFalse(text.contains("calibrated", ignoreCase = true))
        assertFalse(text.contains("resolved physical", ignoreCase = true))
    }
}
