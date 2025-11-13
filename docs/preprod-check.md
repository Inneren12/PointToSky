# Pre-production verification checklist

The `preprodCheck` Gradle task runs the end-to-end test suite that mirrors the
most important mobile and wear flows before publishing a beta build.

```bash
./gradlew preprodCheck
```

This aggregate task executes:

- Core scenario and unit tests:
  - `:core:astro:testDebugUnitTest`
  - `:core:catalog:testDebugUnitTest`
  - `:core:location:android:testDebugUnitTest`
- Mobile tests:
  - `:mobile:testDebugUnitTest`
  - `:mobile:connectedDebugAndroidTest`
- Wear tests:
  - `:wear:testDebugUnitTest`
  - `:wear:connectedDebugAndroidTest`

## What is covered

- Orientation → horizontal → equatorial conversion with a fixed fake
  orientation frame, including catalog lookup of the closest bright object.
- Tonight target ranking using the shared star catalog to ensure bright objects
  above the horizon are prioritised.
- AR overlay calculations on mobile with deterministic orientation, location,
  and sky targets.
- Tonight tile mirroring payload generation on wear to guard JSON push
  compatibility.
- Smoke instrumentation flows on mobile and wear to make sure the Aim/AR route
  and the main activity start without crashes when feature flags are enabled.

## Manual checks before release

- Verify a real phone and watch can connect and exchange Aim/Tonight data.
- Confirm sensors (compass/orientation) calibrate correctly on device hardware.
- Grant location and camera permissions on a physical device and ensure the Aim
  overlay responds to device motion.
- Validate Tonight tile layout on an actual watch face when mirroring is on.
- Review analytics/log sinks for recent events after running the suite.
