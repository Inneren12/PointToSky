# Sprint Status

Quick-reference status board for the camera/AR prediction workstream (CAM-*). For full derivations and
risk registers, see `docs/camera_coordinate_calibration_contract.md` and
`docs/camera_star_prediction_contract.md`. This file only tracks what is implemented, tested, and
device-validated — it does not restate the underlying design.

## CAM slice status

**"Tests authored" and "Tests executed" are deliberately separate columns.** A slice whose tests were
written and reviewed by hand but never actually run through Gradle/JUnit is "authored: Yes, executed:
No" — it is never described as simply "tested," which would misstate what was actually verified.

| Slice | What it is | Implemented | Tests authored | Tests executed | Build verified | Device-validated |
|---|---|---|---|---|---|---|
| CAM-1c–1f | CameraX `Preview`+`ImageAnalysis`, frame metadata, timestamp-paired rotation, crop/rotation/display transform, immutable `CameraSessionGeometry`, session-scoped geometry provider | Yes | Yes | Yes (per that PR's own record) | Yes (per that PR's own record) | No |
| CAM-1g | `internalDebug`-only camera-geometry diagnostics overlay; observable geometry result + debug counters | Yes | Yes | Yes (per that PR's own record) | Yes (per that PR's own record) | **No — `CAM-1g BLOCKED ON PHYSICAL DEVICE VALIDATION`** |
| CAM-2a | Pure, Android-free star prediction (`projectStars`): catalog RA/Dec + observer location/time + magnetic declination + `CameraSessionGeometry` → predicted camera/image/display positions, with typed unavailable outcomes | Yes | Yes | Yes (388 tests, `:core:astro-core`, verified via a direct `kotlinc`/JUnit-console invocation — Gradle itself could not run; see `docs/camera_star_prediction_contract.md` §13) | Partial (`:core:astro-core` compilation confirmed this way; `:mobile` not attempted by that PR) | No — not wired into any renderer, no device claim |
| CAM-2b | `internalDebug`-only predicted-star overlay: bounded catalog adapter, pure reducer, diagnostic state, Compose markers/panel/controls consuming `projectStars(...)` for visual diagnosis only, plus an explicit session/diagnostic-fallback intrinsics mode via a total, exact-copy `CameraSessionGeometry.withIntrinsics` substitution | Yes | Yes | **Partial** — Android-free subset only: `:core:astro-core` (398/398) and `:mobile`'s prediction-package unit tests (48/48), via a `kotlinc`/JUnit-console workaround, not real Gradle; Compose/`androidTest`/instrumentation tests **not run**; see below | **Partial** — same Android-free subset compiled via the workaround; real Gradle main/androidTest compilation, lint, and assemble **not run** | **No — `CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`** |

## CAM-2b (this sprint)

- **Scope:** visualizes CAM-2a's output; never changes production star placement.
  `ArScreen.calculateOverlay()`/`projectionParams(viewport)` unchanged except a byte-for-byte-equivalent
  extraction (`visibilitySelectedStars`) shared with the new overlay.
- **Gate:** reuses `CameraGeometryDiagnosticsGate`/`isDiagnosticsEnabled` verbatim — no second flavor
  check. Enabled only for `internalDebug`.
- **Catalog input:** bounded (200), deterministic (brightest-first), sourced from the phone's own
  visibility-selected `StarRecord` prefix — no second catalog parser, no spatial index.
- **Observer/time/declination:** reuses `ArUiState.Ready.location`/`.instant` and the legacy renderer's
  already-computed `GeomagneticField` declination — no independent `System.currentTimeMillis()`, no
  second `GeomagneticField` instance, no silent `0°` fallback.
- **True-north ownership:** raw, magnetic-north-referenced `CameraSessionGeometry` rotation +
  non-zero `StarProjectionContext.magneticDeclinationRad` — never a matrix pre-corrected via
  `correctedForTrueNorth` in addition to a non-zero context declination (would double-correct).
- **Recomputation:** Compose `remember`, keyed on geometry observation / observer inputs / bounded star
  subset — no polling, no timer, no permanent coroutine, no unbounded queue.
- **Drawing:** only `VISIBLE_IN_VIEWPORT` predictions, anchored exactly at CAM-2a's `displayPoint`, no
  further transform. Fixed cyan diagnostic marker style, drawn on top of the unchanged legacy overlay.
- **Intrinsics mode:** an explicit `PredictedStarOverlayIntrinsicsMode` (`SESSION_INTRINSICS` default,
  `DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK` opt-in) lets CAM-2b draw markers on sessions whose real
  intrinsics are `PhysicalSensor`-referenced (common once Camera2 characteristics resolve), by
  substituting a legacy fixed-FOV, analysis-buffer-referenced intrinsics sized to the exact current
  frame — via `CameraSessionGeometry.withIntrinsics` (`:core:astro-core`), a total (cannot-fail) field-
  level copy of the accepted geometry bundle that changes only `intrinsics` and never re-derives a
  pairing tolerance from the accepted delta, never mutates the original session geometry or provider,
  and is always labeled distinctly ("session" vs. "diagnostic fallback," never "calibrated") in the
  panel.
- **Batched astronomy:** `projectStars` now computes local sidereal time once per batch
  (`PreparedStarProjectionContext`, `:core:astro-core`) instead of once per star, avoiding up to 200
  redundant `lstAt` calls per frame; the public `equatorialToLocalSky(star, context)` API and its output
  are unchanged.
- **Validation — four separate gates, not one collapsed claim.** Real Gradle still cannot run in this
  sandbox at all (no Android SDK; `:core:astro-core`'s pinned JDK-17 toolchain download is blocked by
  egress policy). Two of the four gates were nonetheless exercised for real via a `kotlinc`/JUnit-console
  workaround (matching CAM-2a's own documented precedent) — not fabricated, not merely "authored":
  - *Main compilation*: `:core:astro-core` main+test — **actually compiled**, clean. `:mobile`'s CAM-2b
    prediction-package files (reducer/adapter/state/format/intrinsics-fallback, plus the two
    Android-free files they depend on and `core/astro`'s catalog `Models.kt`) — **actually compiled**,
    clean, against the real `:core:astro-core` output. `:mobile`'s Compose/`ArScreen.kt` integration —
    **not compiled**; requires `android.jar`, the Compose K2 compiler plugin, and AndroidX/CameraX AARs,
    which a bare `kotlinc` invocation cannot replicate (the same boundary CAM-2a's own author drew).
  - *JVM unit tests*: `:core:astro-core:test` — **actually run: 398/398 passing** (388 pre-existing + 3
    `PreparedStarProjectionContextTest` cases + 7 follow-up `CameraSessionGeometry.withIntrinsics`
    exact-preservation/non-mutation/defensive-ownership cases). `:mobile`'s `PredictedStarCatalogAdapterTest`/
    `PredictedStarOverlayReducerTest`/`PredictedStarOverlayFormatTest` — **actually run: 48/48 passing**
    (45 pre-existing + 3 follow-up diagnostic-fallback synchronization-metadata cases; JUnit4/
    `kotlin-test-junit`, matching `:mobile`'s real Gradle binding; one disclosed stub `BuildConfig` object
    stood in for the AGP-generated one, never read by the code under test). `:mobile`'s Compose UI test
    (`PredictedStarOverlayUiTest`) — **not run**, same reason as above.
  - *androidTest compilation* (`:mobile:compileInternalDebugAndroidTestKotlin`/
    `compilePublicDebugAndroidTestKotlin`): **not run** — requires the Android SDK.
  - *Connected instrumentation tests* (`:mobile:connectedInternalDebugAndroidTest`): **not run** — no
    device/emulator available.
  The `kotlinc` workaround is corroborating evidence, not a substitute for real Gradle — it exercises
  neither Gradle's own dependency resolution, resource merging, ProGuard/R8, nor manifest merging. See
  `docs/camera_star_prediction_contract.md` §14.10 for the full disclosure and
  `docs/validation/cam_2b_device_validation.md` for the unexecuted physical checklist.

**Overall CAM-2b status: `CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`** (and, separately, on someone
with a working Android SDK/Gradle environment actually running the four gates above).
