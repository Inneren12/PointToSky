package dev.pointtosky.wear.aim.core

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.Ephemeris
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.api.LocationOrchestrator
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import dev.pointtosky.core.time.TimeSource
import dev.pointtosky.wear.sensors.orientation.OrientationAccuracy
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import dev.pointtosky.wear.sensors.orientation.OrientationSource
import dev.pointtosky.wear.sensors.orientation.OrientationZero
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultAimControllerTest {
    @Test
    fun `StarTarget without embedded eq resolves via starResolver`() =
        runBlocking {
            val resolvedEq = Equatorial(raDeg = 10.0, decDeg = 45.0)
            val expectedHorizontal = Horizontal(azDeg = 90.0, altDeg = 30.0)

            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(GeoPoint(52.0, 13.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { eq, _, _ ->
                        if (eq == resolvedEq) expectedHorizontal
                        else Horizontal(azDeg = 0.0, altDeg = 0.0)
                    },
                    starResolver = { id -> if (id == 42) resolvedEq else null },
                )

            // StarTarget with no embedded eq — resolver must supply the coordinates.
            controller.setTarget(AimTarget.StarTarget(starId = 42, eq = null))
            controller.setHoldToLockMs(0)
            controller.setTolerance(AimTolerance(azDeg = 5.0, altDeg = 5.0))
            controller.start()

            location.emit(
                LocationFix(
                    point = GeoPoint(52.0, 13.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.MANUAL,
                ),
            )

            // Aim ray on target — should enter IN_TOLERANCE (not stay SEARCHING forever).
            orientation.emit(orientationFrame(timestampMs = 0L, azDeg = 90.0, altDeg = 30.0))

            val state =
                withTimeout(1_000) {
                    controller.state.filter {
                        it.phase == AimPhase.IN_TOLERANCE || it.phase == AimPhase.LOCKED
                    }.first()
                }

            assertEquals(
                expectedHorizontal.azDeg,
                state.target.azDeg,
                1e-6,
                "resolver-supplied equatorial must be used as target",
            )
            controller.stop()
        }

    @Test
    fun `dAz wraps across 0-360`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(GeoPoint(0.0, 0.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val targetHorizontal = Horizontal(1.0, 0.0)
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> targetHorizontal },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.start()

            location.emit(
                LocationFix(
                    point = GeoPoint(0.0, 0.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.MANUAL,
                ),
            )

            val frame = orientationFrame(timestampMs = 0L, azDeg = 359.0, altDeg = 0.0)
            orientation.emit(frame)

            val state =
                withTimeout(1_000) {
                    controller.state.filter { it.current.azDeg != 0.0 }.first()
                }

            assertEquals(359.0, state.current.azDeg, 1e-3)
            assertEquals(1.0, state.target.azDeg, 1e-6)
            assertEquals(2.0, state.dAzDeg, 1e-6)
            controller.stop()
        }

    @Test
    fun `locks after hold duration and resets when out of tolerance`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(GeoPoint(0.0, 0.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val targetHorizontal = Horizontal(0.0, 0.0)
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> targetHorizontal },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(500)
            controller.setTolerance(AimTolerance(azDeg = 5.0, altDeg = 5.0))
            controller.start()

            location.emit(
                LocationFix(
                    point = GeoPoint(0.0, 0.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.MANUAL,
                ),
            )

            orientation.emit(orientationFrame(timestampMs = 0L, azDeg = 0.0, altDeg = 0.0))

            withTimeout(1_000) {
                controller.state.filter { it.phase == AimPhase.IN_TOLERANCE }.first()
            }

            orientation.emit(orientationFrame(timestampMs = 700L, azDeg = 0.0, altDeg = 0.0))

            withTimeout(1_000) {
                controller.state.filter { it.phase == AimPhase.LOCKED }.first()
            }

            // Need RELEASE_TICKS (3) consecutive frames beyond the release box (tol.azDeg * 1.8 = 9°)
            // to drop from LOCKED back to SEARCHING. Each frame must pass the rate limiter (>66ms apart).
            repeat(3) { i ->
                delay(100)
                orientation.emit(orientationFrame(timestampMs = 1_400L + i * 100L, azDeg = 20.0, altDeg = 0.0))
            }

            val state =
                withTimeout(2_000) {
                    controller.state.filter { it.phase == AimPhase.SEARCHING }.first()
                }

            // At alt=0 cross-track == raw azimuth delta; 20° is well beyond the 9° release box
            assertTrue { kotlin.math.abs(state.dAzDeg) >= 5.0 }
            controller.stop()
        }

    @Test
    fun `near-zenith lock - large raw azimuth but small cross-track enters tolerance and locks`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(GeoPoint(0.0, 0.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            // Target at az=15°, alt=85°. Raw dAz=15° > tol.azDeg=3°, but cross-track ≈ 15°*cos(85°) ≈ 1.3° < 3°.
            val targetHorizontal = Horizontal(azDeg = 15.0, altDeg = 85.0)
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> targetHorizontal },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(0) // lock on second tick
            controller.setTolerance(AimTolerance(azDeg = 3.0, altDeg = 4.0))
            controller.start()

            location.emit(
                LocationFix(
                    point = GeoPoint(0.0, 0.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.MANUAL,
                ),
            )

            // Ray held at az=0°, alt=85° — same altitude as target, raw azimuth offset 15°
            orientation.emit(orientationFrame(timestampMs = 0L, azDeg = 0.0, altDeg = 85.0))
            withTimeout(1_000) {
                controller.state.filter { it.phase == AimPhase.IN_TOLERANCE }.first()
            }

            delay(100)
            orientation.emit(orientationFrame(timestampMs = 100L, azDeg = 0.0, altDeg = 85.0))
            withTimeout(1_000) {
                controller.state.filter { it.phase == AimPhase.LOCKED }.first()
            }

            controller.stop()
        }

    @Test
    fun `release-box time does not accumulate toward lock`() =
        runBlocking {
            // If the ray drifts into the grace zone (outside enter, inside release) for longer
            // than holdMs the phase must NOT become LOCKED — hold time accumulates only while
            // inside the enter box.
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(GeoPoint(0.0, 0.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val targetHorizontal = Horizontal(azDeg = 0.0, altDeg = 0.0)
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> targetHorizontal },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(300) // short hold so flakiness window is small
            controller.setTolerance(AimTolerance(azDeg = 3.0, altDeg = 4.0))
            controller.start()

            location.emit(
                LocationFix(
                    point = GeoPoint(0.0, 0.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.MANUAL,
                ),
            )

            // Enter tolerance
            orientation.emit(orientationFrame(timestampMs = 0L, azDeg = 0.0, altDeg = 0.0))
            withTimeout(1_000) { controller.state.filter { it.phase == AimPhase.IN_TOLERANCE }.first() }

            // Drift into the grace zone (az=4°: > 3° enter, < 5.4°=3.0*1.8 release) and stay
            // there for > holdMs. Each frame resets inTolSinceMs so lock time must not accumulate.
            repeat(4) { i ->
                delay(100) // > MIN_UPDATE_INTERVAL_MS (66ms) and > holdMs/4
                orientation.emit(orientationFrame(timestampMs = (i + 1) * 100L, azDeg = 4.0, altDeg = 0.0))
            }
            delay(100) // ensure the last frame is processed before asserting

            val phase = controller.state.value.phase
            assertEquals(
                AimPhase.IN_TOLERANCE,
                phase,
                "Grace-zone time must not count toward lock; expected IN_TOLERANCE but got $phase",
            )

            controller.stop()
        }

    @Test
    fun `locked stays locked during brief inside-release-box blip`() =
        runBlocking {
            // A single frame inside the release box (but outside the enter box) while LOCKED
            // must NOT trigger a phase drop.
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(GeoPoint(0.0, 0.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val targetHorizontal = Horizontal(azDeg = 0.0, altDeg = 0.0)
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> targetHorizontal },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(0) // lock on second tick
            controller.setTolerance(AimTolerance(azDeg = 3.0, altDeg = 4.0))
            controller.start()

            location.emit(
                LocationFix(
                    point = GeoPoint(0.0, 0.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.MANUAL,
                ),
            )

            // Reach LOCKED
            orientation.emit(orientationFrame(timestampMs = 0L, azDeg = 0.0, altDeg = 0.0))
            withTimeout(1_000) { controller.state.filter { it.phase == AimPhase.IN_TOLERANCE }.first() }
            delay(100)
            orientation.emit(orientationFrame(timestampMs = 100L, azDeg = 0.0, altDeg = 0.0))
            withTimeout(1_000) { controller.state.filter { it.phase == AimPhase.LOCKED }.first() }

            // Single blip inside release box (az=4°: > enter 3°, < release 5.4°) — must stay LOCKED
            delay(100)
            orientation.emit(orientationFrame(timestampMs = 200L, azDeg = 4.0, altDeg = 0.0))
            delay(200)

            val phase = controller.state.value.phase
            assertEquals(
                AimPhase.LOCKED,
                phase,
                "LOCKED must survive a release-box blip; expected LOCKED but got $phase",
            )

            controller.stop()
        }

    @Test
    fun `hysteresis hold-through - release-box blip does not drop phase to SEARCHING`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(GeoPoint(0.0, 0.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val targetHorizontal = Horizontal(azDeg = 0.0, altDeg = 0.0)
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> targetHorizontal },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(2_000) // long hold so blip frames don't accidentally lock
            controller.setTolerance(AimTolerance(azDeg = 3.0, altDeg = 4.0))
            controller.start()

            location.emit(
                LocationFix(
                    point = GeoPoint(0.0, 0.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.MANUAL,
                ),
            )

            // Get into IN_TOLERANCE
            orientation.emit(orientationFrame(timestampMs = 0L, azDeg = 0.0, altDeg = 0.0))
            withTimeout(1_000) {
                controller.state.filter { it.phase == AimPhase.IN_TOLERANCE }.first()
            }

            // Feed a frame inside the release box (az=4° > 3° enter, but < 5.4°=3.0*1.8 release)
            // Phase must NOT drop to SEARCHING — this is the key regression test.
            delay(100) // ensure rate limiter allows the tick
            orientation.emit(orientationFrame(timestampMs = 100L, azDeg = 4.0, altDeg = 0.0))
            delay(200) // give the controller time to process

            val phase = controller.state.value.phase
            assertTrue(
                phase != AimPhase.SEARCHING,
                "Expected phase to stay IN_TOLERANCE during release-box blip, was $phase",
            )

            controller.stop()
        }

    @Test
    fun `hysteresis release - RELEASE_TICKS beyond-release-box frames drop to SEARCHING`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(GeoPoint(0.0, 0.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val targetHorizontal = Horizontal(azDeg = 0.0, altDeg = 0.0)
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> targetHorizontal },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(0) // lock on second tick
            controller.setTolerance(AimTolerance(azDeg = 3.0, altDeg = 4.0))
            controller.start()

            location.emit(
                LocationFix(
                    point = GeoPoint(0.0, 0.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.MANUAL,
                ),
            )

            // Reach LOCKED (two ticks with holdMs=0)
            orientation.emit(orientationFrame(timestampMs = 0L, azDeg = 0.0, altDeg = 0.0))
            withTimeout(1_000) { controller.state.filter { it.phase == AimPhase.IN_TOLERANCE }.first() }
            delay(100)
            orientation.emit(orientationFrame(timestampMs = 100L, azDeg = 0.0, altDeg = 0.0))
            withTimeout(1_000) { controller.state.filter { it.phase == AimPhase.LOCKED }.first() }

            // Emit exactly RELEASE_TICKS (3) frames beyond the release box (az=10° > 5.4°=3.0*1.8)
            repeat(3) { i ->
                delay(100) // > MIN_UPDATE_INTERVAL_MS (66ms) so each frame passes the rate limiter
                orientation.emit(orientationFrame(timestampMs = 200L + i * 100L, azDeg = 10.0, altDeg = 0.0))
            }

            withTimeout(2_000) {
                controller.state.filter { it.phase == AimPhase.SEARCHING }.first()
            }

            controller.stop()
        }

    @Test
    fun `setTarget while locked resets phase machine to SEARCHING`() =
        runBlocking {
            // Lock onto target A (raDeg=0 → az 0°/alt 0°), then switch to target B
            // (raDeg=100 → az 120°). The ray stays at az 0°, so B is far out of tolerance.
            // The first published state after the switch must be SEARCHING, not LOCKED.
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(GeoPoint(0.0, 0.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { eq, _, _ ->
                        if (eq.raDeg == 0.0) Horizontal(azDeg = 0.0, altDeg = 0.0)
                        else Horizontal(azDeg = 120.0, altDeg = 0.0)
                    },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(0) // lock on second tick
            controller.setTolerance(AimTolerance(azDeg = 3.0, altDeg = 4.0))
            controller.start()

            location.emit(
                LocationFix(
                    point = GeoPoint(0.0, 0.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.MANUAL,
                ),
            )

            // Lock onto A: ray at az=0, target A at az=0
            orientation.emit(orientationFrame(timestampMs = 0L, azDeg = 0.0, altDeg = 0.0))
            withTimeout(1_000) { controller.state.filter { it.phase == AimPhase.IN_TOLERANCE }.first() }
            delay(100)
            orientation.emit(orientationFrame(timestampMs = 100L, azDeg = 0.0, altDeg = 0.0))
            withTimeout(1_000) { controller.state.filter { it.phase == AimPhase.LOCKED }.first() }

            // Switch to B while still LOCKED — resetPhaseMachine() must fire immediately
            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(100.0, 0.0)))

            // The state update from resetPhaseMachine() is synchronous; the next published state
            // must be SEARCHING. We also emit a new frame to confirm the tick agrees.
            withTimeout(1_000) { controller.state.filter { it.phase == AimPhase.SEARCHING }.first() }

            delay(100)
            orientation.emit(orientationFrame(timestampMs = 200L, azDeg = 0.0, altDeg = 0.0))
            delay(200)

            // Ray is 120° away from B — must remain SEARCHING
            assertEquals(
                AimPhase.SEARCHING,
                controller.state.value.phase,
                "After target switch the phase must stay SEARCHING when ray is far from new target",
            )

            controller.stop()
        }

    @Test
    fun `no location fix - phase is NO_LOCATION and never locks`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            // getLastKnown() == null and fixes emits nothing → the controller has no fix at all.
            val location = FakeLocationOrchestrator(null)
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    // A ray at az0/alt0 would match this target and lock if the (0,0) fallback regressed.
                    raDecToAltAz = { _, _, _ -> Horizontal(azDeg = 0.0, altDeg = 0.0) },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(0)
            controller.setTolerance(AimTolerance(azDeg = 5.0, altDeg = 5.0))
            controller.start()

            // First frame on the would-be target → NO_LOCATION (no fix to compute against).
            orientation.emit(orientationFrame(timestampMs = 0L, azDeg = 0.0, altDeg = 0.0))
            withTimeout(1_000) {
                controller.state.filter { it.phase == AimPhase.NO_LOCATION }.first()
            }

            // Keep the ray on target across several frames — must never guide or lock.
            repeat(5) { i ->
                delay(100)
                orientation.emit(orientationFrame(timestampMs = (i + 1) * 100L, azDeg = 0.0, altDeg = 0.0))
                delay(50)
                val phase = controller.state.value.phase
                assertTrue(
                    phase != AimPhase.IN_TOLERANCE && phase != AimPhase.LOCKED,
                    "Without a location fix the controller must not guide; got $phase",
                )
            }
            assertEquals(AimPhase.NO_LOCATION, controller.state.value.phase)
            controller.stop()
        }

    @Test
    fun `location fix arrives - NO_LOCATION clears`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(null)
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> Horizontal(azDeg = 0.0, altDeg = 30.0) },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(0)
            controller.setTolerance(AimTolerance(azDeg = 5.0, altDeg = 5.0))
            controller.start()

            // No fix yet → NO_LOCATION.
            orientation.emit(orientationFrame(timestampMs = 0L, azDeg = 0.0, altDeg = 30.0))
            withTimeout(1_000) {
                controller.state.filter { it.phase == AimPhase.NO_LOCATION }.first()
            }

            // A real fix arrives. Re-emit it each tick: the controller's fixes subscription may not
            // have been active for the first emission (SharedFlow has no replay), and a single
            // dropped emission would otherwise hang the test.
            val realFix =
                LocationFix(
                    point = GeoPoint(45.0, -113.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.GPS,
                )
            val cleared =
                withTimeout(2_000) {
                    var phase = controller.state.value.phase
                    var t = 100L
                    while (phase == AimPhase.NO_LOCATION) {
                        location.emit(realFix)
                        orientation.emit(orientationFrame(timestampMs = t, azDeg = 0.0, altDeg = 30.0))
                        delay(100)
                        phase = controller.state.value.phase
                        t += 100
                    }
                    phase
                }
            assertTrue(
                cleared != AimPhase.NO_LOCATION,
                "NO_LOCATION must clear once a real fix arrives; got $cleared",
            )
            controller.stop()
        }

    @Test
    fun `target below horizon - phase is BELOW_HORIZON and never locks`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            // Real fix present via the getLastKnown() seed.
            val location = FakeLocationOrchestrator(GeoPoint(45.0, -113.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    // Target sits 20° below the horizon.
                    raDecToAltAz = { _, _, _ -> Horizontal(azDeg = 0.0, altDeg = -20.0) },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(0) // would lock instantly if the horizon gate were missing
            controller.setTolerance(AimTolerance(azDeg = 5.0, altDeg = 5.0))
            controller.start()

            // Wait for BELOW_HORIZON (tolerate a transient NO_LOCATION before the seed lands).
            withTimeout(2_000) {
                var t = 0L
                while (controller.state.value.phase != AimPhase.BELOW_HORIZON) {
                    orientation.emit(orientationFrame(timestampMs = t, azDeg = 0.0, altDeg = -20.0))
                    delay(100)
                    t += 100
                }
            }

            // Ray stays exactly on the sub-horizon target — must never guide or lock.
            repeat(5) { i ->
                orientation.emit(orientationFrame(timestampMs = 600L + i * 100L, azDeg = 0.0, altDeg = -20.0))
                delay(100)
                assertEquals(AimPhase.BELOW_HORIZON, controller.state.value.phase)
            }
            controller.stop()
        }

    @Test
    fun `target rises above horizon - re-acquires from SEARCHING`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(GeoPoint(45.0, -113.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            // Target altitude flips from below to above the horizon. StateFlow.value reads are
            // volatile, so the tick coroutine observes the change without extra synchronisation.
            val targetAlt = MutableStateFlow(-5.0)
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> Horizontal(azDeg = 0.0, altDeg = targetAlt.value) },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(300) // a real hold is required; no instant inherited lock
            controller.setTolerance(AimTolerance(azDeg = 5.0, altDeg = 5.0))
            controller.start()

            // Below the horizon first.
            withTimeout(2_000) {
                var t = 0L
                while (controller.state.value.phase != AimPhase.BELOW_HORIZON) {
                    orientation.emit(orientationFrame(timestampMs = t, azDeg = 0.0, altDeg = -5.0))
                    delay(100)
                    t += 100
                }
            }

            // Target rises; the ray follows it. The first frame after the rise must NOT inherit a
            // lock — it re-acquires from scratch (IN_TOLERANCE, never instant LOCKED).
            targetAlt.value = 30.0
            delay(100)
            orientation.emit(orientationFrame(timestampMs = 1_000L, azDeg = 0.0, altDeg = 30.0))
            val firstAfter =
                withTimeout(1_000) {
                    controller.state.filter { it.phase != AimPhase.BELOW_HORIZON }.first()
                }
            assertTrue(
                firstAfter.phase == AimPhase.IN_TOLERANCE || firstAfter.phase == AimPhase.SEARCHING,
                "After rising the target must re-acquire (not instant LOCKED); got ${firstAfter.phase}",
            )

            // With continued on-target frames it locks via the normal hold.
            val locked =
                withTimeout(2_000) {
                    var t = 1_100L
                    var s = controller.state.value
                    while (s.phase != AimPhase.LOCKED) {
                        orientation.emit(orientationFrame(timestampMs = t, azDeg = 0.0, altDeg = 30.0))
                        delay(100)
                        s = controller.state.value
                        t += 100
                    }
                    s
                }
            assertEquals(AimPhase.LOCKED, locked.phase)
            controller.stop()
        }

    @Test
    fun `restart without a fix - stale lastFix is cleared and never guides`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            // Session 1 has a real fix (via the getLastKnown() seed); session 2 will have none.
            val location = FakeLocationOrchestrator(GeoPoint(45.0, -113.0))
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    // On-target ray (az0/alt30) — would lock if a stale fix leaked into session 2.
                    raDecToAltAz = { _, _, _ -> Horizontal(azDeg = 0.0, altDeg = 30.0) },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(0)
            controller.setTolerance(AimTolerance(azDeg = 5.0, altDeg = 5.0))

            // Session 1: real fix present → reach a guiding phase, so lastFix holds a real fix.
            controller.start()
            withTimeout(2_000) {
                var t = 0L
                while (controller.state.value.phase != AimPhase.IN_TOLERANCE &&
                    controller.state.value.phase != AimPhase.LOCKED
                ) {
                    orientation.emit(orientationFrame(timestampMs = t, azDeg = 0.0, altDeg = 30.0))
                    delay(100)
                    t += 100
                }
            }
            controller.stop()

            // The fix source now reports no location for the next session.
            location.setManual(null)

            // Session 2: the previous session's fix must not leak in. Every on-target frame must read
            // a non-guiding phase (the stale fix would otherwise lock instantly with holdMs=0), and the
            // controller must settle on NO_LOCATION.
            controller.start()
            var sawNoLocation = false
            withTimeout(2_000) {
                var t = 5_000L
                while (!sawNoLocation) {
                    orientation.emit(orientationFrame(timestampMs = t, azDeg = 0.0, altDeg = 30.0))
                    delay(100)
                    val phase = controller.state.value.phase
                    assertTrue(
                        phase != AimPhase.IN_TOLERANCE && phase != AimPhase.LOCKED,
                        "A stale fix must not guide/lock after restart without a fix; got $phase",
                    )
                    if (phase == AimPhase.NO_LOCATION) sawNoLocation = true
                    t += 100
                }
            }
            assertEquals(AimPhase.NO_LOCATION, controller.state.value.phase)
            controller.stop()
        }

    @Test
    fun `fix lost mid-session - drops to NO_LOCATION`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(null)
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> Horizontal(azDeg = 0.0, altDeg = 30.0) },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(0)
            controller.setTolerance(AimTolerance(azDeg = 5.0, altDeg = 5.0))
            controller.start()

            // Acquire mid-session: loop emit GPS fix + on-target frames until LOCKED.
            val gpsFix =
                LocationFix(
                    point = GeoPoint(45.0, -113.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.GPS,
                )
            withTimeout(2_000) {
                var t = 0L
                while (controller.state.value.phase != AimPhase.LOCKED) {
                    location.emit(gpsFix)
                    orientation.emit(orientationFrame(timestampMs = t, azDeg = 0.0, altDeg = 30.0))
                    delay(100)
                    t += 100
                }
            }

            // Lose it: setManual(null) sets _currentFix to null without emitting on fixes (mirrors
            // the real orchestrator: loss is silent on the non-null flow).
            location.setManual(null)

            // Controller must settle on NO_LOCATION.
            withTimeout(2_000) {
                var t = 5_000L
                while (controller.state.value.phase != AimPhase.NO_LOCATION) {
                    orientation.emit(orientationFrame(timestampMs = t, azDeg = 0.0, altDeg = 30.0))
                    delay(100)
                    t += 100
                }
            }
            assertEquals(AimPhase.NO_LOCATION, controller.state.value.phase)
            controller.stop()
        }

    @Test
    fun `stationary fix - stays guiding without re-emission`() =
        runBlocking {
            val orientation = FakeOrientationRepository()
            val location = FakeLocationOrchestrator(null)
            val timeSource = FakeTimeSource(Instant.EPOCH)
            val ephem = FakeEphemerisComputer()
            val controller =
                DefaultAimController(
                    orientation = orientation,
                    location = location,
                    time = timeSource,
                    ephem = ephem,
                    raDecToAltAz = { _, _, _ -> Horizontal(azDeg = 0.0, altDeg = 30.0) },
                )

            controller.setTarget(AimTarget.EquatorialTarget(Equatorial(0.0, 0.0)))
            controller.setHoldToLockMs(0)
            controller.setTolerance(AimTolerance(azDeg = 5.0, altDeg = 5.0))
            controller.start()

            // Acquire: emit fix once, then drive orientation frames until LOCKED.
            val fix =
                LocationFix(
                    point = GeoPoint(45.0, -113.0),
                    timeMs = 0,
                    accuracyM = null,
                    provider = ProviderType.GPS,
                )
            withTimeout(2_000) {
                var t = 0L
                while (controller.state.value.phase != AimPhase.LOCKED) {
                    location.emit(fix)
                    orientation.emit(orientationFrame(timestampMs = t, azDeg = 0.0, altDeg = 30.0))
                    delay(100)
                    t += 100
                }
            }

            // Stationary: no further location.emit and no clear. A quiet provider ≠ lost location.
            repeat(5) { i ->
                delay(100)
                orientation.emit(orientationFrame(timestampMs = 5_000L + i * 100L, azDeg = 0.0, altDeg = 30.0))
                delay(50)
                val phase = controller.state.value.phase
                assertTrue(
                    phase == AimPhase.LOCKED || phase == AimPhase.IN_TOLERANCE,
                    "Quiet provider while stationary must not trigger NO_LOCATION; got $phase",
                )
            }
            controller.stop()
        }

    private fun orientationFrame(
        timestampMs: Long,
        azDeg: Double,
        altDeg: Double,
    ): OrientationFrame {
        val azRad = Math.toRadians(azDeg)
        val altRad = Math.toRadians(altDeg)
        val cosAlt = kotlin.math.cos(altRad)
        val east = (kotlin.math.sin(azRad) * cosAlt).toFloat()
        val north = (kotlin.math.cos(azRad) * cosAlt).toFloat()
        val up = kotlin.math.sin(altRad).toFloat()
        return OrientationFrame(
            timestampNanos = timestampMs * 1_000_000,
            azimuthDeg = azDeg.toFloat(),
            pitchDeg = 0f,
            rollDeg = 0f,
            forward = floatArrayOf(east, north, up),
            accuracy = OrientationAccuracy.HIGH,
            rotationMatrix = FloatArray(9),
        )
    }

    private class FakeOrientationRepository : OrientationRepository {
        private val _frames = MutableSharedFlow<OrientationFrame>(extraBufferCapacity = 16)
        override val frames: Flow<OrientationFrame> = _frames
        override val zero: StateFlow<OrientationZero> = MutableStateFlow(OrientationZero())
        override val fps: StateFlow<Float?> = MutableStateFlow(null)
        override val source: OrientationSource = OrientationSource.ROTATION_VECTOR
        override val activeSource: StateFlow<OrientationSource> = MutableStateFlow(OrientationSource.ROTATION_VECTOR)
        override val isRunning: StateFlow<Boolean> = MutableStateFlow(true)

        override fun start(config: dev.pointtosky.wear.sensors.orientation.OrientationRepositoryConfig) = Unit

        override fun stop() = Unit

        override fun updateZero(orientationZero: OrientationZero) = Unit

        override fun setRemap(screenRotation: dev.pointtosky.wear.sensors.orientation.ScreenRotation) = Unit

        suspend fun emit(frame: OrientationFrame) {
            _frames.emit(frame)
        }
    }

    private class FakeLocationOrchestrator(
        initial: GeoPoint?,
    ) : LocationOrchestrator {
        private val _fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 4)
        private var lastFix: LocationFix? =
            initial?.let {
                LocationFix(point = it, timeMs = 0, accuracyM = null, provider = ProviderType.MANUAL)
            }

        private val _currentFix = MutableStateFlow<LocationFix?>(
            initial?.let { LocationFix(point = it, timeMs = 0, accuracyM = null, provider = ProviderType.MANUAL) }
        )
        override val currentFix: Flow<LocationFix?> = _currentFix

        override val fixes: Flow<LocationFix> = _fixes

        override suspend fun start(config: LocationConfig) = Unit

        override suspend fun stop() = Unit

        override suspend fun getLastKnown(): LocationFix? = lastFix

        override suspend fun setManual(point: GeoPoint?) {
            val fix = point?.let { LocationFix(it, timeMs = 0, accuracyM = null, provider = ProviderType.MANUAL) }
            lastFix = fix
            _currentFix.value = fix
        }

        override suspend fun preferPhoneFallback(enabled: Boolean) = Unit

        suspend fun emit(fix: LocationFix) {
            lastFix = fix
            _currentFix.value = fix
            _fixes.emit(fix)
        }
    }

    private class FakeTimeSource(
        private var instant: Instant,
    ) : TimeSource {
        override fun now(): Instant = instant

        override val ticks: Flow<Instant> = MutableSharedFlow(extraBufferCapacity = 1)

        fun update(instant: Instant) {
            this.instant = instant
        }
    }

    private class FakeEphemerisComputer : EphemerisComputer {
        override fun compute(
            body: Body,
            instant: Instant,
        ): Ephemeris = Ephemeris(eq = Equatorial(0.0, 0.0))
    }
}
