package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.resolutionselector.AspectRatioStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `internalDebug`-only pure JVM tests for the deliberate `ImageAnalysis` resolution-candidate
 * selection (task §11) — candidates come only from device-declared sizes, never invented, and each
 * carries the [AnalysisResolutionFamily] of the band that selected it (P1 family fix: the family is
 * assigned once, at selection, and never re-inferred from exact integer ratios).
 */
class AnalysisResolutionCandidatesTest {
    @Test
    fun `parses WxH strings and skips malformed entries`() {
        val parsed = parseAnalysisResolutions(listOf("640x480", "1280x720", "bogus", "0x480", "640x", "12x34x56"))
        assertEquals(listOf(AnalysisResolutionSize(640, 480), AnalysisResolutionSize(1280, 720)), parsed)
    }

    @Test
    fun `selects the near-4-3 size closest to 640x480 and the near-16-9 size closest to 1280x720 with their families`() {
        val supported =
            parseAnalysisResolutions(
                listOf("4080x3072", "1920x1080", "1280x720", "800x600", "640x480", "352x288", "320x240"),
            )
        val candidates = selectAnalysisResolutionCandidates(supported)
        assertEquals(
            listOf(
                AnalysisResolutionCandidate(640, 480, AnalysisResolutionFamily.NEAR_4_3),
                AnalysisResolutionCandidate(1280, 720, AnalysisResolutionFamily.NEAR_16_9),
            ),
            candidates,
        )
    }

    @Test
    fun `an exact 1280x720 candidate is NEAR_16_9`() {
        val candidates = selectAnalysisResolutionCandidates(parseAnalysisResolutions(listOf("1280x720")))
        assertEquals(listOf(AnalysisResolutionCandidate(1280, 720, AnalysisResolutionFamily.NEAR_16_9)), candidates)
    }

    @Test
    fun `a non-exact in-band 848x480 candidate is still NEAR_16_9`() {
        // 848/480 ≈ 1.7667 — inside the near-16:9 band but failing width*9 == height*16
        // (848*9 = 7632 ≠ 480*16 = 7680). The family must come from the band, never that check.
        val candidates = selectAnalysisResolutionCandidates(parseAnalysisResolutions(listOf("848x480")))
        assertEquals(listOf(AnalysisResolutionCandidate(848, 480, AnalysisResolutionFamily.NEAR_16_9)), candidates)
    }

    @Test
    fun `a non-exact near-4-3 candidate is NEAR_4_3`() {
        // 720/540 ≈ 1.3333 exactly 4:3; 704x528 also 4:3; use 700x525 (=4:3) … pick a genuinely
        // non-exact one: 640x492 ≈ 1.3008, inside the 1.28–1.40 band, not an exact 4:3 ratio.
        val candidates = selectAnalysisResolutionCandidates(parseAnalysisResolutions(listOf("640x492")))
        assertEquals(listOf(AnalysisResolutionCandidate(640, 492, AnalysisResolutionFamily.NEAR_4_3)), candidates)
    }

    @Test
    fun `a device without the canonical sizes still yields in-band candidates, never invented ones`() {
        val supported = parseAnalysisResolutions(listOf("720x540", "960x540"))
        val candidates = selectAnalysisResolutionCandidates(supported)
        assertEquals(
            listOf(
                AnalysisResolutionCandidate(720, 540, AnalysisResolutionFamily.NEAR_4_3),
                AnalysisResolutionCandidate(960, 540, AnalysisResolutionFamily.NEAR_16_9),
            ),
            candidates,
        )
    }

    @Test
    fun `a band with no supported size is simply absent`() {
        val only43 = selectAnalysisResolutionCandidates(parseAnalysisResolutions(listOf("640x480")))
        assertEquals(listOf(AnalysisResolutionCandidate(640, 480, AnalysisResolutionFamily.NEAR_4_3)), only43)
        assertTrue(selectAnalysisResolutionCandidates(emptyList()).isEmpty())
    }

    @Test
    fun `the family-to-strategy decision maps NEAR_16_9 to the 16-9 strategy and NEAR_4_3 to the 4-3 strategy`() {
        // Pure function, no CameraX binding — this is the exact decision CameraPreview applies, so a
        // NEAR_16_9 request (even a non-exact 848x480) always binds with the 16:9 strategy.
        assertSame(
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
            aspectRatioStrategyFor(AnalysisResolutionFamily.NEAR_16_9),
        )
        assertSame(
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY,
            aspectRatioStrategyFor(AnalysisResolutionFamily.NEAR_4_3),
        )
    }

    @Test
    fun `toRequest carries the dimensions and the selecting band's family unchanged`() {
        val request = AnalysisResolutionCandidate(848, 480, AnalysisResolutionFamily.NEAR_16_9).toRequest()
        assertEquals(AnalysisResolutionRequest(848, 480, AnalysisResolutionFamily.NEAR_16_9), request)
    }
}
