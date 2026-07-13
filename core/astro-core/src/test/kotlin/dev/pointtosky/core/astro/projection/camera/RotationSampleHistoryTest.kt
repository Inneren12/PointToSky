package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [RotationSampleHistory] (CAM-1d): ordering, capacity eviction, nearest-sample
 * lookup, duplicate-timestamp handling, and defensive matrix copying.
 */
class RotationSampleHistoryTest {
    private fun sample(
        timestampNanos: Long,
        fill: Float = timestampNanos.toFloat(),
    ) = TimedRotationSample(timestampNanos = timestampNanos, rotationMatrix = FloatArray(9) { fill })

    @Test
    fun `empty history returns null nearest and empty snapshot`() {
        val history = RotationSampleHistory(capacity = 10)

        assertNull(history.nearest(0L))
        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun `one sample is returned as nearest regardless of query timestamp`() {
        val history = RotationSampleHistory(capacity = 10)
        history.add(sample(100L))

        assertEquals(100L, history.nearest(0L)?.timestampNanos)
        assertEquals(100L, history.nearest(100L)?.timestampNanos)
        assertEquals(100L, history.nearest(1_000_000L)?.timestampNanos)
    }

    @Test
    fun `ordered insertion keeps snapshot sorted ascending`() {
        val history = RotationSampleHistory(capacity = 10)
        listOf(10L, 20L, 30L, 40L).forEach { history.add(sample(it)) }

        assertEquals(listOf(10L, 20L, 30L, 40L), history.snapshot().map { it.timestampNanos })
    }

    @Test
    fun `out-of-order insertion still yields a snapshot sorted ascending`() {
        val history = RotationSampleHistory(capacity = 10)
        listOf(30L, 10L, 40L, 20L).forEach { history.add(sample(it)) }

        assertEquals(listOf(10L, 20L, 30L, 40L), history.snapshot().map { it.timestampNanos })
    }

    @Test
    fun `capacity eviction keeps only the most recent samples by timestamp`() {
        val history = RotationSampleHistory(capacity = 3)
        (0 until 5).forEach { history.add(sample(it.toLong())) }

        assertEquals(listOf(2L, 3L, 4L), history.snapshot().map { it.timestampNanos })
    }

    @Test
    fun `duplicate timestamps are preserved, not deduplicated`() {
        val history = RotationSampleHistory(capacity = 10)
        history.add(sample(50L, fill = 1f))
        history.add(sample(50L, fill = 2f))

        val snapshot = history.snapshot()
        assertEquals(2, snapshot.size)
        assertTrue(snapshot.all { it.timestampNanos == 50L })
    }

    @Test
    fun `nearest at an exact duplicate timestamp resolves to the most recently added one`() {
        val history = RotationSampleHistory(capacity = 10)
        history.add(sample(50L, fill = 1f))
        history.add(sample(50L, fill = 2f))

        val nearest = history.nearest(50L)
        assertNotNull(nearest)
        assertEquals(2f, nearest.rotationMatrix[0])
    }

    @Test
    fun `nearest prefers whichever neighbor is closer`() {
        val history = RotationSampleHistory(capacity = 10)
        history.add(sample(100L))
        history.add(sample(200L))

        assertEquals(100L, history.nearest(120L)?.timestampNanos)
        assertEquals(200L, history.nearest(180L)?.timestampNanos)
    }

    @Test
    fun `nearest breaks equidistant ties by preferring the earlier sample`() {
        val history = RotationSampleHistory(capacity = 10)
        history.add(sample(100L))
        history.add(sample(200L))

        assertEquals(100L, history.nearest(150L)?.timestampNanos)
    }

    @Test
    fun `clear discards all retained samples`() {
        val history = RotationSampleHistory(capacity = 10)
        history.add(sample(100L))

        history.clear()

        assertNull(history.nearest(100L))
        assertTrue(history.snapshot().isEmpty())
    }

    @Test
    fun `add defensively copies the caller's matrix array`() {
        val history = RotationSampleHistory(capacity = 10)
        val original = FloatArray(9) { 1f }
        history.add(TimedRotationSample(timestampNanos = 10L, rotationMatrix = original))

        original[0] = 999f

        assertEquals(1f, history.nearest(10L)?.rotationMatrix?.get(0))
    }

    @Test
    fun `nearest and snapshot return independent copies that cannot corrupt history state`() {
        val history = RotationSampleHistory(capacity = 10)
        history.add(sample(10L, fill = 1f))

        val fromNearest = history.nearest(10L)
        assertNotNull(fromNearest)
        fromNearest.rotationMatrix[0] = 999f

        val fromSnapshot = history.snapshot().single()
        fromSnapshot.rotationMatrix[0] = 888f

        assertEquals(1f, history.nearest(10L)?.rotationMatrix?.get(0))
    }

    @Test
    fun `capacity must be positive`() {
        assertFailsWith<IllegalArgumentException> { RotationSampleHistory(capacity = 0) }
    }
}
