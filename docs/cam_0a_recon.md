# CAM-0a — Camera/AR/Projection/Sensor Pipeline Recon

Status: **recon only** — no production code, renderer behavior, or tests were
changed by this document. It inventories the existing pipeline ahead of
future camera-based star matching / calibration work (CAM-1..4), building on
CAT-1 (PTSKCAT0 real-star catalog + generated assets) and VF-1/VF-2a
(`RealStarVisibilityService`, `VisibleRealStarSnapshot`).

## 1. Camera / AR entry points

- **Permission.** `CAMERA` is declared in `mobile/src/main/AndroidManifest.xml`
  (plus `<uses-feature android:name="android.hardware.camera"
  android:required="false"/>`, so the app still installs on camera-less
  devices/emulators) and requested at runtime in
  `mobile/src/main/java/dev/pointtosky/mobile/ar/ArScreen.kt` (`ArScreen`)
  via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`,
  fired from a `LaunchedEffect(Unit)` the first time the composable enters
  composition without permission.
- **Preview ownership.** `mobile/src/main/java/dev/pointtosky/mobile/ar/CameraPreview.kt`
  owns the CameraX `PreviewView`/`Preview` use case. It builds a `Preview`,
  calls `cameraProvider.unbindAll()` then
  `bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)`
  inside a `DisposableEffect`-scoped coroutine. `lifecycleOwner` comes from
  `LocalLifecycleOwner.current`, so CameraX starts/stops the camera
  automatically with the hosting Activity's lifecycle.
- **Pipeline.** CameraX only (`androidx.camera:camera-core/camera2/lifecycle/view
  1.3.4` in `mobile/build.gradle.kts`). No ARCore, no direct Camera2/NDK
  usage, no custom `SurfaceTexture` handling. The lint baseline already flags
  1.3.4 as outdated (1.5.1 available) — worth bumping before adding new
  CameraX-dependent code.
- **Threading.** Camera bind/setup runs on `Dispatchers.Main` (a `Job`-scoped
  coroutine tied to the composable's `DisposableEffect`). Sensor listeners
  (`RotationFrame`, `PhoneCompassBridge`) receive callbacks on the default
  (main) looper thread; `PhoneCompassBridge` hands off outbound messages to a
  `CoroutineScope(SupervisorJob() + Dispatchers.Default)`.
- **Frame access availability — the key finding.** Only a `Preview` use case
  is bound today. **No `ImageAnalysis`, no `ImageCapture`, no raw frame
  access exists anywhere in the codebase.** Resolution/fps/pixel format are
  therefore unconfigured — CameraX auto-selects a preview surface size for
  the `PreviewView`. Any camera-based star matching needs a **new**
  `ImageAnalysis` (or `ImageCapture`) use case added alongside `Preview` in
  the same `bindToLifecycle` call. The lifecycle/permission/threading
  scaffolding already in place makes this additive, not a rework.

## 2. Sensor/orientation pipeline

- **`RotationFrame.kt`.** `rememberRotationFrame()` registers a
  `SensorEventListener` on `Sensor.TYPE_ROTATION_VECTOR` (platform-fused
  gyro+accel+mag) at `SENSOR_DELAY_GAME`. Produces
  `RotationFrame(rotationMatrix: FloatArray(9), forwardWorld: FloatArray(3),
  timestampNanos: Long)`. `timestampNanos` is the raw `event.timestamp`
  (hardware/boot-time clock, **not** wall-clock) — relevant for future
  frame/sensor sync (§7).
- **Display rotation.** `DisplayRemap.kt::remapForDisplay` wraps
  `SensorManager.remapCoordinateSystem` using `context.display.rotation`
  (API 30+) / deprecated `windowManager.defaultDisplay.rotation` fallback,
  called on every sensor event so rotation changes are picked up live.
  `MainActivity`/the AR route is locked to `android:screenOrientation="portrait"`
  in the manifest, so on typical portrait-native phones this will usually
  observe `ROTATION_0` at runtime. **This is not a contract across all
  Android form factors, though:** `Display.rotation` is measured relative to
  the device's *natural* orientation, not its current visual orientation, so
  natural-landscape devices — tablets, Chromebooks, and foldables among them
  — can still report `ROTATION_90`/`ROTATION_270` for the same
  portrait-locked Activity. `remapForDisplay` therefore remains
  production-relevant on those form factors and must be tested before CAM
  work builds on it — not dismissed as a portrait-only code path. The
  currently-broken unit test (see §3) is a gap in that coverage, not
  evidence that the path itself is unused.
- **Device attitude model.** `rotationMatrix` is Android's device→world
  matrix (E/N/Up world frame); `forwardWorld` is the negated 3rd column
  (the direction the phone's back/camera points, in world coordinates).
  `RotationFrame.correctedForTrueNorth(declinationDeg)` rotates this from
  magnetic-north- to true-north-relative using
  `android.hardware.GeomagneticField.declination`, computed once per
  resolved location in `ArScreen.kt`. `deviceRollDegrees` derives on-screen
  roll (for counter-rotating labels) from row 2 of the matrix, with a
  documented epsilon guard near zenith/nadir and a sign convention the code
  comment says was "verified on three poses" manually — **not** asserted by
  any automated test.
- **GPS/manual location → AR wiring.**
  `mobile/location/DeviceLocationRepository.kt` wraps Play Services
  `FusedLocationProviderClient` (`PRIORITY_BALANCED_POWER_ACCURACY`) behind a
  permission-gated `callbackFlow<GeoPoint?>`. `ArViewModel.locationSnapshot`
  combines this with `LocationPrefs.manualPointFlow` (DataStore-backed
  manual override): manual wins if set, else device, else a `(0,0)`
  placeholder with `resolved=false`. `SkyMapViewModel` duplicates the same
  combine pattern.
- **World-to-screen transform.** Yes — a working pinhole-camera-style
  transform already exists and is exercised in production:
  `calculateOverlay()` / `projectHorizontalsToScreen()` in `ArScreen.kt`.
  Pipeline: Alt/Az → unit vector in world frame (`horizontalToVector`) →
  rotate into device frame via `transpose(rotationMatrix)` (world→device) →
  perspective-divide by `-z` against a **hardcoded** vertical FOV constant
  (`VERTICAL_FOV_DEG = 56.0`, horizontal FOV derived from current aspect
  ratio) → normalized device coords → pixel coords via viewport
  half-width/half-height. Not read from actual camera/lens intrinsics (§6, §7).

## 3. Projection/rendering pipeline

- **Coordinate types present:** `Equatorial` (RA/Dec) and `Horizontal`
  (Alt/Az) in `core/astro-core/.../coord/Coordinates.kt`; Compose `Offset`
  for screen x/y; ad-hoc `FloatArray(3)` "world"/"device" unit vectors local
  to `ArScreen.kt`/`RotationFrame.kt`. There is **no shared/exported**
  "camera-normalized-coordinates" type — NDC (`ndcX`/`ndcY`) is a private
  local concept inside `projectDeviceVector`.
- **Projection code:**
  - `core/astro-core/transform/EquatorialHorizontalTransform.kt` —
    `raDecToAltAz`/`altAzToRaDec`, standard Meeus spherical astronomy with
    optional Saemundsson refraction. Pure, unit-tested, and — per code
    comments — deliberately shared identically by AR, sky map, and the
    object card. No duplication/drift risk here.
  - `mobile/ar/ArScreen.kt` (private, `@VisibleForTesting internal`) —
    `projectHorizontalsToScreen`/`calculateOverlay`/`projectDeviceVector`/
    `horizontalToVector`/`vectorToHorizontal`/`transpose`/`multiply`: the
    actual sky→screen pinhole projector. It is **not** a separate reusable
    module — it lives directly in the AR screen file. Any CAM feature
    needing the same projection (e.g. to draw a calibration overlay) would
    either duplicate this logic or require extracting it first.
  - `mobile/skymap/SkyMapViewModel.kt` runs an independent Alt/Az projection
    for the 2D sky map — it stops at `raDecToAltAz` and does not share the
    AR screen's perspective-projection code. Worth confirming there's no
    drift if CAM ever needs to reuse one path for the other.
- **How PTSKCAT4 is projected today:** `ArViewModel` loads
  `PtskCatalogLoader` → `AstroCatalog` (`star.bin`); `ArScreen.calculateOverlay()`
  projects every renderable, magnitude-filtered star through
  `projectEquatorial`, builds constellation-skeleton/asterism lines from the
  catalog's `LINE_NODE` flags and polylines, sorts brightest-first, and caps
  for Canvas performance (`MAX_STAR_POINTS=1500`, `MAX_LABELS=9`).
- **`ProjectionOrientationTest` — executed this recon, root cause traced.**
  See "Test run performed" below. Verdict: **not a projection-math bug.**
  It is a test-harness gap — `:mobile` has no Robolectric shadow for
  `SensorManager.remapCoordinateSystem`, so under AGP's
  `unitTests.isReturnDefaultValues = true` stub, the call silently no-ops in
  the JVM test, zeroing the rotation matrix and discarding every projected
  point (`z == 0` fails `projectDeviceVector`'s `z >= -0.01f` guard). The
  real device path is unaffected. The practical implication for CAM: **the
  AR projection pipeline's display-rotation handling has zero working
  automated regression coverage today** — worth fixing before camera work
  builds more logic on top of the same untested surface.

## 4. Existing catalog paths

- **PTSKCAT4** (curated, `mobile`/`wear` `assets/catalog/star.bin`) — loaded
  by `core/astro/.../catalog/PtskCatalogLoader.kt`; consumed by
  `CatalogRepository` (search/identify) and directly by
  `ArViewModel`/`SkyMapViewModel` for rendering. v4/v5 binary, hand-curated,
  includes constellation figures/asterisms/art overlays, no proper
  motion/spectral type. **This is the only catalog the renderer uses.**
- **PTSKCAT0** (real-star, CAT-1/VF-1) — `catalog/stars_real.bin`, HYG
  v4.2-derived, mag-sorted flat records (ra/dec/mag/bv/hip + sparse names),
  no constellation/proper-motion/spectral-type by design. Loaded via
  `AssetRealStarCatalogProvider` → `PtskCat0Catalog.parse`.
  `RealStarVisibilityService.select(SkyQualityInput)` (Bortle/SQM/direct
  limiting-magnitude) → `LimitingMagnitudeModel` →
  `RealStarVisibilityFilter.select` (binary-search prefix, since PTSKCAT0 is
  mag-sorted) → `RealStarVisibilityResult(catalog, limitingMagnitude,
  selection)`.
- **VF-2a renderer adapter (already merged — commit `342a54c`, included in
  this branch's base).** `core/catalog/.../visibility/render/VisibleRealStarSnapshot.kt`:
  `VisibleRealStar` (plain `raDeg/decDeg/mag/bv/hip/name`, no
  `PtskCat0Catalog` reference), `VisibleRealStarSnapshot` (lazy,
  non-copying view over a `RealStarVisibilityResult`),
  `VisibleRealStarProvider.snapshot(service, input)` (one-call
  composition). **This is the safe adapter point** the recon asked about —
  it already exists, is already documented
  (`docs/real_star_visibility_contract.md` §5), and is explicitly scoped for
  exactly this: "nothing in `:mobile`/`:wear` references this package — it
  exists so a future renderer/AR integration can consume VF-1's output."
- **Mixing check — confirmed clean.** Grepped all of `mobile/src/main` and
  `wear/src/main`: only `mobile/catalog/CatalogDebugScreen.kt`,
  `RealStarVisibilityDebugProvider.kt`, and `mobile/logging/MobileLog.kt`
  reference the PTSKCAT0/VF-1 packages, and all three are the diagnostic-only
  debug-screen path (a one-shot startup probe + a counts readout), explicitly
  documented as not feeding the renderer or PTSKCAT4. `ArViewModel`,
  `ArScreen`, and `SkyMapViewModel` import only `dev.pointtosky.core.astro.catalog`
  (PTSKCAT4) / `core.catalog.runtime.CatalogRepository` (which itself wraps
  `PtskCatalogLoader`, i.e. also PTSKCAT4). No mixing anywhere today.
- **Recommended adapter point for a future visible-real-star candidate
  list:** `VisibleRealStarProvider.snapshot(service, input)` →
  `VisibleRealStarSnapshot.stars: List<VisibleRealStar>`. A camera-matcher
  feature should depend on this package alone, never on
  `PtskCat0Catalog`/`VisibleStarSelection` directly. It needs a
  `RealStarVisibilityService` (built the same way
  `RealStarVisibilityDebugProvider` does —
  `AssetRealStarCatalogProvider(AndroidAssetProvider(context))`) and a
  `SkyQualityInput` (manual only today; no GPS/auto-grid/Moon/twilight wired
  into VF-1 itself, per the contract's §6).

## 5. Debug/UI paths

- **Catalog Debug screen** — `mobile/catalog/CatalogDebugScreen.kt` +
  `CatalogDebugRoute`: star/constellation/boundary load diagnostics
  (`CatalogDebugViewModel`), a manual RA/Dec/radius/magLimit probe form plus
  a self-test button (`CatalogRepository.probe`/`runSelfTest`), and a
  "Real-star visibility" card wired to `RealStarVisibilityDebugProvider.state`
  (catalog count/magLimit, fixed "Bortle 5" input, computed limiting
  magnitude, visible count).
- **AR overlays** — `ArScreen.kt` already ships a runtime "pro mode" panel
  (`ArControlsPanel`): magnitude-limit slider, visibility-filter toggle with
  auto/manual Bortle source, star-label/star-point/reticle-only toggles,
  constellation mode selector — all togglable without a separate build
  variant. There is **no dedicated AR debug overlay** today (no on-screen
  raw sensor readout, rotation matrix dump, or FOV/assumption display); the
  closest existing thing is `InfoPanel`, which shows reticle Alt/Az + RA/Dec
  + nearest-object separation.
- **Logging pattern** — centralized `mobile/logging/MobileLog.kt` wrapping
  `core/logging`'s `LogBus.event(name, payload: Map<String, Any?>)`:
  structured, named events (`real_star_visibility_debug`, `ar_open`,
  `card_open`, etc). Any new CAM instrumentation (frame-capture events,
  match attempts, calibration results) should follow this convention.
- **Good place for a future camera calibration debug panel:** two
  reasonable, low-friction options already exist: (a) add a new card to
  `CatalogDebugScreen.kt` mirroring the "Real-star visibility" card pattern
  — lowest risk, reuses the existing nav entry and MobileLog conventions;
  (b) extend `ArControlsPanel`'s pro-mode panel directly, since that's the
  live camera-preview screen where a "does the projected star land on the
  real star in frame" check would eventually need to render. Start with (a)
  for anything that doesn't need the live camera feed; move to (b) only once
  actual frame-vs-projection comparison is being built.

## 6. Algorithm feasibility

**Already available:**
- RA/Dec ↔ Alt/Az transforms (Meeus, refraction-aware), unit-tested.
- Device attitude → world-forward vector, display-rotation-aware remap.
- World→screen pinhole projection (adjustable FOV constant, currently
  hardcoded).
- PTSKCAT0 real-star catalog + magnitude-based visibility selection (VF-1) +
  renderer-facing adapter (VF-2a) exposing plain ra/dec/mag/bv/hip/name.
- GPS + manual location resolution; Bortle/SQM/limiting-magnitude modeling;
  Moon/Sun-altitude-aware limiting-magnitude estimate
  (`limitingMagnitudeAt`).
- Structured logging/debug-UI conventions to extend.

**Missing but easy:**
- Adding an `ImageAnalysis`/`ImageCapture` CameraX use case next to the
  existing `Preview` — scaffolding (lifecycle/permission/threading) already
  in place.
- A calibration/candidate-list debug panel following the
  `RealStarVisibilityDebugProvider` + `CatalogDebugScreen` pattern.
- Wiring `VisibleRealStarProvider` into a real (non-debug/non-test) caller —
  currently zero references outside its own package and tests.
- CameraX version bump (1.3.4 → 1.5.1+) before building new
  `ImageAnalysis`-dependent code on the older API surface.

**Missing and risky (real engineering, not just wiring):**
- **Camera intrinsics/FOV calibration.** `VERTICAL_FOV_DEG = 56.0` is a
  hardcoded constant, not derived from `Camera2CameraInfo`/
  `CameraCharacteristics` (focal length, sensor size,
  `LENS_INTRINSIC_CALIBRATION`, active-array crop) or from the actual
  `ImageAnalysis` output resolution/aspect. Pixel-accurate matching needs
  this replaced with real per-device intrinsics — currently absent.
- **Frame/sensor timestamp sync.** `RotationFrame.timestampNanos` is the raw
  sensor hardware timestamp; CameraX `ImageProxy.imageInfo.timestamp` uses a
  similar but not guaranteed-identical clock depending on OEM/camera HAL. No
  code today reads or reconciles these — needed to know "which pose
  corresponds to this frame."
- **Star-pattern matching itself.** `PtskCat0Catalog.nearby()` is explicitly
  documented as a "V1 spatial query... linear scan," a placeholder — no
  feature detection, centroiding, or geometric matching against camera
  pixels exists anywhere.
- **Lens distortion correction.** Untouched anywhere (expected, since no
  frame pixels are read yet).

**Blocked by unclear contract:** none found that block a first camera
slice. `docs/real_star_visibility_contract.md` §6 already anticipates
CAM-1..4 reusing `selection.indices`/`VisibleRealStarSnapshot` as the
candidate set, so there's no ambiguity about which catalog/service a
matcher should consume. The one open design question — where camera-frame
projection math should live, since today's projector is private to
`ArScreen.kt` — is a first-slice design decision, not a blocker.

## 7. Risks

| Risk | Assessment |
| --- | --- |
| **Coordinate handedness** | Internally consistent today (`transpose(rotationMatrix)` as world→device; `az = atan2(x,y)`), but only manually verified ("confirm on-device regardless" per code comment), not asserted end-to-end by a working automated test. A second projection surface (raw camera-frame pixels, which may have a different origin/rotation than `PreviewView`'s surface) must not silently diverge from this convention. |
| **Display rotation** | Handled for the preview via `remapForDisplay`, and — despite the Activity being portrait-locked — this remains production-relevant: `Display.rotation` is relative to the device's natural orientation, so natural-landscape form factors (tablets, Chromebooks, foldables) can report non-zero rotation for the same portrait-locked Activity. The path is currently unverified by a working automated test (see §3), which is the actual gap — not that the path is unused. `ImageAnalysis` reports its own `imageInfo.rotationDegrees`, distinct from display rotation — new logic, not yet written. |
| **Camera intrinsics/FOV** | Hardcoded 56° constant — a real blocker for pixel-accurate matching (§6). |
| **Lens distortion** | Unaddressed; likely tolerable for coarse first-pass matching but should be a written, explicit assumption rather than a silent gap. |
| **Frame/sensor timestamp sync** | No reconciliation logic exists (§6) — required before trusting "this pose ↔ this frame." |
| **Catalog epoch/proper motion** | PTSKCAT0 is fixed J2000.0, no proper-motion terms (by design, per `docs/star_catalog_ptskcat0_format.md`). Negligible for nearly all stars over ~26 years, but a few high-proper-motion stars (e.g. Barnard's Star, ~10″/yr) could be measurably off — worth an explicit documented tolerance rather than a silent assumption if CAM ever anchors calibration on a specific star. |
| **Moon/twilight/visibility filtering** | Not a gap — `limitingMagnitudeAt` (core/astro) already models Moon altitude/illumination and Sun altitude against a dark-sky NELM baseline and is already shared by AR/sky-map/card. Reusable as-is for a matcher's candidate-set filtering; avoid reinventing it. |
| **Performance on mobile** | Existing AR overlay already caps stars (1500 points/9 labels) and runs sensors at `SENSOR_DELAY_GAME`. `ImageAnalysis` + per-frame matching introduces a new, currently-unbounded cost center (decode, feature extraction, candidate matching) with no existing throttling/backpressure pattern to copy — needs its own design (frame skip/throttle, dedicated analysis executor) before shipping. |
| **Testability** | `:mobile` has **no Robolectric** (unlike `:wear`, which does) and sets `unitTests.isReturnDefaultValues = true`, so code calling real `android.hardware.*`/`SensorManager`/`Surface` statics is effectively untestable in a plain JVM unit test — confirmed root cause of the `ProjectionOrientationTest` failure (§3). CAM's camera/sensor-fusion glue will hit the same wall unless the team either adds Robolectric to `:mobile` or keeps CAM's testable core (projection math, matching, distortion correction) in plain-Kotlin `core:*` modules and isolates untestable Android glue to thin wrapper classes — the codebase already mostly follows the latter pattern (`PtskCat0Catalog`, `RealStarVisibilityFilter`, `LimitingMagnitudeModel` are all plain JVM), and CAM should continue it. |

## Test run performed (read-only inspection, not committed)

To resolve whether `ProjectionOrientationTest` reflects a real projection bug
or an environment artifact, this recon actually executed it (this
environment had no Android SDK/JDK 17 preinstalled, so both were provisioned
locally, used, and then removed/reverted — nothing here was committed):

```
# Android cmdline-tools + platform 35 + build-tools, installed to a scratch dir (not the repo)
curl -sSL -o cmdline-tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
sdkmanager --sdk_root=<scratch>/sdk "platform-tools" "platforms;android-35" "build-tools;35.0.0"

# JDK 17 (required by mobile/build.gradle.kts's jvmToolchain(17); only JDK 21 was preinstalled)
apt-get update && apt-get install -y --no-install-recommends openjdk-17-jdk-headless

# local.properties (gitignored, untracked) pointed sdk.dir at the scratch SDK;
# gradle.properties briefly gained org.gradle.java.installations.paths for the JDK17 toolchain,
# then was reverted via `git checkout -- gradle.properties` immediately after the run.
echo "sdk.dir=<scratch>/sdk" > local.properties

/opt/gradle-8.14.3/bin/gradle :mobile:testInternalDebugUnitTest \
  --tests "dev.pointtosky.mobile.ar.ProjectionOrientationTest" --console=plain
```

Result: **1 test, 1 failed** —
`java.lang.AssertionError: expected:<3> but was:<0>` at
`ProjectionOrientationTest.kt:40` (the first `assertEquals(horizontals.size,
portrait.size)`). Root cause traced in §3: `SensorManager.remapCoordinateSystem`
no-ops under `:mobile`'s Robolectric-less JVM test stub, zeroing the
rotation matrix and causing every projected point to fail the forward-facing
(`z < -0.01f`) visibility guard — a test-harness gap, not a math bug. The
repository's working tree was left clean after this (`git status` verified
clean; `gradle.properties`/`local.properties` changes were not committed).

## Files inspected

- Manifest/build: `mobile/src/main/AndroidManifest.xml`,
  `mobile/build.gradle.kts`, `mobile/lint-baseline.xml`
- Camera/AR: `mobile/ar/{CameraPreview,ArScreen,ArViewModel,RotationFrame,
  DisplayRemap,AstroOverlayModels}.kt`,
  `mobile/sensors/PhoneCompassBridge.kt`
- Projection/transform core: `core/astro-core/transform/EquatorialHorizontalTransform.kt`,
  `core/astro-core/coord/Coordinates.kt`, `core/astro-core/identify/Identify.kt`
- Catalog (PTSKCAT4): `core/astro/catalog/{PtskCatalogLoader,Models}.kt`
- Catalog (PTSKCAT0/VF-1/VF-2a): `core/catalog/binary/{PtskCat0Catalog,
  RealStarCatalogProvider,RealStarVisibilityFilter}.kt`,
  `core/catalog/visibility/{RealStarVisibilityService,LimitingMagnitudeModel}.kt`,
  `core/catalog/visibility/render/VisibleRealStarSnapshot.kt`,
  `core/catalog/runtime/CatalogRepository.kt`
- Visibility/rendering support: `core/astro/visibility/{LimitingMagnitudeAt,
  SkyBrightness,LightPollutionGrid}.kt`,
  `mobile/visibility/{LightPollutionProvider,VisibilitySettings,EffectiveBortle}.kt`,
  `mobile/render/BvColor.kt`
- Location: `mobile/location/DeviceLocationRepository.kt`
- Sky map (comparison path): `mobile/skymap/{SkyMapViewModel,SkyMapScreen,
  ConstellationOutlineLoader}.kt`
- Debug/UI: `mobile/catalog/{CatalogDebugScreen,RealStarVisibilityDebugProvider,
  CatalogRepositoryProvider}.kt`, `core/catalog/runtime/debug/CatalogDebugViewModel.kt`
- Logging: `mobile/logging/MobileLog.kt`
- Tests read: `mobile/src/test/.../ar/{ProjectionOrientationTest,ArOverlayScenarioTest,
  DeviceRollDegreesTest,AstroOverlayBuildersTest,ArViewModelTest}.kt`
- Docs: `docs/real_star_visibility_contract.md`,
  `docs/star_catalog_ptskcat0_format.md`, `PROJECT_OVERVIEW.md`,
  `MODULES.md`, `FILE_OVERVIEW.md`, `stage.md`
- Packer (asset generation context): `tools/catalog-packer/src/main/.../*.kt` (file listing only)

## Proposed next implementation-prep slice

A small, additive slice that retires two "missing but easy" items while
generating real data for the riskiest open question (timestamp sync),
without touching the renderer or adding any matching logic:

1. Add an `ImageAnalysis` use case alongside the existing `Preview` in
   `CameraPreview.kt` (frame-only — no processing yet), bumping CameraX to
   1.5.x first.
2. Wire `VisibleRealStarProvider` into one real (non-debug) call site, even
   if just logged via `MobileLog`, to retire "zero references outside its
   own package."
3. Add a debug-panel readout (via the `CatalogDebugScreen` extension point,
   §5) logging the delta between `ImageProxy.imageInfo.timestamp` and the
   nearest `RotationFrame.timestampNanos`, to measure real-world clock skew
   before any matching logic depends on it.
4. Fix or replace `ProjectionOrientationTest` so it actually exercises
   `remapForDisplay` (Robolectric shadow, or extracting the display-rotation
   math into an Android-independent pure function) — this is the
   recommended sequencing: land working coverage before adding more
   sensor/camera logic on the same untested surface.

## Recommended tests to add before further implementation

1. A working regression test for `remapForDisplay`/rotation-invariant
   projection (Robolectric-backed, or an Android-independent refactor),
   since the existing one cannot currently execute meaningfully.
2. A golden-vector test locking down the world↔device handedness convention
   (fixed known pose → fixed known Alt/Az → exact expected screen position),
   independent of the rotation test, before a second projection surface
   (camera-frame coordinates) is added that must agree with it.
3. A CameraX `ImageAnalysis` binding test (androidTest, since CameraX needs
   a real/emulated camera) verifying the new use case binds without
   disrupting the existing `Preview`.
4. Once `ImageAnalysis` lands: an assertion/log-based test quantifying
   frame/sensor timestamp skew, to turn §7's timestamp-sync risk into a
   measured number.
5. Unit tests for whatever intrinsics-resolution code replaces the
   hardcoded `VERTICAL_FOV_DEG` constant.

## Blockers

None. The catalog/service layering (PTSKCAT4 vs PTSKCAT0, VF-1, VF-2a) is
already documented and unambiguous about what a future matcher should
consume, and no camera/sensor API in use today prevents adding an
`ImageAnalysis` use case.

## Non-blocking risks (see §7 for full detail)

Coordinate handedness (unverified by working tests), display rotation
(untested path), hardcoded camera FOV/no intrinsics, no lens-distortion
handling, no frame/sensor timestamp sync, PTSKCAT0's fixed J2000 epoch (no
proper motion), unbounded future per-frame matching cost, and `:mobile`'s
lack of Robolectric for Android-framework-dependent code.

## Verdict

**CAM-READY WITH GATES**

The catalog/service/adapter chain (CAT-1 → VF-1 → VF-2a) and the core
projection/sensor math are solid, tested (where Android framework calls
aren't involved), and already anticipate this work. But three gates should
close before or alongside the first camera-matching slice, not after:

1. Fix the testability gap for Android-framework-dependent AR/sensor code
   (`ProjectionOrientationTest` currently can't verify what it claims to)
   before adding more untested surface on top of it.
2. Replace the hardcoded FOV constant with real camera intrinsics before any
   pixel-accurate matching depends on today's fixed-56°-vertical projection.
3. Instrument and measure frame/sensor timestamp skew (via the proposed
   implementation-prep slice above) before matching logic assumes frame and
   pose are synchronized.
