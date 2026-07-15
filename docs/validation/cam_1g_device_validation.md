# CAM-1g Device Validation

**Status: `CAM-1g BLOCKED ON PHYSICAL DEVICE VALIDATION`**

Originally authored and tested by a coding agent with no physical Android device, no camera hardware,
no rotation sensor, and (at the time) no working Gradle/Android SDK — only JVM unit tests and static
review were performed. A later CAM-2b validation-closure session provisioned an Android SDK and JDK-17
toolchain and ran the real Gradle build/lint/assemble gates for the whole `:mobile` module (see
`docs/camera_coordinate_calibration_contract.md` §11.9 and `docs/SPRINT_STATUS.md` for exact commands),
which compiles and lints CAM-1g's own `CameraGeometryDiagnosticsGate`/`CameraGeometryDiagnostics`/panel
code as part of `compileInternalDebugKotlin`, `lintInternalDebug`, and `assembleInternalDebug` — all
passed. **That is real evidence CAM-1g's code still compiles, lints clean, and packages; it is not a
physical-device pass** — no phone or emulator was available in that session either (no `adb`-visible
device, no `/dev/kvm`). **Every checklist item below is still transcribed from the CAM-1g task
description, unexecuted.** None of the results in this file are fabricated or guessed — the fields are
left blank intentionally, and must stay blank until someone actually runs the checklist on a physical
device.

Do not treat this file as a completed report. Do not mark the CAM-1g gate as passed based on JVM
tests, real-Gradle compile/lint/assemble results (however real), emulator execution, or static review
alone.

## Build

- Commit: see `docs/SPRINT_STATUS.md` for the exact Gradle commands and results of the validation-closure
  pass that last exercised this build
- Variant: `internalDebug` (`:mobile:assembleInternalDebug`) — built successfully via real Gradle
- Device category: _(not exercised — no physical device or emulator available)_
- Android version: _(not exercised)_

## Initial session (Test A)

- Status progression observed: _(not exercised)_
- Time until first `Ready` (approx.): _(not exercised)_
- Buffer: _(not exercised)_
- Crop: _(not exercised)_
- Rotation: _(not exercised)_
- Viewport: _(not exercised)_
- Pair delta: _(not exercised)_
- Intrinsics source: _(not exercised)_
- Geometry quality: _(not exercised)_
- Scale / offset: _(not exercised)_
- Center round-trip error: _(not exercised)_
- No crash / preview remained visible: _(not exercised)_

## Motion (Tests B, C, D)

- Stationary (10 s+): pair delta min / typical / max: _(not exercised)_; `Ready` stability: _(not exercised)_;
  repeated clock mismatch observed: _(not exercised)_; center round-trip error near zero: _(not exercised)_
- Slow pan: bundle stayed `Ready`/known pairing status: _(not exercised)_; frame/ready counts kept
  increasing: _(not exercised)_; no stale timestamp or crash: _(not exercised)_
- Fast pan: pairing reached `OUTSIDE_TOLERANCE`: _(not exercised)_; automatic recovery to `Ready`:
  _(not exercised)_; unexpected `CLOCK_MISMATCH_SUSPECTED`: _(not exercised)_; permanently stuck state:
  _(not exercised)_

## Orientation transitions (Test E)

| Orientation | Buffer | Rotation | Viewport | Scale | Offset X/Y | Round-trip error |
|---|---|---|---|---|---|---|
| Portrait (initial) | _(not exercised)_ | | | | | |
| Landscape left | _(not exercised)_ | | | | | |
| Portrait | _(not exercised)_ | | | | | |
| Landscape right | _(not exercised)_ | | | | | |
| Portrait (final) | _(not exercised)_ | | | | | |

Viewport rebuild / no stale previous viewport / center-probe stays reversible / no crash: _(not
exercised)_.

## Lifecycle (Tests F, G)

- Background/foreground (≥3 cycles): camera resumes/rebinds cleanly: _(not exercised)_; no
  permanently stale geometry: _(not exercised)_; intrinsics lookup not repeated per frame: _(not
  exercised)_; same composition/session vs. new one: _(not exercised)_
- Leave/re-enter AR (≥5 cycles): fresh `session:` id visible each time: _(not exercised)_; prior
  frame timestamp/counters do not leak: _(not exercised)_; prior `Ready` bundle not shown immediately
  as current: _(not exercised)_; intrinsics resolved once per new session: _(not exercised)_; no
  post-disposal publish; no camera bind leak; no crash: _(not exercised)_

## Permission denial (Test H)

- Denying camera permission never shows `Ready` geometry: _(not exercised)_
- Granting permission afterward reaches a fresh `Ready` session: _(not exercised)_

## Preview-only fallback (Test I)

- Exercised: **no**
- Result: not exercised — no test/debug seam for forcing combined-bind failure was exercised on a
  physical device in this PR. (The production Preview-only fallback path itself is unchanged CAM-1c
  behavior; see `docs/camera_coordinate_calibration_contract.md` §4.4.)

## Renderer regression

- Existing overlay behavior unchanged: **not exercised on-device.** `ArScreen.calculateOverlay()` and
  `projectionParams(viewport)` are unmodified by this PR (verified by code review/diff, not by
  on-device observation) — see §11.8 of the calibration contract doc.

## Issues found

- None — no device session has been run yet.

## Gate verdict

**`CAM-1g BLOCKED ON PHYSICAL DEVICE VALIDATION`**

The diagnostic overlay, pure mapper, formatting, and center-probe are implemented and covered by JVM
unit tests, and the code compiles/lints/assembles clean under real Gradle (see
`docs/camera_coordinate_calibration_contract.md` §11.9 and `docs/SPRINT_STATUS.md`). The physical-device
checklist above must still be executed on a real device or emulator and this file updated with real,
non-fabricated results — and reviewed — before this gate can close.
