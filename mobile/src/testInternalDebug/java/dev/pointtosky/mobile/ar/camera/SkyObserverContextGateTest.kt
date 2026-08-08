package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext
import dev.pointtosky.core.location.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKY-1 (`internalDebug`-only): a session must not be recordable without an observing context.
 *
 * A frame with `observer: null` carries pixels and a pose and nothing to project through them; the
 * offline replay skips every one of them with `OBSERVER_CONTEXT_UNAVAILABLE`. A whole session of those
 * is a night of storage spent on data no detector can be developed against, which is the entire point
 * of the format. So the block happens at Record, not per frame.
 *
 * The location half of this is pinned as pure functions rather than through the composable: resolving
 * a point and naming a permission state are the parts with a right answer, and neither needs a
 * `FusedLocationProvider`, a DataStore, or a permission dialog to check.
 */
class SkyObserverContextGateTest {
    private val fixtures = SkySessionCaptureFixtures

    private val kyiv = GeoPoint(latDeg = 50.4501, lonDeg = 30.5234)
    private val lviv = GeoPoint(latDeg = 49.8397, lonDeg = 24.0297)

    // -----------------------------------------------------------------------------------------
    // Which point a session observes from
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a manual override wins over the device fix, as everywhere else in the app`() {
        assertEquals(lviv, resolveSkyObserverPoint(manual = lviv, device = kyiv))
    }

    @Test
    fun `the device fix is used when no manual override is set`() {
        assertEquals(kyiv, resolveSkyObserverPoint(manual = null, device = kyiv))
    }

    @Test
    fun `a manual override applies even before any device fix arrives`() {
        assertEquals(lviv, resolveSkyObserverPoint(manual = lviv, device = null))
    }

    @Test
    fun `with neither source there is no point at all - never null island`() {
        val resolved = resolveSkyObserverPoint(manual = null, device = null)

        assertNull(resolved, "a (0, 0) fallback would be written into every frame as a real coordinate")
    }

    // -----------------------------------------------------------------------------------------
    // What the screen knows about the permission
    // -----------------------------------------------------------------------------------------

    @Test
    fun `an ungranted permission this screen has not asked for is an action, not a refusal`() {
        assertEquals(
            SkyLocationPermissionState.NOT_REQUESTED,
            skyLocationPermissionState(granted = false, requested = false),
        )
    }

    @Test
    fun `an ungranted permission this screen did ask for is a denial`() {
        assertEquals(
            SkyLocationPermissionState.DENIED,
            skyLocationPermissionState(granted = false, requested = true),
        )
    }

    @Test
    fun `a granted permission is granted however it was obtained`() {
        // Granted from system settings without this screen ever asking still reads as GRANTED.
        assertEquals(
            SkyLocationPermissionState.GRANTED,
            skyLocationPermissionState(granted = true, requested = false),
        )
        assertEquals(
            SkyLocationPermissionState.GRANTED,
            skyLocationPermissionState(granted = true, requested = true),
        )
    }

    @Test
    fun `the granted check accepts exactly what DeviceLocationRepository accepts`() {
        // The repository treats coarse-or-fine as granted. A narrower check here would call a user who
        // granted approximate-only "denied" while the location flow happily emits fixes.
        assertEquals(
            setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"),
            SKY_ACCEPTED_LOCATION_PERMISSIONS.toSet(),
        )
    }

    @Test
    fun `the screen only requests permissions the manifest declares`() {
        // Requesting an undeclared permission is denied by the system with no dialog shown, which would
        // read back as a refusal the operator never made. Only coarse is declared.
        assertEquals(setOf("android.permission.ACCESS_COARSE_LOCATION"), SKY_LOCATION_PERMISSIONS.toSet())
        assertTrue(SKY_LOCATION_PERMISSIONS.all { it in SKY_ACCEPTED_LOCATION_PERMISSIONS })
    }

    // -----------------------------------------------------------------------------------------
    // What the operator is told
    // -----------------------------------------------------------------------------------------

    private fun statusText(
        gate: SkyRecordingGate,
        permission: SkyLocationPermissionState,
        observer: SkyObserverContext?,
    ): String =
        skyCaptureStatusText(
            state = SkyCaptureUiState(),
            capability = fixtures.manualExposureCapability(),
            requested = DEFAULT_SKY_EXPOSURE,
            applied = null,
            gate = gate,
            bindFailure = null,
            resolution = AnalysisResolutionRequest(1280, 720, AnalysisResolutionFamily.NEAR_16_9),
            physicalCameraId = "3",
            locationPermission = permission,
            observer = observer,
        )

    private val blockedOnObserver =
        SkyRecordingGate.Blocked(SkyRecordingBlockedReason.OBSERVER_CONTEXT_UNAVAILABLE)

    @Test
    fun `a denied permission is named in the HUD as the thing blocking recording`() {
        val text = statusText(blockedOnObserver, SkyLocationPermissionState.DENIED, observer = null)

        assertTrue(text.contains("locationPermission=DENIED"), text)
        assertTrue(text.contains("BLOCKED(OBSERVER_CONTEXT_UNAVAILABLE)"), text)
        assertTrue(text.contains("DENIED; Record stays blocked"), text)
    }

    @Test
    fun `an unasked permission tells the operator there is an action to take`() {
        val text = statusText(blockedOnObserver, SkyLocationPermissionState.NOT_REQUESTED, observer = null)

        assertTrue(text.contains("locationPermission=NOT_REQUESTED"), text)
        assertTrue(text.contains("grant location permission below"), text)
    }

    @Test
    fun `a granted permission with no fix yet says so rather than blaming the permission`() {
        val text = statusText(blockedOnObserver, SkyLocationPermissionState.GRANTED, observer = null)

        assertTrue(text.contains("observer=UNAVAILABLE (no location fix)"), text)
        assertTrue(text.contains("waiting for a fix"), text)
    }

    @Test
    fun `a resolved observer is shown with its declination`() {
        val text =
            statusText(
                SkyRecordingGate.Allowed(
                    SkyResolvedExposure(
                        exposureTimeNanos = 500_000_000L,
                        sensitivityIso = 1600,
                        frameDurationNanos = 500_000_000L,
                    ),
                ),
                SkyLocationPermissionState.GRANTED,
                observer = fixtures.observer(),
            )

        assertTrue(text.contains("recording=ALLOWED"), text)
        assertTrue(text.contains("decl="), text)
    }

    @Test
    fun `a missing declination is distinguished from a missing fix`() {
        val text =
            statusText(
                blockedOnObserver,
                SkyLocationPermissionState.GRANTED,
                observer = fixtures.observer().copy(magneticDeclinationDeg = null),
            )

        assertTrue(text.contains("observer=UNAVAILABLE (no magnetic declination)"), text)
    }

    // -----------------------------------------------------------------------------------------
    // The gate itself, as the screen calls it
    // -----------------------------------------------------------------------------------------

    private fun gate(observer: SkyObserverContext?) =
        evaluateSkyRecordingGate(
            requested = DEFAULT_SKY_EXPOSURE,
            capability = fixtures.manualExposureCapability(),
            appliedExposure =
                assertIs<SkyExposureResolution.Resolved>(
                    fixtures.manualExposureCapability().resolve(DEFAULT_SKY_EXPOSURE),
                ).exposure,
            intrinsicsResolved = true,
            observer = observer,
        )

    @Test
    fun `no location permission means no observer means no recording`() {
        // With permission absent, DeviceLocationRepository emits null forever and no manual override
        // exists, so resolveSkyObserverPoint yields null and there is no context to record against.
        val observer =
            resolveSkyObserverPoint(manual = null, device = null)?.let {
                fixtures.observer()
            }

        assertEquals(
            SkyRecordingBlockedReason.OBSERVER_CONTEXT_UNAVAILABLE,
            assertIs<SkyRecordingGate.Blocked>(gate(observer)).reason,
        )
    }

    @Test
    fun `a valid observer lets the gate proceed`() {
        assertIs<SkyRecordingGate.Allowed>(gate(fixtures.observer()))
    }
}
