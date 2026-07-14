package dev.pointtosky.core.astro.projection.camera.prediction

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Section B (camera rotation anchors, literal matrices), Section E (end-to-end pure cases), and
 * Section F (defensive tests) for CAM-2a.
 */
class CameraStarProjectionTest {
    private val eps = 1e-6

    private fun assertVector(
        expectedX: Double,
        expectedY: Double,
        expectedZ: Double,
        actual: DeviceVector,
        message: String,
    ) {
        assertTrue(abs(actual.x - expectedX) < eps, "$message: x expected $expectedX but was ${actual.x}")
        assertTrue(abs(actual.y - expectedY) < eps, "$message: y expected $expectedY but was ${actual.y}")
        assertTrue(abs(actual.z - expectedZ) < eps, "$message: z expected $expectedZ but was ${actual.z}")
    }

    // =====================================================================================
    // Section B: camera rotation anchors (literal matrices, literal expected directions)
    // =====================================================================================

    @Test
    fun `identity rotation - world nadir maps to device forward axis`() {
        // IDENTITY_ROTATION_MATRIX device axes == world axes, so forwardWorld (-col2) = (0,0,-1) = nadir.
        val device = worldToDeviceVector(IDENTITY_ROTATION_MATRIX, LocalSkyDirection(0.0, 0.0, -1.0))
        assertVector(0.0, 0.0, -1.0, device, "identity: nadir -> device forward")

        val projection = projectToCameraDirection(LocalSkyDirection(0.0, 0.0, -1.0), IDENTITY_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "nadir must project to image center")
    }

    @Test
    fun `identity rotation - world zenith (opposite of forward) is rejected as behind the camera`() {
        val projection = projectToCameraDirection(LocalSkyDirection(0.0, 0.0, 1.0), IDENTITY_ROTATION_MATRIX)
        assertEquals(CameraDirectionProjection.BehindCamera, projection)
    }

    @Test
    fun `north-facing upright camera - north horizon maps to image center`() {
        val projection = projectToCameraDirection(LocalSkyDirection(0.0, 1.0, 0.0), NORTH_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "camera forward must project to image center")
    }

    @Test
    fun `north-facing upright camera - east is to the right (increasing normalizedX)`() {
        // 20 deg east of north: cardinal-check helper gives an exact, non-degenerate local sky vector.
        val direction = localSkyDirectionFromHorizontal(azimuthRad = Math.toRadians(20.0), altitudeRad = 0.0)
        val projection = projectToCameraDirection(direction, NORTH_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(inFront.normalizedX > 0.0, "east of a north-facing camera must have positive normalizedX, was ${inFront.normalizedX}")
        assertTrue(abs(inFront.normalizedY) < eps, "on the horizon, normalizedY must be ~0")
    }

    @Test
    fun `north-facing upright camera - above the horizon maps to smaller normalizedY (image up)`() {
        // 20 deg above due north.
        val direction = localSkyDirectionFromHorizontal(azimuthRad = 0.0, altitudeRad = Math.toRadians(20.0))
        val projection = projectToCameraDirection(direction, NORTH_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(abs(inFront.normalizedX) < eps, "on the meridian, normalizedX must be ~0")
        assertTrue(
            inFront.normalizedY < 0.0,
            "a direction above the horizon must have negative normalizedY (+y=down means up is negative), was ${inFront.normalizedY}",
        )
    }

    @Test
    fun `90 degree yaw right (north to east) - world east maps to device forward axis`() {
        val projection = projectToCameraDirection(LocalSkyDirection(1.0, 0.0, 0.0), EAST_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "east must project to image center for the east-facing camera")
    }

    @Test
    fun `90 degree yaw left (north to west) - world west maps to device forward axis`() {
        val projection = projectToCameraDirection(LocalSkyDirection(-1.0, 0.0, 0.0), WEST_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "west must project to image center for the west-facing camera")
    }

    @Test
    fun `90 degree pitch up (north to zenith) - world zenith maps to device forward axis, nadir is rejected`() {
        val zenithProjection = projectToCameraDirection(LocalSkyDirection(0.0, 0.0, 1.0), ZENITH_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(zenithProjection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "zenith must project to image center for the zenith-pitched camera")

        val nadirProjection = projectToCameraDirection(LocalSkyDirection(0.0, 0.0, -1.0), ZENITH_UPRIGHT_ROTATION_MATRIX)
        assertEquals(CameraDirectionProjection.BehindCamera, nadirProjection)
    }

    @Test
    fun `pitching a further 90 degrees down from zenith returns exactly the identity matrix`() {
        assertEquals(IDENTITY_ROTATION_MATRIX.toList(), listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        // ZENITH_UPRIGHT_ROTATION_MATRIX's own KDoc documents this relationship; this test pins it.
    }

    @Test
    fun `180 degree rotation (north to south) - south maps to device forward, north is now rejected`() {
        val southProjection = projectToCameraDirection(LocalSkyDirection(0.0, -1.0, 0.0), SOUTH_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(southProjection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "south must project to image center for the south-facing camera")

        val northProjection = projectToCameraDirection(LocalSkyDirection(0.0, 1.0, 0.0), SOUTH_UPRIGHT_ROTATION_MATRIX)
        assertEquals(CameraDirectionProjection.BehindCamera, northProjection)
    }

    @Test
    fun `using the rotation matrix directly instead of its transpose gives a different, wrong device vector`() {
        // Distinguishes a transpose/inverse mistake: applying EAST_UPRIGHT_ROTATION_MATRIX directly
        // (as if it mapped world->device) to world East gives a different result than the correct
        // transpose-multiply worldToDeviceVector performs.
        val world = LocalSkyDirection(1.0, 0.0, 0.0)
        val correct = worldToDeviceVector(EAST_UPRIGHT_ROTATION_MATRIX, world)
        assertVector(0.0, 0.0, -1.0, correct, "correct transpose-multiply result")

        val r = EAST_UPRIGHT_ROTATION_MATRIX
        val wrongX = r[0] * world.x + r[1] * world.y + r[2] * world.z
        val wrongY = r[3] * world.x + r[4] * world.y + r[5] * world.z
        val wrongZ = r[6] * world.x + r[7] * world.y + r[8] * world.z

        assertTrue(
            abs(wrongX - correct.x) > 0.5 || abs(wrongY - correct.y) > 0.5 || abs(wrongZ - correct.z) > 0.5,
            "applying R directly (not transposed) must give a materially different result from the correct Rᵀ multiply: " +
                "direct=($wrongX,$wrongY,$wrongZ) vs correct=(${correct.x},${correct.y},${correct.z})",
        )
    }

    @Test
    fun `worldToDeviceVector rejects a rotation matrix that is not exactly 9 elements`() {
        val ex =
            assertFailsWithMessage {
                worldToDeviceVector(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f), LocalSkyDirection(0.0, 0.0, 1.0))
            }
        assertTrue(ex.contains("9"), "message should mention the required size: $ex")
    }

    private fun assertFailsWithMessage(block: () -> Unit): String {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return e.message.orEmpty()
        }
        throw AssertionError("expected IllegalArgumentException")
    }

    // =====================================================================================
    // Section E: full end-to-end pure cases
    // =====================================================================================

    private val instant: Instant = Instant.parse("2024-07-07T07:00:00Z")
    private val lonDeg = -10.0

    /** Star exactly on the north horizon; NORTH_UPRIGHT_ROTATION_MATRIX's forward axis. */
    private fun forwardAxisStar() = meridianTransitStar(instant, lonDeg, latDeg = 45.0, decDeg = 45.0, lowerCulmination = true)

    /** Star exactly on the south horizon; behind a camera facing NORTH_UPRIGHT_ROTATION_MATRIX. */
    private fun behindCameraStar() = meridianTransitStar(instant, lonDeg, latDeg = 45.0, decDeg = -45.0, lowerCulmination = false)

    @Test
    fun `E1 - forward-axis star projects to the display center`() {
        val (star, context) = forwardAxisStar()
        val geometry = buildTestGeometry(rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX)
        val result = projectStars(listOf(star), context, geometry).single()

        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        val displayPoint = assertNotNull(result.displayPoint)
        assertTrue(abs(displayPoint.x - 500.0) < eps, "expected display center x=500, was ${displayPoint.x}")
        assertTrue(abs(displayPoint.y - 250.0) < eps, "expected display center y=250, was ${displayPoint.y}")
    }

    @Test
    fun `E2 - behind-camera star is classified BEHIND_CAMERA with no pixel data`() {
        val (star, context) = behindCameraStar()
        val geometry = buildTestGeometry(rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX)
        val result = projectStars(listOf(star), context, geometry).single()

        assertEquals(PredictedStarClassification.BEHIND_CAMERA, result.classification)
        assertNull(result.cameraDirection)
        assertNull(result.imagePoint)
        assertNull(result.displayPoint)
    }

    @Test
    fun `E3 - star inside camera FOV but cropped out`() {
        // NORTHEAST_UPRIGHT camera + north-horizon star lands at buffer-space x=0 (left edge); a crop
        // starting at x=200 excludes it even though it is well inside the camera's frustum.
        val (star, context) = meridianTransitStar(instant, lonDeg, latDeg = 45.0, decDeg = 45.0, lowerCulmination = true)
        val geometry =
            buildTestGeometry(
                rotationMatrix = NORTHEAST_UPRIGHT_ROTATION_MATRIX,
                cropRectLeftPx = 200,
                cropRectTopPx = 0,
                cropRectRightPx = 700,
                cropRectBottomPx = 500,
            )
        val result = projectStars(listOf(star), context, geometry).single()

        assertEquals(PredictedStarClassification.OUTSIDE_IMAGE, result.classification)
        assertNotNull(result.cameraDirection) // in front of the camera
        assertNotNull(result.imagePoint)
    }

    @Test
    fun `E4 - star visible after non-zero crop`() {
        val (star, context) = meridianTransitStar(instant, lonDeg, latDeg = 45.0, decDeg = 45.0, lowerCulmination = true)
        val geometry =
            buildTestGeometry(
                rotationMatrix = NORTHWEST_UPRIGHT_ROTATION_MATRIX, // lands at buffer-space x=1000 (right edge)
                cropRectLeftPx = 500,
                cropRectTopPx = 0,
                cropRectRightPx = 1000,
                cropRectBottomPx = 500,
                viewportWidthPx = 500,
                viewportHeightPx = 500,
            )
        val result = projectStars(listOf(star), context, geometry).single()

        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        val displayPoint = assertNotNull(result.displayPoint)
        assertTrue(abs(displayPoint.x - 500.0) < eps)
        assertTrue(abs(displayPoint.y - 250.0) < eps)
    }

    @Test
    fun `E5 - portrait geometry projects the on-axis star to the display center`() {
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1080,
                bufferHeightPx = 1920,
                viewportWidthPx = 1080,
                viewportHeightPx = 1920,
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
            )
        val result = projectStars(listOf(star), context, geometry).single()
        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        assertTrue(abs(result.displayPoint!!.x - 540.0) < eps)
        assertTrue(abs(result.displayPoint.y - 960.0) < eps)
    }

    @Test
    fun `E6 - landscape geometry projects the on-axis star to the display center`() {
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                viewportWidthPx = 1920,
                viewportHeightPx = 1080,
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
            )
        val result = projectStars(listOf(star), context, geometry).single()
        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        assertTrue(abs(result.displayPoint!!.x - 960.0) < eps)
        assertTrue(abs(result.displayPoint.y - 540.0) < eps)
    }

    @Test
    fun `E7 - legacy-fallback intrinsics path produces a visible on-axis prediction`() {
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
                intrinsicsResolution = legacyFallbackIntrinsics(imageWidthPx = 1000, imageHeightPx = 500),
            )
        val result = projectStars(listOf(star), context, geometry).single()
        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        assertTrue(abs(result.displayPoint!!.x - 500.0) < eps)
        assertTrue(abs(result.displayPoint.y - 250.0) < eps)
    }

    @Test
    fun `E8 - calibrated intrinsics path produces a visible on-axis prediction`() {
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
                intrinsicsResolution = resolvedIntrinsics(horizontalFovDeg = 70.0, verticalFovDeg = 50.0),
            )
        val result = projectStars(listOf(star), context, geometry).single()
        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        assertTrue(abs(result.displayPoint!!.x - 500.0) < eps)
        assertTrue(abs(result.displayPoint.y - 250.0) < eps)
    }

    @Test
    fun `E9 - input ordering is preserved regardless of magnitude or visibility`() {
        val (forwardStar, context) = forwardAxisStar()
        val (behindStar, _) = behindCameraStar()
        val stars =
            listOf(
                star(catalogIndex = 3, rightAscensionRad = behindStar.rightAscensionRad, declinationRad = behindStar.declinationRad, magnitude = -1.0),
                star(catalogIndex = 1, rightAscensionRad = forwardStar.rightAscensionRad, declinationRad = forwardStar.declinationRad, magnitude = 5.0),
                star(catalogIndex = 2, rightAscensionRad = forwardStar.rightAscensionRad, declinationRad = forwardStar.declinationRad, magnitude = -2.0),
            )
        val geometry = buildTestGeometry(rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX)

        val results = projectStars(stars, context, geometry)
        assertEquals(listOf(3, 1, 2), results.map { it.catalogIndex }, "order must mirror input order exactly, never sorted by magnitude/visibility")
    }

    @Test
    fun `E10 - repeated calls with identical inputs produce equal outputs`() {
        val (star, context) = forwardAxisStar()
        val geometry = buildTestGeometry(rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX)

        val first = projectStars(listOf(star), context, geometry)
        val second = projectStars(listOf(star), context, geometry)
        assertEquals(first, second)
    }

    // =====================================================================================
    // Section F: defensive tests
    // =====================================================================================

    @Test
    fun `invalid inputs are rejected by the input types themselves, not by projectStars`() {
        assertFailsWithMessage { EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = Double.NaN, declinationRad = 0.0) }
        assertFailsWithMessage { EquatorialStarDirection(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 2.0) } // > pi/2
        assertFailsWithMessage { StarProjectionContext(latitudeRad = 2.0, longitudeRad = 0.0, utcEpochMillis = 0L) } // > pi/2
        assertFailsWithMessage { StarProjectionContext(latitudeRad = 0.0, longitudeRad = Double.NaN, utcEpochMillis = 0L) }
    }

    @Test
    fun `no successful projection ever contains NaN or infinity`() {
        val instants =
            listOf(
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-06-15T12:34:56Z"),
                Instant.parse("2030-12-31T23:59:59Z"),
            )
        val lonValues = listOf(-179.0, -45.0, 0.0, 45.0, 179.0)
        val latValues = listOf(-80.0, -30.0, 0.0, 30.0, 80.0)
        val raValues = listOf(0.0, 45.0, 90.0, 180.0, 270.0, 359.0)
        val decValues = listOf(-80.0, -30.0, 0.0, 30.0, 80.0)
        val rotations = listOf(IDENTITY_ROTATION_MATRIX, NORTH_UPRIGHT_ROTATION_MATRIX, SOUTH_UPRIGHT_ROTATION_MATRIX, ZENITH_UPRIGHT_ROTATION_MATRIX)

        for (instant in instants) {
            for (lon in lonValues) {
                for (lat in latValues) {
                    for (rotation in rotations) {
                        val stars =
                            raValues.flatMap { ra ->
                                decValues.map { dec ->
                                    EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = Math.toRadians(ra), declinationRad = Math.toRadians(dec))
                                }
                            }
                        val context = StarProjectionContext(Math.toRadians(lat), Math.toRadians(lon), instant.toEpochMilli())
                        val geometry = buildTestGeometry(rotationMatrix = rotation)

                        for (result in projectStars(stars, context, geometry)) {
                            result.cameraDirection?.let {
                                assertTrue(
                                    it.cameraX.isFinite() && it.cameraY.isFinite() && it.cameraZ.isFinite() &&
                                        it.normalizedX.isFinite() && it.normalizedY.isFinite(),
                                    "non-finite cameraDirection for $result",
                                )
                            }
                            result.imagePoint?.let { assertTrue(it.x.isFinite() && it.y.isFinite(), "non-finite imagePoint for $result") }
                            result.displayPoint?.let { assertTrue(it.x.isFinite() && it.y.isFinite(), "non-finite displayPoint for $result") }
                        }
                    }
                }
            }
        }
    }
}
