package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SKY-1 (`internalDebug`-only): the exposure store's association contract. `CaptureResult` and
 * `ImageProxy` arrive on different threads with no guaranteed ordering, so "the latest exposure"
 * would silently attribute one frame's shutter to another frame's pixels — these tests pin the
 * keyed-by-`SENSOR_TIMESTAMP` behaviour that prevents it.
 */
class SkyExposureSampleStoreTest {
    private fun sample(
        sensorTimestampNanos: Long?,
        exposureTimeNanos: Long = 500_000_000L,
    ) = SkyExposureSample(
        exposureTimeNanos = exposureTimeNanos,
        sensitivityIso = 1600,
        sensorTimestampNanos = sensorTimestampNanos,
    )

    @Test
    fun `a sample is returned for exactly the frame timestamp it was keyed by`() {
        val store = SkyExposureSampleStore()
        store.record(sample(sensorTimestampNanos = 1_000L))

        assertEquals(500_000_000L, assertNotNull(store.takeFor(1_000L)).exposureTimeNanos)
    }

    @Test
    fun `a frame with no matching result gets no exposure rather than a neighbour's`() {
        val store = SkyExposureSampleStore()
        store.record(sample(sensorTimestampNanos = 1_000L, exposureTimeNanos = 111L))
        store.record(sample(sensorTimestampNanos = 2_000L, exposureTimeNanos = 222L))

        assertNull(store.takeFor(1_500L), "an unmatched frame must record no exposure, never the nearest one")
    }

    @Test
    fun `reading does not consume the entry`() {
        val store = SkyExposureSampleStore()
        store.record(sample(sensorTimestampNanos = 7L))

        assertNotNull(store.takeFor(7L))
        assertNotNull(store.takeFor(7L))
    }

    @Test
    fun `a result with no sensor timestamp is counted rather than stored under a guessed key`() {
        val store = SkyExposureSampleStore()

        store.record(sample(sensorTimestampNanos = null))

        assertEquals(1L, store.unkeyedResultCount)
        assertNull(store.takeFor(0L))
    }

    @Test
    fun `the ring evicts oldest first and stays bounded`() {
        val store = SkyExposureSampleStore(capacity = 4)
        (1L..6L).forEach { store.record(sample(sensorTimestampNanos = it, exposureTimeNanos = it * 1_000L)) }

        assertNull(store.takeFor(1L), "the two oldest entries must have been evicted")
        assertNull(store.takeFor(2L))
        assertEquals(3_000L, assertNotNull(store.takeFor(3L)).exposureTimeNanos)
        assertEquals(6_000L, assertNotNull(store.takeFor(6L)).exposureTimeNanos)
    }

    @Test
    fun `a non-positive capacity is rejected`() {
        assertFailsWith<IllegalArgumentException> { SkyExposureSampleStore(capacity = 0) }
    }

    @Test
    fun `manual exposure requests are clamped into the device's advertised ranges`() {
        val capability =
            SkyManualExposureCapability(
                supported = true,
                exposureTimeRangeNanos = 100_000L..250_000_000L,
                sensitivityRange = 50..3200,
            )

        val clamped =
            assertNotNull(
                capability.clamp(SkyManualExposureRequest(exposureTimeNanos = 2_000_000_000L, sensitivityIso = 12800)),
            )

        assertEquals(250_000_000L, clamped.exposureTimeNanos)
        assertEquals(3200, clamped.sensitivityIso)
    }

    @Test
    fun `an unsupported camera clamps to nothing rather than to an auto-exposed request`() {
        val capability =
            SkyManualExposureCapability(supported = false, unsupportedReason = "MANUAL_SENSOR_CAPABILITY_ABSENT")

        assertNull(capability.clamp(DEFAULT_SKY_EXPOSURE))
    }

    @Test
    fun `a request within range is left alone`() {
        val capability =
            SkyManualExposureCapability(
                supported = true,
                exposureTimeRangeNanos = 100_000L..4_000_000_000L,
                sensitivityRange = 50..12800,
            )

        assertEquals(DEFAULT_SKY_EXPOSURE, capability.clamp(DEFAULT_SKY_EXPOSURE))
    }

    @Test
    fun `a non-positive exposure request is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            SkyManualExposureRequest(
                exposureTimeNanos = 0L,
                sensitivityIso = 100,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SkyManualExposureRequest(
                exposureTimeNanos = 1_000L,
                sensitivityIso = 0,
            )
        }
    }
}
