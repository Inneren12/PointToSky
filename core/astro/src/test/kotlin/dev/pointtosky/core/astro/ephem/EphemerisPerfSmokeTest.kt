package dev.pointtosky.core.astro.ephem

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class EphemerisPerfSmokeTest {

    private val computer = SimpleEphemerisComputer()

    @Test
    fun `average computation time stays within soft budget`() {
        val iterationsPerBody = 10_000
        val bodies = Body.entries
        val step = Duration.ofMillis(8_640)
        val stepNanos = step.toNanos()
        val startInstant = Instant.parse("2025-01-01T00:00:00Z")

        var checksum = 0.0
        val totalDurationNs = bodies.sumOf { body ->
            var instant = startInstant
            val bodyStart = System.nanoTime()
            repeat(iterationsPerBody) {
                val ephemeris = computer.compute(body, instant)
                checksum += ephemeris.eq.raDeg
                instant = instant.plusNanos(stepNanos)
            }
            System.nanoTime() - bodyStart
        }

        val totalCalls = iterationsPerBody.toLong() * bodies.size
        val averageNsPerCall = totalDurationNs.toDouble() / totalCalls.toDouble()
        val averageMicrosPerCall = averageNsPerCall / 1_000.0
        val softBudgetMicros = 75.0 // 50 µs target with additional headroom for CI noise.

        assertTrue(
            averageMicrosPerCall <= softBudgetMicros,
            "Expected average ephemeris compute time <= 50 µs (soft limit $softBudgetMicros µs), was $averageMicrosPerCall µs",
        )
        assertFalse(checksum.isNaN())
    }
}
