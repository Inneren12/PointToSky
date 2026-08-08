# Star detection (SKY-2)

Turning one frame's pixels into point sources: sub-pixel centroids and a relative brightness. Pure
JVM, no Android, no device — it runs on the analysis-buffer luma a SKY-1 session records.

This is deliberately **one half** of the detector/matcher pair. What it produces is a list of
"there is something star-like here"; deciding *which* star, and solving a pose from that, are later
stages with their own failure modes. Keeping them apart is what makes it possible to say whether a
bad pose came from bad pixels or from bad correspondence.

## Where the code lives

| Concern | Module | File |
| --- | --- | --- |
| Luma frame (stride-aware) | `:core:astro-core` | `…/projection/camera/detect/LumaFrame.kt` |
| Tiled background / noise model | `:core:astro-core` | `…/camera/detect/TiledBackground.kt` |
| Detector | `:core:astro-core` | `…/camera/detect/StarDetector.kt` |
| Evaluation metrics | `:core:astro-core` | `…/camera/detect/DetectionEvaluation.kt` |
| Synthetic frame renderer (**test only**) | `:core:astro-core` (`test`) | `…/camera/detect/SyntheticFrameRenderer.kt` |

## Input

`LumaFrame` — the SKY-1 `RAW_Y8` plane as written: 8-bit unsigned intensity, one byte per pixel,
`rowStridePx` bytes per row. **The stride may exceed the width** and is honoured on every access; a
detector that assumed `width * height` packing would read row padding as image content and report a
diagonal smear of phantom sources. `LumaFrame.forReference` builds a frame from a `SkyLumaReference`
and the bytes of the file it points at, checking the recorded `byteLength` so a truncated capture
fails instead of being detected in.

## Pixel-coordinate convention (not chosen here)

SKY-2 does **not** define a coordinate convention. It uses the project's existing one, stated
canonically in `PixelGeometry.kt`'s file KDoc and `docs/camera_coordinate_calibration_contract.md`
§9.2:

> Coordinates are continuous image-edge coordinates in `[0, W] × [0, H]`, so raster sample `[x, y]`
> occupies `[x, x+1) × [y, y+1)` and its **centre** is at `(x + 0.5, y + 0.5)`.

Origin top-left, `+x` right, `+y` down. This is the same space `PinholeProjectionModel` projects into
and the same one `SkyPredictedStar.imageXPx` / `imageYPx` are recorded in, so a detected centroid and
a predicted position are subtractable with no transform in between.

Being the same convention is not asserted, it is pinned: `PixelConventionBridgeTest` takes a
`PixelPoint` out of the real `PinholeProjectionModel` and out of `projectStars`, renders a source at
exactly that coordinate, detects it, and requires the round trip to return the number it started
from with no systematic offset on either axis. The test asserts its own sensitivity — that a
half-pixel shift would exceed its tolerance — so a pass is evidence rather than a tolerance wide
enough to swallow the error it exists to find. Flipping the detector's offset to `0.0` fails it.

A consequence worth stating because it is easy to get backwards: a star projected to exactly
`(320.0, 240.0)` in a 640×480 buffer sits on the **corner** shared by samples `[319,239]`,
`[320,239]`, `[319,240]`, `[320,240]` — the buffer's geometric centre — not at the centre of any one
sample.

The detector takes **one frame's luma plus that frame's predicted stars** and nothing else. It does
not depend on clock alignment: whether a real session reports a usable `SENSOR_INFO_TIMESTAMP_SOURCE`
decides whether replay can rebuild geometry, and has no bearing on detection.

## Algorithm

1. **Tiled background.** The sky level and its noise are measured per tile (64 px default) and
   bilinearly interpolated between tile centres. A single global threshold cannot work on a frame
   with a light-pollution gradient: set high enough for the bright end and the dark end loses every
   star; set low enough for the dark end and the bright end returns one blob.
   The level is the tile **median** and the spread is read from the **lower quartile**
   (`sigma = (median - q25) / 0.6745`), because stars contaminate only the upper tail — a mean and a
   standard deviation would both be pulled up by the very stars the threshold has to find.
   The **level** is measured over a tile's raw pixels, from a 256-bin histogram that is exact because
   an 8-bit luma has no values between the bins. The **spread** is measured over the residual
   `pixel - levelAt(pixel centre)`, so a gradient steep enough to ramp within one tile is removed by
   the interpolated model before the spread is taken and sigma reports the sky's noise rather than
   the ramp. That needs two passes — the residual is taken against the interpolated level, which is
   not available until every tile's level has been measured. The residual is a real number, since the
   interpolated level is a `Double` that lands between luma levels, so pass 2 keeps and sorts the
   residual samples themselves rather than binning them; rounding them onto the luma grid first would
   discard the sub-luma information the spread is made of.

   How finely the spread resolves therefore depends on the tile. Over a gradient the level sweeps
   several luma across the tile, the residuals are a continuous sample, and the estimate lands within
   a few percent of `sqrt(sigma^2 + 1/12)` — the injected noise plus the sensor's own rounding. Over a
   flat sky the level is constant across the tile, every residual is an integer minus that constant,
   and sigma is quantised to multiples of `1 / 0.6745 = 1.48` luma; below about one luma of noise it
   reads as zero, which is what `minThresholdAboveBackground` is the floor for. That quantisation is a
   property of an 8-bit sensor, not of the estimator.
   The final tile on each axis absorbs the remainder, so it is genuinely a different size; its
   interpolation node is therefore placed at its **actual** centre, computed from its real
   `[start, end)` bounds, never at the nominal `(index + 0.5) * tileSizePx`. For a 100 px wide frame
   at a 64 px tile the last column spans `[64, 100)` and is centred at 82, where the nominal formula
   says 96 — trusting the formula would place the last measured value 14 px right of the pixels it
   was measured over and shift the whole model near the frame's right and bottom edges.
2. **Threshold** at `background + max(k * sigma, minThresholdAboveBackground)`, `k = 4` by default.
   The absolute floor matters: a noiseless frame measures `sigma == 0`, and `background + 0` would
   put the whole frame above threshold.
3. **Connected components** by iterative flood fill (8-connected by default; never recursive, since
   one cloud can connect a large fraction of a frame).
4. **Intensity-weighted centroid** per component, weighted by `luma - background`. Weighting by raw
   luma would drag every centroid toward the frame's brightest region.
5. **Filter and flag**: reject below `minPixelCount` (a single bright pixel is a hot pixel or a
   cosmic-ray hit, not a star) and above `maxPixelCount` (cloud edge, moon glow, flare); flag
   `saturated` at peak 255 and `nearEdge` on border contact.

Output is ordered brightest first, ties broken by `y` then `x` — a total order that depends only on
the pixels, so identical input gives an identical list.

### Near-edge policy

An edge source is **kept and flagged**, not dropped. Its PSF is truncated so its centroid is biased
inward, but the detection is real; a flag lets a matcher widen its tolerance and lets a future pose
solver exclude it from a fit, where dropping it would hide it from both. `rejectNearEdge = true`
selects the strict policy.

### Known limitation

The background model is bilinear between tile centres, so it can follow a background that ramps but
not one that *curves* on the tile scale. Under a source much broader than a tile — moon glow, a cloud
edge, a horizon light — the interpolation runs below the true sky at the source's crown, and the top
of that crown can clear the local threshold and come back as a few small sources.

`maxPixelCount` does not address this and must not be quoted as if it did. It rejects a single
oversized *connected component*, which is what a broad source produces when the background model does
not absorb it. In the case above the model absorbs most of the source, so what is left above threshold
is a handful of small fragments, each well inside the size limits. Separating those from a star
requires a judgement about shape, which this detector deliberately does not make; what is bounded
today is that the fragments stay small and stay on the source that produced them.

Outside the outermost tile centres the model extrapolates flat, so the border margin — half a tile on
each side — carries whatever the sky ramps across it, both in the level it reports and in the residual
the spread is measured on. This is the same conservatism the whole frame used to have before the
spread moved onto the residual, now confined to the margin.

## Brightness is relative, not photometric

`DetectedSource.brightness` is the flux above the local background summed over a source's
above-threshold pixels, in raw luma units. It **orders sources within one frame** and nothing more:
no zero point, no exposure normalisation, no colour term, no aperture correction. It is not even the
source's total flux, since the threshold truncates the profile's wings by an amount that depends on
how broad the profile is — two sources of equal total flux but different widths do not measure equal.
It must never be converted to a magnitude or compared across frames with different exposures.
Calibrated photometry is a separate axis of work.

## `DetectionEvaluation` is a metric, not the matcher

`evaluateDetections` pairs detections with known positions by nearest neighbour inside a fixed pixel
radius and reports detection rate, centroid residual RMS, and unpaired-detection count. It works only
when the predicted positions are already very nearly right — which is what a synthetic fixture
provides and what real data does not.

### The truth set is the detector-observable subset

`predictedCount` means **in-image predicted sources the detector could physically have found**, not
all projected catalogue entries, and `detectionRate` is a fraction of that. `toPredictedPointsPx()`
is what narrows one to the other, on two independent gates:

1. **Classification**, via `PredictedStarClassification.isDetectorObservable()`. Kept:
   `VISIBLE_IN_VIEWPORT` and `INSIDE_IMAGE_OUTSIDE_VIEWPORT`. Dropped: `OUTSIDE_IMAGE` and
   `BEHIND_CAMERA`.
2. **Non-null coordinates.** Nothing is manufactured, defaulted, or clamped into the frame.

The gate that is easy to miss is `OUTSIDE_IMAGE`. `projectStars` runs the pinhole model for every
in-front star and *only then* classifies the resulting point as falling outside `sourceCrop`, so an
`OUTSIDE_IMAGE` entry arrives carrying a perfectly finite image coordinate — one that may even lie
numerically inside the buffer's dimensions. A finite coordinate is therefore not evidence that a star
is on the analysed raster, and a coordinate-range check cannot substitute for the classification.
Counting those stars would charge the detector with misses no detector could avoid, and the size of
the error would track how much sky the catalogue happened to cover outside the frame rather than
anything about the detector.

`INSIDE_IMAGE_OUTSIDE_VIEWPORT` is kept, deliberately: it means inside `sourceCrop` but cropped away
by `FILL_CENTER` before reaching the display. That is a statement about what the user saw, not about
what the sensor recorded — the pixels are in the analysis buffer either way, and the detector never
looks at the viewport.

An in-image classification with absent coordinates is a self-contradictory record (every in-front
projection produces a point). It is excluded rather than repaired into a position the projection
never produced.

The `when` behind `isDetectorObservable()` is exhaustive with no `else`, so adding a classification
to the projection contract is a compile error here until someone decides which side it falls on.

A number from it may be reported as *"the detector recovered N of M known sources at this residual"*.
It may **not** be reported as a match rate, a correspondence, a plate solution, or evidence that
pointing works. Feeding its matches into a pose solve assumes the answer: the pairing was made *from*
the predicted positions, so a pose fitted to it can only return the pose those predictions came from.
The real matcher — robust to unknown rotation and translation, missing stars, and detections with no
counterpart — is separate work built on geometric invariants.

`falsePositiveCount` is exact on a synthetic frame and an upper bound on a real one: the predicted
set is limited by catalogue depth and by what the projector considered in view, so a real star the
catalogue did not carry counts as a false positive despite being a correct detection.

## Synthetic frames are a test utility

`SyntheticFrameRenderer.kt` lives in the test source set and must stay there. Nothing in the shipped
app path may call it, and a frame it produces must never be written into a session log — a synthetic
frame in a log directory would be indistinguishable from a captured one and would poison every
measurement made from that session.

It exists because the SKY-1 fixtures carry no pixels: `SkySessionLogFixtures` builds a
`SkyLumaReference` (path, geometry, byte length) and nothing else, because the replay it was written
for compares recomputed projections against recorded ones and never opens a frame file.

Stars are rendered by **integrating** a Gaussian over each pixel's area rather than point-sampling
it, because point sampling a profile a few pixels wide biases the reconstructed centroid by the same
order as the sub-pixel accuracy being tested — a point-sampled fixture would measure the renderer's
discretisation error and call it the detector's. Backgrounds (uniform, linear gradient), noise
(Gaussian, Poisson), hot pixels, and saturation are all injectable, and all noise is seeded, so every
frame is reproducible.

## Relationship to SKY-1

SKY-2 **consumes** the SKY-1 contract and does not change it. No schema change, no codec change, no
`SKY_SESSION_LOG_SCHEMA_VERSION` bump; `SkyLumaReference` and `SkyPredictedStar` are read as-is, and
the `FrameContent` dot-grid track is untouched.

### Which predicted positions a run scores against

An offline SKY-3 run scores detections against the **detector-observable positions of a successful
replay** — `SkyFrameReplayResult.Ready.projections` narrowed by `toPredictedPointsPx`, i.e. what the
current projection math produces from the frame's own pose, observer and intrinsics. It never scores
against the log's recorded `imageXPx`/`imageYPx`. A recorded coordinate is what some earlier build
wrote; only the recomputed one is a position the current math stands behind, so scoring against a
record that is stale, hand-edited, or written by a build whose projection has since been corrected
would let it earn an excellent detection rate against a number nothing verified. Where replay refuses
a frame there is no truth set, and the frame is reported as unscored rather than falling back to the
recorded values.

The recorded coordinates are retained for exactly one purpose: a **recorded-vs-replayed integrity
diagnostic** (`replayMaxImageResidualPx`, `replayRmsImageResidualPx`,
`replayClassificationMismatchCount`) — how far the capturing device's projection sat from what the
same math produces now. That is a measure of replay integrity, not of detector error, and the two are
deliberately never mixed.

The `List<SkyPredictedStar>.toPredictedPointsPx` overload still exists for in-repo fixture tests,
where the "recorded" positions are authored by the test itself and there is no replay to disagree
with.

## What consumes this next

`docs/star_matcher_input_contract.md` defines what a star matcher is handed — detections, catalog
candidates, and the angular scale the detector deliberately does not know — and restates, with a
measurement rather than an assertion, why `brightness` may never be converted to a magnitude. It is an
input contract only; no matching algorithm exists yet.
