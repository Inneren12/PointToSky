# PointToSky Master Recon

```text
Repository:                 Inneren12/PointToSky (GitHub; single repository, phone + watch + shared core + tools)
Code snapshot audited:      main @ 09b16549cac78fc6e9f2649bf142d146ccfd11eb  ("Merge pull request #241 …", 2026-09-04)
Recon branch:               claude/pointtosky-master-audit-k4rws7  (PR #243 — this master recon)
Document revision base:     revision 4 @ a77bd8f7a503d56fbf758f015a418a0ed623f7eb (this text is revision 5,
                            committed on top of it; later revisions: `git log -- <this file>`)
Earlier revisions:          revision 1 @ 7987ba8, revision 2 @ e846486, revision 3 @ 5b73330
Delta from audited snapshot: documentation only — docs/pointtosky/MASTER_POINTTOSKY_RECON_2026-10-06.md
                            (no production code, test, build or asset change)
Recon date:                 2026-10-06 (revisions 2–5 same day: corrections listed in "Revision log" at the end)
Relevant application modules: :mobile, :wear, :wear:sensors, :wear:benchmark, :core:astro-core, :core:astro,
                            :core:catalog, :core:common, :core:location, :core:time, :core:logging,
                            :tools:catalog-packer, :tools:sky-session-loader, :tools:ephem-cli, res/ (Python data builders)
Relevant historical branches: none carry unmerged autodetect work. Open PRs: #236 (camera-ray angle helper,
                            claude/camera-ray-angle-api-adb9hj, 3 commits, not merged), #242 (older README-only
                            wording PR, claude/readme-spectrum-axis-fix-h9dntw, 1 commit — unrelated to this
                            recon PR #243). 191 pre-2026-07 branches
                            (codex/*, older claude/*, feature/sqm-grid-v3) share no history with main.
Previous recon:             docs/recon/RECON_main_2026-09-02.md (state at b6bdc7c; build/CI/asset audit)
```

This document describes **repository truth of the code snapshot `main @ 09b1654`**. Every claim has a `path:line` reference. Status words:
`DONE`, `PARTIAL`, `MISSING`, `BROKEN`, `DEAD / LEGACY`, `SCAFFOLD / MOCK`, `UNVERIFIED ON DEVICE`, `UNKNOWN`.
Where it matters, evidence is split into *implemented / unit-tested / integration-tested / device-tested /
production-used*. Nothing in this recon was device-tested by the recon itself (no device or emulator is attached to
the cloud container). Gradle could not run here either: there is no Android SDK, the JDK is 21 only, the Maven
Central mirror returned HTTP 429, and the `com.jaredsburrows.license` plugin did not resolve. So test counts below are
`@Test` counts read from source plus the last recorded run (2026-09-02 recon §6), not new executions.

---

## 1. Executive summary

**What PointToSky is today.** It is a working **sensor-only** stargazing app. The phone has an AR camera backdrop, a
sky map, search, cards and settings. The watch has Aim (an arrow plus hold-to-lock), Identify, a Tonight tile and
complications. Pointing on both devices comes from the Android rotation-vector sensor plus WMM magnetic
declination. The camera on the phone is **only a visual backdrop**. The production AR overlay is drawn with a
**fixed 56° vertical FOV** guess (`core/astro-core/.../projection/Projection.kt:40,130-137`), the legacy BSC-derived
`star.bin` catalog, and the latest unpaired rotation sample.

**What exists toward autodetect.** A large, carefully-contracted, pure-JVM foundation, all in `:core:astro-core`:
- camera frame metadata, rotation pairing and crop/scale transforms (CAM-1);
- a pinhole star predictor (CAM-2a) and an `internalDebug` predicted-star overlay (CAM-2b);
- analysis-buffer intrinsics machinery (CAM-2c);
- a tiled-background star detector (SKY-2);
- a session-log capture/replay format with an offline analyzer (SKY-1/SKY-3);
- a matcher **input contract** (`StarMatcherInput`, `StarCatalogQuery`, `AnalysisBufferScale`).

The math is unit-tested to a high standard and the contracts are explicit.

**What does not exist.** Each of these is missing on `main` and on every branch:
- a star matcher (no correspondence search, no invariants, no RANSAC, no pose solve);
- an optical attitude correction;
- temporal optical tracking or lock;
- a navigation-star set;
- any production code path that reads camera **pixels**;
- any autofocus control;
- any real night-sky frame or session.

**Verdict.**
- The autodetect pipeline is **architecturally incomplete**. It has well-built geometry and detection *components*,
  but the identification core (matcher → attitude → tracker → corrected pose) is entirely missing, and none of the
  components run in the production app.
- The visible navigation-star set is **not used to constrain matching**: no set exists and no matcher exists. The
  only candidate generator (CAM-2b, debug-only) feeds the **brightest 200 stars of the whole celestial sphere**,
  including those below the horizon (`mobile/.../camera/prediction/PredictedStarCatalogAdapter.kt:36-65`).

**Correctness defects found during recon.** These are documented here, not fixed (see §27):
- **The ephemeris is one day behind for the Sun, Moon, Jupiter and Saturn.** `SimpleEphemerisComputer.kt:36,369`
  uses JD 2451544.5 as the epoch of Schlyter's elements, whose epoch is JD 2451543.5. That puts the Sun about 1.1°
  off and the Moon about 13° off. Verified independently: Sun RA on 2025-01-01T00Z is 280.663° in the code, 281.767°
  with the correct epoch, and 281.768° per Meeus. The golden test is a self-snapshot and cannot catch it.
- **No precession.** J2000 star coordinates are compared with sidereal time of date, a systematic error of about
  0.36° in 2026 (estimate: about 50.3″/yr × 26 yr).
- **No proper motion.** The HYG source carries `pmra`/`pmdec`, but `HygRealCatalogParser` drops them, so PTSKCAT0
  positions are epoch-2000 positions used against 2026 observations (§6.2, §8.3).
- **No atmospheric-refraction policy for optical prediction.** CAM-2a and almost every caller use
  `applyRefraction = false`. A future matcher would read low-altitude refraction as camera or attitude error (§8.3).
- **Watch Aim can overwrite the requested target with a picker default.** There are two paths, with different
  failure shapes (§17.1).
  - `/app/open`: an equatorial or star `initialTarget` maps to picker index 4, which is Polaris.
  - `/aim/set_target` entered from Home: the picker starts at index 0, the Sun, and its first emission may replace
    the `externalAim` target.
  - Evidence: `wear/.../aim/ui/AimScreen.kt:115-118,160-168,241-260,550-563`. Found by code reading; UNVERIFIED ON
    DEVICE.
- **Watch Identify falls back to a body at any distance.** The nearest-body fallback has no distance cap, so
  Identify can return the Moon 150° away (`wear/.../identify/IdentifyViewModel.kt:220-242`).
- **Watch Tonight tile never gets a location** (`wear/.../tile/tonight/TonightTileService.kt:77-82` with
  `RealTonightProvider.kt:55`).
- **Watch offline star resolver opens a missing asset** (`catalog/stars_V1.bin`; `OfflineStarResolver.kt:20-25`).
- **Phone-heading override never reaches Aim or Identify.** It changes `azimuthDeg` only, while Aim and Identify read
  `forward` (`PhoneHeadingOverrideRepository.kt:19-31`).

**Top 5 blockers for Goal 1** (detail in §27):
1. **No matcher / optical attitude solution** (MISSING).
2. **No on-device detection path.** Production `ImageAnalysis` reads metadata only
   (`mobile/.../ar/camera/CameraFrameAnalyzer.kt`), and `detectStars` has never run on a device or on real sky.
3. **Camera model not trustworthy on the Pixel 9 target**:
   - calibrated intrinsics are blocked by `UnsupportedLogicalMultiCameraMapping` (device-observed under CameraX
     1.3.4);
   - **per-frame active-physical-camera identity is UNINVESTIGATED**. `ImageInfo` does not carry it, but a Camera2
     capture result may (`CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`, readable through the session
     capture callback the repository already installs in SKY-1);
   - **distortion-correction state is unknown**. `DISTORTION_CORRECTION_MODE` is never read or set. It decides both
     whether the YUV is lens-corrected and which sensor coordinate basis (pre-correction vs active array) the metadata
     uses (§10.1, §11.5);
   - **camera↔device extrinsics are unaudited**. `LENS_POSE_ROTATION` / `LENS_POSE_REFERENCE` are never read, and the
     code relies on nominal axes (§14.1);
   - autofocus has never been configured anywhere in the repository or its history;
   - the current CameraX 1.4.2 analysis resolution is UNVERIFIED ON DEVICE. 640×480 was observed under 1.3.4;
   - Preview and ImageAnalysis are bound without a `ViewPort`.
4. **No visible navigation-star candidate predictor.** There is no navigation set, no horizon gate in CAM-2a, no
   uncertainty-aware candidate cone, no recovery search independent of magnetic heading, and no spatial index. The
   41k-star PTSKCAT0 port is unused.
5. **No real night-sky evidence**, plus astronomy-truth defects that would poison both prediction and navigation:
   ephemeris lag, no precession, no proper-motion policy, no refraction policy.

**First recommended PR.** **PTS-01: fix the ephemeris epoch and add convention-explicit reference tests** that
replace the self-referential golden test. It is small, independent and P0-correct.

**PTS-02** (CI truth) follows. The current PR checks are not green: `smoke` and `Build catalog artifacts` fail in
Android SDK setup (§2).

**PTS-03** is the first PR on the autodetect path. It is the Pixel 9 experiment, and it establishes four independent
classes of camera truth (§28.2):
- **A** physical-frame identity;
- **B** projection-domain compatibility;
- **C** distortion state and pixel coordinate space;
- **D** camera↔device extrinsics.

A known physical ID alone does not make per-frame physical-camera geometry valid.

Revision 3 also:
- replaces the frame-ambiguous correction formula with an explicit frame model (§14.1);
- splits corrected pose into typed camera and device poses (§28.2);
- schedules detector hardening (PTS-12) on early real-sky data before the matcher (PTS-13) (§29).

---

## 2. Repository / branch / HEAD

| Item | Value | Evidence |
|---|---|---|
| Repository | `Inneren12/PointToSky` — PointToSky lives alone in this repository (no unrelated 2D/3D/AR-fabrication tracks) | `settings.gradle.kts:24-43` (`rootProject.name = "PointToSky"`) |
| Code snapshot audited | `main @ 09b16549cac78fc6e9f2649bf142d146ccfd11eb` (= `origin/main` at recon time) | `git rev-parse origin/main` |
| Recon branch / PR | `claude/pointtosky-master-audit-k4rws7`, PR #243 (this document). Not to be confused with the older README-only PR #242 | GitHub |
| Document revisions | r1 `7987ba8`; r2 `e846486`; r3 `5b733300adb0cfa57ffc457bdb50c72ba3b02a87` (base of this revision 4). Revision 4 and later commits are identified by `git log -- docs/pointtosky/MASTER_POINTTOSKY_RECON_2026-10-06.md`, so this table cannot go self-stale | `git log origin/main..origin/claude/pointtosky-master-audit-k4rws7` |
| CI on the PR #243 head (`e846486`, as reviewed) | **not green.** `Lint` PASS. `smoke` (Android PR Smoke) FAIL and `Build catalog artifacts` (Android CI) FAIL, both inside `android-actions/setup-android@v3`: the sdkmanager child process exits at "Computing updates…", about 15–19 s into the job, **before any Gradle/project step**. The failures are **not attributed** to this docs-only change: they occur in runner SDK setup, and nothing ties them to the diff. This reinforces PTS-02 | GitHub check runs 112153413673, 112153413173 (job logs) |
| Delta from the snapshot | this one Markdown file only; no code, test, build or asset changes | `git diff --stat origin/main...origin/claude/pointtosky-master-audit-k4rws7` |
| Commits on main | 192 (`git log --oneline \| wc -l`); history rebuilt about 2026-07-01 with 4 root commits | `docs/recon/RECON_main_2026-09-02.md` §2.1 |
| Merged since the previous recon | #237 (that recon), #238 (LICENSE/NOTICE/cruft), #239 (English README), #240 (test-suite/CI fixes), #241 (README reconcile) | `git log b6bdc7c..09b1654 --merges` |
| Open PR #236 | "Publish angleBetweenRad as the canonical ray→angle function". Makes `AnalysisBufferScale.angleBetweenRad` public, adds a unit-ray precondition and scale-safe `unprojectToCameraRay` normalisation; +627/−20 over 5 files. **Not merged; on main the helper is still `private`** (`core/astro-core/.../match/AnalysisBufferScale.kt:261-275`) | GitHub API; `git diff origin/main...origin/claude/camera-ray-angle-api-adb9hj --stat` |
| Open PR #242 | README-only. Changes the CAM-2c row from "confirmed on a real Pixel 9" to "reached by analysis … not confirmed on a device". **That correction is itself inaccurate**: `docs/validation/cam_2c_pixel9_evidence.md` §1 and §3 record two hand-collected real Pixel 9 runs that observed `UnsupportedLogicalMultiCameraMapping` | §11.6 below |
| Historical branches | 248 remote refs. Every one searched with `git log --remotes -S CONTROL_AF_MODE`, `-S FocusMeteringAction`, and `--grep ransac\|star match\|triangle\|autofocus`. **No autofocus code and no matcher code exists on any branch**; hits are SKY-3 contract commits only | recon commands, 2026-10-06 |

**Module split.** Phone (`:mobile`), watch (`:wear` + `:wear:sensors` + `:wear:benchmark`), shared pure-JVM astronomy
(`:core:astro-core`), Android libraries (`:core:astro`, `:core:catalog`, `:core:location`, `:core:time`,
`:core:logging`), a KMP data-layer codec (`:core:common`), and JVM tools (`:tools:*`). The Python catalog and skyglow
builders live in `res/`.

---

## 3. Product goals (as given) and how the repository maps to them

| Goal | Repository readiness |
|---|---|
| **G1: finish the core autodetect/navigation application** | Foundations PARTIAL (geometry, prediction, offline detector, matcher input). Identification core MISSING. Navigation works sensor-only on both devices, with several watch bugs (§17, §18) |
| **G2: redesign** | All UI is Compose (no XML layouts). Phone navigation is a hand-rolled `when` switch with no back stack. `ArScreen.kt` (1,676 LOC) mixes UI, protocol and astronomy. Debug screens are exposed in public builds on both devices (§20) |
| **G3: constellation graphics** | IAU boundaries (`const_v1.bin`) are drawn on the SkyMap. Implicit figure lines encoded in PTSKCAT4 star ids are drawn in phone AR. 9 asterisms in 3 constellations. Art is one placeholder rectangle. Nothing on the watch (§19) |

---

## 4. Module map

| Module | Type | Project deps | Runs on | Role in G1 | Status |
|---|---|---|---|---|---|
| `:core:astro-core` | pure JVM (`kotlin("jvm")`, JUnit5) | — | shared | All astronomy math plus the whole camera stack: `projection/camera/{*, prediction, detect, match, skylog}` | DONE (math), production-used only for transforms, legacy projection and debug overlay |
| `:core:astro` | Android lib | astro-core, logging, time | shared | `PtskCatalogLoader` (PTSKCAT4), limiting magnitude, light-pollution grid, sky brightness | DONE |
| `:core:catalog` | Android lib | astro, astro-core, logging | shared | `CatalogRepository` (over PTSKCAT4 `star.bin` + `const_v1.bin`), PTSKCAT0 reader + `PtskCat0StarCatalogQuery`, `RealStarVisibilityService` | DONE / PARTIAL |
| `:core:common` | KMP | — | shared | data-layer messages and codec, v1 | PARTIAL (no tests) |
| `:core:location` | Android lib | — | shared | `DefaultLocationOrchestrator`, fused repository, `LocationPrefs` | DONE / PARTIAL (one failing, one flaky test) |
| `:core:time` | Android lib | — | shared | `TimeSource`, `ZoneRepo`, a duplicate `JulianDate` (dead) | DONE |
| `:core:logging` | Android lib | — | shared | `LogBus`, crash logs, redaction | DONE (one known failing `RedactorTest` case) |
| `:mobile` | Android app (flavors `internal`/`public`) | all core | phone | AR, SkyMap, Search, Card, Settings; CAM/SKY debug experiments in `src/internalDebug` (54 files, about 13.4k LOC) | see §20 |
| `:wear` | Android app (flavors `internal`/`public`, identical code) | all core + `:wear:sensors` | watch | Aim, Identify, Tonight tile, complications | see §18 |
| `:wear:sensors` | Android lib | astro-core, logging | watch | rotation-vector / accel+mag orientation, low-pass filter, declination | DONE / PARTIAL |
| `:wear:benchmark` | `com.android.test` | targets `:wear` | watch | macrobenchmark (never run in CI) | UNKNOWN |
| `:tools:catalog-packer` | JVM CLI | — | host | HYG → PTSKCAT0 (`--format=ptskcat0`); legacy PTSKSTAR default | DONE / DEAD-LEGACY parts |
| `:tools:sky-session-loader` | JVM CLI | astro-core | host | parse → replay → detect → evaluate on captured sessions | DONE (synthetic only) |
| `:tools:ephem-cli` | JVM CLI | astro-core | host | ephemeris CLI; carries a dead duplicate `JulianDate` | DONE / DEAD parts |
| `res/*.py` | Python | — | host | PTSKCAT4 builders (`build_catalog_variant_b.py` = phone `star.bin`), `build_const_ptskcons.py`, `build_figures_from_d3.py`, `res/skyglow/` grid pipeline | DONE / LEGACY mix |

Detailed build and CI health: §22 and `docs/recon/RECON_main_2026-09-02.md` §6.

---

## 5. Current architecture

### 5.1 Dependency diagram (code, as built)

```text
                         ┌──────────────────────── :mobile (phone) ────────────────────────┐
                         │ MainActivity (hand-rolled nav)                                    │
                         │  ├─ ArScreen ── calculateOverlay ─▶ Projection.kt (56° legacy)   │◀── PRODUCTION overlay
                         │  │     │        RotationFrame (TYPE_ROTATION_VECTOR, no filter)   │
                         │  │     ├─ CameraPreview (CameraX 1.4.2: Preview + ImageAnalysis)  │
                         │  │     │      └─ CameraFrameAnalyzer → metadata only (no pixels)  │
                         │  │     ├─ CameraTimestampSynchronizer / SessionGeometryProvider   │ (run ungated,
                         │  │     │   / CameraSessionIntrinsicsCoordinator                   │  consumed only
                         │  │     └─ [internalDebug] PredictedStarOverlayReducer → projectStars   in internalDebug)
                         │  ├─ SkyMap / Search / Card ──▶ CatalogRepository                  │
                         │  └─ [internalDebug] SkySessionCapture, CAM-2c experiments          │
                         └───────────┬───────────────────────────────┬───────────────────────┘
                                     │ Data Layer (/aim/set_target,   │
                                     │ /app/open, /sensor/heading,    │
                                     │ /location/*, /tile/*)          │
                         ┌───────────▼────── :wear (watch) ───────────▼──────────────────────┐
                         │ Aim (DefaultAimController) · Identify · Tonight tile · complications│
                         │   └─ :wear:sensors OrientationRepository (RV → accel/mag)         │
                         └───────────┬───────────────────────────────────────────────────────┘
                                     │
   ┌─────────────────────────────────▼──────────────────────────────────────────────────────────┐
   │ :core:catalog  CatalogRepository ─▶ :core:astro PtskCatalogLoader (PTSKCAT4 star.bin)       │
   │               BinaryConstellationBoundaries (const_v1.bin) · PtskCat0Catalog (stars_real.bin)│
   │               PtskCat0StarCatalogQuery ─implements─▶ astro-core StarCatalogQuery (unused)    │
   │ :core:astro   LimitingMagnitude, LightPollutionGrid, SkyBrightness                          │
   ├─────────────────────────────────────────────────────────────────────────────────────────────┤
   │ :core:astro-core (pure JVM)                                                                 │
   │   time/ (JD, GMST/LST) · transform/ (RA/Dec↔Alt/Az) · aim/ · identify/ · ephem/ (Schlyter) │
   │   projection/Projection.kt (legacy 56°)                                                     │
   │   projection/camera/ (metadata, pairing, CropScale, intrinsics, SessionGeometry)            │
   │      prediction/ (CameraStarPredictor, PinholeProjectionModel)                              │
   │      detect/ (StarDetector, TiledBackground)        ◀── only tools/sky-session-loader + tests│
   │      match/ (StarMatcherInput, StarCatalogQuery, AnalysisBufferScale)  ◀── tests only       │
   │      skylog/ (session log model, codec, replay)                                             │
   └─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Subsystem table

| Subsystem | Path | Entry points | State owner | Phone/Watch/Shared | Status |
|---|---|---|---|---|---|
| Phone app | `mobile/src/main/java/dev/pointtosky/mobile/` | `MainActivity.kt:106,403-535` | `MutableStateFlow<MobileDestination>` | phone | PARTIAL |
| Wear app | `wear/src/main/java/dev/pointtosky/wear/` | `MainActivity.kt:318-533` (`SwipeDismissableNavHost`) | NavHost | watch | PARTIAL |
| Astronomy / domain | `core/astro-core/.../{time,transform,coord,aim,identify,ephem}` | `lstAt`, `raDecToAltAz`, `altAzToRaDec`, `aimDelta`, `IdentifySolver.findBest`, `SimpleEphemerisComputer.compute` | stateless | shared | DONE, ephemeris BROKEN (§8.7) |
| Catalog / data | `core/catalog`, `core/astro/.../catalog`, `tools/catalog-packer`, `res/*.py` | `CatalogRepository.create`, `PtskCatalogLoader.load`, `PtskCat0Catalog` | `CatalogRepositoryProvider` singletons | shared | DONE / PARTIAL |
| Sensor / orientation | `wear/sensors/.../orientation`, `mobile/.../ar/RotationFrame.kt`, `mobile/.../sensors/PhoneCompassBridge.kt` | `OrientationRepository.create`, `rememberRotationFrame` | repository flow / Compose `remember` | separate implementations per device | DONE / PARTIAL |
| Camera | `mobile/.../ar/CameraPreview.kt`, `mobile/.../ar/camera/*`, `core/astro-core/.../projection/camera/*` | `CameraPreview(...)`, `CameraFrameAnalyzer` | `ArScreen` composable | phone | PARTIAL |
| Detection | `core/astro-core/.../camera/detect/*` | `detectStars(frame, config)` | stateless | shared (JVM) | DONE, not production-used |
| Matching | `core/astro-core/.../camera/match/*` | `StarMatcherInput.of(...)` | — | shared | contract DONE, algorithm MISSING |
| Projection | `projection/Projection.kt` (legacy), `projection/camera/prediction/*` (CAM-2a) | `projectionParams`, `projectDeviceVector`, `projectStars` | stateless | shared | DONE (two projectors) |
| Navigation | `wear/.../aim/core/DefaultAimController.kt`, `core/astro-core/.../aim/*` | `AimController.setTarget/state` | controller `StateFlow` | watch (phone has none) | DONE (watch, sensor-only) with BROKEN target delivery |
| Constellations | `core/catalog/.../BinaryConstellationBoundaries.kt`, `mobile/.../ar/AstroOverlayModels.kt`, `mobile/.../skymap/*` | `CatalogRepository`, `ArViewModel.loadAstroCatalog` | `AstroCatalogState` | phone only | PARTIAL |
| Phone ↔ Wear | `core/common/.../datalayer/*`, `mobile/.../datalayer/*`, `wear/.../datalayer/*` | `MobileBridge.send`, `DlReceiverService` | — | both | PARTIAL |
| Persistence / settings | DataStore prefs: `LocationPrefs`, `MobileSettingsDataStore`, `AimIdentifySettingsDataStore`, `SensorsSettingsDataStore`, `TileSettingsDataStore`, `ComplicationPrefsStore` | — | DataStore | both | DONE |
| Diagnostics | `mobile/.../ar/CamDiagnosticHud.kt`, `mobile/.../ar/camera/*DiagnosticFormat.kt`, `mobile/src/internalDebug/.../CamDiagnosticSnapshotJson.kt`, `core/logging` | HUD and JSON export | — | phone (camera); both (logs) | PARTIAL |
| Tests / testdata | see §22 | — | — | — | PARTIAL; **no real-sky data** |

---

## 6. Star catalog

### 6.1 Shipped assets (headers parsed from the committed bytes with a Python `struct` script)

| Asset | Size (B) | Format | Records | Mag range | Record | Notes |
|---|---|---|---|---|---|---|
| `mobile/src/main/assets/catalog/stars_real.bin` | 705,889 | **PTSKCAT0 v1** (HYG v4.2) | **41,487** | −1.44 … **8.00** | 16 B | Epoch 2000. Sorted by magnitude. Names table: 3,159 entries (42,069 B). 93 records have HIP=0, no duplicate HIPs |
| `wear/src/main/assets/catalog/stars_real.bin` | 183,698 | PTSKCAT0 v1 | **8,920** | −1.44 … **6.50** | 16 B | 3,074 names. **No wear code opens it (DEAD asset)** |
| `mobile/src/main/assets/catalog/star.bin` | 297,540 | **PTSKCAT4 v5** (BSC5 + curated figures) | 9,241 (8,404 points + 837 `LINE_NODE`/`AUX_ONLY` skeleton nodes) | −1.46 … 6.50 | 28 B (with B−V) | Sections STR0, CST0 (88), ASTR (9), APLY (9), ASTN, ART0 (1). Produced by `res/build_catalog_variant_b.py` |
| `wear/src/main/assets/catalog/star.bin` | 33,152 | PTSKCAT4 v4 | **904** | −1.46 … **4.50** | 24 B | No figures, asterisms or art. Builder script and magnitude limit UNKNOWN |
| `{mobile,wear}/.../catalog/const_v1.bin` (identical) | 17,898 | PTSKCONS v1 | 88 constellations, 89 polygons, 1,659 vertices | — | — | IAU boundaries via d3-celestial. CRC32 verified |

The previous recon's numbers (41,487 / mag ≤ 8.00 phone; 8,920 / mag ≤ 6.50 watch) are **still current**.
**Important:** the *production* star source on both devices is **PTSKCAT4 `star.bin`**. PTSKCAT0 is not.
- Phone: 9,241 records, mag ≤ 6.5. Watch: 904 records, mag ≤ 4.5.
- `CatalogRepository.loadStars` → `PtskCatalogLoader(assetPath = "catalog/star.bin")`
  (`core/catalog/.../runtime/CatalogRepository.kt:157-171`, `core/astro/.../catalog/PtskCatalogLoader.kt:105`).
- `ArViewModel` also uses `PtskCatalogLoader` (`mobile/.../ar/ArViewModel.kt:375`).

### 6.2 Record fields and conventions

- **PTSKCAT0** (`docs/star_catalog_ptskcat0_format.md:17-40`; reader `core/catalog/src/main/java/.../binary/PtskCat0Catalog.kt:18-35,112-177`):
  - RA and Dec are float32 **degrees**, J2000.
  - Magnitude is int16 centi-mag; B−V is int16 milli, with −32768 meaning unknown; HIP is uint32, 0 meaning none.
  - A sparse names table is keyed by HIP (>0) or by −(index+1).
  - No HR, HD, constellation, spectral type or **proper-motion** fields.
  - Matching identity is the **record index**, stable for one binary only (`PtskCat0StarCatalogQuery.kt:30-34`).
- **PTSKCAT4** (`PtskCatalogLoader.kt:308-326`, `Models.kt:442-458`):
  - id = `cc*10000 + pp*100 + ss`; RA, Dec and magnitude are float32 degrees.
  - Carries a constellation index (u16) and flags `BRIGHT=0x01`, `LINE_NODE=0x02`, `AUX_ONLY=0x08`.
  - The name is a string-pool offset; B−V is present from v5.
  - **No HIP id**, so PTSKCAT4 and PTSKCAT0 records cannot be joined by identity.
- **Proper motion:** neither runtime format carries it, and it is never applied (MISSING).
  - The HYG source has proper-motion columns (`pmra`, `pmdec`, per the HYG catalogue documentation). The packer
    **drops them deliberately**: `HygRealCatalogParser.read` keeps only `id`, `ra`, `dec`, `mag`, `ci`, `hip`,
    `proper` and `bf` (`tools/catalog-packer/.../ptskcat0/HygRealCatalogParser.kt:8-21,38-66`).
  - Whether HYG `pmra` already includes the cos δ factor (μα\*) or is μα must be **verified against the HYG source
    documentation before any implementation**. The recon has not established it. The source CSV is not committed.
  - For optical navigation this has to be an explicit design decision (§8.3, G-25).
- **Precession:** never applied (MISSING, §8.3).
- **Epoch handling:** the PTSKCAT0 header has `epoch = 2000` (`tools/catalog-packer/.../ptskcat0/PtskCat0Writer.kt:34`). Nothing reads it to transform coordinates.

### 6.3 Generators

| Tool | Output | Status |
|---|---|---|
| `tools/catalog-packer --format=ptskcat0 --source=hyg` (`PackerMain.kt:20-23,53-60`; `HygRealCatalogParser.kt:28-57`; `PtskCat0Writer.kt:38-41`) | `stars_real.bin`: magnitude cut, sorted by (mag, hip, id), hours→degrees with a self-check, Sun skipped | DONE. The HYG v4.2 CSV is not committed (`.gitignore`); attribution is in `NOTICE.md` |
| `catalog-packer` default `--format=ptskstar`; `core/catalog/.../BinaryCatalogHeader.kt` | PTSKSTAR (no asset uses it) | DEAD / LEGACY |
| `tools/catalog-packer/.../constellation/ConstellationCommand.kt` | unreachable from `main()`, `@file:Suppress("unused")` | DEAD / LEGACY |
| `res/build_catalog_variant_b.py` | phone `star.bin` v5 (BSC bulk + curated skeleton/asterisms/art) | DONE |
| `res/build_bsc_ptskcat4.py`, `res/build_bsc_ptskcat4_a2.py`, `res/build_ptskcat4.py` | v4 variants; which one built the wear `star.bin` is UNKNOWN | LEGACY / UNKNOWN |
| `res/build_const_ptskcons.py` | `const_v1.bin` (header comment `:7-9` describes an AABB reader that has since been replaced) | DONE, stale comment |
| `res/build_figures_from_d3.py` | `res/<abbr>.json` skeletons (88 files, 690 edges) | DONE |

### 6.4 Loading, indexing, memory

| Loader | Production callers | Index | Memory (estimate) | Status |
|---|---|---|---|---|
| `PtskCatalogLoader` (PTSKCAT4) | `CatalogRepository` (both devices), `ArViewModel` factory (phone), wear `RealTonightProvider` (re-parsed per call unless injected, `RealTonightProvider.kt:54,155`), internalDebug `SkySessionCaptureScreen.kt:311` | maps by id, constellation and asterism. **No spatial index**. The cache is per instance, so the phone parses at least twice | about 1–1.5 MB per copy on the phone (not measured) | DONE |
| `AstroStarCatalogAdapter` | via `CatalogRepository` | linear scan + sort by separation (`core/catalog/.../star/AstroStarCatalogAdapter.kt:10-49`) | — | DONE |
| `PtskCat0Catalog` | **only** `RealStarVisibilityDebugProvider` (startup count probe, phone, all variants; `mobile/.../catalog/RealStarVisibilityDebugProvider.kt:61`, started from `MainActivity.kt:114`) | struct-of-arrays. `countBrighterOrEqual` is a binary search; **`nearby` is a linear scan of the magnitude prefix with one `acos` per star** (`PtskCat0Catalog.kt:65-92`) | about 664 KB arrays + a 706 KB transient byte array | code DONE, production: debug probe only |
| `PtskCat0StarCatalogQuery` (implements astro-core `StarCatalogQuery`) | **none** (tests only) | delegates to `nearby`; exact Double magnitude cut (`:76-82`) | — | SCAFFOLD |
| `RealStarVisibilityService` | debug probe only. **Re-parses the asset on every `select`** (`core/catalog/.../visibility/RealStarVisibilityService.kt:76`) | — | — | PARTIAL |
| `BinaryConstellationBoundaries` | `CatalogRepository.loadBoundaries` (both devices) | point-in-polygon, linear over 89 polygons; AABBs parsed but unused (`:90-96`) | small | DONE |
| `ConstellationOutlineLoader` (phone SkyMap) | `SkyMapViewModel.kt:115` | **second, duplicate `const_v1` parser** | small | DONE (duplicate) |

### 6.5 Catalog tests

All tests build synthetic bytes. **No test parses a shipped asset**: there is no golden for counts, CRC or the Sirius
position.
- `PtskCat0CatalogTest` (10), `PtskCat0StarCatalogQueryTest` (14), `AssetRealStarCatalogProviderTest` (8),
  `BinaryConstellationBoundariesLoadTest` (11), `PtskCatalogLoaderTest` (5), `PtskCat0PackingTest` (12),
  `AstroStarCatalogAdapterTest` (3).
- **`CatalogRepository.runSelfTest` is BROKEN.** It expects HIP ids (Sirius 32349, Arcturus 69673, Rigel 24436),
  but searches PTSKCAT4 ids, where Sirius = 130000 (`CatalogRepository.kt:64-91`). It is shown on the debug screens
  of both devices and is untested.

---

## 7. Navigation-star set

**Status: MISSING.**

I searched case-insensitively across all code, tests, docs, Python and JSON for "navigation star", navStar, nav star,
NAV_, navigational, "bright star list", curated, "named stars", anchor star, reference star, guide star and
alignment star. No navigation-star concept exists. "curated" refers only to constellation figures; "anchor" only to
`ArtOverlay.anchorStarA/B`.

| Question | Answer (repository truth) |
|---|---|
| Does a curated navigation-star set exist? | **No.** |
| Where / how many / how selected / stable IDs? | Not applicable. |
| Independent of magnitude filtering? | Not applicable. Every existing star subset is a magnitude cut. |
| Used by runtime matching or UI? | Not applicable. No matcher exists. |
| Can the system compute the subset visible at time/location? | **Generic pieces only.** `raDecToAltAz` exists (`core/astro-core/.../transform/EquatorialHorizontalTransform.kt:48-97`); `limitingMagnitudeAt` (`core/astro/.../LimitingMagnitudeAt.kt`) and the AR `effectiveMagLimit` (`ArViewModel.kt:184-204`) give sky-wide magnitude cuts. No function returns "stars above the horizon now" as a candidate list. |
| Can it compute the subset plausibly inside the current camera FOV? | **Only after projecting every candidate.** CAM-2a `projectStars` classifies each input star `VISIBLE_IN_VIEWPORT` / `INSIDE_IMAGE_OUTSIDE_VIEWPORT` / `OUTSIDE_IMAGE` / `BEHIND_CAMERA` (`CameraStarPredictor.kt:99-115`). There is no cone pre-query. `AnalysisBufferScale.enclosingConeRadiusRad` exists (`match/AnalysisBufferScale.kt:186-193`) but has no production caller. |
| Connected to the matcher? | **No** (no matcher). |

Closest existing material:
1. **PTSKCAT0 magnitude prefix.** "Brightest N" is free because the records are sorted (`PtskCat0Catalog.kt:15-16`).
   Cumulative counts: ≤2.0: 50; ≤3.0: 179; ≤4.0: 523; ≤4.5: 925; ≤5.0: 1,637; ≤6.0: 5,070.
2. **PTSKCAT0 names table**: 3,159 / 3,074 entries, mixing proper names and Bayer/Flamsteed.
3. **PTSKCAT4 `BRIGHT` flag**: 106 records on the phone, 48 on the watch. Nothing in production reads it.
4. **Hard-coded targets**:
   - `polarisJ2000` (`core/astro-core/.../coord/KnownPoints.kt:4`);
   - the watch Aim picker SUN/MOON/JUPITER/SATURN/POLARIS (`wear/.../aim/ui/AimScreen.kt:231-240`);
   - `DemoAimTargets` (`mobile/.../datalayer/DemoAimTargets.kt`);
   - the Tonight fallback "VEGA" (`RealTonightProvider.kt:145-151,206`).
5. **CAM-2b candidate list**: the brightest **200** PTSKCAT4 stars with mag ≤ `effectiveMagLimit` over the **whole
   sphere** (`PredictedStarCatalogAdapter.kt:36-65`; the 200th star is about mag 3.13).

**Implication.** A navigation-star set must be created.
- It is a data file plus generator, keyed by an identity that survives re-packing (HIP is the obvious candidate,
  since PTSKCAT0 record indices do not survive), and resolved to the runtime record at load.
- It needs explicit selection rules: brightness, isolation from near neighbours (to avoid ambiguous pairs), sky
  coverage, colour if useful, and a **proper-motion rule** (§8.3 item 10).
- The visible-subset and FOV-subset computations sit on top of it, through a staged fail-open candidate policy so a
  wrong magnetic heading cannot exclude the true stars (§9.1, §28.2, PTS-06/PTS-10).

---

## 8. Astronomy coordinate pipeline

### 8.1 Chain actually implemented

```text
catalog J2000 RA/Dec (deg)                      — no proper motion, no precession/nutation/aberration
 → JD from Instant (UTC used as UT1)            core/astro-core/.../time/JulianDate.kt:11-23
 → GMST (Meeus 12.4, mean), LST = GMST + lonE   core/astro-core/.../time/SiderealTime.kt:24-57
 → hour angle τ = wrap±180(LST − RA)            transform/EquatorialHorizontalTransform.kt:54
 → alt/az (az from North through East, [0,360)) ...:69-97 (refraction optional, Saemundsson)
 → ENU world vector (+X E, +Y N, +Z Up)         projection/Projection.kt:76-85 (Float, legacy);
                                                camera/prediction/LocalSkyDirection.kt:116-126 (Double, CAM-2a)
 → [CAM-2a] true ENU → magnetic ENU (−decl)     prediction/TrueNorthToMagneticNorthTransform.kt:73-87
 → device: v_dev = Rᵀ v_world                   prediction/RotationMath.kt:38-51 ; legacy ArScreen.kt:1425
 → optical: (x, −y, −z), back camera on −Z      prediction/DeviceToOpticalCameraTransform.kt:65-68
 → buffer optical by ImageProxy rotationDegrees prediction/DisplayAlignedOpticalToBufferOpticalTransform.kt:88-109
 → pinhole u = fx·x/z + cx, v = fy·y/z + cy     prediction/PinholeProjectionModel.kt:110-122 (no distortion)
 → display px: crop → rotate CW → FILL_CENTER   camera/CropScaleTransform.kt:326-401
```

The **production legacy overlay** skips the camera stages. It goes ENU → `transpose(trueNorthFrame.R)` →
`projectDeviceVector(projectionParams(viewport))`: VFOV 56° on the viewport height, HFOV from aspect, principal
point at the viewport centre, rejection when `z ≥ −0.01` or NDC radius > 1.2 (`Projection.kt:40-43,130-169`;
`ArScreen.kt:1407-1456`).

### 8.2 Conventions

| Item | Convention | Evidence |
|---|---|---|
| Time source | `Instant.now()` via `SystemTimeSource`; tick 1 s (AR, SkyMap, Aim), 200 ms (watch Identify) | `core/time/.../TimeSource.kt:22-59` |
| Time scale | UTC treated as UT1; no DUT1/TT. The `gmstDeg` KDoc says "TT" but is fed UTC JD | `SiderealTime.kt:21,53` |
| Julian date | `floorDiv(epochSec,86400) + frac + 2440587.5`. **Three byte-identical copies**; only astro-core's is used | `core/astro-core/.../time/JulianDate.kt`; dead copies `core/time/.../JulianDate.kt`, `tools/ephem-cli/.../core/time/JulianDate.kt` |
| Sidereal | **mean** GMST; the `Sidereal` KDoc's "apparent" is a mislabel | `coord/Coordinates.kt:37` |
| Longitude | east-positive | `SiderealTime.kt:43` |
| Azimuth | from North through East | `EquatorialHorizontalTransform.kt:94` |
| RA/Dec units | degrees throughout the Kotlin domain; radians in the CAM-2a/`StarCatalogQuery` layer, converted at the port | `Coordinates.kt:9-12`; `StarCatalogQuery.kt` KDoc "Units" |
| Refraction | default `true`, but explicitly `false` almost everywhere (AR, CAM-2a, Card, all watch call sites). **SkyMap stars use `true` while SkyMap outlines use `false`** | `SkyMapViewModel.kt:167,184` |
| Rotation matrix | Android device→world, row-major `FloatArray(9)`, world = ENU **magnetic** | `mobile/.../ar/RotationFrame.kt:62-75` |
| Phone forward | −Z (back camera) | `RotationFrame.kt:70-75` |
| Watch forward | +Y (forearm) | `wear/sensors/.../RotationVectorOrientationRepository.kt:302-305` |
| Display remap | phone: `remapCoordinateSystem` per display rotation (activity portrait-locked, `mobile/src/main/AndroidManifest.xml:26`); watch: a **user setting**, default ROT_0 | `mobile/.../ar/DisplayRemap.kt:7-30`; `wear/.../settings/SensorsSettingsDataStore.kt:23-27` |
| True north | phone: world Rz(decl) left-multiplied (`RotationFrame.kt:125-143`); watch: `az + decl` (`core/astro-core/.../aim/Pointing.kt:121-122`). Both use WMM `GeomagneticField` | — |
| Quaternion | used only in the sky log: scalar-last `(x,y,z,w)`, w ≥ 0, derived from R (Shepperd) | `skylog/SkySessionLog.kt:246-295` |
| Camera | back only (`DEFAULT_BACK_CAMERA`); front camera unsupported; camera–IMU extrinsics assumed identity | `CameraPreview.kt:273` |
| Sensor orientation | not read separately; `ImageProxy.imageInfo.rotationDegrees` is used | `CameraFrameMetadataSource.kt:153` |

### 8.3 Convention mismatches and defects

1. **BROKEN: ephemeris epoch, off by one day.**
   - `SimpleEphemerisComputer.kt:36` computes `d = jd − JD_AT_2000_01_01_00UT` with `2451544.5` (`:369`). The
     elements are Schlyter's (e.g. Moon `N = 125.1228 − 0.0529538083·d`, `:73`), whose `d = 0` is
     **2000 Jan 0.0 UT = JD 2451543.5**.
   - Recon cross-check for 2025-01-01T00Z: code Sun RA 280.663°; Schlyter-epoch Sun 281.767°; Meeus ch.25 Sun
     281.768°. Moon ecliptic longitude: about 13° wrong (sub-agent computation).
   - This affects every Sun/Moon/Jupiter/Saturn position: phone Card, SkyMap, AR, Search; watch Aim, Identify,
     Tonight tile and complications; and the Moon/twilight penalties in `limitingMagnitudeAt`.
2. **Self-referential golden test.** `SimpleEphemerisComputerGoldenTest.kt:22-35` regenerates
   `core/astro/src/test/resources/ephem_golden_v1.json` from the computer under test (`golden.recompute(computer)`)
   with a 1.5° tolerance. It pins the defect instead of detecting it.
3. **MISSING: J2000 → of-date.** No precession or nutation matrix exists anywhere. Stars are J2000 while LST is of
   date, a systematic error of about 0.36° in 2026 (estimate). The planets' output frame is "of-date-ish" mean
   obliquity (`SimpleEphemerisComputer.kt:291-295`), so stars and planets are in **different frames**.
4. **No topocentric Moon parallax.** The Moon is geocentric (error up to about 1°).
5. **Two "legacy 56°" fallbacks put the 56° on different axes**:
   - the overlay applies 56° to the display viewport height (`Projection.kt:130-137`);
   - the CAM-2b fallback applies 56° to the *native buffer* height (`LegacyFallbackCameraIntrinsics.kt:36-45`),
     which is the display-horizontal axis in portrait with rotation 90.
6. **Two Bortle → NELM tables disagree** by 0.2 mag (0.4 at class 9):
   - `LimitingMagnitudeModel.fromBortle` gives 7.6…3.6 (`core/catalog/.../visibility/LimitingMagnitudeModel.kt:25-35`);
   - `Bortle.darkNelm` gives 7.8…4.0 (`core/astro/.../LimitingMagnitude.kt:8-18`).
7. **Duplicate alt/az**: the watch tile's `AstroMath.raDecToAltAz` (`wear/.../tile/tonight/AstroMath.kt:21-41`) has
   no pole guard.
8. **Unresolved location renders at (0,0)** in the legacy AR overlay (`ArViewModel.kt:86-89,322`). CAM-2b refuses in
   that case.
9. **Dead math**: `math/Vector3.kt:71-80` uses a different azimuth convention (math, not compass), with no callers;
   `Vec3`/`Mat3` in `java/.../math/VectorMath.kt` are also unused.
10. **MISSING: proper-motion policy.** The facts:
    - PTSKCAT0 positions are catalog-epoch (2000) positions, and the packer drops HYG `pmra`/`pmdec` (§6.2).
    - Camera observations are made in 2026. Most stars move negligibly over 26 years, but some bright stars do not.
      The per-star displacement has not been quantified in the repository.
    - Nothing documents an accuracy budget against which "negligible" could be judged.

    Requirements for optical navigation:
    - Navigation-reference positions must be **propagated from catalog epoch to observation epoch** before matching.
    - Proper motion and precession must be applied in **one explicitly documented coordinate pipeline**.
    - Epoch-2000 positions must never be silently mixed with 2026 camera observations.
    - The HYG `pmra` convention must be verified first (§6.2).

    An acceptable smaller v1: **exclude** navigation stars whose accumulated proper-motion displacement since the
    catalog epoch exceeds a justified threshold. That threshold is **TBD BY ACCURACY BUDGET / DEVICE TEST**, and the
    exclusion must be explicit and tested.
11. **MISSING: atmospheric-refraction policy for optical matching.**
    - Observed star positions are displaced upward by atmospheric refraction. The displacement grows quickly toward
      the horizon.
    - The only refraction model is the optional Saemundsson term in `raDecToAltAz`
      (`EquatorialHorizontalTransform.kt:81-92`). CAM-2a builds geometric directions with refraction off
      (`prediction/LocalSkyDirection.kt:96`), and so do almost all other callers (§8.2).
    - A geometric catalog prediction must not let a matcher or attitude solver silently absorb refraction as
      camera, intrinsics or attitude error.

    A v1 policy must be chosen explicitly between:
    - **(A)** an apparent-altitude correction in the candidate/prediction path, with documented assumptions
      (pressure, temperature, model validity range); or
    - **(B)** excluding reference stars below a configurable minimum altitude until a refraction model is adopted.

    The final altitude threshold is **TBD BY ACCURACY BUDGET / DEVICE TEST**. Either way, tests and device validation
    must report **residual versus altitude** (§23, §30).

**Ephemeris reference-test conventions (for PTS-01).**
- An external-reference test must state the reference frame (mean equator/equinox of date vs J2000/ICRF), the
  apparent-vs-geometric setting (aberration, light time, nutation), the observer (geocentric vs topocentric) and the
  time scale (UT vs TT).
- It must compare like with like. `SimpleEphemerisComputer` is a low-precision, geocentric, mean-obliquity model with
  no aberration or light-time correction (§8.3 items 3-4). It must not be compared blindly with a JPL Horizons
  apparent topocentric value, which would create false failures unrelated to the epoch.
- Suitable references: worked examples computed with the same model convention (for example, a Schlyter-epoch
  reimplementation and Meeus' low-precision examples with their stated frames). If Horizons is used, request
  geocentric, astrometric or mean-of-date output and set tolerances from the documented model accuracy.
- The test's job is to prove the epoch correction. Model-accuracy limits must not turn it into a false failure.

### 8.4 Tests pinning conventions (all JVM)

| Test | What it pins |
|---|---|
| `core/astro/.../time/SiderealTimeTest` | Meeus Ex. 12.b GMST (±5e-4°); sidereal rate |
| `core/time/.../JulianDateTest` | JD references (for the dead copy) |
| `core/astro/.../transform/EquatorialHorizontalTransformTest` | round trip; zenith; horizon. **Azimuth direction is not asserted here** |
| `core/astro-core/.../prediction/EquatorialToLocalSkyTest` | ENU cardinals, τ=±90° gives E/W, east-positive longitude, RA seam, poles |
| `ProjectionTest` (15) | legacy projector, `VERTICAL_FOV_DEG == 56` |
| `mobile/.../ar/ProjectionOrientationTest` | **mirror** of the remap (not the framework call), det = +1 |
| `RotationFrameTrueNorthEquivalenceTest`, `TrueNorthToMagneticNorthTransformTest` | declination sign agreement between legacy and CAM-2a |
| `CameraStarProjectionTest` (35), `DeviceToOpticalCameraTransformTest`, `DisplayAlignedOpticalToBufferOpticalTransformTest`, `PinholeProjectionModel(Unproject)Test`, `CropScaleTransformTest` (29), `FillCenterCropScaleTest` | the full CAM-2a chain, including double-rotation regressions |
| `wear/sensors/.../RotationVectorOrientationRepositoryTest` | +Y forward, cardinals, offset sign |
| `SimpleEphemerisComputerGoldenTest` | **self-snapshot only** (see 8.3.2) |

Gaps: no test asserts any ephemeris against an external reference. No test covers precession, proper motion, or
refraction in the optical-prediction path. No instrumentation
test covers the real `SensorManager.remapCoordinateSystem`. The KDoc of `LocalSkyDirection.kt:21-25` cites a
"cardinal agreement" test that does not exist.

---

## 9. Visible-star prediction

| Stage | Implemented? | Class / function | Numbers | Margin | Real FOV? | Crop/aspect | Rotation | Runtime cost | Production? |
|---|---|---|---|---|---|---|---|---|---|
| All relevant stars | yes | PTSKCAT0 41,487 (phone) or PTSKCAT4 9,241 / 904 | mag ≤ 8.0 / 6.5 / 4.5 | — | — | — | — | parse once | PTSKCAT4 yes; PTSKCAT0 debug probe only |
| Above horizon | **legacy only** | `ArScreen.kt:1442` (`altDeg < 0 → null`) | 0° | none | — | — | — | per star | yes (legacy). **CAM-2a has no horizon gate**: a below-horizon star in front of a down-tilted camera classifies VISIBLE (`CameraStarPredictor.kt:69-134`) |
| Magnitude / limiting magnitude | yes, two parallel models | (a) `limitingMagnitudeAt` + `resolveEffectiveBortle` (`ArViewModel.kt:184-204`, `core/astro/.../LimitingMagnitude.kt:31-59`); (b) VF-1 `LimitingMagnitudeModel` + `RealStarVisibilityFilter` | user cap default 6.0; Bortle default CLASS_4; Moon/twilight penalties (from the broken ephemeris) | — | — | — | — | (a) per tick | (a) yes; (b) debug probe |
| Light pollution | yes, regional | `LightPollutionProvider`, `bortle.bin` PTSKLP01 v3 | covers only lat 50–60°N × lon 120–110°W | — | — | — | — | once | yes |
| Navigation stars | **no** | — | — | — | — | — | — | — | — |
| Consistent with rough orientation | **no** | — | — | — | — | — | — | — | — |
| Inside / near camera FOV | **after full projection** | CAM-2a `projectStars` classification (`CameraStarPredictor.kt:99-115`); legacy NDC ≤ 1.2 | 1e-6 px tolerance (CAM-2a); 1.2 NDC (legacy) | **not configurable** | CAM-2a: yes when intrinsics resolve (Pixel 9: no); legacy: 56° guess | CAM-2a: yes (FILL_CENTER, crop); legacy: viewport aspect only | CAM-2a: yes; legacy: display remap | O(candidates) trig per frame | CAM-2a: internalDebug only |
| Matcher candidate list | **no producer** | `StarCatalogQuery` / `PtskCat0StarCatalogQuery`, `StarMatcherInput` | cone sized from `enclosingConeRadiusRad` (documented) | — | — | — | — | linear scan of up to 41k stars per query | **no** |

**Unnecessarily broad candidate sets** (explicitly asked):
1. **CAM-2b input**: the brightest 200 PTSKCAT4 stars over the **whole sphere**, so about half are below the horizon
   and most are outside the FOV (`PredictedStarCatalogAdapter.kt:36-65`; `ArScreen.kt:503-504`). The cut is also too
   bright for matching (about mag ≤ 3.1). The same set feeds SKY-1 capture (`SkySessionCaptureSession.kt:364-372`).
2. **Legacy overlay**: on every rotation-sensor event (`SENSOR_DELAY_GAME`), `remember(state, rotationFrame, …)`
   (`ArScreen.kt:582`) re-filters all 9,241 records. It then runs `raDecToAltAz` on up to 8,404 stars and sorts them
   **on the main thread** (`:1462-1483`). Alt/az depends only on time and location (1 Hz), so it is recomputed far
   more often than needed.
3. **`PtskCat0Catalog.nearby`**: a linear scan of the whole magnitude prefix (41k stars at mag 8.0) per query, with no
   spatial index (`PtskCat0Catalog.kt:83-92`). Not called per frame today, but it would be the matcher's catalog path.
4. **`RealStarVisibilityService.select`**: re-reads and re-parses 706 KB per call (`RealStarVisibilityService.kt:76`).

Docs drift: `docs/camera_star_prediction_contract.md:945` says CAM-2b never uses "the full ~42k-star catalog". True,
but its source is the 9,241-record PTSKCAT4, not PTSKCAT0.

### 9.1 Required candidate policy: the sensor prior must fail open

Nothing below exists yet. It is the requirement the future `VisibleCandidatePredictor` must satisfy.

The rough IMU/magnetometer attitude is a **hint, not a hard gate**. Optical identification exists partly *to recover
from* a bad magnetic heading, so the true solution must never be excluded solely because the magnetometer prior is
wrong.

The same principle already appears in the matcher contract: "a correct matcher must work with [the prior] empty"
(`StarMatcherInput.kt:80-90`). Candidate generation needs a staged policy:

| Stage / state | Prior used | Candidate region | Purpose |
|---|---|---|---|
| Trusted sensor prior (good accuracy, no interference flag, consistent with recent optical solution) | sensor attitude + small uncertainty | narrow cone: `enclosingConeRadiusRad` + attitude-uncertainty margin | normal acquisition |
| Degraded / uncertain prior (low accuracy, stale, large variance) | sensor attitude + large uncertainty | widened cone | acquisition under doubt |
| Magnetic interference / LOST / repeated failure | **no heading assumption** (pitch/roll from gravity may still bound altitude) | nav-star-wide or hemisphere-scale above-horizon set — a recovery / lost-in-space search | recover from a wrong heading |
| After optical lock (tracking) | timestamped `priorProjections` from the last verified solution, propagated by the rotation delta | gated neighbourhoods of predicted positions (fast path) | cheap per-frame tracking |
| Periodic re-verification while locked | none, or a deliberately widened prior | wider hypothesis path at a cadence TBD | stops a poisoned prior from becoming permanent |

Candidate-set size targets must be stated **per state**. They cannot be one number:
- **Normal locked operation**: small (gated neighbourhoods of predicted stars).
- **Initial acquisition**: the cone-limited nav-star subset.
- **Recovery / lost-in-space**: the above-horizon nav-star set, potentially O(10²) stars.

All of these numbers are **TBD BY DEVICE TEST**. The recon does not promise O(10¹) candidates for every state. The
recovery path therefore needs a hypothesis method whose cost is acceptable on the full above-horizon nav-star set
(for example pattern invariants over a precomputed pair/triangle index), not only the cone-limited fast path.

---

## 10. Camera pipeline

### 10.1 Production bind (`mobile/src/main/java/dev/pointtosky/mobile/ar/CameraPreview.kt`)

| Item | Repository truth | Evidence |
|---|---|---|
| API / version | CameraX **1.4.2** (camera-core, camera2, lifecycle, view) | `gradle/libs.versions.toml:31,75-78` |
| Camera selection | `cameraSelectorOverride ?: CameraSelector.DEFAULT_BACK_CAMERA`. Production passes no override, so it uses the **logical** default back camera. No zoom call (zoom pinned 1.0× only on the internalDebug override path) | `CameraPreview.kt:60,273,305-331`; `ArScreen.kt:540-550` |
| Preview | `Preview.Builder().build()`: no resolution selector, no target rotation. `PreviewView.scaleType = FILL_CENTER` | `:191,224-227` |
| ImageAnalysis | `STRATEGY_KEEP_ONLY_LATEST`; no `setOutputImageFormat` (YUV_420_888 default); no target rotation; **no resolution selector in production**, so CameraX picks its own default. **640×480 was device-observed on a Pixel 9 under CameraX 1.3.4** (`cam_2c_pixel9_evidence.md` §3). The resolution and stream configuration under the **current 1.4.2** production bind are **UNVERIFIED ON DEVICE**. The `CameraPreview.kt:160` KDoc says only "typically 640×480" | `:238-271`; `docs/validation/cam_2c_pixel9_evidence.md:193-210` |
| Executor | single-thread executor, shut down on dispose or bind failure | `:215,345-346,410-424` |
| Bind | Preview + ImageAnalysis in one `bindToLifecycle`. On `IllegalArgumentException`, falls back to **Preview-only** (then no metadata, geometry stays `MissingFrame`) | `:278-283,371-407` |
| ViewPort / UseCaseGroup | **none** | repository grep; `docs/camera_coordinate_calibration_contract.md:479` |
| Analyzer | `CameraFrameAnalyzer`: reads timestamp, size, rotation, crop and `sensorToBufferTransformMatrix`. **Never reads `imageProxy.planes`** | `mobile/.../ar/camera/CameraFrameAnalyzer.kt:8-41`; `CameraFrameMetadataSource.kt:125-204` |
| Frame timestamps | `imageInfo.timestamp` (start of exposure); paired to the nearest rotation sample, no interpolation, ≤ 50 ms; clock-mismatch heuristic 5 s; history 120 | `core/astro-core/.../FrameRotationPairing.kt:72-127`; `TimestampSyncConfig.kt:21-54` |
| Timestamp source | production never reads `SENSOR_INFO_TIMESTAMP_SOURCE` (only SKY-1 does) | `mobile/src/internalDebug/.../SkyCaptureClock.kt:74-94` |
| Intrinsics | resolved **once per session**: analysis-buffer K′ (needs a non-logical camera + `AXIS_ALIGNED_0` matrix), then active-array K mapped through the matrix, then CAM-1b `PhysicalSensor` FOV, then legacy 56° | `mobile/.../ar/camera/AnalysisBufferIntrinsicsResolver.kt:314-589`; `SessionScopedCameraIntrinsicsResolver.kt:92-108`; `CameraIntrinsicsResolver.kt:83-131`; `core/astro-core/.../LegacyFallbackCameraIntrinsics.kt:32-63` |
| Physical camera IDs (static) | declared IDs read for diagnostics (`CameraManager.getCameraCharacteristics(id).physicalCameraIds`). Production never binds one | `CameraSessionIntrinsicsCoordinator`/diagnostic JSON `cam2c.physicalCameraIds` |
| **Active physical camera per frame** | **UNINVESTIGATED / UNVERIFIED ON PIXEL 9.** Details below this table | `CameraPreview.kt:51-59`; `SkyCaptureExposure.kt:252-267,455-466`; `SkySessionCameraPreview.kt:106-122` |
| Per-frame Camera2 capture results | production: **none read**. SKY-1 (internalDebug) reads `TotalCaptureResult` per frame through `Camera2Interop.Extender.setSessionCaptureCallback` and joins it to the analysis frame by `SENSOR_TIMESTAMP` (`SkyExposureJoin`, `DEFAULT_CAPACITY = 6`, `DEFAULT_MAX_WAIT_NANOS = 4 s`, `SkyExposureJoin.kt:174,181`). Fields read: exposure, ISO, frame duration, AE mode, AWB mode, sensor timestamp only | same |
| Distortion coefficients | `LENS_DISTORTION` read into the characteristics snapshot and captured verbatim in the CAM-2c frame-content evidence, **never applied** (the `SkySessionLog.kt:696-699` KDoc saying it is "not read anywhere" is stale) | `CameraCharacteristicsSource.kt:181-192`; `FrameContentMappingHypothesis.kt:100-105` |
| **Distortion-correction mode** | **never read and never set** (`DISTORTION_CORRECTION_MODE` absent from the repository, grep). So the delivered YUV's correction state and the metadata's coordinate basis are **UNKNOWN** on every device. On devices that support the control, Camera2 enables correction by default | grep; Camera2 contract (§11.5) |
| Pre-correction vs active array | both read (`CameraCharacteristicsSource.kt:155-199`). `LENS_INTRINSIC_CALIBRATION` is accepted only when the pre-correction array equals the active array on all four edges (`AnalysisBufferIntrinsicsResolver.kt:211-232,376`). That guard avoids an offset mismatch but **does not consider the distortion mode**. The Pixel 9 pre-correction array is not recorded in any committed evidence | — |
| **Camera pose (extrinsics)** | `LENS_POSE_ROTATION`, `LENS_POSE_TRANSLATION`, `LENS_POSE_REFERENCE` **never read** (grep). Prediction assumes the nominal device↔camera axes (`DeviceToOpticalCameraTransform.kt`) | grep |
| Exposure / ISO / shutter | production: CameraX auto. SKY-1 (internalDebug): manual `CONTROL_AE_MODE_OFF`, exposure, ISO and frame duration via `Camera2Interop` on ImageAnalysis; presets 0.5 s/ISO1600 (default), 0.125 s/3200, 1 s/800, 2 s/400; per-frame validation | `mobile/src/internalDebug/.../SkyCaptureExposure.kt:191-267,427-447`; `SkySessionCaptureScreen.kt:109-124` |
| Night behaviour | none in production (no scene mode, extensions or low-light APIs) | grep |

**Active physical camera per frame (corrected in revision 2).** Revision 1 repeated the code's claim that CameraX
1.4.2 offers no way to learn the active physical camera of a logical multi-camera stream. That conclusion is **too
strong**. The repository has not proved it:
- `ImageProxy` / `ImageInfo` do **not** expose the active physical ID directly.
  - This is the claim actually made at `CameraPreview.kt:51-59`: "no per-frame physical-camera-identity callback".
  - That is correct only as a statement about CameraX's own types.
- **A Camera2 repeating capture result may expose it.**
  - Android Camera2 defines `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (API 29+; `minSdkMobile = 26`,
    so it needs an API guard, `gradle/libs.versions.toml:10`).
  - CameraX's `Camera2Interop.Extender.setSessionCaptureCallback(...)` installs a `CameraCaptureSession.CaptureCallback`
    on the same session that serves Preview and ImageAnalysis.
  - **The repository already uses exactly this mechanism** for SKY-1 exposure read-back (`SkyCaptureExposure.kt:259`).
    It joins results to frames by `SENSOR_TIMESTAMP` but never reads the active-physical-ID key.
- **Whether the Pixel 9 HAL actually reports the key** for this CameraX configuration, whether the value is stable,
  and whether it can be matched to every analysis frame **must be tested on the device** (PTS-03).

Until then the status is **UNINVESTIGATED / UNVERIFIED ON PIXEL 9**, not "unavailable".

### 10.2 Autofocus (repository truth)

**MISSING everywhere.** No code in `mobile/`, `core/` or `tools/` references `CONTROL_AF_MODE`, `AF_MODE`,
`FocusMeteringAction`, `startFocusAndMetering`, `LENS_FOCUS_DISTANCE`, `LENS_STATE` or
`LENS_INFO_MINIMUM_FOCUS_DISTANCE`. **`git log --remotes -S` over all 248 refs finds no commit that ever added or
removed AF code.** The historical "replace fixed focus with continuous AF" work **does not exist in this repository**.

| Path | AF set by repository? | Effective AF |
|---|---|---|
| Production Preview + ImageAnalysis | no | CameraX/HAL default (typically continuous-picture). UNKNOWN / UNVERIFIED ON DEVICE |
| SKY-1 capture (internalDebug) | no (AE options only) | default |
| CAM-2c FrameContent / physical-binding experiments | no | default |

- Focus distance and lens state are **not logged anywhere**: not in `SkyExposureSample`
  (`skylog/SkySessionLog.kt:451-457`), and not in the CAM diagnostic JSON.
- `docs/validation/cam_2c_pixel9_evidence.md:974` lists "camera focus" as untested.
- `docs/camera_coordinate_calibration_contract.md:340` defers per-frame focal-length changes under AF.

### 10.3 Same camera / same configuration?

- **Same logical camera:** yes. Preview and ImageAnalysis are bound together in every bind site
  (`CameraPreview.kt:278-283`, `SkySessionCameraPreview.kt:187-192`, `FrameContentCameraPreview.kt:187`).
- **Same physical sensor per frame:** UNKNOWN.
  - `ImageInfo` carries no physical identity.
  - The Camera2 capture-result route (`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` via the session capture callback,
    §10.1) is **uninvestigated**.
  - No code reads the key, and nothing has been tested on the Pixel 9.
- **Same FOV / crop between Preview and Analysis:** not enforced (no ViewPort) and UNVERIFIED.
- **Geometry valid for every analysed frame:** PARTIAL.
  - Per frame: pairing and `CropScaleTransform` are rebuilt (`CameraSessionGeometryProvider.kt:152-165`).
  - Intrinsics are cached for the session (`CameraSessionGeometryProvider.kt:188`;
    `SessionScopedCameraIntrinsicsResolver.kt:100-106`).
  - Only a buffer-size change is caught downstream (`CameraStarPredictor.kt:51-55`).
  - Matrix, crop or zoom changes are not re-validated.

### 10.4 Camera risks for star detection

1. AF is uncontrolled and unlogged: it may hunt in the dark, and a defocused session cannot be recognised afterwards.
2. The production analysis resolution is whatever CameraX picks.
   - 640×480 was observed under 1.3.4. The 1.4.2 value is UNVERIFIED ON DEVICE.
   - A buffer that small would make the plate scale coarse. The exact °/px depends on the Pixel 9 main-camera FOV,
     which the repository has not established (TBD BY DEVICE TEST).
3. Auto exposure (production) is unsuitable for faint point sources. Manual exposure exists only in SKY-1.
4. A logical multi-camera (Pixel 9: logical "0", physical "2,3,4") may switch sensors, for example in low light or by
   OEM policy.
   - Whether the switch is *silent* is not established. The capture-result active-physical-ID route (§10.1) has not
     been tried.
   - Whether focal length and intrinsics change with the active sensor (`LENS_FOCAL_LENGTH`,
     `LENS_INTRINSIC_CALIBRATION` in the capture result) is also untested.
5. No ViewPort, so the overlay can be offset from the visible preview.
6. Start-of-exposure timestamp with nearest-sample pose and no interpolation. With 0.5–2 s exposures, the pose at
   mid-exposure differs from the paired one. Rolling shutter is not modelled.
7. No distortion correction, so wide-angle edge stars are biased.
8. Intrinsics resolved once and never re-validated.
9. Misleading quality labels: `CameraGeometryQuality.CALIBRATED` is assigned to any `Resolved` result
   (`CameraIntrinsicsResolution.kt:59-64`), including `PhysicalSensor` and `APPROXIMATE_PRINCIPAL_POINT`.
10. SKY-1 provenance mismatch.
    - The header's `cameraId` is the *requested* physical ID (`SkySessionCaptureSession.kt:291`).
    - Intrinsics resolve over the bound logical `CameraInfo`, with `physicalCameraIds = null` (`:415,449-465`).
    - SKY-1 does not pin zoom.

---

## 11. Image geometry / projection truth

### 11.1 Pixel convention

**Continuous edge coordinates.** Raster sample `[x, y]` covers `[x, x+1) × [y, y+1)` and its centre is
`(x+0.5, y+0.5)`; the buffer centre is `W/2`.
- Stated in `core/astro-core/.../PixelGeometry.kt:9-34` and `docs/camera_coordinate_calibration_contract.md` §9.2.
- The detector uses it (`StarDetector.kt:432`, +0.5).
- `PixelConventionBridgeTest` pins detector ↔ projector agreement.
- UNKNOWN: whether `LENS_INTRINSIC_CALIBRATION` cx/cy follows the same half-pixel convention. It is used verbatim
  (`AnalysisBufferIntrinsicsResolver.kt:399-408`).

### 11.2 Transform chain and formulas

```text
sensor active array ──M (ImageInfo.getSensorToBufferTransformMatrix, static per bind)──▶ analysis buffer W×H (unrotated)
   K' (buffer) = M ∘ K(active):  a = m00·fx, c = m00·cx + m01·cy + m02, …   AnalysisBufferIntrinsicsMapping.kt:308-376
   accepted only when M classifies AXIS_ALIGNED_0 (scale+translate, positive) AnalysisBufferIntrinsicsResolver.kt:451-455
crop rect (buffer-local; full buffer without ViewPort)                       CropScaleTransform.kt:418-430
detector centroid (buffer px, edge coords)                                   StarDetector.kt:351-432
pixel → ray: PinholeProjectionModel.unprojectToCameraRay (exact inverse)     PinholeProjectionModel.kt:165-184
ray → pixel: u = fx·nx + cx, v = fy·ny + cy  (fx = W/(2tan(hFov/2)), fy = H/(2tan(vFov/2)); cx,cy default W/2,H/2)
                                                                             PinholeProjectionModel.kt:110-122,223-253
buffer → display: rotate CW by rotationDegrees (90: (h−y, x); 180: (w−x, h−y); 270: (y, w−x)),
                  scale = max(Vw/rotW, Vh/rotH), offset = (V − rot·scale)/2 (FILL_CENTER, never letterbox)
                                                                             CropScaleTransform.kt:326-401
AnalysisBufferScale: cameraRayFor (delegates to unproject), per-edge extents, enclosingConeRadiusRad,
                  radiansPerPixel (on-axis only, for tolerances)              match/AnalysisBufferScale.kt:77-250
```

- **x/y swap / mirror**: optional `axisSwapped`/`negateX`/`negateY` flags in the pinhole model. Mirroring is not
  supported (back camera only; the resolver rejects `MIRRORED` matrices).
- **Letterbox / pillarbox**: not modelled. FILL_CENTER only, matching the production `PreviewView`.
- **Stale intrinsics / principal point**: see §10.3.
- **FOV approximations**: legacy 56° in two different axes (§8.3.5).

### 11.3 Are a detected pixel and a predicted star in the same coordinate system?

**By construction yes, but only when every one of these holds:**
- (a) the frame's luma came through an ImageAnalysis path that keeps pixels (today: SKY-1 / FrameContent,
  internalDebug only);
- (b) the camera is not a logical multi-camera. The current resolver treats this as a hard gate. Relaxing it needs
  **two independent proofs**, and per-frame physical identity (§10.1) is only the first:
  - **identity**: which physical camera produced each frame;
  - **projection-domain compatibility**: the delivered `sensorToBufferTransformMatrix`'s source coordinate system is
    one in which that physical camera's active array, pre-correction array and `LENS_INTRINSIC_CALIBRATION` may
    legally be composed, **and** the mapped geometry matches actual frame content.

  `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md` §2.3 source-traces CameraX 1.4.2's matrix to the active array
  of the CameraX-**opened** camera. That remains the logical camera even under physical-stream binding, so the
  physical camera's arrays, intrinsics, crop and frame content are expected to live in a **different** basis. This is
  why `SensorToBufferDomainProof` exists separately from physical identity, and why `resolveCam2cForExplicitPhysicalCamera`
  refuses to unlock from identity alone (`mobile/src/internalDebug/.../SensorToBufferDomainProof.kt:1-20,85`);
- (c) a single focal length is reported, plus sensor size and active array;
- (c′) **the distortion-correction mode is known** for the stream, the intrinsics are expressed in the basis that mode
  implies, and lens distortion is accounted for exactly once (§11.5). Today the mode is never read, so this is
  unestablished on every device;
- (d) the matrix is present and `AXIS_ALIGNED_0`;
- (e) the crop region lies inside the active array;
- (f) the buffer size is unchanged since resolution;
- (g) zoom is 1.0× and the frame content follows CameraX's assumed centre crop (NOT ESTABLISHED,
  `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md:239`).

Unit tests pin this equality (`PixelConventionBridgeTest`, `CalibratedAnalysisBufferProjectionTest`,
`StarMatcherInputTest`). **No device has demonstrated it.**

- **On the Pixel 9 today**, (b) fails. The chain is `UnsupportedLogicalMultiCameraMapping`, then the CAM-1b
  `PhysicalSensor` reference, then `projectStars` returns `IntrinsicsMappingUnavailable(PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED)`
  (`CameraStarPredictor.kt:46-60`). **No projectable prediction exists to compare with.** The geometry strategy
  that lifts this must wait for the PTS-03 experiment (§28.2). The candidates are:
  - **A.** truthful per-frame physical-camera geometry. Allowed **only if both** identity **and**
    coordinate-domain/frame-content correspondence are established. A known physical ID, or a numerically plausible
    matrix, is not proof;
  - **B.** explicit physical binding. It faces the same domain question, because the matrix stays logical-basis;
  - **C.** approximate logical intrinsics plus a scale-tolerant solve, kept as the fallback.
- **Legacy-fallback trap**: when characteristics are unreadable or ambiguous, `legacyFallbackCameraIntrinsics`
  returns an `AnalysisBuffer` reference. `projectStars` accepts it and projects with a guessed 56° FOV, labelled
  `LEGACY_INTRINSICS_FALLBACK` (`LegacyFallbackCameraIntrinsics.kt:46-50`).
- **The production overlay is not in this system at all**: it uses viewport + 56° (§8.1).
- The CAM-2c physical-camera unlock path can never fire. `resolveCam2cForExplicitPhysicalCamera` requires a
  `ProvenActiveArrayLocal` domain proof (`mobile/src/internalDebug/.../SensorToBufferDomainProof.kt:85`), and that
  proof is constructed nowhere. SCAFFOLD.

### 11.4 Pixel 9 device evidence (what is actually recorded)

| Run | CameraX | Provenance | Key values |
|---|---|---|---|
| §1 of `cam_2c_pixel9_evidence.md` (`:37-52`) | 1.3.4 | hand-transcribed HUD screenshots, real Pixel 9, internalDebug | `cameraId=0 logical=true physicalIds=2,3,4 matrixClass=AXIS_ALIGNED_0 transformPresent=1115/1115 CAM-2c=UnsupportedLogicalMultiCameraMapping publishedReference=PhysicalSensor` |
| §3 (`:188-210`) | 1.3.4 | export/freeze workflow on a real Pixel 9 | `pixelArray=4080x3072 activeArray=[0,0—4080,3072] ImageAnalysis buffer=640x480 cropRect=[0,0—640,480] rotationDegrees=90 matrix=identity framesWithSupportedTransformClass=1751/1751 CAM-2c=UnsupportedLogicalMultiCameraMapping` (the identity matrix is the 1.3.4 unset default, recon doc `:211-223`) |
| 1.4.2 matrix | 1.4.2 | recorded only in `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md:237-252` as "DEVICE_OBSERVED (the matrix)"; raw export not committed | `m00 = m11 = 0.1568627506 (=640/4080), m12 ≈ −0.941` → uniform scale + symmetric crop, `AXIS_ALIGNED_0` |
| everything after the 1.4.2 upgrade (physical binding, frame-content target, SKY-1) | 1.4.2 | **code-only**: "INSTRUMENTED/DEVICE EXECUTION PENDING" | `cam_2c_pixel9_evidence.md:783-787,1565ff.`; `docs/SPRINT_STATUS.md:16-21,1633` |

So the README's "confirmed on a real Pixel 9's rear camera" for `UnsupportedLogicalMultiCameraMapping` **is backed**
by §1/§3, though under CameraX 1.3.4. The older README-only PR #242 would remove that claim and should be revisited.

**Resolution provenance.**
- The committed 640×480 buffer observation comes from the **1.3.4** run (§3).
- The 1.4.2 matrix scale `640/4080` *suggests* a 640-px-wide buffer, but no committed 1.4.2 export records the
  buffer size, crop or stream configuration. So the **1.4.2 production resolution remains UNVERIFIED ON DEVICE**.
- PTS-03 must record it.

**Not recorded anywhere:**
- any per-frame Camera2 capture result (active physical ID, focal length, focus distance, AF state, crop region);
- any SKY-1 session;
- any detector run;
- any star frame from any device.

---

### 11.5 Distortion-correction state and coordinate basis (revision 4)

A camera-domain proof that does not know the distortion-correction state is **incomplete**.

**Camera2 platform contract**, per the Camera2 reference for `CaptureRequest.DISTORTION_CORRECTION_MODE` (API 28+),
`SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` and `LENS_INTRINSIC_CALIBRATION`:
Two different things must be kept apart: the **metadata coordinate contract**, and the **actual pixel-domain
correction** the HAL performs.

*Pixel-domain correction (what the HAL does to processed output):*
- `OFF`: no distortion correction.
- `HIGH_QUALITY`: the highest-quality correction, even if capture slows.
- `FAST`: a camera-selected correction that must not slow capture. It **may be effectively the same as `OFF`** if any
  correction would slow capture.
- So `FAST` does **not** imply fully corrected YUV.

*Metadata coordinate contract (which basis affected metadata uses):*
- mode `OFF`: the pre-correction active array (`SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`);
- any mode other than `OFF` (`FAST` or `HIGH_QUALITY`): the active array (`SENSOR_INFO_ACTIVE_ARRAY_SIZE`).

The metadata basis therefore follows the reported mode, while the residual distortion in the pixels must be
**measured**.
- Correction applies to processed outputs (YUV / Y8 / JPEG), not to RAW.
- On devices that support the control, correction is **on by default**.
- `LENS_INTRINSIC_CALIBRATION` is defined in the **pre-correction** active-array coordinate system.

**Repository truth.**
- `DISTORTION_CORRECTION_MODE` is never read and never set (grep), so every session runs on the device default.
- `LENS_DISTORTION` is read but never applied (§10.1).
- The intrinsics resolver uses `LENS_INTRINSIC_CALIBRATION` only when the pre-correction and active arrays are
  identical (`AnalysisBufferIntrinsicsResolver.kt:211-232,376`).
- No code knows whether the analysed YUV is distortion-corrected.
- The pinhole projector applies **no** distortion (`PinholeProjectionModel.kt:110-122`). That is right for
  camera-corrected output, and wrong for uncorrected output away from the image centre.

**Chain to establish for every tested stream / physical camera** (PTS-03 Group C):

```text
distortion mode (per frame, CaptureResult)
  → source calibration coordinate space (LENS_INTRINSIC_CALIBRATION: pre-correction array)
  → metadata coordinate basis implied by that mode (pre-correction for OFF; active array otherwise)
  → is measurable lens distortion still present in the delivered analysis YUV? (measured, not inferred from the mode)
  → is an application-side distortion model required?
  → active vs pre-correction array relationship (offsets, sizes, per physical ID)
  → sensor-to-buffer matrix domain (CameraX: opened camera's SENSOR_INFO_ACTIVE_ARRAY_SIZE; §11.3)
  → final pixel coordinate space of the analysis buffer
```

**Rules, for any strategy chosen in PTS-09:**
- Never assume correction is unnecessary just because the mode reports `FAST`. Whether distortion remains must be
  measured, for example from edge residuals in the frame-content correspondence.
- If the YUV is **measured** to be camera-corrected, **do not** distort or undistort it again with `LENS_DISTORTION`.
  Express
  the intrinsics in the corrected (active-array) basis, through a transform that is proven and not assumed.
- If correction is `OFF`, either model `LENS_DISTORTION` explicitly in the pre-correction basis, or **reject
  calibrated projection** until that transform exists.
- The current pre==active equality guard is necessary but **not sufficient**: equal arrays do not reveal whether the
  pixels are corrected.
- Residual lens-model refinement beyond the mode/basis truth stays P2 (G-18).

## 12. Star source detector

**Status: DONE as a pure-JVM algorithm (unit-tested on synthetic frames). Not production-used. UNVERIFIED ON DEVICE.
Never run on real sky.**

| Stage | Implementation | Evidence |
|---|---|---|
| Input | `LumaFrame`: 8-bit luma, row-stride aware; `forReference` checks `RAW_Y8` and byte length | `core/astro-core/.../camera/detect/LumaFrame.kt:24-89` |
| YUV → LumaFrame on device | **none in production.** internalDebug SKY-1 packs the Y plane to `rowStride == width` (`FrameContentCameraPreview.kt:46-84`); the format docs say "planes[0] verbatim" (minor doc drift) | — |
| Preprocessing | none: no denoise, blur, dark frame or hot-pixel map | — |
| Background | tiled, 64 px tiles; level = exact-histogram median; sigma = (median − q25)/0.6745 measured on the **residual** after bilinear level subtraction (PR #234); bilinear interpolation between real tile centres, flat beyond them | `TiledBackground.kt:206-342`; `StarDetector.kt:66` |
| Threshold | `luma > level + max(4.0·σ, 1.0)` | `StarDetector.kt:74,81,333-342` |
| Blobs | 8-connected flood fill (4 optional), `visited` BooleanArray | `:120,216-281` |
| Centroid | intensity-weighted `max(luma − bg, 0)`, +0.5 edge convention; no PSF fit | `:351-432` |
| Size filter / hot pixels | `3 ≤ pixelCount ≤ 5000`. The 3-pixel minimum is the only hot-pixel rejection | `:89,97` |
| Saturation / edge | flagged, not rejected (`peak ≥ 255`; `EDGE_MARGIN_PX = 1`) | `:100,107,134,402-419` |
| Blur / shape | **none**: no FWHM, elongation, SNR, confidence or centroid σ | `StarMatcherInput.kt:49-53` |
| Output | `DetectedSource(xPx, yPx, brightness, peakLuma, localBackgroundLuma, pixelCount, saturated, nearEdge)`. Brightness is raw summed flux, explicitly **not** a magnitude. Sorted brightness ↓, then y, then x. **No maximum count.** Identity = list index | `StarDetector.kt:182-191,293-296` |
| Accuracy claims | RMS < 0.2 px on a synthetic Gaussian (FWHM 3, σ 2); < 0.1 px noiseless | `StarDetectorTest.kt:51-58,388-404` |
| Allocations | per frame: `BooleanArray(W·H)`, background arrays, a residual `DoubleArray` reused across tiles, `ArrayDeque<Int>` (boxes every index > 127), per-component accumulators, a final sorted copy | — |
| Complexity | ≈ O(W·H·log T) + per-tile sort O(4096·log 4096); about 3 interpolations per pixel | — |
| Benchmarks | none | — |
| Tests | `StarDetectorTest` 16, `TiledBackgroundTest` 17, `DetectionEvaluationTest` 12, `LumaFrameTest` 7, `SyntheticFrameRendererTest` 6, `PixelConventionBridgeTest` 3, `SkySessionLogDetectionTest` 2. **All fixtures synthetic** (640×480, stride 704, background 24, FWHM 3) | `core/astro-core/src/test/.../camera/detect/*` |
| Call sites | `tools/sky-session-loader/.../SkySessionDetectionRun.kt:246` and tests. **Zero in `mobile/` or `wear/`** | grep |

**Answers.**
- *Suitable for stars?* Conceptually yes: a local background, a k·σ threshold, connected components and a weighted
  centroid is a standard first-generation design. It has not been exercised on real sensor noise, hot pixels, ISP
  denoise/sharpening, compression or defocus.
- *Wired to the production camera path?* **No.**
- *Prototype or test path only?* Yes: offline JVM tool plus tests.
- *Known failure modes* (from `docs/star_detection_contract.md:112-129` and the code):
  - tile-scale curvature in the background (Moon glow, cloud edges, horizon lights) leaves "crown" fragments;
  - flat extrapolation at borders;
  - σ quantised in steps of 1.48 luma on flat skies;
  - brightness truncated by the threshold;
  - no hot-pixel map;
  - no shape or blur rejection;
  - no detection cap (an urban frame could yield thousands of sources);
  - per-pixel boxing.
- *Work before autodetect is usable*:
  1. an on-device luma tap;
  2. a real-sky dataset;
  3. a detection cap and SNR/shape metrics;
  4. hot-pixel handling (temporal persistence or a dark-frame map);
  5. a performance pass (no boxing, reuse buffers);
  6. AF/exposure control upstream.

  Items 3–5, plus a source-quality/uncertainty contract for the matcher, are scheduled as **PTS-12 (detector
  hardening on real-sky fixtures)**. It is evidence-driven from the early real-sky dataset (PTS-11) and is a
  prerequisite of matcher v1 (PTS-13), G-27.

---

## 13. Matcher

**Status: algorithm MISSING (on `main` and on every branch). Input contract DONE (unit-tested; no production
constructor).**

`core/astro-core/.../projection/camera/match/` holds exactly three files: `StarMatcherInput.kt`,
`StarCatalogQuery.kt` and `AnalysisBufferScale.kt`. `docs/star_matcher_input_contract.md:3-5`: "no matching
algorithm exists yet. No association, no geometric invariants, no hashes, no RANSAC, no pose, no plate solve."

The only correspondence / pose / RANSAC-like code in the repository is the CAM-2c internalDebug **printed dot-grid
target** experiment (`mobile/src/internalDebug/.../FrameContentCornerDetector.kt`, `FrameContentPoseMath.kt`, a
planar DLT homography). It is labelled evidence-only and is **not star matching**.

### 13.1 Current equivalents of the requested concepts

| Concept | Current type | Fields / behaviour | Production producer |
|---|---|---|---|
| `StarMatcherInput` | `match/StarMatcherInput.kt:110-196` | `detections: List<DetectedSource>`, `candidates: List<EquatorialStarDirection>`, `scale: AnalysisBufferScale`, `priorProjections: List<PredictedStarProjection>`. Private constructor; the only way in is `of(...)`, which snapshots the lists. `init` requires unique candidate `catalogIndex` values and that every prior names a candidate. **No timestamp, frame id, attitude prior, observer or exposure** | **none** |
| `DetectedSource` | `detect/StarDetector.kt:182-191` | see §12 | `detectStars` (offline only) |
| `EquatorialStarDirection` | `prediction/EquatorialStarDirection.kt:29-33` | `catalogIndex: Int`, `rightAscensionRad` (canonical [0,2π)), `declinationRad`, `magnitude: Double?` | `PtskCat0StarCatalogQuery` (tests); `PredictedStarCatalogAdapter` (CAM-2b, from PTSKCAT4 **ids**) |
| `AnalysisBufferScale` | `match/AnalysisBufferScale.kt:77-250` | `pinhole`, `quality: CameraGeometryQuality`; `cameraRayFor`, per-edge FOV, `enclosingConeRadiusRad`, `radiansPerPixel*`; `forGeometry` throws for unmappable intrinsics (no fallback, by design: "a fabricated scale is worse than no scale") | tests only |
| `PredictedStarProjection` | `prediction/PredictedStarProjection.kt:67-73` | `catalogIndex`, `magnitude`, `classification`, `cameraDirection?`, `imagePoint?`, `displayPoint?` | `projectStars` (CAM-2b overlay, SKY-1 capture, replay) |
| `priorProjections` | field of `StarMatcherInput` | documented as "a hint, never an answer"; empty is the honest default; a matcher must work without it (`StarMatcherInput.kt:80-90`; contract `:304-315`) | **none** |
| Catalog port | `StarCatalogQuery.nearby(raRad, decRad, radiusRad, magnitudeLimit?)`; `normalizeStarCatalogQuery` validates and wraps | `match/StarCatalogQuery.kt:45-182`; impl `core/catalog/.../PtskCat0StarCatalogQuery.kt:50-127` (linear scan) | tests only |
| Ray-angle helper | `angleBetweenRad` is **private** on main; PR #236 (open) publishes it with a unit-ray check | `AnalysisBufferScale.kt:261-275` | — |

### 13.2 Requested matcher properties

Algorithm, candidate generation, invariants, nearest-neighbour, pair/triangle use, RANSAC, error metric, thresholds,
ambiguity handling, outlier rejection, minimum matches, confidence, fallback, complexity and frame reuse: **none
exist**. The contract does fix several rules a future matcher must follow:
- angles come only from `cameraRayFor`, never from `pixelDistance × radiansPerPixel`;
- brightness is a within-frame rank only;
- centroids carry no uncertainty;
- saturated/near-edge sources mean "widen the tolerance, don't reject";
- detection identity is its list index;
- `priorProjections` may bound the search but must never be the association.

### 13.3 `priorProjections` audit

| Question | Answer |
|---|---|
| Who produces it? | Nobody in production. Only tests populate it. |
| Which frame/time does it correspond to? | Not recorded: the type has no timestamp. It would be `projectStars` output for the frame's paired rotation sample (`FrameRotationPairing`), but no code joins them. |
| Are timestamps respected? | Not applicable (no field). |
| When is it cleared? | Never managed. It is a per-construction list. |
| Does it reduce ambiguity? | Not today. The KDoc permits only bounding use. |
| Can stale priors cause a false lock? | The contract forbids prior-based association precisely to prevent that circularity (`DetectionEvaluation.kt:13-25`). With no matcher, the risk is latent. |
| Can the matcher recover after losing lock? | No matcher exists. |

`DetectionEvaluation.evaluateDetections` (greedy nearest-neighbour within `tolerancePx`) is a **detector metric** and
is explicitly "not the matcher" (`detect/DetectionEvaluation.kt:13-25,92-138`).

---

## 14. Sensor attitude / fusion

| Item | Phone | Watch |
|---|---|---|
| Sensors used | `TYPE_ROTATION_VECTOR` only; no fallback; frame = null if absent (`mobile/.../ar/RotationFrame.kt:52-56`) | `TYPE_ROTATION_VECTOR` primary; accel + mag fallback chosen **only at construction** (`wear/sensors/.../OrientationRepository.kt:34-54`, `DelegatingOrientationRepository.kt:62-70`); throws if neither exists (`:47-49`) |
| Gyro / game RV / geomagnetic RV | not used | not used (MISSING) |
| Rate | `SENSOR_DELAY_GAME`, unthrottled | `SENSOR_DELAY_GAME`, throttled to 66 ms (about 15 Hz) by event time (`OrientationModels.kt:115-122`) |
| Filtering | none | EMA on forward α = 0.25 (RV) (`RotationVectorOrientationRepository.kt:27,155,183`); EMA on raw accel/mag α = 0.15 `// TODO: tune` (`AccelMagOrientationRepository.kt:24`). Azimuth/pitch/roll **unfiltered** |
| Timestamps | `event.timestamp` used for camera pairing | used for FPS/logging |
| Accuracy | ignored (`onAccuracyChanged = Unit`, `RotationFrame.kt:95-98`) | mapped (unknown → MEDIUM); Identify shows a figure-8 hint; **Aim ignores accuracy** (its confidence = circular variance of azimuth, `DefaultAimController.kt:509-531`) |
| Magnetic interference detection | none | none (MISSING) |
| Declination | `GeomagneticField(lat, lon, 0, now)` once per location (`ArScreen.kt:463-475`) | new `GeomagneticField` **every tick** (uncached, TODO at `GeomagneticFieldDeclinationProvider.kt:10-12`) |
| Remap | display rotation (portrait-locked) | user setting, default ROT_0 |
| Manual calibration | none | zero-azimuth offset, **not persisted** (`RotationVectorOrientationRepository.kt:40`; `SensorsViewModel.kt:76-81`) |
| Phone heading to watch | `PhoneCompassBridge` sends magnetic `getOrientation` azimuth (no remap, no declination) at ≤ 2 Hz (`mobile/.../sensors/PhoneCompassBridge.kt:84-115`) | `PhoneHeadingOverrideRepository` replaces **`azimuthDeg` only**. Aim and Identify read `forward`, so the override has **no effect** (BROKEN). It subtracts the zero offset while the sensors add it (sign inconsistency) (`wear/.../sensors/orientation/PhoneHeadingOverrideRepository.kt:19-31`) |

**What does the camera provide to attitude?** Nothing. There is no absolute attitude, no attitude correction, no yaw
correction, no full-quaternion correction and no screen offset derived from the camera.
- The CAM-2b overlay "never feeds calculateOverlay, projectionParams, or any production star position"
  (`ArScreen.kt:669-675`).
- The authoritative orientation for navigation is the raw rotation vector plus declination (phone:
  `RotationFrame.correctedForTrueNorth`; watch: `OrientationRepository` from `wear/.../MainActivity.kt:107-110`).

### 14.1 Frame model for future optical fusion (required before the tracker exists)

Revision 3 replaced revision 2's unqualified `q_corr = R_opt · R_sensor⁻¹`, which multiplied a camera-frame attitude
by a device-frame attitude as if both had the same source frame.

Revision 4 further separates the **physical** device frame from the **display-remapped** frame. A physical
camera↔device extrinsic is body-fixed. The display-remapped frame changes with display rotation, so it must not
carry body-fixed terms.

**Frames.** The notation is `R_A_from_B`, which maps a vector in frame B to frame A.

| Frame | Meaning | Layer | Evidence |
|---|---|---|---|
| `Wmag` | world ENU referenced to **magnetic** north; the frame Android's rotation vector reports | world | `mobile/.../ar/RotationFrame.kt:62-75` |
| `Wtrue` | world ENU referenced to **true** north; the frame catalog-derived directions are built in | world | `prediction/LocalSkyDirection.kt:116-126`; `R_Wmag_from_Wtrue = trueEnuToMagneticEnu` (`TrueNorthToMagneticNorthTransform.kt:73-87`) |
| `S` | **Android sensor / device physical frame**, i.e. the device's natural-orientation axes that `SensorManager.getRotationMatrixFromVector` reports **before** `remapCoordinateSystem`. This is also the "Android sensor coordinate system" that Camera2 `LENS_POSE_ROTATION` is defined against | physical / body | `RotationFrame.kt:62-69` (matrix before `remapForDisplay`) |
| `Ddisp` | **display-remapped device frame** (+x display right, +y display up, +z out of the display), i.e. `S` after `remapForDisplay(displayRotation)`. The production phone activity is portrait-locked, so today `Ddisp = S` in practice, but structurally this is a **presentation** transform | presentation | `DisplayRemap.kt:7-30`; `RotationFrame.kt:69`; `DeviceToOpticalCameraTransform.kt` (`DeviceVector` KDoc) |
| `Cphys(id)` | **physical camera-aligned frame** of physical camera `id`: the image sensor's axes and optical axis. Body-fixed relative to `S` for a given physical camera. Its exact axis convention, as Camera2 defines it for `LENS_POSE_ROTATION`, must be pinned in PTS-03 against the platform reference | physical / body | Camera2 `LENS_POSE_*` (not read anywhere in the repo, grep) |
| `Cdisp` | display-aligned optical camera frame (+x image right, +y image down, +z forward) | presentation | `DeviceToOpticalCameraTransform.kt` (`OpticalCameraVector` KDoc) |
| `Cbuf` | buffer-aligned optical frame, i.e. `Cdisp` rotated by `ImageProxy` `rotationDegrees`. The analysis buffer is the unrotated sensor readout, so `Cbuf` follows the physical sensor's readout axes up to a fixed axis-convention permutation (to be pinned in PTS-03). `unprojectToCameraRay` returns rays in this frame | image / physical readout | `DisplayAlignedOpticalToBufferOpticalTransform.kt:88-109`; `PinholeProjectionModel.kt:165-184,223-253` |

**Transform ownership.**

| Transform | Kind | Changes when… | Source today |
|---|---|---|---|
| `R_Wtrue_from_Wmag` | world (declination) | location/date changes | WMM `GeomagneticField` |
| `R_Wmag_from_S` | sensor attitude | the device moves | rotation vector (raw `R`, before remap) |
| `R_S_from_Ddisp` | **presentation** | display rotation changes | `remapForDisplay` (identity under the portrait lock) |
| `R_Cphys_from_S(id)` | **physical, body-fixed extrinsic** | never, for a given physical camera | **not represented explicitly**: implied by the nominal assumption below; candidate platform source `LENS_POSE_ROTATION` (never read) |
| `R_Cbuf_from_Cphys(id)` | physical readout axis convention | never, for a given physical camera | implicit; to be pinned in PTS-03 |
| `R_Cdisp_from_Ddisp` (nominal) | combined nominal assumption | — | `diag(1, −1, −1)` (`opticalX = deviceX`, `opticalY = −deviceY`, `opticalZ = −deviceZ`) |
| `R_Cbuf_from_Cdisp` | **presentation / buffer** | `rotationDegrees` changes | `DisplayAlignedOpticalToBufferOpticalTransform` |

**Repository-truth chain** (CAM-2a prediction, `CameraStarPredictor.kt:69-134`):

```text
v_Cbuf = R_Cbuf_from_Cdisp(rotationDegrees) · R_Cdisp_from_Ddisp(nominal diag(1,−1,−1)) · R_Ddisp_from_Wmag · R_Wmag_from_Wtrue · v_Wtrue
         with R_Ddisp_from_Wmag = Rᵀ of the display-remapped rotation matrix (worldToDeviceVector, RotationMath.kt:38-51)
```

The code never isolates the body-fixed extrinsic. It is **implied**:

```text
R_Cbuf_from_S(implied) = R_Cbuf_from_Cdisp(rotationDegrees) · R_Cdisp_from_Ddisp(nominal) · R_Ddisp_from_S(displayRotation)
```

That expression mixes two presentation terms with one nominal physical assumption. For it to describe a physical
extrinsic, the two presentation terms (display remap and `rotationDegrees`) must cancel consistently across display
rotations, leaving a body-fixed `R_Cbuf_from_S`. Under the portrait lock only one combination is exercised. Exact
invariance across rotations is **unpinned by tests** (PTS-18).

**Sensor-predicted camera attitude** (composed only through matching inner frames):

```text
R_Wtrue_from_S_sensor        = R_Wtrue_from_Wmag(decl) · R_Wmag_from_S(rotation vector)
R_Wtrue_from_Cbuf_sensorPred = R_Wtrue_from_S_sensor · R_S_from_Cphys(id) · R_Cphys_from_Cbuf(id)
                               (today: the implied nominal R_S_from_Cbuf above)
```

**Optical attitude.** A future matcher plus Wahba solve pairs `Cbuf` rays with `Wtrue` catalog directions (after the
epoch/precession/refraction policy). It therefore returns `R_Wtrue_from_Cbuf_optical`.

**Comparisons must have equal source and destination frames.**
- `ΔW = R_Wtrue_from_Cbuf_optical · (R_Wtrue_from_Cbuf_sensorPred)ᵀ` is a `Wtrue ← Wtrue` rotation. It is
  world-fixed, for example a magnetic heading error.
- `ΔC = (R_Wtrue_from_Cbuf_sensorPred)ᵀ · R_Wtrue_from_Cbuf_optical` is a `Cbuf ← Cbuf` rotation. It is attributable
  to the body-fixed physical extrinsic `R_Cphys_from_S(id)`, or to its readout convention, **per physical camera
  id**.
- It is **not** attributable to the display remap or to `rotationDegrees`, which are presentation terms with known
  values.

The recon does **not** choose a representation. The future tracker decides how to model world-fixed attitude bias,
the body-fixed `S ↔ Cphys(id)` extrinsic, magnetic heading correction and short-term propagation. It must not
collapse them into one frame-ambiguous constant quaternion, and it must not store a body-fixed term in `Ddisp` or
`Cdisp`.

**Extrinsic provenance.** `R_Cphys_from_S(id)` must carry one of these statuses, recorded per physical ID by PTS-03
and consumed by PTS-09/PTS-18:

| Status | Meaning |
|---|---|
| `CALIBRATED_PLATFORM_POSE` | `LENS_POSE_ROTATION` present, `LENS_POSE_REFERENCE` not `UNDEFINED`, **and** consistent with optical evidence (printed target / star solution) within a tolerance TBD BY DEVICE TEST |
| `PLATFORM_POSE_PRESENT_BUT_UNVERIFIED` | present with a non-`UNDEFINED` reference, but not yet compared against optical evidence |
| `PLATFORM_POSE_UNUSABLE` | absent, `UNDEFINED` reference, or inconsistent with optical evidence |
| `NOMINAL_FALLBACK` | PointToSky's nominal axes (today's implicit behaviour) |
| `OPTICALLY_REFINED` | estimated or refined by the optical solution / tracker, with its uncertainty |

`LENS_POSE_REFERENCE` defines the origin of the pose translation and qualifies the pose fields. Its exact semantics
for the rotation under each reference value must be confirmed against the Camera2 reference in PTS-03.
`LENS_POSE_TRANSLATION` (metres) is recorded, but it is irrelevant for directions to stars at infinity. Its only use
is camera identity and consistency checks.

**Required invariants / tests for the future tracker** (PTS-18):
1. With a perfect sensor orientation and the true extrinsic (nominal in synthetic tests), the optical residual is
   identity (`ΔW = ΔC = I` within tolerance).
2. With a valid correction held, rotating the phone propagates the camera pose correctly. A simulated world-fixed
   heading bias stays correct after rotation only when applied world-side. A simulated body-fixed misalignment stays
   correct only when applied as an `S ↔ Cphys` extrinsic. Applying either one on the wrong side must fail after a
   rotation.
3. A body-fixed camera/device transform never becomes a world-fixed correction.
4. **Changing the display rotation, or `ImageProxy.rotationDegrees`, changes only the presentation/buffer-basis
   terms** (`R_S_from_Ddisp`, `R_Cbuf_from_Cdisp`). It must not mutate the estimated physical `R_Cphys_from_S(id)`.
   The implied `R_Cbuf_from_S` must be invariant across the four display rotations when `rotationDegrees` is
   consistent.
5. A switch of active physical camera `id` selects that camera's own extrinsic and intrinsics. It never re-uses
   another id's estimated extrinsic silently.
6. Multiplication order and quaternion conventions are pinned by tests using non-commuting rotations.
   - Camera2 `LENS_POSE_ROTATION` is four coefficients in the order **`[x, y, z, w]`**, describing the rotation
     **from the Android sensor coordinate system `S` to the camera-aligned frame `Cphys`**.
   - The parser must take `input array = [x, y, z, w]`. A quaternion helper whose constructor expects `(w, x, y, z)`
     may be used only through an explicitly named reorder adapter. The repository's existing sky-log quaternion is
     already scalar-last `(x, y, z, w)` (`skylog/SkySessionLog.kt:246-295`).
   - **Regression test:**
     - pick a non-trivial known rotation;
     - parse its Camera2 `[x, y, z, w]` values and build `R_Cphys_from_S`;
     - check the transformed basis vectors;
     - check that reading the same four numbers as `[w, x, y, z]` gives a different matrix and fails.
   - The direction (`S → Cphys`, not its inverse), the magnetic/true
   distinction, the display remap and `rotationDegrees` are each exercised. A wrong order, a wrong direction or a
   missing frame must fail.

---

## 15. Temporal tracking / lock

**Optical tracking: MISSING.**

| Question | Answer |
|---|---|
| Does every frame solve from scratch? | Nothing solves. `detectStars` is stateless (it rebuilds the background and `visited` every call). |
| State that survives between frames today | non-optical only: the rotation-sample ring for camera pairing (`RotationSampleHistory`, capacity 120); the session-scoped intrinsics cache; the watch EMA filters; the watch Aim phase machine (`SEARCHING / IN_TOLERANCE / LOCKED / BELOW_HORIZON / NO_LOCATION`, `wear/.../aim/core/AimModels.kt:31`; hold-to-lock with a 1.8× release box and 3-tick grace, `DefaultAimController.kt:439-492,540-541`). |
| Previous pose / correspondences / optical flow / Kalman / confidence decay / reacquisition | none |
| Only one star visible / clouds / fast motion / exposure change / quick rotation / stale prior / high-confidence wrong match | **Not handled by any code.** Today the app would keep showing the sensor-only overlay, because no optical state exists to lose. |

The watch "LOCKED" is a **sensor-tolerance** state ("the user is pointing within N° of the target for 1.2 s"). It
says nothing about optical lock.

A future tracker's surviving state must be **frame-labelled** (§14.1):
- the last `R_Wtrue_from_Cbuf_optical` with its frame timestamp;
- any estimated world-fixed correction term (`Wtrue`) and, **separately**, the body-fixed physical extrinsic
  `R_Cphys_from_S(id)` per physical camera with its provenance status. Neither is ever stored in a display-remapped
  frame (`Ddisp`/`Cdisp`), since display rotation changes those;
- the rotation-sample history used to propagate between frames;
- time-stamped `priorProjections` derived from those.

An untyped "pose" or "correction" carried across frames is not acceptable.

---

## 16. Autodetect end-to-end (repository truth)

```text
Camera (CameraX 1.4.2, logical back camera, AE/AF defaults)
  │  [WORKING]  Preview + ImageAnalysis bound together (CameraPreview.kt:278-283)
  ▼
Frame geometry (metadata, rotation pairing, crop/scale, intrinsics)
  │  [PARTIAL]  computed in production, consumed only in internalDebug; Pixel 9 → PhysicalSensor (unprojectable)
  ▼
Luma pixels
  │  [MISSING in production]  CameraFrameAnalyzer never reads planes
  │  [DEBUG ONLY]             SKY-1 / FrameContent (internalDebug) pack Y plane → disk
  ▼
Detector (detectStars)
  │  [NOT CONNECTED]  runs only in tools/sky-session-loader (offline JVM) and tests
  ▼
DetectedSource[]
  │  [NOT CONNECTED]
  ▼
Visible-star candidate generation
  │  [DEBUG ONLY / PARTIAL]  CAM-2b: brightest-200 PTSKCAT4 whole-sky → projectStars (no horizon, no cone)
  │  [NOT CONNECTED]         StarCatalogQuery/PtskCat0StarCatalogQuery: tests only
  ▼
Matcher (StarMatcherInput → ?)
  │  [MISSING]  input DTO only
  ▼
Optical solution (attitude + confidence)
  │  [MISSING]
  ▼
Sensor correction / fusion
  │  [MISSING]
  ▼
Temporal tracking
  │  [MISSING]
  ▼
Navigation state
  │  [WORKING, sensor-only]  phone: none (reticle only); watch: DefaultAimController
  ▼
UI overlay
     [WORKING, legacy]  ArScreen.calculateOverlay with 56° FOV, uncorrected rotation vector
     [DEBUG ONLY]       PredictedStarOverlayUi (CAM-2b markers)
```

Offline loop that does exist:
`SKY-1 capture (internalDebug, device) → session.jsonl + frames/*.y → tools/sky-session-loader → replay(projectStars) → detectStars → evaluateDetections → metrics`.
Status: implemented and JVM-integration-tested on **synthetic** sessions. **No real session exists.**

---

## 17. Navigation / aim experience

### 17.1 Watch (production)

1. **Selection.** The picker offers SUN/MOON/JUPITER/SATURN/POLARIS (`AimScreen.kt:231-240`). External targets arrive
   through `/aim/set_target`, `/app/open`, intents and the complication.
2. **Representation.** `AimTarget.{EquatorialTarget, BodyTarget, StarTarget(id, eq?)}` (`AimModels.kt:45-65`).
3. **Target alt/az.** Equatorial targets are used as given; bodies go through `SimpleEphemerisComputer` (1-day lag);
   stars use `eq` or `offlineStarResolver`. The resolver is **DEAD** because `catalog/stars_V1.bin` is not shipped
   (`OfflineStarResolver.kt:20-25`). Conversion: `lstAt` plus `raDecToAltAz(refraction = false)`, no precession
   (`DefaultAimController.kt:416-430`).
4. **Current ray.** `forward` → horizontal → `toTrueNorth(decl)` (`:387-400`).
5. **Relative direction.** `aimDelta`: cross-track = wrapped dAz·cos(alt), along-track = dAlt
   (`core/astro-core/.../aim/AimGeometry.kt:33-38`).
6. **Phase machine.**
   - Tolerance defaults: 3°/4° (NAKED_EYE); FINDER mode 1.5°/2°.
   - Release box: 1.8×. Grace: 3 ticks. Hold to lock: 1,200 ms (configurable 200–3,000).
   - Phase gates: NO_LOCATION; BELOW_HORIZON when the target altitude is < 0°.
   - The tick runs every 66 ms. Evidence: `DefaultAimController.kt:276-301,439-492,535-541`; `AimModels.kt:7-29`.
7. **UI.**
   - Shown: a phase badge, a confidence ring, a left/right `TurnArrow` (direction only, no magnitude), |ΔAz| text and
     an altitude bar.
   - Not present: a reticle and audio.
   - Evidence: `AimScreen.kt:272-362,519-541`.
8. **Haptics.** ENTER 60 ms; LOCK a triple waveform; LOST 130 ms (fires on any transition to SEARCHING, including
   target changes) (`wear/.../haptics/HapticPolicy.kt`; `AimScreen.kt:184-198`).

**Delivery bugs** (BROKEN by code reading; UNVERIFIED ON DEVICE).

There are three `setTarget` writers in the Aim screen:
- `AimRoute`'s `externalAim` effect (`AimScreen.kt:115-118`);
- `AimScreen`'s one-shot `initialTarget` effect (`:160-168`);
- the picker effect, `snapshotFlow { pickerState.selectedOption }.collect { setTarget(options[idx]) }` (`:248-260`).

The picker is seeded only from `initialTarget`: `initialIndex = targetIndexFor(initialTarget)` (`:241`), and
`targetIndexFor` maps `null` to 0 (SUN), Body targets to their slot, and **Equatorial/Star targets to 4 (POLARIS)**
(`:550-563`). The two delivery paths therefore fail differently:

| Path | How it arrives | Picker seed | Likely outcome | Must be tested |
|---|---|---|---|---|
| `/app/open` with a target | `WearBridge.appOpens` → `latestAppOpen` → `AimRoute(initialTarget = appOpenRequest?.target)` (`wear/.../MainActivity.kt:381-386,416-420`) | `targetIndexFor(EquatorialTarget/StarTarget) = 4` → POLARIS | the initial effect sets the requested target, then the picker's first emission can overwrite it with **Polaris** | first composition of Aim via `/app/open` with an equatorial target (e.g. Vega) |
| `/aim/set_target` entered from Home (`initialTarget == null`) | `WearBridge.aimLaunches` → `latestAim` → `AimRoute(externalAim = aimRequest)` → `controller.setTarget(it.target)` directly (`MainActivity.kt:376-379,414-420`; `AimScreen.kt:115-118`) | `targetIndexFor(null) = 0` → SUN | the picker's first emission may overwrite the external target with the **Sun** | navigate Home → Aim with a pending `externalAim` |
| `/aim/set_target` while Aim is already mounted | new `externalAim.seq` re-runs the `AimRoute` effect | the picker has already emitted, and `selectedOption` does not change | ordering and emission behaviour differ (the picker may not re-emit) | a separate test: target arrives while the screen is visible |
| Phone "send to watch" (both messages) | the phone sends `/aim/set_target` **and then** `/app/open` carrying the same target (`mobile/.../MainActivity.kt:198-228`) | `initialTarget` set → 4 (POLARIS) for equatorial targets | combined interplay of the rows above | end-to-end test with both messages |

Separately, `WearBridge` uses `MutableSharedFlow(replay = 0)`, and `MainActivity` emits in `onCreate` before
`setContent`, so cold-start requests can be dropped (`wear/.../datalayer/WearBridge.kt:33-45`;
`wear/.../MainActivity.kt:135`).

Revision 1 said "every phone-sent equatorial/star target becomes Polaris". That overstated a bug that is path- and
ordering-dependent, as tabled above.

### 17.2 Phone (production)

- `ArScreen` draws a static crosshair `Reticle` (`ArScreen.kt:853`), star points, labels, constellation lines and
  asterisms, and an info panel.
- **There is no target guidance: no arrow, no phase, no lock and no haptics (MISSING).**
- "Set target" sends the **reticle's own RA/Dec**, labelled with the nearest star's name, to the watch
  (`ArScreen.kt:737-766`, `ArRoute :166-186`).
- The nearest object is the pure minimum separation among projected stars (`ArScreen.kt:1489-1491`).

### 17.3 Mode classification

| Mode | Used? |
|---|---|
| sensor-only | **always**: watch Aim/Identify, phone AR/Identify |
| sensor + optical correction | never (MISSING) |
| camera-identified | never (MISSING) |
| hybrid | never |

**UX failure points:**
- Phone-sent targets can be overwritten by the watch picker's default (Polaris via `/app/open`, Sun via `/aim/set_target` from Home; §17.1).
- Stale or unknown location renders at (0,0) on the phone.
- The watch has no GPS path, and its location relay is double opt-in, default off (§18).
- Magnetic error is invisible to the Aim user.
- Bodies are off by the ephemeris lag (≈13° for the Moon).
- The phone has no guidance at all.

---

## 18. Phone / Wear OS

| Topic | Repository truth |
|---|---|
| Calculations on the phone | AR overlay, SkyMap, Search and Card ephemerides, visibility/NELM, `IdentifySolver` (constellation) |
| Calculations on the watch | **everything itself**: ephemeris, LST, alt/az, Aim, Identify, Tonight tile. The phone sends only targets, heading and location |
| Catalogs | phone `star.bin` PTSKCAT4 v5, 9,241 records, ≤ 6.5; watch `star.bin` PTSKCAT4 v4, **904 records, ≤ 4.5**. The watch `stars_real.bin` is never opened |
| Protocol | `DATA_LAYER_PROTOCOL_VERSION = 1`; kotlinx JSON with `ignoreUnknownKeys`; `Envelope` unused; a mismatched `v` is dropped silently (`core/common/.../datalayer/BridgeMessages.kt:7-16`, `JsonCodec.kt:14-24`) |
| `/aim/set_target` P→W | sent from WearMenu demo, Card and AR reticle (`mobile/.../MainActivity.kt:198-245`). PARTIAL: can be overwritten by the picker's default (Sun when entered with no `initialTarget`), and the follow-up `/app/open` seeds Polaris for equatorial targets (§17.1). The STAR kind is never sent. `AimSender`/`AimSetTargetBuilder` are DEAD |
| `/app/open` P→W | DONE (same cold-start caveat) |
| `/sensor/heading` P→W | 2 Hz; stale after 2 s on the watch (`wear/.../datalayer/PhoneHeadingBridge.kt:16`). **Ineffective** (§14) |
| `/location/request_one` ↔ `/location/response_one` | PARTIAL. Needs watch `usePhoneFallback` **and** phone `shareLocationWithWatch` (both default false, `core/location/.../prefs/LocationPrefs.kt:67,79`), and the phone `MainActivity` must be STARTED (`mobile/.../MainActivity.kt:283-292`). The watch times out after 5 s |
| `/location/last_fix` DataItem | receiver only; **no writer** (DEAD) |
| `/tile/tonight/push_model` W→P | DONE (mirror; default off). The phone mirror preview activity is **not in the manifest** (BROKEN) |
| `/tile/tonight/open`, `/card/open`, `/identify/result`, `/aim/lock_event`, `/ack` | DEAD or SCAFFOLD (no sender, or no handler, or no manifest filter). The watch ACKs before decoding; `MobileBridge.send` fabricates `Ack(ok = true)` (`mobile/.../datalayer/MobileBridge.kt:69-116`) |
| Watch location | `DefaultLocationOrchestrator(fused = null, …)`: **no GPS on the watch** (`wear/.../MainActivity.kt:120-126`). Manual entry or phone relay only. With defaults, Aim shows NO_LOCATION |
| Stale state | heading TTL 2 s; remote fix fresh TTL 120 s; orchestrator re-check every 10 s. **Watch Identify keeps the last fix when the source is lost** (`IdentifyViewModel.kt:107-113`) |
| Offline | the watch works fully offline with a manual location |
| Battery | the orientation repository runs whenever the watch `MainActivity` is STARTED, on every screen including Home (`wear/.../MainActivity.kt:137-146`). `TonightTileRefreshWorker.schedule` has no callers (DEAD). Tile freshness 45 min |

**Sources of different astronomical results between phone and watch:**
1. Different catalogs (9,241 / ≤ 6.5 vs 904 / ≤ 4.5).
2. Different identify algorithms.
   - Watch: magnitude-weighted `IdentifySolver`, plus a nearest body with **no distance cap**, which also makes CONST
     unreachable (`IdentifyViewModel.kt:220-242`).
   - Phone: pure nearest separation, no bodies.
3. Different location sources: the watch has no altitude and keeps stale fixes; the phone falls back to (0,0).
4. Declination computed at different cadence and altitude.
5. Different forward axes (by design), filters and remap policies.
6. The Tonight tile: **never given a location** (`TonightTileService.kt:77-82` passes 4 positional arguments, so
   `getLastKnownLocation` keeps its `{ null }` default, `RealTonightProvider.kt:55`). It always shows Moon + Vega.
   The complication and list use the manual point only.
7. The tile uses its own `AstroMath.raDecToAltAz`.
8. No time sync (both use the system clock, so this should agree).

---

## 19. Constellations (current state)

| Kind | Data | Rendering | Status |
|---|---|---|---|
| Names | PTSKCAT4 CST0, 88 (abbreviation + English name) | only the abbreviation reaches the UI (`AstroStarCatalogAdapter.kt:44`, `ArViewModel.kt:309-310`) | PARTIAL |
| Localized names | none (only generic "Созвездие" strings) | — | MISSING |
| Boundaries | `const_v1.bin` (IAU via d3-celestial) | phone SkyMap draws the polygons (`skymap/SkyMapScreen.kt:510-541`, own parser `ConstellationOutlineLoader.kt`); used by identify on both devices | DONE |
| Figure lines | implicit: PTSKCAT4 `LINE_NODE` records grouped by `(cc, pp)` and chained by `ss` (`mobile/.../ar/AstroOverlayModels.kt:66-76`); 837 nodes, 88 constellations, 690 edges (phone only) | phone AR `ConstellationLayer` Canvas, FIGURE mode only (`ArScreen.kt:1097-1146,1511-1520`) | DONE (phone AR) |
| Asterisms | ASTR/APLY/ASTN: 9 (Lyra Triangle; Orion's Belt/Rectangle/Sheaf/Sword/Shield/Club; Big Dipper; Mizar–Alcor) | phone AR, only for the constellation under the reticle; hard-coded Ori/Lyr defaults (`ArScreen.kt:1528-1589,1542-1547`) | PARTIAL (3 / 88) |
| Labels | asterism label star only | — | PARTIAL / MISSING |
| Art | ART0: one `orion_silhouette_v1`; no image asset | translucent `drawRect` (`ArScreen.kt:1133-1144`) | SCAFFOLD |
| Star membership | PTSKCAT4 constellation index (build-time) | — | DONE (PTSKCAT4 only) |
| Watch | none | none (text IAU code only) | MISSING |
| Camera/sky rendering | lines go through the **legacy 56° projector** with per-endpoint culling (a segment vanishes when either end leaves the frustum; no clipping; straight lines in screen space) | `ArScreen.kt:1450-1456` | PARTIAL |
| Inert UI | constellation selector and pro-mode toggles are "shown unconditionally … inert" (`ArScreen.kt:1147-1150`) | — | DEAD UI |

### Future constellation graphics integration points (architectural only)

**Status of the future constellation-graphics work: REQUIREMENTS PENDING.** The product and visual specification will
be supplied separately. This recon makes **no** commitment to a figure data model, line-geometry method, clipping
method or watch rendering architecture.

**Current-architecture facts any future design will meet** (observations, not decisions):
- Figure geometry is encoded *inside* PTSKCAT4 star ids (`cc/pp/ss` + flags). There is no explicit edge list,
  per-edge style or branching.
- PTSKCAT4 records have no HIP, so today's figure endpoints share no identity with PTSKCAT0 or a future
  navigation-star set.
- Two projectors exist (legacy and CAM-2a), and constellation drawing currently uses the legacy one.
- The AR overlay rebuild runs at sensor rate on the main thread.
- The watch ships PTSKCAT4 v4 data with no figures.
- Constellation identity is an index 0..87 with no localisation keys.

**Integration seams already justified by the current architecture:**
1. **Stable star/object identity**: whatever figure data is chosen must reference stars through an identity that
   survives catalog re-packing and is shared with navigation (§7).
2. **Corrected-pose source**: graphics consume the same pose as navigation (`CorrectedPoseSource`, §28), so they
   register with the real sky once optical correction exists.
3. **Common projection interface**: one projector abstraction (today: legacy `Projection.kt` vs CAM-2a
   `projectStars`), replacing the local `projectStarRecord` closure in `ArScreen.calculateOverlay`.
4. **Rendering-layer boundary**: today `OverlayData` / `ScreenLineSegment` → `ConstellationLayer` (phone AR) and
   `ConstellationProjection` / `drawConstellations` (SkyMap). Graphics stay behind such a boundary, so the astronomy
   and navigation layers do not depend on them.
5. **Phone/watch data availability**: the phone has boundaries + figures + 9 asterisms; the watch has boundaries
   only. Any requirement involving the watch must account for that.

---

## 20. Current UI / redesign baseline

### 20.1 Phone (`mobile`, Compose; navigation = `MutableStateFlow<MobileDestination>` + `when`, `MainActivity.kt:106,403-535,674-705`)

| Screen / route | Purpose | Entry | Main deps | Prod/debug | Legacy concerns | Reuse? |
|---|---|---|---|---|---|---|
| Onboarding | disclaimer + permissions | forced first run | `MobileOnboardingPrefs` | PROD | overrides deep-linked Card | reuse logic |
| Home | flat button list | launcher | `CardRepository`, settings | PROD | title "Point-to-Sky Mobile" hard-coded; scaffold-grade | replace |
| SkyMap | zenith-centred azimuthal-equidistant all-sky chart, mag ≤ 6, IAU boundaries | Home | `CatalogRepository`, `ConstellationOutlineLoader`, location, Bortle | PROD | duplicate `const_v1` parser; below-horizon points not culled; mixed refraction | keep VM, replace renderer |
| Search | stars / 4 bodies / constellations → Card | Home | `CatalogRepository`, ephemeris | PROD | no unit test | reuse |
| Card(id) | object details, visibility, share, send to watch | Search, Home, deep link `app://pointtosky/card`, watch `/card/open` | `CardRepository` (in-memory), location, Bortle | PROD | — | reuse |
| Ar | camera AR overlay | Home (if `arEnabled`) | `ArViewModel`, `PtskCatalogLoader`, CameraX, rotation vector | PROD (legacy overlay) + internalDebug HUD | `ArScreen.kt` **1,676 LOC** mixing protocol, camera session, astronomy math (`calculateOverlay :1406-1606`), layout; Detekt-excluded | split |
| WearMenu | demo targets to watch | Home | `DemoAimTargets`, `MobileBridge` | PROD but SCAFFOLD | reuses `time_debug_back` string | replace |
| Settings | toggles + **ungated "Debug tools"** | Home | `MobileSettingsDataStore` | PROD with debug exposed (`SettingsScreen.kt:200-223`) | — | restructure |
| LocationSetup | manual/auto location, share to watch | **Settings → Debug tools** | `LocationPrefs`, `PhoneLocationBridge` | PROD-reachable | user feature buried in debug; only screen handling system back (`:78`) | move |
| TimeDebug, CatalogDebug, CrashLogs | debug | Settings → Debug tools | — | **debug exposed in public** | `RealStarVisibilityDebugProvider` loads every launch (`MainActivity.kt:114`) | hide |
| Policy / PolicyDocument | HTML policy in a WebView (`AndroidView`) | Settings | assets | PROD | — | reuse |
| `TonightPreviewActivity` | watch tile mirror | Settings → Mirror preview | `TonightMirrorStore` | **BROKEN** (not in manifest) | — | replace |
| `TonightOpenActivity` | raw payload dump | none | — | DEAD | — | delete |
| CAM HUD, predicted-star overlay, full report, physical-binding, frame-content, SKY-1 capture | camera R&D | AR screen / report dialog | camera stack | **internalDebug only** (`CameraGeometryDiagnosticsGate.kt:16,22`; manifest `mobile/src/internalDebug/AndroidManifest.xml`) | HUD/overlay code compiled into `main` (dead at runtime in public builds) | keep as dev tools |

Other phone findings:
- **System back finishes the Activity on every screen except LocationSetup**, because there is no back stack.
- A latent bug: `onOpenAr` toasts "disabled" and still navigates (`MainActivity.kt:176-189`).
- The theme is a bare `MaterialTheme {}` with no night or red mode.
- The `values-ru` translation is missing 9 strings, and there are 37 hard-coded `Text("…")` literals.

### 20.2 Watch (`wear`, Wear Compose Material v1, `SwipeDismissableNavHost`)

| Route / surface | Purpose | Prod/debug | Concerns | Reuse? |
|---|---|---|---|---|
| Onboarding | disclaimer | PROD | — | reuse |
| home | chip list incl. **Astro debug, Catalog debug, Sensors debug, Time debug, Crash logs** | PROD, **no gating at all** (no variant source sets) | debug in public release | replace |
| aim (Find) | arrow + phase + haptics | PROD | target override bug; dead star resolver | keep controller, redo UI |
| identify → card | nearest object + card | PROD | unbounded body fallback; hard-coded "Карточка"; no tests | reuse VM after fixes |
| settings | aim/identify/tile/haptics | PROD | identify radius/mag above 5°/5.5 has no effect (`IdentifyViewModel.kt:172`) | reuse |
| location | manual / phone | PROD | uses phone `material3` on the watch | restyle |
| sensors_calibrate | zero offset | PROD | not persisted | promote |
| Tonight tile | top targets | PROD | Tiles v1 API; never gets a location; English names hard-coded | rework |
| Complications (aim status, tonight target) + config activities | — | PROD | config activities hard-coded Russian + phone M3 | restyle |
| `AimScreen()`/`IdentifyScreen()` placeholders, `identifyDestination` | — | DEAD | — | delete |

### 20.3 Redesign boundaries (not appearance)

**State the future UI must represent** (from §17 and the proposed model in §28.3):
- navigation state, target, guidance vector, confidence and lock;
- calibration and attitude quality (magnetic accuracy, optical correction age);
- location quality and freshness;
- camera availability and geometry quality;
- the autodetect pipeline state.

**Keep independent of the redesign** (domain or core interfaces):
- `core/astro-core` (math, prediction, detection, the future matcher and tracker);
- `AimController` / the phase machine, moved to a shared module so the phone can use it;
- `CatalogRepository`;
- the location orchestrator;
- the data-layer protocol;
- camera session/geometry providers.

**Replace**:
- the phone Home, the navigation shell (adopt a back-stack-aware navigator), WearMenu and Settings structure;
- the watch home;
- the tile layout (move to ProtoLayout);
- the AR overlay rendering, but only after extracting `calculateOverlay` into a presenter/domain class.

**Reuse with restyle**: Card, Search, SkyMap view model, watch Aim controller and Identify view model (after fixes),
Onboarding logic, Policy.

**Gate**: all debug screens on both devices behind `internal` flavor / debug build.

---

## 21. Diagnostics

| Signal | Available today | Where |
|---|---|---|
| camera ID, logical flag, declared physical IDs | yes | CAM diagnostic JSON `cam2c` (internalDebug, `CamDiagnosticSnapshotJson.kt`, schema v4) |
| **active physical camera per frame** (`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`) | **no** (never read; the SKY-1 capture callback that could read it exists) | — |
| per-frame `LENS_FOCAL_LENGTH` / `LENS_INTRINSIC_CALIBRATION` / `SCALER_CROP_REGION` / zoom ratio / OIS state from the capture result | **no** | — |
| per-frame `DISTORTION_CORRECTION_MODE` | **no** (never read) | — |
| per-frame `HOT_PIXEL_MODE` / `NOISE_REDUCTION_MODE` / `EDGE_MODE` (optional detector provenance) | **no** (never read) | — |
| per-physical-ID static `LENS_DISTORTION`, active / pre-correction arrays | partial: logical-camera snapshot only (`CameraCharacteristicsSource.kt`; CAM JSON `cam2c.metadata`) | — |
| per-physical-ID `LENS_POSE_ROTATION` / `LENS_POSE_TRANSLATION` / `LENS_POSE_REFERENCE` | **no** (never read) | — |
| frame resolution, crop, rotation, viewport | yes | HUD + JSON `geometry` |
| sensor-to-buffer matrix, class, frame counts | yes | JSON `cam2c.frameTransform` |
| intrinsics (active/buffer fx, fy, cx, cy), quality, source | yes | JSON `calibration`, `cam2c.resolvedBufferK` |
| FOV (h/v) | HUD only | `CameraGeometryDiagnosticFormat.kt:85-111` |
| frame↔pose pair Δ | HUD only | same |
| exposure / ISO / frame duration / AE / AWB | SKY-1 log only | `skylog/SkySessionLog.kt:451-457` |
| **AF mode / AF state / focus distance / lens state** | **no** | — |
| sensor attitude | SKY-1 log (rotation matrix + quaternion) | `SkySessionLog.kt` |
| predicted visible stars | CAM-2b panel (`inputCount`, `visibleCount`); SKY-1 per-frame `predictedStars` | JSON `cam2b`; sky log |
| detected stars | offline loader only | `tools/sky-session-loader` |
| match count, residual, confidence, optical correction, target error | **no** (no matcher) | — |
| FPS / processing latency | watch orientation FPS (`OrientationFrameLogger.kt:63-87`); **no camera pipeline latency** | — |
| magnetic accuracy | watch only | `SensorsDebugScreen` |

**Add during the finishing phase** (not in this recon):
- per-frame Camera2 capture-result fields, correlated to `ImageInfo.timestamp` (PTS-03 probe first; the subset that
  proves useful then goes into the sky log as schema v3):
  - camera identity and timing: `SENSOR_TIMESTAMP`, `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`;
  - lens: `LENS_FOCAL_LENGTH`, `LENS_INTRINSIC_CALIBRATION`, `LENS_FOCUS_DISTANCE`, `LENS_STATE`;
  - focus: `CONTROL_AF_MODE`, `CONTROL_AF_STATE`;
  - framing: `SCALER_CROP_REGION`, zoom ratio where supported;
  - exposure: exposure time, ISO, frame duration;
  - stabilisation (OIS) state where exposed;
- per-stage latency and FPS for the detect→match→track loop;
- detection count and the background σ histogram;
- candidate count before and after each filter;
- match hypothesis count, inliers, RMS residual (px and arcmin), ambiguity ratio;
- correction quaternion and correction age;
- tracker state transitions;
- magnetic-field magnitude and inclination versus WMM (interference flag).

---

## 22. Tests

### 22.1 Inventory (`@Test` counts read from source; execution status from the 2026-09-02 recon and CI)

| Source set | @Test | Kind | Runs in CI today? |
|---|---|---|---|
| `core/astro-core/src/test` | 677 (49 classes) | unit; synthetic-image | **no** (`android-full.yml:53` lists it, but the workflow has not run since 2026-02-13) |
| `core/astro/src/test` | 92 | unit, golden (self-snapshot), scenario | no |
| `core/catalog/src/test` | 99 | unit, binary | **yes** (`android.yml` catalog job) |
| `core/location/src/test` | 16 | unit + Robolectric (1 failing, 1 flaky per `025cf68`/`522b569` messages) | no |
| `core/logging/src/test` | 24 | unit (1 known failing `RedactorTest` case, left unfixed by `ff96609`) | no |
| `core/time/src/test` | 4 | unit + Robolectric | no |
| `core/common` | 0 | — | — |
| `mobile/src/test` | 368 | unit | publicDebug yes (`android-release.yml` tests job) |
| `mobile/src/testInternalDebug` | 391 | unit (CAM-2c, SKY-1) | **no** |
| `mobile/src/testPublicDebug` | 3 | variant boundary | yes |
| `mobile/src/androidTest` + `androidTestInternalDebug` | 37 + 51 | instrumentation (Compose) | assembled only, **never executed** (no emulator job; `docs/SPRINT_STATUS.md:82-83,929`) |
| `wear/src/test` | 28 nominal; **`DefaultAimControllerTest` (17) and `RealTonightProviderTest` (3) are excluded from every Test task** (`wear/build.gradle.kts:132-148`) | unit, Robolectric | publicDebug yes (about 9 effective) |
| `wear/src/androidTest` | 7 | instrumentation | assembled only |
| `wear/sensors/src/test` | 13 | unit (sensor conventions) | no |
| `wear/benchmark` | 5 | macrobenchmark | no |
| `tools/catalog-packer`, `tools/sky-session-loader` | 34, 14 | unit / JVM integration (synthetic sessions) | no |
| `res/skyglow/tests` (Python) | 81 | unit | no |

Classification:
- **No property-based tests.**
- **The only fixture file is `core/astro/src/test/resources/ephem_golden_v1.json`**, and it is self-referential.
- Everything else is in-code synthetic.
- **No recorded real camera frame or session exists anywhere.**

### 22.2 Subsystem map

| Subsystem | Pinned by tests? |
|---|---|
| Coordinate transforms | yes (§8.4); ephemeris **not** against an external reference |
| Projection (legacy + CAM-2a) | yes, extensive |
| Catalog parsing | yes (synthetic bytes); **no shipped-asset golden** |
| Visible-star calculation | magnitude filters yes; horizon/FOV pre-filter MISSING (no code) |
| Camera rotation/crop | yes (`CropScaleTransformTest` 29, `CameraStarProjectionTest` 35, …) |
| Detector | yes (synthetic) |
| Matcher | input DTO only |
| Prior projections | DTO validation only |
| False-positive matching | MISSING |
| Temporal recovery | MISSING |
| Sensor conventions | partial (watch RV repository; phone remap **mirror** only) |
| Phone/watch parity | MISSING |
| UI | thin Compose tests; never executed on a device |

### 22.3 High-risk paths with no (running) tests

- `SimpleEphemerisComputer` accuracy.
- Watch `IdentifyViewModel`; watch `DefaultAimController` (17 tests excluded); `AimScreen` target-picker interplay.
- `WearBridge`, `PhoneLocationBridge`, `PhoneCompassBridge`, `PhoneHeadingOverrideRepository`.
- `OfflineStarResolver`, `RealTonightProvider` (excluded), `TonightTileService` location wiring.
- `CatalogRepository.runSelfTest`.
- `SkyMapViewModel` / `projectBase`; `ConstellationOutlineLoader`.
- Framework `remapCoordinateSystem`.
- The `core:common` codec.
- Both `MainActivity` navigation state machines.

---

## 23. Device validation

**Passing unit tests ≠ verified under the real night sky.** The only recorded device evidence in the repository is
the CAM-2c Pixel 9 topology/matrix observations (§11.4). No star has ever been detected, predicted-and-compared, or
matched on a device in any recorded artifact. Watch Aim, Identify and sensors have no recorded device validation
(nothing in `docs/SPRINT_STATUS.md` or `docs/validation/`).

**Pixel 9 assumptions in the current code:**
- logical rear camera "0" with physical "2,3,4";
- 4080×3072 active array;
- an analysis buffer of 640×480 with rotation 90 — **observed under CameraX 1.3.4 only**; the 1.4.2 value is
  UNVERIFIED ON DEVICE;
- CameraX 1.4.2 matrix `AXIS_ALIGNED_0` (uniform scale ≈ 0.157 plus a symmetric crop);
- calibrated intrinsics blocked by the logical-multi-camera gate;
- per-frame active physical camera: **UNINVESTIGATED** (§10.1);
- AndroidX Test pinned for Pixel 9 / Android 16 compatibility (PR #217).

| Scenario | Current status | Required evidence |
|---|---|---|
| Bright navigation star | NOT TESTED (no nav set, no on-device detection) | SKY-1 session of ≥1 named star; detector finds it; matcher identifies it |
| Multiple bright stars | NOT TESTED | session with ≥ N (TBD BY DEVICE TEST) catalog stars; correct identities; RMS residual |
| Urban sky | NOT TESTED | sessions at Bortle ≥ 7; false-positive rate; no confident false lock |
| Dark sky | NOT TESTED | Bortle ≤ 3 session; detection count cap behaviour |
| Slight defocus | NOT TESTED (AF uncontrolled, unlogged) | sessions with logged focus distance across a sweep |
| Fast pan | NOT TESTED | tracker loss/reacquire timings |
| Portrait | NOT TESTED (activity is portrait-locked; the only orientation in use) | overlay registration error px |
| Landscape | NOT APPLICABLE today (portrait lock) | — |
| Near horizon | NOT TESTED (CAM-2a has no horizon gate; refraction off) | sessions spanning low to high altitude; **residual versus altitude** with and without the chosen refraction policy (§8.3 item 11) |
| Active physical camera per frame (identity) | NOT TESTED | PTS-03 Group A: is `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` reported? stable in a star-mode session? does it switch in low light? do focal length/intrinsics change with it? can each result be matched to its analysis frame? |
| Projection-domain compatibility per physical ID | NOT TESTED (no `ProvenActiveArrayLocal` ever constructed) | PTS-03 Group B: the matrix's source basis (logical vs physical active array); a legal composition with physical intrinsics; frame-content correspondence (printed target, later stars) for every occurring physical ID |
| Distortion state per stream / physical ID | NOT TESTED (mode never read) | PTS-03 Group C: requested/effective `DISTORTION_CORRECTION_MODE` per frame; the metadata basis it implies; **measured** residual distortion in the YUV (never inferred from `FAST`); pre-correction vs active arrays; frame-content correspondence at image edges under each available mode (edge residuals reveal residual or double-corrected distortion) |
| Camera↔device extrinsics per physical ID | NOT TESTED (`LENS_POSE_*` never read) | PTS-03 Group D: reported pose and reference; quaternion convention; comparison with nominal axes and printed-target optical evidence; provenance status |
| CameraX 1.4.2 stream configuration | NOT TESTED (1.3.4 evidence only) | recorded analysis/preview resolution, crop, matrix and stream configuration under 1.4.2 |
| Recovery under wrong magnetic heading | NOT TESTED (no matcher) | deliberately disturbed heading (e.g. tens of degrees, value TBD): optical acquisition still succeeds through the recovery path (§9.1) |
| High-proper-motion reference star | NOT TESTED | residual of a high-PM bright star with and without epoch propagation, or its explicit exclusion (§8.3 item 10) |
| Zenith | NOT TESTED | session at alt > 75°; azimuth singularity behaviour |
| Magnetically disturbed area | NOT TESTED (no interference detection) | session near a steel structure; optical correction recovers the yaw error |
| Loss / reacquisition | NOT TESTED (no tracker) | cover the lens, uncover; time to relock |
| Watch Aim to a phone-sent target | NOT TESTED (code-reading bug, §17.1) | each path separately: `/app/open` with Vega (not replaced by Polaris); `/aim/set_target` entered from Home (not replaced by Sun); `/aim/set_target` while Aim is mounted; phone send-to-watch (both messages) |
| Watch Identify | NOT TESTED | identify Vega/Arcturus at night; no "Moon" when the Moon is far |
| Phone/watch parity | NOT TESTED | same target, same location: alt/az agree within TBD BY DEVICE TEST |

---

## 24. Performance

| Work | Thread / dispatcher | Frequency | Measured? | Notes |
|---|---|---|---|---|
| Legacy overlay `calculateOverlay` | **main** (Compose `remember`) | every rotation event (`SENSOR_DELAY_GAME`) | no | re-filters 9,241 records and runs `raDecToAltAz` on up to 8,404 stars plus a sort, while alt/az only changes at 1 Hz (`ArScreen.kt:582,1462-1483`) |
| CAM-2b reducer (`projectStars` on 200 stars) | **main** (Compose `remember`) | per paired analysis frame (internalDebug) | no | `ArScreen.kt:505-528` |
| Camera metadata analyzer | single-thread executor | per frame | no | cheap (no pixels) |
| Intrinsics/session geometry | analyzer + main | per frame (geometry), once (intrinsics) | no | runs ungated in public builds but is unused there |
| `detectStars` | — (offline) | — | no benchmark | per-pixel interpolation; boxing in `ArrayDeque<Int>` |
| `PtskCat0Catalog.nearby` | — | — | no | linear scan with `acos` per star (41k) |
| `RealStarVisibilityService.select` | IO | once at startup | no | re-parses 706 KB per call |
| `PtskCatalogLoader` | IO/`runBlocking` in `CatalogRepository.loadAstroCatalog` (`CatalogRepository.kt:173-176`) | per instance | no | parsed at least twice on the phone; per call on the watch tile |
| Watch declination | orientation collector | every Aim tick (15 Hz) | no | new `GeomagneticField` each call |
| Watch orientation | sensor thread → flow | 15 Hz, whenever the activity is STARTED | no | runs on Home |
| Data layer | binder/IO | heading 2 Hz | no | — |

**Candidate-set reduction.** The matcher's catalog work scales with the candidate count. Reference sizes: the whole
PTSKCAT0 catalog at mag ≤ 8.0 is 41,487 stars; PTSKCAT4 is 9,241. A navigation set is O(10²) stars (size TBD). Per
state (§9.1), all **estimates, TBD BY DEVICE TEST**:

| State | Candidate source | Order of magnitude (estimate) |
|---|---|---|
| Locked / tracking | gated neighbourhoods around time-stamped predicted positions | a handful to O(10¹) |
| Initial acquisition with a trusted prior | above-horizon nav stars within the FOV cone + attitude margin (≈1/10–1/30 of the sphere for a phone FOV) | O(10¹) |
| Degraded prior | widened cone | O(10¹)–O(10²) |
| Recovery / lost-in-space (no heading trust) | all above-horizon nav stars | O(10²) |

Only the cone-limited states make naive per-frame pair/triangle generation cheap. The recovery state needs a
pattern-index method (e.g. precomputed nav-star pair/triangle invariants), run at a lower cadence. Both require the
missing spatial index (or a nav-set-only scan) and the candidate predictor.

---

## 25. Failure modes

| Failure | Current behaviour | Desired behaviour | Existing protection | Gap | Severity |
|---|---|---|---|---|---|
| No stars detected | n/a (no on-device detection); the overlay keeps showing sensor-only stars | state SEARCHING/UNAVAILABLE with a reason ("no stars visible") | none | detection path + state | P0 |
| Too many sources | n/a; the detector has no cap | cap by brightness + SNR; flag "too bright / light-polluted" | `MAX_PIXEL_COUNT = 5000` per blob only | count cap, SNR | P1 |
| Hot pixels | the detector rejects only blobs < 3 px | temporal persistence / dark map | 3-px minimum | hot-pixel model | P1 |
| City lights / horizon glow | tile-scale background crowns (`star_detection_contract.md:112-129`) | mask below the horizon; crown rejection | none | horizon mask | P1 |
| Moon | Moon penalty uses a 1-day-lagged Moon (≈13° wrong) | correct ephemeris; mask the Moon disc | `limitingMagnitudeAt` penalty | ephemeris fix, mask | P0 (ephemeris) |
| Cloud | n/a | confidence decay, LOW_CONFIDENCE, keep the last correction with an age | none | tracker | P1 |
| Wrong exposure | production uses AE | night exposure policy | SKY-1 manual exposure (debug) | production policy | P1 |
| Blur | no shape metric | reject elongated sources; warn | none | shape metrics | P2 |
| Autofocus failure | AF uncontrolled, unlogged | focus policy + logged focus distance | none | AF control | P0 |
| Wrong camera (logical switch) | not detected (the active-physical-ID capture result is never read; its availability on Pixel 9 is uninvestigated) | read the per-frame active physical ID if the HAL reports it; otherwise pin binding/zoom, and as a last resort detect scale change in the solve | zoom pinned only in the debug override | PTS-03 probe, then the PTS-09 decision | P0 |
| Incorrect intrinsics | Pixel 9 → unprojectable; legacy 56° elsewhere; labelled CALIBRATED loosely | geometry from truthful per-frame metadata (A, only with identity, a projection-domain/content proof **and** a known distortion state) or physical binding (B, also needing the domain proof and distortion state); a scale-tolerant solve (C) only as fallback; honest labels | CAM-2c gates | PTS-03 → PTS-09 | P0 |
| Distortion double-applied or ignored | mode unknown; distortion never modelled; the intrinsics basis is checked only by pre==active equality | per stream: if the YUV is measured as camera-corrected, do not re-apply `LENS_DISTORTION`; never infer "corrected" from `FAST`; where distortion remains, model it in the pre-correction basis or reject calibrated projection | partial (pre==active guard) | PTS-03 Group C → PTS-09 | P0 |
| Wrong camera↔device boresight | nominal axes assumed; platform pose never read | per-physical-ID extrinsic with provenance (platform pose verified optically, or nominal, or optically refined); never stored in display frames | none | PTS-03 Group D → PTS-09/PTS-18 | P1 |
| Proper motion ignored | epoch-2000 positions used against 2026 frames | epoch-propagated reference positions, or explicit exclusion of high-PM nav stars | none | proper-motion policy (§8.3 item 10) | P1 |
| Low-altitude refraction | geometric (refraction-off) prediction; a matcher would absorb the displacement as attitude/intrinsics error | apparent-altitude correction (A) or a minimum-altitude exclusion (B), explicit and tested | none | refraction policy (§8.3 item 11) | P1 |
| Wrong location | phone renders at (0,0); watch NO_LOCATION | block with UNAVAILABLE(location) | watch gate only | phone gate | P1 |
| Stale location | watch Identify keeps stale fixes; TTL elsewhere | age displayed; degrade | TTLs (120 s remote) | consistency | P2 |
| Wrong time | system clock trusted | sanity check vs GNSS time (optional) | none | — | P3 |
| Magnetic interference | invisible; Aim confidence uses azimuth variance only | detect field magnitude/inclination anomaly; the candidate predictor **fails open** into the recovery search so optical correction can override (§9.1) | accuracy status (watch Identify only) | detection + fail-open candidates + optical | P1 |
| Sensor drift | none | optical correction bounds it | none | — | P1 |
| Insufficient navigation stars | n/a | report "too few reference stars"; widen to the full catalog | none | — | P1 |
| Matcher ambiguity | n/a | ambiguity ratio gate; never lock on ties | contract only | matcher | P0 |
| False lock | n/a | verification by projecting all candidates; min inliers; residual bound; consistency with the sensor prior | contract forbids prior-based association | matcher/verifier | P0 |
| Prior poisoning | n/a | priors time-stamped; tracker re-verifies; periodic full solve | contract warning | tracker | P1 |
| Rapid motion | n/a (overlay follows the rotation vector) | gyro-propagated prediction; drop to SEARCHING when blurred | none | tracker | P1 |
| App resume | camera rebinds; intrinsics re-resolve per session | restore the last correction with an age, re-verify | session lifecycle code | correction persistence | P2 |
| Device rotation | portrait lock (phone); watch remap is a manual setting | keep the lock; or support landscape via rotationDegrees (already modelled) | CAM-2a handles rotation | watch remap auto | P2 |
| Phone/watch disconnect | heading TTL 2 s; location relay timeout 5 s; targets fire-and-forget; fabricated ACK | visible link state; queued target with replay | TTLs | real ACK, replayed target | P1 |

---

## 26. Keep / rework / retire

### KEEP (sound; do not rewrite)

- `core/astro-core` CAM-1 geometry: `CameraFrameMetadata`, `FrameRotationPairing`, `RotationSampleHistory`,
  `CropScaleTransform`, `PixelGeometry`, `CameraSessionGeometry(+Result)`.
- CAM-2a prediction (`CameraStarPredictor`, `PinholeProjectionModel` incl. `unprojectToCameraRay`, the transform
  chain, `PredictedStarProjection` classification).
- The detector design (`StarDetector`, `TiledBackground`, `LumaFrame`) as the base for on-device detection. It is
  hardened, not replaced, in PTS-12; its pixel-coordinate and centroid contracts are preserved.
- Matcher input contract types (`StarMatcherInput`, `StarCatalogQuery`, `AnalysisBufferScale`) and their documented
  rules.
- The SKY-1 session-log model, codec and replay, plus `tools/sky-session-loader`.
- The PTSKCAT0 pipeline (packer + reader) and `PtskCat0StarCatalogQuery`.
- `BinaryConstellationBoundaries`.
- Sidereal time, `raDecToAltAz`/`altAzToRaDec`, `aimDelta`, `Pointing`.
- The watch `DefaultAimController` phase machine and haptics policy.
- `:wear:sensors` repositories.
- `DefaultLocationOrchestrator`.
- The CAM diagnostic export (internalDebug).
- `SkyCaptureExposure` manual exposure + per-frame validation.

### REWORK (conceptually right; targeted finishing)

- `SimpleEphemerisComputer`: epoch fix; external-reference tests; optional topocentric Moon.
- Coordinates: add J2000 → of-date precession at a single seam.
- `PtskCat0Catalog`: spatial index (Dec bands / HEALPix-lite) for `nearby`.
- `PredictedStarCatalogAdapter`: replace whole-sky brightest-200 with the candidate predictor (horizon, staged
  fail-open cone, nav set, epoch and refraction policy).
- `HygRealCatalogParser` / PTSKCAT0 writer (or the nav-set generator): carry proper motion where the PM policy needs it.
- SKY-1 capture callback (`SkyCaptureExposure.kt`): extend it to read the per-frame metadata listed in §21, including
  `DISTORTION_CORRECTION_MODE`.
- `CameraCharacteristicsSource`: record per-physical-ID static evidence (`LENS_POSE_*`, distortion modes, both
  arrays), as evidence first (PTS-03).
- `SkySessionLog.kt:696-699` KDoc: it says `LENS_DISTORTION` is "not read anywhere", which is stale. Correct it when
  that file is next touched.
- `CameraStarPredictor`: optional horizon classification.
- `CameraPreview` (production): luma tap, analysis resolution policy, AF/AE policy, optional ViewPort.
- `AnalysisBufferIntrinsicsResolver`: honest quality labels; a Pixel 9 strategy chosen **after** the PTS-03 probe (§28.2).
- `CameraPreview.kt:51-59` KDoc: it says there is "no per-frame physical-camera-identity callback". Narrow this to the
  CameraX types once PTS-03 reports (a documentation fix, outside this recon's scope).
- Phone `RotationFrame`: accuracy plumbing; optional gyro/game-RV fusion.
- Watch: `AimScreen` target arbitration; `WearBridge` replay; `IdentifyViewModel` body cap + stale fix; the
  `TonightTileService` location argument; `OfflineStarResolver` (point at a shipped catalog);
  `PhoneHeadingOverrideRepository` (override `forward`, fix the sign); `GeomagneticFieldDeclinationProvider` cache.
- `ArScreen.calculateOverlay`: move to a domain/presenter class; compute alt/az at 1 Hz, project at sensor rate,
  off the main thread.
- `CatalogRepository.runSelfTest`: use PTSKCAT4 ids or HIP via PTSKCAT0.
- Two Bortle → NELM tables: unify.
- CI: re-enable `android-full`; run pure-JVM suites on PRs; un-exclude `DefaultAimControllerTest` with virtual time.
- Watch debug screens and phone Settings → Debug tools: gate behind `internal`/debug.

### REMOVE / RETIRE (conservative; confirm with the owner first)

- `fix-volatile-running.diff`: `git apply --check` fails; the target fields no longer exist.
- `core/time/.../JulianDate.kt` and `tools/ephem-cli/.../core/time/JulianDate.kt`: dead duplicates.
- `math/Vector3.vectorFromSphericalDegrees`, `java/.../math/VectorMath.kt` (`Vec3`/`Mat3`): unused, conflicting
  convention.
- PTSKSTAR (`BinaryCatalogHeader`, packer default `--format=ptskstar`); the unreachable `ConstellationCommand`.
- The wear `stars_real.bin` (unused 183 KB), *unless* PTS-21 adopts it for watch parity.
- `TonightOpenActivity`; the wear placeholder `AimScreen()`/`IdentifyScreen()`; `identifyDestination`; `AimSender`,
  `AimSetTargetBuilder`, `Envelope`, `DlAcks`, `/ack`, `/location/last_fix`, `/tile/tonight/open` (or implement them).
- `TonightTileRefreshWorker` (no scheduler), or wire it.
- The inert AR constellation-selector / pro-mode toggles (`ArScreen.kt:1147-1150`).
- `ConstellationOutlineLoader`, merged into `BinaryConstellationBoundaries` (one parser).
- The legacy `Projection.kt` 56° projector: **retire only after** the corrected-pose projector replaces it.

---

## 27. Critical gaps

Format: ID · problem · evidence · affected paths · user impact · technical risk · direction · dependencies ·
evidence required.

### P0 — blocks usable core navigation

**G-01 · No matcher / optical attitude solve.**
- **Evidence:** `match/` holds 3 contract files; `docs/star_matcher_input_contract.md:3-5`; no branch has any.
- **Affected:** all of autodetect.
- **User impact:** no camera identification; magnetic error goes uncorrected.
- **Risk:** high (algorithmic; false locks).
- **Direction:** a hypothesis matcher with two paths:
  - (i) a constrained fast path over the cone-limited nav-star candidates;
  - (ii) a **recovery path** that does not depend on magnetic heading (pattern invariants over the above-horizon nav
    set, §9.1).
  - Both use angular invariants via `cameraRayFor`, tolerances from `AnalysisBufferScale`, verification by projection
    and a Wahba/SVD attitude.
  - It must tolerate scale uncertainty only to the degree the chosen camera-geometry strategy requires (§28.2).
  - Its output is frame-labelled: `R_Wtrue_from_Cbuf_optical` (§14.1).
- **Depends on:** G-03, G-04, G-05, G-06, G-25, G-26, G-27.
- **Evidence:** synthetic + recorded-session metrics; device sessions.

**G-02 · No on-device pixel path / detector never run on device.**
- **Evidence:** `CameraFrameAnalyzer.kt:8-41`; `detectStars` call sites (§12).
- **Affected:** `mobile` camera.
- **User impact:** autodetect impossible.
- **Risk:** medium (allocation, latency).
- **Direction:** a stride-aware Y-plane → `LumaFrame` tap in the production analyzer behind a flag; buffer reuse;
  run `detectStars` on the analyzer thread at a throttled rate.
- **Depends on:** —.
- **Evidence:** per-frame latency on a Pixel 9; detection counts on real sky.

**G-03 · Camera model not trustworthy on the Pixel 9.**
- **Evidence:** §10.1–10.4, §11.3–11.4.
  - Calibrated intrinsics are blocked for logical multi-cameras.
  - **Per-frame active physical camera is UNINVESTIGATED**: `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` is never read,
    although the SKY-1 session capture callback that could read it exists.
  - **The projection domain is unresolved, independently of identity.** The 1.4.2 matrix is source-traced to the
    opened (logical) camera's active array (`cam_2c_sensor_to_buffer_domain_recon.md` §2.3). Physical intrinsics
    cannot be composed with it until domain compatibility and frame-content correspondence are proven
    (`SensorToBufferDomainProof`).
  - **The distortion-correction state is unknown** (moved here from G-18 in revision 4):
    - `DISTORTION_CORRECTION_MODE` is never read or set;
    - the mode fixes the metadata coordinate basis (pre-correction for `OFF`, active array otherwise). The actual
      pixel correction is HAL-dependent: `FAST` may be effectively `OFF`, so it must be measured;
    - `LENS_INTRINSIC_CALIBRATION` is defined in pre-correction coordinates;
    - so neither the pixel domain nor the correct intrinsics basis is established, and double correction cannot be
      ruled out.
  - **The camera↔device extrinsics are unaudited**: `LENS_POSE_ROTATION` / `LENS_POSE_TRANSLATION` /
    `LENS_POSE_REFERENCE` are never read, and the code relies on nominal axes.
  - AF has never been set.
  - The 1.4.2 analysis resolution is unverified (640×480 is 1.3.4 evidence).
  - There is no ViewPort.
- **Affected:** intrinsics, AF, resolution, provenance.
- **User impact:** projections are unusable or wrong.
- **Risk:** high (device-specific).
- **Direction:**
  - first **measure** with the Pixel 9 experiment (PTS-03): identity (A), projection domain (B), distortion state
    (C) and extrinsics (D), as independent proofs;
  - then choose among:
    - A (truthful per-frame physical-camera geometry, only if A, B and C hold);
    - B (explicit physical binding, which still needs B and C);
    - C (approximate logical intrinsics + scale-tolerant solve, **fallback only**);
  - explicit AF/AE policy, logged;
  - an analysis-resolution policy from measured 1.4.2 options;
  - honest quality labels.
- **Depends on:** —.
- **Evidence:** a PTS-03 report answering all four question groups (§29); Pixel 9 sessions with focus logs.

**G-04 · No visible navigation-star candidate predictor with uncertainty and recovery.**
- **Evidence:** §7, §9.
- **Affected:** CAM-2b adapter, SKY-1, the future matcher.
- **User impact:** a matcher would be slow, ambiguous, or blind to a wrong heading.
- **Risk:** medium. A hard magnetometer gate would make optical recovery impossible.
- **Direction:** a navigation-star data file plus a pure-JVM `VisibleCandidatePredictor(time, location, attitude
  prior with uncertainty + trust state, camera model with uncertainty)`:
  - above-horizon (with margin);
  - the epoch, proper-motion and refraction policy applied (G-06, G-25, G-26);
  - a **staged fail-open** region (trusted → narrow cone; degraded → widened cone; interference/lost →
    nav-star-wide / hemisphere recovery set; locked → time-stamped prior fast path with periodic wide
    re-verification);
  - a spatial index for PTSKCAT0.
- **Depends on:** G-06, G-25.
- **Evidence:** unit tests (true stars ⊂ candidates for every state, including a deliberately wrong heading);
  replay of real sessions.

**G-05 · No real night-sky dataset.**
- **Evidence:** no session or frame in the repository (§22.1); no doc reports a real run.
- **Affected:** all tuning.
- **User impact:** unknown performance.
- **Risk:** high.
- **Direction:** two evidence stages.
  - **Early real-sky capture** (detector evidence). Needs luma capture, AF/exposure/focus metadata, and trustworthy
    timestamps, location and time. It does **not** need final projectable intrinsics.
  - **Calibrated optical sessions** (residuals, recall, matcher, attitude). These come after the camera-geometry
    strategy is established.
  - In both stages, store sessions outside git and commit a small fixture subset.
- **Depends on:** early stage: G-02 + AF/metadata logging; calibrated stage: G-03.
- **Evidence:** `sky-session-loader` reports.

**G-06 · Astronomy truth defects.**
- **Evidence:** §8.3 items 1–3: ephemeris −1 day; no precession; self-referential golden.
- **Affected:** all body positions on both devices; all stars (≈0.36°).
- **User impact:** the Moon is ≈13° wrong; the Sun and planets about 1°; star overlays carry a systematic offset.
- **Risk:** low (fixes are well understood).
- **Direction:**
  - fix the epoch;
  - reference tests with **explicit frame, apparent/geometric, observer and time-scale conventions** that match the
    model under test (§8.3 "Ephemeris reference-test conventions");
  - precession applied in the same documented pipeline as proper motion (G-25).
- **Depends on:** —.
- **Evidence:** unit tests against convention-matched published values.

**G-27 · Detector not hardened for real sky / no source-quality contract for the matcher.**
- **Evidence:** §12:
  - no detection-count cap;
  - no SNR metric;
  - no FWHM/shape or elongation metric;
  - a 3-pixel minimum is the only hot-pixel handling;
  - no centroid uncertainty (`StarMatcherInput.kt:49-53`);
  - `ArrayDeque<Int>` boxing and per-frame allocations;
  - synthetic-only validation.

  The target architecture says "capped + SNR", but revision 2 scheduled no PR for it.
- **Affected:** matcher tolerances, false-lock risk, performance.
- **User impact:** an unbounded or noisy source list feeds the matcher.
- **Risk:** medium.
- **Direction:** evidence-driven hardening on the early real-sky fixtures (PTS-12):
  - a bounded deterministic top-N;
  - a positional/source-quality metric;
  - SNR / background significance;
  - shape metrics, if the data shows the need;
  - a stated treatment for saturated and edge sources;
  - evidence-based hot-pixel suppression;
  - allocation fixes that traces show matter.

  Existing pixel-coordinate and centroid contracts are preserved. A sophisticated PSF fitter is not required unless
  the data justifies one.
- **Depends on:** G-02, the early stage of G-05.
- **Evidence:** a real-sky fixture report; a known-star retention rate (TBD BY DEVICE TEST).

### P1 — required for reliable release

- **G-07 · No temporal tracker / frame-correct optical fusion.**
  - Evidence: §15.
  - Direction:
    - frame-labelled state per §14.1 (world-fixed and body-fixed terms kept separate; no frame-ambiguous constant
      quaternion);
    - age/decay;
    - rotation-delta-propagated, time-stamped `priorProjections`;
    - re-verification;
    - reacquisition through the recovery path;
    - the §14.1 invariant tests.
- **G-08 · No confidence / false-lock protection.** No code. Direction: inlier count, residual, ambiguity ratio,
  consistency checks; thresholds TBD BY DEVICE TEST. Sensor-prior consistency may raise confidence but must not veto
  a well-verified solution, since the prior can be the thing that is wrong.
- **G-09 · Watch navigation bugs.** Evidence §17.1, §18:
  - Aim target overwrite by the picker default, which is path-dependent:
    - `/app/open` with an equatorial/star target → Polaris (index 4);
    - `/aim/set_target` entered from Home → Sun (index 0);
    - an already-mounted screen has different behaviour;
  - cold-start request drop;
  - unbounded body fallback in Identify (no angular cap);
  - stale fix in Identify;
  - `OfflineStarResolver` asset mismatch (`stars_V1.bin` not shipped);
  - Tonight tile never gets a location;
  - phone heading override does not update `forward`;
  - Aim controller tests excluded.
- **G-10 · Phone has no navigation guidance and renders at (0,0) without a location.** Evidence §17.2, §8.3 item 8.
- **G-11 · CI does not run the core suites.** `android-full` has been dead since 2026-02-13. astro-core (677),
  core:astro, wear:sensors, tools and internalDebug tests do not run on PRs. Wear `DefaultAimControllerTest` is
  excluded.
- **G-12 · Magnetic interference invisible.** Evidence §14. Direction: field-magnitude/inclination check vs WMM;
  surface it in the state; it drives the candidate predictor into recovery mode (G-04).
- **G-13 · Overlay computation on the main thread at sensor rate.** Evidence §24.
- **G-14 · Phone/watch catalog and algorithm divergence.** Evidence §18.
- **G-25 · No proper-motion policy for navigation references.**
  - **Evidence:** HYG `pmra`/`pmdec` are dropped at pack time (`HygRealCatalogParser.kt:38-66`); runtime positions
    are epoch 2000 (§6.2, §8.3 item 10).
  - **Direction:** verify the HYG `pmra` convention; propagate nav-reference positions to the observation epoch in
    the same documented pipeline as precession. Acceptable v1: explicitly exclude nav stars whose accumulated
    displacement exceeds a threshold that is **TBD BY ACCURACY BUDGET / DEVICE TEST**, with tests.
  - **Depends on:** G-06.
  - Must land before matcher tolerances are calibrated (PTS-15/PTS-17).
- **G-26 · No refraction policy for optical prediction.**
  - **Evidence:** §8.2, §8.3 item 11 (`applyRefraction = false` in CAM-2a and almost every caller).
  - **Direction:** v1 policy A (apparent-altitude correction with documented assumptions) or B (minimum-altitude
    exclusion, threshold **TBD BY ACCURACY BUDGET / DEVICE TEST**); tests and device evidence of residual versus
    altitude.
  - Must land before matcher tolerances are calibrated.

### P2 — quality / performance / UX

- **G-15** Debug screens exposed in public builds (both devices).
- **G-16** `ArScreen.kt` monolith; no phone back stack.
- **G-17** Duplicate parsers, tables and math (two `const_v1` parsers, two NELM tables, two alt/az implementations,
  three `JulianDate` copies).
- **G-18** **Residual** lens-model refinement after the distortion-mode / coordinate-basis truth (now part of G-03)
  is established; rolling shutter; start-of-exposure pose pairing.
- **G-19** Inconsistent refraction usage in the SkyMap (UI rendering; distinct from the optical policy G-26).
- **G-20** Missing translations and hard-coded strings.

### P3 — later

- **G-21** Constellation graphics: **REQUIREMENTS PENDING**. The product and visual specification will be supplied
  separately; only the §19 integration seams are recorded here.
- **G-22** Global light-pollution grid (one 10° tile today).
- **G-23** Topocentric Moon, more planets (Mercury, Venus, Mars absent: `SimpleEphemerisComputer.kt:15-20`), DSOs.
- **G-24** Tiles → ProtoLayout migration.

---

## 28. Recommended target architecture (smallest path to Goal 1)

### 28.1 Pipeline (adapting existing components)

Ordered truth layers. Each layer consumes only the one above it plus its own declared inputs. Frames use the §14.1
notation.

```text
1. Camera metadata truth                    EXISTING CameraFrameMetadata, pairing, CropScaleTransform, intrinsics
   (per analysis frame, per physical id)    resolver + NEW per-frame Camera2 capture-result join (active physical ID,
                                            DISTORTION_CORRECTION_MODE, focal length, intrinsics, focus, crop/zoom,
                                            exposure) keyed on SENSOR_TIMESTAMP == ImageInfo.timestamp, plus four
                                            independent proofs (§28.2): A identity, B projection domain, C distortion
                                            state / pixel coordinate space, D camera↔device extrinsic provenance
                                            (LENS_POSE_*). Honest geometry quality + uncertainty
        ↓
2. Astronomy epoch / proper-motion /        EXISTING time, LST, RA/Dec↔Alt/Az + NEW single documented pipeline:
   precession / refraction truth            catalog epoch → observation epoch (proper motion per the chosen policy)
                                            → precession J2000→date → topocentric Wtrue directions → refraction
                                            policy (apparent-altitude correction OR minimum-altitude exclusion)
        ↓
3. Candidate prediction with uncertainty    NEW VisibleCandidatePredictor over the NEW nav-star set (+ optional
                                            PTSKCAT0 cone via EXISTING StarCatalogQuery + NEW spatial index);
                                            staged fail-open regions (§9.1); the prior is
                                            R_Wtrue_from_Cbuf_sensorPred (§14.1) with its uncertainty
        ↓
4. Detector                                 EXISTING detectStars on a NEW production luma tap, HARDENED on real-sky
                                            fixtures (PTS-12): bounded top-N, SNR, source-quality/uncertainty
                                            contract
        ↓
5. Matcher                                  NEW; StarMatcherInput (EXISTING contract, extended with the source-quality
                                            contract) → hypotheses (fast path and recovery path) → verification
        ↓
6. Optical attitude                         NEW: R_Wtrue_from_Cbuf_optical, inliers, residual, ambiguity (+ scale
                                            estimate only if geometry strategy C is in force)
        ↓
7. Temporal tracker / frame-correct fusion  NEW: §28.3 machine A; frame-labelled state (§14.1): world-fixed and
                                            body-fixed correction terms kept separate, age/decay, rotation-delta
                                            propagation, periodic wide re-verification
        ↓
8. Corrected poses (typed, §28.2)           NEW CorrectedCameraPose  = R_Wtrue_from_Cbuf (+ timestamp, physical id,
                                                                       geometry + extrinsic provenance)
                                                CorrectedDevicePose  = R_Wtrue_from_S    (+ timestamp, provenance)
                                            (display-oriented poses are derived on demand through the presentation
                                            transform R_S_from_Ddisp; never stored as a separate truth)
        ↓
9. Navigation / overlay                     camera overlay ← CorrectedCameraPose; pointing/guidance ←
                                            CorrectedDevicePose (or the camera boresight, explicitly chosen);
                                            EXISTING AimController/phase machine → moved to a shared module
```

**Recovery invariant.** Layer 3 always has a path that does not depend on a correct magnetic heading. A wrong
magnetometer prior may slow acquisition, but it must never exclude the true solution.

**Frame invariant.** No layer multiplies or divides attitudes whose source and destination frames differ (§14.1).
Every attitude crossing a layer boundary carries its frame pair in its type or name.

### 28.2 Ownership and interfaces

| Layer | Owns | Must not know about |
|---|---|---|
| Camera metadata truth (`camera/*`, mobile camera session) | per-frame geometry + provenance + domain proof + quality/uncertainty, pairing, crop/rotation, luma tap | catalog, matching |
| Astronomy truth (`core/astro-core` time/transform/ephem + epoch/PM/precession/refraction) | JD, LST, `Wtrue` directions per the declared policy, bodies | camera, UI |
| Candidate prediction (new, astro-core) | candidate sets per trust state + time-stamped priors | pixels |
| Detection (`camera/detect`) | `DetectedSource[]` per frame + source-quality contract | catalog, pose |
| Matching (new, astro-core) | correspondences, `R_Wtrue_from_Cbuf_optical`, confidence; **stateless per call** | UI, Android |
| Tracking (new, astro-core) | state machine, frame-labelled correction terms, decay, reacquire policy, re-verification cadence | Android |
| Camera extrinsics (new, camera metadata + tracker) | `R_Cphys_from_S(id)` per physical ID with its provenance status (§14.1). **The only body-fixed camera↔device transform** | display rotation, UI |
| Presentation transforms (existing) | `R_S_from_Ddisp` (display remap), `R_Cbuf_from_Cdisp` (`rotationDegrees`) | estimation; they are known, never estimated |
| Corrected poses (new, astro-core types) | `CorrectedCameraPose` (`Wtrue ← Cbuf`) and `CorrectedDevicePose` (`Wtrue ← S`) as **separate types**, related only through the physical `R_S_from_Cphys(id) · R_Cphys_from_Cbuf(id)` | UI |
| Navigation domain (shared) | target, guidance vector, phase/lock; declares which pose it consumes | camera internals |
| UI (phone / watch) | rendering of navigation + autodetect state | math |

**Corrected-pose boundary.** There is no untyped generic "pose" whose frame is implicit:
- The phone **camera overlay** needs `CorrectedCameraPose` (`Wtrue ← Cbuf`), because it projects through the pinhole
  model and `CropScaleTransform`.
- **General navigation / pointing** needs a device or pointing pose. Examples are the phone's camera boresight (optical
  +Z) and the watch's device +Y. It consumes `CorrectedDevicePose` (`Wtrue ← S`, the **physical** device frame) or an
  explicitly named boresight derived from the camera pose. A display-oriented view (`Ddisp`) is derived through the
  presentation remap when the UI needs it.
- The two poses are related **only** through the physical, body-fixed path `R_S_from_Cphys(id) · R_Cphys_from_Cbuf(id)`
  (§14.1). The presentation transforms (`R_S_from_Ddisp`, `R_Cbuf_from_Cdisp`) are not part of that relationship.
  Revision 3 described the whole `R_D_from_Cbuf` chain as body-fixed; that was wrong, because it contains presentation
  terms.
- The watch has its own `S` and no camera, so any phone-derived correction transferred to it is an open question
  (§31).

**Pixel 9 camera-geometry strategy: decided after PTS-03, not before.**

PTS-03 answers four **independent** question groups: A identity, B projection domain, C distortion state,
D camera↔device extrinsics.
- Knowing the physical camera ID does not prove B, C or D.
- A valid sensor-to-buffer matrix does not prove C or D.
- A usable `LENS_POSE_ROTATION` does not prove the pixel-domain mapping.

*Group A, physical-frame identity:*
1. Which physical camera produced each frame (`LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID`)?
2. Is it stable during a star-mode session? Does it switch in low light?
3. Can `CaptureResult` and `ImageProxy` be joined reliably (`SENSOR_TIMESTAMP` == `ImageInfo.timestamp`)?
4. Are focal length and intrinsics dynamic when the physical sensor changes?

*Group B, projection-domain compatibility (per physical ID):*
1. What source coordinate system does the delivered `sensorToBufferTransformMatrix` use for that frame?
2. Does it correspond to the **logical** active array (as `cam_2c_sensor_to_buffer_domain_recon.md` §2.3 predicts) or
   to the **active physical** sensor's active array?
3. Can that physical camera's `LENS_INTRINSIC_CALIBRATION` (and active / pre-correction arrays) be legally composed
   with the matrix, i.e. is there a proven map between the two bases?
4. Does the predicted mapped geometry correspond to **actual frame content**? Use the existing CAM-2c frame-content
   correspondence tooling (`FrameContentTarget`, `FrameContentCorrespondenceScreen`, internalDebug) and, later,
   calibrated star sessions.
5. If the active physical camera switches, does the relationship hold for **each** physical ID?

*Group C, distortion state and pixel coordinate space (per stream and physical ID):*
1. What is the requested and the effective `DISTORTION_CORRECTION_MODE` per frame (`OFF` / `FAST` / `HIGH_QUALITY`;
   on supporting devices correction is on by default)? Is the control supported at all?
2. Which sensor coordinate system does the metadata use?
   - With mode `OFF`, Camera2 coordinates use `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`.
   - With any other mode (`FAST` / `HIGH_QUALITY`), they use `SENSOR_INFO_ACTIVE_ARRAY_SIZE`.
   - This is the metadata contract only. It says nothing about how much correction the pixels received.
   - Also record what the pre-correction vs active arrays are.
3. Does **measurable** lens distortion remain in the delivered analysis YUV?
   - Correction applies to processed outputs such as YUV/Y8/JPEG, not to RAW.
   - `FAST` may be effectively `OFF`, so this must be measured, not inferred from the mode.
3a. Therefore, is an **application-side distortion model** required for this stream?
4. `LENS_INTRINSIC_CALIBRATION` is defined in **pre-correction** active-array coordinates. Which basis must it be
   transformed into to describe the delivered pixels, and is that transform known?
5. Is the sensor-to-buffer matrix's source domain consistent with the mode-dependent basis?
6. For every tested stream / physical camera, establish the chain:
   `distortion mode → calibration source space → YUV corrected? → active/pre-correction relationship → matrix domain →
   final pixel coordinate space`.

*Group D, camera↔device extrinsics (per physical ID):*
1. Are `LENS_POSE_ROTATION`, `LENS_POSE_TRANSLATION` and `LENS_POSE_REFERENCE` reported?
2. What are the quaternion convention and direction (Android sensor frame `S` → camera-aligned `Cphys`)?
3. If the reference is `UNDEFINED`, the pose is not calibrated truth.
4. When a usable pose exists, compare it with PointToSky's nominal axes and with optical evidence (printed target,
   later star solutions), per physical ID.
5. Classify the result per physical ID: `CALIBRATED_PLATFORM_POSE`, `PLATFORM_POSE_PRESENT_BUT_UNVERIFIED`,
   `PLATFORM_POSE_UNUSABLE`, `NOMINAL_FALLBACK` or `OPTICALLY_REFINED` (§14.1). The recon does not pre-decide that a
   platform pose is accurate enough.

**Double-correction rule.**
- If the processed YUV is **measured** to be distortion-corrected by the camera device, the projection path must
  **not** distort or undistort it again with `LENS_DISTORTION`.
- It must never assume correction is unnecessary merely because the mode reports `FAST`.
- If correction is `OFF`, the path must either model `LENS_DISTORTION` explicitly, in the pre-correction basis, or
  **reject calibrated projection** until that transform is implemented.
- Do not assume `LENS_DISTORTION` must always be applied manually.

A numerically plausible matrix is **not** proof. A known physical ID is **not** proof. Then choose:
- **A. Truthful per-frame physical-camera geometry.** Allowed **only if Groups A, B and C are established** for
  every physical ID that occurs:
  - identity;
  - a legal composition of that camera's intrinsics with the delivered matrix's domain, and frame-content
    correspondence;
  - a known distortion mode and pixel coordinate space.

  The domain proof is recorded through `SensorToBufferDomainProof` (or its successor), never inferred from identity.
- **B. Explicit physical-camera binding** (`CameraSelector.setPhysicalCameraId`). Identity is fixed by construction,
  but Groups B and C still apply, because the matrix is predicted to stay logical-basis (§2.3, UNVERIFIED).
- **C. Fallback only.** Treat logical static intrinsics at 1.0× as **APPROXIMATE**, with an explicit focal-scale
  uncertainty, and make the first solve scale-tolerant. Used if A and B cannot be proven. It is **not** pre-selected.
  Group C still determines whether residual distortion is present in the pixels.

Every strategy must state:
- its distortion mode and pixel domain;
- whether lens correction is already present in the YUV;
- which intrinsic coordinate basis is being transformed, and into what;
- the camera↔physical-device extrinsic provenance from Group D.

**`projectStars` Ready is not sufficient unless all four are known, or explicitly modelled as uncertainty.**

### 28.3 Proposed navigation / autodetect state model (input to the redesign)

The watch `AimPhase` already covers the *pointing* half (SEARCHING / IN_TOLERANCE / LOCKED / BELOW_HORIZON /
NO_LOCATION). The code suggests **two orthogonal state machines** rather than one combined list:

**A. Pose-quality machine** (autodetect / tracking; phone first, optionally shared with the watch):

| State | Entry | Exit | Candidate policy (§9.1) | Data required | UI must communicate |
|---|---|---|---|---|---|
| UNAVAILABLE | no camera / no permission / no location / no time / no rotation sensor | prerequisite restored → PREPARING | — | reason enum | what is missing and how to fix it |
| PREPARING | session bound; waiting for geometry/metadata, first paired frame, exposure settle | geometry Ready → SEARCHING | — | geometry status, provenance, domain proof | "starting camera" |
| SENSOR_ONLY | autodetect disabled/unsupported, or repeated failure; navigation still possible | user enables / conditions improve | — | sensor accuracy, magnetic flag | pointing is approximate |
| SEARCHING | geometry ready, no accepted solution | candidate hypothesis → CANDIDATE | narrow or widened cone by prior trust | detections, candidates | "looking for stars"; counts (debug) |
| CANDIDATE | hypothesis passes invariants, not yet verified across K frames (K TBD) | verified → LOCKED; rejected → SEARCHING | same as the generating path | correspondences, residual, ambiguity | tentative |
| LOCKED | verified `R_Wtrue_from_Cbuf_optical`; frame-labelled correction terms applied | residual/inliers degrade → LOW_CONFIDENCE; detections lost → LOST | time-stamped prior fast path + periodic wide re-verification | correction terms, inliers, residual, age | pointing is camera-corrected |
| LOW_CONFIDENCE | lock degrading (few stars, cloud, blur) | recovers → LOCKED; times out → LOST | fast path + more frequent re-verification | confidence trend | correction may be stale |
| LOST | no verification for T s (TBD) | reacquire attempt → RECOVERING | — | last correction terms + age | using the last known correction / sensor only |
| RECOVERING | solve without trusting heading (correction kept, decaying) | success → LOCKED; timeout → SEARCHING | **recovery set** (nav-star-wide / hemisphere) | — | re-finding stars |

The prompt's `ACQUIRED` collapses into CANDIDATE→LOCKED. `ALIGNING` belongs to the pointing machine, not the pose
machine. The magnetic-interference flag (G-12) can move SEARCHING straight to the recovery candidate policy.

**B. Pointing / guidance machine** (the existing `AimPhase`, generalised and moved to a shared module):
NO_TARGET → (NO_LOCATION | BELOW_HORIZON | SEARCHING) → IN_TOLERANCE (= "aligning") → LOCKED. Its inputs are a
**declared** corrected pose from A (`CorrectedDevicePose`, or an explicitly named camera boresight) plus the target
alt/az. The UI shows the guidance vector, tolerance and hold progress, and **annotates the guidance with A's pose
quality**.

---

## 29. PR roadmap

Each PR is independently reviewable. "Device" means a Pixel 9 (phone) and a Wear OS watch, with evidence committed
to `docs/validation/`.

**Dependency direction** (revision 3):
- camera-domain truth before the geometry strategy;
- real pixels before detector claims;
- real detector evidence before hardening;
- a hardened detector contract before the matcher;
- the matcher before attitude/tracker integration;
- coordinate-frame-safe fusion before navigation consumes a corrected pose.

Detector evidence (PTS-11) does **not** wait for the final geometry decision (PTS-09). The pure candidate predictor
(PTS-10) does not wait for PTS-09 either, but its **production FOV-constrained filtering** is activated only with a
PTS-09-approved geometry.

```text
PTS-01 ─┐
PTS-02 ─┤ (independent)
PTS-03 ──────────────► PTS-09 ──► PTS-16 (calibrated optical sessions)
PTS-04 ─► PTS-05 ─► PTS-06 ─► PTS-10 (pure) ··· production FOV filtering gated on PTS-09 ··┐
PTS-07 ─┬─► PTS-11 (early real-sky) ─► PTS-12 ─► PTS-13 ─► PTS-14 ─► PTS-15 ─► PTS-17 ─► PTS-18 ─► PTS-19 …
PTS-08 ─┘            (needs PTS-03 for resolution evidence)
```

### Phase A — establish truth / unblock autodetect

**PTS-01 · Ephemeris epoch fix + convention-explicit reference tests**
- **Goal:** correct the Sun, Moon, Jupiter and Saturn positions, and prove the correction.
- **Scope:**
  - `JD_AT_2000_01_01_00UT` → 2451543.5 (with a rename);
  - reference tests whose frame, apparent/geometric setting, observer (geocentric) and time scale are stated and
    **match the model's own conventions**, e.g. a Schlyter-epoch reimplementation and Meeus low-precision worked
    examples. JPL Horizons values only if requested in a matching geocentric/astrometric or mean-of-date
    configuration, with tolerances taken from the model's documented accuracy;
  - regenerate `ephem_golden_v1.json`, and stop self-regeneration from being the only check.
- **Files:** `core/astro-core/.../ephem/SimpleEphemerisComputer.kt`, `core/astro/src/test/.../ephem/*`.
- **Depends on:** —.
- **Tests:** the new convention-matched reference tests; a regression test that the old epoch fails them.
- **Device:** none needed.
- **Exit:** the epoch correction is demonstrated, with no false failure from mismatched conventions. Tolerances are
  justified in the PR.

**PTS-02 · CI truth**
- **Goal:** every PR runs the pure-JVM and core Android unit suites, and the PR checks are green.
- **Scope:**
  - **fix the `android-actions/setup-android@v3` SDK setup failure** that currently breaks `smoke` and
    `Build catalog artifacts` before any project step (§2);
  - add `:core:astro-core:test`, `:core:astro`, `:core:catalog`, `:wear:sensors`, `:tools:*` and
    `testInternalDebugUnitTest` to the PR workflow;
  - get `android-full` running;
  - fix, or quarantine with a tracked issue, the known `RedactorTest` / `core:location` failures;
  - port `DefaultAimControllerTest` to virtual time and un-exclude it.
- **Files:** `.github/workflows/*`, `wear/build.gradle.kts`, the affected tests.
- **Depends on:** —.
- **Exit:** green CI with those suites on a PR.

**PTS-03 · Pixel 9 camera truth experiment: identity, projection domain, distortion state, extrinsics (internalDebug)**
- **Goal:** establish four **independent** classes of camera truth before any geometry strategy is chosen (§28.2):
  - **A** frame identity;
  - **B** projection-domain compatibility;
  - **C** distortion state and pixel coordinate space;
  - **D** camera↔device extrinsics.

  None implies another. A known physical ID proves neither B, C nor D. A valid sensor-to-buffer matrix proves neither
  C nor D. A usable `LENS_POSE_ROTATION` does not prove the pixel-domain mapping.
- **Scope:**
  - Attach a `Camera2Interop.Extender.setSessionCaptureCallback(...)` callback to the **same** CameraX session that
    serves Preview and ImageAnalysis. Reuse or extend the SKY-1 callback and `SkyExposureJoin`.
  - **Per capture result**, where available:
    - `SENSOR_TIMESTAMP`;
    - `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (API 29+ guard);
    - **`DISTORTION_CORRECTION_MODE`**;
    - `LENS_FOCAL_LENGTH`, `LENS_INTRINSIC_CALIBRATION`, `LENS_FOCUS_DISTANCE`, `LENS_STATE`;
    - `CONTROL_AF_MODE`, `CONTROL_AF_STATE`;
    - `SCALER_CROP_REGION`, zoom ratio where supported;
    - exposure time, sensitivity/ISO, frame duration;
    - OIS/stabilisation state where exposed;
    - *optional detector-provenance diagnostics*, where available: `HOT_PIXEL_MODE`, `NOISE_REDUCTION_MODE`,
      `EDGE_MODE`. The detector is calibrated against processed YUV, and these ISP stages can change hot-pixel
      persistence, background noise, PSF/source shape and weak-source detectability. They are diagnostic provenance
      only. The architecture does not depend on them unless real Pixel 9 evidence shows it must.

    Each result is correlated with `ImageProxy.imageInfo.timestamp`.
  - **Per physical camera ID (static characteristics)**:
    - `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`, `SENSOR_INFO_PIXEL_ARRAY_SIZE`;
    - `LENS_INTRINSIC_CALIBRATION`, `LENS_DISTORTION`;
    - the available distortion-correction modes;
    - **`LENS_POSE_ROTATION`, `LENS_POSE_TRANSLATION`, `LENS_POSE_REFERENCE`**;
    - the same for the logical camera.
  - **Per frame and active physical ID:** the delivered `sensorToBufferTransformMatrix`, and the 1.4.2
    analysis/preview resolution and stream configuration.
  - Run the existing CAM-2c frame-content correspondence workflow (`FrameContentTarget`, internalDebug) per physical
    ID, under each distortion mode the device allows, comparing mapped geometry against actual content.
  - Export through the CAM diagnostic JSON or the sky log.
- **Files:** `mobile/src/internalDebug/.../SkyCaptureExposure.kt`, `SkySessionCameraPreview.kt`,
  `SkyExposureJoin.kt`, `FrameContent*`, `SensorToBufferDomainProof.kt` (evidence recording only),
  `CamDiagnosticSnapshotJson.kt`; `mobile/.../ar/camera/CameraCharacteristicsSource.kt` (additional reads for
  evidence); possibly `skylog/*` (schema bump).
- **Depends on:** —.
- **Tests:** unit tests for:
  - extraction (null-tolerant: every key optional);
  - the timestamp join;
  - evidence-record serialisation;
  - `LENS_POSE_ROTATION` parsing pinned to `input array = [x, y, z, w]` → `R_Cphys_from_S`, including the §14.1
    invariant-6 regression (basis vectors checked; a `[w, x, y, z]` misreading must fail).
- **Device:** Pixel 9 sessions (indoors with the printed target, and at night) answering:
  - **A, identity:** which physical camera produced each frame; whether it switches (including in low light);
    whether the join holds; whether focal length/intrinsics change.
  - **B, projection domain:** the matrix's source coordinate system per frame; logical vs physical active-array
    basis; whether physical `LENS_INTRINSIC_CALIBRATION` can legally be composed with it; whether mapped geometry
    matches frame content; whether this holds for each physical ID.
  - **C, distortion state:**
    1. the requested and effective mode per frame;
    2. the metadata coordinate basis implied by that mode;
    3. whether measurable lens distortion remains in the delivered analysis YUV (measured, e.g. from frame-content
       edge residuals, never inferred from `FAST`);
    4. whether an application-side distortion model is required;
    - the active vs pre-correction relationship;
    - the basis `LENS_INTRINSIC_CALIBRATION` must be transformed from and into;
    - the full chain `mode → calibration space → YUV corrected? → array relationship → matrix domain → final pixel
      space`, per stream and physical ID.
  - **D, extrinsics:**
    - whether `LENS_POSE_*` is reported, and with which reference;
    - the quaternion convention and direction relative to the raw Android sensor frame `S`;
    - its comparison with PointToSky's nominal axes and with printed-target optical evidence, per physical ID.
- **Exit:**
  - a committed `docs/validation/` report with A/B/C/D answers and the 1.4.2 stream configuration;
  - a recorded `SensorToBufferDomainProof` outcome per physical ID (proven / unresolved / mismatch);
  - a recorded distortion-state chain per stream and physical ID;
  - an extrinsic provenance status per physical ID (`CALIBRATED_PLATFORM_POSE` /
    `PLATFORM_POSE_PRESENT_BUT_UNVERIFIED` / `PLATFORM_POSE_UNUSABLE` / `NOMINAL_FALLBACK`).
  - **No geometry strategy is chosen in this PR.** A plausible-looking matrix, a known physical ID or a present
    platform pose is not accepted as proof of anything beyond its own class.

**PTS-04 · Astronomy epoch pipeline: proper motion + precession in one seam**
- **Goal:** remove the epoch mismatch (≈0.36° precession, plus per-star proper motion).
- **Scope:**
  - verify the HYG `pmra` convention (μα vs μα·cos δ) against the source documentation;
  - choose a proper-motion policy: carry PM for nav stars and propagate to the observation epoch, **or** explicitly
    exclude nav stars above a displacement threshold (TBD BY ACCURACY BUDGET / DEVICE TEST);
  - add an IAU 1976 (Lieske) precession helper;
  - apply both in a single documented catalog → topocentric pipeline, with bodies in a consistent frame;
  - make the packer carry PM if the chosen policy needs it.
- **Files:** `core/astro-core/.../transform`, `prediction/LocalSkyDirection.kt`, `tools/catalog-packer/.../ptskcat0/*`.
- **Depends on:** PTS-01 (shared test conventions).
- **Tests:** Meeus Ex. 21.b (precession); a proper-motion worked example; an exclusion-rule test if that policy is
  chosen.
- **Exit:** reference examples pass. No path mixes epoch-2000 positions with observation-epoch frames undocumented.

**PTS-05 · Refraction policy for optical prediction**
- **Goal:** keep refraction from being absorbed as camera, intrinsics or attitude error.
- **Scope:**
  - **either** an apparent-altitude correction in the candidate/prediction path, with assumptions documented;
  - **or** a configurable minimum reference-star altitude (threshold TBD BY ACCURACY BUDGET / DEVICE TEST);
  - expose residual-versus-altitude in the offline loader output.
- **Depends on:** PTS-04.
- **Tests:** unit tests at several altitudes; the policy is applied once, never twice.
- **Device:** residual vs altitude on calibrated sessions (PTS-16).
- **Exit:** the policy is explicit, configurable and tested.

**PTS-06 · Navigation-star set (data + loader)**
- **Goal:** a curated set with a re-pack-stable identity.
- **Scope:**
  - selection rules: brightness, isolation radius, sky coverage, and the proper-motion rule from PTS-04. Final
    numbers decided and justified in the PR;
  - generator in `tools/catalog-packer`;
  - asset for both devices;
  - runtime resolution identity → PTSKCAT0 record;
  - tests on the shipped asset.
- **Depends on:** PTS-04.
- **Exit:** the set is loaded on both devices.

**PTS-07 · Luma tap + analysis-resolution path**
- **Goal:** real pixels, behind a flag (production) and in the internalDebug capture path.
- **Scope:** a stride-aware Y-plane copy into a reused `LumaFrame` buffer in `CameraFrameAnalyzer`; an
  analysis-resolution policy informed by the PTS-03 measured 1.4.2 configuration; the flag off by default in public.
- **Files:** `mobile/.../ar/CameraPreview.kt`, `mobile/.../ar/camera/CameraFrameAnalyzer.kt`.
- **Depends on:** PTS-03 (resolution evidence).
- **Tests:** unit (stride / pixelStride); instrumentation if available.
- **Device:** Pixel 9 frame latency and bound resolution.
- **Exit:** a luma frame per frame, with measured cost.

**PTS-08 · Night AF/AE policy + per-frame metadata in production logs**
- **Goal:** controlled focus and exposure, logged.
- **Scope:**
  - AF policy: manual infinity where supported, else documented continuous;
  - a production AE policy for star mode;
  - the PTS-03 metadata subset that proved useful, carried into the CAM JSON and sky log (schema v3 with migration);
  - `SENSOR_INFO_TIMESTAMP_SOURCE` read in production.
- **Depends on:** PTS-03.
- **Device:** Pixel 9 focus-distance evidence at night.
- **Exit:** a logged focus distance and AF state in a real session.

**PTS-09 · Camera-geometry strategy selected from PTS-03 evidence (A / B / C)**
- **Goal:** a projectable, honestly-labelled camera model on the Pixel 9.
- **Scope:**
  - implement the strategy the PTS-03 evidence supports (§28.2):
    - **A** only if identity, projection-domain/content correspondence **and** distortion state are established for
      every occurring physical ID;
    - **B** only if the domain proof and distortion state hold for the bound physical camera;
    - otherwise **C** (approximate logical intrinsics with a scale-uncertainty field).
  - Whichever strategy is chosen, it must define and record:
    - the distortion mode and the final pixel coordinate space;
    - whether measurable lens distortion remains in the YUV, as measured in PTS-03 and not inferred from the
      reported mode;
    - the **double-correction rule** (§28.2): never re-apply `LENS_DISTORTION` to output measured as camera-corrected;
      never assume correction is unnecessary merely because the mode is `FAST`; when distortion remains (mode `OFF`,
      or a `FAST` that is effectively `OFF`), model it explicitly or reject calibrated projection;
    - which intrinsic coordinate basis (pre-correction vs active) is transformed, and how;
    - the camera↔physical-device extrinsic provenance (`R_Cphys_from_S(id)` status from PTS-03 Group D).
  - Honest `CameraGeometryQuality` labels, extended with the distortion and extrinsic provenance; a zoom policy in
    star mode; the decision recorded with its evidence.
- **Files:** `AnalysisBufferIntrinsicsResolver.kt` (today it accepts `LENS_INTRINSIC_CALIBRATION` only when the
  pre-correction array equals the active array, `:211-232,376`, and ignores the distortion mode),
  `CameraIntrinsicsResolution.kt`, `SensorToBufferDomainProof.kt`, camera session code.
- **Depends on:** PTS-03, PTS-08.
- **Device:** a Pixel 9 overlay plus diagnostics; for A/B, frame-content correspondence evidence under the chosen
  distortion mode.
- **Exit:**
  - `projectStars` Ready on the Pixel 9 **and** a geometry record stating distortion mode, pixel domain, intrinsic
    basis and extrinsic provenance, each known or explicitly modelled as uncertainty;
  - a label that matches that record.
  - `projectStars` Ready alone is not an exit criterion.

**PTS-10 · Visible candidate predictor with uncertainty + PTSKCAT0 spatial index**
- **Goal:** bounded candidates in every state, without a hard magnetometer gate.
- **Scope:**
  - `VisibleCandidatePredictor` (pure JVM) with the staged fail-open policy (§9.1);
  - horizon margin and the epoch / proper-motion / refraction policy (PTS-04/05);
  - nav set first;
  - the prior expressed as `R_Wtrue_from_Cbuf_sensorPred` with uncertainty (§14.1);
  - a Dec-band index in `PtskCat0Catalog`;
  - CAM-2b adapter and SKY-1 switched to it.
- **Depends on:** PTS-04, PTS-05, PTS-06 for the **pure algorithm**. That algorithm may be implemented and tested
  before PTS-09, using synthetic or explicitly-labelled geometry.
- **Activation gate:** the predictor's **production camera-FOV-constrained filtering** (the narrow and widened cones
  sized from a real camera FOV) must not become authoritative on guessed legacy geometry (56° /
  `LEGACY_INTRINSICS_FALLBACK`). Activating it in production requires a `CameraGeometry` whose
  provenance/uncertainty record passed PTS-09. Until then, production use is limited to the horizon and recovery
  (heading-independent) sets, or to an explicitly widened uncertainty that covers the legacy-geometry error.
- **Tests:** true FOV stars ⊂ candidates for each state, **including a deliberately wrong heading**; candidate counts
  reported per state; the activation gate refuses a legacy-fallback geometry.
- **Exit:** the CAM-2b overlay uses the predictor. Per-state counts are recorded, with targets TBD BY DEVICE TEST.
  FOV-constrained production filtering stays off until a PTS-09-approved geometry exists.

**PTS-11 · Early real-night dataset (detector evidence)**
- **Goal:** real pixels for detector work, without waiting for final intrinsics.
- **Prerequisites:**
  - luma capture (PTS-07);
  - AF/exposure/focus metadata logged (PTS-08);
  - trustworthy timestamps, location and time. The SKY-1 observer gate and clock-alignment rules already exist
    (`docs/sky_session_log_format.md`).
  - **Does not depend on PTS-09.**
- **Scope:**
  - a procedure doc and SKY-1 sessions covering urban and dark sky, focus/exposure sweeps, blur and motion, and the
    Moon present or absent;
  - off-repo storage, plus a small committed fixture set (cropped frames + jsonl);
  - a `sky-session-loader` detector report: source counts, background σ, hot-pixel persistence, shape/size
    distributions.
- **Used for:** detector tuning; hot-pixel and background behaviour; the focus/exposure policy; source counts;
  blur/shape behaviour.
- **Exit:** the real-sky fixtures are committed and the detector baseline report is recorded.

**PTS-12 · Detector hardening on real-sky fixtures**
- **Goal:** a bounded, deterministic detector output with a documented source-quality contract for the matcher.
- **Scope** (evidence-driven, from PTS-11 data):
  - a bounded maximum output count / top-N policy with deterministic ordering, consistent with the existing
    brightness → y → x total order;
  - an explicit positional/source-quality metric suitable for matcher tolerances (e.g. derived from `pixelCount`,
    `peakLuma`, `localBackgroundLuma`, SNR);
  - SNR / background significance;
  - shape information sufficient to reject obvious elongated or non-stellar blobs, **if the fixtures show the need**;
  - a defined matcher treatment of saturated and near-edge sources;
  - hot-pixel suppression based on evidence from recorded sessions (e.g. temporal persistence);
  - removal of the per-frame allocations/boxing that device traces show significant.
- **Must preserve** the existing pixel-coordinate (edge-convention) and centroid contracts, pinned by
  `PixelConventionBridgeTest`.
- Thresholds are calibrated from the dataset, not invented. A sophisticated PSF fitter is not required unless the
  data justifies it.
- **Depends on:** PTS-11.
- **Tests:** existing detector suites plus real-fixture tests; determinism; the bound respected.
- **Exit:**
  - deterministic bounded detections;
  - real-sky fixtures covered;
  - known stars retained at an acceptable rate (TBD BY DEVICE TEST);
  - obvious non-stellar and hot-pixel contamination characterised;
  - the documented source-quality/uncertainty contract added to `StarMatcherInput`'s contract.

### Phase B — identification

**PTS-13 · Matcher v1 (fast path + recovery path)**
- **Scope:**
  - pure JVM; bounded detections (PTS-12 contract) × candidates (PTS-10);
  - angular pair/triangle invariants via `cameraRayFor` / `angleBetweenRad` (merge or supersede PR #236);
  - a recovery path over the full above-horizon nav set using precomputed invariants;
  - tolerances from the source-quality contract;
  - scale tolerance only as required by the PTS-09 strategy;
  - verification by projecting all candidates; an inlier set.
- **Depends on:** PTS-10, **PTS-12**; PTS-09 for the scale-tolerance requirement.
- **Tests:** synthetic scenes (rotations, scale error if C, missing/extra sources, hot pixels, mirrored sky, wrong
  time, **wrong heading by tens of degrees**); PTS-11 fixtures for the detector side.
- **Exit:** the synthetic suite passes; the fixture-driven detector→matcher interface is exercised.

**PTS-14 · Attitude solve**
- **Scope:**
  - Wahba via SVD (or QUEST) from (`Cbuf` camera ray, `Wtrue` direction) pairs, returning `R_Wtrue_from_Cbuf_optical`;
  - residuals in px and arcmin, also binned by altitude;
  - a scale refinement only under strategy C.
- **Tests:** known-rotation recovery; noise sensitivity; output-frame pinned (a `Cbuf`/`Cdisp`/`Ddisp`/`S` mix-up must fail).

**PTS-15 · Confidence + false-lock protection**
- **Scope:** inlier count, RMS, ambiguity ratio and consistency checks → `MatchVerdict`; a negative test corpus.
  Sensor-prior disagreement alone does not veto a verified solution.
- **Exit:** zero confident locks on the negative corpus. Thresholds stay TBD BY DEVICE TEST until PTS-16/17 data
  calibrates them.

**PTS-16 · Calibrated optical sessions**
- **Goal:** geometry-dependent evidence.
- **Scope:** SKY-1 sessions captured under the PTS-09 strategy, covering:
  - predicted-vs-detected residuals;
  - candidate recall per state;
  - altitude / refraction residuals;
  - high-proper-motion stars;
  - disturbed-heading sessions;
  - focal-scale validation.
- **Depends on:** PTS-09, PTS-10, PTS-12 (it can run in parallel with PTS-13–15).
- **Exit:** a calibrated session set is committed (fixtures plus report).

**PTS-17 · Offline end-to-end matcher evaluation**
- **Scope:** `tools/sky-session-loader` runs detect → predict → match → solve on PTS-11 and PTS-16 sessions, and
  reports identification rate, residuals (including vs altitude), recall and optical attitude accuracy.
- **Depends on:** PTS-13, PTS-14, PTS-15, PTS-16.
- **Exit:** a real-session report committed. PTS-15 thresholds are calibrated from it.

### Phase C — stable navigation

**PTS-18 · Tracker + frame-correct optical fusion**
- **Scope:**
  - the §28.3 A-machine;
  - frame-labelled state per §14.1:
    - `R_Wtrue_from_Cbuf_optical`;
    - a separately modelled world-fixed term (`Wtrue`);
    - a body-fixed physical extrinsic term `R_Cphys_from_S(id)` per physical camera, initialised from its PTS-03/09
      provenance status (platform pose, or nominal) and possibly refined (`OPTICALLY_REFINED`);
    - the representation is chosen in this PR;
    - age/decay;
  - presentation transforms (`R_S_from_Ddisp`, `R_Cbuf_from_Cdisp`) taken as known inputs, **never estimated**;
  - time-stamped `priorProjections` propagated by the rotation delta;
  - gated re-association, then re-verification;
  - periodic wide re-verification;
  - reacquisition through the recovery path;
  - produce the typed `CorrectedCameraPose` (`Wtrue ← Cbuf`) and `CorrectedDevicePose` (`Wtrue ← S`) (§28.2).
- **Depends on:** PTS-17.
- **Tests:**
  - the §14.1 invariants:
    1. identity residual under a perfect sensor and the true extrinsic;
    2. correct propagation under rotation with a held correction, with world-side vs body-side application
       distinguished;
    3. a body-fixed transform never becomes world-fixed;
    4. **a display-rotation or `rotationDegrees` change alters only presentation terms and never mutates the
       estimated `R_Cphys_from_S(id)`**, with the implied `R_Cbuf_from_S` invariant across the four display
       rotations;
    5. a physical-camera switch selects that id's own extrinsic;
    6. pinned multiplication order and quaternion convention with non-commuting rotations, including the Camera2
       `[x, y, z, w]` → `R_Cphys_from_S` regression (a `[w, x, y, z]` misreading must fail);
  - scripted sequences: cloud gap, fast pan, single star, stale prior, poisoned prior, heading jump, display
    rotation, physical-camera switch.

**PTS-19 · On-device integration**
- **Scope:**
  - an analyzer-thread pipeline (detect → predict → match/track) throttled to a measured budget;
  - the AR overlay switched from the legacy 56° projector to the CAM-2a projector, consuming **`CorrectedCameraPose`**;
  - alt/az at 1 Hz off the main thread.
- **Depends on:** PTS-18.
- **Device:** Pixel 9 latency and FPS; lock under hand motion.

**PTS-20 · Shared navigation domain + phone guidance**
- **Scope:**
  - move `AimController` / the phase machine into a shared module;
  - phone guidance (arrow / offset / lock / haptics) consuming a **declared** pose (`CorrectedDevicePose` or an
    explicitly named camera boresight);
  - the phone location gate (no (0,0) rendering).
- **Depends on:** PTS-18.

**PTS-21 · Watch correctness + parity**
- **Scope:**
  - Aim target arbitration, so no picker default overwrites a requested target;
  - **path-specific regression tests**:
    - (a) `/app/open` with an equatorial target (must not become Polaris);
    - (b) `/aim/set_target` entered from Home with `initialTarget == null` (must not become Sun);
    - (c) `/aim/set_target` while Aim is mounted;
    - (d) the phone's send-to-watch sequence (`/aim/set_target` then `/app/open`);
  - `WearBridge` replay for cold start;
  - Identify body angular cap + stale-fix handling;
  - Tonight tile location injection;
  - `OfflineStarResolver` → a shipped catalog;
  - phone heading override applied to `forward` with a consistent sign;
  - declination cache;
  - decide the watch catalog;
  - a parity test fixture;
  - optional phone → watch correction message (versioned, frame-typed). It must be gated on the open frame question
    (§31).
- Independent of Phases A–C except where noted; it can be scheduled early.

### Phase D — validation

**PTS-22 · Device validation matrix:** the §23 rows executed on a Pixel 9 and a watch; results committed.

**PTS-23 · Performance:** JVM microbenchmarks (detector, predictor per state, matcher fast and recovery paths) plus
on-device traces.

**PTS-24 · Failure handling:** the §25 rows implemented as states and reasons; magnetic-interference flag wired to
the recovery candidate policy.

### Phase E — redesign foundation

**PTS-25** Gate debug UI on both devices (`internal` flavor / debug); move LocationSetup out of "Debug tools".

**PTS-26** Phone navigation shell with a back stack; extract `ArScreen` into presenter + composables; kill dead
screens.

**PTS-27** UI state contracts: expose the A/B machines (§28.3) and the typed corrected poses as immutable UI state on
both devices.

### Phase F — visual redesign

**PTS-28…** Per-screen redesign PRs against the PTS-27 state contracts (requirements to be supplied).

### Phase G — constellation graphics

**REQUIREMENTS PENDING — product/visual specification to be supplied separately.** No PRs are planned in this recon.
Implementation must use the §19 integration seams (stable identity, corrected-pose source, common projection
interface, rendering-layer boundary, phone/watch data availability) once requirements exist. Camera-registered
graphics would consume `CorrectedCameraPose`.

---

## 30. Definition of "autodetect complete"

Each criterion is measurable. Values without repository evidence are **TBD BY DEVICE TEST**, or **TBD BY ACCURACY
BUDGET / DEVICE TEST** where an error budget must be agreed first.

1. **Camera metadata, domain, distortion and extrinsic truth.**
   - For every analysed frame used in a solution, the camera geometry is one of:
     - (A) per-frame physical-camera geometry, with recorded identity, a projection-domain/frame-content proof
       **and** a known distortion state;
     - (B) bound-physical geometry with a recorded domain proof and distortion state;
     - (C) an explicit, tested scale-uncertainty model.
   - In every case, the distortion mode, the final pixel domain and the intrinsics basis are recorded, and lens
     distortion is accounted for **exactly once** (never double-corrected).
   - The camera↔device extrinsic carries a provenance status.
   - The chosen strategy and its PTS-03 evidence are recorded. Quality labels match provenance.
2. **Detection on real frames.**
   - On recorded Pixel 9 sessions, at least X% (TBD BY DEVICE TEST) of predicted in-image nav stars brighter than
     mag M (TBD) are detected within R px (TBD).
   - Detector output is bounded and deterministic, with the documented source-quality contract (PTS-12).
3. **Prediction correctness.**
   - On real sessions with a verified optical solution, every true in-FOV nav star is in the candidate set
     (100% recall) **in every candidate state**, including the recovery state under a deliberately wrong heading.
   - Candidate counts per state are within targets (TBD BY DEVICE TEST).
4. **Constrained matching.** The matcher never scans the whole catalog per frame. The fast path uses only the
   predictor's cone-limited set, and the recovery path only the above-horizon nav set.
5. **Epoch and refraction truth.**
   - Reference positions are propagated to the observation epoch (proper motion + precession), or high-PM stars are
     explicitly excluded per a tested rule.
   - The refraction policy is applied.
   - Residual versus altitude shows no systematic trend beyond E_alt (TBD BY ACCURACY BUDGET / DEVICE TEST).
6. **Correct optical attitude.** On locked frames, the RMS residual is ≤ E arcmin (TBD) and the solved
   `R_Wtrue_from_Cbuf_optical` agrees with independent verification within T° (TBD).
7. **Frame-correct fusion.**
   - The §14.1 invariant tests pass:
     - identity residual under a perfect sensor;
     - correct propagation under rotation;
     - a body-fixed transform never treated as world-fixed;
     - display-rotation / `rotationDegrees` changes never mutating the estimated physical extrinsic;
     - a per-physical-ID extrinsic selection;
     - pinned multiplication order and quaternion convention.
   - No untyped pose crosses a layer boundary (code criterion).
8. **No silent false lock.** On a negative corpus (wrong time ±1 h, wrong location, mirrored frames, random noise,
   urban frames without stars), there are zero LOCKED states.
9. **Recovery from a wrong heading.** With the magnetometer prior offset by a large error (TBD), the system still
   reaches LOCKED within ≤ S₁ s (TBD) through the recovery path.
10. **Lock survives normal hand motion.** On the recorded hand-held sequences, the lock is retained for ≥ P% (TBD) of
    frames.
11. **Recovery after loss.** After a cover/uncover or a cloud gap of D s, the system reaches LOCKED again within
    ≤ S s (TBD). A poisoned prior is cleared by periodic wide re-verification within ≤ V s (TBD).
12. **Navigation uses typed corrected poses.** The camera overlay consumes `CorrectedCameraPose`, and phone guidance a
    declared `CorrectedDevicePose` / boresight. The legacy 56° projector is not on the star-mode path.
13. **Latency.** Frame-to-overlay latency ≤ L ms and pipeline rate ≥ F Hz on a Pixel 9 (TBD).
14. **Phone/watch consistency.** The parity test passes. Phone- and watch-computed target alt/az agree within Q°
    (TBD). The watch shows the requested target on every delivery path (PTS-21 a–d).
15. **Diagnostics.** The §21 "add" list is present in the CAM JSON and sky log.
16. **Real-device evidence.** Every §23 matrix row has a committed validation record with session ids.
17. **Astronomy truth.** The PTS-01 (convention-matched) and PTS-04/05 reference tests pass.
18. **CI.** The PR checks, including SDK setup, are green on the head that claims completion (PTS-02).

---

## 31. Open questions backed by repository evidence

1. **Pixel 9 per-frame physical identity (PTS-03 Group A).**
   - Does the HAL report `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` for this CameraX 1.4.2 Preview + ImageAnalysis
     configuration?
   - Is it stable, and does it switch in low light?
   - Are focal length and intrinsics dynamic with it?
   - UNINVESTIGATED / UNVERIFIED ON PIXEL 9.
2. **Pixel 9 projection domain (PTS-03 Group B).**
   - What source basis does the delivered `sensorToBufferTransformMatrix` use per frame: the logical active array, as
     §2.3 of the CAM-2c recon predicts, or the active physical sensor's?
   - Can physical `LENS_INTRINSIC_CALIBRATION` be legally composed with it?
   - Does mapped geometry match frame content for each physical ID?
   - UNVERIFIED. No `ProvenActiveArrayLocal` proof has ever been constructed.
3. **Can capture results be matched to every analysis frame?** SKY-1's `SkyExposureJoin` matches by
   `SENSOR_TIMESTAMP`, but it has never been run on a device (UNVERIFIED).
4. **CameraX 1.4.2 analysis resolution / stream configuration on the Pixel 9.** UNVERIFIED ON DEVICE. 640×480 is
   1.3.4 evidence.
5. **AF behaviour at night.** What does CameraX's default AF actually do on the Pixel 9 in the dark? Is manual focus
   supported?
6. **Does SKY-1's Camera2Interop on ImageAnalysis also govern the Preview stream's repeating request?** UNVERIFIED
   (§10.1).
7. **Detector behaviour on real sensor data.** Hot-pixel prevalence, ISP denoise/sharpening effects, source counts in
   urban skies, and whether shape metrics are needed. UNKNOWN until PTS-11.
8. **Tracker correction representation.** How should world-fixed bias (magnetic heading), body-fixed boresight
   (camera↔IMU) and short-term propagation be modelled and separated? This is a design decision for PTS-18; the
   recon fixes only the frame invariants (§14.1).
9. **Camera↔IMU extrinsics.**
   - The code assumes the nominal axes (`diag(1,−1,−1)` between the display-remapped device frame and the
     display-aligned camera frame), which implies a body-fixed `R_Cphys_from_S` (§14.1).
   - Does the Pixel 9 report `LENS_POSE_ROTATION` / `LENS_POSE_REFERENCE` per physical ID? With which reference, and
     with what agreement with optical evidence?
   - Until PTS-03 Group D answers, the status is `NOMINAL_FALLBACK`. Optical estimation of a body-fixed term is
     planned only if the platform pose is absent, unusable or contradicted.
9a. **Distortion state on the Pixel 9.**
   - Is `DISTORTION_CORRECTION_MODE` supported? Which mode does the CameraX 1.4.2 session actually run with?
   - Does measurable distortion remain in the analysis YUV? Is `FAST`, if reported, effectively `OFF` on this
     device?
   - What are the Pixel 9 pre-correction and active arrays per physical ID?
   - UNKNOWN (PTS-03 Group C).
9b. **Does CameraX's `sensorToBufferTransformMatrix` account for the distortion mode's coordinate basis?** It is
    source-traced to the opened camera's `SENSOR_INFO_ACTIVE_ARRAY_SIZE` (CAM-2c recon §2.3). Its relationship to
    pre-correction coordinates under correction `OFF` is UNKNOWN.
10. **HYG `pmra` convention.** Does it already include cos δ (μα\*)? Must be verified before PTS-04.
11. **Proper-motion exclusion threshold and refraction minimum altitude.** TBD BY ACCURACY BUDGET / DEVICE TEST.
12. **Candidate-set size targets per state.** TBD BY DEVICE TEST (§24).
13. **Watch catalog.** Should the watch adopt PTSKCAT0 (the unused 8,920-star asset), the nav set, or keep the PTSKCAT4
    904-star file?
14. **Wear `star.bin` provenance.** Which script and magnitude limit built it? UNKNOWN (§6.3).
15. **Watch Aim ordering on device.** Which of the §17.1 paths actually overwrite the target on a real watch?
    UNVERIFIED ON DEVICE.
16. **Open PRs.** The older README-only PR #242 corrects the README's Pixel 9 claim in the wrong direction (§11.4).
    PR #236 should be merged or folded into PTS-13.
17. **CI.** The PR #243 head fails `smoke` and `Build catalog artifacts` inside `android-actions/setup-android@v3`
    before any project step. The `Android Release Artifacts` bundle jobs on `main` have a separate, unverified
    signing cause.
18. **`fix-volatile-running.diff`.** Delete it? It no longer applies (§26).
19. **Navigation-star selection rules.** Number of stars, isolation radius, inclusion of planets: product decision.
20. **Phone → watch optical correction.** The watch has a different physical device frame (its own `S`, forward +Y), no camera, and its
    own magnetometer. Is any phone-derived correction transferable, and in which frame? There is no repository
    precedent.
21. **Does the watch `DefaultAimControllerTest` still pass?** It has been excluded for an unrecorded period
    (`wear/build.gradle.kts:132-148`).

---

## Appendix A — Recon method

- **Repository and branches:** `git fetch origin '+refs/heads/*:refs/remotes/origin/*'` (248 refs); for history,
  `git log --remotes -S` for `CONTROL_AF_MODE` and `FocusMeteringAction`, and `--grep` for matcher terms.
- **GitHub:** the API was used to list PRs (#236 and the older README-only #242 open; this recon is PR #243).
- **Assets:** Python `struct` parses of every catalog asset header (scratch scripts, not committed).
- **Code:** call-site tracing with ripgrep for `detectStars`, `projectStars`, `StarMatcherInput`, `.nearby(`, AF and
  exposure keys, and data-layer paths.
- **Ephemeris cross-check:** an independent Python Schlyter + Meeus Sun computation for 2025-01-01T00Z (§8.3).
- **Gradle:** could not run in this container (no Android SDK, JDK 21 only, Maven 429, license plugin unresolved).
  Test status is taken from source counts, the 2026-09-02 recon and the GitHub Actions history.
- **Platform references (revision 2).**
  - Camera2 `CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (API 29+):
    https://developer.android.com/reference/android/hardware/camera2/CaptureResult#LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID
  - CameraX `Camera2Interop.Extender.setSessionCaptureCallback`:
    https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2Interop.Extender
  - The CameraX mechanism is also proven in-repo: SKY-1 calls `extender.setSessionCaptureCallback(captureCallback)`
    (`mobile/src/internalDebug/.../SkyCaptureExposure.kt:259`) against CameraX 1.4.2.
  - The reference pages were too large to read in full from this container. The Camera2 key's semantics are taken
    from the platform API, and **its behaviour on the Pixel 9 is exactly what PTS-03 must measure**.
  - HYG `pmra`/`pmdec` column presence is per the HYG catalogue documentation (astronexus/HYG-Database). The source
    CSV is not committed, and the `pmra` cos δ convention is unverified (§6.2).
- **No production code was changed.** The only file added is this document.

---

## Revision log

**Revision 2 (2026-10-06)** is a documentation-only correction, made on the same branch and in the same PR (#243).
PTS numbers in the revision-2 rows use revision-2 numbering, which revision 3 superseded.

| # | Correction | Sections |
|---|---|---|
| 1 | Separated *code snapshot audited* (`main @ 09b1654`) from *recon branch*, *recon branch HEAD* and *delta*. The branch is no longer described as identical to main. This PR (#243) is distinguished from the older README-only PR #242 | header, §2, Appendix A |
| 2 | Re-opened per-frame active physical camera. Status changed from "no API" to **UNINVESTIGATED / UNVERIFIED ON PIXEL 9**. `ImageInfo` does not expose it, but Camera2 `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` via the session capture callback (already used by SKY-1) may. Added the PTS-03 probe. The A/B/C geometry choice is deferred until after it, with C (approximate logical + scale-tolerant solve) as fallback only | §1, §10.1, §10.3, §10.4, §11.3, §11.4, §21, §23, §25, §26, §27 G-01/G-03, §28.2, §29, §30, §31 |
| 3 | Added a proper-motion gap and requirement. HYG `pmra`/`pmdec` are dropped by the packer. Propagate to the observation epoch in one pipeline with precession, or apply an explicit tested exclusion (threshold TBD BY ACCURACY BUDGET / DEVICE TEST). The `pmra` convention must be verified first | §1, §6.2, §7, §8.3 item 10, §25, §27 G-25, §28.1, §29 PTS-04, §30, §31 |
| 4 | Added a refraction policy for optical matching: (A) apparent-altitude correction, or (B) minimum-altitude exclusion (threshold TBD). Residual-vs-altitude testing and device evidence are required | §1, §8.3 item 11, §23, §25, §27 G-26, §28.1, §29 PTS-05, §30 |
| 5 | The sensor prior now fails open. A staged candidate policy (trusted / degraded / recovery / locked fast path / periodic wide re-verification) is defined. Candidate-size targets are stated per state, and O(10¹) is no longer promised everywhere | §7, §9.1, §24, §25, §27 G-04/G-08, §28, §29 PTS-10/PTS-12, §30 |
| 6 | Corrected the Wear Aim overwrite description. It is path-dependent: `/app/open` maps to Polaris (index 4); `/aim/set_target` from Home maps to Sun (index 0); the already-mounted case differs; the phone sends both messages. Regression tests are specified per path | §1, §17.1, §17.3, §18, §23, §27 G-09, §29 PTS-19, §30 |
| 7 | 640×480 is now recorded as Pixel 9 + CameraX **1.3.4** evidence. The 1.4.2 production resolution is UNVERIFIED ON DEVICE, and PTS-03 must record it | §1, §10.1, §10.4, §11.4, §23, §31 |
| 8 | Removed premature constellation-graphics design (HIP-keyed figure model, great-circle subdivision, clipping, watch architecture). Kept only the justified seams, and marked Phase G **REQUIREMENTS PENDING** | §19, §27 G-21, §29 Phase G |
| 9 | Ephemeris reference tests must match frame, apparent/geometric setting, observer and time-scale conventions to the model under test | §8.3, §27 G-06, §29 PTS-01 |
| 10 | Architecture reordered into explicit truth layers: camera metadata → astronomy epoch/PM/precession/refraction → candidates with uncertainty → detector → matcher → attitude → tracker → corrected pose → navigation. Added a recovery invariant. The roadmap was renumbered (PTS-01…PTS-26) | §28, §29 |

**Preserved unchanged (no contrary evidence):**
- the ephemeris one-day epoch defect, and the absence of external ephemeris reference tests;
- missing precession;
- missing matcher, optical attitude solution and temporal optical tracker;
- no navigation-star set;
- no production luma/detector path; the detector is synthetic-only;
- the linear PTSKCAT0 `nearby` query;
- no recorded real night-sky dataset;
- the Tonight tile location wiring defect;
- the `OfflineStarResolver` asset mismatch;
- `PhoneHeadingOverrideRepository` not updating `forward`;
- the Identify body fallback without an angular cap.

**Revision 3 (2026-10-06)** is documentation only, on the same branch and PR (#243), on top of `e846486`.

| # | Correction | Sections |
|---|---|---|
| 1 | **Removed** the frame-ambiguous `q_corr = R_opt · R_sensor⁻¹`. Added a frame model, with frames `Wmag`, `Wtrue`, `D`, `Cdisp` and `Cbuf` and the repository-truth chain: `R` is device→world; `worldToDeviceVector` uses `Rᵀ`; `R_Cdisp_from_D = diag(1,−1,−1)`; buffer rotation by `rotationDegrees`. Defined the sensor-predicted camera attitude and the optical `R_Wtrue_from_Cbuf_optical`, and the two well-typed residuals (world-fixed `ΔW`, body-fixed `ΔC`) without choosing a representation. Added the required tracker invariants and tests | §14.1, §15, §27 G-07, §28.1, §28.3, §29 PTS-14/PTS-18, §30 item 7 |
| 2 | Strategy A now requires **two independent proofs**: physical-frame identity (Group A) **and** projection-domain compatibility plus frame-content correspondence per physical ID (Group B). This follows `cam_2c_sensor_to_buffer_domain_recon.md` §2.3 (the matrix is logical-basis) and `SensorToBufferDomainProof`. B also needs the domain proof. A plausible matrix or a known ID is not proof | §11.3, §23, §25, §27 G-03, §28.2, §29 PTS-03/PTS-09, §30 item 1, §31 items 1–2 |
| 3 | Added detector hardening (new **PTS-12**, gap G-27): bounded deterministic top-N; a source-quality/uncertainty contract; SNR; shape only if the data needs it; saturated/edge treatment; evidence-based hot-pixel suppression; allocation fixes; contracts preserved; thresholds calibrated from data. Matcher v1 (PTS-13) depends on it | §12, §26, §27 G-27, §28.1, §29, §30 item 2 |
| 4 | Split real-sky evidence into **early real-night capture** (PTS-11, detector evidence; needs luma, AF/exposure metadata and trustworthy time/location, not final intrinsics) and **calibrated optical sessions** (PTS-16, after PTS-09) | §27 G-05, §29 |
| 5 | Corrected pose is now **typed**: `CorrectedCameraPose` (`Wtrue ← Cbuf`) for the camera overlay and `CorrectedDevicePose` (`Wtrue ← D`) or a named boresight for pointing, related only through a body-fixed `R_D_from_Cbuf`. No untyped pose crosses a layer boundary | §28.1, §28.2, §28.3, §29 PTS-18/19/20/27, §30 item 12 |
| 6 | CI wording: the PR #243 head is **not green**. Lint passes. `smoke` and `Build catalog artifacts` fail inside `android-actions/setup-android@v3` before any project step. The failures are not attributed to the docs change. PTS-02 now includes fixing SDK setup | §1, §2, §29 PTS-02, §30 item 18, §31 item 17 |
| 7 | Roadmap renumbered to the requested dependency direction: PTS-01 … PTS-12 truth/evidence/hardening; PTS-13 matcher; PTS-14 attitude; PTS-15 confidence; PTS-16 calibrated sessions; PTS-17 offline end-to-end; PTS-18 tracker/frame-correct fusion; PTS-19 on-device; PTS-20 phone guidance; PTS-21 watch; PTS-22…27 validation and redesign foundation | §29 and all cross-references |

**Preserved from revision 2:**
- the ephemeris epoch finding, and convention-matched ephemeris tests;
- the proper-motion policy; precession; the refraction policy;
- the fail-open candidate predictor and heading-independent recovery;
- the Pixel 9 active-physical-ID probe (now extended with Group B);
- the 1.4.2 resolution marked unverified;
- the path-specific Wear target bug;
- deferred constellation graphics;
- no production luma path;
- missing matcher, attitude and tracker;
- no real night-sky evidence.

**Revision 4 (2026-10-06)** is documentation only, on the same branch and PR (#243), on top of revision 3 @
`5b733300adb0cfa57ffc457bdb50c72ba3b02a87`.

| # | Correction | Sections |
|---|---|---|
| 1 | Added **distortion-correction state** to camera truth. `DISTORTION_CORRECTION_MODE` (never read in the repository) decides whether the YUV is corrected and whether metadata uses pre-correction or active coordinates. `LENS_INTRINSIC_CALIBRATION` is pre-correction. A full chain is required per stream and physical ID, plus a double-correction rule. The issue moves from P2 G-18 into P0 G-03; residual lens refinement stays G-18 | §1, §10.1, §11.3, new §11.5, §21, §23, §25, §26, §27 G-03/G-18, §28.1, §28.2, §29 PTS-03/PTS-09, §30 item 1, §31 items 9a/9b |
| 2 | **Camera pose metadata audited.** `LENS_POSE_ROTATION` / `TRANSLATION` / `REFERENCE` are never read. They are added to PTS-03 per physical ID, with provenance statuses `CALIBRATED_PLATFORM_POSE` / `PLATFORM_POSE_PRESENT_BUT_UNVERIFIED` / `PLATFORM_POSE_UNUSABLE` / `NOMINAL_FALLBACK` / `OPTICALLY_REFINED`. Optical boresight estimation is now conditional on that audit, not presumed | §1, §10.1, §14.1, §21, §23, §25, §28.2, §29 PTS-03/PTS-09/PTS-18, §31 item 9 |
| 3 | **Physical frame separated from the display frame.** New frames `S` (raw Android sensor frame) and `Cphys(id)`; revision 3's `D` is renamed `Ddisp` (presentation). The only body-fixed term is `R_Cphys_from_S(id)`. Presentation terms (`R_S_from_Ddisp`, `R_Cbuf_from_Cdisp`) are known and never estimated. Corrected for revision 3's claim that `R_D_from_Cbuf` is body-fixed. `CorrectedDevicePose` is now `Wtrue ← S`. Added invariants: display-rotation / `rotationDegrees` changes never mutate the extrinsic; per-ID extrinsic selection | §14.1, §15, §28.1, §28.2, §29 PTS-18, §30 item 7 |
| 4 | PTS-03 now establishes **four independent proofs** (A identity, B projection domain, C distortion state, D extrinsics). None implies another | §28.2, §29 PTS-03 |
| 5 | PTS-09: every strategy must state the distortion mode and pixel domain, whether the YUV is already corrected, the intrinsic basis transformed, and the extrinsic provenance. `projectStars` Ready alone is not an exit | §28.2, §29 PTS-09 |
| 6 | PTS-10: the pure algorithm may precede PTS-09. Production FOV-constrained filtering is gated on a PTS-09-approved geometry and must refuse legacy-fallback geometry | §29 PTS-10 and dependency note |
| 7 | Header pinned to committed revisions (revision 3 @ `5b73330…` as base). No self-referential "next commit" wording | header, §2 |

**Preserved from revisions 2–3:**
- the ephemeris epoch defect and convention-matched ephemeris tests;
- precession, proper motion and the optical refraction policy;
- the fail-open candidate prediction and the navigation-star set requirement;
- the physical-camera identity probe and the projection-domain proof;
- detector real-sky evidence and hardening before the matcher;
- frame-labelled optical attitudes and typed camera/device corrected poses;
- no production luma path;
- missing matcher, attitude solve and tracker;
- the path-specific Wear target bug;
- deferred constellation graphics;
- CI SDK-setup failures recorded as they were observed.

**Revision 5 (2026-10-06)** is a small documentation correction on top of revision 4 @
`a77bd8f7a503d56fbf758f015a418a0ed623f7eb`.

| # | Correction | Sections |
|---|---|---|
| 1 | `LENS_POSE_ROTATION` quaternion order corrected from "w/x/y/z" to Camera2's **`[x, y, z, w]`** (rotation `S → Cphys`). Parsing is pinned to `input array = [x, y, z, w]`, with an explicit reorder adapter required for any `(w, x, y, z)` helper. Added a regression test requirement: known non-trivial rotation, basis-vector check, and failure when the same numbers are read as `[w, x, y, z]` | §14.1 invariant 6 (used by PTS-03 / PTS-18) |
| 2 | `FAST` distortion semantics corrected. The **metadata coordinate contract** (`OFF` → pre-correction array; any other mode → active array) is separated from the **actual pixel correction** (`HIGH_QUALITY` highest quality; `FAST` camera-selected, never slows capture, may be effectively `OFF`). Residual distortion in the YUV must be measured. PTS-03 Group C now asks: effective mode; implied metadata basis; measured residual distortion; whether an application-side model is needed. The double-correction rule gains "never assume no correction is needed because the mode is `FAST`" | §11.5, §21, §23, §25, §27 G-03, §28.2 Group C, §29 PTS-03/PTS-09, §31 9a |
| 3 | Added optional detector-provenance diagnostics `HOT_PIXEL_MODE`, `NOISE_REDUCTION_MODE`, `EDGE_MODE`. They are diagnostic only; the architecture does not depend on them | §21, §29 PTS-03 |

No conclusion, gap priority or roadmap order changed. Revision 4's own log row says "decides whether the YUV is
corrected". That wording is superseded by row 2 above: the mode decides the metadata basis, and the pixel correction
is measured.
