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

### 3.5 CAM-2c: calibrated `AnalysisBuffer` intrinsics (real-device fix)

CAM-1b's `CAMERA_CHARACTERISTICS` path (§3.4) always produced a `PhysicalSensor`-referenced
`CameraIntrinsics` — a real per-device FOV, but measured over the physical sensor with no recorded
mapping to any particular `ImageAnalysis` buffer. CAM-2a's `projectStars` correctly refuses to
project a `PhysicalSensor`-referenced value (`IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED`),
so on real hardware the only way to see predicted markers at all was CAM-2b's explicit
`DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK` mode, which substitutes the legacy fixed-FOV — and Pixel 9
evidence showed exactly the bug that predicts: the centre marker aligned (any FOV puts an on-axis
ray at the image centre) but off-axis markers diverged systematically, because the legacy fallback's
FOV is not the true buffer-space intrinsics. CAM-2c closes this by deriving a real, calibrated
`AnalysisBuffer`-referenced mapping from Camera2 metadata instead of tuning the fallback FOV.

**Five coordinate spaces, never conflated** (a first pass at this section numbered five but listed
only four — corrected below; the genuinely missing fifth is the pre-correction active array, split
out of what was one bullet into its own):

1. **Physical sensor millimetres** — `SENSOR_INFO_PHYSICAL_SIZE`, `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`.
2. **Active-array pixels** — `SENSOR_INFO_ACTIVE_ARRAY_SIZE` (`ActiveArrayIntrinsics`,
   `core/astro-core/.../camera/ActiveArrayIntrinsics.kt`).
3. **Pre-correction active-array pixels** — `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`, the space
   `LENS_INTRINSIC_CALIBRATION`/`LENS_DISTORTION` are actually reported in — closely related to space 2
   but **not assumed identical**: `resolveAnalysisBufferIntrinsics` compares the full rectangle (all
   four edges, not just width/height — a same-size-but-shifted-origin pre-correction array is *not*
   the same rectangle) before trusting `LENS_INTRINSIC_CALIBRATION`'s numbers as space-2 coordinates.
4. **CameraX `ImageAnalysis` buffer pixels** — the exact `frame.bufferWidthPx`×`bufferHeightPx` space
   `PinholeProjectionModel` already projects into (`AnalysisBufferIntrinsicsValues`,
   `core/astro-core/.../camera/AnalysisBufferIntrinsicsMapping.kt`).
5. **Display viewport pixels** — unchanged; still owned exclusively by `CropScaleTransform`.

**Cropped-rect provenance (§5 of the task spec) — resolved, not assumed.** `CameraFrameMetadata`'s
own `cropRectLeftPx`/etc (CAM-1c) are already documented as **buffer**-space (`ImageProxy.cropRect`,
constrained to lie within the buffer) — and `CameraPreview.kt` binds `Preview`+`ImageAnalysis` with
no `ViewPort`/`UseCaseGroup`, so that rect is always the identity `(0,0,width,height)` in this app's
current binding. It therefore cannot answer "which part of the sensor maps into this buffer" and is
never used for that purpose. The real answer is `ImageInfo.getSensorToBufferTransformMatrix()` — a
stable, official CameraX API (present in `camera-core` since at least `1.1.0-beta01`, well before
this project's `1.3.4`) whose own contract documents a mapping "from the value of
`CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE` to `(0, 0, image.getWidth, image.getHeight)`" —
populated with a real, non-default value for every bound `ImageAnalysis`/Camera2 session. CAM-2c
reads it via `ImageProxyFrameMetadataSource` (`:mobile`) and converts it, in full, into a plain
`SensorToBufferMatrix3` (`core/astro-core/.../camera/SensorToBufferMatrix3.kt`) carried as a new
optional field on `CameraFrameMetadata` — no new callback wiring through `CameraPreview`/`ArScreen`
was needed; `CameraSessionIntrinsicsCoordinator.onFrameMetadata` already receives the whole frame.

**Correction (post-launch fix): the matrix is not assumed axis-aligned.** CAM-2c's first pass
asserted "Camera2's own crop-then-scale pipeline never introduces rotation/skew" and rejected (as
`null`) any reported matrix that was not a pure axis-aligned scale+translate. That assumption is
empirically well-founded for CameraX's own reference implementation
(`CameraUseCaseAdapter.calculateSensorToBufferTransformMatrix`, built from `Matrix.setRectToRect(...,
ScaleToFit.CENTER)` then inverted — provably rotation/skew-free by construction) but is **not**
something the public `ImageInfo.getSensorToBufferTransformMatrix()` contract itself guarantees for
every CameraX version or OEM camera HAL, so treating a non-axis-aligned report as "unavailable"
rather than "possibly a real, different geometric relationship" was a genuine gap. `SensorToBufferMatrix3`
now preserves all 9 reported values exactly, and `classifySensorToBufferMatrix` (pure, tolerance-only-
for-float-noise) sorts every matrix into one of eight classes: `AXIS_ALIGNED_0`/`ORTHOGONAL_90`/
`ORTHOGONAL_180`/`ORTHOGONAL_270` (composable into a valid pinhole model — see below) and `MIRRORED`/
`GENERAL_AFFINE_UNSUPPORTED`/`PROJECTIVE_UNSUPPORTED`/`SINGULAR` (typed rejections —
`AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform(transformClass)`, never a
silent `null`/guess). `mapActiveArrayIntrinsicsThroughMatrix` composes the full active-array pinhole
matrix `K` (now including a `skewPx` term) with the classified `M` via genuine matrix multiplication
(`K' = M·K`) rather than independently-derived per-field formulas, deriving buffer-space `fx`/`fy`/
`cx`/`cy` plus `axisSwapped`/`negateXInput`/`negateYInput` flags for the `ORTHOGONAL_90`/`270`/`180`
cases. `PinholeProjectionModel` gained matching `axisSwapped`/`negateXInput`/`negateYInput` fields
(all defaulting `false`, 100% backward compatible) that `project()` consumes to pick which of its two
normalized-ray inputs drives `focalLengthXPx` vs `focalLengthYPx`, and with which sign — a
**position**-space operation, resolved once per session from `M`, that cannot double up with the
separate, untouched, **direction**-space `frame.rotationDegrees`-driven rotation
(`DisplayAlignedOpticalToBufferOpticalTransform`, applied fresh per star at projection time — see
that class's own KDoc and `PinholeProjectionModelTest`'s dedicated no-double-rotation proof). In
practice, on every device this codebase can prove reachable via CameraX's own real implementation,
the matrix is `AXIS_ALIGNED_0` — the other seven classes exist for robustness against a HAL/CameraX
version this codebase has not traced, not because they are known to occur.

**Principal-point and skew policy (§3 of the task spec).** `LENS_INTRINSIC_CALIBRATION` (`[fx, fy, cx,
cy, skew]`) is preferred only when **both** of two independent checks pass: (1) its coordinate-space
contract is verified — if the device also reports `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`, it
must exactly match `SENSOR_INFO_ACTIVE_ARRAY_SIZE` on all four edges (space 3 vs space 2 above; if the
device does not report one at all, the two spaces are trivially identical); and (2) its skew term
(`calibration[4]`) is within `INTRINSIC_SKEW_TOLERANCE_PX` (0.5px) of zero — a real rectilinear lens
has zero skew, so anything past that tolerance indicates a genuine non-pinhole-representable lens
characteristic this codebase's skew-free downstream model cannot represent, and the calibrated `K` is
rejected outright (`CameraCalibrationDiagnosticReason.NON_ZERO_INTRINSIC_SKEW`) rather than silently
dropping the skew term and keeping a principal point that looks more precise than it actually is.
Failing either check falls back to `activeArrayIntrinsicsFromFocalLength` with a geometric-centre
principal point and zero skew. Either way `fx`/`fy` are real, focal-length-derived numbers — this is
a principal-point-only fallback, never a fixed-FOV guess. `CameraIntrinsicsQuality`
(`CALIBRATED`/`APPROXIMATE_PRINCIPAL_POINT`) records which policy applied; it is `null` for every
source except `CAMERA_CHARACTERISTICS`. **Corrected by fix §P2 (§3.8):** this fallback's `fx`/`fy`
were originally derived from `physicalSensorSizeMm / activeArrayPx`; §3.8 corrects this to
`physicalSensorSizeMm / pixelArrayPx` (`SENSOR_INFO_PIXEL_ARRAY_SIZE`, not
`SENSOR_INFO_ACTIVE_ARRAY_SIZE`) — the two differ whenever the active array excludes inactive border
pixels the full pixel array still reports.

**A new, deliberate `CameraIntrinsics` invariant relaxation.** `source=CAMERA_CHARACTERISTICS` may
now carry `reference=AnalysisBuffer` in addition to the existing `reference=PhysicalSensor` — the
same real Camera2 metadata, but mapped through the exact CameraX transform for one analyzed frame.
`PinholeProjectionModel.forGeometry`/`projectStars` were **not** touched: they still gate on
`reference` alone, so a `PhysicalSensor`-referenced value (CAM-1b's own path, or a logical
multi-camera/missing-transform/invalid-metadata fallback — see below) is rejected exactly as before.

**Typed resolution, no silent fallback** (`mobile/.../ar/camera/AnalysisBufferIntrinsicsResolver.kt`):
`AnalysisBufferIntrinsicsResolution` is `Resolved` / `MissingActiveArray` / `MissingPhysicalSensorSize`
/ `MissingFocalLength` / `MissingSensorToBufferTransform` / `UnsupportedSensorToBufferTransform(class)`
/ `UnsupportedLogicalMultiCameraMapping(cameraId, physicalCameraIdsForDiagnostics)` /
`InvalidMetadata(reason)` — extended by later fixes to also include
`RotationOwnershipUnproven(transformClass)` (fix round 2 §4, §3.6) and `MissingPixelArraySize` (fix
§P2, §3.8); see those subsections for what each means. `resolveCameraIntrinsicsPreferringCalibration`
tries this path first and falls back to the **unchanged** CAM-1b `resolveCameraIntrinsics` (still
`PhysicalSensor`-referenced, or the legacy fallback) for any other outcome — never the reverse.

**Logical multi-camera: still blocked, provenance investigated (§1/§5 of the fix task) — this is the
single biggest reason CAM-2c is not yet a universal "normal session path".** An earlier pass's own
sprint notes described CAM-2c as "replacing CAM-2b's diagnostic-fallback substitution as the normal,
non-debug session path" — true only for a camera whose `REQUEST_AVAILABLE_CAPABILITIES` does **not**
include `LOGICAL_MULTI_CAMERA`. `UnsupportedLogicalMultiCameraMapping` still fires unconditionally
whenever it does, and this fix investigated — rather than assumed — whether this project's pinned
`androidx.camera:camera-camera2:1.3.4` could do better: it cannot. Binding a concrete physical camera
(`CameraSelector.setPhysicalCameraId`) and reading per-frame physical-camera provenance
(`CameraInfo.getPhysicalCameraInfos()`) both require `1.4.0-beta01` or later — confirmed absent from
this project's pinned `1.3.4` API surface. `Camera2CharacteristicsSource` now takes an optional
`Context` and, when supplied, reads the logical camera's declared physical camera IDs via a narrow,
read-only `android.hardware.camera2.CameraManager.getCameraCharacteristics(id).getPhysicalCameraIds()`
bypass — **diagnostics only**, never used to bind or select a camera, and never changing this guard's
outcome. Real Pixel phones (Pixel 4 onward) have used logical multi-camera rear configurations, so
this almost certainly means **CAM-2c's calibrated path is blocked on a real Pixel 9's actual rear
camera today** — the honest status is "normal session path on any non-logical-multi-camera device;
blocked, falling back to the CAM-1b/legacy path exactly as before, on a logical multi-camera device" —
not an unconditional replacement. Revisiting this is possible only via a CameraX version bump (a
broader, riskier dependency change, out of this fix's scope) or an experimental capture-callback
subsystem.

**The coordinator's coherent-input gate (§4 of the fix task).** `CameraSessionIntrinsicsCoordinator`
previously accepted the *first* analyzed frame's dimensions and `sensorToBufferTransform`
unconditionally, then permanently ignored every later frame — including when that first frame's
transform was `null` or (under the pre-fix axis-aligned-only assumption) merely rotated. It now keeps
accepting frames (bounded, no queue — only the current best candidate and the most recent frame's
dimensions are retained) until one frame's own transform is both non-`null` and classifies as one of
the four *supported* classes, using that exact frame's dimensions and transform together, never mixed
with another frame's. A configurable bound (`maxFramesWaitingForUsableTransform`, default 30 frames —
~1s at a typical 30fps `ImageAnalysis` rate) gives up and resolves with the most recent frame's
dimensions and a `null` transform (falling through to the CAM-1b path) if no usable transform ever
arrives. `CameraSessionIntrinsicsCoordinatorState` (`WAITING_FOR_CAMERA_INFO`/`WAITING_FOR_FRAME`/
`WAITING_FOR_USABLE_SENSOR_TO_BUFFER_TRANSFORM`/`RESOLVING`/`RESOLVED`/`DISPOSED`) exposes this as an
observable property for tests/diagnostics.

**Diagnostics only** (`CameraCalibrationDiagnostics`, `internalDebug`-gated `CamDiagnosticTopPanels`
panel): active array size, sensor physical size, focal length, active-array fx/fy/cx/cy, the
active-array-space crop region, buffer fx/fy/cx/cy, `quality` (with the skew-rejection reason, when
applicable), the sensor-to-buffer mapping source and its classified `transformClass`, and the bound
camera's ID/logical-multi-camera flag/declared physical camera IDs (when available). Never persisted
externally — on-screen only, in a debug build.

**Validation (this environment).** `:core:astro-core:test` (455 tests, including
`SensorToBufferMatrix3`/classification tests, the general matrix-composition rewrite of
`AnalysisBufferIntrinsicsMappingTest`, `PinholeProjectionModelTest`'s axis-swap/no-double-rotation
tests, and `CalibratedAnalysisBufferProjectionTest`'s four-off-axis-ray and
all-four-`rotationDegrees` full-pipeline tests) and `:mobile:test{Internal,Public}DebugUnitTest` (326
tests each, including the rewritten `AnalysisBufferIntrinsicsResolverTest`'s skew-tolerance/
unsupported-transform-class/non-zero-origin cases and `CameraSessionIntrinsicsCoordinatorTest`'s
coherent-input-gate cases) all pass under a real Gradle build. No physical device or emulator is
available in this sandboxed environment (no `/dev/kvm`, no hardware-virtualization CPU flags, `adb
devices -l` lists none), so §10's live Pixel 9 centre/mid-field/edge-star dx/dy comparison could not
be executed here; it remains an open item for a session with real hardware access — and, per the
logical-multi-camera paragraph above, is expected to hit `UnsupportedLogicalMultiCameraMapping` (not
a calibrated `AnalysisBuffer` result) on a real Pixel 9's actual rear camera regardless.

### 3.6 CAM-2c fix round 2: coordinate-origin ownership and a real rotation proof

> **Corrected by the "CAM-2c fix round 3" pass below (§3.7):** this section's coordinate-origin fix
> (§1/§2 below) was itself wrong — it treated `LENS_INTRINSIC_CALIBRATION` cx/cy and the CameraX
> sensor-to-buffer matrix's own domain as **absolute**, full-pixel-array-relative coordinates, and
> added `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`/`.top` before composing with the matrix. Android's own
> Camera2 documentation is explicit that both are **active-array-local** instead (`(0, 0)` at the
> active array rectangle's own top-left) — see §3.7 for the AOSP-sourced evidence. The rotation-proof
> fix (§4 below) and the invariant-tightening fix (§5 below) are unaffected and remain current. Left
> as-written for historical record; do not treat any coordinate-origin claim in this section as current
> without cross-checking §3.7.

A review of §3.5's fix identified two further gaps, both real correctness bugs on any device whose
`SENSOR_INFO_ACTIVE_ARRAY_SIZE` rectangle does not start at `(0, 0)` — a shifted active array is a
documented, real Camera2 possibility (not every sensor's usable pixels begin at the readout origin) —
and one test-quality gap. This subsection documents the second fix pass; §3.5 above is otherwise
unchanged and still accurate.

**§1/§2: active-array coordinate-origin ownership, enforced by a distinct type, not a convention
readers must remember.** `ActiveArrayIntrinsics` (`core/astro-core/.../camera/ActiveArrayIntrinsics.kt`)
previously read `SENSOR_INFO_ACTIVE_ARRAY_SIZE`'s `left`/`top` only to compute `widthPx`/`heightPx`,
then discarded them — so `cxPx`/`cyPx` were composed through the sensor-to-buffer matrix with no
declared coordinate origin, silently correct only for the `activeLeft == activeTop == 0` case. Two new
fields, `coordinateOriginXPx`/`coordinateOriginYPx: Double` (both defaulting to `0.0`, so every
existing named-argument call site is unaffected), now make that origin an explicit, required part of
the value: `cxPx`/`cyPx` are documented to always be in **sensor matrix space** — the same space
`ImageInfo.getSensorToBufferTransformMatrix()` consumes — never rectangle-local, unless both origin
fields happen to be `0.0`. `resolveAnalysisBufferIntrinsics` (`:mobile`) now translates explicitly
before composing through the matrix: the focal-length-derived centre is
`activeLeft + activeWidth/2, activeTop + activeHeight/2`
(`activeArrayIntrinsicsFromFocalLength`'s own `coordinateOriginXPx`/`coordinateOriginYPx` parameters);
a calibrated `LENS_INTRINSIC_CALIBRATION` cx/cy — read as local to the pre-correction active-array
rectangle's own top-left, the fix task's prescribed convention — is translated as
`activeLeft + calibrationCx, activeTop + calibrationCy`. This uniformly uses `activeLeft`/`activeTop`
for both paths because `preCorrectionActiveArrayMatchesActiveArray` (§3.5, unchanged) already requires
an exact four-edge match whenever a pre-correction rectangle is reported, so
`preCorrectionLeft == activeLeft` is guaranteed in the only case the calibrated path is actually used.

**§2: crop-region bounds validated against the real rectangle, not an assumed `[0, width) x [0,
height)` range.** `ActiveArraySensorCropRegion` is renamed `ActiveArrayRect` and now serves two roles:
the ground-truth `SENSOR_INFO_ACTIVE_ARRAY_SIZE` rectangle (`leftPx`/`topPx`/`rightPx`/`bottomPx`, all
in sensor matrix space) and the region a buffer's inverse-mapped matrix infers
(`SensorToBufferMatrix3.toActiveArrayRect`, renamed from `toActiveArraySensorCropRegion`).
`resolveAnalysisBufferIntrinsics` validates the inferred crop against the actual reported rectangle's
four edges (`left >= activeLeft`, `top >= activeTop`, `right <= activeRight`, `bottom <= activeBottom`,
with the existing `1e-6`px float tolerance) — previously it validated against `0 until
activeArrayWidthPx`/`0 until activeArrayHeightPx`, which only happens to be correct when the active
array starts at the origin, and would have silently accepted a crop shifted by exactly the missing
offset.

**§4: rotation ownership beyond `AXIS_ALIGNED_0` is unproven, not proven-then-assumed — the resolver
now says so explicitly instead of composing it.** The prior fix's own no-double-rotation proof exercised
an `axisSwapped` pinhole model and `DisplayAlignedOpticalToBufferOpticalTransform`'s rotation
independently and checked each ran — it never actually combined them end-to-end against an
independently hand-derived expected pixel, so it could not have caught a sign or ordering error in
their composition. Two problems followed from treating that as sufficient proof: (1) the test itself
was not a real proof, and (2) no public CameraX/Camera2 contract this codebase has found states how an
`ORTHOGONAL_90`/`180`/`270`-classified sensor-to-buffer matrix should combine with
`CameraFrameMetadata.rotationDegrees` on a real device — composing them was algebraically elegant but
unproven. The fix applies the task's own escape hatch: `mapActiveArrayIntrinsicsThroughMatrix`
(`:core:astro-core`) is unchanged and stays fully general (pure algebra, independently tested,
explicitly documented as *not* a production-support claim), but `resolveAnalysisBufferIntrinsics`
(`:mobile`) now accepts only `AXIS_ALIGNED_0` from it — any other classified-but-composable class
(`ORTHOGONAL_90`/`180`/`270`) returns a new typed outcome,
`AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven(transformClass)`, instead of a `Resolved`
value. `CameraSessionIntrinsicsCoordinator.isSupportedTransformClass` was narrowed to match (only
`AXIS_ALIGNED_0` is "usable"), so the coherent-input gate does not spend its one-candidate-frame slot on
a frame the resolver would reject anyway. In place of the old mechanism-only test,
`CalibratedAnalysisBufferProjectionTest` now chains the real production functions
(`DisplayAlignedOpticalToBufferOpticalTransform.apply` → `projectBufferOpticalDirection` →
`PinholeProjectionModel.forGeometry`/`.project` → `CropScaleTransform.imageToDisplay`) end-to-end for
an off-axis ray (`normalizedX=0.20, normalizedY=0.07`) at `rotationDegrees` 0 and 90, asserting against
pixel values computed entirely by hand from the documented per-stage formulas (never by calling the
functions under test) — `(392.0, 265.2)` and `(312.0, 345.2)` respectively, both confirmed by a real
Gradle test run. `AnalysisBufferIntrinsicsResolverTest` separately proves `ORTHOGONAL_90`/`180`/`270`
each now yield `RotationOwnershipUnproven`, not `Resolved`.

**§5: the `axisSwapped`/`negateXInput`/`negateYInput` invariant now checks both fields it depends on.**
`CameraIntrinsics.init` previously allowed non-default axis-remapping flags whenever `reference is
AnalysisBuffer`, regardless of `source` — so a `LEGACY_FALLBACK`/`AnalysisBuffer` value (which never
goes through any sensor-to-buffer matrix composition) could in principle carry a stale or fabricated
flag. The check now requires **both** `source == CAMERA_CHARACTERISTICS` **and** `reference is
AnalysisBuffer`. `CameraIntrinsicsTest` adds constructor-rejection cases for `LEGACY_FALLBACK`,
`CAMERA_INTRINSIC_CALIBRATION`, and `CAMERA_CHARACTERISTICS`+`PhysicalSensor`, alongside the existing
`CAMERA_CHARACTERISTICS`+`AnalysisBuffer` acceptance case. The shared `analysisBufferIntrinsics` test
fixture (`:core:astro-core` `TestGeometryFixtures.kt`), which is `LEGACY_FALLBACK`-sourced, had its
now-invalid `axisSwapped`/`negateXInput`/`negateYInput` parameters removed rather than kept as dead
optional arguments — no test fixture should be able to construct the invalid combination the production
invariant exists to reject.

**§6: diagnostics gain active-rect and principal-point-basis fields.** `CameraCalibrationDiagnostics`
adds `activeArrayLeftPx`/`TopPx`/`RightPx`/`BottomPx`, `principalPointBasis` (currently always
`"SENSOR_MATRIX_SPACE"`), and `principalPointBeforeTranslationXPx`/`YPx` — all internal-debug-only, per
the existing convention, surfaced in the `CamDiagnosticTopPanels` text via
`buildCameraCalibrationDiagnosticText` as an `[left,top — right,bottom]` rect line, the basis label, and
the pre-/post-translation principal point pair.

**Validation (this environment, second fix pass).** `:core:astro-core:test` (462 tests: the CAM-2c fix
round 2 additions are five new `CameraIntrinsicsTest` axis-flag cases and
`CalibratedAnalysisBufferProjectionTest`'s two hand-derived end-to-end rotation tests) and
`:mobile:test{Internal,Public}DebugUnitTest` (332 tests each, growing by the non-zero-origin precise
tests, the inverse-crop-recovers-the-real-rectangle test, and the `ORTHOGONAL_90`/`180`/`270` →
`RotationOwnershipUnproven` tests in `AnalysisBufferIntrinsicsResolverTest`) all pass under a real
Gradle build. As before, no physical device or emulator is available in this sandboxed environment, so
live Pixel 9 validation of the origin-translation and `AXIS_ALIGNED_0`-only restriction remains an open
item for a session with real hardware access — and a real Pixel 9's rear camera is still expected to
hit `UnsupportedLogicalMultiCameraMapping` before any of this round's fixes would even be exercised, per
§3.5's logical-multi-camera paragraph, unchanged by this fix.

### 3.7 CAM-2c fix round 3 (§P1): the coordinate-origin fix itself was wrong — corrected with AOSP source evidence

§3.6's own coordinate-origin fix (§1/§2) turned out to be a regression, not a fix: it treated
`LENS_INTRINSIC_CALIBRATION` cx/cy and the CameraX sensor-to-buffer matrix's own domain as **absolute**
coordinates within the full pixel array, and added `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`/`.top` before
composing with the matrix. Android's own Camera2 contract says the opposite. This subsection documents
the correction and its source evidence; §3.6's other three fixes (the rotation-ownership proof, the
tightened `CameraIntrinsics` invariant, and the renamed `ActiveArrayRect` type) are unaffected and
remain current.

**The authoritative evidence**, quoted verbatim from AOSP's `system/media/camera/docs/metadata_definitions.xml`
— the source file that generates the official `CameraCharacteristics` Javadoc, fetched directly from
`android.googlesource.com/platform/system/media` in this session (not reconstructed from memory):

- `SENSOR_INFO_ACTIVE_ARRAY_SIZE`'s own `<details>`: *"The coordinate system for most other keys that
  list pixel coordinates, including `android.scaler.cropRegion`, is defined relative to the active
  array rectangle given in this field, with `(0, 0)` being the top-left of this rectangle."*
- `LENS_INTRINSIC_CALIBRATION`'s own `<details>`: *"Note that the coordinate system for this transform
  is the `android.sensor.info.preCorrectionActiveArraySize` system, where `(0,0)` is the top-left of
  the `preCorrectionActiveArraySize` rectangle. Once the pose and intrinsic calibration transforms have
  been applied to a world point, then the `android.lens.distortion` transform needs to be applied, and
  the result adjusted to be in the `android.sensor.info.activeArraySize` coordinate system (where
  `(0, 0)` is the top-left of the `activeArraySize` rectangle) ..."*
- `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`'s own `<details>` (confirming the *rectangle itself* —
  as opposed to *other keys expressed in its coordinate system* — genuinely is full-pixel-array-relative,
  which is why this distinction is real and not just a naming quirk): *"This rectangle is defined
  relative to the full pixel array; `(0,0)` is the top-left of the full pixel array."* — and its worked
  numeric example computes a pixel's position in `activeArraySize`-relative coordinates as
  `(x_i - activeArray.left, y_i - activeArray.top)`, a **subtraction** converting a full-array
  coordinate to a local one, never an addition converting a local one back to full-array form.

Two distinct coordinate spaces are both real, and Camera2 uses each in exactly one direction: (1) the
active-array/pre-correction rectangles' own `left`/`top`/`right`/`bottom`, as reported by
`CameraCharacteristics`, are placements **within the full pixel array**; but (2) essentially every
*other* pixel-coordinate-valued key — crop region, AE/AF regions, and (per the quotes above)
`LENS_INTRINSIC_CALIBRATION` — is expressed **local to** that rectangle's own top-left, never
translated by its full-array placement. §3.6's fix conflated these two, reading (2)-style values
(`LENS_INTRINSIC_CALIBRATION`'s cx/cy, and the focal-length-derived centre) as if they were (1)-style
values, then adding an offset that was never supposed to be there.

CameraX's own `ImageInfo.getSensorToBufferTransformMatrix()` Javadoc (fetched from AOSP's
`platform/frameworks/support` mirror, `camera/camera-core/.../ImageInfo.java`, `androidx-main`) states
only that the matrix maps "from the value of `CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE` to
`(0, 0, image.getWidth, image.getHeight)`" — it does not itself spell out local-vs-full-array any more
explicitly than that. This session could not locate the exact CameraX-camera2 implementation that
constructs this matrix at the pinned `1.3.4` version (AOSP's `androidx-main` HEAD has since
restructured that package away from where it lived at `1.3.4`, and no tagged `1.3.4` ref for this
artifact was found in the mirror in this sandboxed session). The conclusion here rests on Camera2's own
universal, cross-referenced convention — every other named "active array coordinate system" in the
same specification (crop region, AE/AF regions, `LENS_INTRINSIC_CALIBRATION`) is local, and the
matrix's own contract names that same characteristic key as its anchor with no separate full-array-offset
term ever mentioned — not on having traced CameraX's own `1.3.4` bytecode/source directly. This is
disclosed explicitly per the fix task's own instruction rather than silently treated as byte-level-verified.

**The fix.** `ActiveArrayIntrinsics.cxPx`/`cyPx` (`core/astro-core/.../camera/ActiveArrayIntrinsics.kt`)
are active-array-local again — the `coordinateOriginXPx`/`coordinateOriginYPx` fields §3.6 added are
removed entirely, not just defaulted to `0.0`. `resolveAnalysisBufferIntrinsics` (`:mobile`) no longer
adds `activeLeft`/`activeTop` to either the calibrated or focal-length-derived principal point; both
are used/derived directly as local values.

**Two distinct rectangle types, so the two coordinate spaces cannot be silently conflated at the type
level again.** `ActiveArrayRect` (`AnalysisBufferIntrinsicsMapping.kt`) keeps its full-pixel-array-relative
meaning — the real Camera2-reported rectangle, used only for the exact four-edge pre-correction-vs-active
match check and diagnostics/provenance, never folded into `cxPx`/`cyPx`. A new `ActiveArrayLocalRect`
type takes over the *inferred crop region* role: `toActiveArrayRect` is renamed `toActiveArrayLocalRect`
and now returns this type, documented as active-array-local (the same space the matrix itself
consumes). `resolveAnalysisBufferIntrinsics`'s crop-bounds validation now checks the inferred region
against `[0, activeArrayWidthPx] x [0, activeArrayHeightPx]` — never against `activeArrayRect`'s
full-pixel-array-relative edges, which was §3.6's own crop-bounds fix and is now understood to have
validated against the wrong coordinate space for a non-zero-origin device (harmlessly, since it never
actually rejected a resolution differently from the correct local check in the fixtures exercised, but
conceptually wrong for the same reason as the principal-point translation).

**Diagnostics simplified.** §3.6's `principalPointBeforeTranslationXPx`/`YPx` fields are removed — with
no translation step, "before" and "after" would always be identical, a dead field. `principalPointBasis`
keeps its role but its one value is renamed `PRINCIPAL_POINT_BASIS_ACTIVE_ARRAY_LOCAL` (was
`"SENSOR_MATRIX_SPACE"`). `CameraFrameMetadata`'s stale `[ActiveArraySensorCropRegion]` KDoc reference
(a name from before even §3.6's own rename to `ActiveArrayRect`) is fixed to `[ActiveArrayRect]`, and its
prose now states the sensor-to-buffer transform's domain is active-array-local.

**Tests.** `AnalysisBufferIntrinsicsResolverTest`'s non-zero-origin section is rewritten: for the active
array `[100,50]-[4132,3074]` (4032×3024, matching the zero-origin fixture's own size), the matrix
mapping it onto the buffer is now **identical** to the zero-origin `fullFrameTransform` — a genuine
non-zero origin changes nothing about the matrix or the composed principal point, which is itself the
clearest illustration of why §3.6's translation was unnecessary. New/rewritten tests assert: the
focal-derived and calibrated local centres both resolve to the exact buffer centre; an off-centre local
calibrated point maps via the same untranslated formula as the zero-origin case; a dedicated regression
test computes what the (reverted) translated value *would* have been and asserts it differs measurably
from the correct result; the inverse-crop region recovers the local `[0,0]-[4032,3024]` rectangle, not
the full-array `[100,50]-[4132,3074]` one; and a new test demonstrates the full-array/local coordinate
conversion (`activeArrayRect.leftPx + localCx == fullArrayCx`) as a documented relationship computed
from the diagnostics' own fields, explicitly never fed back into the resolver. `AnalysisBufferIntrinsicsMappingTest`
(`:core:astro-core`) gains a symmetric `ActiveArrayLocalRect` validation/width-height test pair
alongside the existing `ActiveArrayRect` ones, plus a test proving the two types' values are never
numerically interchangeable for a non-zero origin.

**Validation (this environment, third fix pass).** `:core:astro-core:test` (465 tests, up from 462 —
new `ActiveArrayLocalRect` tests and the full-array/local conversion test) and
`:mobile:test{Internal,Public}DebugUnitTest` (333 tests each, up from 332 — the rewritten non-zero-origin
section nets one additional test) all pass under a real Gradle build, alongside
`compileInternalDebugAndroidTestKotlin`, `lintInternalDebug`/`lintPublicDebug` (0 errors, 30 pre-existing
warnings, unchanged), and `assembleInternalDebug`/`assemblePublicDebug`. As with every prior CAM-2c pass,
no physical device or emulator is available in this sandboxed environment, so live Pixel 9 validation of
this corrected coordinate contract remains untested here — and, unchanged from §3.5/§3.6, a real Pixel
9's rear camera is still expected to hit `UnsupportedLogicalMultiCameraMapping` before any of this fix
would even be exercised.

### 3.8 CAM-2c fix §P2: focal-derived pixel pitch comes from the full pixel array, not the active array

The focal-length-derived fallback (used whenever no usable `LENS_INTRINSIC_CALIBRATION` exists — §3.4)
computed `fx`/`fy` in pixels as:

```text
fxActive = focalLengthMm / physicalSensorWidthMm  * activeArrayWidthPx
fyActive = focalLengthMm / physicalSensorHeightMm * activeArrayHeightPx
```

This mixes two distinct Camera2 keys that do not describe the same region: `SENSOR_INFO_PHYSICAL_SIZE`
is the physical size of the **full pixel array** (`SENSOR_INFO_PIXEL_ARRAY_SIZE`), while
`SENSOR_INFO_ACTIVE_ARRAY_SIZE` may be **smaller** — a real, documented Camera2 possibility, since the
active array excludes optically black or otherwise inactive border pixels the full pixel array still
reports. Dividing the physical millimetres by the smaller active-array pixel count overestimates pixel
pitch and so **underestimates** `fx`/`fy` in pixels whenever the two differ, producing a computed field
of view wider than the real one.

**The corrected formula** derives pixel pitch from the full pixel array instead:

```text
fxPixelGrid = focalLengthMm / physicalSensorWidthMm  * pixelArrayWidthPx
fyPixelGrid = focalLengthMm / physicalSensorHeightMm * pixelArrayHeightPx
```

`activeArrayWidthPx`/`activeArrayHeightPx` keep their existing, different role: they define the
active-array-local coordinate domain (`ActiveArrayIntrinsics.widthPx`/`heightPx`, per §3.7) and the
default geometric-centre principal-point approximation — never pixel pitch. The active array and the
full pixel array share the same underlying sensor pixel grid (a pixel is the same physical size in
both), so an `fx`/`fy` value derived from the full pixel array is exactly as valid a description of
active-array-local pixels as it is of full-pixel-array ones.

**Pixel-array metadata source.** `CameraCharacteristicsSnapshot` gains `pixelArrayWidthPx`/
`pixelArrayHeightPx: Int?`, sourced from `CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE` (an
`android.util.Size`, distinct from the `Rect`-typed active-array/pre-correction-active-array keys) in
`Camera2CharacteristicsSource`. Kept structurally distinct from `activeArrayLeft/Top/Right/Bottom`,
`preCorrectionActiveArray*`, and analysis-buffer dimensions — never inferred from
`activeArrayRight - activeArrayLeft`.

**A usable calibrated `K` needs no pixel-array size at all.** `LENS_INTRINSIC_CALIBRATION` already
supplies `fx`/`fy` directly in pixels, so `resolveAnalysisBufferIntrinsics`'s calibrated branch never
reads `pixelArrayWidthPx`/`pixelArrayHeightPx`. The pixel-array-size check lives exclusively in the
focal-length-derived fallback branch: a new typed outcome,
`AnalysisBufferIntrinsicsResolution.MissingPixelArraySize`, is returned only when that fallback is
actually reached (no usable calibration) **and** the pixel array size is missing or not strictly
positive. A device with usable `LENS_INTRINSIC_CALIBRATION` but no reported `SENSOR_INFO_PIXEL_ARRAY_SIZE`
still resolves normally with `quality = CALIBRATED`.

**Diagnostics** (`CameraCalibrationDiagnostics`, internal-debug-only, unchanged persistence policy) gain
`pixelArrayWidthPx`/`pixelArrayHeightPx: Int?` and `focalDerivationBasis: String`
(`PIXEL_ARRAY`/`LENS_INTRINSIC_CALIBRATION`) — surfaced in `buildCameraCalibrationDiagnosticText` as a
`pixel array: W×H` line (or `unavailable`), a `pixel-array vs active-array delta: Δw=…, Δh=…` line, and
a `focal derivation basis: …` line, alongside the existing `active rect (full pixel array): […]` line.

**Tests.** `ActiveArrayIntrinsicsTest`/`AnalysisBufferIntrinsicsResolverTest` add: pixel array equal to
the active array (the new default test fixture — every pre-existing focal-derived-quality test already
exercises this case unchanged, since it numerically matches the old formula exactly); pixel array
larger than the active array (`6.4×4.8mm`, `3.6mm` focal, pixel array `4100×3100`, active array
`4032×3024` — asserting `fx = 3.6/6.4×4100`, `fy = 3.6/4.8×3100`, explicitly distinct from the old
active-array-based values, and an end-to-end off-axis `PinholeProjectionModel.project` call proving the
projected pixel itself reflects the pixel-array-derived focal length); missing/invalid pixel-array
dimensions (`MissingPixelArraySize`, both when absent and when zero/negative); a usable calibrated `K`
resolving despite a missing pixel array; and a non-zero active-array origin combined with a
larger-than-active pixel array, confirming the two corrections (§3.7 and this one) compose
independently — `cx`/`cy` remain active-array-local while `fx`/`fy` reflect the pixel array alone.

**Test-contract correction (same fix, immediately after landing).** `CameraCalibrationDiagnosticFormatTest`'s
own fixture initially set `activeCxPx`/`activeCyPx` to `(2116.0, 1562.0)` — the full-pixel-array-relative
equivalent of the active-array-local centre `(2016.0, 1512.0)` — while declaring
`principalPointBasis = ACTIVE_ARRAY_LOCAL`, silently re-encoding the exact `+activeLeft`/`+activeTop`
regression §3.7 removed, in a test fixture rather than production code. Corrected to `(2016.0, 1512.0)`;
the full-array equivalent is now tested only as an explicitly separate, labelled conversion
(`activeArrayLeftPx + activeCxPx == 2116.0`) that the rendered diagnostic text never contains. A new
regression test asserts `activeCxPx == activeArrayWidthPx/2` and `activeCxPx != activeArrayLeftPx +
activeArrayWidthPx/2` directly (the `Y` analogues likewise); the non-zero-origin-plus-larger-pixel-array
resolver test (previous paragraph) now also asserts `principalPointBasis`/`focalDerivationBasis`
explicitly, using the same canonical `4100×3100` pixel array as the standalone pixel-array test above
(previously an unrelated `4200×3200`). No production code changed — `resolveAnalysisBufferIntrinsics`,
`CameraCalibrationDiagnostics.of`, and `buildCameraCalibrationDiagnosticText` were already correct; only
the test fixture's own hardcoded numbers were wrong.

**Validation (this environment, fourth fix pass, including the test-contract correction).**
`:core:astro-core:test` (468 tests, up from 465 — four new `activeArrayIntrinsicsFromFocalLength`
tests) and `:mobile:test{Internal,Public}DebugUnitTest` (341 tests each, up from 333 — six new resolver
tests, one new diagnostics-format test, and two more from the test-contract correction) all pass
under a real Gradle build, alongside `compileInternalDebugAndroidTestKotlin`,
`lintInternalDebug`/`lintPublicDebug` (0 errors, 30 pre-existing warnings, unchanged), and
`assembleInternalDebug`/`assemblePublicDebug`. Skew handling (§3.4/§3.5's `INTRINSIC_SKEW_TOLERANCE_PX`
policy), coordinate-origin handling (§3.7), rotation ownership (§3.6/§3.7's `RotationOwnershipUnproven`
outcome), the pinhole projection math itself (`mapActiveArrayIntrinsicsThroughMatrix`,
`PinholeProjectionModel`), and `CropScaleTransform`/`displayPoint` ownership are all unchanged by this
fix — confirmed by grep and by every pre-existing test in those areas continuing to pass unmodified. As
with every prior CAM-2c pass, no physical device or emulator is available in this sandboxed environment,
so live Pixel 9 validation of this corrected pixel-pitch formula remains untested here.

### 3.9 CAM-2c physical-camera provenance spike: CameraX upgrade, explicit physical binding, narrow
guard extension (this PR)

> **Corrected by "CAM-2c physical-camera provenance experiment fix" below (§3.10):** a review of this
> section's own implementation found two P1 correctness blockers — `CameraTopologyBuilder`'s capability
> mapping used a fabricated magic value (`29`) instead of the real
> `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA` constant (`11`), and
> `resolveCam2cForExplicitPhysicalCamera` could resolve calibrated intrinsics from physical-camera
> identity alone, without independently proving the sensor-to-buffer transform's own source domain —
> plus several evidence-integrity defects (async zoom fired-and-forgotten rather than awaited, a
> fabricated `logicalCameraId` fallback, only one of two possible `CameraInfo` shapes handled, a
> mis-labelled `focalLengthMm`-as-`fx` report line, locale-dependent number formatting, and a
> documented `adb shell am start` launch path that cannot reach a non-exported `Activity`). §3.10
> documents every fix. This section's own "satisfies every one of the task's five preconditions"
> framing (§5/§6 below) is itself corrected: physical-camera identity and transform-domain proof are
> now explicit, independent gates — five conditions were never sufficient on their own, since none of
> them ever said anything about the transform's own source domain. Left as-written for historical
> record; do not treat any claim in this section as current without cross-checking §3.10.
>
> **Answers to the specific claims a later review of this section flagged:** the CameraX
> physical-binding *code* built and tested in this pass is build/test-ready — every gate in this
> section's own Validation subsection is a real, first-party Gradle result — but **physical binding is
> not yet device-verified**, and it never was: no physical Pixel 9 (or any device/emulator) was
> available in either this session or the one that authored it. A fixture reaching
> `Cam2cPhysicalCameraResolution.Resolved` in a unit test is proof the *resolution logic* is correct
> given its inputs — it is **not** evidence that a real Pixel 9's actual reported identity matrix (the
> real-device identity matrix `docs/validation/cam_2c_pixel9_evidence.md` §3 already recorded) is
> usable; §3.10 makes the transform-domain proof an explicit, independent, currently-always-failing gate
> specifically so no fixture-level `Resolved` result can be mistaken for that. **No physical camera ID
> is selected by default anywhere in this codebase** — the experiment screen requires an explicit user
> tap on a candidate enumerated from real `CameraManager` topology, never a hardcoded or inferred
> default.

**Trigger.** Every prior CAM-2c pass above confirmed the same conclusion twice on a real Pixel 9
(`docs/validation/cam_2c_pixel9_evidence.md` §1/§3): `cameraId=0`, `logical=true`,
`physicalIds=2,3,4`, and `AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping`
every time, because this codebase's pinned `androidx.camera:camera-camera2:1.3.4` cannot bind a
concrete physical camera or read per-frame physical-camera provenance — both require `1.4.0-beta01`+.
§3.5's own KDoc named the two APIs needed (`CameraSelector.setPhysicalCameraId`,
`CameraInfo.getPhysicalCameraInfos()`) but never exercised them, since the version bump itself was out
of scope for every prior pass. This pass is the first to actually attempt the bump and build the
physical-binding path it unlocks.

**§1: CameraX version recon — minimum justified upgrade, not the newest stable release.** The current
stable CameraX line (checked via the official `developer.android.com/jetpack/androidx/releases/camera`
release notes, this session, July 2026) is `1.6.1`. Attempting that version against this project's
pinned `AGP 8.7.2`/`compileSdk 35` fails immediately at dependency resolution — Gradle's own AAR
metadata check reports `camera-camera2:1.6.1`/`camera-core:1.6.1`/`camera-lifecycle:1.6.1`/`camera-view:1.6.1`
each require **AGP 8.9.1+** and (for `camera-camera2`/`camera-view`) **compileSdk 36**, confirmed by an
actual `:mobile:compileInternalDebugKotlin` attempt in this session (not merely read from release
notes) that failed with exactly those errors. `1.5.0`/`1.5.1` were checked the same way (official
release notes): still require AGP `8.9.1`+. Bumping AGP/compileSdk is a project-wide change — it would
touch `:wear`, every `:core` module, and `:app`, none of which this spike is scoped to touch (task's
own "keep the migration narrowly scoped" instruction) — so **`1.4.2`** (the latest stable patch of the
first line exposing the physical-camera APIs; `CameraSelector.setPhysicalCameraId` landed at
`1.4.0-beta01` per this project's own prior KDoc investigation, and `1.4.0` itself went stable July 18,
2024) is the version actually used. Confirmed empirically: `1.4.2` compiles cleanly against the
existing `AGP 8.7.2`/`compileSdk 35` with zero new warnings.

Dependencies moved into the version catalog (`gradle/libs.versions.toml`, `camerax = "1.4.2"`,
`camerax-core`/`camerax-camera2`/`camerax-lifecycle`/`camerax-view`) — they were four hardcoded
`"androidx.camera:...:1.3.4"` strings in `mobile/build.gradle.kts` before, the only CameraX artifacts
in this project, all four now pinned to the exact same version through one catalog entry (task's "one
consistent version" requirement).

**API surface actually confirmed, not assumed.** Rather than trust scraped documentation (which proved
unreliable — several web fetches for `CameraSelector.Builder`/`CameraInfo` method signatures returned
truncated or navigation-only content), this pass unzipped the resolved `camera-core-1.4.2-api.jar`/
`camera-camera2-1.4.2-api.jar` from the local Gradle cache and ran `javap -p` directly against the
compiled classes — ground truth, not a claim about what the version *should* expose:

```
public final class androidx.camera.core.CameraSelector$Builder {
  ...
  public androidx.camera.core.CameraSelector$Builder setPhysicalCameraId(java.lang.String);
  ...
}
public interface androidx.camera.core.CameraInfo {
  ...
  public default boolean isLogicalMultiCameraSupported();
  public default java.util.Set<androidx.camera.core.CameraInfo> getPhysicalCameraInfos();
  ...
}
public final class androidx.camera.camera2.interop.Camera2CameraInfo {
  public androidx.camera.camera2.interop.Camera2CameraInfo(androidx.camera.camera2.internal.Camera2CameraInfoImpl);
  public androidx.camera.camera2.interop.Camera2CameraInfo(androidx.camera.camera2.internal.Camera2PhysicalCameraInfoImpl);
  public static androidx.camera.camera2.interop.Camera2CameraInfo from(androidx.camera.core.CameraInfo);
  public java.lang.String getCameraId();
  public <T> T getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics$Key<T>);
  ...
}
```

The two-constructor-overload evidence on `Camera2CameraInfo` (one for the logical `Camera2CameraInfoImpl`,
one for a physical `Camera2PhysicalCameraInfoImpl`) is exactly why the design below reuses the
existing `Camera2CharacteristicsSource` (§3.5) unmodified for a physical `CameraInfo` too — a `CameraInfo`
returned by `getPhysicalCameraInfos()` is still just a `CameraInfo`, and `Camera2CameraInfo.from(it)`
is evidently built to dispatch to the physical-backed constructor for it. This is **IMPLEMENTATION_OBSERVED**
evidence (a real compiled class inspected directly), not **API_GUARANTEED** (no Javadoc contract was
read describing this dispatch), and not **DEVICE_OBSERVED** (no physical device was available to run
it — see §9 below).

**Behavioral risks recorded, not merely asserted.** `minSdk` for `camera-camera2:1.4.2` is unchanged
from `1.3.4`'s floor (this project's own `minSdkMobile = 26` is untouched — the `1.5.0` line raised the
CameraX-internal floor to API 23, still below 26, so no risk here). No `ImageAnalysis`/`Preview`
behavioral change between `1.3.4` and `1.4.2` is documented in the official release notes that this
project's existing CAM-1c–CAM-2c pipeline depends on (crop rect, `sensorToBufferTransformMatrix`
contract, rotation handling) — confirmed by the full `:core:astro-core:test`/`:mobile:test{Internal,Public}DebugUnitTest`
suites passing unchanged (see the Validation subsection below) immediately after the version bump,
before any new code was added.

**Rollback point.** `gradle/libs.versions.toml`'s `camerax = "1.4.2"` and `mobile/build.gradle.kts`'s
four `libs.camerax.*` references are the entire diff for the version bump itself (task's slice A) —
reverting `camerax` to `"1.3.4"` and restoring the four hardcoded `androidx.camera:...:1.3.4` strings
is a two-file, mechanical revert with no other code depending on the version number directly.

**§2/§3: physical-camera topology recon and explicit binding (`internalDebug` only, task §3/§4).**
`mobile/src/internalDebug/java/dev/pointtosky/mobile/ar/camera/CameraTopologyBuilder.kt` enumerates
every rear Camera2 camera via the raw platform `CameraManager` (not `ProcessCameraProvider.availableCameraInfos`,
which only reports cameras CameraX considers bindable) — mirroring `Camera2CharacteristicsSource`'s own
existing, narrow, read-only `CameraManager` diagnostic bypass (§3.5's "Option C"), never used to bind a
camera. For each logical camera it records lens facing, `LOGICAL_MULTI_CAMERA` capability, every
declared physical camera ID (`CameraCharacteristics.getPhysicalCameraIds()`, never inferred from ID
ordering — task explicitly forbids that), focal lengths, sensor size, pixel/active/pre-correction
array rectangles, hardware level, capabilities, and `YUV_420_888` stream configurations relevant to
`ImageAnalysis`; the same fields are read for each declared physical candidate. Deterministic
text/JSON export (`CameraTopologyReport.kt`/`CameraTopologyJson.kt`) is reachable from the existing
CAM diagnostics dialog's new "Share topology" action (`CamDiagnosticFullReportDialog.kt`) — the same
export mechanism (`CamDiagnosticActions`) every other CAM diagnostics action already uses.

`mobile/src/internalDebug/java/dev/pointtosky/mobile/ar/camera/PhysicalCameraBindingExperiment.kt`
adds `explicitPhysicalCameraSelector(physicalCameraId)` (`CameraSelector.Builder().requireLensFacing(BACK).setPhysicalCameraId(id).build()`)
and `resolvePhysicalCameraBindingFromCameraInfo`, which — given the `CameraInfo` CameraX hands back
from a successful bind using that selector — finds the matching physical `CameraInfo` in
`getPhysicalCameraInfos()` and reads its characteristics via the existing `Camera2CharacteristicsSource`
(reused unmodified). `mobile/src/main/java/dev/pointtosky/mobile/ar/CameraPreview.kt` gained two
purely additive, default-`null`/no-op parameters — `cameraSelectorOverride: CameraSelector? = null`
and `onExplicitBindFailure: (String) -> Unit = {}` — following the exact pattern
`Camera2CharacteristicsSource`'s own optional `Context` parameter already established in this codebase
(a shared, production-safe type with only a debug-only caller ever supplying a non-default value).
When `cameraSelectorOverride` is non-`null` and the combined `Preview`+`ImageAnalysis` bind fails, this
composable does **not** retry the `DEFAULT_BACK_CAMERA` Preview-only fallback (task's "do not silently
fall back to the logical camera when physical binding fails") — it reports `onExplicitBindFailure`
instead. When binding succeeds, the bound camera's zoom is fixed to `1.0×`
(`EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO`) before `onCameraInfo` fires (task §9's mitigation — see
below). No production call site of `CameraPreview` (the live `ArScreen`) passes a non-`null`
`cameraSelectorOverride` — production behavior is unchanged, confirmed by `git diff` scope and by
`:mobile:test{Internal,Public}DebugUnitTest` passing with identical counts to the pre-upgrade baseline
outside the new test files.

The experiment is exposed as its own standalone screen/`Activity`
(`PhysicalCameraBindingExperimentActivity`, `mobile/src/internalDebug/java/dev/pointtosky/mobile/ar/camera/PhysicalCameraBindingExperimentScreen.kt`,
registered only in `mobile/src/internalDebug/AndroidManifest.xml`) rather than layered onto the live
AR screen's own camera session — most devices only support one open Camera2 session at a time, so a
second, parallel bind attempting a different physical selector while the AR screen's own
`DEFAULT_BACK_CAMERA` session is still open would contend for the same hardware.

> **Corrected by §3.10:** this paragraph originally documented launching the experiment via
> `adb shell am start -n <applicationId>/....PhysicalCameraBindingExperimentActivity`. That is not a
> launch mechanism this codebase stands behind for an `android:exported="false"` component — §3.10
> replaces it with a verified in-app entry point.

Confirmed
compile-time `internalDebug`-only two ways in this session: (1) `aapt dump xmltree` on the built
`internal-debug` APK shows the activity present, the same command on the `public-debug` APK shows it
absent; (2) `strings` over the `public-debug` APK's `.dex` files finds zero occurrences of
`PhysicalCameraBindingExperiment`/`resolveCam2cForExplicitPhysicalCamera`/`verifyPhysicalCameraProvenance`
anywhere in the compiled bytecode.

**§5/§6: provenance tuple, narrow guard extension — without touching the existing guard at all.**
`mobile/src/internalDebug/java/dev/pointtosky/mobile/ar/camera/PhysicalCameraProvenance.kt` defines
`PhysicalCameraProvenance(logicalCameraId, physicalCameraId, bindingMethod, confidence)` and the typed
`PhysicalCameraBindingResolution` sealed interface (`Bound`/`PhysicalCameraBindingUnavailable`/
`PhysicalCameraIdentityUnverified`/`PhysicalCameraCharacteristicsMismatch`). The pure, unit-tested
`verifyPhysicalCameraProvenance` function is the entire decision: `Bound` only when (1) a physical
`CameraInfo` matching the requested candidate was actually found among `getPhysicalCameraInfos()`, (2)
its own characteristics were read successfully, (3) that snapshot's own `cameraId` equals the requested
candidate, and (4) that snapshot does **not** itself report `isLogicalMultiCamera` (a genuine physical
sub-camera is never expected to declare `LOGICAL_MULTI_CAMERA` — if one somehow does, this codebase
does not trust it enough to treat it as a proven, single-sensor source, and rejects it as a mismatch
rather than guessing).

The key design choice: **`AnalysisBufferIntrinsicsResolver.kt`'s `resolveAnalysisBufferIntrinsics` and
its `UnsupportedLogicalMultiCameraMapping` guard (§3.5) are not modified at all** — zero lines changed
in that file by this pass. A verified physical binding's own `CameraCharacteristicsSnapshot` (read
directly from the physical `CameraInfo`, never the logical one) simply does not trigger that guard in
the first place, since its own `cameraId` is the physical ID and its own `isLogicalMultiCamera` reads
`false` — exactly the same as any ordinary, non-multi-camera device's snapshot would. A new, additive
entry point (`Cam2cPhysicalCameraResolution.kt`, `resolveCam2cForExplicitPhysicalCamera`) wraps a
`PhysicalCameraBindingResolution` and, only for `Bound`, calls the **unchanged**
`resolveAnalysisBufferIntrinsics(snapshot, ...)` (the existing snapshot-based overload) with that exact
physical snapshot — never re-reading, never substituting a different snapshot. This satisfies every
one of the task's five preconditions (§6) without weakening the guard for the *ordinary* implicit
session path, which still resolves through the unchanged `resolveCameraIntrinsicsPreferringCalibration`
and still hits `UnsupportedLogicalMultiCameraMapping` exactly as documented in every prior CAM-2c
section above — confirmed by the pre-existing `AnalysisBufferIntrinsicsResolverTest` cases for that
guard continuing to pass, completely untouched, alongside the new tests.

**§9: dynamic physical-camera switching — an honest, documented limitation, not a solved problem.**
The `javap`-inspected `CameraInfo`/`Camera`/`Camera2CameraInfo` API surface at `1.4.2` exposes no
per-frame or live/observable physical-camera-identity signal — `getPhysicalCameraInfos()` is a static
`Set`, not a `LiveData`, and no capture-result-level physical-camera-ID callback exists anywhere in
this pinned version. `PhysicalCameraProvenance`'s own KDoc states this explicitly: the provenance
proof is **session-scoped, established once at bind time**, not a frame-by-frame guarantee. The
practical mitigations the task itself suggests are implemented — the experiment session fixes zoom to
`1.0×` immediately after bind (`EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO`) and enables no camera
extensions/effects (this app defines none) — but these are mitigations, not proofs of frame-level
stability. No typed "provenance changed mid-session" outcome was added, because no CameraX 1.4.2 API
exists to detect that condition at all; adding a typed outcome for a condition this codebase cannot
observe would itself be exactly the kind of overclaim §3.6-§3.8 above have repeatedly corrected. This
remains the honest, current gap: a future CameraX version (or a raw `CameraCaptureSession.CaptureCallback`
reading `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`, itself only available at API 28+ and
not currently wired into any capture callback in this codebase) would be needed to close it.

**§7: sensor-to-buffer transform domain — unchanged, still unresolved.** This pass adds no new
evidence about `ImageInfo.getSensorToBufferTransformMatrix()`'s real source-coordinate domain beyond
what §3.5-§3.8 and `docs/validation/cam_2c_pixel9_evidence.md` §3/§4 already established: the one
Pixel 9 observation on record is an **identity** matrix that does not match the
`ASSUMED_WHOLE_ACTIVE_ARRAY_LOCAL` hypothesis, and the pinned CameraX implementation's exact source
domain has not been source-traced. `SourceDomainBasis`/`WholeActiveArrayHypothesisVerdict` (§3.5's §4
subsection) are untouched by this pass. A physical-camera-bound session's own frame would, if
device-validated, produce a *new* data point for this same open question — see §10 below for why that
validation could not be run here.

**Automated tests (task §11).** `mobile/src/testInternalDebug/java/dev/pointtosky/mobile/ar/camera/PhysicalCameraProvenanceTest.kt`
(pure `verifyPhysicalCameraProvenance` cases: matching identity verifies as `Bound`; no physical
`CameraInfo` found is `PhysicalCameraIdentityUnverified`, never `Bound`; unreadable/mismatched/
logical-reporting characteristics are all `PhysicalCameraCharacteristicsMismatch`, never `Bound`; two
independent bindings to different candidates never cross-contaminate provenance) and
`Cam2cPhysicalCameraResolutionTest.kt` (a verified `Bound` binding with a valid transform resolves
`Resolved`; a non-`Bound` binding is reported as `BindingFailure`, never resolved; a missing transform
on an otherwise-verified binding does not publish calibrated intrinsics; the exact physical snapshot
passed into `Bound` — proven via a deliberately distinguishing focal length/active array fixture — is
what `resolveAnalysisBufferIntrinsics` actually consumes, never a substituted logical-camera value) and
`CameraTopologyReportTest.kt` (text/JSON export formatting) are new. No test asserts a hardcoded Pixel
9 physical ID (`"2"`/`"3"`/`"4"`) as universally correct — every fixture uses whichever ID the specific
test scenario names, and a grep of the new production files for the literal strings `"2"`/`"3"`/`"4"`
in this session found zero hardcoded matches (task's "no hardcoded Pixel 9 physical ID leaks into
general production logic" requirement). The pre-existing `AnalysisBufferIntrinsicsResolverTest` (§3.5's
own fixture family, `isLogicalMultiCamera = true`, `cameraId = "0"`) is unmodified and continues to
prove the ordinary logical-camera path still returns `UnsupportedLogicalMultiCameraMapping` with no
physical binding supplied.

**§10: physical-device validation — could not be run in this environment; see the boundary this
crosses.** Every prior CAM-2c pass recorded above ran in a sandboxed authoring environment with no
physical device or emulator (`no /dev/kvm`, no hardware-virtualization CPU flags, `adb devices -l`
lists none) and said so explicitly. **This pass ran in a different kind of environment** — a Claude
Code remote-execution container with real network access (used to install a JDK 17 toolchain and a
real Android SDK, and to run a real, non-fake Gradle build for the first time against this exact
CameraX-1.4.2-upgraded tree) — but it is still a cloud container with **no physical Android device or
emulator attached**, and no path exists in this session to attach one. Every code/test/lint/assemble
gate in this section's own Validation subsection below is a real, first-party Gradle execution
result, not a fabricated one — but the task's §9 (fixed-zoom/no-extensions mitigation, implemented)
and §10 (center/edge/corner residual capture on a real Pixel 9, a live-camera exercise) both
fundamentally require a physical device this session does not have. No residual data, no "selected
physical camera and evidence supporting that selection" from a live bind, and no confirmation that
`resolvePhysicalCameraBindingFromCameraInfo` actually reaches `Bound` (rather than
`PhysicalCameraIdentityUnverified`, if a real Pixel 9's `getPhysicalCameraInfos()` behaves differently
than the `javap`-inspected API surface promises) exist from this pass. This is not a smaller version of
the same "no device" gap every prior pass recorded — it is the same gap, restated honestly rather than
worked around by guessing what a real bind would report.

**Validation (this environment) — real Gradle, a provisioned JDK 17 + Android SDK (`platform-tools`,
`platforms;android-35`, `build-tools;35.0.0`), the same kind of environment deviation every prior
CAM-2c pass recorded (though via network access rather than pre-staged artifacts).**
- `:core:astro-core:test`: **PASS, 468/468** (unchanged — this pass touches no `:core:astro-core` code).
- `:mobile:testInternalDebugUnitTest`: **PASS, 426/426** (up from 412 baseline — 14 new tests across
  `PhysicalCameraProvenanceTest`/`Cam2cPhysicalCameraResolutionTest`/`CameraTopologyReportTest`).
- `:mobile:testPublicDebugUnitTest`: **PASS, 370/370** (unchanged from baseline — confirms the new
  `internalDebug`-only code contributes zero tests, and zero regressions, to the public variant).
- `:mobile:compileInternalDebugKotlin`/`compilePublicDebugKotlin`: **PASS**, zero new warnings beyond
  the two pre-existing, unrelated ones (`RotationFrame`'s deprecated `defaultDisplay`,
  `DeviceLocationRepository`'s missing `@OptIn`).
- `:mobile:compileInternalDebugAndroidTestKotlin`: **PASS.**
- `:mobile:lintInternalDebug`/`lintPublicDebug`: **PASS, 0 errors** (28/26 warnings, down from the
  prior pass's 30/30 baseline reading — two new, pre-existing-pattern warnings from this pass's own
  `internalDebug`-only manifest activity, `LockedOrientationActivity`/`DiscouragedApi` on
  `android:screenOrientation="portrait"`, mirror the identical warning `MainActivity` itself already
  carries in `mobile/src/main/AndroidManifest.xml` — not a new class of problem, the same accepted
  pattern this app already uses for its one other launchable `Activity`).
- `:mobile:assembleInternalDebug`/`assemblePublicDebug`: **PASS**, both debug APKs built; manifest and
  `.dex`-content inspection (above) confirms the `internalDebug`-only boundary holds in the actual
  packaged output, not merely in source-set configuration.
- Connected instrumented tests / physical Pixel 9 validation (task §9/§10): **not executed** — no
  physical device or emulator is available in this environment (see §10 above for exactly what kind of
  environment this was and was not).

### 3.10 CAM-2c physical-camera provenance experiment fix: two P1 correctness blockers, five evidence-
integrity defects (this PR)

**Scope, restated.** Every fix below stays inside the CameraX physical-binding experiment and its
`internalDebug` diagnostics — `AnalysisBufferIntrinsicsResolver.kt`'s ordinary
`UnsupportedLogicalMultiCameraMapping` guard, CAM-2a projection math, the renderer, the catalog, the
detector, the matcher, and magnetic declination handling are all untouched, confirmed by `git diff`
scope and by every pre-existing test outside this experiment's own files continuing to pass unmodified.

**Fix 1 — P1: `CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA` is `11`, not
`29`.** `CameraTopologyBuilder.kt`'s `capabilityLabel` mapped raw capability code `29` to
`LOGICAL_MULTI_CAMERA` — a fabricated value, not the real Android constant. Confirmed via `javap -p
-constants` against this project's own pinned `android-35` `android.jar`:
```
public static final int REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA = 11;
```
Fixed by referencing the real constant directly. The guard needed to avoid Android Lint's `InlinedApi`
warning (the same pattern `Camera2CharacteristicsSource.kt`'s own `isLogicalMultiCamera` derivation
already uses) is now expressed via an injectable `apiLevel: Int = Build.VERSION.SDK_INT` parameter
rather than a bare `Build.VERSION.SDK_INT` reference — this project has no Robolectric dependency to
fake `SDK_INT` in a plain JVM test, so the parameter is what makes `capabilityLabel` unit-testable at
all; empirically confirmed lint-safe by an actual `:mobile:lintInternalDebug`/`lintPublicDebug` run
(0 errors, identical warning counts to the pre-fix baseline — no new `InlinedApi` finding).
`CameraTopologyBuilderCapabilityLabelTest` proves the real constant maps correctly, that `29` (the old,
wrong value) maps to a generic label instead, that unrelated capabilities never map to
`LOGICAL_MULTI_CAMERA`, and that a Pixel-9-like capability array is correctly reported as logical.

**Fix 2 — P1: physical-camera identity and transform-domain identity are now independent, both-required
proofs.** The prior revision's `resolveCam2cForExplicitPhysicalCamera` called
`resolveAnalysisBufferIntrinsics` the moment `PhysicalCameraBindingResolution.Bound` was reached — i.e.
a verified physical sensor alone was treated as sufficient, even though `resolveAnalysisBufferIntrinsics`'s
own K-composition math (§3.5) carries its own, separate, never-proven assumption that the sensor-to-
buffer matrix's source domain is active-array-local. A verified physical binding over the real Pixel 9's
own recorded identity matrix (`docs/validation/cam_2c_pixel9_evidence.md` §3) could therefore have
resolved a calibrated `AnalysisBuffer` `K` despite that matrix already being known, from §3's own finding,
to fail the one hypothesis this codebase can currently test.

Fixed with a new, `internalDebug`-only `SensorToBufferDomainProof` sealed type
(`SensorToBufferDomainProof.kt`): `ProvenActiveArrayLocal` (the *only* variant that unlocks calling
`resolveAnalysisBufferIntrinsics` — the one domain its own math assumes),
`ProvenPreCorrectionActiveArrayLocal`/`ProvenAnalysisSourceDomain(basis)` (proven bases this resolver's
current math does not yet handle, reserved for a future pass), `Unresolved` (no proof — the *only*
outcome any current, real code path in this codebase can automatically produce), and
`HypothesisMismatch(verdict)` (the existing `assessWholeActiveArrayMappingHypothesis` diagnostic
returned a mismatch — evidence, never proof). `resolveCam2cForExplicitPhysicalCamera` now takes a
`domainProof: SensorToBufferDomainProof` parameter and checks `binding` (must be `Bound`) *and*
`domainProof.unlocksAnalysisBufferResolution()` — in that order, so a binding failure always wins over
an unresolved/mismatched domain — before ever calling `resolveAnalysisBufferIntrinsics`; failing either
check returns the new `Cam2cPhysicalCameraResolution.DomainNotProven` outcome, never `Resolved`.

`evidenceOnlySensorToBufferDomainProof` is the one automatic way this codebase computes a
`SensorToBufferDomainProof` today: it runs the existing `assessWholeActiveArrayMappingHypothesis`
against the *physical* camera's own active-array dimensions and returns `HypothesisMismatch` on a real
mismatch, or `Unresolved` for every other outcome — **including a match**, since per that function's
own KDoc a match is still not proof (task requirement: "preserve the existing whole-active-array
hypothesis diagnostic as evidence only; it must not become proof"). No automatic code path in this
codebase can construct a `Proven*` variant; those exist so a future pass that actually source-traces or
device-proves a real CameraX contract has a typed place to record that proof, and so tests
(`Cam2cPhysicalCameraResolutionTest`) can exercise the `Resolved` path that proof unlocks without
waiting for that future pass. Concretely: **the experiment's real, current behavior on every device this
codebase can run on today is that `resolveCam2cForExplicitPhysicalCamera` can never reach `Resolved`
automatically** — it always sees `Unresolved` or `HypothesisMismatch`. This is intentional and matches
the honest state of every prior CAM-2c pass's own findings; a `Resolved` fixture-level test result
(`Cam2cPhysicalCameraResolutionTest`'s own "explicitly proven compatible domain" case) is a proof the
*resolution logic* is correct given a hypothetical future proof, never evidence that a real Pixel 9's
actual identity matrix is usable.

New tests (`Cam2cPhysicalCameraResolutionTest`, `SensorToBufferDomainProofTest`): a verified binding with
an unresolved domain is `DomainNotProven`, never `Resolved`; a verified binding with a hypothesis
mismatch is `DomainNotProven`; a verified binding with `ProvenActiveArrayLocal` and a valid transform is
`Resolved`; a verified binding with `ProvenPreCorrectionActiveArrayLocal`/`ProvenAnalysisSourceDomain`
(proven, but not the one basis this resolver's math handles) is still `DomainNotProven`; a binding
failure wins over any domain proof, even `ProvenActiveArrayLocal`; the exact real-Pixel-9 shape (a
trivial identity matrix, `Unresolved` domain) never resolves.

**Fix 3 — bound `CameraInfo` resolution now handles both possible CameraX shapes.**
`resolvePhysicalCameraBindingFromCameraInfo` previously assumed the `CameraInfo` CameraX hands back from
a physical-camera-selector bind is always the *logical* camera's own, and searched only its
`getPhysicalCameraInfos()` set — but no CameraX contract this codebase has found guarantees that; the
returned `CameraInfo` could plausibly already be backed by the physical sensor directly on some
device/HAL. Fixed with a new, pure, unit-tested `selectPhysicalCameraInfoSource` (`PhysicalCameraProvenance.kt`)
that takes only plain `String`s (no `CameraInfo`/`Camera2CameraInfo` mocking needed) and determines,
via exact identity comparison — never ID ordering — which shape applies: **Shape A**
(`PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL`, checked first) when the bound `CameraInfo`'s
own Camera2 ID already equals the requested candidate, reading characteristics from it directly; **Shape
B** (`PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO`) when the bound `CameraInfo` is
the logical camera's own and the requested ID is found among its declared physical candidates, reading
characteristics from that nested `CameraInfo`. `PhysicalCameraProvenance` now carries `bindingSource`
explicitly, so a report can always show which shape a given session actually matched. Eight new
`selectPhysicalCameraInfoSource` unit tests (`PhysicalCameraProvenanceTest`) cover both shapes, shape-A
precedence when both would technically match, no-match, an unresolvable bound ID never accidentally
matching shape A, and no-ID-ordering-dependence.

**Fix 4 — `logicalCameraId` is nullable and never fabricated.** The prior revision defaulted a `null`
logical ID to the physical candidate itself (`logicalCameraId ?: requestedPhysicalCameraId`) —
presenting an unobserved equality as if it were real. `PhysicalCameraProvenance.logicalCameraId` is now
`String?`, `null` whenever the logical ID is genuinely unavailable (always, for Shape A — no separate
logical `CameraInfo` is ever identified in that shape) or could not be read (Shape B). The experiment
report explicitly renders `"known(id)"` vs. `"unavailable"` rather than ever presenting a bare value a
reader could mistake for an observed ID. New tests assert the `null` is preserved, not substituted.

**Fix 5 — zoom application is now actually awaited before provenance is established.**
`CameraControl.setZoomRatio` returns an asynchronous `ListenableFuture<Void>`; the prior revision fired
it and immediately called `onCameraInfo` as if `1.0×` were already applied — never guaranteed.
`CameraPreview.kt` now has a shared `ListenableFuture.awaitCompletion(executor)` suspend adapter (the
same pattern its own pre-existing `getCameraProvider()` already used for
`ProcessCameraProvider.getInstance()`'s future) and suspends on the zoom future's actual completion
before calling `onCameraInfo` for a physical-camera-experiment bind; failure returns a new typed
`onExplicitBindFailure(EXPLICIT_SELECTOR_ZOOM_FAILED)` instead. The awaited suspension point is itself a
new disposal-race window, closed the same way every other suspension point in this file already is: an
explicit `session.isDisposed` re-check immediately after the await, before `onCameraInfo` fires.
`PhysicalCameraProvenance`'s own KDoc, and `EXPLICIT_PHYSICAL_CAMERA_FIXED_ZOOM_RATIO`'s KDoc, are both
corrected to state plainly that the fixed zoom is a **mitigation** — it removes the one lens-switch
trigger this codebase can control — **never a proof** that no OEM physical-camera switch can occur, and
never a claim of frame-level physical-camera-identity stability (no CameraX API in this pinned version
can verify that even in principle — see Fix 2's `Unresolved`-only behavior above, and §3.9's own §9
subsection, unaffected by this fix).

**Fix 6 — the experiment report now prints the real buffer-space `K`, not `focalLengthMm` mislabelled as
`fx`.** The prior revision's `buildPhysicalCameraExperimentReportText` printed `"K: fx=" +
CameraIntrinsics.focalLengthMm` — a physical, millimetre-space quantity — conflating it with the
buffer-space pixel quantity `CameraCalibrationDiagnostics.bufferFxPx`. The report now prints
`fxPx`/`fyPx`/`cxPx`/`cyPx` from `CameraCalibrationDiagnostics.bufferFxPx`/`bufferFyPx`/`bufferCxPx`/
`bufferCyPx` under an explicit `"K (buffer-space px):"` label, with `focalLengthMm` printed on its own,
separately labelled line. The formatting logic was extracted into `formatCam2cResultLines` (previously
inlined inside a function that only accepted the file-private `ExperimentPhase`, making it untestable
from `testInternalDebug`) so `PhysicalCameraExperimentReportFormatTest` can construct a
`Cam2cPhysicalCameraResolution.Resolved` with deliberately distinct `bufferFxPx`/`focalLengthMm` values
and assert the printed `fxPx=` is the buffer-space value, never the millimetre one.

**Fix 7 — topology text formatting is locale-independent.** `CameraTopologyReport.kt`'s
`"%.2f".format(value)` calls used the JVM platform default locale, which renders `%.2f` with a comma
decimal separator under many locales (e.g. German) — silently corrupting this deterministic diagnostics
export on a device configured for such a locale. Every call now goes through a `formatDecimal` helper
using `String.format(Locale.ROOT, "%.2f", value)` explicitly. `CameraTopologyReportTest`'s new test sets
`Locale.setDefault(Locale.GERMANY)` for its duration and asserts the rendered text still uses dot
decimals (`"9.79x7.37"`, never `"9,79x7,37"`). JSON export was already locale-independent (kotlinx.
serialization's own number encoding never consults the platform default `Locale`) and required no fix.

**Fix 8 — the experiment is now reachable through a launch path that actually works.**
`PhysicalCameraBindingExperimentActivity` is (and remains) `android:exported="false"`; the prior
revision's own KDoc and the manifest's own comment both instructed launching it via `adb shell am start
-n ...`, a mechanism this codebase cannot stand behind for a non-exported component. The actual, fixed
entry point is in-app: the existing CAM diagnostics dialog (`CamDiagnosticFullReportDialog.kt`) gained
an "Open physical-camera experiment" action that calls `context.startActivity(Intent(context,
PhysicalCameraBindingExperimentActivity::class.java))` — a same-app, same-process `Intent`, always
permitted regardless of `exported`, which restricts only *other* apps/processes. `exported="false"` is
unchanged, per the task's explicit "keep `android:exported=false`" instruction. Since this project has
no Robolectric/espresso-intents dependency to exercise a real `Activity` launch in a plain JVM test, the
one thing that *can* silently drift and break this launch — the class name the `Intent` resolves to
diverging from the manifest's own declared `android:name` after a future rename — is isolated behind a
pure `PHYSICAL_CAMERA_BINDING_EXPERIMENT_ACTIVITY_CLASS_NAME` constant (via reflection,
`PhysicalCameraBindingExperimentActivity::class.java.name`) and unit-tested
(`PhysicalCameraBindingExperimentActivityLaunchTest`) against the exact manifest string.

> **Corrected by §3.11 below:** the class-name comparison above proves the two strings match, never that
> the in-app action actually launches the registered `Activity` — §3.11's Fix C replaces the inlined
> `Intent(...)` construction with one `buildPhysicalCameraBindingExperimentIntent(context)` function and
> adds real, `PackageManager`-backed instrumented tests.

**Fix 9 (this documentation pass) and Fix 10 (boundary/validation).** `CamDiagnosticsInternalDebugVariantBoundaryTest`/
`CamDiagnosticsPublicVariantBoundaryTest` were extended with every new class this fix pass and the prior
pass introduced (`PhysicalCameraProvenance`, `PhysicalCameraBindingResolution`,
`PhysicalCameraInfoSelection`, `Cam2cPhysicalCameraResolution`, `SensorToBufferDomainProof`,
`CameraTopologyReport`/`Entry` and their Kt facades, etc.) — present on `internalDebug`, absent from
`publicDebug`. See the Validation subsection below for the full real-Gradle gate list this fix pass ran.

**Validation (this session) — real Gradle, same provisioned JDK 17 + Android SDK + Gradle 8.14.3 as
§3.9's own pass.**
- `:core:astro-core:test`: **PASS, 468/468** (unchanged — no `:core:astro-core` code touched).
- `:mobile:testInternalDebugUnitTest`: **PASS, 451/451** (426 baseline + 25 new: capability-label,
  domain-proof, both-shape selection, nullable-logical-id, K-format, locale, and launch-path tests).
- `:mobile:testPublicDebugUnitTest`: **PASS, 370/370** (unchanged — confirms zero leakage).
- `:mobile:compileInternalDebugKotlin`/`compilePublicDebugKotlin`: **PASS**, zero new warnings.
- `:mobile:compileInternalDebugAndroidTestKotlin`: **PASS.**
- `:mobile:lintInternalDebug`/`lintPublicDebug`: **PASS, 0 errors** (28/26 warnings — identical counts
  to §3.9's own pass; no new `InlinedApi`/other finding from the `capabilityLabel` guard rewrite).
- `:mobile:assembleInternalDebug`/`assemblePublicDebug`: **PASS**, both debug APKs built.
- Physical Pixel 9 validation: **still not executed** — no physical device or emulator is available in
  this environment, same as every prior pass. This fix pass corrects code-level defects a careful review
  found without a device; it does not, and cannot, newly establish device-level facts.

### 3.11 CAM-2c physical-camera provenance experiment: runtime correctness fix — stale callbacks, terminal
lifecycle, launch-path testability (this PR)

**Scope, restated.** Confined to `CameraPreview`'s callback delivery, the experiment's own session
state/lifecycle, and its launch-path test coverage. CAM-2a projection math, transform-domain policy
(§3.10 Fix 2's `SensorToBufferDomainProof` gate), intrinsics math, the renderer, the detector, the
matcher, the star catalog, and the ordinary logical-camera guard are all untouched.

**Fix A — stale callback capture.** `CameraPreview`'s `ImageAnalysis.Analyzer` and bind coroutine are
installed inside a `DisposableEffect(Unit)` that runs its body exactly once per composition (§4.4's own
"Lifecycle ownership" section already documents this for the bind/dispose race; the same one-shot effect
also owns callback installation). The experiment's session composable
(`PhysicalCameraBindingSession`) constructs a *brand-new* `onFrameMetadata`/`onCameraInfo`/
`onExplicitBindFailure` lambda instance on every recomposition (every state transition) — before this
fix, a later recomposition's new lambda never reached the already-installed, long-lived analyzer, which
kept invoking whichever lambda instance existed the moment the effect first ran (e.g. one that read a
`Binding`-era value, permanently, even after the session had since resolved to `Bound`).

Fixed with `rememberStableCallback<T>(latest: (T) -> Unit): (T) -> Unit` (`CameraPreview.kt`, production,
shared by every build variant — not `internalDebug`-only, since the defect class applies to any caller
that passes a changing lambda into `CameraPreview`): backed by `rememberUpdatedState`, the returned
function reference is `remember`ed exactly once (so passing it into the long-lived analyzer registration
never itself triggers a rebind), while every *invocation* of that stable reference reads whichever
`latest` lambda this composable most recently recomposed with. `CameraPreview` wraps all three of its own
callback parameters through it before installing them.

On the experiment side, the prior private sealed `ExperimentPhase` (`Binding`/`Bound`/`ExplicitBindFailed`
— `Bound` bundled the binding *and* the frame together as one variant, so receiving one without the other
required an awkward, easy-to-get-wrong intermediate representation) was replaced with a flat, pure data
type: `ExperimentSessionState` (`bindingResolution`, `latestFrame`, `explicitBindFailureReason` as three
independent, nullable fields) plus three reducer functions — `reduceCameraInfoResolved`, `reduceFrame`,
`reduceExplicitBindFailure` (`mobile/src/internalDebug/.../camera/ExperimentSessionState.kt`). Each
reducer: is a no-op unless its `attemptId` parameter matches the session's own `attemptId` (guards against
a late callback from a superseded attempt — see Fix B); is a no-op once
`ExperimentSessionState.isTerminallyFailed` (see Fix B); handles either callback order (a frame before
`onCameraInfo`, or vice versa) by keeping `bindingResolution`/`latestFrame` independent; never regresses
or erases an already-resolved `bindingResolution` (`reduceFrame` has no way to null it out); and
recomputes the CAM-2c result (§3.10 Fix 2's `evidenceOnlySensorToBufferDomainProof` +
`resolveCam2cForExplicitPhysicalCamera`, unchanged) once both a binding and a frame are present, using the
*physical* camera's own active-array dimensions — never the logical camera's — exactly as before.

New tests: `ExperimentSessionStateTest` (10 pure JVM tests) — a fresh session has no binding/frame/result;
binding-before-frame and frame-before-binding both retain what they're given and reach the same
`DomainNotProven` result once both are present (order independence); a later frame refreshes
`latestFrame`/`cam2cResult` without erasing `bindingResolution`; a resolved binding never regresses;
explicit failure is terminal (every later reducer call, including a second failure, is a no-op); a
callback for a superseded `attemptId` never mutates the session; two independent attempts never
cross-contaminate. `PhysicalCameraExperimentReportFormatTest` gained 3 new cases exercising
`buildPhysicalCameraExperimentReportText` end-to-end: a `null` session renders `SELECTING_CANDIDATE`; the
exact regression scenario the task specified — a fresh session renders `latestFrame=none yet`/
`cam2cResult=awaiting frame`, still does after only a binding resolves, and renders a concrete
`latestFrame=640x480`/`cam2cResult=DOMAIN_NOT_PROVEN` once a frame is folded in via the pure reducers,
never leaving a stale "awaiting frame" behind; a terminally failed session renders the failure and omits
binding/frame detail.

A new instrumented Compose test, `ExperimentCallbackFreshnessTest` (`androidTestInternalDebug`), is
written to prove the fix end-to-end without needing real camera hardware (none is attached in this
environment — see the Validation subsection below): a minimal `SimulatedCameraFrameSource` composable
mirrors both `CameraPreview`'s own `DisposableEffect(Unit)`-installs-once pattern and
`PhysicalCameraBindingSession`'s own fresh-lambda-every-recomposition pattern; the test installs a
callback while the session is still `Binding`, transitions it to `Bound` **without** recreating the
simulated analyzer (asserted via `assertSame` on the installed callback reference itself), invokes the
*original* callback reference, and asserts the report advances from `latestFrame=none yet`/
`cam2cResult=awaiting frame` to a concrete frame and `DOMAIN_NOT_PROVEN` — the stale `Binding`-era value is
never permanently captured, *as written*. A second case in the same file covers the reverse order (frame
before binding). **This test compiles against real Android/Compose-test APIs but has not been executed —
no device or emulator is available in this environment (see the Validation subsection below); it has not
yet demonstrated anything on real hardware.**

**Fix B — terminal cleanup on explicit zoom/bind failure.** Before this fix, once `CameraPreview` reported
an explicit zoom/bind failure, the experiment kept showing an `EXPLICIT_BIND_FAILED` banner while the
camera/analyzer it had already bound (Preview + ImageAnalysis, per §4.4's lifecycle contract) stayed live
indefinitely — no code path unbound it, and there was no in-app way back to candidate selection.

Fixed by conditional composition, not a new unbind call: `PhysicalCameraBindingSession` now calls
`CameraPreview` only while `!state.isTerminallyFailed`; once true, `CameraPreview` is removed from
composition entirely, which runs its own `DisposableEffect`/`CameraSessionLifecycle` disposal path
(§4.4's own exactly-once `cleanupAndShutdown`) and actually unbinds the camera — no second, ad-hoc unbind
call was added, so `CameraSessionLifecycle`'s single-lock, exactly-once guarantee is unchanged. New
"Retry"/"Back to candidates" actions (`testTag`s `physical_camera_experiment_retry`/
`physical_camera_experiment_back_to_candidates`) give the user a way out of the terminal state.

Attempt isolation — "callbacks from candidate A cannot mutate a later attempt for candidate B" — is now
owned by a new pure `ExperimentUiModel` (`mobile/src/internalDebug/.../camera/ExperimentUiModel.kt`):
`nextAttemptId` increases monotonically and is never reused; `startAttempt`/`retry`/`backToCandidates`
are pure transitions over it; `updateSession(attemptId, reducer)` applies a reducer only if `attemptId`
still matches the live session — the same guard `ExperimentSessionState`'s own reducers apply a second
time, defense-in-depth against a callback already in flight when the UI moved on. At the Compose level,
`key(session.attemptId) { PhysicalCameraBindingSession(...) }` forces a full subtree teardown/recreate on
every new attempt (retry or a different candidate), so candidate A's CameraX session is fully torn down —
never merely recomposed-over — before candidate B's session exists; this is the primary isolation
mechanism, with the `attemptId` checks as a second, independent layer.

New tests: `ExperimentUiModelTest` (8 pure JVM tests, **executed and passing**) — starting an attempt
advances the counter; retry with no session is a no-op; retrying after a terminal failure starts a fresh,
non-terminally-failed session with a new `attemptId` for the same candidate; `backToCandidates` discards
the session but keeps the counter monotonic; `updateSession` is a no-op both with no live session and with
a mismatched `attemptId`; **a callback from a superseded `attemptId` never overwrites the retried session**
(the exact task-required scenario, reproduced through `ExperimentUiModel` itself, not just the underlying
reducer). `ExperimentSessionLifecycleUiTest` (`androidTestInternalDebug`, Compose) is written to render
`PhysicalCameraBindingSession` with an already-terminally-failed `ExperimentSessionState` and assert the
failure banner appears and — by construction, since `PhysicalCameraBindingSession` now early-returns a
dedicated terminal layout whenever `state.isTerminallyFailed` before it ever reaches the `CameraPreview`
call below — never attempts to reach real camera hardware; and that Retry/Back actions each invoke their
own callback exactly once. This test **compiles but has not been executed on a device or emulator** (see
Fix B.1 and the Validation subsection below); a retried `ExperimentUiModel` session starting clean (no
binding, no frame, not terminally failed, a fresh `attemptId`) is proven by `ExperimentUiModelTest` above,
which *has* executed and passed.

**Fix B.1 — terminal-UI reachability (a follow-up review found this).** The terminal layout Fix B
introduced rendered Retry/Back as the *first* child of a plain `Box`, then unconditionally rendered a
`fillMaxSize`, scrollable report layer as the *second* child. In a `Box`, later children draw on top of
earlier ones, so that fullscreen report layer visually covered Retry/Back and intercepted every pointer
event over the whole screen — the controls existed in the semantics tree (so a test that only asserted
`assertExists()` or called `performClick()` directly against the semantics tree, without checking
visibility or scrolling, would still have passed), but a real user could not see or tap them. Fixed:
`PhysicalCameraBindingSession` now renders exactly one scrollable `Column` while
`state.isTerminallyFailed` — failure banner, report text, Retry, then Back to candidates, as real
siblings in a single layout, never two independently-`fillMaxSize`d layers stacked in a `Box`. The report
text's own `testTag` moved from the scroll container onto the `Text` node itself (matching the pattern
`CamDiagnosticFullReportDialog.kt` already uses), so `hasText` assertions target a node whose own
semantics actually carry that text rather than relying on merged-descendant semantics the container never
opted into. `ExperimentSessionLifecycleUiTest` was rewritten to prove reachability with real Compose UI
test APIs, not just existence: a small, fixed-size host (`320.dp x 480.dp` — well under a typical device
screen) forces genuine scrolling regardless of the actual emulator/device screen size;
`performScrollToNode` + `assertIsDisplayed` reach and confirm visibility of the banner, the report, and
both controls in turn; each of Retry/Back is scrolled to, asserted displayed, then clicked via
`performClick()`, with the *other* callback made to `error()` if it fires, proving no cross-wiring and
exactly-once delivery. As with every other instrumented test in this pass, this is compiled against real
Android/Compose-test APIs, not yet executed on a device or emulator.

**Fix C — testable launch path.** §3.10 Fix 8's own launch-path test
(`PhysicalCameraBindingExperimentActivityLaunchTest`) compared a reflected class name against a second,
independently hand-written string literal — proving the two strings match, never that the in-app "Open
physical-camera experiment" action actually launches the registered `Activity`. The `Intent(context,
PhysicalCameraBindingExperimentActivity::class.java)` construction was also previously inlined directly
inside the button's `onClick`, so no test could observe what a click actually launches.

Fixed by extracting `buildPhysicalCameraBindingExperimentIntent(context: Context): Intent`
(`PhysicalCameraBindingExperimentScreen.kt`) as the one function that ever constructs that `Intent`. The
real button now routes through a new, injectable `onOpenPhysicalCameraExperiment: (() -> Unit)? = null`
parameter on `CamDiagnosticFullReportDialog` — defaulting to the real
`startActivity(buildPhysicalCameraBindingExperimentIntent(context))` call, the same optional-injection
pattern the existing `actions: CamDiagnosticActions?` parameter already uses for Copy/Share. Two new
`androidTestInternalDebug` tests, written against a real `Context`/`PackageManager`
(`InstrumentationRegistry`), not a plain-JVM stub:
- `ExperimentLaunchIntentTest` — asserts, *as written*, that the built `Intent`'s `component.className` is
  the experiment `Activity`'s real reflected class name; that `PackageManager.resolveActivity` (the same
  lookup a real `startActivity` performs) resolves that exact component in this `internalDebug` build; and
  that `PackageManager.getActivityInfo` reports `exported == false`, matching the manifest.
- `CamDiagnosticPhysicalCameraExperimentLaunchUiTest` — asserts, *as written*, that a Compose click on
  "Open physical-camera experiment" invokes an injected recording lambda exactly once.

Neither test has actually run and passed — no `connectedInternalDebugAndroidTest` execution has occurred
in this environment (see the Validation subsection). "`PackageManager` resolves it" and "the click invokes
the action" describe what these tests check when executed, not a result obtained so far. Neither test
opens the `Activity` itself either way, and neither claims to — only a real Pixel 9 run (still pending) can
claim that; these tests establish code/instrumentation-*compilation*-level confidence in the launch path
today, a distinct and lesser claim than a device run or even a device-executed test pass.

**Validation (this session) — real Gradle, same provisioned JDK 17 + Android SDK + Gradle 8.14.3 as
§3.10's own pass.**
- `:mobile:testInternalDebugUnitTest`: **PASS, 472/472** (451 baseline + 21 new: 10
  `ExperimentSessionStateTest` + 8 `ExperimentUiModelTest` + 3 new cases in the existing
  `PhysicalCameraExperimentReportFormatTest`).
- `:mobile:testPublicDebugUnitTest`: **PASS**, unchanged — confirms zero leakage of this pass's new code.
- `:mobile:compileInternalDebugKotlin`/`compilePublicDebugKotlin`: **PASS**, zero new warnings.
- `:mobile:compileInternalDebugAndroidTestKotlin`: **PASS** (4 new instrumented test files).
- Instrumented/connected tests (`ExperimentCallbackFreshnessTest`, `ExperimentSessionLifecycleUiTest`,
  `ExperimentLaunchIntentTest`, `CamDiagnosticPhysicalCameraExperimentLaunchUiTest`): compiled against a
  real `androidx.compose.ui.test`/`InstrumentationRegistry` API surface, **not executed** — this
  environment has no connected device/emulator to run `connectedInternalDebugAndroidTest` against. This is
  a real, distinct gate from a plain-JVM compile: it proves these tests type-check against real Android
  runtime APIs (`PackageManager`, `Context.startActivity`, Compose UI test node matchers), not merely that
  they parse — but it is not the same claim as "these tests pass on a device," which remains pending.
- Physical Pixel 9 validation: **still not executed** — no physical device or emulator is available in
  this environment. This fix pass corrects code-level defects a careful review found and adds tests; it
  does not, and cannot, newly establish device-level facts.

### 3.12 CAM-2c dual-basis diagnostic slice: basis identity, geometry classification, CameraX 1.4.2 model

Follow-up to `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md`, which changed this document's
epistemic baseline for `ImageInfo.getSensorToBufferTransformMatrix()`:

- **The CameraX transform contract is explained.** The matrix's declared coordinate domain is
  established (public `ImageInfo` Javadoc, confirmed at the 1.4.2 implementation level): the
  **CameraX-opened camera's** `SENSOR_INFO_ACTIVE_ARRAY_SIZE` rect → the full unrotated
  `ImageProxy` buffer, constructed as the inverse of `Matrix.setRectToRect(bufferRect,
  openedCameraActiveArrayRect, ScaleToFit.CENTER)` (`CameraUseCaseAdapter`, camera-core 1.4.2). The
  intended geometry is axis-aligned uniform scale with a symmetric center crop whenever aspect
  ratios differ; `rotationDegrees` and `cropRect` are separate; zoom/crop-region is **not**
  incorporated; entries are float32 widened to double. Earlier statements in §3.5–§3.11 that the
  source domain itself was "not source-traced" are historical — superseded by the recon.
- **The historical 1.3.4 identity matrix is resolved**: CameraX 1.3.4 only set the matrix when a
  ViewPort was bound; with none, `UseCase`'s default identity leaked through. The old
  "pre-normalized source domain" speculation is retired.
- **What remains device-unproven** (why nothing is unlocked): under
  `CameraSelector.Builder.setPhysicalCameraId(...)` CameraX still opens the logical camera and
  routes streams via a physical `OutputConfiguration` — the matrix is therefore expected to be
  constructed from the **opened logical** camera's active-array basis while the CAM-2c physical path
  reads intrinsics from the **selected physical** camera's characteristics. Logical basis ≠ physical
  basis until explicitly reconciled and device-validated (equal dimensions are supporting evidence
  only, never coordinate equivalence); and matrix metadata matching its own construction is not a
  measurement of frame content.

This slice adds the `internalDebug`-only machinery to gather exactly that evidence (see
`docs/SPRINT_STATUS.md`'s "CAM-2c dual-basis diagnostic slice" for the full inventory): an explicit
`CameraCoordinateBasis` identity model (camera ID + role + coordinate space + full native rect +
metadata source — no unqualified "active-array-local" labels), the `WholeActiveArrayGeometryClass`
classifier with four separated tolerances (float-storage noise vs. geometric magnitude vs. scale
isotropy vs. affine structure), the pure `CAMERAX_1_4_2_IMPLEMENTATION_MODEL`
(`predictCameraX142SensorToBufferMatrix` — a model of the pinned implementation, never a
cross-version guarantee), per-frame dual-basis assessment with labelled residuals and a typed
comparison verdict, the opened-logical-camera snapshot captured coherently from the same bound
`CameraInfo` (typed `Unavailable` when the logical parent cannot be identified — never guessed),
per-attempt matrix-stability counters, deliberate near-4:3/16:9 `ImageAnalysis` resolution selection
(each switch a fresh generation), and deterministic text/JSON export
(`PHYSICAL_CAMERA_EXPERIMENT_JSON_SCHEMA_VERSION=1`; main CAM JSON schema 3 → 4 for the additive
geometry-class fields) with full-precision (widened-float32) matrix values and a mandatory safety
block.

**Safety invariants, restated:** no `SensorToBufferDomainProof.Proven*` variant is constructed by
any path in this slice; `evidenceOnlySensorToBufferDomainProof` still yields only
`Unresolved`/`HypothesisMismatch` (tested even for a perfect `UNIFORM_SCALE_CENTER_CROP`
classification); the physical experiment's expected successful outcome remains
`PHYSICAL CAMERA BINDING VERIFIED` + `TRANSFORM GEOMETRY CLASSIFIED` +
`LOGICAL/PHYSICAL BASIS COMPARISON EXPORTED` + `CAM-2c DOMAIN NOT PROVEN`; calibrated Pixel 9
`AnalysisBuffer` intrinsics remain blocked.

### 3.13 Dual-basis diagnostic fix: model-match structural scope, explicit aspect family, verdict split, stability honesty

A review of §3.12's slice found and fixed four diagnostic-correctness/evidence-integrity defects
(see `docs/SPRINT_STATUS.md`'s "CAM-2c dual-basis diagnostic fix" for the full record). The
contract-level statements this pass makes precise:

- **Model matching is only valid inside the model's own structure.** The CameraX 1.4.2
  implementation model is axis-aligned affine; a projective or sheared observed matrix cannot match
  it merely because its top two rows resemble the prediction. The typed
  `CameraX142ModelComparison` gate (reusing the geometry classifier's structural decisions) makes
  out-of-scope structures a `COMPARISON_UNSUPPORTED_STRUCTURE` outcome that never emits the
  `CAMERAX_IMPLEMENTATION_MODEL_MATCH` evidence level; the mapped-point residual is Euclidean and
  is not computed for unsupported structures (applying only a projective matrix's upper rows is not
  its mapping, and general projective matching is deliberately unimplemented).
- **The requested aspect family is explicit and independent of exact `WxH` equality.** The family
  (`NEAR_4_3`/`NEAR_16_9`) is assigned once by the selection band, travels intact through session
  state and retry to the `AspectRatioStrategy` decision, and is exported separately from both the
  requested and the actually-bound dimensions. Nothing re-infers it from integer ratios.
- **A both-basis match with differing rects is ambiguous, not "numerically indistinguishable".**
  `MATCHES_BOTH_EQUAL_RECTS_NUMERICALLY_INDISTINGUISHABLE` requires equal candidate native rects;
  differing rects that both fall within tolerance are
  `MATCHES_BOTH_DIFFERING_RECTS_WITHIN_TOLERANCE`. `basesNumericallyIndistinguishable` is pure rect
  identity; the better-predicting ranking uses a documented tie tolerance.
- **Stability counters separate raw-bit changes from geometrically meaningful changes.** Bitwise
  change = exact inequality of widened float32 coefficients (no tolerance); geometric change = mapped
  Euclidean displacement over a fixed 4096×3072 reference rectangle beyond a documented 1e-3 px
  threshold; exports carry the threshold names/values. The retired single 1e-6 raw-coefficient bound
  sat below the float32 ULP of large translations and was never honest "float noise".
- Unchanged: no `SensorToBufferDomainProof.Proven*` is constructed, frame-content correspondence
  remains unmeasured, `DomainNotProven` remains the automatic experiment outcome.

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
- `onIntrinsicsResolved(resolution)` — from `CameraSessionIntrinsicsCoordinator` (§10.7), not
  directly from `CameraPreview.onCameraInfo`; only the *first* call per provider instance has any
  effect — intrinsics resolve at most once per bound camera session, then are reused for every
  subsequent frame.

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
latest status/quality/pair-delta; no historical list). (CAM-1g later adds a third, combined
`observation: StateFlow<CameraSessionGeometryObservation>` publishing `state` and `debugState()`
together from the same locked recompute, specifically so a live UI observes counter changes even when
`state` alone would not re-emit a structurally-equal result — see §11.3.) A published bundle always
combines the
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
resolver or Camera2 seam — with once-per-instance caching: the first `resolveOnce(cameraInfo,
imageWidthPx, imageHeightPx)` call performs the real Camera2 `CameraCharacteristics` lookup and
maps CAM-1b's `CameraIntrinsicsResolution(intrinsics, fallbackReason: String?)` into CAM-1f's
sealed `CameraIntrinsicsResolution` (§10.1); every later call on the same instance returns the
cached value without repeating the lookup. One instance belongs to one bound camera session
(`remember`ed alongside `CameraSessionGeometryProvider`); re-entering AR creates a new instance, so
a fresh resolution is always attempted — nothing is cached across sessions.

**`resolveOnce`'s dimensions are `Int`, not `Int?`, and `require`d strictly positive** — an earlier
revision accepted nullable, defaulted-to-`null` dimensions and dismissed calling it with `null` as
"a minor nicety, not a correctness requirement." That was wrong: the real per-device
`CAMERA_CHARACTERISTICS` path genuinely does not depend on the analyzed image's dimensions (focal
length and physical sensor size are static device properties), but the **legacy fallback path does**
— `legacyFallbackCameraIntrinsics` derives its horizontal FOV from the analyzed image's aspect
ratio (§3.4), defaulting to a square 1:1 assumption when the dimensions are unknown. Because
resolution only ever happens once per session and is cached for its entire lifetime, resolving with
`null` at the moment `CameraInfo` becomes available — which is usually *before* the first
`ImageAnalysis` frame has arrived — would permanently publish a wrong fallback horizontal FOV for
every device that ever takes the fallback path with a non-square analyzed buffer (16:9, 4:3, or any
other real aspect ratio). `CameraSessionGeometry.intrinsics` is part of the published bundle, so
this is a correctness bug in what CAM-1f actually reports, not a cosmetic detail.

**`CameraSessionIntrinsicsCoordinator`** (`CameraSessionIntrinsicsCoordinator.kt`, CAM-1f addition)
is what guarantees `resolveOnce` is only ever called once both real inputs are known:

```kotlin
class CameraSessionIntrinsicsCoordinator(
    private val resolver: SessionScopedCameraIntrinsicsResolver,
    private val onResolved: (CameraIntrinsicsResolution) -> Unit,
) {
    fun onCameraInfo(cameraInfo: CameraInfo)
    fun onFrameMetadata(frame: CameraFrameMetadata)
    fun dispose()
}
```

- **Order-independent**: `onCameraInfo`/`onFrameMetadata` may arrive in either order — CameraX gives
  no guarantee which fires first — and either ordering resolves correctly, exactly once, using the
  *first* accepted `CameraInfo` and the *first* accepted frame's real `bufferWidthPx`/`bufferHeightPx`.
  A repeated call to either method, once its own input has already been accepted, is a no-op; later
  `CameraInfo`/frame values never override the first.
- **Preview-only fallback**: if the combined `Preview` + `ImageAnalysis` bind is rejected and
  `CameraPreview` falls back to Preview-only (§4.4), `onCameraInfo` may still fire, but no
  `ImageAnalysis` frame is ever produced, so `onFrameMetadata` never fires. Resolution therefore
  never happens in that case — by construction, not a special case — and CAM-1f geometry correctly
  stays unavailable (no frame means no pairing means no bundle either; §10.6's `MissingFrame`/
  `IntrinsicsUnavailable` states cover this). No `PreviewView` dimension is ever substituted for the
  missing analyzed-frame dimensions, and Preview-only camera display itself is unaffected.
- **Thread safety**: one internal lock guards the atomic "both inputs present, not yet claimed"
  transition. `resolveOnce` (real Camera2 work) runs *outside* that lock — it must never block a
  concurrent `onCameraInfo`/`onFrameMetadata`/`dispose` caller. The final `onResolved` publish,
  however, is a deliberate, reviewed exception: it runs **while holding the lock**, serialized with
  `dispose()` (see below). This is safe only because the production callback
  (`geometryProvider::onIntrinsicsResolved`) takes its own separate lock and never calls back into
  the coordinator — no lock cycle, no reverse acquisition.
- **Disposal — exact linearization rule**: terminal and idempotent, same convention as every other
  CAM-1f/CAM-1d session owner, with one precise guarantee about the boundary between it and
  publication:
  ```text
  Final onResolved publication and dispose() are serialized on the same lock.
  A publication already holding the lock (already invoking onResolved) finishes
  before a concurrent dispose() can acquire the lock and return.
  No onResolved call can begin after dispose() has returned.
  ```
  An earlier revision instead re-checked `disposed` *before* calling `onResolved`, releasing the
  lock in between the check and the call — a check-then-act window in which `dispose()` could
  complete and then a late `onResolved` call could still begin. That is now impossible: `dispose()`
  and the disposed-check-and-publish step share the same lock for the whole step, so there is no
  window between "check" and "call" for `dispose()` to run in. A resolution already inside
  `resolveOnce` when `dispose()` runs is not cancelled (it completes the Camera2 read, outside the
  lock), but if it finishes after `dispose()` has already run, its result is discarded rather than
  published. Proven by two tests in `CameraSessionIntrinsicsCoordinatorTest`: one blocks
  `resolveOnce` itself and disposes before it returns (publication never begins); the other blocks
  `onResolved` itself and disposes while it is in progress (`dispose()` provably does not return
  until `onResolved` finishes — verified via a `Future.get` with a short timeout that must throw
  `TimeoutException` while blocked, then complete promptly once released).

`ArScreen` wires `CameraPreview`'s `onCameraInfo` directly to `intrinsicsCoordinator::onCameraInfo`,
and calls `intrinsicsCoordinator.onFrameMetadata(frame)` at the top of the `onFrameMetadata`
callback — before the existing `timestampSynchronizer.onCameraFrame(frame)` call — so exact
frame/pairing coherence (§10.2) is unaffected: the coordinator only reads `frame.bufferWidthPx`/
`bufferHeightPx`, never the frame object itself, and never influences pairing. Disposal order in the
session's `DisposableEffect.onDispose` is: `timestampSynchronizer.dispose()`, then
`intrinsicsCoordinator.dispose()`, then `geometryProvider.dispose()`. The guarantee this order gives
is **not** "the geometry provider is never briefly live after an upstream session ends" — each
component's own disposal is what actually enforces its part of the contract, not the ordering
between them. The guarantees are, per component: the timestamp synchronizer accepts no new frame
pairing once *its* `dispose()` returns; the intrinsics coordinator cannot *begin* a publish once
*its* `dispose()` returns (the linearization rule above); and the geometry provider, disposed last,
is then terminally disposed itself and clears all of its own state. Disposing the two upstream
inputs before the provider simply means that by the time the provider is disposed, neither upstream
source can still be mid-callback trying to feed it — a sequencing convenience, not what makes any
individual component's contract hold.

**No recalculation is ever triggered within a session**: sensor physical size and the bound
`CameraInfo` do not change while a session is bound, and the first analyzed frame's buffer
dimensions do not change either (`ImageAnalysis` is bound once, with no target-resolution change
mid-session), so there is no scenario in this PR that needs one.

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

`ArScreen` owns one `CameraSessionGeometryProvider`, one `SessionScopedCameraIntrinsicsResolver`,
and one `CameraSessionIntrinsicsCoordinator` (all `remember`ed), disposes them from the same
`DisposableEffect(Unit)` that disposes `timestampSynchronizer` — in the order
`timestampSynchronizer` → `intrinsicsCoordinator` → `geometryProvider` (§10.7) — feeds
`onViewportChanged` from a `LaunchedEffect(overlaySize)`, and wires `CameraPreview`'s
`onCameraInfo` directly to `intrinsicsCoordinator::onCameraInfo` and `onFrameMetadata` to both
`intrinsicsCoordinator.onFrameMetadata` and (via `timestampSynchronizer`) `onPairedFrame`, as
described in §10.6–§10.7. **Nothing reads `CameraSessionGeometryProvider.state`** in this PR —
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

## 11. CAM-1g debug-only diagnostic consumer and device-validation gate (this PR)

CAM-1f wired a full producer (frame → pairing → crop/scale → intrinsics → `CameraSessionGeometry`)
that nothing consumed (§10.9, §10.10: "Nothing reads `CameraSessionGeometryProvider.state` in this
PR"). CAM-1g adds the **first** consumer — a debug-only diagnostic overlay inside `ArScreen` — so the
CAM-1c–1f pipeline can finally be observed on a physical device. This slice is a **validation gate**:
it adds no matching, no detection, no projection correction, and no renderer handoff. It is also the
first slice for which a physical-device report is required, not optional.

### 11.1 Pure diagnostic model — `:mobile`, package `dev.pointtosky.mobile.ar.camera`

Deliberately **not** placed in `:core:astro-core` even though it touches only pure core types
(`CameraSessionGeometryResult`, `CropScaleTransform`, `PixelPoint`) — it exists to answer a `:mobile`
UI/validation question ("is this build's overlay showing a plausible bundle"), and its JVM tests are
exercised via `:mobile:testInternalDebugUnitTest`, not `:core:astro-core:test`.

- **`CameraGeometryDiagnosticCategory`** (`CameraGeometryDiagnostics.kt`) — a flat, stable, 13-value
  enum covering every distinct reason the pipeline can report:

  ```text
  MISSING_FRAME · INVALID_VIEWPORT · INTRINSICS_PENDING ·
  ROTATION_NO_SAMPLES · ROTATION_OUTSIDE_TOLERANCE · ROTATION_CLOCK_MISMATCH ·
  PAIRING_FRAME_MISMATCH · PAIRING_DELTA_MISMATCH · PAIRING_OUTSIDE_CONFIGURED_TOLERANCE ·
  CROP_SCALE_CONSTRUCTION_FAILED ·
  READY_CALIBRATED · READY_LEGACY_FALLBACK ·
  DISPOSED
  ```

  `CameraSessionGeometryResult.toDiagnosticCategory()` maps every sealed subtype/reason to exactly
  one of these — `Ready` splits on `CameraGeometryQuality` into the two `READY_*` values, so a
  fallback bundle is never displayed as indistinguishable from a calibrated one. Never derived from
  the sealed result's own `toString()`.

- **`CameraGeometryDiagnosticSnapshot`** (same file) — the bounded, immutable display model:

  ```kotlin
  data class CameraGeometryDiagnosticSnapshot(
      val category: CameraGeometryDiagnosticCategory,
      val quality: CameraGeometryQuality?,
      val frameTimestampNanos: Long?,
      val bufferWidthPx: Int?, val bufferHeightPx: Int?,
      val cropLeftPx: Int?, val cropTopPx: Int?, val cropRightPx: Int?, val cropBottomPx: Int?,
      val rotationDegrees: Int?,
      val viewportWidthPx: Int?, val viewportHeightPx: Int?,
      val pairDeltaNanos: Long?,
      val intrinsicsSource: CameraIntrinsicsSource?,
      val horizontalFovDeg: Double?, val verticalFovDeg: Double?,
      val uniformScale: Double?, val displayOffsetX: Double?, val displayOffsetY: Double?,
      val centerProbe: CameraGeometryCenterProbeSnapshot?,
  )
  ```

  `CameraSessionGeometryResult.toDiagnosticSnapshot()` is a **pure mapper** — no Android dependency,
  no additional state read, no pixels, no rotation matrix (`CameraSessionGeometry.pairedRotation` is
  never called from diagnostics code, since it is the one property that would otherwise force a
  defensive-copy of matrix data this slice must never expose). Geometry/FOV/scale/offset/center-probe
  fields are populated **only** for `Ready`, the only variant that actually carries a
  `CameraSessionGeometry`. Two variants are exceptions because they carry viewport data directly even
  though they are not `Ready`: `MissingFrame.viewportSize` (the last known *valid* viewport) and
  `InvalidViewport`'s raw width/height. Every other non-`Ready` variant — `RotationUnavailable`,
  `IntrinsicsUnavailable`, `GeometryRejected`, `Disposed` — reports only its `category`; the sealed
  result does not carry frame/buffer data for those variants (`FrameRotationPairingResult`'s
  non-`Paired` cases carry only bare timestamps, never a `CameraFrameMetadata`), so the snapshot
  reports "unavailable" there rather than reconstructing a value from a different source and implying
  it was captured atomically with the reported status.

- **`computeCameraGeometryCenterProbe(transform: CropScaleTransform)`** (§11.4 below) and
  **`nextDebugSessionId()`** (§11.6 below) live in the same file.

### 11.2 Debug-only visibility gate

**`CameraGeometryDiagnosticsGate`** (`CameraGeometryDiagnosticsGate.kt`) gates the overlay on the
project's actual build-variant matrix (`mobile/build.gradle.kts`: `flavorDimensions += "distribution"`
with `internal`/`public` flavors, plus AGP's implicit `debug`/`release` build types) rather than on a
new flag invented for this PR:

```kotlin
internal fun isDiagnosticsEnabled(debug: Boolean, flavor: String): Boolean =
    debug && flavor == "internal"

object CameraGeometryDiagnosticsGate {
    val isEnabled: Boolean = isDiagnosticsEnabled(debug = BuildConfig.DEBUG, flavor = BuildConfig.FLAVOR)
}
```

`isDiagnosticsEnabled` is a plain pure function of `(debug, flavor)` — `CameraGeometryDiagnosticsGateTest`
exercises all four `(debug, flavor)` combinations against it directly, plus one assertion that the gate
*object* (`CameraGeometryDiagnosticsGate.isEnabled`) agrees with `isDiagnosticsEnabled(BuildConfig.DEBUG,
BuildConfig.FLAVOR)` for whichever variant is currently running.

**Flavor-aware, not `internalDebug`-only.** `CameraGeometryDiagnosticsGateTest` lives in the shared
`src/test` source set, so it is compiled and run once per test variant —
`testInternalDebugUnitTest` *and* `testPublicDebugUnitTest` both execute the same class, and
`BuildConfig.FLAVOR` differs between the two (`"internal"` vs `"public"`). An earlier revision of this
test hardcoded `assertTrue(CameraGeometryDiagnosticsGate.isEnabled)`, which only holds for
`internalDebug` and fails outright under `testPublicDebugUnitTest` (`BuildConfig.FLAVOR == "public"` ⇒
the correct, expected gate value is `false`). The fix computes the *expected* value from the same
`BuildConfig` fields the gate itself reads, then asserts the gate agrees — so the same assertion is
correct under both variants without weakening what the gate itself does: the overlay is still compiled
into every variant but only *shown* in `internalDebug` — it never appears in `internalRelease`,
`publicDebug`, or `publicRelease`. No new user-facing setting, developer-options screen, or persistent
preference was added; no existing debug-overlay toggle mechanism exists elsewhere in `:mobile` to reuse
(checked — no `BuildConfig.DEBUG`-gated UI or developer-options screen predates this PR).

### 11.3 Collection — one observable `CameraSessionGeometryObservation`, lifecycle-aware and composition-scoped

**Why not `state` + `debugState()`.** The first revision of this section had `ArScreen` separately
collect `geometryProvider.state` (a `StateFlow<CameraSessionGeometryResult>`) and sample
`geometryProvider.debugState()` (a plain synchronized getter, not a `Flow`) once per recomposition.
That under-notifies a live UI: `StateFlow` never re-emits a *structurally-equal* value, so two
consecutive frames that both produce `IntrinsicsUnavailable(PENDING)` — or two consecutive
`RotationUnavailable` results with the same reason — publish only one `state` emission between them,
even though `observedFrameCount` advanced on the second frame. Compose never recomposes for that
second frame (nothing it collects changed), so the diagnostic overlay's frame/ready counters could sit
stale for the entire time a session stayed in one non-ready category — exactly the situation the
physical-device checklist's stationary/slow-pan/fast-pan tests need live counters for.

**Fix: `CameraSessionGeometryProvider.observation`.** `CameraSessionGeometryProvider.kt` adds:

```kotlin
data class CameraSessionGeometryObservation(
    val result: CameraSessionGeometryResult,
    val debugState: CameraSessionGeometryDebugState,
)

val observation: StateFlow<CameraSessionGeometryObservation>
```

`recomputeLocked()` (the one place that ever changes `result`/counters/latest-status) publishes both
`_state.value` and `_observation.value` from the *same* just-computed `result` and the *same*
post-update counters, in the same locked section, via a shared `debugStateLocked()` helper (also used
by the still-available `debugState()` getter, so the field list is never duplicated). Because
`CameraSessionGeometryObservation` bundles the counters into the value being compared for
`StateFlow`'s equality-based no-op-on-equal-emit behavior, **a counter-only change (same `result`,
advanced `debugState`) still produces a new, distinct `CameraSessionGeometryObservation` and therefore
still emits** — this is what closes the staleness gap above. `dispose()` publishes
`CameraSessionGeometryObservation(result = Disposed, debugState = debugStateLocked())` after counters
are already cleared, under the same lock. `observation` remains bounded, immutable, latest-value-only
— no queue, no history — exactly like `state`/`debugState()` before it. The pre-existing `state`
`StateFlow` is unchanged and still available for any simpler consumer that only needs the result.

**Collection in `ArScreen`.** `ArScreen` collects `geometryProvider.observation` (not `state`, and not
a direct `debugState()` sample) with `collectAsStateWithLifecycle()` — the same pattern `ArRoute`
already uses for `viewModel.state` (`ArScreen.kt`, both call sites). The collection lives entirely
inside `if (CameraGeometryDiagnosticsGate.isEnabled) { ... }` inside the `ArScreen` composable body:
conditional composable calls are fully supported by Compose (unlike hook-order rules in other UI
frameworks), and because the gate's value is fixed for the process lifetime, the branch taken never
changes across recompositions of a given build. No manually launched permanent coroutine, no timer, no
polling loop is used — the underlying collection is scoped to the composition via
`collectAsStateWithLifecycle`'s own `LaunchedEffect`, so it is cancelled the moment `ArScreen` leaves
composition, matching §12's lifecycle requirements. The diagnostic snapshot is built from
`geometryObservation.result.toDiagnosticSnapshot()`; the displayed frame/ready counters are read
directly from `geometryObservation.debugState` — `ArScreen` no longer calls
`geometryProvider.debugState()` for live display. A local `SideEffect` compares the snapshot's
`category` (not the raw observation) across recompositions to maintain a bounded
`statusTransitionCount`: a counter-only observation emission (same category, advanced counters) does
not bump it, since the comparison is on `category`, not on emission identity or count. `ArScreen`
starts no new camera, sensor, resolver, or provider — this is one lifecycle-aware collection of one
existing provider's `observation`, nothing more.

### 11.4 Center-probe (§7)

```kotlin
fun computeCameraGeometryCenterProbe(transform: CropScaleTransform): CameraGeometryCenterProbeSnapshot {
    val viewportCenter = PixelPoint(transform.viewportSize.width / 2.0, transform.viewportSize.height / 2.0)
    val imagePoint = transform.displayToImage(viewportCenter)
    val displayRoundTrip = transform.imageToDisplay(imagePoint)
    val roundTripErrorPx = hypot(displayRoundTrip.x - viewportCenter.x, displayRoundTrip.y - viewportCenter.y)
    ...
}
```

Computed once per `Ready` snapshot (`toDiagnosticSnapshot()` calls it directly on
`geometry.cropScaleTransform`), never per-frame-logged (§10 of the task description; the overlay
*displays* it every recomposition, but nothing logs it). Values are never clamped, and the error is
computed at full `Double` precision before any display rounding — only `buildCameraGeometryDiagnosticText`
rounds, for display, to 1 decimal (image point) / 3 decimals (round-trip error).
`CameraGeometryDiagnosticCenterProbeTest` covers: unrotated, 90°/180°/270° rotation, non-zero crop
origin, and both horizontal- and vertical-center-crop aspect regimes — every case round-trips to
within `1e-6` px. This checks only that the *published* transform is internally self-consistent; per
§9.10/§10.10, it is **not** proof of Preview/ImageAnalysis pixel alignment.

### 11.5 Status/reason mapping and formatting

`CameraGeometryDiagnosticFormat.kt` formats every snapshot field deterministically, always with
`Locale.ROOT` (never the device locale, so a comma-decimal locale cannot change the displayed digits):
pair delta as signed milliseconds with 1 decimal (`"+6.4 ms"` / `"-6.4 ms"`), FOV with 1 decimal per
axis, scale with 3 decimals, offsets with 2 decimals per axis, pixel sizes as `"W×H"`, rotation as
`"D°"`, crop as `"[left,top — right,bottom]"`, and a fixed `"unavailable"` placeholder for every
missing field — never an empty string, never `"null"`, never the sealed result's own `toString()`,
never a rotation matrix or any other pixel-plane value.
`buildCameraGeometryDiagnosticText(snapshot, sessionId, statusTransitionCount, observedFrameCount,
readyBundleCount)` assembles the full multi-line overlay text from these; `CameraGeometryDiagnosticFormatTest`
covers signed/zero/missing pair delta, FOV/scale/offset/pixel-size/rotation/crop formatting and their
missing-field placeholders, the no-scientific-notation guarantee for both ordinary and very large
values, and that the assembled text never contains `"CameraSessionGeometryResult"`,
`"CameraSessionGeometry("`, `"rotationMatrix"`, or `"FloatArray"`.

### 11.6 Overlay UI and session identity

A single non-interactive `Text` composable (`CameraGeometryDiagnosticOverlay`, private, in
`ArScreen.kt`) renders the built text — translucent background, monospace, width-bounded
(`widthIn(max = 280.dp)`), placed at `Alignment.TopStart` with enough top padding to clear the
existing back button. It carries no `clickable`/`pointerInput` modifier, so it never intercepts AR
gestures or camera interaction, and it is rendered as the last element in the top-level `Box` — purely
an overlay layer, never replacing or resizing any existing element. It is shown for every `ArUiState`
(`Loading` and `Ready` alike), since the camera-geometry pipeline itself runs independently of AR
catalog/location loading.

`nextDebugSessionId()` is a private, in-process `AtomicLong` counter (`CameraGeometryDiagnostics.kt`)
incremented via `remember { nextDebugSessionId() }` in `ArScreen` — a fresh value every time a new
`ArScreen` composition is created (i.e. every time AR is re-entered), never persisted, never a device
identifier, never uploaded. Displaying `"session: N"` alongside a bounded `"transitions: N"` counter is
sufficient to prove, from the overlay alone, that leaving and re-entering AR produced a new owner
rather than reusing a disposed one (§12, Test G).

### 11.7 Lifecycle

The diagnostic consumer adds no new lifecycle surface of its own: it reads
`geometryProvider.observation`, owned and disposed exactly as CAM-1f already specifies for `state`
(§10.6) — `dispose()` publishes both from the same locked transition. Because
`collectAsStateWithLifecycle`'s collection is scoped to the `ArScreen` composition (§11.3), it stops
the moment `ArScreen` leaves composition — before, not after, `geometryProvider.dispose()` runs in the
same `DisposableEffect.onDispose` — so no diagnostic recomposition can read from a disposed provider
except to display the terminal `Disposed`/`DISPOSED` category itself (with its counters already
cleared), which is expected and transient. A freshly entered `ArScreen` composition gets a fresh
`remember`ed `geometryProvider` (§10.6, unchanged by this PR) and a fresh `nextDebugSessionId()` value,
so no prior session's frame timestamp, counters, or `Ready` bundle can leak into a new session's first
recomposition.

### 11.8 Confirmation (scope boundaries)

CAM-1g reads **no** pixels, adds **no** YUV/RGB conversion, implements **no** star detection, **no**
matching, **no** pose correction, **no** rotation interpolation, **no** SLERP, and moves **no**
existing star-overlay point. `ArScreen.calculateOverlay()` and `projectionParams(viewport)` are
byte-for-byte unchanged by this PR — the renderer still uses exclusively the legacy fixed
`VERTICAL_FOV_DEG = 56.0` projection (§3, §10.9). `CameraSessionGeometry.intrinsics`,
`CameraSessionGeometry.pairedRotation`, `CropScaleTransform`, and the center-probe output are read
**only** for display in the diagnostic overlay — none of them feeds `calculateOverlay`,
`projectionParams`, catalog selection (PTSKCAT0/PTSKCAT4 unchanged), or any orientation state. No
`CameraX TransformationInfo` was added. The overlay adds no persistent analytics history and logs no
matrix or raw timestamp stream; it is diagnostic-only and, per §11.2, debug-build-only.

### 11.9 Physical-device validation status

**`CAM-1g BLOCKED ON PHYSICAL DEVICE VALIDATION`** — this PR was authored and tested by a coding agent
with no access to a physical Android device, camera hardware, or rotation sensor; only JVM unit tests
and static review were performed (see the exact command/result log in this PR's description and
`docs/validation/cam_1g_device_validation.md`). The diagnostic overlay, pure mapper, formatting, and
center-probe are implemented and unit-tested exactly as specified, and the full Test A–I checklist
from the task description is transcribed, unexecuted, into
`docs/validation/cam_1g_device_validation.md` for whoever next has a physical device available. Per
§10.10/§9.10, readiness alone (even if it could be observed) would not itself prove
Preview/ImageAnalysis pixel alignment — that still requires a visible marker/grid or a future
detector, out of scope here. This section originally read "CAM-2a (predict-only projection) must not
begin until this device gate has actually been run and reviewed." **Update (CAM-2a):** the task
authorizing CAM-2a explicitly permits it to be implemented as a pure, isolated math slice
notwithstanding this outstanding gate, on the condition that it is not wired into production
rendering and not represented as device-validated — see §12 below. This status line
(`CAM-1g BLOCKED ON PHYSICAL DEVICE VALIDATION`) is otherwise **unchanged**: no physical-device pass
has been run for CAM-1g or CAM-2a.

**Update (CAM-2b validation-closure session):** a later session ran the real Gradle build/lint/
assemble/unit-test gates for the whole `:mobile` module (see `docs/SPRINT_STATUS.md` and
`docs/camera_star_prediction_contract.md` §14.10 for the exact commands/results), which compiles and
lints CAM-1g's own diagnostic-overlay code (`CameraGeometryDiagnosticsGate`,
`CameraGeometryDiagnostics`, the CAM-1g panel in `ArScreen.kt`) as part of `:mobile:compileInternalDebugKotlin`,
`lintInternalDebug`, and `assembleInternalDebug`, all of which passed. That is real evidence CAM-1g's
code still compiles, lints clean, and packages — it is **not** a physical-device pass: no phone or
emulator was available in that session either (no `adb`-visible device, no `/dev/kvm`). The Test A–I
checklist in `docs/validation/cam_1g_device_validation.md` remains entirely unexecuted, and this
status line is otherwise unchanged.

---

## 12. CAM-2a predict-only star projection pipeline (this PR)

CAM-2a adds a pure, isolated math slice — package `dev.pointtosky.core.astro.projection.camera.prediction`
in `:core:astro-core` — that computes deterministic predicted star positions in camera-normalized,
analyzed-image, and display coordinates from a catalog star's RA/Dec, an observer location/time, and
an existing `CameraSessionGeometry` bundle (CAM-1f), plus a bounded visibility classification. **Full
derivation, conventions, and worked examples live in the focused companion doc,
[`docs/camera_star_prediction_contract.md`](camera_star_prediction_contract.md)** — this section is
a short pointer plus the essential facts needed to keep this file's own inventory/risk-register
accurate.

**Status:** pure math, unit-tested (388 JVM tests), **not** wired into any renderer or `:mobile` code
path, **not** device-validated. §11.9 above records CAM-1g's own device-gate status as unchanged; the
task authorizing this PR explicitly permits CAM-2a to proceed as an isolated math slice despite that
outstanding gate, provided it stays unwired and unclaimed as device-validated — both of which hold
here.

**What it adds** (all new files, package `.../camera/prediction`):

- `EquatorialStarDirection` / `StarProjectionContext` — pure input types (RA/Dec radians, east-positive
  longitude, absolute UTC instant). Storage is canonical-only: the primary constructor is `private`
  (`@ConsistentCopyVisibility`), so the generated `copy()` cannot bypass normalization either, and the
  sole public entry point is the `of(...)` factory, which wraps RA into `[0, 2π)` and longitude into
  `[-π, π)` before the (also re-validated) `init` block runs.
- `LocalSkyDirection` / `equatorialToLocalSky` — reuses the existing ENU basis
  (`horizontalToVector`'s convention, §1.3 above) and reuses `lstAt`/`raDecToAltAz` verbatim
  (refraction off, per §2 Step A's own recommendation); only the small ENU-embedding arithmetic is
  freshly written, in `Double` for precision.
- `worldToDeviceVector` — applies `pairedRotation`'s **transpose** to a world vector. This traces
  and reconfirms §1.3/§2 Step C's existing "`rotationMatrix` is device→world; `transpose` is
  world→device" fact against the production `RotationFrame`/`ArScreen` code, rather than assuming it.
- `DeviceToOpticalCameraTransform` — the one fixed device→optical sign flip (`+y`/`+z` negated),
  derived from and consistent with the existing (legacy) `projectDeviceVector`'s own sign behavior.
  Its output (`OpticalCameraVector`) is still expressed in the **display-aligned** basis inherited from
  `pairedRotation` (post `remapForDisplay`) — see the next bullet for why that is *not* yet safe to feed
  into the buffer-space pinhole model.
- `DisplayAlignedOpticalToBufferOpticalTransform` — closes a correctness gap found in review of the
  first CAM-2a draft: `pairedRotation` is display-aligned (`+x`/`+y` = display-right/display-up for
  whatever the display's current rotation happens to be), but the pinhole model projects into the
  **native, unrotated analysis buffer**, and `CropScaleTransform.imageToDisplay` then applies its own
  forward rotation by `frame.rotationDegrees`. Feeding a display-aligned ray straight into the pinhole
  model — the first draft's bug — silently double-applies rotation for `rotationDegrees ∈ {90, 270}`
  (once implicitly via the attitude, once explicitly via `CropScaleTransform`). This transform is the
  **one** explicit, pure correction for that mismatch — a 2D change of basis around the optical Z axis
  only, derived as the exact inverse of `CropScaleTransform`'s own private `rotateClockwise` Jacobian
  (not guessed) — producing a distinct `BufferOpticalCameraVector` type so the two bases can never be
  silently mixed again. Depth (`z`) is untouched. See
  `docs/camera_star_prediction_contract.md` §4.5 for the full derivation, mapping table, and coupled
  attitude/frame-rotation test design.
- `CameraDirectionProjection` / `PinholeProjectionModel` — pinhole projection using **real**
  intrinsics-derived focal length (`fx = width / (2·tan(hFov/2))`, likewise `fy`) into the **unrotated
  full analyzed-buffer** coordinate space, with `CropScaleTransform.imageToDisplay` (already existing,
  CAM-1e) doing the one-and-only rotation. `forGeometry` now requires the resolved intrinsics'
  `reference` to be a `CameraIntrinsicsReference.AnalysisBuffer` (see the next bullet) whose
  `widthPx`/`heightPx` **exactly** equal `geometry.frame`'s buffer dimensions, and throws otherwise —
  it is no longer possible for a physical-sensor-referenced calibrated FOV, a dimensionless fallback,
  or an analysis-buffer-referenced value resolved for a *different* buffer/session to be silently
  applied as if it matched this one.
- `CameraIntrinsicsReference` (`AnalysisBuffer(widthPx, heightPx)` / `PhysicalSensor` / `Unspecified`)
  — a second correctness gap, hardened twice: `CameraIntrinsics` (CAM-1b) derives FOV from
  `SENSOR_INFO_PHYSICAL_SIZE` + focal length with no active-array/crop-region reasoning at all
  (confirmed by inspecting `CameraIntrinsicsResolver.kt`), so a `CAMERA_CHARACTERISTICS`-sourced
  (calibrated) intrinsics value describes the **physical sensor**, not necessarily the `ImageAnalysis`
  buffer CameraX may have cropped/scaled it into — a mapping this codebase captures no metadata for.
  The first fix derived an `ANALYSIS_BUFFER`/`PHYSICAL_SENSOR` label purely from `source`
  (`LEGACY_FALLBACK` → always "safe"), which review found insufficient: `legacyFallbackCameraIntrinsics`
  can be constructed with no dimensions, with dimensions from a different buffer/session, or with a
  different aspect ratio, then reused against an arbitrary `CameraSessionGeometry` — deriving "safe"
  from `source` alone cannot detect any of that, recreating the exact dimensionless/stale-aspect
  fallback bug CAM-1f's own coordinator prevents, just one layer higher. `CameraIntrinsicsReference` is
  now **caller-supplied data**, not derived from `source`: `AnalysisBuffer` carries its own
  `widthPx`/`heightPx` so a consumer can check them against the real buffer, `PhysicalSensor` means no
  analysis-buffer mapping is known at all, and `Unspecified` means no reference dimensions were ever
  available (e.g. a fallback resolved before the first analyzed frame). `CameraIntrinsics.init` still
  enforces the one cross-field rule production must uphold (`CAMERA_CHARACTERISTICS`/
  `CAMERA_INTRINSIC_CALIBRATION` ⇒ `PhysicalSensor`; `LEGACY_FALLBACK` ⇒ `AnalysisBuffer` or
  `Unspecified`) as defense in depth, without pretending `AnalysisBuffer`'s actual dimensions are
  implied by `source`. `projectStars` checks `reference` **before** ever constructing a
  `PinholeProjectionModel` and returns a categorized
  `StarPredictionBatchResult.IntrinsicsMappingUnavailable(reason)` — `PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED`,
  `ANALYSIS_BUFFER_REFERENCE_MISSING`, or `ANALYSIS_BUFFER_DIMENSIONS_MISMATCH` — instead of fabricating
  a buffer-space FOV or silently reusing a mismatched one.
- `StarPredictionBatchResult` — sealed result (`Ready(projections)` /
  `IntrinsicsMappingUnavailable(reason)`) so "the calibrated intrinsics can't be mapped to this buffer"
  is a distinct, explicit, typed outcome rather than an exception or a silently-degraded projection.
  `Ready`'s primary constructor is `private`; the sole public path, `Ready.of(...)`, stores a defensive
  copy of its input list, so a caller cannot mutate an already-returned `Ready` result through a
  reference to the (possibly mutable) list it originally passed in.
- `PredictedStarProjection` / `PredictedStarClassification` — a bounded result distinguishing
  behind-camera, outside-crop, inside-crop-but-outside-viewport, and visible, carrying no rotation
  matrix, catalog object, or Android type. `init` enforces there is no public "impossible" state:
  `cameraDirection`/`imagePoint`/`displayPoint` are all `null` for `BEHIND_CAMERA` and all non-null for
  every other classification — enforced, not just documented, so no construction or `copy()` path can
  produce a mismatched combination. `CameraDirectionSnapshot`'s five scalars are each validated finite.
- `StarPredictionSummary` — every count is validated non-negative, and the four classification counts'
  sum (computed in `Long`, so four `Int`-range counts near `Int.MAX_VALUE` cannot wrap around into a
  value that coincidentally matches `inputCount`) must equal it exactly.
- `StarProjectionContext.magneticDeclinationRad` / `trueEnuToMagneticEnu` — a third correctness fix,
  independent of the two above: `equatorialToLocalSky` produces a **true**-north ENU vector (pure
  astronomy), but `geometry.pairedRotation.rotationMatrix` is the *raw* device→world matrix from
  `SensorManager.getRotationMatrixFromVector` — **magnetic**-north-referenced (§1.3's own "North
  caveat"; R3 below) — captured *before* the legacy renderer's separate, later
  `RotationFrame.correctedForTrueNorth(declinationDeg)` call, which only ever corrects the matrix used
  to build its own `trueNorthFrame` for pixel math, never the matrix fed into CAM-1d's rotation-sample
  history. Feeding a true-north sky vector straight into that raw matrix silently rotated every
  predicted star's azimuth by the local magnetic declination (which can exceed 10°) — this is R3,
  previously tracked only for the legacy renderer, recreated in CAM-2a's own pipeline.
  `magneticDeclinationRad` (east-positive, exactly `android.hardware.GeomagneticField.getDeclination()`'s
  and `correctedForTrueNorth`'s own sign convention) is now an explicit pure input on
  `StarProjectionContext`, defaulting to `0.0` — an explicit "treat magnetic north as true north" mode,
  never a claim that the real declination is zero — and `trueEnuToMagneticEnu` is the one explicit
  correction, applied once, immediately before `worldToDeviceVector`, derived as the exact algebraic
  inverse of `correctedForTrueNorth`'s own matrix math (see
  `docs/camera_star_prediction_contract.md` §4.6 for the full derivation and equivalence proof, the
  latter checked against both a ported reimplementation of that algebra (`:core:astro-core`, which
  cannot depend on `:mobile` to call the real function) and the actual production function itself
  (`:mobile`'s `RotationFrameTrueNorthEquivalenceTest`). CAM-2a still cannot compute a real
  declination itself (no `GeomagneticField` call, no Android dependency) — that remains a future
  mobile-integration responsibility, unimplemented here.
- `projectStars(stars, context, geometry)` — the pure, stateless, order-preserving batch API; plus
  `summarizeStarPredictions` for a bounded count-only summary.

**What it explicitly does not add:** pixel reads, a detector/matcher, pose correction, rotation
interpolation/SLERP, any renderer change, or any new `CameraSessionGeometryProvider` producer/consumer
in `:mobile`. `ArScreen.calculateOverlay()`/`projectionParams(viewport)`/`VERTICAL_FOV_DEG = 56.0` are
byte-for-byte unchanged. `projectStars` is still not called from `ArScreen` or anywhere in `:mobile`
production code, and `:core:astro-core` still takes no Android/hardware dependency (no
`GeomagneticField` call, no sensor ownership) — the caller must always supply
`magneticDeclinationRad` itself.

**Risk register additions** — see `docs/camera_star_prediction_contract.md` §11 for detail: the
pinhole model's unrotated-buffer coordinate space and the IMU-derived device frame are not verified
co-registered on a real device (same unresolved class of risk as R8 below); camera-to-IMU extrinsic
offset and lens distortion remain unmodeled, matching CAM-1's own deferred scope (§3.3). The
display-aligned/buffer-optical basis mismatch (R12) and the physical-sensor/analysis-buffer intrinsics
mismatch (R13) are both now closed **at the pure-math level** by the transform and the
`CameraIntrinsicsReference` exact-dimension check above — see R12/R13 in Appendix A — but neither of
those fixes is itself device-validated; they are internally-consistent-by-construction, not confirmed
against real hardware. R12's own tests are explicitly **self-consistent basis-composition tests**, not
a model of CameraX's real sensor-orientation/rotation-reporting behavior (see
`docs/camera_star_prediction_contract.md` §11's first bullet) — they do not model
`CameraCharacteristics.SENSOR_ORIENTATION` or how CameraX derives `ImageProxy.imageInfo.rotationDegrees`
from it, so they cannot and do not claim to reproduce "the real production relationship." R3
("Magnetic vs true North") is likewise now closed for CAM-2a's own pipeline at the pure-math level by
`trueEnuToMagneticEnu` — this does not verify a *given* caller-supplied `magneticDeclinationRad` is the
actual correct value for a real place/time (CAM-2a has no way to check that; see
`docs/camera_star_prediction_contract.md` §11's dedicated bullet), only that CAM-2a's own algebra,
given a declination, treats it exactly as the legacy renderer's already-accepted `correctedForTrueNorth`
does.

---

## 13. CAM-2b debug-only predicted-star overlay consumer (this PR)

CAM-2a (§12) produced `PredictedStarProjection` values but nothing consumed them. CAM-2b adds the
**first** consumer — an `internalDebug`-only diagnostic overlay inside `ArScreen` — so the CAM-2a
pipeline can finally be visually cross-checked against the legacy renderer and, eventually, against a
physical device. This is the CAM-2a §12 "next slice" described there, now implemented. **Full
derivation, adapter/reducer design, and the physical-device checklist live in the focused companion
doc, [`docs/camera_star_prediction_contract.md`](camera_star_prediction_contract.md) §14** — this
section is a short pointer plus the essential facts needed to keep this file's own inventory/risk
register accurate.

**Status:** implemented. **Tests authored: Yes. Tests executed: Partially — see below. Build verified:
Partially (via a compiler workaround, not real Gradle) — see below.** Not device-validated. A coding
agent authored this, and the JVM unit tests below, with no physical Android device, camera hardware, or
rotation sensor available, and no Android SDK or working Gradle/JDK-17 toolchain in this sandbox. Real
Gradle could not run at all; however, `:core:astro-core`'s and `:mobile`'s CAM-2b Android-free
prediction-package tests were actually compiled and run via a direct `kotlinc`/JUnit-console invocation
(398/398 and 48/48 tests passing respectively, including the follow-up `CameraSessionGeometry.withIntrinsics`
preservation tests and the diagnostic-fallback sync-metadata reducer tests) — `:mobile`'s Compose/Android
compilation, `androidTest` compilation, lint, assemble, and connected instrumentation tests remain
genuinely unexecuted. See
`docs/camera_star_prediction_contract.md` §14.10 for the itemized (not collapsed) disclosure of exactly
which of the four build/test gates were, and were not, exercised, and by what method.

**What it adds** (`:mobile`, package `dev.pointtosky.mobile.ar.camera.prediction`, plus two small
Compose additions in `dev.pointtosky.mobile.ar`):

- A bounded catalog adapter (`selectPredictedStarDirections`) that converts the phone's own
  visibility-selected `StarRecord` prefix (the same filter `ArScreen.calculateOverlay` already applies,
  now shared via `visibilitySelectedStars`) into `EquatorialStarDirection`, bounded to
  `PREDICTED_STAR_OVERLAY_MAX_INPUT_STARS = 200` brightest-first entries, keyed by each star's stable
  catalog id — never the full ~42k-star catalog, never a second catalog parser, never sorting inside
  CAM-2a itself.
- A pure reducer (`reducePredictedStarOverlayState`) that gates on the same `internalDebug` flavor check
  CAM-1g already established (`CameraGeometryDiagnosticsGate`/`isDiagnosticsEnabled` — reused directly,
  not a second inconsistent flavor check), maps every non-`Ready` `CameraSessionGeometryResult` and
  every `IntrinsicsMappingUnavailableReason` to a specific typed `PredictedStarOverlayWaitingReason`/
  the reason itself, and otherwise calls `projectStars(...)` with the **raw**, still magnetic-north-
  referenced `CameraSessionGeometry` CAM-1f/1g already publish — never a matrix pre-corrected via
  `RotationFrame.correctedForTrueNorth` — plus the legacy renderer's own already-computed
  `GeomagneticField` declination as `StarProjectionContext.magneticDeclinationRad`. Passing both a
  corrected matrix and a non-zero context declination would double-correct true north (§12's own R3);
  the reducer's KDoc makes this ownership split explicit, and a dedicated unit test cross-checks the
  reducer's output against an independent `projectStars` call over the same raw geometry.
- A bounded, immutable UI state (`PredictedStarOverlayState`: `Disabled`/`Waiting`/`Ready`/
  `Unavailable`) built once per coherent recomputation via Compose `remember`, keyed on the geometry
  observation, observer location/time, declination, and the bounded star subset — never a polling loop,
  never a permanently launched coroutine, never an unbounded queue.
- Two small Compose additions (`PredictedStarMarkersCanvas`, `PredictedStarOverlayPanel`,
  `PredictedStarOverlayControls`) that draw only `VISIBLE_IN_VIEWPORT` predictions, anchored exactly at
  CAM-2a's own `displayPoint` with no further scale/rotation/offset, in a fixed cyan
  hollow-circle-plus-cross style deliberately distinct from the legacy `BvColor` star dots — drawn on
  top of the existing legacy overlay, never replacing it — plus a compact status panel and a
  session-local (non-persisted) pair of toggles.

**What it explicitly does not add:** pixel reads, a detector/matcher, pose correction, rotation
interpolation/SLERP, any change to `ArScreen.calculateOverlay()`/`projectionParams(viewport)`/
`VERTICAL_FOV_DEG = 56.0` (all byte-for-byte unchanged), any new `CameraSessionGeometryProvider`
instance (CAM-2b reuses the exact provider/observation `ArScreen` already collects for CAM-1g), any
catalog asset or parser change, or physical-sensor intrinsics support (CAM-2a's
`PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED` gate is unchanged and still surfaces as a categorized
`Unavailable` state, never a fabricated projection).

**Risk register addition** — see Appendix A, R14: the predicted-marker/legacy-overlay visual comparison
this slice enables is itself unverified on real hardware; every risk CAM-2a already carried (R3, R11,
R12, R13) is unchanged and still open.

---

## Appendix A — sign/handedness risk register (quick reference)

| # | Risk | Where | Guard |
|---|---|---|---|
| R1 | Azimuth clockwise-from-North vs math CCW | Steps A/B | cardinal-table test (§6.4) |
| R2 | Refraction on in one direction, off in the other | `raDecToAltAz` vs `altAzToRaDec` | fix one convention (§2 Step A) |
| R3 | Magnetic vs true North | `correctedForTrueNorth` | declination-offset test (§6.4); CAM-2a's own pipeline closes this at the pure-math level via `StarProjectionContext.magneticDeclinationRad` / `trueEnuToMagneticEnu`, proven algebraically equivalent to `correctedForTrueNorth` (`docs/camera_star_prediction_contract.md` §4.6) — not a claim that a caller-supplied declination is itself correct, and not device-validated |
| R4 | Camera axis is device `−Z` | `forwardWorld` negation | reticle round-trip test (§2 Step C) |
| R5 | Display remap vs image `rotationDegrees` double-count | `remapForDisplay` + analyzer | per-rotation test (§6.3) |
| R6 | Screen/image Y flip (`1 − ndcY`) | `projectDeviceVector` | centre + edge test (§6.2) |
| R7 | Wrong/hardcoded FOV → scale error | `projectionParams` | FOV-scaling test (§6.5) |
| R8 | `FILL_CENTER` crop ignored (image ≠ viewport aspect) | `CameraPreview` + Step E | crop/scale test (§6.5); pure `CropScaleTransform` forward/inverse + visibility tests (§9, CAM-1e) — not yet wired/device-validated |
| R9 | Front-camera mirroring | (back camera only today) | flip guard if front added |
| R10 | Sensor/camera clock base mismatch | §4 sync | age gate + near-still fallback (§4.3); measured nearest-sample pairing + tolerance/mismatch thresholds + diagnostic compatibility status, not yet device-validated (§4.5) |
| R11 | `pairedRotation`'s device frame vs. the pinhole model's unrotated-buffer pixel grid may not be co-registered on a real device | CAM-2a `PinholeProjectionModel`/`worldToDeviceVector` | traced (not guessed) transpose direction + literal-matrix tests (§12, `docs/camera_star_prediction_contract.md` §4/§11) — pure math only, not yet device-validated |
| R12 | Display-aligned optical ray fed directly into the buffer-space pinhole model double-applies rotation for `rotationDegrees ∈ {90, 270}` | CAM-2a `DisplayAlignedOpticalToBufferOpticalTransform` | one explicit inverse-basis transform, derived from `CropScaleTransform.rotateClockwise`'s own Jacobian; coupled attitude/`frame.rotationDegrees` tests for all four rotations (`docs/camera_star_prediction_contract.md` §4.5) — closed at the pure-math/internal-consistency level, not device-validated |
| R13 | A physical-sensor-referenced calibrated FOV (`CameraIntrinsics`) silently assumed to describe the `ImageAnalysis` buffer, when CameraX may have cropped/scaled the sensor into that buffer; and (hardened further) an analysis-buffer-referenced FOV resolved for one buffer/session silently reused against a different, dimensionless, or differently-sized buffer | CAM-2a `PinholeProjectionModel.forGeometry` / `CameraIntrinsicsReference` | `CameraIntrinsicsReference.AnalysisBuffer` carries its own `widthPx`/`heightPx` as data, not merely a source-derived label; `forGeometry` requires an exact width/height match against `geometry.frame`'s buffer (aspect-ratio match alone is not accepted) and throws otherwise; `projectStars` checks this first and returns a categorized `StarPredictionBatchResult.IntrinsicsMappingUnavailable` (`PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED` / `ANALYSIS_BUFFER_REFERENCE_MISSING` / `ANALYSIS_BUFFER_DIMENSIONS_MISMATCH`) instead of fabricating, silently downgrading, or reusing a stale/mismatched reference (`docs/camera_star_prediction_contract.md` §6.2) |
| R14 | CAM-2b's predicted-marker overlay visually implies alignment with the legacy renderer/real sky, but the comparison itself is not yet verified on real hardware — every upstream risk it depends on (R3, R11, R12, R13) is unchanged and still open | CAM-2b `ArScreen` predicted-star overlay | Reducer/adapter/format tests authored **and executed** (48/48 passing) via a `kotlinc`/JUnit-console workaround for the Android-free subset only — the Compose `ArScreen.kt` integration itself, `androidTest`, lint, and assemble remain unexecuted (no Android SDK/Gradle-JDK17 in this sandbox — see `docs/camera_star_prediction_contract.md` §14.10 for the itemized breakdown); physical-device checklist transcribed, unexecuted, into `docs/validation/cam_2b_device_validation.md` (§13, `docs/camera_star_prediction_contract.md` §14) |
| R15 | `PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK` substitutes a legacy fixed-FOV intrinsics for a real `PhysicalSensor`-referenced one, purely to let CAM-2b draw markers for diagnosis — this is not, and must never be read as, a real calibrated FOV for that device | CAM-2b `CameraSessionGeometry.withIntrinsics` / `PredictedStarOverlayMetadata` | Explicit, session-local, opt-in toggle defaulting to `SESSION_INTRINSICS`; the panel always labels which mode is active and, in fallback mode, shows both the real session intrinsics and the substituted projection intrinsics side by side, never calling the fallback "calibrated" or "resolved" (`docs/camera_star_prediction_contract.md` §14.11) |
