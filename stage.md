# Stage – PointToSky

As of 2026-09-04:

## 1. Current focus
- Pixel 9 / logical-multi-camera calibration (CAM-2c): `AnalysisBufferScale.forGeometry` still throws by design for a
  physical-sensor-referenced or dimensionless intrinsics value; no fallback exists in `core` or the matcher path.
  Blocked on physical-device validation (no device evidence collected yet).
- Land PR #236 (`angleBetweenRad` API) — the only open SKY-series work; SKY-1/2/3 (session loader, sigma fix, matcher
  input contract) are already merged.
- Fix CI: `core:time` has a failing test (`ZoneRepoTest`, unmocked `IntentFilter`); `core:location` and `core:logging`
  unit tests do not compile; `wear:sensors`' `DelegatingOrientationRepositoryTest` hangs; wear `androidTest` does not
  compile (`ServiceScenario`); the release bundle is unsigned so `Android Release Artifacts` is red on every `main`
  push; the nightly `Android Full` workflow has been dead since February 2026.
- Continue removing blocking calls on UI/binder threads (Wear tiles/complications, catalog fetchers) in favor of
  suspending flows; keep hardening orientation/location pipelines for steadier aim/identify UX.

## 2. Recently completed
- Repo hygiene: added the missing Apache License 2.0 (`LICENSE`), corrected `NOTICE.md` (light-pollution asset has
  shipped — `mobile/src/main/assets/lightpollution/bortle.bin`, PTSKLP01 v3 — and code/data licensing are now stated
  separately), removed the stray `stage.md`-adjacent noise, and removed the unreferenced legacy `app/` module (never
  wired into `settings.gradle.kts`, no code elsewhere depended on its `com.pointtosky` package).
- Fixed `MODULES.md`'s `:core:astro-core` classification (`kotlin("jvm")`, not an Android library).
- SKY-1/2/3 merged: session-log loader (#233), residual-sigma fix (#234), matcher input contract (#235).
- CAM-0 through CAM-2c landed (#204–#229), including the `FrameContentTargetSpec` 4×5 dot-grid calibration target
  (internalDebug only, with SVG export/sharing).

## 3. Near-term backlog
- `fix-volatile-running.diff` (root) is still tracked and stale: its `DefaultAimController.kt` diff no longer applies
  — the file has since been rewritten (no `running`/`orientationJob`/`locationJob` fields). Needs a decision: drop the
  patch file, or re-derive and land whatever fix it was meant to provide.
- Performance & battery passes for Wear tiles/complications and AR overlays; trim unnecessary wakeups.
- Replace lingering `runBlocking`/blocking I/O with suspending calls and tighter dispatcher usage.
- Expand ViewModel/unit coverage for catalog queries, aim/identify flows, and data-layer bridges.
- Tighten R8/ProGuard rules and security hardening (services/exported components, FileProvider grants).
- Improve accessibility and UI polish (text scaling, contrast) on mobile and Wear.
- ktlint: ~5.2k advisory violations outstanding (`ignoreFailures` on outside strict mode); at least
  `CardScreen.kt:196` still flagged.

## 4. How to use this file
Contributors should update this file when the main focus or priorities shift; keep it short and current. AI agents should read this before large changes to align with active goals and avoid disrupting near-term milestones.
