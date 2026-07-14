package dev.pointtosky.mobile.ar.camera.prediction

import dev.pointtosky.core.astro.projection.camera.prediction.IntrinsicsMappingUnavailableReason
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionSummary
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticCategory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** JVM tests for [buildPredictedStarOverlayDiagnosticText] (task §9): bounded, deterministic, multi-line status text. */
class PredictedStarOverlayFormatTest {
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
        val state =
            PredictedStarOverlayState.Ready(
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
                metadata =
                    PredictedStarOverlayMetadata(
                        inputCount = 5,
                        visibleCount = 2,
                        frameTimestampNanos = 1_000L,
                        rotationTimestampNanos = 990L,
                        pairDeltaNanos = 10L,
                        frameRotationDegrees = 90,
                        intrinsicsSource = "LEGACY_FALLBACK",
                        intrinsicsReference = "AnalysisBuffer(1000x500)",
                        magneticDeclinationDeg = 6.5,
                    ),
            )

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
}
