package dev.pointtosky.mobile.ar.camera

/**
 * Plain-value fake [CloseableFrameMetadataSource] (CAM-1c) — has no `planes`/`image`/pixel concept
 * at all, so any test built on it inherently cannot access pixel data. Tracks [closeCount] so tests
 * can assert the exactly-once-close guarantee.
 */
class FakeFrameMetadataSource(
    override val timestampNanos: Long = 1_000L,
    override val widthPx: Int = 1920,
    override val heightPx: Int = 1080,
    override val rotationDegrees: Int = 0,
    override val cropRectLeftPx: Int? = null,
    override val cropRectTopPx: Int? = null,
    override val cropRectRightPx: Int? = null,
    override val cropRectBottomPx: Int? = null,
) : CloseableFrameMetadataSource {
    var closeCount: Int = 0
        private set

    override fun close() {
        closeCount++
    }
}
