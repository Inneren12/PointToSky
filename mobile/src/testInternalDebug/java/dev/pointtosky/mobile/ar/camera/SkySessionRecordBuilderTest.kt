package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.prediction.IntrinsicsMappingUnavailableReason
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.prediction.projectStars
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference
import dev.pointtosky.core.astro.projection.camera.skylog.toCameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.skylog.toStarProjectionContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKY-1 (`internalDebug`-only) pure tests for [buildSkyFrameRecord] — the assembly step between the
 * runtime pieces one analyzed frame produces and the pure log record written to disk.
 */
class SkySessionRecordBuilderTest {
    private val fixtures = SkySessionCaptureFixtures

    private fun lumaReference(): SkyLumaReference =
        SkyLumaReference(
            path = "frames/frame_000000.y",
            format = SkyLumaFormat.RAW_Y8,
            widthPx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
            heightPx = SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
            rowStridePx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
            byteLength = SkySessionCaptureFixtures.BUFFER_WIDTH_PX.toLong() * SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
        )

    @Test
    fun `the record carries the frame, viewport and pose from the geometry it was built for`() {
        val geometry = fixtures.geometry()

        val record =
            buildSkyFrameRecord(
                sequence = 5L,
                capturedAtEpochMillis = 1_767_225_600_000L,
                geometry = geometry,
                luma = lumaReference(),
                observer = fixtures.observer(),
                exposure = fixtures.exposureSample(),
                stars = emptyList(),
                prediction = StarPredictionBatchResult.Ready.of(emptyList()),
            )

        assertEquals(5L, record.sequence)
        assertEquals(geometry.frame, record.frame)
        assertEquals(SkySessionCaptureFixtures.VIEWPORT_WIDTH_PX, record.viewportWidthPx)
        assertEquals(SkySessionCaptureFixtures.VIEWPORT_HEIGHT_PX, record.viewportHeightPx)
        assertEquals(geometry.pairedRotation.timestampNanos, record.pose.timestampNanos)
        assertEquals(9, record.pose.rotationMatrix.size)
    }

    @Test
    fun `predicted stars pair each projection with the celestial coordinates it came from`() {
        val geometry = fixtures.geometry()
        val stars = fixtures.starDirections()
        val context = assertNotNull(fixtures.observer().toStarProjectionContext())
        val prediction = assertIs<StarPredictionBatchResult.Ready>(projectStars(stars, context, geometry))

        val record =
            buildSkyFrameRecord(
                sequence = 0L,
                capturedAtEpochMillis = 0L,
                geometry = geometry,
                luma = lumaReference(),
                observer = fixtures.observer(),
                exposure = null,
                stars = stars,
                prediction = prediction,
            )

        assertEquals(stars.size, record.predictedStars.size)
        record.predictedStars.forEachIndexed { index, star ->
            assertEquals(stars[index].catalogIndex, star.catalogIndex)
            assertEquals(stars[index].rightAscensionRad, star.rightAscensionRad)
            assertEquals(stars[index].declinationRad, star.declinationRad)
            assertEquals(stars[index].magnitude, star.magnitude)
            assertEquals(prediction.projections[index].classification, star.classification)
            assertEquals(prediction.projections[index].imagePoint?.x, star.imageXPx)
            assertEquals(prediction.projections[index].imagePoint?.y, star.imageYPx)
            assertEquals(prediction.projections[index].displayPoint?.x, star.displayXPx)
            assertEquals(prediction.projections[index].displayPoint?.y, star.displayYPx)
        }
    }

    @Test
    fun `a star behind the camera records no pixel coordinates rather than fabricated ones`() {
        val geometry = fixtures.geometry()
        val stars = fixtures.starDirections()
        val context = assertNotNull(fixtures.observer().toStarProjectionContext())
        val prediction = assertIs<StarPredictionBatchResult.Ready>(projectStars(stars, context, geometry))

        val record =
            buildSkyFrameRecord(
                sequence = 0L,
                capturedAtEpochMillis = 0L,
                geometry = geometry,
                luma = lumaReference(),
                observer = fixtures.observer(),
                exposure = null,
                stars = stars,
                prediction = prediction,
            )

        record.predictedStars
            .filter {
                it.classification == PredictedStarClassification.BEHIND_CAMERA
            }.forEach { star ->
                assertNull(star.imageXPx)
                assertNull(star.imageYPx)
                assertNull(star.displayXPx)
                assertNull(star.displayYPx)
            }
    }

    @Test
    fun `a refused prediction batch records the frame with no stars rather than dropping it`() {
        val record =
            buildSkyFrameRecord(
                sequence = 0L,
                capturedAtEpochMillis = 0L,
                geometry = fixtures.geometry(),
                luma = lumaReference(),
                observer = fixtures.observer(),
                exposure = fixtures.exposureSample(),
                stars = fixtures.starDirections(),
                prediction =
                    StarPredictionBatchResult.IntrinsicsMappingUnavailable(
                        IntrinsicsMappingUnavailableReason.ANALYSIS_BUFFER_DIMENSIONS_MISMATCH,
                    ),
            )

        assertEquals(emptyList(), record.predictedStars)
        assertNotNull(record.exposure, "the pixels, pose and exposure are still worth keeping without a prediction")
    }

    @Test
    fun `a batch whose size disagrees with its input records no stars rather than mispairing them`() {
        val geometry = fixtures.geometry()
        val stars = fixtures.starDirections()
        val context = assertNotNull(fixtures.observer().toStarProjectionContext())
        val fullBatch = assertIs<StarPredictionBatchResult.Ready>(projectStars(stars, context, geometry))
        val truncated = StarPredictionBatchResult.Ready.of(fullBatch.projections.dropLast(1))

        val record =
            buildSkyFrameRecord(
                sequence = 0L,
                capturedAtEpochMillis = 0L,
                geometry = geometry,
                luma = lumaReference(),
                observer = fixtures.observer(),
                exposure = null,
                stars = stars,
                prediction = truncated,
            )

        assertEquals(emptyList(), record.predictedStars)
    }

    @Test
    fun `the pinhole record is derived from the geometry the session actually projects with`() {
        val geometry = fixtures.geometry()

        val pinhole = assertNotNull(skyPinholeRecordOrNull(geometry))

        assertTrue(pinhole.fxPx > 0.0 && pinhole.fyPx > 0.0)
        assertEquals(SkySessionCaptureFixtures.BUFFER_WIDTH_PX / 2.0, pinhole.cxPx, absoluteTolerance = 1e-9)
        assertEquals(SkySessionCaptureFixtures.BUFFER_HEIGHT_PX / 2.0, pinhole.cyPx, absoluteTolerance = 1e-9)
    }

    @Test
    fun `the session header flattens the intrinsics the session resolved`() {
        val header =
            buildSkySessionHeader(
                sessionId = "sky_header_test",
                startedAtEpochMillis = 1L,
                bufferWidthPx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
                bufferHeightPx = SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
                intrinsics = fixtures.intrinsics(),
                maxPairDeltaNanos = 25_000_000L,
                clockMismatchThresholdNanos = 5_000_000_000L,
                deviceModel = "Test Device",
                cameraId = "3",
                physicalCameraIds = listOf("2", "3"),
                calibration = null,
                pinhole = skyPinholeRecordOrNull(fixtures.geometry()),
                notes = null,
            )

        assertEquals(66.0, header.intrinsics.horizontalFovDeg)
        assertEquals(SkySessionCaptureFixtures.BUFFER_WIDTH_PX, header.intrinsics.referenceWidthPx)
        assertEquals(listOf("2", "3"), header.physicalCameraIds)
        assertEquals(fixtures.intrinsics(), header.intrinsics.toCameraIntrinsicsResolution())
        assertNotNull(header.intrinsics.pinhole)
    }
}
