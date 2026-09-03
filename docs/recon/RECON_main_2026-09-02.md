# RECON — `main` state audit — 2026-09-02

Read-only snapshot of `origin/main` at `b6bdc7c` (Merge PR #235, 2026-08-07 23:12 -0600).
Every claim below cites the command or file it came from. "UNVERIFIED" = could not be checked in this environment.

Environment: remote Linux container, no Android SDK, no `gh` CLI (GitHub MCP tools used instead), JDK 21 preinstalled
(JDK 17 installed via apt for this run), network via proxy. Android SDK for unit tests was auto-downloaded by AGP
(`android.builder.sdkDownload=true`) into a scratch `ANDROID_HOME` — see §6 for what that did and did not allow.

## 1. TL;DR

1. **Merged into `main`:** every PR from #187 through #235 (49 merge commits, `git log --merges`). That includes CAT-1
   (#193/#194), CAM-0..CAM-2c (#204–#229), SKY-1 (#230/#231), SKY-2 (#232, sigma fix #234), SKY-3 (#233 loader, #235 matcher input).
2. **NOT in `main`:** PR #186 (AR GPS location wiring). Its merge commit `d46ae40` is the tip of `origin/feature/sqm-grid-v3`
   and is **not an ancestor of main** (`git merge-base --is-ancestor` → NO).
3. **`main` history was rebuilt ~2026-07-01**: 189 commits, **4 root commits** (`git rev-list --max-parents=0 main`).
   191 of 241 remote branches share **no common ancestor** with main; all of those last moved on/before 2026-06-27 → abandoned.
4. **Open PRs (GitHub API):** #236 (`claude/camera-ray-angle-api-adb9hj`, 3 ahead / 0 behind, 2026-08-08), plus stale
   #129 and #128 (codex, Dec/Nov 2025, unrelated history).
5. **The three "in-progress SKY PRs" are all merged** (#233 loader, #234 residual sigma, #235 matcher input). Only the
   `angleBetweenRad` follow-up (#236) is open.
6. **No `LICENSE` file at repo root.** `NOTICE.md` exists and credits HYG v4.2 (CC BY-SA 4.0), BSC5, d3-celestial (BSD-3), NASA Black Marble/VIIRS.
7. **README is a Russian-language Wear "Tonight Tile (tests)" stub** (99 lines); no architecture/build overview. `MODULES.md`, `FILE_OVERVIEW.md`, `PROJECT_OVERVIEW.md` exist but are partly stale (e.g. calls `:core:astro-core` an Android library; it is `kotlin("jvm")`).
8. **Assets verified:** `bortle.bin` = PTSKLP01 **v3**, 800×800 @ 0.0125°, latTop 60 / lonLeft −120 (a 10°×10° regional tile, not global).
   Phone catalog PTSKCAT0 v1: 41 487 stars, mag ≤ 8.00, 16-byte records, 705 889 B (~689 KB). Watch: 8 920 stars, mag ≤ 6.50, 183 698 B.
9. **Pixel 9 blocker (CAM-2c) is unresolved by design**: `AnalysisBufferScale.forGeometry` still throws for a PhysicalSensor
   reference (KDoc says so explicitly); the `LegacyFallback` path exists only as the diagnostic overlay mode in `PredictedStarOverlayReducer`. No device evidence collected (SPRINT_STATUS §"STILL BLOCKED").
10. **Build/test:** all pure-JVM suites green (astro-core 677, catalog-packer 34, sky-session-loader 14, detector+matcher 106/106); mobile 759/759, wear 9/9, core:catalog 99/99, core:astro 99/99 green. **Broken on main:** `core:time` 1 failing test (`ZoneRepoTest`, unmocked `IntentFilter`), `core:location` and `core:logging` unit tests do not compile, `wear:sensors` unit tests hang forever (`DelegatingOrientationRepositoryTest`), wear `androidTest` does not compile (`ServiceScenario`), release bundle unsigned → `Android Release Artifacts` red on every main push; nightly `Android Full` dead since Feb 2026. ktlint: 5 238 advisory violations (ignoreFailures on), `CardScreen.kt:196` still flagged; `core:location` test file unparsable.

## 2. Git state

### 2.1 Sync

```
git fetch --all --prune && git checkout main && git pull   → "Already up to date"
git status --porcelain                                     → (empty: clean)
git rev-parse HEAD                                         → b6bdc7c97bcc1edff7322211d2fd5c9c09c20b37
git rev-list --count main                                  → 189
git rev-list --max-parents=0 main                          → 56009a0, d975329, 504abc5 (Merge PR #187), 675e37c   (4 roots)
```

The four root commits are dated 2026-06-30..2026-07-08. Main is an orphan/rebuilt history; the pre-July history lives only
on the old branches (`git merge-base main origin/feature/sqm-grid-v3` → empty).

### 2.2 `git log --oneline -40 main` (verbatim)

```
b6bdc7c Merge pull request #235 from Inneren12/claude/matcher-input-contract-f24g5y
fa636dc SKY-3: normalize catalog queries at the port, refresh stale docs
a3771fe Merge remote-tracking branch 'origin/main' into claude/matcher-input-contract-f24g5y
24c5e7d SKY-3: snapshot StarMatcherInput's lists, wrap RA before converting to degrees
31c0541 Merge pull request #233 from Inneren12/claude/sky-session-loader-mnyte3
ba62c3e Merge pull request #234 from Inneren12/claude/tiled-background-gradient-sigma-nst0lz
191460b SKY-3 review fixes: canonical pixel->ray inverse, per-edge FOV, exact magnitude cut
2788e74 SKY-2 review fixes: sort real residuals, and split the max-component contract
3a1ecf3 SKY-3: score the detector against the replayed projection, not recorded pixels
9228bf2 SKY-3: matcher input contract (catalog port, angular scale, input DTO)
f43f245 SKY-2: measure the per-tile noise sigma on the background-subtracted residual
5ac5d86 SKY-3: session-log loader tool (tools/sky-session-loader)
4830be8 Merge pull request #232 from Inneren12/claude/star-detector-impl-9v5cmf
a2d7d5d SKY-2: score the detector only against in-image predicted sources
4141f12 SKY-2 fixes: real tile centres, and pin the detector to the production pixel convention
3dd0860 SKY-2: pure-JVM star detector, synthetic frames and detection metrics
f719f99 Merge pull request #231 from Inneren12/claude/sky1-observer-context-gate-ykqwjf
ec96a2e SKY-1: gate recording on observer context, and stop overstating clock alignment
df1eab4 Merge pull request #230 from Inneren12/claude/sky-session-log-capture-ctolgd
bf6b76e SKY-1 fixes: session isolation, write serialization, exposure join and gate
436b5d9 SKY-1b/c: on-device sky session capture, manual exposure and format docs
26d05b8 SKY-1a: pure sky session-log model, JSONL codec and offline replay
e2d3154 Merge pull request #229 from Inneren12/claude/cam-2c-printable-target-blockers-6puqhp
17e1c5c CAM-2c: fix printable-target SVG sharing and reject overlapping target circles
b0ed3f2 Merge pull request #228 from Inneren12/claude/cam-2c-frame-content-correspondence-obcvrc
1c15da0 Fix CAM-2c frame-content Freeze correctness and orientation auditability
f8e445e Fix CAM-2c frame-content correspondence experiment: remove circular pose/verdict bias
b070e09 CAM-2c: frame-content correspondence experiment (internalDebug-only)
9ec1a2d Merge pull request #227 from Inneren12/claude/cam-2c-mobile-layout-7v2vj2
0ced1a9 CAM-2c physical experiment: fix compact status semantics, correct validation claims
b700f67 CAM-2c physical-camera experiment: fix mobile layout usability
888fbc9 Merge pull request #226 from Inneren12/claude/cam-2c-architecture-leak-x0b778
0c69383 docs: record the CAM-2c architecture-leak fix pass and validation results
80bb5a1 CAM-2c: fix the resolution-seam boundary test - Compose forbids @Composable references
2f53022 CAM-2c: narrow the dual-basis resolution-request seam to internal, never public API
a52f574 Merge pull request #225 from Inneren12/claude/cam2c-sensor-buffer-recon-ny8csj
066ab5a CAM-2c dual-basis fix: model-match structural scope, aspect family, verdict split, stability honesty
ca572fe CAM-2c dual-basis diagnostic: labelled logical/physical basis evidence, geometry classifier, CameraX 1.4.2 model
d8b0c4e CAM-2c recon: correct verdict terminology and 16:9 overflow direction
fef59c2 CAM-2c recon: source-trace CameraX 1.4.2 sensor-to-buffer matrix domain
```

### 2.3 PRs ≥ #180 in `main` history (from `git log --merges` + `git diff --stat <merge>^1 <merge>`)

Ordered oldest → newest. Summary is from the diff stat (files touched), not the title.

| PR | merged | files / +/- | what changed (from diff stat) |
|---|---|---|---|
| #187 | 2026-06-30 | (root commit `504abc5`, no parent → no diff) | Bortle→continuous sky-brightness; survives only as an orphan root of the rebuilt main |
| #189 | 2026-07-07 | 2 / +26 −7 | `mobile/.../card/CardViewModelTest.kt`, new `MainDispatcherRule.kt` |
| #188 | 2026-07-07 | 7 / +234 −198 | `.gitignore`, `CardScreen.kt`, **`mobile/stdout` (deleted)**, `res/skyglow/REAL_GRID_RUNBOOK.md`, `build_bortle_bin.py`, `calibrate_scale.py`, `tests/test_calibrate_scale.py` |
| #190 | 2026-07-07 | 1 / binary | `mobile/src/main/assets/lightpollution/bortle.bin` replaced |
| #191 | 2026-07-07 | 2 / +55 −11 | `mobile/.../visibility/EffectiveBortle.kt` + test (coverage fix) |
| #192 | 2026-07-08 | 3 / +314 −216 | `res/skyglow/REAL_GRID_RUNBOOK.md`, `build_bortle_bin.py`, `tests/test_build_bortle_bin.py` (v3 pipeline restore) |
| #193 | 2026-07-08 | 12 / +946 −30 | CAT-1: `.gitignore`, `NOTICE.md`, `PtskCat0Catalog.kt`+test, `docs/star_catalog_ptskcat0_format.md`, `tools/catalog-packer` (`PackerMain`, `HygRealCatalogParser`, `PtskCat0Writer`) + tests |
| #194 | 2026-07-09 | 2 / +197 | `core/catalog/.../RealStarCatalogProvider.kt` + `AssetRealStarCatalogProviderTest` (PTSKCAT0 runtime loader) |
| #195 | 2026-07-09 | 2 / +176 | `RealStarVisibilityFilter.kt` + test |
| #196 | 2026-07-09 | 3 / +293 | `.gitignore`, `LimitingMagnitudeModel.kt` + test |
| #197 | 2026-07-09 | 2 / +297 | `RealStarVisibilityService.kt` + test |
| #198 | 2026-07-09 | 2 / +49 −13 | `RealStarVisibilityService.kt` + test (follow-up) |
| #199 | 2026-07-09 | 5 / +248 | `RealStarVisibilityDebugProbe.kt`+test, `MainActivity.kt`, `RealStarVisibilityDebugProvider.kt`, `MobileLog.kt` |
| #200 | 2026-07-09 | 6 / +214 −19 | `mobile/lint-baseline.xml`, `CatalogDebugScreen.kt`, debug provider + test, `values/strings.xml`, `values-ru/strings.xml` |
| #201 | 2026-07-09 | 1 / +160 | `docs/real_star_visibility_contract.md` |
| #202 | 2026-07-09 | 4 / +45 −1 | `RealStarCatalogProvider.kt`, `RealStarVisibilityDebugProvider.kt` + tests (VF-1) |
| #203 | 2026-07-09 | 3 / +316 −5 | `VisibleRealStarSnapshot.kt` + test, `docs/real_star_visibility_contract.md` (VF-2a) |
| #204 | 2026-07-09 | 1 / +598 | `docs/camera_coordinate_calibration_contract.md` (CAM-0b) |
| #205 | 2026-07-09 | 1 / +425 | `docs/cam_0a_recon.md` (CAM-0a) |
| #206 | 2026-07-11 | 2 / +299 −18 | `mobile/.../ar/DisplayRemap.kt`, `ProjectionOrientationTest.kt` (CAM-0c) |
| #207 | 2026-07-11 | 3 / +364 −93 | `core/astro/projection/Projection.kt` + test, `ArScreen.kt` (CAM-1a) |
| #208 | 2026-07-11 | 11 / +1029 | `CameraFov.kt`, `CameraIntrinsics.kt`, `LegacyFallbackCameraIntrinsics.kt` + tests; mobile `CameraCharacteristicsSource/IntrinsicsProvider/IntrinsicsResolver` (CAM-1b) |
| #209 | 2026-07-12 | 14 / +1775 −3 | `CameraFrameMetadata.kt`; mobile `CameraFrameAnalyzer`, `CameraFrameMetadataSink/Source`, `CameraSessionLifecycle`, `CameraPreview.kt` (CAM-1c ImageAnalysis metadata) |
| #210 | 2026-07-12 | 16 / +1806 −10 | `FrameRotationPairing`, `RotationSampleHistory`, `TimedRotationSample`, `TimestampSyncConfig/Diagnostics` + tests; `ArScreen`, `CameraPreview` (CAM-1d) |
| #211 | 2026-07-14 | 6 / +1707 −8 | `CropScaleTransform.kt`, `PixelGeometry.kt` + tests, contract doc (CAM-1e FILL_CENTER) |
| #212 | 2026-07-14 | 18 / +3253 −54 | `CameraIntrinsicsResolution`, `CameraSessionGeometry(+Result)`, `CropScaleTransform`; mobile `CameraSessionGeometryProvider`, `CameraSessionIntrinsicsCoordinator`, `CameraTimestampSynchronizer` (CAM-1f) |
| #213 | 2026-07-14 | 12 / +1809 −20 | `CameraGeometryDiagnostics(+Format,+Gate)`, `CameraSessionGeometryProvider` + tests, `docs/validation/cam_1g_device_validation.md` (CAM-1g) |
| #214 | 2026-07-14 | 42 / +5804 −6 | `camera/prediction/*` (`CameraStarPredictor`, `PinholeProjectionModel`, `PredictedStarProjection`, `RotationMath`, transforms…) (CAM-2a) |
| #215 | 2026-07-14 | 21 / +3022 −20 | `CameraSessionGeometry`, `CameraStarPredictor`, `LocalSkyDirection`, `PreparedStarProjectionContext`; `PredictedStarOverlayUi` + test; docs (CAM-2b overlay) |
| #216 | 2026-07-14 | 6 / +214 −173 | docs (`SPRINT_STATUS`, contracts, validation), `PreProdSmokeMobileTest.kt` (CAM-2b closure) |
| #217 | 2026-07-14 | 2 / +24 −25 | `gradle/libs.versions.toml`, `mobile/build.gradle.kts` (AndroidX Test / Pixel 9 compat) |
| #218 | 2026-07-14 | 5 / +1274 −148 | `ArScreen.kt`, `CamDiagnosticHud.kt`, `PredictedStarDebugControlsState.kt`, `PredictedStarOverlayUi.kt` + layout test |
| #219 | 2026-07-14 | 7 / +925 −104 | `ArScreen.kt`, `CamDiagnosticHud.kt`, `PredictedStarOverlayState.kt`, isolation/visibility tests (CAM-2b legacy overlay) |
| #220 | 2026-07-15 | 31 / +5477 −81 | `ActiveArrayIntrinsics`, `AnalysisBufferIntrinsicsMapping`, `CameraFrameMetadata`, `CameraIntrinsics`, `SensorToBufferMatrix3`, `PinholeProjectionModel` + tests (CAM-2 ImageAnalysis intrinsics) |
| #221 | 2026-07-15 | 14 / +1314 −39 | mobile `AnalysisBufferIntrinsicsResolver`, `CameraIntrinsicsProvider/Resolver`, `CameraSessionIntrinsicsCoordinator(+DiagnosticFormat)`, `SessionScopedCameraIntrinsicsResolver` (CAM-2c runtime) |
| #222 | 2026-07-16 | 20 / +3281 −226 | `CamDiagnosticActions/FullReportDialog/Hud`, `CamDiagnosticReportFormat/Snapshot/SnapshotJson`, `CamDiagnosticsExportUiImpl` + tests |
| #223 | 2026-07-16 | 17 / +1521 −60 | `WholeActiveArrayMappingHypothesis.kt`, coordinator/diagnostic format, snapshot JSON + tests (CAM-2c domain consistency) |
| #224 | 2026-07-17 | 33 / +4893 −10 | `Cam2cPhysicalCameraResolution.kt`, internalDebug manifest, experiment launch/lifecycle UI tests, `libs.versions.toml`, `mobile/build.gradle.kts` (CAM-2c Pixel 9 intrinsics experiment) |
| #225 | 2026-07-18 | 34 / +4390 −45 | `AnalysisResolutionCandidates`, `CameraCoordinateBasis`, `CameraX142MatrixModel`, `DualBasisMatrixEvidence`, `ExperimentSessionState`, `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md` |
| #226 | 2026-07-18 | 12 / +299 −40 | `MatrixStabilityCounters`, `PhysicalCameraExperimentExport`, `AnalysisResolutionRequest`, `CameraPreview.kt`, variant-boundary tests |
| #227 | 2026-07-18 | 4 / +779 −18 | `PhysicalCameraBindingExperimentScreen.kt`, compact status + live overlay UI tests, evidence doc |
| #228 | 2026-07-19 | 32 / +6605 −1 | `FrameContentCameraPreview`, `FrameContentCornerDetector`, `FrameContentCorrespondence{Export,Screen,SessionState,Snapshot}`, `CamDiagnosticFullReportDialog` (CAM-2c frame-content experiment) |
| #229 | 2026-07-19 | 13 / +901 −14 | `FrameContentTarget.kt`, `FrameContentTargetSvg(+FileProvider,+Sharing)`, `res/xml/filepaths_cam2c_target.xml`, snapshot/SVG tests |
| #230 | 2026-08-07 | 34 / +8661 | `core/astro-core/build.gradle.kts`, `camera/skylog/*` (`SkySessionLog`, `Codec`, `Decode`, `JsonAccess`, `Replay`) + tests, fixtures (SKY-1) |
| #231 | 2026-08-07 | 25 / +1764 −171 | `skylog/*` refinements + tests, `docs/sky_session_log_format.md`, `SkyCaptureClock.kt`, `SkyLocationPermissionActionUiTest.kt` (SKY-1 gate) |
| #232 | 2026-08-07 | 15 / +3049 | `camera/detect/{DetectionEvaluation,LumaFrame,StarDetector,TiledBackground}.kt`, `PixelGeometry.kt`; tests incl. `SyntheticFrameRenderer(.kt in src/test)`, `PixelConventionBridgeTest`, `SkySessionLogDetectionTest` (SKY-2) |
| #234 | 2026-08-07 | 4 / +581 −40 | `TiledBackground.kt`, `StarDetectorTest`, `TiledBackgroundTest`, `docs/star_detection_contract.md` (**residual-based sigma**) |
| #233 | 2026-08-07 | 13 / +1987 −1 | `settings.gradle.kts`, `tools/sky-session-loader/**` (`SkySessionLoaderCli`, `SkySessionDirectory`, `SkySessionDetectionRun`, tests), `DetectionEvaluation.kt`, `FILE_OVERVIEW.md`, `MODULES.md` (SKY-3 loader) |
| #235 | 2026-08-07 | 13 / +2969 −3 | `camera/match/{AnalysisBufferScale,StarCatalogQuery,StarMatcherInput}.kt`, `PinholeProjectionModel.kt` (unproject), `PtskCat0StarCatalogQuery.kt` + tests, `docs/star_detection_contract.md` (SKY-3 matcher input) |

Note: #186 is absent (see §2.5). PRs #180–#185 do not appear anywhere in main's history (grep of `git log main` for `#18[0-6]` matches nothing) — they predate the history rebuild.

### 2.4 Remote branches (`git branch -r`: 241 refs incl. `origin/main`)

Full per-branch table: `scratchpad/branches.md` (generated; not committed). Summary:

| class | count | detail |
|---|---|---|
| merged into main (ancestor) | 48 | 46 `claude/*` feature branches for PRs #187–#235, `chore/skyglow-v3-pipeline`, `test/fix-card-viewmodel-main-dispatcher`, `origin/main` itself |
| related, unmerged | 2 | `claude/camera-ray-angle-api-adb9hj` — **3 ahead / 0 behind**, last 2026-08-08, = open PR #236 ("Publish angleBetweenRad as the canonical ray→angle function"); `claude/androidx-test-pixel9-compat-9h0s0s` — 1 ahead / 90 behind, last 2026-07-15, a docs-only follow-up ("Document the Pixel 9/Android 16 AndroidX Test runtime fix") to #217 → **abandoned** (49 days, behind) |
| **unrelated history** (no merge-base with main) | 191 | 132 `codex/*`, 58 `claude/*`, 1 `feature/sqm-grid-v3`. Newest last-commit among them is 2026-06-27 (67 days old). Ahead/behind vs main is meaningless (whole separate history, 295–426 commits each). **All 191 are abandoned by construction** — they predate the July history rebuild. |

Flagged as abandoned (>30 days and/or fully behind): the 191 unrelated branches + `claude/androidx-test-pixel9-compat-9h0s0s`. That is 192 of 193 unmerged branches.

### 2.5 Open PRs (GitHub API via MCP `list_pull_requests`, `gh` not installed)

| PR | head | base sha | created | note |
|---|---|---|---|---|
| #236 Publish angleBetweenRad as the canonical ray→angle function | `claude/camera-ray-angle-api-adb9hj` @ `472c4c4` | `b6bdc7c` (current main) | 2026-08-08 | SKY-3 follow-up, 3 commits, mergeable base |
| #129 Refactor Tonight tile service to use coroutines | `codex/remove-runblocking-from-tonighttileservice` | `4e822cd` (pre-rebuild) | 2025-12-02 | unrelated history — stale |
| #128 Fix Tonight tile mirroring JSON escaping | `codex/conduct-deep-code-audit-for-pointtosky` | `0a35729` (pre-rebuild) | 2025-11-26 | unrelated history — stale |

PR #186 ("ar-gps-location-wiring") — its merge commit `d46ae40` exists only on `origin/feature/sqm-grid-v3`; `git merge-base --is-ancestor d46ae40 main` → **NO**.

### 2.6 Junk / tracked noise

```
git ls-files | grep -iE 'stdout|stderr|\.log$'   → (nothing)
```
`mobile/stdout` is **not tracked** (deleted in PR #188; `.gitignore:26` lists `mobile/stdout`).
Still tracked at the root and looking like leftovers:
- `fix-volatile-running.diff` — a raw patch file (`wear/.../DefaultAimController.kt`), present since root commit `504abc5`.
- `stage.md` — "Stage – PointToSky, As of 2025-05-20", a 27-line status note.

## 3. Module map (`settings.gradle.kts` + each `build.gradle.kts`)

| module | type (from plugins) | project deps (`project(":…")` in build file) |
|---|---|---|
| `:mobile` | Android app (compose, license-report) | `:core:astro`, `:core:astro-core`, `:core:catalog`, `:core:common`, `:core:location`, `:core:logging`, `:core:time` |
| `:wear` | Android app (compose, license-report) | `:core:astro`, `:core:astro-core`, `:core:catalog`, `:core:common`, `:core:location`, `:core:logging`, `:core:time`, `:wear:sensors` |
| `:wear:benchmark` | `com.android.test` (macrobenchmark) | — (targets `:wear`) |
| `:wear:sensors` | Android lib | `:core:astro-core`, `:core:logging` |
| `:core:common` | Kotlin Multiplatform + Android lib | — |
| `:core:logging` | Android lib | — |
| `:core:location` | Android lib | — |
| `:core:astro-core` | **pure JVM** (`kotlin("jvm")`, JUnit5) | — |
| `:core:astro` | Android lib | `:core:astro-core`, `:core:logging`, `:core:time`; `testImplementation(:core:catalog)` |
| `:core:time` | Android lib | — |
| `:core:catalog` | Android lib | `:core:astro`, `:core:astro-core`, `:core:logging` |
| `:tools:ephem-cli` | pure JVM application | `:core:astro-core` |
| `:tools:catalog-packer` | pure JVM application | — |
| `:tools:sky-session-loader` | pure JVM application (JUnit5) | `:core:astro-core` |
| `app/` (on disk, **not in `settings.gradle.kts`**) | Android app skeleton (`build.gradle.kts`, `proguard-rules.pro`, `src`) | — |

`:core:astro` ↔ `:core:catalog`: catalog depends on astro (`implementation`), astro depends on catalog only in tests → no cycle.
`MODULES.md` describes `:core:astro-core` as "Android library" — it is `kotlin("jvm")` (discrepancy, see §8).

### 3.1 Required symbols

| item | status | path |
|---|---|---|
| `astro-core` | present | `core/astro-core/` |
| `core:catalog` | present | `core/catalog/` |
| `core:location` | present | `core/location/` |
| `tools/catalog-packer` | present | `tools/catalog-packer/` |
| `tools/sky-session-loader` | present (PR #233) | `tools/sky-session-loader/` |
| `StarDetector` | present (top-level `fun detectStars` + `object StarDetectorDefaults`) | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/camera/detect/StarDetector.kt` |
| `TiledBackground` | present | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/camera/detect/TiledBackground.kt` |
| `SyntheticFrameRenderer` | present — **in test sources only** | `core/astro-core/src/test/kotlin/dev/pointtosky/core/astro/projection/camera/detect/SyntheticFrameRenderer.kt` |
| `evaluateDetections` | present | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/camera/detect/DetectionEvaluation.kt` |
| `StarCatalogQuery` | present | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/camera/match/StarCatalogQuery.kt` (impl: `core/catalog/src/main/java/dev/pointtosky/core/catalog/binary/PtskCat0StarCatalogQuery.kt`) |
| `AnalysisBufferScale` | present | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/camera/match/AnalysisBufferScale.kt` |
| `unprojectToCameraRay` | present | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/camera/prediction/PinholeProjectionModel.kt` |
| `StarMatcherInput` | present | `core/astro-core/src/main/kotlin/dev/pointtosky/core/astro/projection/camera/match/StarMatcherInput.kt` |
| `PinholeProjectionModel` | present | same file as above |
| `PredictedStarOverlayReducer` | present | `mobile/src/main/java/dev/pointtosky/mobile/ar/camera/prediction/PredictedStarOverlayReducer.kt` |
| `ArViewModelFactory` | present | `mobile/src/main/java/dev/pointtosky/mobile/ar/ArViewModel.kt` |
| `deviceLocationFlow` | present | `mobile/src/main/java/dev/pointtosky/mobile/location/DeviceLocationRepository.kt` (used in `mobile/.../card/CardViewModel.kt`) |
| `MainDispatcherRule` | present | `mobile/src/test/java/dev/pointtosky/mobile/card/MainDispatcherRule.kt` |

**Residual-based sigma in `TiledBackground`: ON MAIN** (PR #234, merge `ba62c3e`). `TiledBackground.kt:250-270` — "Pass 2: the spread, on the background-subtracted residual rather than on the raw values"; residuals sorted, `sigma = (median − q25)/0.6745`.

## 4. Assets and data

Headers read with a Python `struct` parse of the committed bytes (no `xxd` in the container).

| asset | path | size (B) | header |
|---|---|---|---|
| Light-pollution grid | `mobile/src/main/assets/lightpollution/bortle.bin` | 54 064 | magic `PTSKLP01`, **version 3**, rows 800, cols 800, latTop 60.0, lonLeft −120.0, degPerCell 0.0125, flags 0 (not placeholder), compLen 54 012 (52 + 54 012 = file size ✔). **No "scale" field in the v3 header** (`LightPollutionGrid.kt:12-24`); cells are SQM bytes `SQM_MIN + (b−1)·SQM_STEP`. Coverage = 10°×10° (lat 50–60 N, lon 120–110 W), i.e. **regional, not global**. |
| Phone star catalog | `mobile/src/main/assets/catalog/stars_real.bin` | 705 889 (≈689 KB) | `PTSKCAT0`, version 1, count **41 487**, magLimitCenti **800** (≤ 8.00), recSize **16**, epoch 2000; records end at 663 820; trailing 42 069 B = names table |
| Watch star catalog | `wear/src/main/assets/catalog/stars_real.bin` | 183 698 | `PTSKCAT0`, version 1, count **8 920**, magLimitCenti **650** (≤ 6.50), recSize 16, epoch 2000; trailing 40 950 B = names table |
| Legacy BSC catalog (phone) | `mobile/src/main/assets/catalog/star.bin` | 297 540 | `PTSKCAT4` v5 |
| Legacy BSC catalog (watch) | `wear/src/main/assets/catalog/star.bin` | 33 152 | `PTSKCAT4` v4 |
| Constellations | `{mobile,wear}/src/main/assets/catalog/const_v1.bin` | 17 898 | `PTSKCONS` v1 |

**Names table:** present in both PTSKCAT0 files — phone 3 159 entries, watch 3 074 entries (first: Sirius, Canopus, Arcturus, Rigil Kentaurus, Vega).
**Asterism table:** **not part of PTSKCAT0** — `docs/star_catalog_ptskcat0_format.md:60-63`: "Curated asterisms/constellation-art data live entirely in the separate `PTSKCAT4` pipeline." Constellation lines ship in `const_v1.bin`/`star.bin` (PTSKCAT4), not in the HYG catalog.

### 4.1 `res/skyglow/` (all tracked — `git ls-files res/skyglow`)

`REAL_GRID_RUNBOOK.md`, `__init__.py`, `brightness.py`, `build_bortle_bin.py`, `build_real_grid.py`, `calibrate_scale.py`, `convolve.py`, `kernel.py`, `tests/{test_brightness,test_build_bortle_bin,test_build_real_grid,test_calibrate_scale,test_convolve,test_kernel}.py`.

- `build_bortle_bin.py` — tracked ✔. `calibrate_scale.py` — tracked ✔.
- `diag_*.py` — **NOT tracked, not on disk**. Root commit `56009a0` message: "docs(skyglow): note test_resolution.py / diag_*.py as not reconstructable".
- `REAL_GRID_RUNBOOK.md` — present, title "Real Bortle Grid — Data Runbook (v3 / SQM)"; references `build_bortle_bin.py` (v3), the PTSKLP01 v3 header (`v3 800x800 latTop=60 lonLeft=-120 deg=0.01250`), and marks `build_real_grid.py` + `calibrate_scale.py` as the **legacy v2 path** "incompatible with the current v3 decoder".

### 4.2 `.gitignore`

Covers `out/` (l.34), `viirs_global/` (l.35), `*.h5` (l.36), `*.canvas.npy` (l.37), `*.log` (l.25), `mobile/stdout` (l.26), `tools/catalog-packer/data/`, `hyg_v42.csv`, plus standard Gradle/IDE entries.

## 5. Licensing and attribution

- **`LICENSE`: NO LICENSE FILE.** `ls LICENSE* COPYING*` → none; `git ls-files | grep -iE 'LICENSE|COPYING'` → none. The only license text in the repo is the d3-celestial BSD-3 block inside `NOTICE.md`.
- **`NOTICE.md`: present** (root, 39 lines, added/extended in PR #193). Relevant lines, quoted:
  - "HYG Database v4.2 — compiled by David Nash (`astronexus/HYG-Database`) … Licensed under Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0), https://creativecommons.org/licenses/by-sa/4.0/."
  - "Per CC BY-SA 4.0, any redistribution of the packed HYG-derived catalog (PTSKCAT0 assets) must carry this same attribution and license."
  - "Light-pollution grid — derived from NASA's Black Marble product suite (VIIRS/NPP), NASA EOSDIS. Reference: Román et al. 2018 … Skyglow model: Duriscoe et al. 2018 … **(The real asset is not yet shipped; attribution is recorded here in advance.)**" ← stale: the v3 asset *is* shipped (PR #190).
  - Also credits Yale BSC5 (public domain, via `brettonw/YaleBrightStarCatalog`) and d3-celestial (BSD-3-Clause, full text included).
- **Vendored third-party code:** none found. No `third_party/`, `vendor/`, or per-directory LICENSE files; no `Copyright (c)` / `SPDX-License-Identifier` / `Apache License` headers in any `.kt/.java/.py` source (grep over the tree, build dirs excluded). Third-party *data* only (above). Both apps apply `license-report` Gradle plugin (`licensePublicReleaseReport` in `android-release.yml`).
- **README.md:** present, **Russian**, 99 lines, titled "PointToSky — Tonight Tile (tests)". Content: how to add the Wear tile via adb, how to run unit/instrumented tests, H9 test commands, AVD-pair e2e steps, WFS integration link. **No architecture, module, or build overview** — effectively a test-runbook stub.
- **All tracked `*.md` (31)** — `git ls-files '*.md'`:

| file | lines | first heading / purpose |
|---|---|---|
| `CONTENT_GUIDE.md` | 53 | Content Guide – PointToSky |
| `FILE_OVERVIEW.md` | 39 | File Overview (top-level tree) |
| `MODULES.md` | 51 | Modules (per-module responsibilities; updated in #233) |
| `NOTICE.md` | 39 | NOTICE (data attributions) |
| `PROJECT_OVERVIEW.md` | 33 | PointToSky – Project Overview |
| `README.md` | 99 | Tonight Tile test runbook (RU) |
| `core/astro/README.md` | 18 | core:astro |
| `core/catalog/README.md` | 43 | core-catalog |
| `docs/SPRINT_STATUS.md` | 1636 | Sprint Status (running CAM/SKY sprint log) |
| `docs/cam_0a_recon.md` | 425 | CAM-0a camera/AR pipeline recon |
| `docs/camera_coordinate_calibration_contract.md` | 3648 | CAM-0b coordinate & calibration contract |
| `docs/camera_star_prediction_contract.md` | 1299 | CAM-2a star prediction contract |
| `docs/constellation_const_v1_format.md` | 53 | `const_v1.bin` format |
| `docs/data-safety.md` | 12 | Play data-safety notes (S9.C) |
| `docs/preprod-check.md` | 43 | Pre-production checklist |
| `docs/real_star_visibility_contract.md` | 185 | VF-1 visibility service contract |
| `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md` | 445 | CAM-2c CameraX 1.4.2 matrix-domain recon |
| `docs/sky_session_log_format.md` | 367 | SKY-1 session log format |
| `docs/star_catalog_ptskcat0_format.md` | 63 | PTSKCAT0 format |
| `docs/star_detection_contract.md` | 242 | SKY-2 detection contract |
| `docs/star_matcher_input_contract.md` | 320 | SKY-3 matcher input contract |
| `docs/validation/cam_1g_device_validation.md` | 109 | CAM-1g device validation |
| `docs/validation/cam_2b_device_validation.md` | 153 | CAM-2b device validation |
| `docs/validation/cam_2c_pixel9_evidence.md` | 1568 | CAM-2c Pixel 9 evidence log |
| `docs/wfs_integration.md` | 35 | Watch Face Studio integration (RU) |
| `res/skyglow/REAL_GRID_RUNBOOK.md` | 183 | v3 SQM grid runbook |
| `stage.md` | 27 | "Stage" status note dated 2025-05-20 (stale) |
| `store/README.md` | 71 | Store listing assets |
| `store/icon/README.md` | 18 | Icon concept |
| `store/rating/iarc.md` | 20 | IARC questionnaire hints (RU) |
| `store/screenshots/README.md` | 22 | Screenshot rules (RU) |

## 6. Build and test health

Environment caveat: no Android SDK or JDK 17 was preinstalled. `./gradlew build -x lint --offline` is impossible (no Gradle cache); the
online `./gradlew build -x lint` failed at task-graph time ("Cannot find a Java installation … languageVersion=17"). After
`apt-get install openjdk-17-jdk-headless` and pointing `ANDROID_HOME` at an empty scratch dir with pre-accepted licences, AGP's
`android.builder.sdkDownload=true` fetched platforms/build-tools itself (314 MB) and the module-level tasks below ran. Nothing
in the repo was modified. `--offline` was never possible, so all numbers are from online runs.

### 6.1 Results

| task | result | tests | failed | detail |
|---|---|---|---|---|
| `./gradlew build -x lint` (whole project) | **FAILED (env)** | — | — | toolchain error before any task ran; not a code failure. Not re-attempted after JDK 17 because it would also assemble/lint every variant; per-module tasks used instead. |
| `:core:astro-core:test` (pure JVM, JUnit5) | PASS | 677 (50 classes) | 0 | includes all `camera/detect/*` and `camera/match/*` suites — see 6.2 |
| `:tools:sky-session-loader:test` | PASS | 14 (2 classes) | 0 | `SkySessionDirectoryTest` 5, `SkySessionLoaderTest` 9 |
| `:tools:catalog-packer:test` | PASS | 34 (4 classes) | 0 | |
| `:tools:ephem-cli:test` | NO-SOURCE | 0 | 0 | module has no tests |
| `:mobile:testInternalDebugUnitTest` | PASS | 759 (73 classes) | 0 | `CardViewModelTest` 9/9 ✔, `ProjectionOrientationTest` 7/7 ✔, `PredictedStarOverlayReducerTest` 32/32 ✔ |
| `:wear:testInternalDebugUnitTest` | PASS | 9 (5 classes) | 0 | |
| `:core:catalog:testDebugUnitTest` | PASS | 99 (13 classes) | 0 | |
| `:core:astro:testDebugUnitTest` | PASS | 99 (16 classes) | 0 | |
| `:core:common:testDebugUnitTest` | NO-SOURCE | 0 | 0 | |
| `:core:time:testDebugUnitTest` | **FAIL** | 4 | 1 | `ZoneRepoTest > zone flow emits current and updated zone`: `java.lang.RuntimeException: Method addAction in android.content.IntentFilter not mocked` (at `ZoneRepo.kt:34`) — needs Robolectric or `unitTests.returnDefaultValues`. |
| `:core:location:testDebugUnitTest` | **FAIL (compile)** | — | — | `core/location/src/test/java/dev/pointtosky/core/location/android/AndroidFusedLocationRepositoryTest.kt:53,85` `Unresolved reference 'FakeFusedClientDelegate'`; `:130` syntax errors "Expecting member declaration" (test file is broken/half-edited). |
| `:core:logging:testDebugUnitTest` | **FAIL (compile)** | — | — | `core/logging/src/test/java/dev/pointtosky/core/logging/LogEventJsonTest.kt:56,61` `Unresolved reference 'boolean'`. |
| `:wear:sensors:testDebugUnitTest` | **HANGS** | — | — | `DelegatingOrientationRepositoryTest."uses rotation vector when available"` (`DelegatingOrientationRepositoryTest.kt:19`) parks forever inside `runBlocking` (jstack after 54 min: `BlockingCoroutine.joinBlocking`). The `withTimeout(1_000)` guards `frames.first()`, but the `runBlocking` scope passed as `scope = this` keeps child coroutines alive so the test never returns. Run was killed manually. |
| `:wear:benchmark` | not run | | | instrumentation-only module |

**Important context from GitHub Actions on the same head `b6bdc7c`:**
- `Android CI` (`android.yml`: `:wear:lint :mobile:lint`, `catalog-packer` sample pack + `:core:catalog:test`) — **success** (run 31241176511).
- `Android Release Artifacts` (`android-release.yml`) — **failure on every push to main since at least PR #221** (runs 155–169). Two independent causes:
  `wear tests` job: `:wear:compilePublicDebugAndroidTestKotlin` fails — `wear/src/androidTest/.../AimStatusComplicationDataTest.kt:5,89,93 Unresolved reference 'ServiceScenario' / 'onComplicationRequest'`;
  `mobile bundle` / `wear bundle` jobs: `:mobile:packagePublicRelease` — `SigningConfig "release" is missing required property "storeFile"` (no signing secret in CI).
- `Android Full` (`android-full.yml`, nightly, runs `testDebugUnitTest` on every module) — **has not run since 2026-02-13** and all 247 recorded runs failed on pre-rebuild sha `7e4f057`. Because the nightly is dead, the `core:time` / `core:location` / `core:logging` / `wear:sensors` test breakages above are invisible to CI. The PR-smoke workflow only compiles `:wear:compileDebugKotlin`.

### 6.2 Star-detector (SKY-2/3) and session-loader suites, run separately

| suite (`:core:astro-core:test`) | tests | failed |
|---|---|---|
| `camera.detect.DetectionEvaluationTest` | 12 | 0 |
| `camera.detect.LumaFrameTest` | 7 | 0 |
| `camera.detect.PixelConventionBridgeTest` | 3 | 0 |
| `camera.detect.SkySessionLogDetectionTest` | 2 | 0 |
| `camera.detect.StarDetectorTest` | 16 | 0 |
| `camera.detect.SyntheticFrameRendererTest` | 6 | 0 |
| `camera.detect.TiledBackgroundTest` | 17 | 0 |
| `camera.match.AnalysisBufferScaleTest` | 16 | 0 |
| `camera.match.MatcherInputBrightnessContractTest` | 3 | 0 |
| `camera.match.StarCatalogQueryTest` | 14 | 0 |
| `camera.match.StarMatcherInputTest` | 10 | 0 |
| **detect + match total** | **106** | **0** |
| `:tools:sky-session-loader:test` (`SkySessionDirectoryTest` 5, `SkySessionLoaderTest` 9) | 14 | 0 |

### 6.3 ktlint

`./gradlew ktlintCheck --continue` (ktlint plugin, `ktlint_official` style, `max_line_length = 120` from `.editorconfig`), 350 ktlint tasks.
Exit code 1, but **only one task actually failed**: `:core:location:runKtlintCheckOverTestSourceSet` — "KtLint failed to parse file
core/location/src/test/java/dev/pointtosky/core/location/android/AndroidFusedLocationRepositoryTest.kt" (same broken file that breaks the
test compile). Every other module *reports* violations but does not fail, because root `build.gradle.kts:41` sets
`ignoreFailures.set(!strictMode)` inside `configureStaticAnalysis()` (`build.gradle.kts:26-41`), where `strictMode` is `val strict = System.getenv("CI") == "true" || project.hasProperty("strict")` (`build.gradle.kts:25`). This run had no `CI` env var, so ktlint was advisory. **On GitHub Actions (`CI=true`) the same 5 238 violations would be fatal** — they are only invisible there because no workflow runs `ktlintCheck`/`check` (`android.yml` runs `:wear:lint :mobile:lint` and `:core:catalog:test`; `android-full.yml` runs `testDebugUnitTest`).

| scope | violations |
|---|---|
| **total** | **5 238** in 259 files |
| `mobile` | 3 324 |
| `core/catalog` | 646 |
| `core/logging` | 618 |
| `core/astro` | 263 |
| `wear/sensors` | 125 |
| `core/location` | 109 |
| `wear` | 99 |
| `core/time` | 28 |
| `core/common` | 26 |
| `core/astro-core`, `tools/*` | 0 (clean — see §6.1 JVM ktlint run, BUILD SUCCESSFUL) |

Top rules: "Argument should be on a separate line" 1 431, "Missing newline before ')'" 715, "Exceeded max line length" 483,
"A multiline expression should start on a new line" 434, "Expected newline before '.'" 391. Heaviest files:
`core/logging/.../RedactorTest.kt` 388, `mobile/.../AnalysisBufferIntrinsicsResolverTest.kt` 268,
`core/catalog/.../BinaryConstellationBoundariesLoadTest.kt` 253, `mobile/.../PredictedStarOverlayReducerTest.kt` 186.

**`CardScreen.kt`: still has exactly one ktlint finding** — `mobile/src/main/java/dev/pointtosky/mobile/card/CardScreen.kt:196:28 A multiline expression should start on a new line`
(the PR #188 touch did not clear it). It is non-fatal under the current `ignoreFailures` setting.

`MainDispatcherRule` exists (`mobile/src/test/java/dev/pointtosky/mobile/card/MainDispatcherRule.kt`) and `CardViewModelTest` passes 9/9 (§6.1).
`ProjectionOrientationTest` passes 7/7 (§6.1).



## 7. Known blockers — current status

**Pixel 9 / `AnalysisBufferScale.forGeometry` (CAM-2c `UnsupportedLogicalMultiCameraMapping`)** — **unresolved, and deliberately so.**
- `core/astro-core/.../match/AnalysisBufferScale.kt:225-247` (companion `forGeometry`): KDoc — "the underlying `PinholeProjectionModel.forGeometry` throws for a physical-sensor-referenced or dimensionless intrinsics value … This function does not soften that into a fallback — a fabricated scale is worse than no scale". It simply delegates to `PinholeProjectionModel.forGeometry(geometry)`.
- There is **no** `withIntrinsics(LegacyFallback(...))` fallback inside `forGeometry` or its callers in `core`. The only production `withIntrinsics(LegacyFallback…)` is `mobile/src/main/java/dev/pointtosky/mobile/ar/camera/prediction/PredictedStarOverlayReducer.kt:106-117`, gated on `PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK` ("CAM-2b diagnostic analysis-buffer fallback") — a debug overlay mode, not a matcher path.
- `mobile/.../camera/AnalysisBufferIntrinsicsResolver.kt:325` still returns `UnsupportedLogicalMultiCameraMapping` for logical multi-cameras; `CameraSessionIntrinsicsCoordinator.kt:93` documents that calibrated publication is withheld in that case.
- `docs/SPRINT_STATUS.md` lines 1260, 1354, 1445, 1551: "CALIBRATED ANALYSISBUFFER PUBLICATION STILL BLOCKED"; line 1633: "CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION". `docs/validation/cam_2c_pixel9_evidence.md:1549`: "No physical device or emulator was attached in this pass".

**`FrameContentTargetSpec` 4×5 dot-grid target — implemented** (internalDebug only): `mobile/src/internalDebug/java/dev/pointtosky/mobile/ar/camera/FrameContentTarget.kt:77-` `internal data class FrameContentTargetSpec(cornerRows = 4, cornerCols = 5, dotSpacingMm = 25.0, markerAreaScaleFactor = 2.5, regularDotDiameterMm = 8.0, markerOffsetXMm = −15.0, markerOffsetYMm = −15.0, minimumBlobClearanceMm …)`, with SVG export/sharing (`FrameContentTargetSvg*.kt`, PR #229).

**`TODO`/`FIXME` mentioning Pixel 9 / LegacyFallback / PhysicalSensor / matcher:** **none** (grep over `*.kt, *.kts, *.py`). The repo has 7 TODOs total, all unrelated (`GeomagneticFieldDeclinationProvider.kt:10`, `AccelMagOrientationRepository.kt:24`, `tools/ephem-cli/.../JulianDate.kt:27`, `ArScreen.kt:1147`, `core/common/.../Constants.kt:5`, `SimpleEphemerisComputer.kt:293,372`).

## 8. Discrepancies

- ASSUMED: PRs #186, #187, #188, #189, CAT-1, CAM-1, CAM-2 are merged into main.
  ACTUAL: #187–#189, CAT-1 (#193–#194), CAM-1 (#207–#213), CAM-2 (#214–#229) are in main. **#186 is NOT** — its merge commit `d46ae40` is only reachable from `origin/feature/sqm-grid-v3` (unrelated history). #187 survives only as a parentless root commit of the rebuilt main.
- ASSUMED: phone catalog ≤8.0 (~42k stars, ~670 KB), watch ≤6.5, 16-byte records.
  ACTUAL: phone mag ≤ 8.00, **41 487** stars, **705 889 B (≈689 KB; 663 792 B of records + 42 069 B names)**; watch ≤ 6.50, 8 920 stars, 183 698 B; recSize 16 ✔. Size assumption is ~5 % low because it omits the names table.
- ASSUMED: three SKY PRs (matcher input contract, sigma fix, session-log loader) are in progress on branches, not merged.
  ACTUAL: **all three are merged** on 2026-08-07 — #235 (`matcher-input-contract-f24g5y`), #234 (`tiled-background-gradient-sigma-nst0lz`), #233 (`sky-session-loader-mnyte3`). The only open SKY work is PR #236 (`angleBetweenRad` API), 3 commits ahead.
- ASSUMED: Pixel 9 AnalysisBufferScale blocker is unresolved.
  ACTUAL: **confirmed unresolved** (§7). No fallback in `forGeometry`; no device evidence.
- ASSUMED: `mobile/stdout` is still tracked; `CardScreen.kt` ktlint issue still open.
  ACTUAL: `mobile/stdout` is **not tracked** (removed in PR #188, ignored via `.gitignore:26`). `CardScreen.kt` ktlint: **still open** — `CardScreen.kt:196:28 A multiline expression should start on a new line` (1 finding; non-fatal because ktlint `ignoreFailures` is on outside strict mode).
- Additional (not in the assumption list):
  - `MODULES.md` says `:core:astro-core` is an "Android library"; it is `kotlin("jvm")` (`core/astro-core/build.gradle.kts`).
  - `NOTICE.md` says the light-pollution asset "is not yet shipped"; `bortle.bin` v3 (flags=0, real data) has shipped since PR #190.
  - `app/` module directory exists on disk but is not in `settings.gradle.kts`.
  - `bortle.bin` covers only lat 50–60 N × lon 120–110 W (one 10° tile), which the runbook confirms (`h06v03`); any "global grid" wording elsewhere is wrong.

## 9. Untracked / noise

`git status --porcelain --ignored` on a fresh checkout → empty, so nothing untracked or ignored exists in the working tree
besides this report (`docs/recon/RECON_main_2026-09-02.md`, left uncommitted) and Gradle `build/` / `.gradle/` / `.kotlin/` outputs
produced by §6 (already ignored). `git status --porcelain` after the build runs still shows only `?? docs/recon/RECON_main_2026-09-02.md`.

Tracked files that arguably should not be:
- `fix-volatile-running.diff` (root) — stray patch file.
- `stage.md` (root) — status note from 2025-05-20.
- `mobile/lint-baseline.xml` — a lint baseline is tracked (legitimate, but note it suppresses lint findings).
