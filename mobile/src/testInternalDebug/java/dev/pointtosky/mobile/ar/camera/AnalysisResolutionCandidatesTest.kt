package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `internalDebug`-only pure JVM tests for the deliberate `ImageAnalysis` resolution-candidate
 * selection (task §11) — candidates come only from device-declared sizes, never invented.
 */
class AnalysisResolutionCandidatesTest {
    @Test
    fun `parses WxH strings and skips malformed entries`() {
        val parsed = parseAnalysisResolutions(listOf("640x480", "1280x720", "bogus", "0x480", "640x", "12x34x56"))
        assertEquals(listOf(AnalysisResolutionCandidate(640, 480), AnalysisResolutionCandidate(1280, 720)), parsed)
    }

    @Test
    fun `selects the near-4-3 size closest to 640x480 and the near-16-9 size closest to 1280x720`() {
        val supported =
            parseAnalysisResolutions(
                listOf("4080x3072", "1920x1080", "1280x720", "800x600", "640x480", "352x288", "320x240"),
            )
        val candidates = selectAnalysisResolutionCandidates(supported)
        assertEquals(
            listOf(AnalysisResolutionCandidate(640, 480), AnalysisResolutionCandidate(1280, 720)),
            candidates,
        )
    }

    @Test
    fun `a device without the canonical sizes still yields in-band candidates, never invented ones`() {
        val supported = parseAnalysisResolutions(listOf("720x540", "960x540"))
        val candidates = selectAnalysisResolutionCandidates(supported)
        assertEquals(listOf(AnalysisResolutionCandidate(720, 540), AnalysisResolutionCandidate(960, 540)), candidates)
    }

    @Test
    fun `a band with no supported size is simply absent`() {
        val only43 = selectAnalysisResolutionCandidates(parseAnalysisResolutions(listOf("640x480")))
        assertEquals(listOf(AnalysisResolutionCandidate(640, 480)), only43)
        assertTrue(selectAnalysisResolutionCandidates(emptyList()).isEmpty())
    }
}
