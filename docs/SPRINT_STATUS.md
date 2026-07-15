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
| CAM-2b | `internalDebug`-only predicted-star overlay: bounded catalog adapter, pure reducer, diagnostic state, Compose markers/panel/controls consuming `projectStars(...)` for visual diagnosis only, plus an explicit session/diagnostic-fallback intrinsics mode via a total, exact-copy `CameraSessionGeometry.withIntrinsics` substitution | Yes | Yes | **Yes — real Gradle, not a workaround.** `:core:astro-core:test` 388/388, `:mobile:testInternalDebugUnitTest`/`testPublicDebugUnitTest` 271/271 each (incl. `PredictedStarOverlayReducerTest`/`Format`/`CatalogAdapterTest`), `androidTest` compilation for both flavors (after fixing one pre-existing, CAM-2b-unrelated compile bug — see below); connected instrumentation **not run** (no device/emulator in this environment); see below | **Yes for debug** — `compileInternalDebugKotlin`/`compilePublicDebugKotlin`, `lintInternalDebug`/`lintPublicDebug` (0 errors), `assembleInternalDebug`/`assemblePublicDebug` all real-Gradle green. Release: Kotlin/Java compile green for both flavors; APK packaging blocked only by a missing release signing keystore (`storeFile`), not a code defect; see below | **No — `CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`** (build gates now sufficiently verified; no physical device or emulator was available in this environment) |

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
- **Validation closure pass (this session) — real Gradle, not the earlier `kotlinc` workaround.** This
  environment shipped neither an Android SDK nor the project's pinned Gradle 8.11.1 distribution (its
  download redirects to a `github.com` release asset blocked by this environment's egress policy, and its
  pinned JDK-17 toolchain was also missing); both were provisioned locally for this pass — an Android SDK
  (`platform-tools`, `platforms;android-35`, `build-tools;35.0.0`, via `sdkmanager` against the reachable
  `dl.google.com`) and `openjdk-17-jdk-headless` (via `apt`) — and the preinstalled Gradle 8.14.3 binary was
  used in place of the unreachable pinned 8.11.1 (AGP 8.7.2 built and ran cleanly under it for every gate
  below; this substitution is a recorded environment deviation, not a silent one). With those in place,
  every gate below is a real, executed Gradle result:
  - *Main compilation*: `:mobile:compileInternalDebugKotlin` / `compilePublicDebugKotlin` — **PASS**
    (pre-existing warnings only: a deprecated `defaultDisplay` API and a missing `@OptIn` marker, neither
    touched by CAM-2b).
  - *JVM unit tests*: `:core:astro-core:test` — **PASS, 388/388** (0 failures/errors; includes
    `CameraSessionGeometryTest` 34, `PreparedStarProjectionContextTest` 3, `CameraStarProjectionTest` 35,
    each also run individually via `--tests`). `:mobile:testInternalDebugUnitTest` /
    `testPublicDebugUnitTest` — **PASS, 271/271 each** (0 failures/errors; includes
    `PredictedStarOverlayReducerTest` 32, `PredictedStarOverlayFormatTest` 6,
    `PredictedStarCatalogAdapterTest` 10, each also run individually via `--tests`).
  - *androidTest compilation* (`:mobile:compileInternalDebugAndroidTestKotlin` /
    `compilePublicDebugAndroidTestKotlin`): **initially FAILED** on a real, reproducible compile error —
    not in any CAM-2b file (`PredictedStarOverlayUiTest.kt` already compiled cleanly, using exactly the
    imports the task spec calls for: `androidx.compose.ui.test.assert`, `org.junit.Assert.assertEquals`),
    but in the pre-existing, unrelated `PreProdSmokeMobileTest.kt`: an invalid
    `import androidx.compose.ui.test.assertExists` (that symbol is a member function of
    `SemanticsNodeInteraction` in the resolved Compose UI Test 1.7.2, not a top-level import) and a missing
    `import dev.pointtosky.mobile.settings.from` (an extension function on `MobileSettings.Companion`,
    already correctly imported the same way in `MainActivity.kt`/`DlReceiverService.kt`). Fixed by
    deleting the stale import and adding the missing one (2-line diff, no production code touched); rerun
    — **PASS** for both flavors.
  - *Lint*: `:mobile:lintInternalDebug` / `lintPublicDebug` — **PASS, 0 errors** (35 pre-existing warnings,
    148 errors/54 warnings pre-filtered by the existing `lint-baseline.xml`; none attributable to CAM-2b).
  - *Assemble*: `:mobile:assembleInternalDebug` / `assemblePublicDebug` — **PASS**, both debug APKs built.
  - *Release*: `compileInternalReleaseKotlin` / `compilePublicReleaseKotlin` — **PASS**. Full
    `assembleInternalRelease` / `assemblePublicRelease` — **blocked**: `packageInternalRelease` fails with
    `SigningConfig "release" is missing required property "storeFile"` (no release keystore is configured
    in this environment — a credential-provisioning limitation, not a code defect). One run additionally
    hit a non-reproducible crash inside AGP's *bundled* Lint tool
    (`ConcurrentModificationException` in `GradleDetector`/`TomlUtilities` during
    `lintVitalAnalyzeInternalRelease`) that did not recur on retry and does not occur on the debug-variant
    lint gates against the identical `build.gradle.kts` — recorded as an observed upstream tooling flake,
    out of CAM-2b's scope to fix.
  - *Connected instrumentation tests* (`:mobile:connectedInternalDebugAndroidTest`): **not run** — no
    physical device and no emulator (no `/dev/kvm`, no hardware-virtualization CPU flags) are available in
    this environment; `adb devices -l` lists none.
  See `docs/camera_star_prediction_contract.md` §14.10 for the full itemized disclosure and
  `docs/validation/cam_2b_device_validation.md` for the still-unexecuted physical checklist.

**Overall CAM-2b status: `CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`** — every build/compile/unit-test/
lint/debug-assemble gate above is now real-Gradle green (one real, pre-existing bug found and fixed along
the way); only the physical-device/emulator checklist remains unexecuted, for lack of any device or
emulator capability in this environment.
