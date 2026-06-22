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

        override val fixes: Flow<LocationFix> = _fixes

        override suspend fun start(config: LocationConfig) = Unit

        override suspend fun stop() = Unit

        override suspend fun getLastKnown(): LocationFix? = lastFix

        override suspend fun setManual(point: GeoPoint?) {
            lastFix = point?.let { LocationFix(it, timeMs = 0, accuracyM = null, provider = ProviderType.MANUAL) }
        }

        override suspend fun preferPhoneFallback(enabled: Boolean) = Unit

        suspend fun emit(fix: LocationFix) {
            lastFix = fix
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
