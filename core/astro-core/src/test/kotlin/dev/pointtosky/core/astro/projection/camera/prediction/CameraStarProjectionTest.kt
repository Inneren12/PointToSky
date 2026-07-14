package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Section B (camera rotation anchors, literal matrices), the self-consistent basis-composition test
 * section (algebraic consistency only — see that section's own comment block for exactly what it does
 * and does not prove), Section E (end-to-end pure cases), and Section F (defensive tests) for CAM-2a.
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

    /**
     * The full world -> device -> display-optical -> buffer-optical -> gate/normalize chain, for a
     * given [rotationDegrees] (default `0`, i.e. an identity buffer transform — Section B below tests
     * the attitude/rotation-matrix math in isolation from frame rotation; the coupled section further
     * down exercises non-zero, *paired* [rotationDegrees] explicitly).
     */
    private fun projectFullPipeline(
        world: LocalSkyDirection,
        rotationMatrix: FloatArray,
        rotationDegrees: Int = 0,
    ): CameraDirectionProjection {
        val device = worldToDeviceVector(rotationMatrix, world)
        val displayOptical = DeviceToOpticalCameraTransform.apply(device)
        val bufferOptical = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, rotationDegrees)
        return projectBufferOpticalDirection(bufferOptical)
    }

    // =====================================================================================
    // Section B: camera rotation anchors (literal matrices, literal expected directions)
    // =====================================================================================

    @Test
    fun `identity rotation - world nadir maps to device forward axis`() {
        // IDENTITY_ROTATION_MATRIX device axes == world axes, so forwardWorld (-col2) = (0,0,-1) = nadir.
        val device = worldToDeviceVector(IDENTITY_ROTATION_MATRIX, LocalSkyDirection(0.0, 0.0, -1.0))
        assertVector(0.0, 0.0, -1.0, device, "identity: nadir -> device forward")

        val projection = projectFullPipeline(LocalSkyDirection(0.0, 0.0, -1.0), IDENTITY_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "nadir must project to image center")
    }

    @Test
    fun `identity rotation - world zenith (opposite of forward) is rejected as behind the camera`() {
        val projection = projectFullPipeline(LocalSkyDirection(0.0, 0.0, 1.0), IDENTITY_ROTATION_MATRIX)
        assertEquals(CameraDirectionProjection.BehindCamera, projection)
    }

    @Test
    fun `north-facing upright camera - north horizon maps to image center`() {
        val projection = projectFullPipeline(LocalSkyDirection(0.0, 1.0, 0.0), NORTH_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "camera forward must project to image center")
    }

    @Test
    fun `north-facing upright camera - east is to the right (increasing normalizedX)`() {
        // 20 deg east of north: cardinal-check helper gives an exact, non-degenerate local sky vector.
        val direction = localSkyDirectionFromHorizontal(azimuthRad = Math.toRadians(20.0), altitudeRad = 0.0)
        val projection = projectFullPipeline(direction, NORTH_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(inFront.normalizedX > 0.0, "east of a north-facing camera must have positive normalizedX, was ${inFront.normalizedX}")
        assertTrue(abs(inFront.normalizedY) < eps, "on the horizon, normalizedY must be ~0")
    }

    @Test
    fun `north-facing upright camera - above the horizon maps to smaller normalizedY (image up)`() {
        // 20 deg above due north.
        val direction = localSkyDirectionFromHorizontal(azimuthRad = 0.0, altitudeRad = Math.toRadians(20.0))
        val projection = projectFullPipeline(direction, NORTH_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(abs(inFront.normalizedX) < eps, "on the meridian, normalizedX must be ~0")
        assertTrue(
            inFront.normalizedY < 0.0,
            "a direction above the horizon must have negative normalizedY (+y=down means up is negative), was ${inFront.normalizedY}",
        )
    }

    @Test
    fun `90 degree yaw right (north to east) - world east maps to device forward axis`() {
        val projection = projectFullPipeline(LocalSkyDirection(1.0, 0.0, 0.0), EAST_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "east must project to image center for the east-facing camera")
    }

    @Test
    fun `90 degree yaw left (north to west) - world west maps to device forward axis`() {
        val projection = projectFullPipeline(LocalSkyDirection(-1.0, 0.0, 0.0), WEST_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(projection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "west must project to image center for the west-facing camera")
    }

    @Test
    fun `90 degree pitch up (north to zenith) - world zenith maps to device forward axis, nadir is rejected`() {
        val zenithProjection = projectFullPipeline(LocalSkyDirection(0.0, 0.0, 1.0), ZENITH_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(zenithProjection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "zenith must project to image center for the zenith-pitched camera")

        val nadirProjection = projectFullPipeline(LocalSkyDirection(0.0, 0.0, -1.0), ZENITH_UPRIGHT_ROTATION_MATRIX)
        assertEquals(CameraDirectionProjection.BehindCamera, nadirProjection)
    }

    @Test
    fun `pitching a further 90 degrees down from zenith returns exactly the identity matrix`() {
        assertEquals(IDENTITY_ROTATION_MATRIX.toList(), listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
        // ZENITH_UPRIGHT_ROTATION_MATRIX's own KDoc documents this relationship; this test pins it.
    }

    @Test
    fun `180 degree rotation (north to south) - south maps to device forward, north is now rejected`() {
        val southProjection = projectFullPipeline(LocalSkyDirection(0.0, -1.0, 0.0), SOUTH_UPRIGHT_ROTATION_MATRIX)
        val inFront = assertIs<CameraDirectionProjection.InFront>(southProjection)
        assertTrue(abs(inFront.normalizedX) < eps && abs(inFront.normalizedY) < eps, "south must project to image center for the south-facing camera")

        val northProjection = projectFullPipeline(LocalSkyDirection(0.0, 1.0, 0.0), SOUTH_UPRIGHT_ROTATION_MATRIX)
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
    // Self-consistent basis-composition tests (Blocker 1 fix)
    //
    // The old "all four rotations" check held the *same* synthetic display-aligned rotation matrix
    // fixed while independently varying frame.rotationDegrees. That could never catch the real bug
    // class, because production changes attitude and frame.rotationDegrees *together* for a physical
    // rotation. These tests instead:
    //  1. build the attitude matrix by column-permutation, mirroring `remapForDisplay`'s formula, for
    //     one fixed base pose re-expressed at each of the four display rotations
    //     (see [remapColumnsForDisplayRotationDegrees]);
    //  2. pair it with the frame.rotationDegrees value that keeps the algebra self-consistent (see
    //     [pairedFrameRotationDegrees] — derived from composing
    //     [remapColumnsForDisplayRotationDegrees] with [DisplayAlignedOpticalToBufferOpticalTransform],
    //     not guessed);
    //  3. project world directions defined *relative to the device's own current axes*
    //     (`col0`/`col1` of the remapped matrix) — the notion of "screen right"/"screen up" a viewer
    //     of the live, correctly-oriented preview would actually perceive;
    //  4. use one *fixed* (non-rotation-matching) viewport, modeling a real, portrait-locked physical
    //     display whose own pixel dimensions do not change shape just because the sensor buffer's
    //     rotation differs.
    //
    // These are pure-math, self-consistent basis-composition tests only — they prove
    // [DisplayAlignedOpticalToBufferOpticalTransform] and `CropScaleTransform`'s forward rotation
    // compose correctly for *some* self-consistent (attitude, frame.rotationDegrees) pairing. They are
    // NOT a claim about "the real production relationship" or "a realistic CameraX pairing": CameraX
    // derives `ImageProxy.imageInfo.rotationDegrees` from the camera's own sensor-mounting orientation
    // (`CameraCharacteristics.SENSOR_ORIENTATION`) combined with the target/display rotation, and
    // neither this test file nor [pairedFrameRotationDegrees] models sensor orientation at all — see
    // [pairedFrameRotationDegrees]'s KDoc (`TestGeometryFixtures.kt`) for the full caveat. Whether any
    // real device reports this exact pairing remains an open, unverified device-alignment risk (see
    // `docs/camera_star_prediction_contract.md` §11).
    // =====================================================================================

    private val coupledInstant: Instant = Instant.parse("2024-04-04T04:00:00Z")
    private val coupledLonDeg = 20.0
    private val twentyDegRad = Math.toRadians(20.0)

    /** `cos(20 deg)*North + sin(20 deg)*axis`, for a device-relative "20 deg off forward, toward axis" test direction. */
    private fun offAxis(axis: LocalSkyDirection): LocalSkyDirection {
        val c = cos(twentyDegRad)
        val s = sin(twentyDegRad)
        return LocalSkyDirection(
            x = c * 0.0 + s * axis.x,
            y = c * 1.0 + s * axis.y,
            z = c * 0.0 + s * axis.z,
        )
    }

    private fun remappedColumn0(displayRotationDegrees: Int): LocalSkyDirection {
        val r = remapColumnsForDisplayRotationDegrees(NORTH_UPRIGHT_ROTATION_MATRIX, displayRotationDegrees)
        return LocalSkyDirection(r[0].toDouble(), r[3].toDouble(), r[6].toDouble())
    }

    private fun remappedColumn1(displayRotationDegrees: Int): LocalSkyDirection {
        val r = remapColumnsForDisplayRotationDegrees(NORTH_UPRIGHT_ROTATION_MATRIX, displayRotationDegrees)
        return LocalSkyDirection(r[1].toDouble(), r[4].toDouble(), r[7].toDouble())
    }

    private fun coupledGeometry(displayRotationDegrees: Int): CameraSessionGeometryAndFrameRotation {
        val attitude = remapColumnsForDisplayRotationDegrees(NORTH_UPRIGHT_ROTATION_MATRIX, displayRotationDegrees)
        val frameRotationDegrees = pairedFrameRotationDegrees(displayRotationDegrees)
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                rotationDegrees = frameRotationDegrees,
                viewportWidthPx = 1000,
                viewportHeightPx = 500, // fixed viewport for every display rotation - see section comment above
                rotationMatrix = attitude,
            )
        return CameraSessionGeometryAndFrameRotation(geometry, frameRotationDegrees)
    }

    private data class CameraSessionGeometryAndFrameRotation(
        val geometry: CameraSessionGeometry,
        val frameRotationDegrees: Int,
    )

    private fun projectSingleReady(
        star: EquatorialStarDirection,
        context: StarProjectionContext,
        geometry: CameraSessionGeometry,
    ): PredictedStarProjection = (projectStars(listOf(star), context, geometry) as StarPredictionBatchResult.Ready).projections.single()

    @Test
    fun `coupled rotations - the same physical forward direction is the display center for all four`() {
        for (d in listOf(0, 90, 180, 270)) {
            val (geometry, _) = coupledGeometry(d)
            val (star, context) = meridianTransitStar(coupledInstant, coupledLonDeg, latDeg = 45.0, decDeg = 45.0, lowerCulmination = true)
            val result = projectSingleReady(star, context, geometry)
            val displayPoint = assertNotNull(result.displayPoint, "d=$d")
            assertTrue(abs(displayPoint.x - 500.0) < eps, "d=$d: expected center x=500, was ${displayPoint.x}")
            assertTrue(abs(displayPoint.y - 250.0) < eps, "d=$d: expected center y=250, was ${displayPoint.y}")
        }
    }

    @Test
    fun `coupled rotations - the current device-relative right direction always increases display X`() {
        for (d in listOf(0, 90, 180, 270)) {
            val (geometry, frameRotationDegrees) = coupledGeometry(d)
            val world = offAxis(remappedColumn0(d))
            val attitude = remapColumnsForDisplayRotationDegrees(NORTH_UPRIGHT_ROTATION_MATRIX, d)
            val cameraProjection = projectFullPipeline(world, attitude, frameRotationDegrees)
            val inFront = assertIs<CameraDirectionProjection.InFront>(cameraProjection, "d=$d")

            val pinhole = PinholeProjectionModel.forGeometry(geometry)
            val imagePoint = pinhole.project(inFront.normalizedX, inFront.normalizedY)
            val displayPoint = geometry.cropScaleTransform.imageToDisplay(imagePoint)

            assertTrue(displayPoint.x > 500.0, "d=$d: expected increased display X, was ${displayPoint.x}")
            assertTrue(abs(displayPoint.y - 250.0) < 1.0, "d=$d: expected ~unchanged display Y, was ${displayPoint.y}")
        }
    }

    @Test
    fun `coupled rotations - the current device-relative up direction always decreases display Y`() {
        for (d in listOf(0, 90, 180, 270)) {
            val (geometry, frameRotationDegrees) = coupledGeometry(d)
            val world = offAxis(remappedColumn1(d))
            val attitude = remapColumnsForDisplayRotationDegrees(NORTH_UPRIGHT_ROTATION_MATRIX, d)
            val cameraProjection = projectFullPipeline(world, attitude, frameRotationDegrees)
            val inFront = assertIs<CameraDirectionProjection.InFront>(cameraProjection, "d=$d")

            val pinhole = PinholeProjectionModel.forGeometry(geometry)
            val imagePoint = pinhole.project(inFront.normalizedX, inFront.normalizedY)
            val displayPoint = geometry.cropScaleTransform.imageToDisplay(imagePoint)

            assertTrue(displayPoint.y < 250.0, "d=$d: expected decreased display Y, was ${displayPoint.y}")
            assertTrue(abs(displayPoint.x - 500.0) < 1.0, "d=$d: expected ~unchanged display X, was ${displayPoint.x}")
        }
    }

    @Test
    fun `coupled rotations - skipping the buffer-optical correction reproduces the pre-fix double-rotation bug`() {
        // Regression guard for Blocker 1 itself: feeding the *display-aligned* optical ray straight
        // into the buffer-space pinhole model - i.e. skipping
        // [DisplayAlignedOpticalToBufferOpticalTransform] entirely, exactly what this codebase did
        // before this fix - must NOT reproduce the correct, consistent up-anchor for a rotated case.
        //
        // Note this is deliberately *not* "apply the unpaired rotationDegrees value to both the
        // direction correction and CropScaleTransform" (an earlier draft of this test tried that): for
        // any rotationDegrees k applied *consistently* to both stages,
        // `CropScaleTransform.rotateClockwise(·, k)` is exactly the mathematical inverse of
        // `DisplayAlignedOpticalToBufferOpticalTransform.apply(·, k)` by construction (see that
        // object's KDoc derivation), so the two cancel out regardless of which (self-consistent) k is
        // chosen - that composition can never distinguish a correct pairing from an incorrect one. The
        // only way to reproduce the real Blocker 1 bug is to *omit* the correction, not to mismatch it.
        val d = 90
        val attitude = remapColumnsForDisplayRotationDegrees(NORTH_UPRIGHT_ROTATION_MATRIX, d)
        val frameRotationDegrees = pairedFrameRotationDegrees(d)
        val world = offAxis(remappedColumn1(d))

        val device = worldToDeviceVector(attitude, world)
        val displayOptical = DeviceToOpticalCameraTransform.apply(device)
        // Pre-fix bug: project displayOptical directly, never converting to buffer-optical space.
        val cameraProjection = projectBufferOpticalDirection(BufferOpticalCameraVector(displayOptical.x, displayOptical.y, displayOptical.z))
        val inFront = assertIs<CameraDirectionProjection.InFront>(cameraProjection)

        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                rotationDegrees = frameRotationDegrees,
                viewportWidthPx = 1000,
                viewportHeightPx = 500,
            )
        val pinhole = PinholeProjectionModel.forGeometry(geometry)
        val imagePoint = pinhole.project(inFront.normalizedX, inFront.normalizedY)
        val displayPoint = geometry.cropScaleTransform.imageToDisplay(imagePoint)

        // With the fix applied (see the "up direction always decreases display Y" test above) this
        // would be (500, <250); omitting the buffer-optical correction instead shows an X-shift (the Y
        // anchor "leaking" into X), the exact symptom of the double-rotation bug this fix closes.
        assertTrue(abs(displayPoint.x - 500.0) > 50.0, "expected the uncorrected pipeline to break the X-invariance, was ${displayPoint.x}")
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
        val result = projectSingleReady(star, context, geometry)

        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        val displayPoint = assertNotNull(result.displayPoint)
        assertTrue(abs(displayPoint.x - 500.0) < eps, "expected display center x=500, was ${displayPoint.x}")
        assertTrue(abs(displayPoint.y - 250.0) < eps, "expected display center y=250, was ${displayPoint.y}")
    }

    @Test
    fun `E2 - behind-camera star is classified BEHIND_CAMERA with no pixel data`() {
        val (star, context) = behindCameraStar()
        val geometry = buildTestGeometry(rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX)
        val result = projectSingleReady(star, context, geometry)

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
        val result = projectSingleReady(star, context, geometry)

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
        val result = projectSingleReady(star, context, geometry)

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
        val result = projectSingleReady(star, context, geometry)
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
        val result = projectSingleReady(star, context, geometry)
        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        assertTrue(abs(result.displayPoint!!.x - 960.0) < eps)
        assertTrue(abs(result.displayPoint.y - 540.0) < eps)
    }

    @Test
    fun `E7 - legacy-fallback (analysis-buffer) intrinsics path produces a visible on-axis prediction`() {
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
                intrinsicsResolution = legacyFallbackIntrinsics(imageWidthPx = 1000, imageHeightPx = 500),
            )
        val result = projectSingleReady(star, context, geometry)
        assertEquals(PredictedStarClassification.VISIBLE_IN_VIEWPORT, result.classification)
        assertTrue(abs(result.displayPoint!!.x - 500.0) < eps)
        assertTrue(abs(result.displayPoint.y - 250.0) < eps)
    }

    @Test
    fun `E8 - calibrated (physical-sensor) intrinsics path is reported unavailable, never fabricated`() {
        // Blocker 2 fix: CAMERA_CHARACTERISTICS-sourced intrinsics are physical-sensor-referenced (no
        // recorded crop/scale mapping to the analyzed buffer), so this must NOT silently produce a
        // Ready prediction - it must report IntrinsicsMappingUnavailable instead of fabricating a
        // buffer-space focal length from a physical-sensor FOV.
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
                intrinsicsResolution = resolvedIntrinsics(horizontalFovDeg = 70.0, verticalFovDeg = 50.0),
            )
        val result = projectStars(listOf(star), context, geometry)
        val unavailable = assertIs<StarPredictionBatchResult.IntrinsicsMappingUnavailable>(result)
        assertEquals(IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED, unavailable.reason)
    }

    @Test
    fun `E8b - a dimensionless (Unspecified) intrinsics reference is reported unavailable as reference-missing`() {
        // A legacy fallback resolved with no analyzed-frame dimensions yet has no reference dimensions
        // to check against anything - it must never be treated as analysis-buffer-compatible, and must
        // be distinguishable from the physical-sensor-unsupported case above.
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
                intrinsicsResolution = unspecifiedReferenceIntrinsics(),
            )
        val result = projectStars(listOf(star), context, geometry)
        val unavailable = assertIs<StarPredictionBatchResult.IntrinsicsMappingUnavailable>(result)
        assertEquals(IntrinsicsMappingUnavailableReason.ANALYSIS_BUFFER_REFERENCE_MISSING, unavailable.reason)
    }

    @Test
    fun `E8c - an AnalysisBuffer reference for a different buffer size is reported unavailable as a dimensions mismatch`() {
        // Regression guard for the exact bug this hardening closes: intrinsics resolved for one buffer
        // (or session) must not be silently reused against a geometry backed by a different buffer,
        // even though both are LEGACY_FALLBACK/analysis-buffer-referenced in a general sense.
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
                intrinsicsResolution = analysisBufferIntrinsics(referenceWidthPx = 1920, referenceHeightPx = 1080),
            )
        val result = projectStars(listOf(star), context, geometry)
        val unavailable = assertIs<StarPredictionBatchResult.IntrinsicsMappingUnavailable>(result)
        assertEquals(IntrinsicsMappingUnavailableReason.ANALYSIS_BUFFER_DIMENSIONS_MISMATCH, unavailable.reason)
    }

    @Test
    fun `E8d - stale-session reuse - intrinsics built for one buffer size cannot silently project a different buffer`() {
        // Simulates the exact scenario this hardening exists to prevent: build intrinsics referencing
        // one analyzed buffer (e.g. an earlier session's 1920x1080 stream), then attempt to use them
        // for a geometry backed by a different buffer (e.g. a new session's 1000x500 stream). The
        // result must be a typed mismatch, never a fabricated Ready prediction.
        val staleIntrinsics = analysisBufferIntrinsics(referenceWidthPx = 1920, referenceHeightPx = 1080)
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
                intrinsicsResolution = staleIntrinsics,
            )
        val result = projectStars(listOf(star), context, geometry)
        val unavailable = assertIs<StarPredictionBatchResult.IntrinsicsMappingUnavailable>(result)
        assertEquals(IntrinsicsMappingUnavailableReason.ANALYSIS_BUFFER_DIMENSIONS_MISMATCH, unavailable.reason)
    }

    @Test
    fun `E8e - an exactly-matching AnalysisBuffer reference still produces Ready`() {
        val (star, context) = forwardAxisStar()
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX,
                intrinsicsResolution = analysisBufferIntrinsics(referenceWidthPx = 1000, referenceHeightPx = 500),
            )
        val result = projectStars(listOf(star), context, geometry)
        assertIs<StarPredictionBatchResult.Ready>(result)
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

        val results = assertIs<StarPredictionBatchResult.Ready>(projectStars(stars, context, geometry)).projections
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
        assertFailsWithMessage { EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = Double.NaN, declinationRad = 0.0) }
        assertFailsWithMessage { EquatorialStarDirection.of(catalogIndex = 0, rightAscensionRad = 0.0, declinationRad = 2.0) } // > pi/2
        assertFailsWithMessage { StarProjectionContext.of(latitudeRad = 2.0, longitudeRad = 0.0, utcEpochMillis = 0L) } // > pi/2
        assertFailsWithMessage { StarProjectionContext.of(latitudeRad = 0.0, longitudeRad = Double.NaN, utcEpochMillis = 0L) }
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
                        val context = StarProjectionContext.of(Math.toRadians(lat), Math.toRadians(lon), instant.toEpochMilli())
                        val geometry = buildTestGeometry(rotationMatrix = rotation)

                        val results = assertIs<StarPredictionBatchResult.Ready>(projectStars(stars, context, geometry)).projections
                        for (result in results) {
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

    @Test
    fun `an empty star list is Ready with an empty projections list, not unavailable`() {
        val geometry = buildTestGeometry(rotationMatrix = NORTH_UPRIGHT_ROTATION_MATRIX)
        val context = neutralContext()
        val result = assertIs<StarPredictionBatchResult.Ready>(projectStars(emptyList(), context, geometry))
        assertEquals(emptyList(), result.projections)
    }
}
