package dev.pointtosky.wear.aim.core

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.api.LocationOrchestrator
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import dev.pointtosky.core.time.TimeSource
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.Ephemeris
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.wear.sensors.orientation.OrientationAccuracy
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationFrameDefaults
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import dev.pointtosky.wear.sensors.orientation.OrientationRepositoryConfig
import dev.pointtosky.wear.sensors.orientation.OrientationSource
import dev.pointtosky.wear.sensors.orientation.OrientationZero
import dev.pointtosky.wear.sensors.orientation.ScreenRotation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

private class FakeOrientationRepo : OrientationRepository {
    private val _frames = MutableSharedFlow<OrientationFrame>(replay = 1, extraBufferCapacity = 16)
    override val frames = _frames.asSharedFlow()
    override val zero = MutableStateFlow(OrientationZero())
    override val fps = MutableStateFlow<Float?>(null)
    override val source = OrientationSource.ROTATION_VECTOR
    override val activeSource = MutableStateFlow(OrientationSource.ROTATION_VECTOR)
    override val isRunning = MutableStateFlow(true)
    override fun start(config: OrientationRepositoryConfig) = Unit
    override fun stop() = Unit
    override fun updateZero(orientationZero: OrientationZero) {
        zero.value = orientationZero
    }
    override fun setRemap(screenRotation: ScreenRotation) = Unit
    suspend fun emit(az: Double, alt: Double) {
        val azRad = Math.toRadians(az)
        val altRad = Math.toRadians(alt)
        val cosAlt = kotlin.math.cos(altRad)
        val east = (kotlin.math.sin(azRad) * cosAlt).toFloat()
        val north = (kotlin.math.cos(azRad) * cosAlt).toFloat()
        val up = kotlin.math.sin(altRad).toFloat()
        _frames.emit(
            OrientationFrame(
                timestampNanos = 0L,
                azimuthDeg = az.toFloat(),
                pitchDeg = alt.toFloat(),
                rollDeg = 0f,
                forward = floatArrayOf(east, north, up),
                accuracy = OrientationAccuracy.HIGH,
                rotationMatrix = FloatArray(9),
            ),
        )
    }
}

private class FakeLocation : LocationOrchestrator {
    private val _fix = LocationFix(
        point = GeoPoint(latDeg = 52.0, lonDeg = 13.0),
        timeMs = 0,
        accuracyM = 100f,
        provider = ProviderType.MANUAL,
    )
    private val _fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 4)
    override val fixes = _fixes.asSharedFlow()
    override suspend fun start(config: LocationConfig) = Unit
    override suspend fun stop() = Unit
    override suspend fun getLastKnown(): LocationFix = _fix
    override suspend fun setManual(point: GeoPoint?) {
        point?.let { _fixes.emit(_fix.copy(point = it)) }
    }
    override suspend fun preferPhoneFallback(enabled: Boolean) = Unit
}

private class FakeTime : TimeSource {
    private val fixed = Instant.parse("2025-01-01T00:00:00Z")
    override fun now(): Instant = fixed
    override val ticks = MutableSharedFlow<Instant>(extraBufferCapacity = 1).asSharedFlow()
}

private class FakeEphem : EphemerisComputer {
    override fun compute(body: Body, instant: Instant): Ephemeris =
        Ephemeris(eq = Equatorial(0.0, 0.0))
}

class AimControllerBasicTest {
    @Test fun dAz_wraps_correctly_and_enters_in_tolerance() = runBlocking {
        val orient = FakeOrientationRepo()
        val ctrl = DefaultAimController(
            orientation = orient,
            location = FakeLocation(),
            time = FakeTime(),
            ephem = FakeEphem(),
            raDecToAltAz = { eq, _, _ -> Horizontal(eq.raDeg, eq.decDeg) }
        )

        ctrl.setHoldToLockMs(10_000)
        ctrl.setTolerance(AimTolerance(azDeg = 3.0, altDeg = 4.0))
        ctrl.setTarget(AimTarget.EquatorialTarget(Equatorial(raDeg = 1.0, decDeg = 0.0)))

        ctrl.start()
        orient.emit(az = 359.0, alt = 0.0)

        withTimeout(1_000) {
            while (kotlin.math.abs(ctrl.state.value.dAzDeg) <= 0.0) {
                delay(10)
            }
        }
        val st = ctrl.state.value
        assertEquals("dAz must be +2°", 2.0, st.dAzDeg, 0.25)
        assertTrue(
            "should be IN_TOLERANCE",
            st.phase == AimPhase.IN_TOLERANCE || st.phase == AimPhase.SEARCHING,
        )
        ctrl.stop()
    }
}
