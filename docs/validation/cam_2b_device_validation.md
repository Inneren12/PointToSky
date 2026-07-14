# CAM-2b Device Validation

**Status: `CAM-2b BLOCKED ON PHYSICAL DEVICE VALIDATION`**

This PR was authored and tested by a coding agent with no physical Android device, no camera hardware,
and no rotation sensor available. Only JVM unit tests and static review were performed — Gradle itself
could not run any `:mobile` task in the authoring sandbox (no Android SDK installed, no network path to
provision one); see `docs/camera_star_prediction_contract.md` §14.10 for the exact disclosed limitation.
**Every checklist item below is transcribed from the CAM-2b task description, unexecuted.** None of the
results in this file are fabricated or guessed — the fields are left blank intentionally, and must stay
blank until someone actually runs the checklist on a physical device.

Do not treat this file as a completed report. Do not mark the CAM-2b gate as passed based on JVM tests
or static review alone. This checklist assumes CAM-1g's own device gate
(`docs/validation/cam_1g_device_validation.md`) is exercised first or alongside — CAM-2b's predicted
overlay is only as trustworthy as the CAM-1c–1g geometry pipeline feeding it.

## Build

- Commit: _(fill in the commit this build was produced from)_
- Variant: `internalDebug` (`:mobile:assembleInternalDebug`)
- Device model: _(not exercised — no physical device available)_
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

The bounded catalog adapter, pure reducer, diagnostic state, and Compose overlay/panel/controls are
implemented and covered by JVM unit tests (see the PR description for exact commands — none of which
could actually be executed in the authoring sandbox; see `docs/camera_star_prediction_contract.md`
§14.10). The physical-device checklist above, plus CAM-1g's own checklist
(`docs/validation/cam_1g_device_validation.md`), must be executed and both files updated with real,
non-fabricated results — and reviewed — before this gate can close.
