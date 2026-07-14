# Camera Coordinate & Calibration Contract (CAM-0b)

**Status:** Spec / recon proposal. No production code is defined here.
**Scope:** Define the minimal coordinate-frame and camera-calibration contract
that CAM-1 (camera-based star matching) needs before implementation begins.
**Audience:** Whoever implements CAM-1 and its supporting core module.

This document is the CAM-0b deliverable. It builds on:

- `docs/star_catalog_ptskcat0_format.md` — the PTSKCAT0 real-star catalog
  format (CAT-1).
- `docs/real_star_visibility_contract.md` — the `RealStarVisibilityService`
  visible-star selection (VF-1) and its VF-2a renderer adapter.
- The CAM-0a code inventory. No standalone CAM-0a document exists in the
  repository, so §0 below folds the relevant inventory into this spec so that
  every claim about "existing code" is grounded in a concrete file.

It deliberately does **not** implement camera matching. It specifies the
frames, the transform chain, the intrinsics contract, the sync contract, and
the CAM-1 input/output data models, then recommends a first implementation
slice.

---

## 0. CAM-0a inventory (existing camera / AR / projection / sensor code)

Everything CAM-1 will build on already exists as the **AR overlay** pipeline
in `:mobile`, plus the astronomy transforms in `:core:astro-core`. The table
is the ground truth for the rest of this document.

| Concern | File | Key symbols |
|---|---|---|
| Camera preview | `mobile/src/main/java/dev/pointtosky/mobile/ar/CameraPreview.kt` | `CameraPreview` — CameraX `Preview` only, `PreviewView.ScaleType.FILL_CENTER`, `DEFAULT_BACK_CAMERA` |
| Sensor attitude | `mobile/src/main/java/dev/pointtosky/mobile/ar/RotationFrame.kt` | `RotationFrame`, `rememberRotationFrame()` (`TYPE_ROTATION_VECTOR`, `SENSOR_DELAY_GAME`), `correctedForTrueNorth()`, `deviceRollDegrees()` |
| Display remap | `mobile/src/main/java/dev/pointtosky/mobile/ar/DisplayRemap.kt` | `remapForDisplay()` (`SensorManager.remapCoordinateSystem`) |
| Projection + overlay | `mobile/src/main/java/dev/pointtosky/mobile/ar/ArScreen.kt` | `calculateOverlay()`, `projectDeviceVector()`, `projectionParams()`, `horizontalToVector()`, `vectorToHorizontal()`, `VERTICAL_FOV_DEG = 56.0` |
| Overlay models | `mobile/src/main/java/dev/pointtosky/mobile/ar/AstroOverlayModels.kt` | `ScreenLineSegment`, `OverlayData`, star/asterism overlays |
| AR view model | `mobile/src/main/java/dev/pointtosky/mobile/ar/ArViewModel.kt` | `ArViewModel`, `ArUiState.Ready`, `ArStar` |
| Eq↔Hor transform | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/transform/EquatorialHorizontalTransform.kt` | `raDecToAltAz()`, `altAzToRaDec()`, `Meteo` |
| Sidereal time | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/time/SiderealTime.kt` | `gmstDeg()`, `lstDeg()`, `lstAt()` |
| Coordinate types | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/coord/Coordinates.kt` | `Equatorial`, `Horizontal`, `GeoPoint`, `Sidereal` |
| Angular separation | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/identify/Identify.kt` | `angularSeparationDeg()`, `IdentifySolver.findBest()` |
| Real catalog (CAT-1) | `core/catalog/src/main/java/dev/pointtosky/core/catalog/binary/PtskCat0Catalog.kt` | `PtskCat0Catalog` (struct-of-arrays: `raDegAt/decDegAt/magAt/bvAt/hipAt/nameAt`) |
| Visible set (VF-1) | `core/catalog/src/main/kotlin/dev/pointtosky/core/catalog/visibility/RealStarVisibilityService.kt` | `select(SkyQualityInput): RealStarVisibilityResult` |
| Visible adapter (VF-2a) | `core/catalog/src/main/kotlin/dev/pointtosky/core/catalog/visibility/render/VisibleRealStarSnapshot.kt` | `VisibleRealStar`, `VisibleRealStarSnapshot`, `VisibleRealStarProvider` |

**Critical inventory findings that shape this spec:**

1. **There is no camera-image pipeline.** `CameraPreview` binds only a CameraX
   `Preview` surface. There is **no** `ImageAnalysis`, no `ImageProxy`, no
   pixel buffer, and no frame timestamp reaching Kotlin. A grep for
   `CameraCharacteristics`, `ImageAnalysis`, `LENS_INFO`, `SENSOR_INFO`, and
   `imageInfo` across the whole tree returns nothing.
   **Updated by CAM-1c:** `CameraPreview` now also binds an `ImageAnalysis`
   use case and a frame-metadata-only pipeline exists — see §4.4. Pixel
   buffers are still never read anywhere in the tree; only metadata
   (`imageInfo.timestamp`, `width`/`height`, `imageInfo.rotationDegrees`,
   `cropRect`) reaches Kotlin.
2. **The projection uses a hardcoded FOV**, not the real lens:
   `VERTICAL_FOV_DEG = 56.0` (`ArScreen.kt:1276`), with horizontal FOV derived
   from the *viewport* aspect ratio (`projectionParams`, `ArScreen.kt:1201`).
3. **The overlay is aligned to the display viewport, not the camera image.**
   Projection maps to Compose `Offset` pixels over `overlaySize` (the AR
   `Box`), while `PreviewView` uses `FILL_CENTER`, which center-crops the
   camera frame by an unknown scale. The overlay currently "works" only
   because both the preview and the overlay fill the same box; the actual
   camera→display crop/scale is never modeled. This is the single biggest gap
   for pixel-accurate star matching.

---

## 1. Coordinate frames

Six frames participate. For each: definition, units, axis convention,
handedness, and where it lives in code today.

### 1.1 Catalog frame — equatorial J2000 (RA/Dec)

- **Type:** `Equatorial(raDeg, decDeg)`
  (`core/astro-core/.../coord/Coordinates.kt:9`).
- **Units:** RA `0°..360°`, Dec `-90°..+90°`, decimal degrees.
- **Epoch:** J2000 (PTSKCAT0 header `Epoch = 2000`;
  `docs/star_catalog_ptskcat0_format.md`). Catalog carries **no proper
  motion** and **no per-record epoch** — a single global J2000.
- **Source:** `PtskCat0Catalog.raDegAt(i)/decDegAt(i)` for CAT-1; VF-2a's
  `VisibleRealStar.raDeg/decDeg` for the visible subset.
- **Handedness:** right-handed celestial sphere; RA increases eastward.

### 1.2 Observer frame — horizontal (Alt/Az)

- **Type:** `Horizontal(azDeg, altDeg)` (`Coordinates.kt:20`).
- **Units:** Az `0°..360°` **clockwise from geographic North**; Alt
  `-90°..+90°` above the horizon.
- **Depends on:** observer `GeoPoint(latDeg, lonDeg)` and local sidereal time
  `Sidereal(lstDeg)`.
- **Source:** `raDecToAltAz(eq, lstDeg, latDeg, applyRefraction, meteo)`.
- **Handedness note:** azimuth is a *compass* angle (clockwise), not a
  math-positive (counter-clockwise) angle. Every conversion to/from a Cartesian
  vector must respect this.

### 1.3 Device / world frame — Android ENU

- **Definition:** the "world" frame produced by
  `SensorManager.getRotationMatrixFromVector` after `remapForDisplay`. It is
  **ENU**: `+X = East`, `+Y = North`, `+Z = Up (away from Earth centre)`.
  Right-handed.
- **North caveat:** the rotation vector's Y axis points at **magnetic**
  North. True North is obtained by rotating about `+Z` by the magnetic
  declination — see `RotationFrame.correctedForTrueNorth()`
  (`RotationFrame.kt:107`), which mirrors the watch's
  `Horizontal.toTrueNorth`.
- **Alt/Az ↔ ENU vector** (in `ArScreen.kt:1249`,
  `horizontalToVector`):
  `v = (cosAlt·sinAz, cosAlt·cosAz, sinAlt)` → confirms `X=East, Y=North,
  Z=Up`, `az = atan2(x, y)`.
- **Matrix:** `RotationFrame.rotationMatrix` is **device→world** (row-major
  3×3). Its transpose is **world→device**.

### 1.4 Camera frame — device axes after display remap

- **Definition:** the phone's own axes as re-expressed for the current display
  rotation by `remapForDisplay` (`DisplayRemap.kt`). Convention used by the
  projector (`projectDeviceVector`, `ArScreen.kt:1210`):
  `+X = screen right`, `+Y = screen up`, `+Z = out of the screen toward the
  user`. The **camera looks along −Z**.
- **Camera forward:** `forwardWorld = (−m[2], −m[5], −m[8])`
  (`RotationFrame.kt:62`) — the third column of device→world negated, i.e. the
  world direction the back camera points at.
- **Assumption baked in:** the optical axis is treated as exactly the device
  `−Z` axis, and the lens is assumed to be co-axial and non-tilted relative to
  the IMU. Any camera-to-IMU extrinsic offset is currently zero. (Acceptable
  for CAM-1 MVP; see §3.)

### 1.5 Image pixel frame — camera sensor image

- **Current status: does not exist in code.** No image buffer is delivered, so
  there is no image-pixel frame today.
- **Intended definition for CAM-1:** integer pixel `(u, v)` over the analyzed
  camera frame, origin top-left, `+u` right, `+v` down, resolution =
  `ImageProxy` width/height (after any analyzer rotation). Principal point
  `(cx, cy)` ≈ image centre unless intrinsics say otherwise.
- This frame is where detected bright points (if used) and projected star
  predictions must be compared for matching.

### 1.6 Display / screen frame — Compose pixels

- **Type:** Compose `Offset(x, y)` in device-independent pixels, origin
  top-left, `+x` right, `+y` down.
- **Produced by** `projectDeviceVector` (`ArScreen.kt:1224`):
  `screenX = halfWidth·(1 + ndcX)`, `screenY = halfHeight·(1 − ndcY)`. Note the
  **Y flip** (`1 − ndcY`): NDC up is +, screen down is +.
- **Extent:** `overlaySize` — the AR `Box` in `ArScreen`, portrait-locked in
  practice (roll handling assumes display `ROTATION_0`; see
  `deviceRollDegrees` docstring, `RotationFrame.kt:127`).
- **Not the same as the image pixel frame** (§1.5) because of `FILL_CENTER`
  crop/scale. Reconciling them is a CAM-1 deliverable (§3). CAM-1e **defines the
  pure geometry required** for this image↔display reconciliation
  (`CropScaleTransform`, §9). It is **not wired into the production projection or
  renderer** — this frame is unchanged until a later slice consumes it.

---

## 2. Transform chain

Intended chain, end to end:

```
RA/Dec (J2000)                     §1.1
  └─(A) + LST + latitude        →  Alt/Az                 §1.2
        └─(B) spherical→cartesian → ENU world vector      §1.3
              └─(C) world→device (Rᵀ) → device/camera vector §1.4
                    └─(D) pinhole project → normalized camera coords (NDC)
                          └─(E) NDC → image pixels / screen pixels §1.5/§1.6
```

Per step: existing code · missing code · sign/handedness risks · tests needed.

### Step A — RA/Dec + time/location → Alt/Az

- **Existing:** `raDecToAltAz()` (Meeus relations, Saemundsson refraction) and
  `lstAt()`/`gmstDeg()` (IAU-1982 GMST, ≈0.1° accurate near J2000). AR builds
  LST via `lstAt(instant, lon)` in `ArViewModel.buildState`.
- **Missing:** nothing structural. For matching, decide a single refraction
  policy (see risks).
- **Sign/handedness risks:**
  - Azimuth is clockwise-from-North; do not feed it to a math-CCW routine.
  - **Refraction inconsistency:** `raDecToAltAz` defaults `applyRefraction =
    true`, but the AR projector calls it with `applyRefraction = false`
    (`ArScreen.kt:967`), while the reticle's inverse `altAzToRaDec` has no
    refraction term at all. CAM-1 must pick one convention (recommend
    refraction **off** for geometric matching; it is <0.6° except very low and
    biases altitude only) and document it.
  - Hour-angle sign: `τ = LST − RA`; a swapped sign mirrors east/west.
- **Tests needed:** already covered by
  `core/astro/.../transform/EquatorialHorizontalTransformTest.kt` and
  `.../time/SiderealTimeTest.kt`. Add: a **round-trip** assertion
  (`altAzToRaDec(raDecToAltAz(x))` ≈ x with refraction off) and a couple of
  **absolute golden** cases (known star Alt/Az at a fixed instant/site).

### Step B — Alt/Az → ENU world vector

- **Existing:** `horizontalToVector()` (`ArScreen.kt:1249`) and its inverse
  `vectorToHorizontal()` (`ArScreen.kt:1260`).
- **Missing:** these are `private` inside `:mobile`. CAM-1 should promote a
  shared, tested version into `:core:astro-core` (e.g. alongside the transform
  package) so both rendering and matching use one implementation.
- **Sign/handedness risks:**
  - `az = atan2(x, y)` with `x=East, y=North`. Swapping the atan2 args, or
    using `atan2(y, x)`, rotates azimuth by 90° and/or mirrors it.
  - `z = sin(alt)`; a sign error flips the sky top-to-bottom.
- **Tests needed:** cardinal-direction table (N→(0,1,0), E→(1,0,0),
  zenith→(0,0,1)); `vectorToHorizontal(horizontalToVector(h)) ≈ h`.

### Step C — world → device/camera vector

- **Existing:** `RotationFrame` (attitude), `remapForDisplay` (display
  rotation), `correctedForTrueNorth` (declination), and `transpose()` →
  `worldToDevice = transpose(rotationMatrix)` in `calculateOverlay`
  (`ArScreen.kt:952`).
- **Missing:** camera-to-IMU extrinsic (assumed identity). A hook for a small
  calibration rotation (the CAM-1 output, §5) to be applied here.
- **Sign/handedness risks:**
  - **Magnetic vs true North:** forgetting `correctedForTrueNorth` biases every
    azimuth by the local declination (can be >10°).
  - **Display remap:** the `AXIS_*` mapping per `Surface.ROTATION_*`
    (`DisplayRemap.kt`) must match the resolution of the analyzed image; if the
    image analyzer applies its own rotation, remap and image rotation can
    double-count or cancel. Verify against the image, not just the preview.
  - **Camera = −Z:** `forwardWorld` negates the matrix column; dropping the
    negation points the reticle at the sky behind the phone.
  - Row-major vs column-major: `rotationMatrix` is row-major device→world; its
    transpose is world→device. Mislabeling inverts the rotation.
- **Tests needed:** `remapForDisplay` invariance (already partly covered by
  `ProjectionOrientationTest`); a declination-offset test asserting a known
  world azimuth lands on the expected device bearing; a "camera points at the
  vector under the reticle" round-trip.

### Step D — device vector → normalized camera coords (NDC)

- **Existing:** `projectDeviceVector()` (`ArScreen.kt:1210`) and
  `projectionParams()` (`ArScreen.kt:1201`): pinhole with
  `ndcX = (x/−z)/tanHFov`, `ndcY = (y/−z)/tanVFov`; rejects `z ≥ −0.01`
  (behind camera) and `distance > MAX_SCREEN_DISTANCE`.
- **Missing (the core CAM-1 gap):**
  - Real focal length / FOV instead of `VERTICAL_FOV_DEG = 56.0` (§3).
  - Principal-point offset (currently assumed dead-centre).
  - Lens distortion (currently none).
- **Sign/handedness risks:**
  - Y flip lives here (`1 − ndcY`); getting it wrong mirrors vertically.
  - `tanHFov = tanVFov · aspect` assumes square pixels and that FOV scales with
    the *viewport* aspect — wrong once the camera image aspect ≠ viewport
    aspect (the `FILL_CENTER` problem).
  - Using the wrong FOV scales all separations, so matches drift outward from
    centre (a pure scale error is the classic FOV bug).
- **Tests needed:** on-axis star projects to exactly `(cx, cy)`; a star at
  half-FOV projects to the frame edge; FOV-scaling test (double FOV → half the
  pixel offset); mirror test (a star to the observer's left must land on the
  correct screen side for the back camera).

### Step E — NDC → image pixels / screen pixels

- **Existing:** linear map to Compose pixels over `overlaySize`
  (`ArScreen.kt:1224`).
- **Existing (CAM-1e, pure only):** the **image↔display mapping**.
  `PreviewView.FILL_CENTER` center-crops the camera frame to fill the view;
  matching against detected points must run in the *image* frame (§1.5) and then
  map into the display frame (or vice-versa) using the crop/scale. CAM-1e adds
  the pure `CropScaleTransform` for exactly this (§9), tested for both
  directions — but it is **not wired** into this projection path or any renderer.
- **Sign/handedness risks:**
  - Crop direction: FILL_CENTER can crop either width or height depending on
    aspect; picking the wrong axis shifts everything.
  - DPI/px vs image-px: overlay is in dp-derived px; image is in sensor px.
  - Front/back camera mirroring: only the **back** camera is used
    (`DEFAULT_BACK_CAMERA`), which is not mirrored — but any future front-camera
    path would need an explicit horizontal flip.
- **Tests needed:** a crop/scale unit test for each aspect regime (image wider
  than view, taller than view, equal); a golden mapping a known image pixel to
  a known screen offset and back.

---

## 3. Camera intrinsics

### 3.1 Current state

| Intrinsic | Today | Source |
|---|---|---|
| Focal length / FOV | Hardcoded `VERTICAL_FOV_DEG = 56.0`; H-FOV = `atan(tanVFov·aspect)` | `ArScreen.kt:1276`, `projectionParams` |
| Sensor size | Not modeled | — |
| Principal point | Assumed image centre `(w/2, h/2)` | `projectDeviceVector` |
| Image resolution | Uses **viewport** (`overlaySize`), not the camera image | `calculateOverlay` |
| Display crop/scale | Ignored (`FILL_CENTER` crop unaccounted) | `CameraPreview` |
| Lens distortion | None | — |

### 3.2 How to obtain / approximate (CAM-1)

Read Camera2 `CameraCharacteristics` for the bound CameraX camera (via
`Camera2CameraInfo.from(cameraInfo)`), then:

- **FOV / focal length (required):** compute from
  `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` (mm) and
  `SENSOR_INFO_PHYSICAL_SIZE` (mm):
  `FOVₓ = 2·atan(sensorWidth / (2·f))`, likewise vertical. This replaces the
  hardcoded 56°. Fall back to 56° V-FOV only if characteristics are missing.
- **Sensor size (required as input to FOV):** `SENSOR_INFO_PHYSICAL_SIZE`,
  cross-checked against `SENSOR_INFO_ACTIVE_ARRAY_SIZE` and the crop from the
  chosen `ImageAnalysis` resolution.
- **Image resolution (required):** the `ImageAnalysis` output size
  (`ImageProxy.width/height` after analyzer rotation) — this, not the viewport,
  is the frame to match in.
- **Principal point (approximate for MVP):** image centre. Prefer
  `LENS_INTRINSIC_CALIBRATION` `[cx, cy]` when
  `LENS_INFO_AVAILABLE_APERTURES`/calibration data are present and
  `LENS_POSE_REFERENCE` indicates a real calibration; otherwise centre.
- **Display crop/scale (required):** derive from the ratio of the
  `ImageAnalysis` aspect to the `PreviewView` aspect under `FILL_CENTER`.
  Provide a pure function mapping image-px ↔ display-px.
- **Lens distortion (defer):** ignore radial distortion for CAM-1; most phone
  main cameras are <1–2 px near centre. Record `LENS_DISTORTION` if present but
  do not apply it in the MVP.

### 3.3 Required for CAM-1 minimum viable matching vs deferred

**Required (MVP):**

- Real per-device FOV (from focal + sensor), replacing the 56° constant.
- The analyzed image resolution and its aspect.
- Principal point = image centre.
- Image↔display crop/scale mapping (so predictions and detections share a
  frame).
- A documented refraction convention (§2, Step A).

**Deferred (post-MVP):**

- Lens distortion correction.
- Non-central principal point from `LENS_INTRINSIC_CALIBRATION`.
- Camera-to-IMU extrinsic rotation (assume identity; the CAM-1 calibration
  correction absorbs small residuals).
- Per-frame autofocus focal-length changes (main cameras vary little; sample
  focal length once per session unless it reports otherwise).

### 3.4 CAM-1b implementation status (this PR)

CAM-1b implements the §3.2 intrinsics contract as real, tested code but does **not** wire it into
rendering. `ArScreen.calculateOverlay()` / `projectionParams(viewport)` still call the legacy
fixed-FOV path (`VERTICAL_FOV_DEG = 56.0`, `core/astro-core/.../projection/Projection.kt`)
unchanged. The code below has **zero production call sites** as of this PR — this is intentional
(§8 note below).

**Pure model** — package `dev.pointtosky.core.astro.projection.camera` in `:core:astro-core`:

- `CameraIntrinsics` (`CameraIntrinsics.kt`): `horizontalFovDeg`, `verticalFovDeg`,
  `focalLengthMm?`, `sensorWidthMm?`, `sensorHeightMm?`, `principalPointXPx?`,
  `principalPointYPx?`, `source: CameraIntrinsicsSource`. Validates eagerly in `init {}`: both
  FOVs must be finite and satisfy `0 < fov < 180`. The optional physical metadata —
  `focalLengthMm`, `sensorWidthMm`, `sensorHeightMm` — must be finite and **strictly positive**
  when present (a physical dimension can never be zero). The optional principal-point image
  coordinates — `principalPointXPx`, `principalPointYPx` — are not physical dimensions; they must
  be finite and **non-negative** when present, since an image-coordinate axis legitimately starts
  at pixel `0`. Invalid values throw `IllegalArgumentException` — never silently clamped, per the
  CAM-1b contract.
- `CameraIntrinsicsSource`: `CAMERA_CHARACTERISTICS`, `CAMERA_INTRINSIC_CALIBRATION` (reserved,
  unused as of CAM-1b — see principal point below), `LEGACY_FALLBACK`.
- `fovDegFromFocalLength(sensorDimensionMm, focalLengthMm)` (`CameraFov.kt`), plus
  `horizontalFovDeg(...)`/`verticalFovDeg(...)` wrappers: pure `fov = 2 * atan(sensorDimensionMm /
  (2 * focalLengthMm))` in degrees. Rejects non-finite/non-positive inputs; for any valid input the
  result is always strictly within `0 < fov < 180` (`atan` is bounded within `(-π/2, π/2)`).
- `legacyFallbackCameraIntrinsics(imageWidthPx?, imageHeightPx?)`
  (`LegacyFallbackCameraIntrinsics.kt`): builds the explicit fallback value. Vertical FOV reuses the
  *same* `VERTICAL_FOV_DEG` constant `projectionParams` uses (both are `internal` in
  `:core:astro-core`, so this is a single source of truth, not a re-guessed literal). Horizontal FOV
  mirrors `projectionParams`'s own aspect-derived formula (`tanHFov = tanVFov * aspect`) when the
  analyzed image size is known, else defaults to the vertical FOV — an explicit, documented
  square-aspect policy, not a guess about physical lens geometry.

**Android provider** — package `dev.pointtosky.mobile.ar.camera` in `:mobile`:

- `CameraIntrinsicsProvider.resolve(cameraInfo, imageWidthPx, imageHeightPx):
  CameraIntrinsicsResolution` (`CameraIntrinsicsProvider.kt`) is the production entry point;
  `Camera2CameraIntrinsicsProvider` is the CameraX/Camera2-backed implementation.
- `CameraIntrinsicsResolution(intrinsics, fallbackReason: String?)` wraps the result — this
  intentionally deviates from returning a bare `CameraIntrinsics` so the fallback diagnostic
  reason survives to the caller (see fallback semantics below).
- Metadata is read via `Camera2CameraInfo.from(cameraInfo)` (`Camera2CharacteristicsSource.kt`),
  using exactly:
  - `CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS` (`FloatArray`, millimetres).
  - `CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE` (`SizeF`, millimetres) → width/height.
  - `LENS_INTRINSIC_CALIBRATION`, `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `SENSOR_INFO_PIXEL_ARRAY_SIZE`,
    and `LENS_DISTORTION` are **not read** in CAM-1b — see principal point below.
  (`Camera2CameraInfo.getCameraCharacteristic()` is gated by `ExperimentalCamera2Interop`, an
  AndroidX legacy Java-based `@RequiresOptIn`-style marker (`androidx.annotation.RequiresOptIn`),
  not Kotlin's native opt-in mechanism. It must be suppressed with `androidx.annotation.OptIn`, not
  `kotlin.OptIn` — the two annotations share identical call syntax
  (`@OptIn(ExperimentalCamera2Interop::class)`), so importing the wrong one is an easy, silent
  mistake: `kotlin.OptIn` compiles cleanly and even passes plain JVM unit tests, but Android Lint's
  `UnsafeOptInUsageError` check — which enforces this marker, not the Kotlin compiler — only
  recognizes `androidx.annotation.OptIn` and fails `:mobile:lintInternalDebug` without it.)
- No CameraX dependency bump was needed: `androidx.camera:camera-camera2:1.3.4` (already a
  `:mobile` dependency) provides `Camera2CameraInfo`/`ExperimentalCamera2Interop`; CAM-1b adds no
  new CameraX artifacts.
- Resolution logic (`resolveCameraIntrinsics`, `CameraIntrinsicsResolver.kt`) is isolated behind the
  `CameraCharacteristicsSource` adapter — a `CameraCharacteristicsSnapshot` plain data holder,
  decoupled from `CameraCharacteristics.Key` mechanics — so it is unit-tested with fake metadata:
  no real camera, no Robolectric.
- **Focal-length selection rule**: `selectFocalLengthMm` returns a `FocalLengthSelection`
  (`Resolved(focalLengthMm)` / `NoneValid` / `Ambiguous`) after filtering
  `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` down to finite, positive candidates. Only the **exactly one
  valid candidate** case resolves — `resolveCameraIntrinsics` then labels the result
  `CAMERA_CHARACTERISTICS`. Zero valid candidates is `NoneValid`. **Two or more** valid candidates
  is `Ambiguous` and also falls back: static `CameraCharacteristics` has no field saying which of
  several reported focal lengths the currently bound capture stream is actually using (that needs a
  live `CaptureResult`, out of CAM-1b's scope — no capture pipeline exists yet), so picking one
  value out of several — even a deterministic pick like the minimum — would risk mislabeling a
  guess as calibrated metadata. (Most phone main cameras report exactly one focal length; multiple
  values only occur on variable-optical-zoom lenses.)
- **Fallback semantics**: each of the following returns
  `CameraIntrinsicsResolution(legacyFallbackCameraIntrinsics(imageWidthPx, imageHeightPx),
  fallbackReason)`, i.e. `intrinsics.source == LEGACY_FALLBACK` plus a short, non-device-specific
  diagnostic string (no raw exception message or stack trace is logged or stored):
  - `CameraCharacteristicsSource.snapshot()` throws → `"camera_characteristics_unavailable"`.
  - no valid (finite, positive) focal length in the array — missing array, empty array, or all
    entries invalid → `"no_valid_focal_length"`.
  - more than one valid focal length in the array (ambiguous — see selection rule above) →
    `"ambiguous_focal_length"`.
  - sensor width/height missing, non-finite, or `<= 0` → `"missing_or_invalid_sensor_size"`.
  - the computed `CameraIntrinsics` itself fails validation (defensive; not expected given the
    upstream guards) → `"computed_intrinsics_invalid"`.

  A successful resolution has `fallbackReason == null` and
  `intrinsics.source == CAMERA_CHARACTERISTICS`.
- **Principal point**: always `null` in CAM-1b. `LENS_INTRINSIC_CALIBRATION` is deliberately not
  parsed — its coordinate mapping (which array indices, which reference frame, interaction with the
  active-array crop) is not yet confirmed against a real `ImageAnalysis` pipeline, and CAM-1b adds
  no such pipeline (see §7 non-goals, unchanged). CAM-1c/1d will initially use the analyzed image
  centre and revisit `LENS_INTRINSIC_CALIBRATION` once that image↔intrinsics mapping exists and can
  be tested.

**Renderer status (unchanged by this PR):** `ArScreen.calculateOverlay()` and
`projectionParams(viewport)` still call the legacy fixed `VERTICAL_FOV_DEG = 56.0` /
aspect-derived-horizontal-FOV path exclusively. `CameraIntrinsicsProvider` has zero production call
sites — no `ArViewModel`/`ArScreen`/`CameraPreview` code constructs or reads a
`Camera2CameraIntrinsicsProvider` yet. No `ImageAnalysis` use case, frame pipeline, timestamp
pairing, crop/scale mapping, star matcher, or `VisibleRealStarProvider` wiring was added.

---

## 4. Timestamp & synchronization

### 4.1 Current state

- **Attitude timestamp exists:** `RotationFrame.timestampNanos = event.timestamp`
  (`RotationFrame.kt:73`), from `SensorEvent.timestamp` (nanoseconds; on most
  devices `SystemClock.elapsedRealtimeNanos`, but the base clock is **not
  guaranteed** across sensors/vendors).
- **Frame timestamp does not exist:** the CameraX `Preview`-only path exposes no
  frame timestamp. `ImageProxy.imageInfo.timestamp` is unavailable because
  there is no `ImageAnalysis`.

### 4.2 Intended pairing (CAM-1 with an image pipeline)

1. Add an `ImageAnalysis` use case; each `ImageProxy` carries
   `imageInfo.timestamp` (ns) and `imageInfo.rotationDegrees`.
2. Buffer recent `RotationFrame`s in a small ring (e.g. last ~10, ~200 ms at
   `SENSOR_DELAY_GAME`).
3. For a captured frame, select the attitude sample **nearest in time** to the
   frame timestamp (linear-interpolate the rotation via slerp if the gap is
   large). Reject the pairing if the nearest attitude is older than a threshold
   (e.g. >50 ms) or the device is rotating fast (attitude rate over a gate).
4. Because sensor and camera clocks may not share a base, prefer treating both
   as `elapsedRealtimeNanos` and, if a systematic offset is detected, expose a
   single calibratable delta rather than assuming zero.

### 4.3 Safe CAM-1 fallback (no reliable pairing)

Star positions change slowly (~15°/hour ≈ 0.004°/s). So the MVP can avoid tight
sync entirely:

- **Require the device to be near-still** for a match attempt (gate on attitude
  angular rate below a small threshold, e.g. <1°/s), and use the **latest
  available** `RotationFrame` at the moment the frame is analyzed.
- Report the attitude-to-frame age in the output metadata so callers can reject
  stale matches.
- This trades a small, bounded pointing error for zero clock-alignment work and
  is sufficient to validate matching before investing in true sync.

### 4.4 CAM-1c implementation status (this PR)

CAM-1c adds the first real frame pipeline: an `ImageAnalysis` use case bound alongside `Preview`,
and a metadata-only extraction path. It does **not** implement §4.2's timestamp pairing, §4.3's
near-still matching gate, image↔display crop/scale mapping, or any matcher — those remain for
CAM-1d and later. The renderer (`ArScreen.calculateOverlay()` / `projectionParams(viewport)`)
still calls the legacy fixed `VERTICAL_FOV_DEG = 56.0` path exclusively, unchanged by this PR, and
`CameraIntrinsicsProvider`/`Camera2CameraIntrinsicsProvider` (CAM-1b) still has zero production call
sites — CAM-1c does not wire them together. That combination is left for CAM-1d.

**Pure model** — `CameraFrameMetadata` (`core/astro-core/.../projection/camera/CameraFrameMetadata.kt`),
package `dev.pointtosky.core.astro.projection.camera` in `:core:astro-core`, alongside CAM-1b's
`CameraIntrinsics`:

- Fields: `timestampNanos: Long`, `bufferWidthPx: Int`, `bufferHeightPx: Int`, `rotationDegrees:
  Int`, plus optional `cropRectLeftPx/TopPx/RightPx/BottomPx: Int?` — plain integers, never an
  Android `Rect`.
- Validated eagerly in `init {}`: `timestampNanos >= 0`; `bufferWidthPx > 0` and `bufferHeightPx >
  0`; `rotationDegrees` in `{0, 90, 180, 270}`; the four crop-rect fields are either all present or
  all absent, and when present must satisfy `left >= 0`, `top >= 0`, `left < right`, `top < bottom`,
  `right <= bufferWidthPx`, `bottom <= bufferHeightPx`. Invalid values throw
  `IllegalArgumentException`, never silently clamped — same convention as `CameraIntrinsics`.
- `bufferWidthPx`/`bufferHeightPx` are recorded exactly as `ImageProxy` reports them: **not** swapped
  based on `rotationDegrees`, and **not** assumed to equal any `PreviewView`/viewport size (§1.5/§3.3
  still list the image↔display crop/scale mapping as not implemented).

**Extraction seam** — package `dev.pointtosky.mobile.ar.camera` in `:mobile`
(`CameraFrameMetadataSource.kt`):

- `CloseableFrameMetadataSource` is a thin interface exposing exactly `timestampNanos`, `widthPx`,
  `heightPx`, `rotationDegrees`, the four optional crop fields, and `close()` — decoupled from
  `ImageProxy` mechanics so extraction logic is unit-tested with a plain fake
  (`FakeFrameMetadataSource` in tests), no real camera, no `ImageProxy` mock.
- `toCameraFrameMetadata()` is the pure mapping from those raw fields to a validated
  `CameraFrameMetadata`.
- `ImageProxyFrameMetadataSource` is the production adapter wrapping a real `ImageProxy`. It reads
  **exactly**: `imageProxy.imageInfo.timestamp`, `imageProxy.width`, `imageProxy.height`,
  `imageProxy.imageInfo.rotationDegrees`, and `imageProxy.cropRect.{left,top,right,bottom}`. It never
  reads `imageProxy.planes`, `imageProxy.image`, or any pixel row/stride — those APIs are not called
  anywhere in this slice's production or test code. `close()` delegates to `ImageProxy.close()`.

**Sink/publisher contract** — `CameraFrameMetadataSink.kt`, same package:

- `CameraFrameMetadataSink` is a one-method interface (`fun onFrame(metadata: CameraFrameMetadata)`).
- `CameraFrameMetadataProvider` is the production implementation: a `StateFlow<CameraFrameMetadata?>`
  (`latest`) updated by simple assignment (`_latest.value = metadata`) on every `onFrame` call. This
  gives latest-value-only semantics with no queue — the previous frame is discarded, never
  accumulated — and thread-safe publication for free (`StateFlow.value` assignment is atomic).
  `frameCount`/`failedFrameCount` are `AtomicLong` counters exposed via `debugState()` for the
  minimal debug readout (latest metadata + counts).
- `CameraPreview` owns one `CameraFrameMetadataProvider` per composition (`remember { ... }`), used
  today only for the throttled debug log (below). It has no other consumer yet; CAM-1d will decide
  how to expose it (e.g. through a small camera-session state) without this PR needing to guess at
  that shape.

**Analyzer** — `CameraFrameAnalyzer.kt`, same package: a CameraX `ImageAnalysis.Analyzer`. Its
`analyze(ImageProxy)` wraps the `ImageProxy` in `ImageProxyFrameMetadataSource` and delegates to the
internal, independently testable `analyzeSource(CloseableFrameMetadataSource)`:

```kotlin
internal fun analyzeSource(source: CloseableFrameMetadataSource) {
    try {
        metadataSink.onFrame(source.toCameraFrameMetadata())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        MobileLog.cameraFrameAnalysisFailed(e.javaClass.simpleName)
        onFrameFailure()
    } finally {
        source.close()
    }
}
```

`source.close()` runs in `finally`, so it executes exactly once per call regardless of outcome:
extraction success, extraction failure (invalid metadata throws `IllegalArgumentException`), or the
sink itself throwing. `CancellationException` is rethrown rather than swallowed (matching the CAM-1b
convention in `resolveCameraIntrinsics`); every other exception is caught so one bad frame cannot
crash the analyzer thread or the camera pipeline. The frame is never retained past this call — no
field stores the `ImageProxy`/source beyond the try block.

**Binding** — `mobile/src/main/java/dev/pointtosky/mobile/ar/CameraPreview.kt`:

- `ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()` —
  no target-resolution selector is configured; the analyzer observes whatever `ImageProxy.width` /
  `height` CameraX actually delivers (§5 "Resolution policy": do not hardcode a target resolution
  unless binding requires it, which it did not here).
- The analyzer runs on a dedicated `Executors.newSingleThreadExecutor()` — never the main thread —
  created inside the `DisposableEffect` that owns the camera binding.
- `Preview` and `ImageAnalysis` are bound together in one call first:
  `cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview,
  imageAnalysis)`. No `unbindAll()` is called before binding (a prior revision did; that call was
  removed together with the disposal fix below — see "Lifecycle ownership and the bind/dispose race").
- If that combined bind throws `IllegalArgumentException` — the device/config rejects the
  `Preview` + `ImageAnalysis` combination (e.g. a legacy-level camera, or no back camera on a
  camera-less device/emulator) — `ImageAnalysis` is abandoned and a **Preview-only** bind is
  retried: `cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
  preview)`. This preserves the AR camera view on a device that still supports `Preview` alone even
  though it rejects the optional metadata use case. See "Preview-only fallback" below.
- The existing `catch (_: IllegalStateException)` on the *combined* bind (lifecycle stopped before
  binding) is preserved unchanged and does **not** retry Preview-only — the lifecycle owner itself
  may already be gone, so there is nothing to bind into. Both the `IllegalStateException` and
  `IllegalArgumentException` branches clear the just-created `ImageAnalysis`'s analyzer (nothing was
  bound, so there is nothing to unbind) and log a bind-failure category via `MobileLog`.
- `PreviewView.ScaleType.FILL_CENTER` and all existing `Preview` lifecycle behavior are unchanged.

### Lifecycle ownership and the bind/dispose race

**This composable, not the bound `Activity` lifecycle, owns whichever use case(s) end up bound —
combined `Preview` + `ImageAnalysis`, or Preview alone after a fallback — and the analysis
executor.** Navigating away from the AR screen does not necessarily stop the `Activity` lifecycle
CameraX is bound to, so relying on that lifecycle (or on a future `unbindAll()` call the *next* time
`CameraPreview` enters composition) to release the camera would leave the old session's use case(s)
bound, its executor already shut down out from under them, and any late analyzer task submission
rejected. `CameraPreview` therefore performs its own explicit teardown on disposal, coordinated by
`dev.pointtosky.mobile.ar.camera.CameraSessionLifecycle` — a small, CameraX-free state machine kept
in a package alongside the other camera helpers precisely so it is JVM-testable
(`CameraSessionLifecycleTest.kt`) without a real camera, `ImageAnalysis`, or `ProcessCameraProvider`.
Ownership of the actual `ProcessCameraProvider`/`Preview`/`ImageAnalysis` instances stays local to
one `DisposableEffect` invocation, captured by closure rather than stored in a separate holder type.

**The bind/dispose race is closed by one lock-serialized state machine, not by independently-atomic
fields.** An earlier revision guarded `disposed` with an `AtomicBoolean` and the registered cleanup
closure with `@Volatile` separately. That left a check-then-act gap in `confirmBound`: `onDispose`
could complete `markDisposed()` *and* `cleanupAndShutdown()` — including shutting down the executor —
in the window between `confirmBound` reading `disposed` and assigning `cleanupUseCases = cleanup`.
That interleaving left a newly bound CameraX use case registered for a cleanup that would now never
run, with the executor already gone. Making `disposed` and the cleanup closure independently atomic
cannot close this gap — only serializing the *transition* (decide disposed-or-not, decide who owns
cleanup, decide cleaned-up-or-not) through a single lock can. `CameraSessionLifecycle` holds
`disposed: Boolean`, `useCasesCleanedUp: Boolean`, and `cleanupUseCases: (() -> Unit)?` as one state
machine guarded by one `private val lock = Any()`; every public method acquires `lock` only for its
bookkeeping decision and releases it *before* invoking any caller-supplied closure, so a slow
`unbind()`/`shutdownNow()` call never blocks the other side's state transition and CameraX calls
never run while `lock` is held. `cleanupUseCases` holds whichever cleanup closure the successful bind
registered — the combined-mode closure (clear analyzer, unbind both use cases) or the Preview-only
fallback closure (unbind `Preview` alone) — `CameraSessionLifecycle` itself does not need to know
which; it only guarantees that closure runs exactly once, from exactly one caller.

**Executor shutdown is a *separate* idempotency axis from the use-case cleanup above, tracked by its
own `executorShutdown: Boolean` field under the same `lock`.** This exists specifically for the
Preview-only fallback (see below): when the combined bind is rejected with
`IllegalArgumentException`, `ImageAnalysis` — and its dedicated executor — is abandoned right there,
*before* any Preview-only fallback bind is even attempted, because there is no use case bound yet at
that point for `confirmBound`'s cleanup closure to unbind. `session.shutdownExecutorOnce {
analysisExecutor.shutdownNow() }` lets that failure-handling code shut the executor down immediately;
`cleanupAndShutdown` (below) calls the same `shutdownExecutorOnce` as its own final step, so an early
call from the failure branch and a later call from `onDispose` still shut the executor down exactly
once between them, regardless of call order — this is what closes CAM-1c's follow-up "do not let both
the failure branch and `onDispose` call `shutdownNow` twice" requirement.

**Disposal order**, run from `onDispose`:

1. `session.markDisposed()` — synchronized transition marking the session disposed, so an in-flight
   bind coroutine cannot complete a *lasting* bind (see the race below).
2. `job.cancel()` — cancels the binding coroutine's `Job`. This is best-effort, not the actual
   guard: coroutine cancellation only takes effect at a suspension point, and nothing between
   `getCameraProvider()` resolving and either `bindToLifecycle()` call returning suspends, so a
   cancelled job alone cannot stop an already-in-flight bind. Step 1's explicit flag is what
   actually prevents it.
3. `session.cleanupAndShutdown { analysisExecutor.shutdownNow() }` — under `lock`, claims whatever
   cleanup a successful bind registered (setting `useCasesCleanedUp = true` and reading+clearing
   `cleanupUseCases` in the same synchronized block as the idempotency check) and releases the lock;
   only then, outside the lock, does it invoke that cleanup — combined-mode
   (`imageAnalysis.clearAnalyzer()` then `cameraProvider.unbind(preview, imageAnalysis)`) or
   Preview-only-fallback-mode (`cameraProvider.unbind(preview)`), **never** `unbindAll()`, so a
   sibling camera owner elsewhere in the app would not be affected — and, in a `finally` block,
   shuts the executor down via `shutdownExecutorOnce`. Because `useCasesCleanedUp` is committed to
   `true` inside the lock *before* the cleanup closure runs, calling `cleanupAndShutdown` more than
   once — or when no bind ever completed — never double-unbinds, and `shutdownExecutorOnce`'s own
   independent guard means it never double-shuts-down either, even if the use-case cleanup closure
   itself throws (see "Cleanup exception semantics" below).

**The bind/dispose race** has three windows, all closed by the same lock:

- *Early*: disposal happens while `getCameraProvider()` is still suspended, or in the gap right
  after it resolves but before the combined `bindToLifecycle()` is called. The coroutine checks
  `session.isDisposed` immediately after the provider resolves and returns without creating or
  binding any use case if disposal already happened.
- *Between combined failure and the fallback attempt*: disposal happens after the combined bind is
  rejected (`IllegalArgumentException`) but before the Preview-only retry. The coroutine re-checks
  `session.isDisposed` right before that second `bindToLifecycle(lifecycleOwner, selector, preview)`
  call and skips it entirely if disposal already happened — the same explicit-guard reasoning as the
  early window: neither this check nor the `bindToLifecycle` call it guards suspends, so a cancelled
  job cannot substitute for it.
- *Late*: disposal happens *during* a synchronous `bindToLifecycle()` call itself — combined or
  Preview-only — i.e. the bind completes successfully but only after `onDispose` has already run.
  Immediately after either successful bind, the coroutine calls `session.confirmBound { <unbind the
  use case(s) that specific bind bound> }`. Under `lock`, `confirmBound` checks
  `disposed || useCasesCleanedUp`: if either is already true — disposal has claimed cleanup, or has
  already finished it — it releases the lock and runs the passed cleanup closure itself,
  immediately, unbinding the just-bound use case(s) right there, and returns `false`, so neither
  `MobileLog.cameraAnalysisBound()` nor `MobileLog.cameraPreviewBoundWithoutAnalysis()` is logged for
  a session that never actually stayed alive. If neither is true, it registers `cleanupUseCases =
  cleanup` inside the same synchronized block (so `onDispose`'s later `cleanupAndShutdown` is
  guaranteed to see it) and returns `true`. Because both `confirmBound` and `cleanupAndShutdown` make
  their decision inside the same lock, exactly one side ever invokes a given cleanup closure for
  every possible thread interleaving — verified directly by a two-thread, barrier-coordinated,
  2000-iteration race test in `CameraSessionLifecycleTest` that asserts the cleanup and shutdown
  callbacks each fire exactly once regardless of which side wins.
- *Duplicate registration*: a second `confirmBound` call on a still-live session (before disposal)
  throws `IllegalStateException` rather than silently overwriting the first registered cleanup —
  `confirmBound`'s `check(cleanupUseCases == null)` runs inside the same synchronized block as the
  registration itself, so this too is race-free. `CameraPreview` never calls `confirmBound` more
  than once per bind coroutine — the combined-success and Preview-only-fallback-success branches are
  mutually exclusive and each `return@launch`es afterward — so this is a defensive contract
  violation guard, not a path exercised in normal operation.

**Cleanup exception semantics**: if the use-case cleanup closure `cleanupAndShutdown` invokes (or the
one `confirmBound` invokes immediately on the late-dispose path) throws, that exception propagates to
the caller — it is not swallowed. `cleanupAndShutdown` still guarantees `shutdownExecutorOnce` runs,
via a `finally` block around the cleanup invocation, and because `useCasesCleanedUp` was already
committed to `true` inside the lock *before* the cleanup ran, a subsequent `cleanupAndShutdown` call
is still a no-op for the use-case side even after the first one threw; `executorShutdown`'s own
independent guard means the executor itself is never shut down twice regardless.

**Failed binds**: if the combined `bindToLifecycle` throws and the Preview-only fallback is either
skipped (disposed) or also throws, `session.confirmBound` is never called at all on that coroutine
run — no cleanup is registered, so there is no stale session reference for a later `onDispose` to act
on. `onDispose`'s `cleanupAndShutdown` still runs (there is simply nothing registered to unbind) and
still shuts the executor down via `shutdownExecutorOnce` — a no-op if the failure-handling code
already did so.

**Executor shutdown uses `shutdownNow()`, not `shutdown()`**, for prompt teardown on navigation away
*or* on a rejected combined bind. By the time `analysisExecutor.shutdownNow()` runs — whether from
`session.shutdownExecutorOnce` called early out of the combined-bind `IllegalArgumentException`
branch, or later from `onDispose`'s `cleanupAndShutdown` — the analyzer has already been cleared, so
CameraX will not submit further analyzer tasks through it. `shutdownNow()` discards at most one
already-queued (not yet started) metadata-extraction task rather than running it — `ImageAnalysis`
with `STRATEGY_KEEP_ONLY_LATEST` on a single-thread executor never has more than one task in flight or
queued at a time. That discarded task only ever reads timestamp/size/rotation and closes the frame —
never pixel data — so discarding it is safe; CameraX's own use-case teardown (triggered by whichever
`unbind` call applies) releases the underlying buffer regardless. A task that is already *running*
when `shutdownNow()` is called completes normally: `CameraFrameAnalyzer.analyzeSource` performs no
blocking/interruptible operation, so the interrupt request does not truncate its `finally`-closed
extraction.

All CameraX provider bind/unbind calls (both `bindToLifecycle` attempts, `confirmBound`'s cleanup
closure, and `onDispose`'s `cleanupAndShutdown`) run on `Dispatchers.Main` or Compose's disposal
callback — i.e. the main thread — matching CameraX's requirement that provider bind/unbind
operations stay off background threads.

### Preview-only fallback when ImageAnalysis binding is unsupported

Some devices/configurations reject the combined `Preview` + `ImageAnalysis` use-case combination
(`bindToLifecycle` throws `IllegalArgumentException`) even though they support `Preview` alone —
e.g. a `LEGACY`-level Camera2 hardware level with a restrictive supported-combination table. Before
this fallback existed, that rejection left *both* use cases unbound, so the AR screen went
permanently black on such a device even though nothing about the AR overlay itself required
`ImageAnalysis` (§0: it feeds only the metadata-only pipeline's throttled debug log — no production
renderer call site reads it).

**Flow**, entirely inside the single bind coroutine, on `Dispatchers.Main`:

1. Attempt the combined bind. `IllegalStateException` (lifecycle already stopping) is handled exactly
   as before — clear the analyzer, `shutdownExecutorOnce`, log `camera_analysis_bind_failed(reason =
   "illegal_state")`, and return; **no fallback is attempted**, because the lifecycle owner itself may
   already be gone, so there is nothing left to bind Preview into either.
2. `IllegalArgumentException` on the combined bind: clear the analyzer, `shutdownExecutorOnce` (see
   above), log `camera_analysis_bind_failed(reason = "illegal_argument_fallback_preview_only")`, then
   re-check `session.isDisposed` (the "between combined failure and the fallback attempt" race window
   above) before retrying with `cameraProvider.bindToLifecycle(lifecycleOwner,
   CameraSelector.DEFAULT_BACK_CAMERA, preview)` — Preview alone, the same `Preview` instance from
   the failed combined attempt, never a new one.
3. If that Preview-only bind succeeds, `session.confirmBound { cameraProvider.unbind(preview) }`
   registers a cleanup that unbinds **Preview only** — it never references or unbinds
   `imageAnalysis`, which was never bound in this path. `MobileLog.cameraPreviewBoundWithoutAnalysis()`
   is logged only if `confirmBound` returns `true` (not a late-dispose race loss) — deliberately a
   different event from `MobileLog.cameraAnalysisBound()`, so the two bind outcomes (frame metadata
   available vs. preview-only) are distinguishable in logs/telemetry.
4. If the Preview-only bind also throws — `IllegalStateException` or `IllegalArgumentException` —
   that is logged as `camera_analysis_bind_failed(reason = "illegal_state_fallback_failed")` or
   `"illegal_argument_fallback_failed"` respectively, and the coroutine returns without calling
   `confirmBound`: no cleanup is ever registered, matching the "Failed binds" behavior above, and
   nothing is left running (the executor was already shut down in step 2).

**What never changes** regardless of which path is taken: `cameraProvider.unbind(...)` is always
called with exactly the use case(s) this composition itself bound — never `unbindAll()` — and
`onDispose`'s disposal order (mark disposed → cancel job → `cleanupAndShutdown`) is identical.
`CameraPreview` does not know or care, at the `onDispose` call site, whether combined or Preview-only
binding succeeded; `CameraSessionLifecycle`'s registered `cleanupUseCases` closure already captures
that.

**Timestamp contract:** `CameraFrameMetadata.timestampNanos` is exactly
`ImageProxy.imageInfo.timestamp` — camera-clock nanoseconds, **not** wall-clock time. It must never
be compared to `System.currentTimeMillis()`. This slice makes **no claim** that it shares a clock
base with `SensorEvent.timestamp` (§4.1); measuring and pairing the two, per §4.2, is CAM-1d's job.
No interpolation or sensor matching is implemented here.

**Logging** (`MobileLog`, `dev.pointtosky.mobile.logging`): `cameraAnalysisBound()` (one event per
successful bind), `cameraAnalysisBindFailed(reasonCategory)` (bind failure category —
`"illegal_state"` / `"illegal_argument"`), `cameraFrameMetadata(widthPx, heightPx, rotationDegrees,
frameCount)` (throttled — logged on frame 1 and every 30th frame after, via
`CameraFrameMetadataProvider`, never once per frame), and `cameraFrameAnalysisFailed(reasonCategory)`
(analyzer failure category — the thrown exception's simple class name only, never a message or stack
trace, never device-specific detail).

**What CAM-1c explicitly does not do:** wire `CameraIntrinsicsProvider` to a bound `CameraInfo`;
change `ArScreen`/`projectionParams` rendering; implement the image↔display crop/scale mapping (§2
Step E); pair frame timestamps with `RotationFrame` (§4.2); add a detector, matcher, or any CV
library; read `imageProxy.planes`/`image`; or add user-facing capture controls.

### 4.5 CAM-1d implementation status (this PR)

CAM-1d measures whether the two clocks from §4.1 appear comparable and implements a bounded
nearest-sample pairing seam. It does **not** assume the two clocks share a base going in — pairing
results, not an assumption, are what feed the diagnostic compatibility status below — and it does
**not** wire the resulting pairs into rendering or matching.

**Timestamp sources (unchanged from CAM-1c/existing rotation pipeline):**

- Sensor: `RotationFrame.timestampNanos = event.timestamp` (`RotationFrame.kt`) — raw
  `SensorEvent.timestamp`, nanoseconds, propagated with no adjustment. CAM-1d does not add a second
  sensor stream or substitute `System.nanoTime()`: the same `event.timestamp` value used to build
  `RotationFrame` is copied, in the same `onSensorChanged` callback, into a `TimedRotationSample`.
- Camera: `CameraFrameMetadata.timestampNanos` — exactly `ImageProxy.imageInfo.timestamp` (CAM-1c,
  unchanged).
- Rotation matrix: `TimedRotationSample.rotationMatrix` is the same **display-remapped**
  `RotationFrame.rotationMatrix` array already consumed by `calculateOverlay`/`deviceRollDegrees` —
  not a second, raw-sensor-coordinate copy. `RotationSampleHistory` defensively copies it on
  ingestion, so later reuse of the shared array reference at the `RotationFrame.kt` call site cannot
  corrupt history state.

**Pure models** — `:core:astro-core`, package `dev.pointtosky.core.astro.projection.camera` (no
Android/CameraX types anywhere in this list):

- `TimedRotationSample(timestampNanos, rotationMatrix)` — `timestampNanos >= 0`, `rotationMatrix`
  exactly 9 elements, both validated eagerly (`TimedRotationSample.kt`).
- `FrameRotationPair(frame, rotation, deltaNanos)` and the sealed `FrameRotationPairingResult`
  (`Paired`, `NoSamples`, `OutsideTolerance`, `ClockMismatchSuspected`) (`FrameRotationPairing.kt`).
- `pairFrameToNearestRotation(frame, samples, maxAllowedDeltaNanos, clockMismatchThresholdNanos)` —
  pure, deterministic, retains nothing past the call. **Selection:** minimum
  `abs(frame.timestampNanos - sample.timestampNanos)`, overflow-safe (saturating subtract/abs helpers
  in the same file — timestamps are validated non-negative, so overflow cannot occur today, but the
  arithmetic does not rely on that holding forever). **Tie-break:** when two samples are equidistant
  in time from the frame, the **earlier** sample (smaller `timestampNanos`) is preferred,
  unconditionally — no interpolation is performed either way.
  **Classification:** `delta <= MAX_PAIR_DELTA_NANOS` → `Paired`; `delta >
  CLOCK_MISMATCH_THRESHOLD_NANOS` → `ClockMismatchSuspected`; otherwise → `OutsideTolerance`. A frame
  outside `MAX_PAIR_DELTA_NANOS` is never paired, regardless of which of the latter two results it
  gets.
- `RotationSampleHistory(capacity)` (`RotationSampleHistory.kt`) — a single-lock-guarded, timestamp-
  sorted buffer capped at `capacity` (never an unbounded list/queue). Out-of-order `add()` calls are
  still sorted correctly; when `capacity` is exceeded, the **smallest**-timestamp sample(s) are
  evicted first. Duplicate timestamps are preserved (never deduplicated); an exact-timestamp
  `nearest()` query against multiple duplicates resolves to the most-recently-`add`-ed one (delta is
  `0` for all of them, so "earlier" does not disambiguate — arrival order does). Both `add()` and
  `nearest()`/`snapshot()` defensively copy the `FloatArray` matrix, so neither side can corrupt the
  other's state by mutating an array it still holds a reference to.
- `TimestampSyncDiagnostics`/`TimestampSyncDebugState`/`TimestampCompatibility`
  (`TimestampSyncDiagnostics.kt`) — see the compatibility-state policy below.

**Thresholds** — one place, `TimestampSyncConfig` (`TimestampSyncConfig.kt`, same package):

| Constant | Value | Rationale |
|---|---|---|
| `MAX_PAIR_DELTA_NANOS` | 50 ms | `SENSOR_DELAY_GAME` nominally delivers ~every 20 ms; 50 ms covers a couple of sensor ticks of jitter plus scheduling latency on either stream while staying well below star-field angular drift (§4.3: ~0.004°/s). |
| `CLOCK_MISMATCH_THRESHOLD_NANOS` | 5 s | Two orders of magnitude above `MAX_PAIR_DELTA_NANOS`, so an ordinary tolerance rejection is never misclassified as a clock-base mismatch; a delta this large cannot plausibly arise from scheduling jitter between two streams sharing a real time base. |
| `ROTATION_HISTORY_CAPACITY` | 120 | ~2 s of history at ~50 Hz nominal `SENSOR_DELAY_GAME` — comfortably wider than `MAX_PAIR_DELTA_NANOS` without growing unbounded. |
| `MIN_OBSERVATIONS_FOR_COMPATIBLE` | 5 | Consecutive in-tolerance pairs required before claiming `COMPATIBLE_OBSERVED` — enough to rule out a one-off coincidence without over-claiming after a single lucky pair. |
| `MIN_CONSECUTIVE_MISMATCHES_FOR_SUSPECTED` | 3 | Consecutive clock-mismatch-scale deltas required before claiming `MISMATCH_SUSPECTED` — one isolated bad sample must not flip the whole session's verdict. |

These are conservative starting points chosen from first principles (sensor delivery rate, star-field
drift rate, order-of-magnitude separation), **not** values tuned against one physical device — see
the device-gate note below.

**Compatibility-state policy** (`TimestampSyncDiagnostics`, conservative by design — this is a
diagnostic inference, never proof):

- Starts `UNKNOWN`.
- A run of `MIN_OBSERVATIONS_FOR_COMPATIBLE` **consecutive** `Paired` results moves the state to
  `COMPATIBLE_OBSERVED` — unless the state is already `MISMATCH_SUSPECTED`, which is treated as the
  stronger claim and is not silently overwritten by a handful of subsequent good pairs.
  `OutsideTolerance`/`NoSamples` are neutral: they neither extend nor reset the "consecutive
  compatible" streak, since neither carries direct evidence about the clock base (only that a sample
  was too far away, or that none existed yet).
  A `ClockMismatchSuspected` result resets the "consecutive compatible" streak to zero.
- A run of `MIN_CONSECUTIVE_MISMATCHES_FOR_SUSPECTED` **consecutive** `ClockMismatchSuspected` results
  moves the state to `MISMATCH_SUSPECTED`. A `Paired` result resets the "consecutive mismatch" streak
  to zero — one isolated bad sample does not flip the session's verdict.
- Running `minAbsDeltaNanos`/`maxAbsDeltaNanos`/`meanAbsDeltaNanos` are updated from every result that
  carries a delta (`Paired`, `OutsideTolerance`, `ClockMismatchSuspected`), not only successful pairs,
  so they reflect the full observed delta distribution, not just the paired subset. `NoSamples`
  carries no delta and leaves them unchanged. All statistics are running counters/min/max/sum — bounded
  and cheap regardless of session length; no percentile/rolling-window tracking is implemented (marked
  optional in scope, intentionally omitted for this slice).

**Integration** — `dev.pointtosky.mobile.ar.camera.CameraTimestampSynchronizer`
(`CameraTimestampSynchronizer.kt`): owns one `RotationSampleHistory` and one `TimestampSyncDiagnostics`,
publishes only the latest `FrameRotationPairingResult` via a `StateFlow` (never a queue of every
pair). It is callback-driven and owns no coroutine/`Job` of its own — `onRotationSample`/
`onCameraFrame` are called directly and synchronously from wherever a new sample already arrives, so
there is no separate collector coroutine to manage or leak:

- `rememberRotationFrame` (`RotationFrame.kt`) gained an optional `onRotationSample:
  (TimedRotationSample) -> Unit = {}` parameter, invoked once per sensor sample from inside the
  existing `onSensorChanged` callback, right after building that call's `RotationFrame` — the
  smallest seam that copies recent production rotation frames into the history without a second
  sensor stream. Defaults to a no-op, so no other caller of `rememberRotationFrame` is affected.
- `CameraPreview` (`CameraPreview.kt`) gained an optional `onFrameMetadata: (CameraFrameMetadata) ->
  Unit = {}` parameter. Internally, the `ImageAnalysis.Analyzer`'s sink now forwards each extracted
  frame to **both** the existing, `remember`ed `CameraFrameMetadataProvider` (unchanged throttled
  debug log) and `onFrameMetadata` — never instead of the provider. This does not broaden
  `CameraPreview`'s ownership: its bind/dispose lifecycle, `CameraSessionLifecycle` usage, and
  executor management are all unchanged; the only addition is one extra forward of a value it
  already extracts. `CancellationException` propagation is inherited unchanged from
  `CameraFrameAnalyzer.analyzeSource` (CAM-1c, already tested in `CameraFrameAnalyzerTest`): if the
  sink — including this forward — throws `CancellationException`, `analyzeSource` rethrows it rather
  than swallowing it; any other exception is caught, logged via `cameraFrameAnalysisFailed`, and does
  not crash the analyzer thread.
- `ArScreen` owns one `CameraTimestampSynchronizer` (`remember`ed), passes
  `timestampSynchronizer::onRotationSample` to `rememberRotationFrame` and
  `timestampSynchronizer::onCameraFrame` to `CameraPreview`'s `onFrameMetadata`, and calls
  `timestampSynchronizer.dispose()` from its own top-level `DisposableEffect(Unit)`'s `onDispose`.
  **Ownership is terminal, not reusable:** `dispose()` is a one-way transition — after it,
  `onRotationSample`/`onCameraFrame` are permanently no-ops (not even a `NoSamples` result is
  published) — because a new `ArScreen` composition receives a brand-new `remember`ed
  `CameraTimestampSynchronizer` instance, so no session ever needs to "clear and keep going" on the
  same instance. This is what actually prevents a stale rotation sample from a previous AR session
  pairing with the first camera frame of a new one (§8 "leave/re-enter AR") — not a same-instance
  reset.
  **Disposal is serialized with session activity through one internal lock**, not independent
  atomics: `onRotationSample`, `onCameraFrame`, and `dispose` each hold that lock for their entire
  check-disposed → read/mutate-state transition, so a callback already in flight on another thread
  when `dispose()` runs either completes and publishes before `dispose()` acquires the lock (and
  `dispose()` then clears its effect), or observes `disposed == true` once it acquires the lock after
  `dispose()` (and does nothing) — there is no interleaving where a late callback publishes a result
  or updates diagnostics after disposal has completed. `MobileLog` calls are made only after the lock
  is released, from an immutable snapshot decided while holding it, so logging never happens while
  the lock is held. `debugState()`/`latestResult` are exposed for future diagnostics UI but are not
  consumed by rendering or matching in this PR.

**Logging** (`MobileLog`, throttled/categorical, never per-frame): `timestampSyncSessionStarted()`
(once, on the first camera frame observed), `timestampSyncFirstPair(deltaMillis)` (once, on the first
successful pair), `timestampSyncSummary(deltaMillis, pairedCount, rejectedCount, compatibility)`
(every 30th processed camera frame), `timestampSyncClockMismatchSuspected(deltaMillis)` (once, on the
transition into `MISMATCH_SUSPECTED`), `timestampSyncUnavailableNoSamples()` (once, on the first frame
with no rotation samples to pair against). None of these log a matrix, a raw device identifier, an
exception message, or a full timestamp stream — only millisecond-rounded deltas and bounded counters/
categories.

**Physical-device gate:** device measurement (camera buffer dimensions, frame/sensor timestamp
progression, first 20–50 nearest deltas, min/mean/max absolute delta, paired-vs-rejected counts, and
resulting compatibility status, across stationary/slow-pan/fast-pan/leave-re-enter/orientation
scenarios) was **not** performed as part of this PR. Whether camera and rotation-sensor timestamps
are actually comparable on any given device — and whether `MAX_PAIR_DELTA_NANOS`/
`CLOCK_MISMATCH_THRESHOLD_NANOS` above are well-calibrated for it — remains an open device gate; the
thresholds in this PR are conservative first-principles defaults, not device-validated values, and
must not be read as "confirmed compatible on Android" from this PR alone.

**What CAM-1d explicitly does not do:** interpolate or SLERP rotation matrices; implement the
image↔display `CropScale` mapping (§2 Step E, still open); wire `CameraIntrinsicsProvider` into
rendering (`ArScreen`/`projectionParams` still use the legacy fixed `VERTICAL_FOV_DEG = 56.0`
exclusively, unchanged); feed `FrameRotationPairingResult`/`FrameRotationPair` into rendering, the
overlay, or any matcher; add a detector or CV library; add persistent timestamp storage; or compare
either timestamp to `System.currentTimeMillis()`.

---

## 5. Matching inputs / outputs (CAM-1 data model)

These are **proposed** shapes to live in a new pure module (suggest
`:core:catalog` visibility/render neighborhood or a new `:core:camera-match`
Kotlin module) so they are unit-testable without Android. Field names are
illustrative; types are the contract.

### 5.1 Input model

```kotlin
/** Everything CAM-1 needs to attempt a match for one frame. Pure data. */
data class CameraMatchInput(
    // Visible star candidates (from VF-1 / VF-2a): equatorial J2000 + mag.
    val candidates: List<VisibleRealStar>,      // dev.pointtosky.core.catalog.visibility.render.VisibleRealStar

    // Camera intrinsics for the analyzed frame (see §3).
    val intrinsics: CameraIntrinsics,

    // Device attitude paired with the frame (see §4).
    val attitude: DeviceAttitude,

    // Frame metadata (resolution, timestamp, rotation, crop mapping).
    val frame: FrameMetadata,

    // Optional detected bright points in IMAGE pixels (§1.5). Empty = predict-only.
    val detectedPoints: List<ImagePoint> = emptyList(),

    // Observing context for RA/Dec → Alt/Az.
    val lstDeg: Double,
    val latDeg: Double,
    val refraction: Boolean = false,             // fixed convention, see §2 Step A
)

data class CameraIntrinsics(
    val hFovDeg: Double,
    val vFovDeg: Double,
    val principalPoint: ImagePoint,              // ≈ image centre for MVP
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val distortion: Distortion? = null,          // null = pinhole (MVP)
)

data class DeviceAttitude(
    val worldToDevice: FloatArray,               // 3×3, world(ENU,true-north)→device
    val trueNorthCorrected: Boolean,             // must be true for matching
    val timestampNanos: Long,
)

data class FrameMetadata(
    val timestampNanos: Long,
    val rotationDegrees: Int,                     // ImageProxy.imageInfo.rotationDegrees
    val attitudeAgeNanos: Long,                   // frame.ts − attitude.ts (§4.3)
    val imageToDisplay: CropScale,                // §2 Step E mapping
)

data class ImagePoint(val u: Double, val v: Double)
```

`VisibleRealStar` is reused verbatim from VF-2a
(`raDeg, decDeg, mag, bv?, hip?, name?`) so CAM-1 consumes VF-1's output
directly via `VisibleRealStarProvider.snapshot(...)`.

### 5.2 Output model

```kotlin
sealed interface CameraMatchResult {

    /** A usable calibration correction was found. */
    data class Success(
        // Small corrective rotation to apply after worldToDevice (§2 Step C),
        // expressed as yaw/pitch/roll deltas in degrees (or a quaternion).
        val correction: AttitudeCorrection,
        val confidence: Double,                  // 0..1
        val matches: List<StarMatch>,            // candidate ↔ detected/predicted
        val residualRmsDeg: Double,              // angular RMS after correction
    ) : CameraMatchResult

    /** No reliable match; explains why. */
    data class Failure(val reason: FailureReason) : CameraMatchResult
}

data class AttitudeCorrection(
    val yawDeg: Double,
    val pitchDeg: Double,
    val rollDeg: Double,
)

data class StarMatch(
    val candidateIndex: Int,                     // index into CameraMatchInput.candidates
    val hip: Int?,                               // catalog identity when known
    val predicted: ImagePoint,                   // where the star was expected
    val detected: ImagePoint?,                   // matched detection, null if predict-only
    val residualDeg: Double,
)

enum class FailureReason {
    TOO_FEW_CANDIDATES,        // not enough visible stars in frame
    TOO_FEW_DETECTIONS,        // detector found nothing usable
    DEVICE_MOVING,             // attitude rate gate (§4.3)
    STALE_ATTITUDE,            // attitudeAgeNanos over threshold
    AMBIGUOUS,                 // multiple equally-good solutions
    NO_CONVERGENCE,            // solver did not converge
    LOW_ALTITUDE,             // frame below horizon / no sky
}
```

**Notes:**

- If `detectedPoints` is empty, CAM-1 runs in **predict-only** mode: it returns
  the projected positions of the candidates (no correction), which is enough to
  build/verify the pipeline before any computer vision exists.
- The correction is intentionally a small rotation, not full intrinsics
  re-estimation — it absorbs residual IMU/declination/extrinsic bias.

---

## 6. Test strategy

All of the following can be pure JVM unit tests (no device), consistent with
the existing `mobile/.../ar/*Test.kt` and `core/astro*/.../*Test.kt` suites.

1. **RA/Dec → Alt/Az sanity** — extend
   `EquatorialHorizontalTransformTest`: add a round-trip
   (`altAzToRaDec∘raDecToAltAz ≈ id`, refraction off) and 2–3 absolute goldens
   (named star Alt/Az at a fixed UTC + site, tolerance ~0.05°). Pin the
   refraction convention with an explicit on/off case.
2. **Projection centre** — a star exactly on the optical axis (device `−Z`)
   projects to the principal point `(cx, cy)`; a star at `+half-HFOV` lands on
   the horizontal frame edge. Assert on `projectDeviceVector` with real
   intrinsics.
3. **Display rotation cases** — reuse/extend `ProjectionOrientationTest`
   ("polyline shape stable across rotations") and `DeviceRollDegreesTest`
   (upright→0°, +90° CW→−90°, +90° CCW→+90°, near-zenith→fallback) for all four
   `Surface.ROTATION_*` values, including image `rotationDegrees` interplay.
4. **Mirrored / handedness mistakes** — a star to the observer's physical left
   must project to the correct screen side for the **back** camera; assert the
   sign of the ENU→device→NDC chain (a deliberate "flip X" mutation must fail
   the test). Include a magnetic-vs-true-north offset case that fails if
   `correctedForTrueNorth` is skipped.
5. **FOV scaling** — with a fixed device vector, doubling FOV halves the pixel
   offset from centre; a wrong-FOV constant is caught as a pure scale error.
   Add an image-aspect ≠ viewport-aspect case that fails without the crop/scale
   mapping.
6. **No-match / failure cases** — each `FailureReason` has a test:
   empty candidates → `TOO_FEW_CANDIDATES`; empty detections in match mode →
   `TOO_FEW_DETECTIONS`; high attitude rate → `DEVICE_MOVING`; stale attitude
   age → `STALE_ATTITUDE`; all candidates below horizon → `LOW_ALTITUDE`.
7. **Golden matching scenario (integration)** — synthesize a frame from a known
   attitude + intrinsics + a handful of catalog stars, generate "detections" by
   projecting them (optionally with a small injected attitude error), and assert
   CAM-1 recovers the injected `AttitudeCorrection` within tolerance and returns
   the correct `StarMatch` HIP ids. Mirror the style of
   `core/astro/.../integration/AimPipelineScenarioTest.kt`.

---

## 7. Non-goals (explicitly excluded from CAM-1)

- **Full computer-vision star detection** — CAM-1 must run predict-only and,
  at most, consume simple externally-supplied bright points. No blob detector,
  no ML, unless a later slice needs it.
- **Renderer switch** — keep the existing Compose `Canvas` overlay
  (`ArScreen`); do not move to OpenGL/`GLSurfaceView`/ARCore rendering.
- **Live camera-matching UI** — no new user-facing calibration screen is
  required for the core slice; matching can be exercised from tests/debug first.
- **Moon / twilight modeling for matching** — the physically-driven
  `limitingMagnitudeAt` already exists for visibility; CAM-1 does not add sky
  brightness or twilight models beyond consuming VF-1's visible set.
- **Proper-motion correction** — PTSKCAT0 is J2000 with no PM; CAM-1 uses
  catalog RA/Dec as-is. (Naked-eye stars move ≪ the matching tolerance over the
  relevant epoch span, so this is safe.)
- **Camera-to-IMU extrinsic calibration** — assume identity; the small
  `AttitudeCorrection` output absorbs residual bias.

---

## 8. Recommended CAM-1 implementation slice

A thin, testable vertical slice that de-risks the hard parts (intrinsics +
frame alignment) without committing to computer vision:

1. **Extract the projection math into `:core:astro-core` (or a new
   `:core:camera-match`).** Promote `horizontalToVector`, `vectorToHorizontal`,
   and `projectDeviceVector` out of `:mobile` `private` scope into shared,
   fully-tested pure functions. No behavior change; unblocks tests §6.2–6.5.
2. **Real intrinsics.** Add a `CameraIntrinsics` provider that reads Camera2
   `CameraCharacteristics` (focal length + physical sensor size → FOV) for the
   bound CameraX camera, replacing `VERTICAL_FOV_DEG = 56.0`. Fall back to 56°
   when characteristics are absent. (§3.3 required set only.)

   **CAM-1b status:** the provider, resolution logic, and fallback described
   above are implemented and tested (§3.4), but the "replacing
   `VERTICAL_FOV_DEG = 56.0`" wiring into `ArScreen`/`projectionParams` is
   deliberately **not** done in CAM-1b — the renderer still calls the legacy
   path unchanged. That wiring is left for a later CAM slice once the intrinsics
   contract has had a chance to be reviewed independently of any rendering
   change.
3. **Frame pipeline + crop/scale.** Add an `ImageAnalysis` use case to obtain
   frame resolution, `rotationDegrees`, and `imageInfo.timestamp`. Implement the
   pure image↔display `CropScale` mapping for `FILL_CENTER` (§2 Step E) with
   unit tests §6.5.

   **CAM-1c status:** the `ImageAnalysis` use case, `CameraFrameMetadata` model, extraction seam,
   and latest-value sink described above (§4.4) are implemented and tested — frame resolution,
   `rotationDegrees`, and `imageInfo.timestamp` all reach Kotlin now. The image↔display `CropScale`
   mapping is **not** implemented in CAM-1c; `ImageProxy.cropRect` is captured as plain integers on
   `CameraFrameMetadata` but nothing maps it to display pixels yet. Timestamp pairing with
   `RotationFrame` (§4.2) is CAM-1d; the pure `FILL_CENTER` image↔display `CropScale` mapping itself
   is **CAM-1e** (§9) — geometry only, still not wired into rendering.
4. **Predict-only matcher.** Implement `CameraMatchInput → CameraMatchResult`
   (§5) in predict-only mode: consume VF-2a `VisibleRealStar` candidates, apply
   Steps A–E with real intrinsics, and emit predicted `StarMatch` positions plus
   the failure reasons. Use the §4.3 "device near-still, latest attitude"
   fallback. No detector yet.
5. **Golden scenario test.** Add the §6.7 integration test that injects a known
   attitude error and asserts recovery once the (still-stubbed) correction
   solver is enabled — initially asserting predict-only positions, then wired to
   a minimal least-squares correction when detections are supplied.

This slice delivers per-device-correct projection, a real frame/intrinsics
pipeline, and the CAM-1 data contract, while leaving detection, extrinsics,
distortion, and any UI for later slices.

---

## 9. CAM-1e image↔display `FILL_CENTER` CropScale mapping (this PR)

CAM-1e defines and tests **geometry only**: a pure, Android-independent mapping
that describes how a camera image / crop rectangle is rotated, uniformly scaled,
center-cropped, and positioned inside the displayed viewport under
`PreviewView.ScaleType.FILL_CENTER`, in **both directions**. It reads no pixels,
does no interpolation, consumes no timestamp pairs (§4) or intrinsics (§3), adds
no detector/matcher, and is **not** wired into the renderer — the AR overlay
still uses the legacy fixed 56° projection (§Step E, `projectionParams`)
unchanged.

**Pure models** — `:core:astro-core`, package
`dev.pointtosky.core.astro.projection.camera` (no Android `PointF`/`Rect`/
`RectF`/`Size`/`Matrix`, no Compose type):

- `PixelPoint(x, y)`, `PixelSize(width, height)`, `PixelRect(left, top, right,
  bottom)` (`PixelGeometry.kt`) — all values validated eagerly: finite, sizes
  strictly positive, rectangles strictly ordered (`left < right`, `top <
  bottom`). No silent clamping.
- `CropScaleTransform` with `imageToDisplay`, `displayToImage`,
  `imageRectToDisplay`, `isImagePointVisible`, `isDisplayPointInsideVisibleImage`,
  `visibleImageRect`, `visibleDisplayRect` (`CropScaleTransform.kt`).
- Factories: `CropScaleTransform.fillCenter(sourceCrop, sourceBufferSize,
  rotationDegrees, viewportSize)` (pure sizes/rects) and
  `createFillCenterCropScaleTransform(frame: CameraFrameMetadata,
  viewportWidthPx, viewportHeightPx)` (from CAM-1c metadata).

### 9.0 Construction contract (invalid states unrepresentable)

`CropScaleTransform`'s primary constructor is **private** and the class is
annotated `@ConsistentCopyVisibility` (so the generated `copy()` is private too).
The **only** way to obtain an instance is `CropScaleTransform.fillCenter(...)`
(or `createFillCenterCropScaleTransform(...)`, which delegates to it):

- Callers supply **only source geometry, rotation, and viewport geometry**. There
  is no parameter through which a caller can pass `uniformScale`,
  `rotatedSourceSize`, or the display offsets.
- The factory **derives** `rotatedSourceSize`, `uniformScale`, `displayOffsetX`,
  and `displayOffsetY` internally, from a single private helper
  (`deriveFillCenter`) that is the one place the scale/offset formulas live.
- `init` re-derives those four values from the source/rotation/viewport and
  `require`s the stored values match (within a small epsilon for the derived
  floating-point comparison). Together with the private constructor/`copy()`,
  this makes an instance that is internally finite but does **not** represent
  `PreviewView.ScaleType.FILL_CENTER` **not representable** by any path.
- `init` also enforces **strict** source-crop domain bounds (`left ≥ 0`,
  `top ≥ 0`, `right ≤ bufferWidth`, `bottom ≤ bufferHeight`) with **no epsilon
  slack on these caller inputs** — a crop even slightly outside the buffer is
  rejected, never clamped. Epsilon is used only for the derived-value comparison.

### 9.1 Coordinate spaces

All spaces share the same pixel convention: **origin top-left, `+x` right, `+y`
down**, unit = pixels. The mapping never introduces a Y-flip (unlike §Step E's
NDC→screen `1 − ndcY`, which is a separate concern in the legacy projector).

1. **Buffer space** — raw `ImageProxy.width × ImageProxy.height`
   (`CameraFrameMetadata.bufferWidthPx/bufferHeightPx`). The **public image side
   of the API is buffer-relative**: `imageToDisplay(p)` takes a buffer-space
   point and `displayToImage(q)` returns one.
2. **Crop space (crop-local)** — the active `ImageProxy.cropRect`
   (`CameraFrameMetadata.cropRect*`), translated so the crop's top-left is the
   origin: `cropLocal = (bufferX − left, bufferY − top)`, ranging
   `[0, cropWidth] × [0, cropHeight]`. **Convention chosen: crop-local,
   internal.** Callers always pass/receive buffer-relative points; the transform
   converts to crop-local internally and back.
3. **Rotated image space** — crop-local coordinates after the clockwise
   `rotationDegrees` rotation; dimensions swap for 90°/270°
   (`rotatedSourceSize`).
4. **Display / viewport space** — `PreviewView`/Compose overlay pixels over
   `viewportSize`. This is the same frame as §1.6, but note CAM-1e maps into
   raw viewport pixels and does **not** reconcile against the legacy projector's
   `overlaySize` usage — that reconciliation is a later slice.

### 9.2 Continuous edge-coordinate convention

Coordinates are **continuous image-edge coordinates** in `[0, W] × [0, H]`, not
pixel-center indices in `[0, W−1] × [0, H−1]`. The top-left buffer corner is
`(0, 0)` and the bottom-right corner is `(bufferWidth, bufferHeight)`. This is
chosen deliberately: the inverse transform and viewport scaling stay free of the
`−1` magic constants that pixel-center coordinates would force in. The two
conventions are never mixed.

### 9.3 Clockwise rotation formulas

`rotationDegrees` is interpreted as `ImageProxy.imageInfo.rotationDegrees`: the
**clockwise** rotation required to bring the buffer into display/target
orientation. Rotation is applied in **crop-local** space (after the buffer→crop
translation), around the crop-local origin. For crop-local `(x, y)` with
unrotated crop size `W × H`:

```text
  0°: x' = x           y' = y            rotated size = W × H
 90°: x' = H − y       y' = x            rotated size = H × W
180°: x' = W − x       y' = H − y        rotated size = W × H
270°: x' = y           y' = W − x        rotated size = H × W
```

Inverse (rotated `(x', y')` → crop-local `(x, y)`):

```text
  0°: x = x'           y = y'
 90°: x = y'           y = H − x'
180°: x = W − x'       y = H − y'
270°: x = W − y'       y = x'
```

These are exact inverses and compose correctly (90°∘90° = 180°, 90°×4 =
identity).

### 9.4 `FILL_CENTER` scale and offsets

For rotated source `rotatedW × rotatedH` and viewport `viewportW × viewportH`:

```text
scale   = max(viewportW / rotatedW, viewportH / rotatedH)
scaledW = rotatedW · scale
scaledH = rotatedH · scale
offsetX = (viewportW − scaledW) / 2
offsetY = (viewportH − scaledH) / 2
```

`max` guarantees the scaled source always fully **covers** the viewport (that is
what `FILL_CENTER` means). Exactly one of `offsetX`/`offsetY` is negative (the
center-cropped axis) unless the aspect ratios match, in which case both are `0`.
**Negative offsets are never clamped** — clamping would break both the center
crop and reversibility. `FIT_CENTER`/`FILL_START`/`FILL_END`/arbitrary
`ScaleType` are out of scope.

### 9.5 Forward and inverse mapping order

Forward `imageToDisplay` (buffer → display):

1. translate buffer → crop-local: `(x − left, y − top)`;
2. rotate crop-local clockwise by `rotationDegrees` (§9.3);
3. multiply by `scale`;
4. add `(offsetX, offsetY)`.

Inverse `displayToImage` (display → buffer) undoes the above in reverse:

1. subtract `(offsetX, offsetY)`;
2. divide by `scale`;
3. un-rotate (inverse of §9.3);
4. translate crop-local → buffer: `(x + left, y + top)`.

Invariant, tested over corners/edge-midpoints/center/interior points for all
four rotations and several aspect ratios (tolerance `1e-6`):

```text
displayToImage(imageToDisplay(p)) ≈ p
imageToDisplay(displayToImage(q)) ≈ q
```

Outputs are **never rounded to integers**.

### 9.6 Crop-rect behavior and non-zero crop origin

- When `CameraFrameMetadata` carries a crop rect, **only that crop** is the
  source region; when absent, the full buffer `(0, 0, bufferW, bufferH)` is used.
- A crop rect equal to the full buffer is **honoured, not ignored** (it still
  defines the source region, and future non-identity crops flow through the same
  path).
- The crop origin is **not** assumed to be `(0, 0)`. A buffer point maps through
  the crop-local translation above; the inverse restores exact **buffer-relative**
  coordinates. Example (tested): buffer `2000×1500`, crop `left=100, top=200,
  right=1900, bottom=1400` → the crop origin `(100, 200)` maps to display
  `(0, 0)` and back.

### 9.7 Visibility and center-crop semantics

- The viewport is **always fully covered** by the scaled source, so
  `visibleDisplayRect` is always the full `(0, 0, viewportW, viewportH)`.
- `visibleImageRect` is the buffer-space sub-rectangle of the crop **actually
  shown** — the inverse image of the viewport (computed from the four viewport
  corners, exact for 90°-multiple rotations). It is narrower/shorter than the
  crop on the center-cropped axis, and equals the crop only when aspects match.
- `isImagePointVisible(bufferPoint)` is true only if the point lies inside the
  crop **and** its display mapping falls inside the viewport — a point inside the
  crop but removed by the center crop is **not** visible.
- A viewport corner may inverse-map to a **non-edge** source point when aspect
  ratios differ; source points may map **outside** the viewport.
- Inverse results are **never clamped** to the crop or viewport. Clamping would
  hide geometry errors and break reversibility, so an out-of-viewport display
  point legitimately yields an out-of-crop buffer point.

### 9.8 Production integration (this PR)

**None.** CAM-1e is **pure-only**. No production code constructs, publishes, or
consumes a `CropScaleTransform` in this PR; the renderer, the PTSKCAT0/PTSKCAT4
star overlay, and the legacy 56° projection are untouched. Wiring the transform
into debug/session state was deliberately deferred rather than added, because a
correct call site needs the **Compose overlay viewport size** (the AR `Box`
extent), which lives in `ArScreen`, not in `CameraPreview` (which owns only the
`PreviewView`). Introducing that plumbing would add lifecycle surface for no
behavioral gain in a geometry-only slice.

**Intended call site for the next slice:** CAM-1e itself does not build this transform in
production. **CAM-1f** (§10) is that next slice: it builds `CropScaleTransform` from the latest
`CameraFrameMetadata` plus the measured overlay viewport size, bundles it with the paired rotation
sample and resolved-or-fallback intrinsics into one immutable `CameraSessionGeometry`, publishes it
latest-value-only, and resets it on camera-session disposal — reusing the CAM-1c/1d lifecycle
rather than creating a second session owner. A future predict-only matcher (§8 step 4) is the
*next* intended call site after CAM-1f, not CAM-1e directly.

### 9.9 CameraX `Preview`/`ImageAnalysis` equivalence caveat

This is the intended **pure** `FILL_CENTER` contract using the metadata
currently available (`CameraFrameMetadata`). It is **not** claimed that
`ImageAnalysis` buffer geometry is automatically identical to `PreviewView`'s
internal `Preview` transformation on every device:

- The mapping is valid **only if** the analyzed crop/rotation geometry
  represents the same camera-stream framing that `Preview` renders.
- CameraX may apply **additional** transformation metadata depending on
  implementation/version/device (e.g. `PreviewView`/`TransformationInfo`
  transforms) that this pure model does not yet consume.
- Future integration of CameraX `TransformationInfo`/`PreviewView` transform
  data may **refine or replace** these assumptions.
- This mapping is **not** labeled ground truth. **Physical-device overlay
  validation remains required.**

### 9.10 Device validation status

**Deferred.** Because CAM-1e adds **no production wiring** (§9.8), there is
nothing on-device to validate yet. Device validation — comparing preview vs
overlay viewport dimensions; recording source crop/rotation/scale/offsets;
rotating portrait↔landscape; confirming the viewport center maps to the expected
image center and the edge-crop direction; leaving/re-entering AR to confirm no
stale transform survives session disposal — is deferred to the first slice that
consumes image coordinates on-device. Pixel-perfect `PreviewView` alignment must
not be claimed without a visible marker/grid or a future detector.

### 9.11 Worked numerical example

Source `1920×1080`, viewport `1080×1080`, `rotationDegrees = 0`, no crop
(full-buffer source):

```text
rotatedSize = 1920 × 1080
scale       = max(1080/1920, 1080/1080) = max(0.5625, 1.0) = 1.0
scaledW×H   = 1920 × 1080
offsetX     = (1080 − 1920) / 2 = −420      # negative: horizontal center crop
offsetY     = (1080 − 1080) / 2 = 0

imageToDisplay(960, 540)  = (960·1 − 420, 540·1 + 0) = (540, 540)   # viewport center
displayToImage(0, 0)      = (0 + 420, 0)             = (420, 0)     # non-edge source point
visibleImageRect          = (420, 0, 1500, 1080)                    # central 1080-wide band shown
```

A rotated example — source `4000×3000`, viewport `1080×1920`,
`rotationDegrees = 90`, no crop:

```text
rotatedSize = 3000 × 4000                              # 90° swaps W/H
scale       = max(1080/3000, 1920/4000) = max(0.36, 0.48) = 0.48
offsetX     = (1080 − 1440) / 2 = −180
offsetY     = (1920 − 1920) / 2 = 0

imageToDisplay(2000, 1500):
  crop-local (2000, 1500) → rotate 90° → (3000−1500, 2000) = (1500, 2000)
  → (1500·0.48 − 180, 2000·0.48 + 0) = (540, 960)                  # viewport center
```

### 9.12 Confirmation (scope boundaries)

CAM-1e wires **no** pixels read, **no** YUV/RGB/interpolation, **no**
timestamp-pair (§4) consumption, **no** matcher/detector, **no** renderer
changes, **no** real-intrinsics rendering (§3), **no** PTSKCAT0/PTSKCAT4 catalog
or overlay changes, **no** lens distortion, **no** additional `ScaleType`
support, and **no** front-camera mirroring. Mapped coordinates are neither
clamped nor rounded.

---

## 10. CAM-1f camera-session geometry bundle (this PR)

CAM-1f combines CAM-1c frame metadata, the CAM-1d rotation sample paired to that *exact* frame
timestamp, the CAM-1e `CropScaleTransform` built from that same frame and the AR overlay viewport,
and CAM-1b intrinsics (resolved or explicit fallback) into one immutable, Android-independent
`CameraSessionGeometry`. It is the input shape a future predict-only matcher (§8 step 4) is
expected to consume; this PR adds no star matching, no detection, no interpolation, and no
renderer change — nothing yet reads the bundle it publishes.

### 10.1 Pure models — `:core:astro-core`, package `dev.pointtosky.core.astro.projection.camera`

- **`CameraSessionGeometry`** (`CameraSessionGeometry.kt`):

  ```kotlin
  class CameraSessionGeometry private constructor(
      val frame: CameraFrameMetadata,
      private val pairedRotationSnapshot: TimedRotationSample,
      val frameRotationDeltaNanos: Long,
      val cropScaleTransform: CropScaleTransform,
      val intrinsics: CameraIntrinsicsResolution,
      val viewportSize: PixelSize,
  ) {
      val pairedRotation: TimedRotationSample
          get() = pairedRotationSnapshot.copy(rotationMatrix = pairedRotationSnapshot.rotationMatrix.copyOf())
  }
  ```

  A **regular class, not a `data class`** — an earlier revision exposed `pairedRotation` as a plain
  constructor property, which copied the array once on ingestion but still let a caller corrupt the
  bundle by mutating the array read back out of a `pairedRotation` access. `pairedRotation` is now a
  computed property backed by a `private` snapshot, and **every read returns a fresh defensive
  copy** — true immutability, not merely "copied once on the way in": neither the array a caller
  originally handed off, nor an array read back out of any previous `pairedRotation` access, can
  ever affect the bundle or a later read. The primary constructor is still `private`, so the only
  construction path is the pure factory `createCameraSessionGeometry` (§10.4); `init` re-derives and
  `require`s the cross-field invariants anyway, so no other path can produce an inconsistent
  instance:
  - `cropScaleTransform.sourceBufferSize` equals `frame`'s buffer dimensions;
  - `cropScaleTransform.sourceCrop` equals the frame-derived crop (`sourceCropFromFrame(frame)` —
    the frame's crop rect when present, else the full buffer; the same helper
    `createFillCenterCropScaleTransform` itself uses, so this mapping lives in exactly one place);
  - `cropScaleTransform.rotationDegrees` equals `frame.rotationDegrees`;
  - `cropScaleTransform.viewportSize` equals `viewportSize`;
  - `frameRotationDeltaNanos` equals `frame.timestampNanos - pairedRotation.timestampNanos`,
    computed with the same overflow-safe helper `FrameRotationPairingResult` uses.

  Pairing-tolerance conformance is **not** one of `init`'s invariants: `FrameRotationPairingResult.Paired`
  and `FrameRotationPair` are both public data classes any caller can construct directly, not only
  `pairFrameToNearestRotation` — an earlier revision's claim that "the factory accepts only `Paired`,
  therefore the delta must already be within tolerance" assumed a `Paired` value could only
  originate from `pairFrameToNearestRotation`, which is false for a publicly constructible type.
  `createCameraSessionGeometry` (§10.4) validates the configured tolerance explicitly, against a
  caller-supplied threshold, before ever calling the canonical factory — see §10.2.

- **`CameraIntrinsicsResolution`** (`CameraIntrinsicsResolution.kt`) — the CAM-1f intrinsics
  policy, chosen from the two options CAM-1f's own task description offered:

  ```kotlin
  sealed interface CameraIntrinsicsResolution {
      val intrinsics: CameraIntrinsics
      data class Resolved(override val intrinsics: CameraIntrinsics) : CameraIntrinsicsResolution
      data class LegacyFallback(
          override val intrinsics: CameraIntrinsics,
          val reason: String,
      ) : CameraIntrinsicsResolution
  }
  ```

  **Policy: both resolved and fallback intrinsics may produce a `Ready` bundle** (the "Preferred"
  option) — a fallback bundle is diagnostically usable (every geometric field is still valid) but
  is never described as fully calibrated: `Resolved.intrinsics.source` can never be
  `LEGACY_FALLBACK` and `LegacyFallback.intrinsics.source` can never be anything else (`init`
  enforces both directions), and every `Ready` result carries an explicit `CameraGeometryQuality`
  (§10.5) derived from this type, never guessed. This mirrors CAM-1b's own
  `dev.pointtosky.mobile.ar.camera.CameraIntrinsicsResolution(intrinsics, fallbackReason: String?)`
  contract (§3.4) — CAM-1f's sealed hierarchy makes the same distinction impossible to forget, since
  there is no nullable field to skip checking. `:mobile`'s
  `SessionScopedCameraIntrinsicsResolver` (§10.7) maps the CAM-1b shape into this one exactly once
  per bound camera session.

- **`CameraSessionGeometryResult`** (`CameraSessionGeometryResult.kt`) — never a nullable
  geometry:

  ```kotlin
  sealed interface CameraSessionGeometryResult {
      data class Ready(val geometry: CameraSessionGeometry, val quality: CameraGeometryQuality) : ...
      data class MissingFrame(val viewportSize: PixelSize?) : ...
      data class InvalidViewport(val widthPx: Int, val heightPx: Int) : ...
      data class RotationUnavailable(val reason: RotationUnavailableReason, val pairingResult: FrameRotationPairingResult) : ...
      data class IntrinsicsUnavailable(val reason: IntrinsicsUnavailableReason) : ...
      data class GeometryRejected(val reason: GeometryRejectionReason) : ...
      data object Disposed : ...
  }
  ```

  `RotationUnavailable` carries `NO_SAMPLES` / `OUTSIDE_TOLERANCE` / `CLOCK_MISMATCH_SUSPECTED`.
  `GeometryRejected` carries `PAIRING_FRAME_MISMATCH` / `PAIRING_DELTA_MISMATCH` /
  `PAIRING_OUTSIDE_CONFIGURED_TOLERANCE` / `CROP_SCALE_CONSTRUCTION_FAILED` — the first three exist
  specifically because a `FrameRotationPairingResult.Paired` is publicly constructible and cannot be
  trusted as proof of anything about how it was produced; see §10.2. No reason carries raw exception
  text. `IntrinsicsUnavailable`/`MissingFrame`/`Disposed` are never returned by the pure factory
  itself (it always requires an already-resolved frame/intrinsics as input); they exist for the
  production session owner (§10.6), which publishes them while a bound session has not yet produced
  those inputs. A cheap `CameraSessionGeometryStatus` enum (`status` extension property) classifies
  any result without exposing reason detail, for logging/debug-state comparisons.

### 10.2 Frame/pairing coherence rule

A published bundle must use **one exact** `CameraFrameMetadata`, the pairing result computed for
*that same frame*, the current viewport, and the current session's intrinsics — never a frame
combined with an independently-"latest" pairing result that happened to be computed for a
different frame. **`FrameRotationPairingResult.Paired` and `FrameRotationPair` are both public data
classes** — any caller can construct one directly, not only `pairFrameToNearestRotation` — so a
`Paired` value is never trusted as self-proving anything. Coherence is enforced two ways:

1. **At the callback boundary** (Option A from CAM-1f's task description):
   `CameraTimestampSynchronizer.onCameraFrame(frame)` (CAM-1d) now *returns* the
   `FrameRotationPairingResult` it just computed and published for that exact `frame` (`null` only
   if the synchronizer is already disposed), instead of `Unit`. `ArScreen` hands the `(frame,
   pairingResult)` pair to `CameraSessionGeometryProvider.onPairedFrame` together, in one call —
   it never separately reads `CameraFrameMetadataProvider.latest` and
   `CameraTimestampSynchronizer.latestResult` and assumes they describe the same frame.
2. **At the pure factory** (not merely defense in depth — the only real gate, since the pairing
   result reaching this function did not necessarily come through step 1): `createCameraSessionGeometry`
   validates, in order:
   - **exact frame equality** — `paired.pair.frame != frame` (full `CameraFrameMetadata` structural
     equality, not just `timestampNanos`) is rejected as `GeometryRejected(PAIRING_FRAME_MISMATCH)`.
     Timestamp equality alone is **not** sufficient proof of coherence: two frames can share a
     timestamp while differing in buffer dimensions, crop rect, or rotation, and only full metadata
     equality catches that;
   - **delta arithmetic** — `paired.pair.deltaNanos` must equal the overflow-safe delta actually
     implied by `frame.timestampNanos` and `paired.pair.rotation.timestampNanos`; a mismatch is
     `GeometryRejected(PAIRING_DELTA_MISMATCH)`;
   - **configured tolerance** — the (now verified-correct) delta's absolute value must not exceed
     `maxAllowedPairDeltaNanos` (§10.4); exceeding it is
     `GeometryRejected(PAIRING_OUTSIDE_CONFIGURED_TOLERANCE)`, regardless of the `Paired` subtype
     having been used. Using the `Paired` subtype alone as tolerance proof was the exact gap this
     validation closes — a forged or mis-tagged `Paired` value cannot bypass it.

   Any caller bug at the production seam — or a forged pairing result reaching the factory by any
   other path — is caught here, categorically, never silently wrapped into a bundle.

### 10.3 Viewport authority

The AR overlay viewport (`overlaySize: IntSize`, the Compose `Box` extent in `ArScreen`) is
**authoritative** for `CropScaleTransform`'s viewport, not `PreviewView`'s own size: this is the
coordinate space a future matcher's predictions will actually be displayed in (CAM-1e §9.8 already
flagged this as the correct choice). `ArScreen` converts `overlaySize` to plain `Int` width/height
at the Compose boundary — `CameraSessionGeometryProvider.onViewportChanged(widthPx: Int, heightPx:
Int)` never sees a Compose `IntSize`. A zero-sized viewport is reported as `InvalidViewport`, never
silently mapped to a fake `1x1` transform — `PixelSize` cannot represent a non-positive size at
all, so the pure factory checks width/height *before* constructing one. `PreviewView`'s own
measured size is not read or compared against `overlaySize` in this PR; if the two ever need to be
reconciled, that is future work, not assumed equal here.

### 10.4 Pure bundle factory

```kotlin
fun createCameraSessionGeometry(
    frame: CameraFrameMetadata,
    pairingResult: FrameRotationPairingResult,
    intrinsicsResolution: CameraIntrinsicsResolution,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    maxAllowedPairDeltaNanos: Long = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
): CameraSessionGeometryResult
```

Deviates from the task description's illustrative `viewportSize: PixelSize` parameter deliberately:
`PixelSize` cannot represent a zero/negative size, so the *invalid* case must be checked before any
`PixelSize` is constructed — plain, possibly-invalid `Int`s let that happen. Accepts only
`FrameRotationPairingResult.Paired`; every other pairing outcome becomes a categorized
`RotationUnavailable`. `maxAllowedPairDeltaNanos` is validated explicitly (§10.2) rather than
inferred from the `Paired` subtype — it `require`s non-negative (a programmer-contract violation at
the call site throws, since that is not an expected runtime outcome) and defaults to
`TimestampSyncConfig.MAX_PAIR_DELTA_NANOS`, but a caller whose pairing result was produced with a
non-default tolerance (e.g. a `CameraTimestampSynchronizer` constructed with a custom
`maxAllowedDeltaNanos`) must pass that same value here — see §10.6 for how the production owner
shares it. Builds the `CropScaleTransform` via the existing CAM-1e
`createFillCenterCropScaleTransform(frame, viewportWidthPx, viewportHeightPx)` — never a second,
independent CropScale construction path — catching only its `IllegalArgumentException` as
`GeometryRejected(CROP_SCALE_CONSTRUCTION_FAILED)`. Every expected-runtime rejection is a returned,
categorized result, never a thrown exception; no result carries raw exception text. Pure, no
Android dependency, no side effects, retains nothing past the call.

### 10.5 Geometry quality classification

```kotlin
enum class CameraGeometryQuality { CALIBRATED, LEGACY_INTRINSICS_FALLBACK }
```

Derived from `CameraIntrinsicsResolution` (`val CameraIntrinsicsResolution.quality`), never
guessed, and carried on every `Ready` result (`Ready.quality`) so a fallback bundle can never be
read as fully calibrated. `CameraSessionGeometryProvider`'s debug state (§10.6) preserves the same
distinction (`latestQuality: CameraGeometryQuality?`), and the throttled `MobileLog` summary logs
it as a plain string — the fallback-vs-calibrated distinction survives into logging/debug state
exactly as CAM-1f's task description required.

### 10.6 Production session owner — `:mobile`, package `dev.pointtosky.mobile.ar.camera`

**`CameraSessionGeometryProvider`** (`CameraSessionGeometryProvider.kt`) is the one session-scoped
owner. It receives:

- `onPairedFrame(frame, pairingResult)` — from `ArScreen`'s `CameraPreview.onFrameMetadata`
  callback, which calls `timestampSynchronizer.onCameraFrame(frame)` and forwards the frame
  together with its non-null return value (§10.2);
- `onViewportChanged(widthPx, heightPx)` — from a `LaunchedEffect(overlaySize)` in `ArScreen`
  (§10.3); a no-op when the value is unchanged, so recomposition noise does not force a rebuild;
- `onIntrinsicsResolved(resolution)` — from `ArScreen`'s `CameraPreview.onCameraInfo` callback
  (§10.7); only the *first* call per provider instance has any effect — intrinsics resolve at
  most once per bound camera session, then are reused for every subsequent frame.

`CameraSessionGeometryProvider`'s constructor takes `maxAllowedPairDeltaNanos: Long =
TimestampSyncConfig.MAX_PAIR_DELTA_NANOS`, threaded straight into every `createCameraSessionGeometry`
call (§10.4). This must agree with whatever tolerance the session's `CameraTimestampSynchronizer`
actually pairs against — `CameraTimestampSynchronizer.maxAllowedDeltaNanos` is a public, immutable
`val` (CAM-1f addition) for exactly this reason, and `ArScreen` reads it directly when constructing
the provider (`CameraSessionGeometryProvider(maxAllowedPairDeltaNanos =
timestampSynchronizer.maxAllowedDeltaNanos)`) rather than letting both default independently. If a
future change constructs either with a non-default tolerance, this wiring keeps them in sync
without needing to remember to update both call sites.

It publishes `state: StateFlow<CameraSessionGeometryResult>`, latest-value-only — never a queue —
plus a bounded `debugState(): CameraSessionGeometryDebugState` (observed/ready/rejected counts,
latest status/quality/pair-delta; no historical list). A published bundle always combines the
frame/pairing pair from the most recent `onPairedFrame` call with whatever viewport and intrinsics
are current at rebuild time (§10.2) — `onViewportChanged`/`onIntrinsicsResolved` each trigger a
rebuild against the still-latest frame/pairing pair, so a viewport rotation or the intrinsics
callback arriving after the first frame both produce a fresh bundle without waiting for the next
camera frame.

**Thread safety and disposal** follow the CAM-1d `CameraTimestampSynchronizer` convention exactly:
one internal lock serializes every update method with `dispose()` — a single state-machine
transition, not independent atomics — so a callback already in flight when `dispose()` runs either
publishes before disposal clears it, or observes `disposed == true` and no-ops; there is no
interleaving that republishes after disposal completes (verified by the same
barrier-coordinated race-test pattern CAM-1d uses, `CameraSessionGeometryProviderTest`).
`dispose()` is terminal and idempotent: every update method becomes a permanent no-op afterward,
`state` becomes `CameraSessionGeometryResult.Disposed`, and all cached inputs/counters are cleared
— so no stale previous-session bundle can survive into a new one. `MobileLog` calls are always
issued from an immutable snapshot decided while holding the lock, never while holding it. One
provider instance belongs to one AR camera session (`remember`ed in `ArScreen`, disposed from the
same top-level `DisposableEffect` that disposes `timestampSynchronizer`); a new session gets a new
instance rather than reusing a disposed one.

### 10.7 Intrinsics resolution — `:mobile`, package `dev.pointtosky.mobile.ar.camera`

**`SessionScopedCameraIntrinsicsResolver`** (`SessionScopedCameraIntrinsicsResolver.kt`) wraps
CAM-1b's `CameraIntrinsicsProvider`/`Camera2CameraIntrinsicsProvider` — reused verbatim, no second
resolver or Camera2 seam — with once-per-instance caching: the first `resolveOnce(cameraInfo)`
call performs the real Camera2 `CameraCharacteristics` lookup and maps CAM-1b's
`CameraIntrinsicsResolution(intrinsics, fallbackReason: String?)` into CAM-1f's sealed
`CameraIntrinsicsResolution` (§10.1); every later call on the same instance returns the cached
value without repeating the lookup. One instance belongs to one bound camera session
(`remember`ed alongside `CameraSessionGeometryProvider`); re-entering AR creates a new instance, so
a fresh resolution is always attempted — nothing is cached across sessions.

**`CameraPreview` gained `onCameraInfo: (CameraInfo) -> Unit = {}`** (CAM-1f addition, defaults to
a no-op): called exactly once per successful bind — combined `Preview` + `ImageAnalysis`, or the
Preview-only fallback (§4.4) — with the real, bound `Camera.cameraInfo`, and only for the
confirmed-live session (gated by the same `CameraSessionLifecycle.confirmBound` late-dispose check
that already guards `MobileLog.cameraAnalysisBound()`/`cameraPreviewBoundWithoutAnalysis()`), never
for a bind that lost the race against disposal. This is what lets intrinsics resolution use the
*actual* bound camera rather than a placeholder — CAM-1f does not add a second camera or sensor
session owner to obtain it.

`resolveOnce` is called with `imageWidthPx = null, imageHeightPx = null`: the `CAMERA_CHARACTERISTICS`
path (focal length + physical sensor size) does not depend on analyzed buffer dimensions at all, and
coupling resolution timing to "wait for the first frame's buffer size" would only refine the
*fallback* path's aspect-derived horizontal FOV (§3.4) — a minor nicety, not a correctness
requirement, and not worth the extra synchronization between the bind coroutine and the frame
callback. **No recalculation is ever triggered within a session**: sensor physical size and the
bound `CameraInfo` do not change while a session is bound, so there is no scenario in this PR that
needs one.

### 10.8 Logging and debug state

`CameraSessionGeometryDebugState` (`CameraSessionGeometryProvider.kt`, alongside the provider,
mirroring where CAM-1c's `CameraFrameDebugState` lives) is bounded — running counts plus the
single latest status/quality/pair-delta, no historical list. `latestStatus` always reflects the
*current* published result. `latestQuality`/`latestPairDeltaNanos` are populated only while the
current result is `Ready`, and are explicitly cleared to `null` on every transition away from
`Ready` — to `MissingFrame`, `InvalidViewport`, `RotationUnavailable`, `IntrinsicsUnavailable`,
`GeometryRejected`, or `Disposed` — so a caller reading debug state never sees a stale quality/delta
left over from a previous `Ready` bundle after the provider has actually stopped being ready. A
later `Ready` recompute (while the provider remains active) repopulates both fields from that new
bundle. `MobileLog` gained `cameraGeometry*`
events (`MobileLog.kt`): `cameraGeometrySessionStarted()` (once, first update),
`cameraGeometryFirstReady(quality)` (once, first `Ready`), `cameraGeometryStatusChanged(status)`
(logged only on an actual status transition), `cameraGeometryFallbackIntrinsicsInUse()` (once, on
the first fallback-quality `Ready`), `cameraGeometrySummary(...)` (throttled — every 30th `Ready`
recompute, never once per frame — carrying `status`, `quality`, `bufferWidthPx`/`bufferHeightPx`,
`viewportWidthPx`/`viewportHeightPx`, `rotationDegrees`, `pairDeltaMillis`, `intrinsicsSource`),
and `cameraGeometryDisposed()` (once). None of these log a rotation matrix, a raw timestamp
stream, a full `CameraIntrinsics` object, a raw exception message, a device identifier, or a pixel
buffer — matching the CAM-1c/1d logging conventions.

### 10.9 Current production wiring (this PR)

`ArScreen` owns one `CameraSessionGeometryProvider` and one `SessionScopedCameraIntrinsicsResolver`
(both `remember`ed), disposes the provider from the same `DisposableEffect(Unit)` that disposes
`timestampSynchronizer`, feeds `onViewportChanged` from a `LaunchedEffect(overlaySize)`, and wires
`CameraPreview`'s `onFrameMetadata`/`onCameraInfo` callbacks to `onPairedFrame`/`onIntrinsicsResolved`
as described in §10.6–§10.7. **Nothing reads `CameraSessionGeometryProvider.state`** in this PR —
it is fed but not consumed. The AR renderer (`ArScreen.calculateOverlay()` / `projectionParams`)
still calls the legacy fixed `VERTICAL_FOV_DEG = 56.0` path exclusively, unchanged; no real
intrinsics, crop/scale mapping, or paired rotation reaches rendering. No star matching, detection,
interpolation, or SLERP is added.

### 10.10 Device validation status

**Deferred.** CAM-1f adds a production owner that is fed real camera/rotation/viewport/intrinsics
data but consumed by nothing — there is no visible behavior change to validate on-device yet. The
manual gate for the *next* slice (whichever one first reads `CameraSessionGeometryProvider.state`)
should verify: a `Ready` bundle appears after opening AR; rotating portrait↔landscape rebuilds the
viewport and transform; panning slowly/quickly does not desync frame/pairing coherence; leaving and
re-entering AR repeatedly never surfaces a stale previous-session bundle; the Camera2 intrinsics
lookup happens exactly once per session (not once per frame); and no renderer or overlay behavior
changes throughout. As with CAM-1e (§9.10), pixel-perfect `PreviewView`/image alignment must not be
claimed from bundle readiness alone — that still requires a visible marker/grid or a future
detector.

### 10.11 Confirmation (scope boundaries)

CAM-1f reads **no** pixels, performs **no** interpolation or SLERP, adds **no** matcher or
detector, makes **no** renderer change, wires **no** real intrinsics into rendering (§3), and
changes **no** PTSKCAT0/PTSKCAT4 catalog or overlay behavior. It adds exactly one new production
session owner (`CameraSessionGeometryProvider`) and one intrinsics-resolution seam
(`SessionScopedCameraIntrinsicsResolver`) — no second camera or sensor session, no persistent
bundle history (latest-value-only throughout), no lens distortion, and no front-camera support.

---

## Appendix A — sign/handedness risk register (quick reference)

| # | Risk | Where | Guard |
|---|---|---|---|
| R1 | Azimuth clockwise-from-North vs math CCW | Steps A/B | cardinal-table test (§6.4) |
| R2 | Refraction on in one direction, off in the other | `raDecToAltAz` vs `altAzToRaDec` | fix one convention (§2 Step A) |
| R3 | Magnetic vs true North | `correctedForTrueNorth` | declination-offset test (§6.4) |
| R4 | Camera axis is device `−Z` | `forwardWorld` negation | reticle round-trip test (§2 Step C) |
| R5 | Display remap vs image `rotationDegrees` double-count | `remapForDisplay` + analyzer | per-rotation test (§6.3) |
| R6 | Screen/image Y flip (`1 − ndcY`) | `projectDeviceVector` | centre + edge test (§6.2) |
| R7 | Wrong/hardcoded FOV → scale error | `projectionParams` | FOV-scaling test (§6.5) |
| R8 | `FILL_CENTER` crop ignored (image ≠ viewport aspect) | `CameraPreview` + Step E | crop/scale test (§6.5); pure `CropScaleTransform` forward/inverse + visibility tests (§9, CAM-1e) — not yet wired/device-validated |
| R9 | Front-camera mirroring | (back camera only today) | flip guard if front added |
| R10 | Sensor/camera clock base mismatch | §4 sync | age gate + near-still fallback (§4.3); measured nearest-sample pairing + tolerance/mismatch thresholds + diagnostic compatibility status, not yet device-validated (§4.5) |
