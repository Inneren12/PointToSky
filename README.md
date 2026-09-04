# PointToSky

PointToSky is a stargazing companion for Android phones and Wear OS watches:
point the device at the sky and it aims/identifies targets using device
orientation, location, and time. What sets it apart from a typical
planetarium app is two pieces of from-scratch, physically-principled work:
a light-pollution / sky-brightness pipeline derived from NASA VIIRS Black
Marble imagery and the Garstang/Duriscoe skyglow model, and an
on-device, camera-based star detection pipeline (pixels → point sources →
predicted sky positions, with the correspondence/matching step still to be
built — see below). Everything else — ephemeris, catalog lookups, tiles,
complications, AR aiming UI — exists to put those two pieces of work in
front of a user.

This document describes what actually exists in this repository today, not
a roadmap. Where a feature is partially built or unverified, it is called
out as such rather than described as done.

## Status

| Area | Status | Notes |
| --- | --- | --- |
| Ephemeris, coordinate transforms, Tonight target selection | Working | `:core:astro-core` / `:core:astro`, unit-tested |
| Real star catalog (PTSKCAT0) load + magnitude/visibility filtering | Working | `:core:catalog`, `RealStarVisibilityService`; consumes whichever sky-brightness number is available (grid lookup or manual entry) — see `docs/real_star_visibility_contract.md` |
| Light-pollution / sky-brightness grid (Bortle + NELM from SQM) | Working, but regional only | One 10°×10° tile (lat 50–60N, lon 120–110W, Alberta); see below |
| Light-pollution grid build pipeline (`res/skyglow/`, Python) | Working, tracked and tested | `build_bortle_bin.py`/`calibrate_scale.py` + the rest of the pipeline; `pytest res/skyglow/tests` — 101 passed, 1 skipped. `diag_*.py` and `test_resolution.py`, two roadmap-mentioned diagnostic scripts, are not present anywhere in the tracked tree or git history after the July 2026 history rebuild and are recorded as not reconstructable (commit `56009a0`) |
| Wear Tonight tile, complications, aim/identify UI | Working | Instrumented tests require a device/emulator; see `docs/wear_tonight_tile_testing.md` |
| Camera pose/geometry capture (CameraX frame metadata, rotation, crop/scale transform) | Working, not device-validated | CAM-1, see `docs/SPRINT_STATUS.md` |
| Pure star-projection math (catalog → predicted camera/image pixel) | Working, not wired into production UI | CAM-2a, pure JVM, `:core:astro-core` |
| Predicted-star debug overlay | Working, `internalDebug`-only | CAM-2b |
| On-device pixel-level star detection (centroids from luma frames) | Working | SKY-2, pure JVM, `docs/star_detection_contract.md` |
| Sky session capture/replay format + CLI analyzer | Working | SKY-1/SKY-3 tooling, see Build & test below |
| Calibrated analysis-buffer camera intrinsics (Camera2 → `ImageAnalysis` buffer) | Implemented, blocked | CAM-2c; hits `UnsupportedLogicalMultiCameraMapping` on logical multi-camera devices — reached by analysis of the CameraX 1.4.2 sensor-to-buffer mapping (`docs/recon/cam_2c_sensor_to_buffer_domain_recon.md`), not confirmed on a device; falls back to the legacy path. See `docs/camera_coordinate_calibration_contract.md` |
| Star matcher (pixel detections → catalog star identity) | Not implemented | Input contract only — see `docs/star_matcher_input_contract.md`: "no matching algorithm exists yet" |
| Global / multi-tile light-pollution coverage | Not built | Tracked as roadmap items L-2 (multi-tile) and L-4 (on-demand regional download); see `res/skyglow/REAL_GRID_RUNBOOK.md` |
| Physical-device validation of the CAM-1/2a/2b/2c slices | Not done in this environment | No device/emulator available in the environments these slices were built in; see `docs/SPRINT_STATUS.md` for the per-slice record |

The authoritative, per-slice record of what's implemented vs. tested vs.
device-validated for the camera workstream is `docs/SPRINT_STATUS.md`. Do
not rely on `PROJECT_OVERVIEW.md`, `CONTENT_GUIDE.md`, or `MODULES.md` for
current status — they predate several of the changes above and are not
kept in sync with implementation state module-by-module (for example,
`PROJECT_OVERVIEW.md`'s module list omits `:tools:sky-session-loader`,
added after that doc was last updated; see the module map below for the
current module list).

## Three orthogonal axes

The astronomy work here decomposes into three orthogonal sources of error
and their remedies — not three stages of one pipeline — which is why they
can be built, tested, and validated independently.

**Propagation** — turning satellite-observed night-light radiance into a
sky-brightness estimate at a given latitude/longitude. Implemented as an
offline Python pipeline (`res/skyglow/`) that reads NASA VIIRS Black Marble
HDF5 tiles, convolves them with a Garstang/Duriscoe-derived kernel, and
calibrates against real SQM (sky-quality-meter) readings. Produces the
`PTSKLP01` binary grid consumed at runtime by `LightPollutionGrid` in
`:core:astro`, baked in at build time so it costs nothing at runtime. Error
here is addressed by a better physical model. Full build/calibration
procedure: `res/skyglow/REAL_GRID_RUNBOOK.md`.

**Spectrum** — an acknowledged limitation of the shipped grid, not
something this pipeline solves. VIIRS DNB, the satellite sensor the grid is
built from, is blind below roughly 500 nm, so the blue pump of white LEDs
is largely unobserved; as outdoor lighting keeps shifting to white LEDs
worldwide, this under-measurement gets worse over time, not better. No
amount of propagation-model improvement fixes this, because it is a
limitation of the input data, not of the physics being modeled. The only
remedies are different input data — no such global dataset exists — or an
on-device measurement, since a phone camera has a blue channel the
satellite lacks.

**On-device camera** — a separate runtime layer, not a correction baked
into the grid. It measures actual conditions here and now for one
observer: sky background, observed limiting magnitude, and, via the blue
channel, a local spectral signal the satellite grid cannot see. The
honesty boundary: the camera measures the *result* on the sky, not the
light sources responsible for it. Pipeline: capture geometry (CAM-1) →
pixel-level detection (SKY-2) → predicted catalog-star positions from pose
(CAM-2a/2b) → calibrated intrinsics (CAM-2c — reached by analysis of the
CameraX 1.4.2 sensor-to-buffer mapping, not confirmed on a device; see
Status table) → matching detections to catalog identities (not yet
implemented). Contracts: `docs/camera_coordinate_calibration_contract.md`,
`docs/camera_star_prediction_contract.md`, `docs/star_detection_contract.md`,
`docs/star_matcher_input_contract.md`.

Whichever sky-brightness number ends up available — grid lookup or manual
entry — `RealStarVisibilityService` (`:core:catalog`) consumes it to decide
which PTSKCAT0 catalog stars are visible; see the Status table and
`docs/real_star_visibility_contract.md`.

## Module map

Modules as declared in `settings.gradle.kts`:

| Module | Type | Responsibility |
| --- | --- | --- |
| `:wear` | Android app | Wear OS UI (aim/identify, settings), Tonight tile service + refresh worker, complications, data-layer bridge to phone |
| `:wear:benchmark` | Android test module (macrobenchmark) | Macrobenchmark cases run against `:wear` |
| `:wear:sensors` | Android library | Sensor-fusion orientation providers (rotation vector / accel-mag), low-pass filtering, magnetic declination |
| `:mobile` | Android app | Phone UI (aim/identify, search, Tonight cards, settings/onboarding), CameraX-backed AR entrypoint, crash log capture/sharing |
| `:core:common` | Kotlin Multiplatform library | Shared constants and the phone↔watch data-layer message envelope/codec |
| `:core:logging` | Android library | Structured logging, ring-buffer sink, crash log storage, redaction |
| `:core:location` | Android library | Location orchestration (fused + manual), DataStore-backed preferences |
| `:core:time` | Android library | Julian date math, timezone repository, time sources |
| `:core:astro-core` | Pure JVM (Kotlin) | Android-free astronomy math: coordinate transforms, ephemeris primitives, aim geometry, and the full camera projection/detection/session-log stack (prediction, detection, matcher input types, sky session log codec/replay) |
| `:core:astro` | Android library | Ephemeris over catalogs, Tonight target selection, light-pollution grid decoder (`LightPollutionGrid`), sky-brightness/limiting-magnitude models |
| `:core:catalog` | Android library | PTSKCAT0 binary catalog adapter, runtime `CatalogRepository`, `RealStarVisibilityService`, catalog debug UI state |
| `:tools:ephem-cli` | Pure JVM CLI | Command-line ephemeris computation over `:core:astro-core` |
| `:tools:catalog-packer` | Pure JVM CLI | Parses HYG/BSC source catalogs and constellation boundary data, packs the `PTSKCAT0`/`const_v1` binary assets |
| `:tools:sky-session-loader` | Pure JVM CLI | Loads a captured SKY-1 session directory and runs parse → replay → detect → evaluate, printing per-frame and aggregate detector metrics |

`store/` exists at the repository root but is not included in
`settings.gradle.kts` — it holds Play Store listing assets (screenshots,
icons, disclaimers), not code.

## Build & test

Build the debug APKs (both apps build `internal`/`public` distribution
flavors — see Module map below):

```bash
./gradlew :mobile:assembleInternalDebug :mobile:assemblePublicDebug
./gradlew :wear:assembleInternalDebug :wear:assemblePublicDebug
```

Run the pure-JVM test suites (no Android SDK/emulator required):

```bash
./gradlew :core:astro-core:test
./gradlew :tools:catalog-packer:test
./gradlew :tools:sky-session-loader:test
```

(`:tools:ephem-cli` has no test sources of its own — it only exposes a
`main` CLI entry point over `:core:astro-core`.)

Run the Android-module unit tests (JVM/Robolectric, no device). Both
`:mobile` and `:wear` build `internal`/`public` distribution flavors, so
the unit-test tasks are flavor-qualified:

```bash
./gradlew :mobile:testInternalDebugUnitTest :mobile:testPublicDebugUnitTest
./gradlew :wear:testInternalDebugUnitTest :wear:testPublicDebugUnitTest
```

Instrumented tests (`connectedAndroidTest`) require a connected device or
emulator; see `docs/wear_tonight_tile_testing.md` for the Wear-specific
runbook and pairing setup.

Run the sky-session analyzer CLI against a captured session directory:

```bash
./gradlew :tools:sky-session-loader:installDist
tools/sky-session-loader/build/install/sky-session-loader/bin/sky-session-loader <session-dir>
```

Session format: `docs/sky_session_log_format.md`.

**Lint/static analysis:** ktlint and detekt are wired into `check` for every
module. Locally they run with `ignoreFailures = true` (advisory — warnings
are printed but the build doesn't fail). In CI, or when you pass
`-Pstrict` (or set `CI=true`) locally, both are enforced and the build
fails on violations:

```bash
./gradlew check -Pstrict
```

**Release builds are unsigned by default.** The `release` build type reads
its signing config from `P2S_MOBILE_KEYSTORE_PATH` / `_PASSWORD`,
`P2S_MOBILE_KEY_ALIAS`, `P2S_MOBILE_KEY_PASSWORD` (and the `P2S_WEAR_*`
equivalents for `:wear`). See `gradle.properties.sample`. Without those
environment variables/properties set, `assembleRelease` produces an
unsigned APK.

## Data & assets

**Light-pollution grid — `PTSKLP01` v3** (`mobile/src/main/assets/lightpollution/bortle.bin`):
a zlib-compressed grid of continuous SQM values, decoded at runtime into
fractional Bortle class and naked-eye limiting magnitude. Verified header
of the shipped asset:

```
magic=PTSKLP01  version=3  rows=800  cols=800
latTop=60.0  lonLeft=-120.0  deg=0.0125
```

That is an 800×800 grid at 0.0125° resolution covering **lat 50–60°N,
lon 120–110°W** (a single VIIRS tile over Alberta, Canada) — not global
coverage. Outside this tile the app reports sky brightness as unavailable
and falls back to manual entry. Build/calibration procedure, including the
12-point atlas-SQM calibration and acceptance criteria:
`res/skyglow/REAL_GRID_RUNBOOK.md`.

**Real star catalogs — `PTSKCAT0`** (16-byte records, RA/Dec/mag/B−V/HIP,
`docs/star_catalog_ptskcat0_format.md`):

| Path | Stars | Magnitude limit |
| --- | --- | --- |
| `mobile/src/main/assets/catalog/stars_real.bin` | 41,487 | ≤ 8.00 |
| `wear/src/main/assets/catalog/stars_real.bin` | 8,920 | ≤ 6.50 |

Both are packed from the HYG v4.2 catalog by `:tools:catalog-packer`.
`star.bin` and `const_v1.bin` in the same directories are a separate,
hand-curated `PTSKCAT4` asset used for constellation figure/art rendering,
not for magnitude-based visibility filtering or matching.

## Licensing

Code in this repository is licensed under the Apache License, Version 2.0;
see `LICENSE` at the repository root.

Bundled data carries its own obligations, tracked in `NOTICE.md`:

- HYG Database v4.2 (the source for the packed `PTSKCAT0` catalogs) is
  licensed CC BY-SA 4.0 — any redistribution of the packed catalog assets
  must carry the same attribution and license.
- NASA Black Marble (VIIRS/NPP VNP46A4), the source for the light-pollution
  grid, is public domain but requires attribution per NASA's data policy.
- Constellation boundaries/figure lines (d3-celestial) are BSD-3-Clause,
  reproduced in full in `NOTICE.md`.
- Yale Bright Star Catalogue (BSC5) is public domain.

Read `NOTICE.md` before redistributing any asset under `mobile/src/main/assets/`
or `wear/src/main/assets/`.

## Document index

- `docs/SPRINT_STATUS.md` — authoritative implemented/tested/device-validated
  record for the camera (CAM-*) workstream.
- `docs/camera_coordinate_calibration_contract.md` — CAM-0b/2c: Camera2 →
  `ImageAnalysis`-buffer intrinsics, coordinate-space discipline, the
  logical-multi-camera block.
- `docs/camera_star_prediction_contract.md` — CAM-2a: catalog + pose →
  predicted camera/image/display pixel positions.
- `docs/star_detection_contract.md` — SKY-2: frame pixels → sub-pixel point
  sources.
- `docs/star_matcher_input_contract.md` — SKY-3: the input contract the
  (not yet implemented) matcher will consume.
- `docs/real_star_visibility_contract.md` — VF-1: Bortle/SQM/limiting
  magnitude → visible-star selection over `PTSKCAT0`.
- `docs/star_catalog_ptskcat0_format.md` — `PTSKCAT0` binary layout.
- `docs/constellation_const_v1_format.md` — `const_v1.bin` binary layout.
- `docs/sky_session_log_format.md` — SKY-1 capture format and where its
  code lives.
- `res/skyglow/REAL_GRID_RUNBOOK.md` — how the `PTSKLP01` light-pollution
  grid is built and calibrated.
- `docs/wfs_integration.md` — Watch Face Studio tap-area integration for
  opening Aim from a third-party watch face (in Russian).
- `docs/wear_tonight_tile_testing.md` — Wear Tonight tile setup and test
  runbook (in Russian; moved here unchanged from the previous root
  `README.md`).
- `docs/data-safety.md` — data collection/storage/sharing categories (Play
  Data Safety section source).
- `docs/preprod-check.md` — the `preprodCheck` Gradle task's end-to-end
  checklist.
- `NOTICE.md` — third-party data attribution and licenses.
