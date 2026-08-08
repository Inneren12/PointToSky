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

The spread is measured over a tile's raw pixels, so a gradient steep enough to ramp within one tile
is counted as noise and inflates sigma. The error is one-sided and conservative — reduced sensitivity
to the faintest stars there, not extra false positives. Subtracting the interpolated level before
measuring the spread would fix it and is a later refinement.

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

Reading the *recorded* predicted coordinates is correct here precisely because this is a detection
metric and not a projection check: `SkySessionLogReplay` already re-derives them from the catalogue
and diffs the two, so the projection is verified independently of anything measured by the detector.
