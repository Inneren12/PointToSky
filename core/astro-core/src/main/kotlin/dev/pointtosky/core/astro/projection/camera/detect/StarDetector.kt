package dev.pointtosky.core.astro.projection.camera.detect

/**
 * SKY-2: pixels → point sources. Finds star-like point sources in one 8-bit luma frame and reports
 * their sub-pixel centroids and a **relative** brightness.
 *
 * This is the first half of the detector/matcher pair and deliberately nothing more. It does not know
 * what star it is looking at, does not compare a detection to a prediction, and does not solve a pose —
 * those are separate stages with separate failure modes, and folding them together would make it
 * impossible to say whether a bad pose came from bad pixels or bad correspondence.
 *
 * ## Algorithm
 * 1. [estimateTiledBackground] measures the sky level and its noise per tile and interpolates between
 *    tile centres, because the night sky background is not uniform across a frame.
 * 2. Threshold at `background + max(sigmaThreshold * sigma, minThresholdAboveBackground)`, evaluated per
 *    pixel from the interpolated model. The absolute floor matters: a synthetic or heavily denoised
 *    frame can report `sigma == 0`, and `background + 0` would put every pixel equal to the background
 *    above threshold.
 * 3. Group above-threshold pixels into connected components by iterative flood fill — never recursive,
 *    since one bright cloud can connect a large fraction of the frame and would overflow the stack.
 * 4. Compute each component's intensity-weighted centroid using `luma - background` as the weight, which
 *    is what makes the result sub-pixel: the weights are the star's own flux distribution, so the
 *    centroid lands where the PSF is centred rather than at the middle of the thresholded footprint.
 * 5. Filter and flag: reject components below [StarDetectorConfig.minPixelCount] (single-pixel spikes are
 *    hot pixels or cosmic-ray hits, not stars, because a real point source is spread over several pixels
 *    by the optics) and above [StarDetectorConfig.maxPixelCount] (cloud edges, moon glow, lens flare);
 *    flag saturation and frame-border contact.
 *
 * ## Brightness is relative, not photometric
 * [DetectedSource.brightness] is the flux above the local background summed over a source's
 * above-threshold pixels, in raw 8-bit luma units. It orders sources within one frame and nothing more:
 * it carries no zero point, no exposure normalisation, no colour term, and no aperture correction, so it
 * must never be converted to a magnitude or compared across frames with different exposures. It is not
 * even the source's *total* flux, since the threshold truncates the profile's wings by an amount that
 * depends on how broad the profile is. Calibrated photometry is a separate axis of work.
 *
 * ## Determinism
 * Identical input yields an identical list, in an identical order. Nothing here is randomised,
 * floating-point accumulation follows a fixed raster order, and the output is sorted by an explicit
 * total order (see [detectStars]). Replay and regression tests depend on this.
 */

/** Which neighbours join one above-threshold pixel to the next when grouping components. */
enum class PixelConnectivity {
    /**
     * Edge-sharing neighbours only. Splits sources that touch at a single corner, which keeps two stars
     * in a tight pair separate at the cost of occasionally splitting one faint, ragged source in two.
     */
    FOUR,

    /**
     * Edge- and corner-sharing neighbours. The default: a real PSF thresholded near its wings has a
     * ragged, diagonally-connected outline, and 4-connectivity routinely breaks such a footprint into
     * several fragments — each then falling below [StarDetectorConfig.minPixelCount] and vanishing.
     */
    EIGHT,
}

/** Documented defaults, kept out of the constructor so each one can carry its own reasoning. */
object StarDetectorDefaults {
    /**
     * Background tile edge, in pixels. Large enough that a star (a handful of pixels across) cannot
     * shift a tile's median, small enough that a light-pollution gradient is resolved into many steps
     * across a typical analysis buffer — a 640x480 buffer gets a 10x8 tile grid at this size.
     */
    const val BACKGROUND_TILE_SIZE_PX = 64

    /**
     * Detection threshold in sigmas above the local background. At 4 sigma a pure-Gaussian noise pixel
     * clears the threshold about 3 times in 100 000, so a 640x480 frame expects roughly ten isolated
     * noise pixels — all of them single-pixel, and all of them removed by [MIN_PIXEL_COUNT]. Lowering
     * this admits fainter stars at a steeply rising false-positive cost.
     */
    const val SIGMA_THRESHOLD = 4.0

    /**
     * The threshold is never closer to the background than this many luma levels, whatever sigma says.
     * A frame with no noise at all reports `sigma == 0`, and without this floor the threshold would
     * equal the background and the whole frame would read as one enormous source.
     */
    const val MIN_THRESHOLD_ABOVE_BACKGROUND = 1.0

    /**
     * Smallest accepted component, in pixels. A point source imaged through real optics is spread over
     * several pixels; a single isolated bright pixel is a hot pixel, a cosmic-ray hit, or a noise
     * excursion. Three is the smallest count that requires a source to have any extent at all while
     * still admitting a genuinely undersampled star.
     */
    const val MIN_PIXEL_COUNT = 3

    /**
     * Largest accepted component, in pixels. A star's thresholded footprint is tens of pixels even when
     * badly out of focus; a component of thousands is a cloud edge, moon glow, a terrestrial light, or
     * lens flare. Generous on purpose — this exists to drop obviously non-stellar blobs, not to make a
     * shape judgement the detector is not equipped to make.
     */
    const val MAX_PIXEL_COUNT = 5000

    /** Peak luma at which an 8-bit pixel is clipped and its true flux is unknowable. */
    const val SATURATION_LUMA = 255

    /**
     * How close to the frame border a component must come to be flagged [DetectedSource.nearEdge].
     * One pixel — touching the outermost row or column — is enough, because a PSF that reaches the edge
     * has already been truncated and its centroid pulled inward.
     */
    const val EDGE_MARGIN_PX = 1
}

/**
 * Detector tuning. Every threshold that could reasonably be argued about is here rather than inline, so
 * a change is a change to one value with a name rather than a hunt through the algorithm.
 */
data class StarDetectorConfig(
    val backgroundTileSizePx: Int = StarDetectorDefaults.BACKGROUND_TILE_SIZE_PX,
    val sigmaThreshold: Double = StarDetectorDefaults.SIGMA_THRESHOLD,
    val minThresholdAboveBackground: Double = StarDetectorDefaults.MIN_THRESHOLD_ABOVE_BACKGROUND,
    val minPixelCount: Int = StarDetectorDefaults.MIN_PIXEL_COUNT,
    val maxPixelCount: Int = StarDetectorDefaults.MAX_PIXEL_COUNT,
    val connectivity: PixelConnectivity = PixelConnectivity.EIGHT,
    val saturationLuma: Int = StarDetectorDefaults.SATURATION_LUMA,
    val edgeMarginPx: Int = StarDetectorDefaults.EDGE_MARGIN_PX,
    /**
     * Whether a component touching the frame border is dropped instead of returned with
     * [DetectedSource.nearEdge] set.
     *
     * The default is to **keep and flag**, and the choice is deliberate. An edge source has a truncated
     * PSF, so its centroid is biased inward by an amount that grows with how much of the profile fell
     * outside the frame — but the bias is small for a source whose wings are merely clipped, and the
     * detection itself is still real. Dropping it here would hide that from every downstream stage,
     * whereas a flag lets a matcher use the source with a wider tolerance and lets a future pose solver
     * exclude it from a fit. Callers that want the strict policy set this to `true`.
     */
    val rejectNearEdge: Boolean = false,
) {
    init {
        require(backgroundTileSizePx > 0) { "backgroundTileSizePx must be positive; was $backgroundTileSizePx" }
        require(sigmaThreshold > 0.0 && sigmaThreshold.isFinite()) {
            "sigmaThreshold must be positive and finite; was $sigmaThreshold"
        }
        require(minThresholdAboveBackground > 0.0 && minThresholdAboveBackground.isFinite()) {
            "minThresholdAboveBackground must be positive and finite; was $minThresholdAboveBackground"
        }
        require(minPixelCount > 0) { "minPixelCount must be positive; was $minPixelCount" }
        require(maxPixelCount >= minPixelCount) {
            "maxPixelCount ($maxPixelCount) must be >= minPixelCount ($minPixelCount)"
        }
        require(saturationLuma in 1..StarDetectorDefaults.SATURATION_LUMA) {
            "saturationLuma must be within [1, ${StarDetectorDefaults.SATURATION_LUMA}]; was $saturationLuma"
        }
        require(edgeMarginPx >= 0) { "edgeMarginPx must be non-negative; was $edgeMarginPx" }
    }
}

/**
 * One detected point source, in analysis-buffer pixels — the same space `SkyPredictedStar.imageXPx` /
 * `imageYPx` use, so a residual against a prediction is a plain subtraction with no transform between.
 *
 * @property xPx intensity-weighted centroid, sub-pixel, in the project's continuous edge-coordinate
 *   convention: raster sample `[x, y]` is centred at `(x + 0.5, y + 0.5)`. That convention is not chosen
 *   here — it is stated canonically in
 *   [dev.pointtosky.core.astro.projection.camera.PixelPoint]'s file KDoc and
 *   `docs/camera_coordinate_calibration_contract.md` §9.2, and is the same one
 *   [dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel] projects into, so a
 *   centroid and a predicted `imageXPx`/`imageYPx` are directly subtractable. `SkySessionLogDetectionTest`
 *   pins the two together against a real projection output.
 * @property brightness integrated flux above the local background, in raw luma units, summed over the
 *   pixels that cleared the threshold. Relative within one frame only — see the file KDoc on why this is
 *   not a magnitude. Note that it is flux above the *threshold*, so it is a lower bound on the source's
 *   total flux and the shortfall depends on the profile's width: two sources of equal total flux but
 *   different widths do not measure equal, because the broader one loses more of itself to the cut.
 * @property peakLuma the brightest single pixel in the component, unsigned 0..255.
 * @property localBackgroundLuma the interpolated background at the centroid's pixel, carried so a caller
 *   can see what [brightness] was measured against without re-running the background model.
 * @property pixelCount how many pixels cleared the threshold.
 * @property saturated whether [peakLuma] reached [StarDetectorConfig.saturationLuma]. A saturated source
 *   has a clipped, flat-topped profile: its centroid is still usable but its [brightness] is a lower
 *   bound on the true flux, not a measurement of it.
 * @property nearEdge whether the component came within [StarDetectorConfig.edgeMarginPx] of the frame
 *   border, meaning its PSF may be truncated and its centroid biased inward.
 */
data class DetectedSource(
    val xPx: Double,
    val yPx: Double,
    val brightness: Double,
    val peakLuma: Int,
    val localBackgroundLuma: Double,
    val pixelCount: Int,
    val saturated: Boolean,
    val nearEdge: Boolean,
)

/**
 * Detects point sources in [frame]. See the file KDoc for the algorithm and for what this deliberately
 * does not do.
 *
 * The returned list is ordered brightest first, with ties broken by `yPx` then `xPx`, so the order is a
 * total one that depends only on the pixels — never on iteration order, hash order, or component
 * discovery order. Two runs over equal input produce equal lists.
 */
fun detectStars(
    frame: LumaFrame,
    config: StarDetectorConfig = StarDetectorConfig(),
): List<DetectedSource> {
    val background = estimateTiledBackground(frame, config.backgroundTileSizePx)
    return detectStars(frame, background, config)
}

/**
 * Detects point sources in [frame] against an already-computed [background].
 *
 * Exposed separately for callers that want to inspect, reuse, or substitute the background model — a
 * diagnostic tool rendering the estimated sky, or a caller running two thresholds over one estimate —
 * without paying for the tiling twice or reimplementing it.
 */
fun detectStars(
    frame: LumaFrame,
    background: TiledBackground,
    config: StarDetectorConfig = StarDetectorConfig(),
): List<DetectedSource> {
    val visited = BooleanArray(frame.pixelCount)
    val sources = mutableListOf<DetectedSource>()
    val stack = ArrayDeque<Int>()
    val neighbours = config.connectivity.offsets()

    for (seedY in 0 until frame.heightPx) {
        for (seedX in 0 until frame.widthPx) {
            val seedIndex = seedY * frame.widthPx + seedX
            if (visited[seedIndex]) continue
            visited[seedIndex] = true
            if (frame.isAboveThreshold(seedX, seedY, background, config)) {
                val component = growComponent(frame, background, config, seedIndex, visited, stack, neighbours)
                component.toSourceOrNull(frame, background, config)?.let(sources::add)
            }
        }
    }

    return sources.sortedWith(DETECTION_ORDER)
}

/**
 * Flood-fills the above-threshold component containing [seedIndex], marking every pixel it reaches in
 * [visited] so the raster scan never starts a second component inside one it has already grown.
 *
 * Iterative, with an explicit [stack]: a recursive fill would be shorter and would blow the JVM stack on
 * the first frame containing a cloud, a lens flare, or a horizon glow — components that can span a large
 * fraction of a multi-megapixel buffer. The stack is passed in and reused across components so a frame
 * full of stars does not allocate one per source.
 */
private fun growComponent(
    frame: LumaFrame,
    background: TiledBackground,
    config: StarDetectorConfig,
    seedIndex: Int,
    visited: BooleanArray,
    stack: ArrayDeque<Int>,
    neighbours: List<NeighbourOffset>,
): ComponentAccumulator {
    stack.clear()
    stack.addLast(seedIndex)
    val component = ComponentAccumulator()

    while (stack.isNotEmpty()) {
        val index = stack.removeLast()
        val x = index % frame.widthPx
        val y = index / frame.widthPx
        component.add(x, y, frame.lumaAt(x, y), background.levelAt(x, y))

        for (offset in neighbours) {
            val nx = x + offset.dx
            val ny = y + offset.dy
            if (!frame.contains(nx, ny)) continue
            val neighbourIndex = ny * frame.widthPx + nx
            if (!visited[neighbourIndex]) {
                visited[neighbourIndex] = true
                if (frame.isAboveThreshold(nx, ny, background, config)) stack.addLast(neighbourIndex)
            }
        }
    }
    return component
}

/** Whether ([x], [y]) addresses a real pixel; kept as one named check so the bounds test reads as one idea. */
private fun LumaFrame.contains(
    x: Int,
    y: Int,
): Boolean = x in 0 until widthPx && y in 0 until heightPx

/**
 * Brightest first, then top-to-bottom, then left-to-right. Every field in the comparator chain comes
 * from the pixels themselves, so the order cannot depend on how components happened to be discovered.
 */
private val DETECTION_ORDER: Comparator<DetectedSource> =
    compareByDescending<DetectedSource> { it.brightness }
        .thenBy { it.yPx }
        .thenBy { it.xPx }

private data class NeighbourOffset(
    val dx: Int,
    val dy: Int,
)

private val FOUR_NEIGHBOURS =
    listOf(
        NeighbourOffset(-1, 0),
        NeighbourOffset(1, 0),
        NeighbourOffset(0, -1),
        NeighbourOffset(0, 1),
    )

private val EIGHT_NEIGHBOURS =
    FOUR_NEIGHBOURS +
        listOf(
            NeighbourOffset(-1, -1),
            NeighbourOffset(1, -1),
            NeighbourOffset(-1, 1),
            NeighbourOffset(1, 1),
        )

private fun PixelConnectivity.offsets(): List<NeighbourOffset> =
    when (this) {
        PixelConnectivity.FOUR -> FOUR_NEIGHBOURS
        PixelConnectivity.EIGHT -> EIGHT_NEIGHBOURS
    }

/**
 * Whether ([x], [y]) is strictly above the local detection threshold.
 *
 * Strictly: a pixel exactly at the threshold is background. With the absolute floor in
 * [StarDetectorConfig.minThresholdAboveBackground] this is what keeps a perfectly flat, noiseless frame
 * from reading as one frame-sized source.
 */
private fun LumaFrame.isAboveThreshold(
    x: Int,
    y: Int,
    background: TiledBackground,
    config: StarDetectorConfig,
): Boolean {
    val level = background.levelAt(x, y)
    val margin = maxOf(config.sigmaThreshold * background.sigmaAt(x, y), config.minThresholdAboveBackground)
    return lumaAt(x, y) > level + margin
}

/**
 * Running totals for one connected component, accumulated in flood-fill order.
 *
 * The centroid weight is `luma - background`, clamped at zero. Weighting by raw luma instead would drag
 * every centroid toward the frame's brightest region, because a pedestal shared by all of a component's
 * pixels contributes to the weighted sum without carrying any information about where the source is.
 */
private class ComponentAccumulator {
    private var weightSum = 0.0
    private var weightedX = 0.0
    private var weightedY = 0.0

    var pixelCount = 0
        private set
    var peakLuma = 0
        private set
    var minX = Int.MAX_VALUE
        private set
    var maxX = Int.MIN_VALUE
        private set
    var minY = Int.MAX_VALUE
        private set
    var maxY = Int.MIN_VALUE
        private set

    fun add(
        x: Int,
        y: Int,
        luma: Int,
        backgroundLevel: Double,
    ) {
        val weight = (luma - backgroundLevel).coerceAtLeast(0.0)
        pixelCount += 1
        weightSum += weight
        weightedX += weight * (x + PIXEL_CENTRE_OFFSET)
        weightedY += weight * (y + PIXEL_CENTRE_OFFSET)
        if (luma > peakLuma) peakLuma = luma
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y
    }

    /**
     * The finished source, or `null` when the component fails a size filter or the near-edge policy.
     *
     * A component whose weights all clamped to zero also yields `null`: it cleared the threshold on the
     * raw comparison but carries no flux above the interpolated background at its own pixels, so there
     * is no defensible centroid to report and dividing by the zero weight sum would produce `NaN`.
     */
    fun toSourceOrNull(
        frame: LumaFrame,
        background: TiledBackground,
        config: StarDetectorConfig,
    ): DetectedSource? {
        if (pixelCount < config.minPixelCount || pixelCount > config.maxPixelCount) return null
        if (weightSum <= 0.0) return null

        val touchesTopLeft = minX < config.edgeMarginPx || minY < config.edgeMarginPx
        val touchesBottomRight =
            maxX >= frame.widthPx - config.edgeMarginPx || maxY >= frame.heightPx - config.edgeMarginPx
        val nearEdge = touchesTopLeft || touchesBottomRight
        if (nearEdge && config.rejectNearEdge) return null

        val centroidX = weightedX / weightSum
        val centroidY = weightedY / weightSum
        val centroidPixelX = centroidX.toInt().coerceIn(0, frame.widthPx - 1)
        val centroidPixelY = centroidY.toInt().coerceIn(0, frame.heightPx - 1)
        return DetectedSource(
            xPx = centroidX,
            yPx = centroidY,
            brightness = weightSum,
            peakLuma = peakLuma,
            localBackgroundLuma = background.levelAt(centroidPixelX, centroidPixelY),
            pixelCount = pixelCount,
            saturated = peakLuma >= config.saturationLuma,
            nearEdge = nearEdge,
        )
    }
}

/**
 * Raster sample `[x, y]` spans `[x, x+1) x [y, y+1)` and so is centred at `(x + 0.5, y + 0.5)` — the
 * project-wide continuous edge-coordinate convention, stated canonically in
 * [dev.pointtosky.core.astro.projection.camera.PixelPoint]'s file KDoc. Adding this offset before
 * weighting is what makes a single-pixel source report the centre of that sample rather than its corner,
 * and what keeps a centroid on the same axis a projected star lands on.
 */
private const val PIXEL_CENTRE_OFFSET = 0.5
