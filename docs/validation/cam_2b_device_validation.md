# CAM-2b Device Validation

**Status: `CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`**

**Tests authored: Yes. Tests executed: Yes, via real Gradle. Build verified: Yes, via real Gradle
(debug); release blocked only on a missing signing keystore, not a code defect. Physical-device
checklist: not executed — no device or emulator available in this environment.**

A validation-closure session ran the real Gradle gates end to end (an Android SDK and a JDK-17 toolchain
were provisioned inside that session's own environment first; its pinned Gradle 8.11.1 was unreachable
there — see `docs/camera_star_prediction_contract.md` §14.10 — so its preinstalled Gradle 8.14.3 was used
instead, a recorded deviation, not a silent one):

- `:core:astro-core:test` — PASS, 388/388.
- `:mobile:compileInternalDebugKotlin` / `compilePublicDebugKotlin` — PASS.
- `:mobile:testInternalDebugUnitTest` / `testPublicDebugUnitTest` — PASS, 271/271 each.
- `:mobile:compileInternalDebugAndroidTestKotlin` / `compilePublicDebugAndroidTestKotlin` — initially
  FAILED on a real, pre-existing, CAM-2b-unrelated bug in `PreProdSmokeMobileTest.kt` (a stale
  `assertExists` import plus a missing `MobileSettings.from` import); fixed (2-line import change); PASS
  on rerun. `PredictedStarOverlayUiTest.kt` itself compiled cleanly on the first attempt.
- `:mobile:lintInternalDebug` / `lintPublicDebug` — PASS, 0 errors.
- `:mobile:assembleInternalDebug` / `assemblePublicDebug` — PASS, both debug APKs built.
- `:mobile:assembleInternalRelease` / `assemblePublicRelease` — release Kotlin/Java compilation PASS;
  APK packaging blocked by `SigningConfig "release" is missing required property "storeFile"` (no
  release keystore configured in that environment).
- `:mobile:connectedInternalDebugAndroidTest` (`PredictedStarOverlayUiTest`) — **NOT RUN**: `adb devices
  -l` listed no device, and the environment has neither `/dev/kvm` nor CPU hardware-virtualization flags,
  so an emulator could not be started either.

Full itemized disclosure: `docs/camera_star_prediction_contract.md` §14.10. **Every checklist item below
is still transcribed from the CAM-2b task description, unexecuted** — none of the fields in this file are
fabricated or guessed; they stay blank until someone actually runs the checklist on a physical device or
emulator.

Do not treat this file as a completed report. Do not mark the CAM-2b gate as passed based on the Gradle
results above, however real, or on static review alone — none of that is a substitute for the
physical-device checklist below. This checklist assumes CAM-1g's own device gate
(`docs/validation/cam_1g_device_validation.md`) is exercised first or alongside — CAM-2b's predicted
overlay is only as trustworthy as the CAM-1c–1g geometry pipeline feeding it.

## AndroidX Test / Espresso runtime fix (Pixel 9, Android 16)

A later pass attempted the physical-device run this file had been waiting on, on a real device this
time:

- **Device**: Pixel 9, Android 16 (platform API 36).
- **Command**: `./gradlew :mobile:connectedInternalDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=dev.pointtosky.mobile.ar.PredictedStarOverlayUiTest`.
- **Observed failure**: all five `PredictedStarOverlayUiTest` cases — including the pure-fixture case
  with no Compose assertions at all — failed identically before any assertion executed:
  `java.lang.NoSuchMethodException: android.hardware.input.InputManager.getInstance []`, entered through
  `androidx.test.espresso.Espresso.onIdle`. Failing even the fixture-only case proved this was an
  AndroidX Test/Espresso runtime incompatibility with Android 16, not a CAM-2b marker/reducer/assertion
  bug.
- **Root cause, found via `:mobile:dependencyInsight --configuration internalDebugAndroidTestRuntimeClasspath`**:
  `androidx.test.espresso:espresso-core` resolved to the stale **3.5.0** — pulled in transitively by
  Compose's `androidx.compose.ui:ui-test-android:1.7.2` and never overridden by any explicit dependency
  anywhere in the build. Espresso 3.5.0 predates the platform's removal of the static
  `InputManager.getInstance()` entry point that its motion-event injection path reflects into; later
  Espresso releases updated that path for current platform APIs. Meanwhile `androidx.test:core`/`core-ktx`
  (1.5.0 vs 1.6.1), `runner` (1.5.2 vs 1.6.1 vs 1.6.2), `rules` (1.5.0 vs 1.6.1), and `ext:junit`/
  `junit-ktx` (1.1.5 vs 1.2.1) were each declared two-to-three times in `mobile/build.gradle.kts` with
  conflicting hard-coded versions — no single coherent AndroidX Test generation existed on the classpath.
- **Fix**: centralized the AndroidX Test stack in `gradle/libs.versions.toml` as one coherent generation
  (`androidx-test-core`/`core-ktx`/`runner`/`rules` at **1.7.0**, `androidx-test-ext-junit`/`junit-ktx` at
  **1.3.0**, and a new `androidx-espresso-core` alias pinned to **3.7.0**) and referenced those aliases
  from `mobile/build.gradle.kts` in place of the scattered literals — including adding an explicit
  `espresso-core` dependency where none had existed before, so Gradle's conflict resolution picks 3.7.0
  over the transitive 3.5.0. No CAM-2a/CAM-2b production code, composables, reducers, or the test file
  itself (`PredictedStarOverlayUiTest.kt`) were touched; the accepted imports
  (`androidx.compose.ui.test.assert`, `org.junit.Assert.assertEquals`) and all five test cases (waiting,
  unavailable, ready marker canvas + counts, Ready→Waiting(DISPOSED) stale-marker removal, plain
  ready-state point count) are unchanged.
- **Resolved runtime after the fix** (`:mobile:dependencyInsight` re-run against
  `internalDebugAndroidTestRuntimeClasspath`): `espresso-core` 3.5.0 → **3.7.0** (by conflict resolution
  between 3.7.0 and 3.5.0); `core`/`core-ktx` → **1.7.0**; `runner` → **1.7.0**; `rules` → **1.7.0**;
  `ext:junit`/`junit-ktx` → **1.3.0**; transitive `espresso-idling-resource` and `androidx.test:monitor`
  auto-aligned to 3.7.0/1.8.0. No stale AndroidX Test version remained selected anywhere in the resolved
  tree.
- **Gradle gates re-run after the fix** (real Gradle, this pass's own environment — an Android SDK and a
  JDK-17 toolchain were provisioned first, same recorded deviation as the validation-closure pass above;
  preinstalled Gradle 8.14.3 used in place of the unreachable pinned 8.11.1):
  `:mobile:compileInternalDebugAndroidTestKotlin` / `compilePublicDebugAndroidTestKotlin` — **PASS**
  (`PredictedStarOverlayUiTest.kt` compiled unchanged); `:mobile:testInternalDebugUnitTest` — **PASS,
  271/271**; `:mobile:lintInternalDebug` — **PASS, 0 errors**; `:mobile:assembleInternalDebug` — **PASS**.
- **`:mobile:connectedInternalDebugAndroidTest` itself**: **not executed by the agent** — this pass ran in
  a cloud sandbox with no `adb`/USB device access and no physical device attached (confirmed: no `adb`
  binary, no `lsusb`, `ANDROID_HOME` unset before this pass provisioned its own throwaway SDK). The
  Pixel 9 run described above was reported by the user as the reproduction case, not reproduced by the
  agent inside this session. **The exact rerun command must still be executed on the actual Pixel 9** (the
  same command in the reproduction section) to confirm `5 tests started / 5 tests passed / 0 failed`; the
  dependency-graph fix above is verified at the resolution/compile/lint/assemble level, not by an on-device
  instrumentation run performed by the agent.

**Verdict for this pass: the AndroidX Test/Espresso version-compatibility defect that caused the reported
`InputManager.getInstance` failure is fixed and verified (dependency resolution + compilation + unit tests
+ lint + assemble, all real Gradle). Physical re-confirmation on the Pixel 9 is still needed before
claiming `CAM-2b INSTRUMENTATION PASS` — see the reproduction command above.** This is independent of, and
does not close, the physical-device checklist below (orientation/true-north/crop/intrinsics/lifecycle),
which remains a separate, still-unexecuted gate.

## Build

- Commit: see the commit that introduces this validation-closure pass (`docs/SPRINT_STATUS.md` records
  the exact Gradle commands and results)
- Variant: `internalDebug` (`:mobile:assembleInternalDebug`) — built successfully via real Gradle in the
  validation-closure session
- Device model: _(not exercised — no physical device or emulator available)_
- Android version: _(not exercised)_
- Camera lens: _(not exercised)_

## Orientation

- Portrait: _(not exercised)_
- Landscape left: _(not exercised)_
- Landscape right: _(not exercised)_
- 180° (where supported): _(not exercised)_
- Rotate device while preview remains active: _(not exercised)_
- Restart AR session after rotation: _(not exercised)_

Expected: predicted right remains screen-right, predicted up remains screen-up, no 90° axis swap, no
double rotation. Observed: _(not exercised)_

## True north

- Location with known non-zero declination used: _(not exercised)_
- Declination shown by the CAM-2b panel vs. an independent reference (e.g. a compass app): _(not
  exercised)_
- Predicted marker vs. legacy overlay position for a known bright star/planet-like anchor: _(not
  exercised)_
- Corrected prediction moves in the expected azimuth direction relative to declination sign: _(not
  exercised)_

Not claimed: absolute astronomical calibration from one visual check (per task instructions).

## Crop / FILL_CENTER

- Portrait viewport with landscape analysis buffer: _(not exercised)_
- Landscape viewport: _(not exercised)_
- Stars near each screen edge inspected for systematic center-crop offset: _(not exercised)_

## Intrinsics

- Fallback analysis-buffer path (`LEGACY_INTRINSICS_FALLBACK`) exercised: _(not exercised)_
- Physical-sensor intrinsics unavailable status (`PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED`)
  observed on a device that only exposes `CAMERA_CHARACTERISTICS`-sourced intrinsics: _(not exercised)_
- No silent use of unsupported calibrated FOV observed (panel always shows a categorized reason instead
  of a plausible-looking but fabricated projection): _(not exercised)_

## Lifecycle

- Background/foreground (≥3 cycles): _(not exercised)_
- Permission denial/regrant: _(not exercised)_
- Camera rebind: _(not exercised)_
- Viewport resize: _(not exercised)_
- Provider disposal (leave/re-enter AR, ≥5 cycles): fresh session shows `Waiting`, never the previous
  session's points, before its own first coherent geometry: _(not exercised)_
- New-session counter reset (CAM-1g's own `session:` counter, cross-checked against CAM-2b panel
  behavior): _(not exercised)_

## Stability

- Overlay follows device motion without obvious frame-backlog lag: _(not exercised)_
- No growing memory usage over an extended session: _(not exercised)_
- No marker accumulation (stale markers from a previous frame remaining on screen): _(not exercised)_
- No crash during rapid rotation: _(not exercised)_

## Record (fill in during an actual device pass)

- Device model: _(not exercised)_
- Android version: _(not exercised)_
- Camera lens: _(not exercised)_
- Buffer dimensions: _(not exercised)_
- Crop rect: _(not exercised)_
- Viewport dimensions: _(not exercised)_
- `rotationDegrees`: _(not exercised)_
- Intrinsics source/reference: _(not exercised)_
- Declination: _(not exercised)_
- Pair delta range: _(not exercised)_
- Observed issues: _(not exercised)_

## Renderer regression

- Existing legacy overlay behavior unchanged: **not exercised on-device.** `ArScreen.calculateOverlay()`
  and `projectionParams(viewport)` are unmodified by this PR beyond extracting the pre-existing
  `visibleStars` filter into a shared, byte-for-byte-equivalent `visibilitySelectedStars` function
  (verified by code review/diff, not by on-device observation) — see
  `docs/camera_star_prediction_contract.md` §14.8.

## Issues found

- None — no device session has been run yet.

## Gate verdict

**`CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`**

The bounded catalog adapter, pure reducer, explicit intrinsics-mode fallback, diagnostic state, and
Compose overlay/panel/controls are implemented, and JVM/Compose-UI tests have been authored **and now
actually executed through real Gradle** for all of them: `:core:astro-core:test` (388/388),
`:mobile:testInternalDebugUnitTest`/`testPublicDebugUnitTest` (271/271 each, including
`PredictedStarCatalogAdapterTest`/`PredictedStarOverlayReducerTest`/`PredictedStarOverlayFormatTest`),
`compileInternalDebugKotlin`/`compilePublicDebugKotlin`, `androidTest` compilation for both flavors (one
real, pre-existing, CAM-2b-unrelated bug found and fixed along the way — see above),
`lintInternalDebug`/`lintPublicDebug` (0 errors), and `assembleInternalDebug`/`assemblePublicDebug`. Full
release assembly is blocked only by a missing signing keystore, not by a code defect. **The one gate that
remains genuinely unexecuted is `:mobile:connectedInternalDebugAndroidTest`
(`PredictedStarOverlayUiTest`'s four cases: waiting/unavailable/ready/disposed-transition) and the
physical-device checklist below** — no physical device and no emulator (no `/dev/kvm`, no CPU
hardware-virtualization) were available in the environment this closure pass ran in. See
`docs/camera_star_prediction_contract.md` §14.10 for the itemized, per-gate disclosure. The physical-device
checklist above, plus CAM-1g's own checklist (`docs/validation/cam_1g_device_validation.md`), must still be
executed on a real device or emulator — and this file updated with real, non-fabricated results, and
reviewed — before this gate can close.
