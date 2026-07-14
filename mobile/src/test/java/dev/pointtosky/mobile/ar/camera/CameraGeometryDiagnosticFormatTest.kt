package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * JVM tests for CAM-1g's deterministic, non-locale-sensitive diagnostic text formatting (§5): fixed
 * decimal precision, explicit sign on the pair delta, a stable placeholder for missing fields, no
 * scientific notation, and no raw sealed-result `toString()`/matrix content in the built overlay
 * text.
 */
class CameraGeometryDiagnosticFormatTest {
    // --- Pair delta: sign and placeholder -------------------------------------------------------

    @Test
    fun `positive pair delta is formatted with an explicit plus sign and one decimal`() {
        assertEquals("+6.4 ms", formatPairDeltaMillis(6_400_000L))
    }

    @Test
    fun `negative pair delta keeps its minus sign`() {
        assertEquals("-6.4 ms", formatPairDeltaMillis(-6_400_000L))
    }

    @Test
    fun `zero pair delta is formatted with a plus sign`() {
        assertEquals("+0.0 ms", formatPairDeltaMillis(0L))
    }

    @Test
    fun `null pair delta shows the unavailable placeholder`() {
        assertEquals("unavailable", formatPairDeltaMillis(null))
    }

    // --- FOV, scale, offset, pixel size, rotation, crop -----------------------------------------

    @Test
    fun `FOV formats both axes with one decimal`() {
        assertEquals("64.2° x 42.8°", formatFovDeg(64.2, 42.8))
    }

    @Test
    fun `missing FOV shows the unavailable placeholder`() {
        assertEquals("unavailable", formatFovDeg(null, 42.8))
    }

    @Test
    fun `scale is formatted with three decimals`() {
        assertEquals("2.044", formatScale(2.0442))
    }

    @Test
    fun `missing scale shows the unavailable placeholder`() {
        assertEquals("unavailable", formatScale(null))
    }

    @Test
    fun `offset is formatted with two decimals for both axes`() {
        assertEquals("-885.10, 0.00", formatOffset(-885.1, 0.0))
    }

    @Test
    fun `pixel size formats width times height`() {
        assertEquals("1920×1080", formatPixelSize(1920, 1080))
    }

    @Test
    fun `missing pixel size shows the unavailable placeholder`() {
        assertEquals("unavailable", formatPixelSize(null, 1080))
    }

    @Test
    fun `rotation degrees formats with a degree sign`() {
        assertEquals("90°", formatRotationDegrees(90))
    }

    @Test
    fun `missing rotation shows the unavailable placeholder`() {
        assertEquals("unavailable", formatRotationDegrees(null))
    }

    @Test
    fun `crop rect formats as an ordered bracketed range`() {
        val snapshot = emptySnapshotFor(CameraGeometryDiagnosticCategory.READY_CALIBRATED)
            .copy(cropLeftPx = 0, cropTopPx = 0, cropRightPx = 1920, cropBottomPx = 1080)
        assertEquals("[0,0 — 1920,1080]", formatCropRect(snapshot))
    }

    @Test
    fun `missing crop rect shows the unavailable placeholder`() {
        val snapshot = emptySnapshotFor(CameraGeometryDiagnosticCategory.MISSING_FRAME)
        assertEquals("unavailable", formatCropRect(snapshot))
    }

    @Test
    fun `intrinsics source formats as the enum name`() {
        assertEquals("CAMERA_CHARACTERISTICS", formatIntrinsicsSource(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS))
    }

    @Test
    fun `missing intrinsics source shows the unavailable placeholder`() {
        assertEquals("unavailable", formatIntrinsicsSource(null))
    }

    // --- No scientific notation, no locale-sensitive comma decimals -----------------------------

    @Test
    fun `no formatted numeric field ever uses scientific notation`() {
        val text = formatOffset(-885.123456, 1_234_567.0)
        assertFalse(text.contains("E", ignoreCase = true))
    }

    @Test
    fun `pair delta never uses scientific notation for large values`() {
        val text = formatPairDeltaMillis(9_999_000_000_000L)
        assertFalse(text.contains("E", ignoreCase = true))
    }

    // --- Overlay text: no raw toString, no matrix content ---------------------------------------

    @Test
    fun `overlay text never contains the sealed result's raw class name or matrix fields`() {
        val snapshot =
            emptySnapshotFor(CameraGeometryDiagnosticCategory.READY_CALIBRATED).copy(
                quality = CameraGeometryQuality.CALIBRATED,
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                cropLeftPx = 0,
                cropTopPx = 0,
                cropRightPx = 1920,
                cropBottomPx = 1080,
                rotationDegrees = 90,
                viewportWidthPx = 1080,
                viewportHeightPx = 2208,
                pairDeltaNanos = 6_400_000L,
                intrinsicsSource = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
                horizontalFovDeg = 64.2,
                verticalFovDeg = 42.8,
                uniformScale = 2.044,
                displayOffsetX = -885.1,
                displayOffsetY = 0.0,
                centerProbe =
                    CameraGeometryCenterProbeSnapshot(
                        viewportCenterXPx = 540.0,
                        viewportCenterYPx = 1104.0,
                        imagePointXPx = 960.0,
                        imagePointYPx = 540.0,
                        roundTripErrorPx = 0.0,
                    ),
            )
        val text =
            buildCameraGeometryDiagnosticText(
                snapshot = snapshot,
                sessionId = 4,
                statusTransitionCount = 3,
                observedFrameCount = 120,
                readyBundleCount = 118,
            )

        assertFalse(text.contains("CameraSessionGeometryResult"))
        assertFalse(text.contains("CameraSessionGeometry("))
        assertFalse(text.contains("rotationMatrix"))
        assertFalse(text.contains("FloatArray"))
        assertTrue(text.contains("CAM geometry: READY_CALIBRATED"))
        assertTrue(text.contains("session: 4"))
        assertTrue(text.contains("round-trip: 0.000 px"))
    }

    // --- Counter-only changes must still change the built text (CAM-1g gate follow-up) -----------

    @Test
    fun `a frame-count-only change produces different overlay text even though the snapshot is unchanged`() {
        val snapshot = emptySnapshotFor(CameraGeometryDiagnosticCategory.INTRINSICS_PENDING)

        val textAtFrameOne =
            buildCameraGeometryDiagnosticText(
                snapshot = snapshot,
                sessionId = 1,
                statusTransitionCount = 0,
                observedFrameCount = 1,
                readyBundleCount = 0,
            )
        val textAtFrameTwo =
            buildCameraGeometryDiagnosticText(
                snapshot = snapshot,
                sessionId = 1,
                statusTransitionCount = 0,
                observedFrameCount = 2,
                readyBundleCount = 0,
            )

        assertTrue(textAtFrameOne.contains("frames: 1"))
        assertTrue(textAtFrameTwo.contains("frames: 2"))
        assertNotEquals(textAtFrameOne, textAtFrameTwo)
    }

    private fun emptySnapshotFor(category: CameraGeometryDiagnosticCategory) =
        CameraGeometryDiagnosticSnapshot(
            category = category,
            quality = null,
            frameTimestampNanos = null,
            bufferWidthPx = null,
            bufferHeightPx = null,
            cropLeftPx = null,
            cropTopPx = null,
            cropRightPx = null,
            cropBottomPx = null,
            rotationDegrees = null,
            viewportWidthPx = null,
            viewportHeightPx = null,
            pairDeltaNanos = null,
            intrinsicsSource = null,
            horizontalFovDeg = null,
            verticalFovDeg = null,
            uniformScale = null,
            displayOffsetX = null,
            displayOffsetY = null,
            centerProbe = null,
        )
}
