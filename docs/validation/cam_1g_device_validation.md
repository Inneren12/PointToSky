# CAM-1g Device Validation

**Status: `CAM-1g BLOCKED ON PHYSICAL DEVICE VALIDATION`**

This PR was authored and tested by a coding agent with no physical Android device, no camera
hardware, and no rotation sensor available. Only JVM unit tests, static review, and (where the
sandbox permitted) JVM-level Gradle test runs were performed — see
`docs/camera_coordinate_calibration_contract.md` §11.9 and the exact command/result log in this PR's
description. **Every checklist item below is transcribed from the CAM-1g task description,
unexecuted.** None of the results in this file are fabricated or guessed — the fields are left blank
intentionally, and must stay blank until someone actually runs the checklist on a physical device.

Do not treat this file as a completed report. Do not mark the CAM-1g gate as passed based on JVM
tests, emulator execution, or static review alone.

## Build

- Commit: _(fill in the commit this build was produced from)_
- Variant: `internalDebug` (`:mobile:assembleInternalDebug`)
- Device category: _(not exercised — no physical device available)_
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
unit tests (see the PR description for exact commands/results). The physical-device checklist above
must be executed and this file updated with real, non-fabricated results — and reviewed — before
CAM-2a (predict-only projection) begins.
