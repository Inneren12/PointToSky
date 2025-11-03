package dev.pointtosky.core.location.fake

import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

class FakeLocationRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val basePoint: GeoPoint = GeoPoint(55.751244, 37.618423),
    private val amplitudeMeters: Double = 250.0,
    private val orbitPeriodMs: Long = 60_000L,
) : LocationRepository {

    private val latest = MutableStateFlow<LocationFix?>(null)
    override val fixes: Flow<LocationFix> = latest.filterNotNull()

    private var job: Job? = null

    override suspend fun start(config: LocationConfig) {
        if (job?.isActive == true) return

        val interval = maxOf(500L, config.minUpdateIntervalMs)
        job = scope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                val now = System.currentTimeMillis()
                val elapsed = now - startTime
                val fix = createFix(now, elapsed)
                latest.value = fix
                delay(interval)
            }
        }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
    }

    override suspend fun getLastKnown(): LocationFix? = latest.value

    private fun createFix(timeMs: Long, elapsedMs: Long): LocationFix {
        val angle = (elapsedMs % orbitPeriodMs).toDouble() / orbitPeriodMs * TWO_PI
        val latOffset = sin(angle) * metersToLatDegrees(amplitudeMeters)
        val lonOffset = cos(angle) * metersToLonDegrees(amplitudeMeters, basePoint.latDeg)

        val point = GeoPoint(
            latDeg = basePoint.latDeg + latOffset,
            lonDeg = basePoint.lonDeg + lonOffset,
        )

        val bearingDeg = ((angle * 180.0 / PI + 360.0) % 360.0).toFloat()
        val speedMps = ((TWO_PI * amplitudeMeters) / orbitPeriodMs) * 1000.0

        return LocationFix(
            point = point,
            timeMs = timeMs,
            accuracyM = 15f,
            provider = ProviderType.FUSED,
            altitudeM = 200.0,
            bearingDeg = bearingDeg,
            speedMps = speedMps.toFloat(),
        )
    }

    private fun metersToLatDegrees(meters: Double): Double = meters / METERS_PER_DEGREE_LAT

    private fun metersToLonDegrees(meters: Double, latitudeDeg: Double): Double {
        val latRad = Math.toRadians(latitudeDeg)
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(latRad).coerceAtLeast(1e-6)
        return meters / metersPerDegreeLon
    }

    private companion object {
        private const val METERS_PER_DEGREE_LAT = 111_320.0
        private const val TWO_PI = 2.0 * Math.PI
    }
}
