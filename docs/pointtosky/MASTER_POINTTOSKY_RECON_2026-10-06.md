# PointToSky Master Recon

```text
Repository:                 Inneren12/PointToSky (GitHub; single repository, phone + watch + shared core + tools)
Branch/ref audited:         main (origin/main) — the audit branch claude/pointtosky-master-audit-k4rws7 is identical to it
HEAD:                       09b16549cac78fc6e9f2649bf142d146ccfd11eb  ("Merge pull request #241 …", 2026-09-04)
Recon date:                 2026-10-06
Relevant application modules: :mobile, :wear, :wear:sensors, :wear:benchmark, :core:astro-core, :core:astro,
                            :core:catalog, :core:common, :core:location, :core:time, :core:logging,
                            :tools:catalog-packer, :tools:sky-session-loader, :tools:ephem-cli, res/ (Python data builders)
Relevant historical branches: none carry unmerged autodetect work. Open PRs: #236 (camera-ray angle helper,
                            claude/camera-ray-angle-api-adb9hj, 3 commits, not merged), #242 (README wording,
                            claude/readme-spectrum-axis-fix-h9dntw, 1 commit). 191 pre-2026-07 branches
                            (codex/*, older claude/*, feature/sqm-grid-v3) share no history with main.
Previous recon:             docs/recon/RECON_main_2026-09-02.md (state at b6bdc7c; build/CI/asset audit)
```

This document describes **repository truth at `09b1654`**. Every claim has a `path:line` reference. Status words:
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
- **Watch Aim overwrites phone-sent targets.** Every equatorial or star target sent from the phone becomes Polaris
  (`wear/.../aim/ui/AimScreen.kt:248-260,550-563`).
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
   - calibrated intrinsics are blocked by `UnsupportedLogicalMultiCameraMapping` (device-observed);
   - autofocus has never been configured anywhere in the repository or its history;
   - the default analysis buffer is 640×480;
   - Preview and ImageAnalysis are bound without a `ViewPort`.
4. **No visible navigation-star candidate predictor.** There is no navigation set, no horizon gate in CAM-2a, no
   attitude cone and no spatial index. The 41k-star PTSKCAT0 port is unused.
5. **No real night-sky evidence**, plus astronomy-truth defects (ephemeris lag, no precession) that would poison
   both prediction and navigation.

**First recommended PR.** **PTS-01: fix the ephemeris epoch and replace the self-referential golden test with an
external-reference test.** It is small, independent and P0-correct. **PTS-02** (CI: run the pure-JVM suites on every
PR) and **PTS-03** (J2000→of-date precession) follow. The first PR *on the autodetect path itself* is **PTS-05**, the
production luma frame tap (§29).

---

## 2. Repository / branch / HEAD

| Item | Value | Evidence |
|---|---|---|
| Repository | `Inneren12/PointToSky` — PointToSky lives alone in this repository (no unrelated 2D/3D/AR-fabrication tracks) | `settings.gradle.kts:24-43` (`rootProject.name = "PointToSky"`) |
| HEAD | `09b1654` = `origin/main` = audit branch | `git rev-parse HEAD`; `git log origin/main..HEAD` is empty |
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
- **Proper motion:** neither format carries it; it is never applied (MISSING). **Precession:** never applied (MISSING, §8.3).
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

**Implication.** A navigation-star set must be created: a data file plus generator, keyed by HIP and resolved to the
PTSKCAT0 record index at load. It needs explicit selection rules: brightness, isolation from near neighbours (to
avoid ambiguous pairs), sky coverage, and colour if useful. The visible-subset and FOV-subset computations then sit on
top of it (§28, PTS-04/PTS-05).

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

Gaps: no test asserts any ephemeris against an external reference. No test covers precession. No instrumentation
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

---

## 10. Camera pipeline

### 10.1 Production bind (`mobile/src/main/java/dev/pointtosky/mobile/ar/CameraPreview.kt`)

| Item | Repository truth | Evidence |
|---|---|---|
| API / version | CameraX **1.4.2** (camera-core, camera2, lifecycle, view) | `gradle/libs.versions.toml:31,75-78` |
| Camera selection | `cameraSelectorOverride ?: CameraSelector.DEFAULT_BACK_CAMERA`. Production passes no override, so it uses the **logical** default back camera. No zoom call (zoom pinned 1.0× only on the internalDebug override path) | `CameraPreview.kt:60,273,305-331`; `ArScreen.kt:540-550` |
| Preview | `Preview.Builder().build()`: no resolution selector, no target rotation. `PreviewView.scaleType = FILL_CENTER` | `:191,224-227` |
| ImageAnalysis | `STRATEGY_KEEP_ONLY_LATEST`; no `setOutputImageFormat` (YUV_420_888 default); no target rotation; **no resolution selector in production**, so the CameraX default (observed **640×480** on a Pixel 9) | `:238-271`; `docs/validation/cam_2c_pixel9_evidence.md:193-210` |
| Executor | single-thread executor, shut down on dispose or bind failure | `:215,345-346,410-424` |
| Bind | Preview + ImageAnalysis in one `bindToLifecycle`. On `IllegalArgumentException`, falls back to **Preview-only** (then no metadata, geometry stays `MissingFrame`) | `:278-283,371-407` |
| ViewPort / UseCaseGroup | **none** | repository grep; `docs/camera_coordinate_calibration_contract.md:479` |
| Analyzer | `CameraFrameAnalyzer`: reads timestamp, size, rotation, crop and `sensorToBufferTransformMatrix`. **Never reads `imageProxy.planes`** | `mobile/.../ar/camera/CameraFrameAnalyzer.kt:8-41`; `CameraFrameMetadataSource.kt:125-204` |
| Frame timestamps | `imageInfo.timestamp` (start of exposure); paired to the nearest rotation sample, no interpolation, ≤ 50 ms; clock-mismatch heuristic 5 s; history 120 | `core/astro-core/.../FrameRotationPairing.kt:72-127`; `TimestampSyncConfig.kt:21-54` |
| Timestamp source | production never reads `SENSOR_INFO_TIMESTAMP_SOURCE` (only SKY-1 does) | `mobile/src/internalDebug/.../SkyCaptureClock.kt:74-94` |
| Intrinsics | resolved **once per session**: analysis-buffer K′ (needs a non-logical camera + `AXIS_ALIGNED_0` matrix), then active-array K mapped through the matrix, then CAM-1b `PhysicalSensor` FOV, then legacy 56° | `mobile/.../ar/camera/AnalysisBufferIntrinsicsResolver.kt:314-589`; `SessionScopedCameraIntrinsicsResolver.kt:92-108`; `CameraIntrinsicsResolver.kt:83-131`; `core/astro-core/.../LegacyFallbackCameraIntrinsics.kt:32-63` |
| Physical camera ID | read for diagnostics (`CameraManager.physicalCameraIds`). Production never binds one; there is no per-frame physical identity API in 1.4.2 | `CameraPreview.kt:51-59` |
| Distortion | `LENS_DISTORTION` recorded, **never applied** | `CameraCharacteristicsSource.kt:181-192` |
| Exposure / ISO / shutter | production: CameraX auto. SKY-1 (internalDebug): manual `CONTROL_AE_MODE_OFF`, exposure, ISO and frame duration via `Camera2Interop` on ImageAnalysis; presets 0.5 s/ISO1600 (default), 0.125 s/3200, 1 s/800, 2 s/400; per-frame validation | `mobile/src/internalDebug/.../SkyCaptureExposure.kt:191-267,427-447`; `SkySessionCaptureScreen.kt:109-124` |
| Night behaviour | none in production (no scene mode, extensions or low-light APIs) | grep |

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
- **Same physical sensor per frame:** UNKNOWN. On a logical multi-camera, CameraX 1.4.2 gives no per-frame physical
  identity.
- **Same FOV / crop between Preview and Analysis:** not enforced (no ViewPort) and UNVERIFIED.
- **Geometry valid for every analysed frame:** PARTIAL.
  - Per frame: pairing and `CropScaleTransform` are rebuilt (`CameraSessionGeometryProvider.kt:152-165`).
  - Intrinsics are cached for the session (`CameraSessionGeometryProvider.kt:188`;
    `SessionScopedCameraIntrinsicsResolver.kt:100-106`).
  - Only a buffer-size change is caught downstream (`CameraStarPredictor.kt:51-55`).
  - Matrix, crop or zoom changes are not re-validated.

### 10.4 Camera risks for star detection

1. AF is uncontrolled and unlogged: it may hunt in the dark, and a defocused session cannot be recognised afterwards.
2. The production analysis is 640×480 by default, so the plate scale is coarse. The exact °/px depends on the Pixel 9
   main-camera FOV, not established in the repository (TBD BY DEVICE TEST).
3. Auto exposure (production) is unsuitable for faint point sources. Manual exposure exists only in SKY-1.
4. A logical multi-camera (Pixel 9: logical "0", physical "2,3,4") can switch sensors silently (low light, OEM policy).
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
- (b) the camera is not a logical multi-camera;
- (c) a single focal length is reported, plus sensor size and active array;
- (d) the matrix is present and `AXIS_ALIGNED_0`;
- (e) the crop region lies inside the active array;
- (f) the buffer size is unchanged since resolution;
- (g) zoom is 1.0× and the frame content follows CameraX's assumed centre crop (NOT ESTABLISHED,
  `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md:239`).

Unit tests pin this equality (`PixelConventionBridgeTest`, `CalibratedAnalysisBufferProjectionTest`,
`StarMatcherInputTest`). **No device has demonstrated it.**

- **On the Pixel 9 today**, (b) fails. The chain is `UnsupportedLogicalMultiCameraMapping`, then the CAM-1b
  `PhysicalSensor` reference, then `projectStars` returns `IntrinsicsMappingUnavailable(PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED)`
  (`CameraStarPredictor.kt:46-60`). **No projectable prediction exists to compare with.**
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
by §1/§3, though under CameraX 1.3.4. Open PR #242 would remove that claim and should be revisited. No SKY-1 session,
no detector run and no star frame from any device is recorded.

---

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

**Delivery bugs.** Both are BROKEN by code reading and UNVERIFIED ON DEVICE:
- The picker's `snapshotFlow { pickerState.selectedOption }` effect runs after the external and initial-target
  effects. Its first emission calls `setTarget(options[initialIndex])`, and `targetIndexFor` maps every
  Equatorial/Star target to index 4 (POLARIS). So a phone-sent Vega becomes Polaris (`AimScreen.kt:115-118,160-168,248-260,550-563`).
- `WearBridge` uses `MutableSharedFlow(replay = 0)`, and `MainActivity` emits in `onCreate` before `setContent`, so
  cold-start targets are dropped (`wear/.../datalayer/WearBridge.kt:33-45`; `wear/.../MainActivity.kt:135`).

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
- Phone-sent targets are replaced (watch).
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
| `/aim/set_target` P→W | sent from WearMenu demo, Card and AR reticle (`mobile/.../MainActivity.kt:198-245`). PARTIAL: overridden on the watch (§17.1). The STAR kind is never sent. `AimSender`/`AimSetTargetBuilder` are DEAD |
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

**Constraints a new system must respect or remove:**
- Figure geometry is encoded *inside* star ids. There is no explicit edge list, no per-edge style and no branching.
- Line endpoints are PTSKCAT4 records, which have no HIP, so they cannot join the PTSKCAT0/navigation identity space.
- Two projectors (legacy and CAM-2a) exist, and constellations are tied to the legacy one.
- Overlay rebuild runs at sensor rate on the main thread.
- The watch ships v4 data with no figures.
- Constellation identity is an index 0..87 with no localisation keys.

**Clean integration points:**
1. **Data**: a new fourcc section in PTSKCAT4 (the parser looks sections up by fourcc, `PtskCatalogLoader.kt:235-253`),
   or preferably a separate HIP-keyed figure file shared by phone, watch and navigation stars.
2. **Domain**: an `AstroCatalog` accessor next to `asterismsByConstellation` (`Models.kt:501-516`).
3. **Assembly**: `AstroCatalogState` in `ArViewModel.loadAstroCatalog` (`:302-318`).
4. **Projection**: one projector service (CAM-2a when calibrated, legacy otherwise) replacing the local
   `projectStarRecord` closure in `calculateOverlay`.
5. **Output**: `OverlayData` / `ScreenLineSegment` consumed by `ConstellationLayer`; on the sky map,
   `ConstellationProjection` / `drawConstellations`.
6. **Build**: `res/build_catalog_variant_b.py` + `res/<abbr>.json`.

The constellation layer should consume the same *corrected pose* as navigation (§28), so graphics register with the
real sky once optical correction exists.

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
| camera ID, logical flag, physical IDs | yes | CAM diagnostic JSON `cam2c` (internalDebug, `CamDiagnosticSnapshotJson.kt`, schema v4) |
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
- AF/lens fields (mode, state, focus distance, lens state) in both the JSON and the sky log (schema v3);
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
- a CameraX default analysis buffer of 640×480 with rotation 90;
- CameraX 1.4.2 matrix `AXIS_ALIGNED_0` (uniform scale ≈ 0.157 plus a symmetric crop);
- calibrated intrinsics blocked by the logical-multi-camera gate;
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
| Near horizon | NOT TESTED (CAM-2a has no horizon gate; refraction off) | session with alt < 15° stars; residual vs altitude |
| Zenith | NOT TESTED | session at alt > 75°; azimuth singularity behaviour |
| Magnetically disturbed area | NOT TESTED (no interference detection) | session near a steel structure; optical correction recovers the yaw error |
| Loss / reacquisition | NOT TESTED (no tracker) | cover the lens, uncover; time to relock |
| Watch Aim to a phone-sent target | NOT TESTED (code-reading bug, §17.1) | send Vega from the Card; the watch shows Vega |
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

**Candidate-set reduction.** The matcher's catalog work scales with the candidate count. Rough sizes (estimate):
- the whole PTSKCAT0 catalog at mag ≤ 8.0 is 41,487 stars;
- PTSKCAT4 is 9,241;
- a navigation set of O(10²) stars (size TBD), filtered to above-horizon (about ½) and then to an attitude-uncertainty
  cone around the camera FOV (on the order of 1/10–1/30 of the sphere for a phone FOV plus margin), leaves
  **O(10¹)** candidates per frame.

That turns pair/triangle hypothesis generation from infeasible-per-frame into trivially cheap. With a valid tracker
state, `priorProjections` (time-stamped) can reduce per-frame association to gated nearest-neighbour checks after
verification. Both require the missing spatial index (or the nav-set-only scan) and the candidate predictor.

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
| Wrong camera (logical switch) | undetectable | detect via scale change in the solve; pin a physical camera or zoom | zoom pinned only in the debug override | provenance | P1 |
| Incorrect intrinsics | Pixel 9 → unprojectable; legacy 56° elsewhere; labelled CALIBRATED loosely | the matcher estimates scale; label honestly | CAM-2c gates | scale-tolerant matcher | P0 |
| Wrong location | phone renders at (0,0); watch NO_LOCATION | block with UNAVAILABLE(location) | watch gate only | phone gate | P1 |
| Stale location | watch Identify keeps stale fixes; TTL elsewhere | age displayed; degrade | TTLs (120 s remote) | consistency | P2 |
| Wrong time | system clock trusted | sanity check vs GNSS time (optional) | none | — | P3 |
| Magnetic interference | invisible; Aim confidence uses azimuth variance only | detect field magnitude/inclination anomaly; optical correction overrides | accuracy status (watch Identify only) | detection + optical | P1 |
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
- The detector design (`StarDetector`, `TiledBackground`, `LumaFrame`) as the base for on-device detection.
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
- `PredictedStarCatalogAdapter`: replace whole-sky brightest-200 with the candidate predictor (horizon + cone + nav set).
- `CameraStarPredictor`: optional horizon classification.
- `CameraPreview` (production): luma tap, analysis resolution policy, AF/AE policy, optional ViewPort.
- `AnalysisBufferIntrinsicsResolver`: honest quality labels; a Pixel 9 strategy (§28.2).
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
- The wear `stars_real.bin` (unused 183 KB), *unless* PTS-17 adopts it for watch parity.
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
- **Direction:** a constrained hypothesis matcher over the visible nav-star candidates: angular pair/triangle
  invariants among the brightest detections, tolerances from `AnalysisBufferScale`, verification by projection,
  Wahba/SVD attitude, a scale-tolerant first solve (§28).
- **Depends on:** G-04, G-05.
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
- **Evidence:** §10.2, §11.3–11.4.
- **Affected:** intrinsics, AF, resolution.
- **User impact:** projections are unusable or wrong.
- **Risk:** high (device-specific).
- **Direction:**
  - explicit AF policy (manual focus at infinity when `LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` and the manual-focus
    capability exists; otherwise documented continuous), logged;
  - an analysis-resolution policy above 640×480 (value TBD BY DEVICE TEST);
  - a Pixel 9 intrinsics strategy that does not need `ProvenActiveArrayLocal` (accept logical static intrinsics at
    1.0× as *approximate* and let the matcher estimate focal scale; or bind a physical camera);
  - honest quality labels.
- **Depends on:** —.
- **Evidence:** Pixel 9 sessions with focus logs; fx estimated by the solver vs the static value.

**G-04 · No visible navigation-star candidate predictor.**
- **Evidence:** §7, §9.
- **Affected:** CAM-2b adapter, SKY-1, the future matcher.
- **User impact:** a matcher would be slow and ambiguous.
- **Risk:** low.
- **Direction:** a navigation-star data file (HIP-keyed) plus a pure-JVM `VisibleCandidatePredictor(time, location,
  rough attitude ± σ, camera FOV)`: above-horizon (with margin), then a cone of `enclosingConeRadiusRad + attitude
  margin`, then a magnitude cut. Add a spatial index for PTSKCAT0.
- **Depends on:** G-06.
- **Evidence:** unit tests; replay of real sessions shows the true stars ⊂ candidates.

**G-05 · No real night-sky dataset.**
- **Evidence:** no session or frame in the repository (§22.1); no doc reports a real run.
- **Affected:** all tuning.
- **User impact:** unknown performance.
- **Risk:** high.
- **Direction:** a Pixel 9 capture campaign with SKY-1 (after the AF/log fields land); store sessions outside git and
  commit a small fixture subset.
- **Depends on:** G-03 (AF logging).
- **Evidence:** `sky-session-loader` reports.

**G-06 · Astronomy truth defects.**
- **Evidence:** §8.3.1–3 (ephemeris −1 day; no precession; self-referential golden).
- **Affected:** all body positions on both devices; all stars (≈0.36°).
- **User impact:** the Moon is ≈13° wrong; planets and the Sun are about 1° wrong; star overlays carry a systematic
  offset.
- **Risk:** low (fixes are well understood).
- **Direction:** fix the epoch; external-reference tests (Meeus examples / JPL Horizons values hard-coded); a
  precession seam.
- **Depends on:** —.
- **Evidence:** unit tests against published values.

### P1 — required for reliable release

- **G-07 · No temporal tracker / correction fusion.** Evidence §15. Direction: correction quaternion with age and
  decay; gyro-propagated `priorProjections` with timestamps; re-verification; reacquisition.
- **G-08 · No confidence / false-lock protection.** No code. Direction: inlier count, residual, ambiguity ratio,
  sensor-prior consistency; thresholds TBD BY DEVICE TEST.
- **G-09 · Watch navigation bugs.** Evidence §17.1, §18:
  - picker overrides external targets;
  - cold-start drop;
  - unbounded body fallback in Identify;
  - stale fix in Identify;
  - dead offline star resolver;
  - Tonight tile never gets a location;
  - ineffective phone heading override;
  - Aim controller tests excluded.
- **G-10 · Phone has no navigation guidance and renders at (0,0) without a location.** Evidence §17.2, §8.3.8.
- **G-11 · CI does not run the core suites.** `android-full` has been dead since 2026-02-13. astro-core (677),
  core:astro, wear:sensors, tools and internalDebug tests do not run on PRs. Wear `DefaultAimControllerTest` is
  excluded.
- **G-12 · Magnetic interference invisible.** Evidence §14. Direction: field-magnitude/inclination check vs WMM;
  surface it in the state.
- **G-13 · Overlay computation on the main thread at sensor rate.** Evidence §24.
- **G-14 · Phone/watch catalog and algorithm divergence.** Evidence §18.

### P2 — quality / performance / UX

- **G-15** Debug screens exposed in public builds (both devices).
- **G-16** `ArScreen.kt` monolith; no phone back stack.
- **G-17** Duplicate parsers, tables and math (two `const_v1` parsers, two NELM tables, two alt/az implementations,
  three `JulianDate` copies).
- **G-18** No lens distortion; no rolling shutter; start-of-exposure pose pairing.
- **G-19** Inconsistent refraction usage (SkyMap).
- **G-20** Missing translations and hard-coded strings.

### P3 — later

- **G-21** Constellation graphics data model (HIP-keyed edges, labels, art, watch).
- **G-22** Global light-pollution grid (one 10° tile today).
- **G-23** Topocentric Moon, more planets (Mercury, Venus, Mars absent: `SimpleEphemerisComputer.kt:15-20`), DSOs.
- **G-24** Tiles → ProtoLayout migration.

---

## 28. Recommended target architecture (smallest path to Goal 1)

### 28.1 Pipeline (adapting existing components)

```text
ObservationContext                         (NEW, pure JVM)  time (frame timestamp → UTC), location+age,
  ├─ camera model  ◀── CameraSessionGeometry + AnalysisBufferScale (EXISTING), quality + scale uncertainty
  └─ rough attitude ◀── paired rotation sample (EXISTING FrameRotationPairing) ⊗ last optical correction
        ↓
VisibleCandidatePredictor                  (NEW) nav-star set (NEW data) ∪ optional PTSKCAT0 cone via
        ↓                                   StarCatalogQuery (EXISTING port + NEW spatial index); precession (NEW)
Predicted candidates in/near FOV           EquatorialStarDirection[] + PredictedStarProjection[] (EXISTING types,
        ↓                                   + NEW timestamp/attitude provenance wrapper)
Detector                                   detectStars (EXISTING) on a production luma tap (NEW), capped + SNR
        ↓
Constrained matcher                        (NEW) StarMatcherInput (EXISTING) → hypotheses → verification
        ↓
OpticalAttitudeSolution + confidence       (NEW) R_world←camera, inliers, residual, ambiguity, scale estimate
        ↓
Temporal tracker                           (NEW) states (§28.3); correction q_corr = R_opt · R_sensor⁻¹ with age
        ↓
CorrectedPoseSource                        (NEW interface) consumed by navigation + overlay + constellations
        ↓
Navigation domain (shared AimController)   (EXISTING watch controller → moved to shared module)
        ↓
Phone UI (overlay via CAM-2a projector) / Wear (Aim, optionally phone-supplied correction)
```

### 28.2 Ownership and interfaces

| Layer | Owns | Must not know about |
|---|---|---|
| Astronomy truth (`core/astro-core` time/transform/ephem + precession) | JD, LST, RA/Dec ↔ Alt/Az, bodies | camera, UI |
| Camera geometry (`camera/*`, mobile camera session) | intrinsics + quality, pairing, crop/rotation, luma tap | catalog, matching |
| Detection (`camera/detect`) | `DetectedSource[]` per frame | catalog, pose |
| Candidate prediction (new, astro-core) | visible nav-star candidates + priors with timestamps | pixels |
| Matching (new, astro-core) | correspondences, attitude, confidence; **stateless per call** | UI, Android |
| Tracking (new, astro-core) | state machine, correction, decay, reacquire policy | Android |
| Navigation domain (shared) | target, guidance vector, phase/lock | camera internals |
| UI (phone / watch) | rendering of navigation + autodetect state | math |

**Pixel 9 strategy.**
- Do not wait for `ProvenActiveArrayLocal`.
- Treat logical-camera static intrinsics at zoom 1.0× as **APPROXIMATE**, with an explicit focal-scale uncertainty
  (magnitude TBD BY DEVICE TEST).
- Make the first solve **scale-tolerant**: angular invariants are ratios, or a small scale search. Feed the estimated
  scale back as a session refinement.
- This turns the CAM-2c block from a prerequisite into a refinement.

### 28.3 Proposed navigation / autodetect state model (input to the redesign)

The watch `AimPhase` already covers the *pointing* half (SEARCHING / IN_TOLERANCE / LOCKED / BELOW_HORIZON /
NO_LOCATION). The code suggests **two orthogonal state machines** rather than one combined list:

**A. Pose-quality machine** (autodetect / tracking; phone first, optionally shared with the watch):

| State | Entry | Exit | Data required | UI must communicate |
|---|---|---|---|---|
| UNAVAILABLE | no camera / no permission / no location / no time / no rotation sensor | prerequisite restored → PREPARING | reason enum | what is missing and how to fix it |
| PREPARING | session bound; waiting for intrinsics, first paired frame, exposure settle | geometry Ready → SEARCHING | geometry status | "starting camera" |
| SENSOR_ONLY | autodetect disabled/unsupported, or repeated failure; navigation still possible | user enables / conditions improve | sensor accuracy, magnetic flag | pointing is approximate |
| SEARCHING | geometry ready, no accepted solution | candidate hypothesis → CANDIDATE | detections, candidates | "looking for stars"; detection/candidate counts (debug) |
| CANDIDATE | hypothesis passes invariants, not yet verified across K frames (K TBD) | verified → LOCKED; rejected → SEARCHING | correspondences, residual, ambiguity | tentative |
| LOCKED | verified solution; correction applied | residual/inliers degrade → LOW_CONFIDENCE; detections lost → LOST | correction, inliers, residual, age | pointing is camera-corrected |
| LOW_CONFIDENCE | lock degrading (few stars, cloud, blur) | recovers → LOCKED; times out → LOST | confidence trend | correction may be stale |
| LOST | no verification for T s (TBD) | reacquire attempt → RECOVERING | last correction + age | using the last known correction / sensor only |
| RECOVERING | full solve with a widened prior (correction kept, decaying) | success → LOCKED; timeout → SEARCHING | — | re-finding stars |

The prompt's `ACQUIRED` collapses into CANDIDATE→LOCKED. `ALIGNING` belongs to the pointing machine, not the pose
machine.

**B. Pointing / guidance machine** (the existing `AimPhase`, generalised and moved to a shared module):
NO_TARGET → (NO_LOCATION | BELOW_HORIZON | SEARCHING) → IN_TOLERANCE (= "aligning") → LOCKED. Inputs: the corrected
pose from A plus the target alt/az. The UI shows the guidance vector, tolerance and hold progress, and **annotates
the guidance with A's pose quality**.

---

## 29. PR roadmap

Each PR is independently reviewable. "Device" means a Pixel 9 (phone) and a Wear OS watch, with evidence committed
to `docs/validation/`.

### Phase A — establish truth / unblock autodetect

**PTS-01 · Ephemeris epoch fix + external reference tests**
- **Goal:** correct the Sun, Moon, Jupiter and Saturn positions.
- **Scope:** `JD_AT_2000_01_01_00UT` → 2451543.5 (with a rename); external-reference test values (Meeus ch.25 / 47
  worked examples, plus a few hard-coded JPL Horizons vectors); regenerate `ephem_golden_v1.json`; stop
  auto-regeneration from being the only check.
- **Files:** `core/astro-core/.../ephem/SimpleEphemerisComputer.kt`, `core/astro/src/test/.../ephem/*`.
- **Depends on:** —.
- **Tests:** new reference tests within stated tolerances.
- **Device:** none needed.
- **Exit:** the Sun is within 0.05° and the Moon within 0.5° of the references (exact tolerances chosen in the PR from
  the model's documented accuracy).

**PTS-02 · CI truth**
- **Goal:** every PR runs the pure-JVM and core Android unit suites.
- **Scope:** add `:core:astro-core:test`, `:core:astro`, `:core:catalog`, `:wear:sensors`, `:tools:*` and
  `testInternalDebugUnitTest` to the PR workflow; get `android-full` running; fix or quarantine-with-issue the known
  `RedactorTest` / `core:location` failures; port `DefaultAimControllerTest` to virtual time and un-exclude it.
- **Files:** `.github/workflows/*`, `wear/build.gradle.kts`, the affected tests.
- **Depends on:** —.
- **Exit:** green CI with those suites on a PR.

**PTS-03 · J2000 → of-date precession seam**
- **Goal:** remove the ≈0.36° systematic offset.
- **Scope:** an IAU 1976 (Lieske) precession helper; applied once at the catalog → topocentric boundary
  (`equatorialToLocalSky`, `raDecToAltAz` callers, or a `StarCatalogQuery` decorator); bodies kept in a consistent
  frame.
- **Files:** `core/astro-core/.../transform`, `prediction/LocalSkyDirection.kt`.
- **Tests:** Meeus Ex. 21.b.
- **Exit:** the reference example within 1″.

**PTS-04 · Navigation-star set (data + loader)**
- **Goal:** a curated, HIP-keyed set.
- **Scope:** selection rules (brightness, isolation radius, sky coverage; final numbers decided in the PR and
  justified); generator in `tools/catalog-packer`; asset (both devices); runtime resolution HIP → PTSKCAT0 record
  index; tests on the shipped asset (count, uniqueness, every HIP resolves).
- **Depends on:** —.
- **Exit:** set loaded on both devices.

**PTS-05 · Production luma tap**
- **Goal:** pixels in production behind a flag.
- **Scope:** stride-aware Y-plane copy into a reused `LumaFrame` buffer in `CameraFrameAnalyzer`; the analysis
  resolution policy (request ≥ 1280×720, final value TBD BY DEVICE TEST); a flag (off by default in public).
- **Files:** `mobile/.../ar/CameraPreview.kt`, `mobile/.../ar/camera/CameraFrameAnalyzer.kt`.
- **Tests:** unit (stride/pixelStride); instrumentation if available.
- **Device:** Pixel 9 frame latency and resolution captured.
- **Exit:** the luma frame is available per frame with measured cost.

**PTS-06 · Night camera policy + AF/lens diagnostics**
- **Goal:** controlled focus/exposure, logged.
- **Scope:** AF policy (manual infinity where supported, else continuous; documented); production AE policy for star
  mode; AF mode/state, `LENS_FOCUS_DISTANCE` and `LENS_STATE` added to the CAM JSON and the sky log (schema v3 with
  migration); `SENSOR_INFO_TIMESTAMP_SOURCE` read in production.
- **Files:** `CameraPreview.kt`, `SkyCaptureExposure.kt`, `skylog/*`.
- **Device:** Pixel 9 focus-distance evidence.
- **Exit:** a logged focus distance in a real session.

**PTS-07 · Visible candidate predictor + PTSKCAT0 spatial index**
- **Goal:** O(10¹) candidates per frame.
- **Scope:** `VisibleCandidatePredictor` (pure JVM): horizon margin, attitude-uncertainty cone, magnitude cut,
  nav-set first; a Dec-band index in `PtskCat0Catalog`; CAM-2b adapter and SKY-1 switched to it; a horizon
  classification option in `CameraStarPredictor`.
- **Depends on:** PTS-03, PTS-04.
- **Tests:** property-style coverage (true FOV stars ⊂ candidates under the stated attitude error).
- **Exit:** the CAM-2b overlay uses the predictor.

**PTS-08 · Pixel 9 intrinsics strategy**
- **Goal:** a projectable camera model on the Pixel 9.
- **Scope:** an explicit `APPROXIMATE_LOGICAL_STATIC` quality with a scale-uncertainty field; honest
  `CameraGeometryQuality` labels; pinned zoom 1.0× in production star mode; documented decision vs physical binding.
- **Files:** `AnalysisBufferIntrinsicsResolver.kt`, `CameraIntrinsicsResolution.kt`.
- **Device:** a Pixel 9 overlay screenshot plus diagnostics.
- **Exit:** `projectStars` Ready on the Pixel 9 with an honest label.

**PTS-09 · Real night-sky capture campaign (docs + fixtures)**
- **Goal:** a dataset.
- **Scope:** procedure doc; ≥ N sessions covering the §23 matrix rows (N TBD); off-repo storage; a small committed
  fixture (cropped frames + jsonl) for JVM tests; `sky-session-loader` report committed.
- **Depends on:** PTS-05/06/08.
- **Exit:** detector metrics on real sky are recorded.

### Phase B — identification

**PTS-10 · Matcher v1 (lost-in-space-lite, constrained)**
- **Scope:** pure JVM; brightest-K detections × candidates; angular pair/triangle invariants via `cameraRayFor` /
  `angleBetweenRad` (merge or supersede PR #236); scale-tolerant hypothesis generation; verification by projecting
  all candidates; inlier set.
- **Tests:** synthetic scenes (rotations, scale error, missing/extra sources, hot pixels, mirrored sky, wrong time).
- **Exit:** the synthetic suite plus PTS-09 fixtures identify correctly.

**PTS-11 · Attitude solve + scale refinement**
- **Scope:** Wahba via SVD (or QUEST) from (camera ray, world direction) pairs; residuals in px and arcmin; optional
  focal-scale estimate.
- **Tests:** known-rotation recovery; noise sensitivity.

**PTS-12 · Confidence + false-lock protection**
- **Scope:** inlier count, RMS, ambiguity ratio, sensor-prior consistency → `MatchVerdict`; negative test corpus.
- **Exit:** zero confident locks on the negative corpus; thresholds recorded as TBD BY DEVICE TEST until PTS-09 data
  calibrates them.

**PTS-13 · Matcher in the offline loader**
- **Scope:** `tools/sky-session-loader` runs detect → predict → match → solve on sessions and reports identification
  rate and residuals.
- **Exit:** a real-session report committed.

### Phase C — stable navigation

**PTS-14 · Tracker + correction fusion**
- **Scope:** the §28.3 A-machine; `q_corr` with age and decay; time-stamped `priorProjections` propagated by the
  rotation-vector delta; gated re-association followed by re-verification; periodic full solve; reacquisition.
- **Tests:** scripted sequences (cloud gap, fast pan, single star, stale prior).

**PTS-15 · On-device integration**
- **Scope:** an analyzer-thread pipeline (detect → predict → match/track) throttled to a measured budget; a
  `CorrectedPoseSource` interface; the AR overlay switched from the legacy 56° projector to the CAM-2a projector +
  corrected pose; alt/az at 1 Hz off the main thread.
- **Device:** Pixel 9 latency and FPS; lock under hand motion.

**PTS-16 · Shared navigation domain + phone guidance**
- **Scope:** move `AimController`/the phase machine into a shared module; phone guidance (arrow / offset / lock /
  haptics) driven by the corrected pose; the phone location gate (no (0,0) rendering).

**PTS-17 · Watch correctness + parity**
- **Scope:**
  - Aim target arbitration (no picker override), `WearBridge` replay;
  - Identify body cap + stale-fix handling;
  - Tonight tile location injection;
  - `OfflineStarResolver` → a shipped catalog;
  - phone heading override on `forward` with a consistent sign;
  - declination cache;
  - decide the watch catalog (PTSKCAT0 6.5 or the nav set);
  - a parity test fixture (same time/location/target → same alt/az on both code paths);
  - optional phone → watch correction message (`/sensor/correction`, versioned).

### Phase D — validation

**PTS-18 · Device validation matrix:** the §23 rows executed on a Pixel 9 and a watch; results committed.

**PTS-19 · Performance:** JVM microbenchmarks (detector, predictor, matcher) plus on-device traces; allocation fixes
(no boxing, reused buffers).

**PTS-20 · Failure handling:** the §25 rows implemented as states and reasons; magnetic interference flag.

### Phase E — redesign foundation

**PTS-21** Gate debug UI on both devices (`internal` flavor / debug); move LocationSetup out of "Debug tools".

**PTS-22** Phone navigation shell with a back stack; extract `ArScreen` into presenter + composables; kill dead
screens.

**PTS-23** UI state contracts: expose the A/B machines (§28.3) as immutable UI state on both devices.

### Phase F — visual redesign

**PTS-24…** Per-screen redesign PRs against PTS-23 state contracts (requirements to be supplied).

### Phase G — constellation graphics (requirements to be supplied separately)

**PTS-3x** HIP-keyed figure data model shared with the nav set; a single projector consuming the corrected pose; great-circle
segment subdivision and clipping; labels and localisation keys; watch support.

---

## 30. Definition of "autodetect complete"

Each criterion is measurable. Values without repository evidence are **TBD BY DEVICE TEST**.

1. **Detection on real frames.** On recorded Pixel 9 sessions, at least X% (TBD BY DEVICE TEST) of predicted
   in-image nav stars brighter than mag M (TBD) are detected within R px (TBD), measured by `sky-session-loader`.
2. **Prediction correctness.** On real sessions with a verified optical solution, every true in-FOV nav star is in
   the candidate set (100% recall), and the candidate count per frame is ≤ C (TBD; target O(10¹)).
3. **Constrained matching.** The matcher never queries more than the predictor's candidate set. There is no
   whole-catalog scan per frame (a code-review criterion and a unit-test assertion).
4. **Correct optical correction.** On locked frames, the RMS residual is ≤ E arcmin (TBD) and the corrected pointing
   error vs the solved attitude is ≤ T° (TBD).
5. **No silent false lock.** On a negative corpus (wrong time ±1 h, wrong location, mirrored frames, random noise,
   urban frames without stars), there are zero LOCKED states. Ambiguous scenes stay in SEARCHING/CANDIDATE.
6. **Lock survives normal hand motion.** On the recorded hand-held sequences, the lock is retained for ≥ P% (TBD) of
   frames.
7. **Recovery.** After a cover/uncover or a cloud gap of D s, the system reaches LOCKED again within ≤ S s (TBD).
8. **Navigation uses the corrected pose.** Phone guidance and the overlay consume `CorrectedPoseSource`. The legacy
   56° projector is not on the star-mode path (code criterion).
9. **Latency.** Frame-to-overlay latency ≤ L ms and pipeline rate ≥ F Hz on a Pixel 9 (TBD).
10. **Phone/watch consistency.** The parity test passes. Phone- and watch-computed target alt/az agree within Q°
    (TBD) for the same inputs. The watch shows the phone-sent target.
11. **Diagnostics.** The §21 "add" list is present in the CAM JSON and sky log, including AF and focus distance.
12. **Real-device evidence.** Every §23 matrix row has a committed validation record with session ids.
13. **Astronomy truth.** The PTS-01 and PTS-03 reference tests pass.

---

## 31. Open questions backed by repository evidence

1. **Pixel 9 intrinsics.** Accept logical static intrinsics as approximate and solve scale optically (recommended),
   or invest in physical binding (`setPhysicalCameraId`)? The recon predicts the matrix stays logical-basis under
   physical binding (`docs/recon/cam_2c_sensor_to_buffer_domain_recon.md:134-155`, UNVERIFIED).
2. **Analysis resolution.** What resolution and frame rate can the Pixel 9 sustain for detect + match? The repository
   only knows the 640×480 default and the SKY-1 options (1280×720 default, 1920×1080).
3. **AF behaviour at night.** What does CameraX's default AF actually do on the Pixel 9 in the dark? It is never
   logged. Is manual focus supported (`LENS_INFO_MINIMUM_FOCUS_DISTANCE`, `MANUAL_SENSOR`)?
4. **Does SKY-1's Camera2Interop on ImageAnalysis also govern the Preview stream?** UNVERIFIED (§10.1).
5. **Watch catalog.** Should the watch adopt PTSKCAT0 (the unused 8,920-star asset), the nav set, or keep the PTSKCAT4
   904-star file? Today it ships both and uses only the latter.
6. **Wear `star.bin` provenance.** Which script and magnitude limit built it? UNKNOWN (§6.3).
7. **Open PR #242** corrects the README's Pixel 9 claim in the wrong direction (§11.4). **PR #236** should be merged
   or folded into PTS-10.
8. **Release signing.** `Android Release Artifacts` bundle jobs are red on every main push. The previous recon
   attributes this to the missing `storeFile`; the current run's cause is UNVERIFIED.
9. **`fix-volatile-running.diff`.** Delete it? It no longer applies (§26).
10. **Navigation-star selection rules.** Number of stars, isolation radius, inclusion of planets as optical
    references: product decision, no repository precedent.
11. **Phone → watch optical correction.** Should a phone-derived correction be sent to the watch? The two devices
    have different forward axes and independent magnetometers, so a phone yaw correction does not transfer to the
    watch frame without a shared reference. The repository has no precedent.
12. **Does the watch `DefaultAimControllerTest` still pass?** It has been excluded for an unrecorded period
    (`wear/build.gradle.kts:132-148`).

---

## Appendix A — Recon method

- **Repository and branches:** `git fetch origin '+refs/heads/*:refs/remotes/origin/*'` (248 refs); for history,
  `git log --remotes -S` for `CONTROL_AF_MODE` and `FocusMeteringAction`, and `--grep` for matcher terms.
- **GitHub:** the API was used to list PRs (#236 and #242 open).
- **Assets:** Python `struct` parses of every catalog asset header (scratch scripts, not committed).
- **Code:** call-site tracing with ripgrep for `detectStars`, `projectStars`, `StarMatcherInput`, `.nearby(`, AF and
  exposure keys, and data-layer paths.
- **Ephemeris cross-check:** an independent Python Schlyter + Meeus Sun computation for 2025-01-01T00Z (§8.3).
- **Gradle:** could not run in this container (no Android SDK, JDK 21 only, Maven 429, license plugin unresolved).
  Test status is taken from source counts, the 2026-09-02 recon and the GitHub Actions history.
- **No production code was changed.** The only file added is this document.
