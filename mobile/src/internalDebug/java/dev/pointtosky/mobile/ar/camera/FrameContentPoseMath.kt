package dev.pointtosky.mobile.ar.camera

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only). Pure, Android-free linear
 * algebra for a **planar-homography pose solve** — this codebase has no PnP/`solvePnP`/reprojection
 * code anywhere (confirmed by repo-wide search before this experiment was added), so this file is a
 * new, first-of-its-kind, deliberately minimal implementation, not a port of an existing solver.
 *
 * ## Why planar homography, not general PnP
 * [FrameContentTargetSpec] is a flat dot grid (every object point has `zMm = 0`), so the classic
 * planar-calibration approach applies: estimate a single 3x3 homography `H` mapping the target plane
 * directly to image pixels via a linear least-squares Direct Linear Transform (DLT), then decompose
 * `K^-1 H` into a rotation/translation using the camera matrix. This is simpler and more numerically
 * tractable in pure Kotlin than a general iterative (Levenberg-Marquardt) PnP solver, at the cost of
 * being a **first-cut, non-iteratively-refined** estimate — see [FrameContentPoseSolution]'s KDoc for
 * the honesty statement this experiment's report always carries alongside a solved pose
 * (`POSE_ESTIMATION_MODEL=PHYSICAL_CAMERA_CALIBRATED_DOMAIN`).
 */

/** A plain 3-vector, millimetres or a dimensionless ray depending on context — never implicitly mixed
 * with a pixel coordinate (see `FrameContentProjection.kt` for the pixel-space boundary). */
internal data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(scalar: Double) = Vec3(x * scalar, y * scalar, z * scalar)

    fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 =
        Vec3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    fun norm(): Double = sqrt(x * x + y * y + z * z)
}

/** A 3x3 matrix, row-major (`mRC` = row `R`, column `C`) — used here only for a rotation matrix, never
 * for the unrelated 2D [dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3]. */
internal data class RotationMatrix3(
    val m00: Double,
    val m01: Double,
    val m02: Double,
    val m10: Double,
    val m11: Double,
    val m12: Double,
    val m20: Double,
    val m21: Double,
    val m22: Double,
) {
    fun apply(v: Vec3): Vec3 =
        Vec3(
            m00 * v.x + m01 * v.y + m02 * v.z,
            m10 * v.x + m11 * v.y + m12 * v.z,
            m20 * v.x + m21 * v.y + m22 * v.z,
        )

    companion object {
        /** Builds a rotation matrix from its three (expected orthonormal) column vectors. */
        fun fromColumns(
            c0: Vec3,
            c1: Vec3,
            c2: Vec3,
        ) = RotationMatrix3(
            m00 = c0.x,
            m01 = c1.x,
            m02 = c2.x,
            m10 = c0.y,
            m11 = c1.y,
            m12 = c2.y,
            m20 = c0.z,
            m21 = c1.z,
            m22 = c2.z,
        )

        val IDENTITY = RotationMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }
}

/**
 * One frozen pose (task §2/§4): a rotation + translation from the target's own local plane frame into
 * the camera frame this experiment's pose solver used, plus the equivalent Rodrigues angle-axis vector
 * ("rvec") for report/JSON export. `translationMm` is in the same millimetre units as
 * [FrameContentObjectPoint].
 *
 * @property estimationModel a fixed, human-readable label for exactly which model produced this pose —
 *   always `"PHYSICAL_CAMERA_CALIBRATED_DOMAIN"` for this first implementation (task §4): pose is
 *   solved once, from the detected `ImageAnalysis`-buffer points directly, using the
 *   `PHYSICAL_ACTIVE_ARRAY_MODEL_PATH` hypothesis's own resolved buffer-space camera matrix (never a
 *   hypothesis-agnostic or logical-basis matrix) — chosen specifically so the pose solve and the
 *   observed points live in the same coordinate domain by construction. This same, frozen pose is then
 *   reused unchanged for every competing mapping hypothesis's residual computation (never refit) — see
 *   `FrameContentCorrespondenceSnapshot.kt`'s `POSE_REUSED_UNCHANGED_ACROSS_ALL_MAPPING_HYPOTHESES` flag.
 */
internal data class FrameContentPoseSolution(
    val rotation: RotationMatrix3,
    val translationMm: Vec3,
    val rodriguesRvec: Vec3,
    val estimationModel: String = FRAME_CONTENT_POSE_ESTIMATION_MODEL,
    val poseSolverRmsResidualPx: Double,
)

internal const val FRAME_CONTENT_POSE_ESTIMATION_MODEL: String = "PHYSICAL_CAMERA_CALIBRATED_DOMAIN"

/** One 2D<->2D correspondence used to solve the planar homography: [objectXMm]/[objectYMm] on the
 * target's own plane, [imageUPx]/[imageVPx] the observed pixel location. */
internal data class PlanarCorrespondence(
    val objectXMm: Double,
    val objectYMm: Double,
    val imageUPx: Double,
    val imageVPx: Double,
)

/** Minimum number of correspondences a homography DLT solve requires (4 points, 8 degrees of freedom,
 * 2 equations per point). Callers should prefer well more than the minimum for a least-squares solve. */
internal const val MIN_CORRESPONDENCES_FOR_HOMOGRAPHY: Int = 4

/**
 * Solves a linear system `A x = b` via Gaussian elimination with partial pivoting. Returns `null`
 * (never a fabricated answer) when [a] is singular or near-singular at any pivot step. [a] must be
 * square; both are treated as read-only (a working copy is made internally).
 */
internal fun solveLinearSystem(
    a: Array<DoubleArray>,
    b: DoubleArray,
    singularPivotTolerance: Double = 1e-10,
): DoubleArray? {
    val n = b.size
    require(a.size == n && a.all { it.size == n }) { "a must be an n x n matrix matching b's size ($n)" }

    // Augmented working copy [A | b].
    val m = Array(n) { row -> DoubleArray(n + 1) { col -> if (col < n) a[row][col] else b[row] } }

    for (pivotCol in 0 until n) {
        var pivotRow = pivotCol
        var pivotMagnitude = abs(m[pivotCol][pivotCol])
        for (row in (pivotCol + 1) until n) {
            val magnitude = abs(m[row][pivotCol])
            if (magnitude > pivotMagnitude) {
                pivotRow = row
                pivotMagnitude = magnitude
            }
        }
        if (pivotMagnitude <= singularPivotTolerance) return null
        if (pivotRow != pivotCol) {
            val tmp = m[pivotCol]
            m[pivotCol] = m[pivotRow]
            m[pivotRow] = tmp
        }
        val pivotValue = m[pivotCol][pivotCol]
        for (row in 0 until n) {
            if (row == pivotCol) continue
            val factor = m[row][pivotCol] / pivotValue
            if (factor == 0.0) continue
            for (col in pivotCol..n) {
                m[row][col] -= factor * m[pivotCol][col]
            }
        }
    }

    return DoubleArray(n) { row -> m[row][n] / m[row][row] }
}

/**
 * Solves a 3x3 planar homography `H` (`[u, v, 1]^T ~ H [X, Y, 1]^T`, up to scale) from
 * [correspondences] via a normalized Direct Linear Transform, fixing `H[2][2] = 1` and solving the
 * remaining 8 coefficients by least squares (normal equations, [solveLinearSystem]). Points are
 * Hartley-normalized (translated to centroid zero, scaled so the mean distance from the origin is
 * `sqrt(2)`) before the linear solve and the result is denormalized afterward — required for numerical
 * stability here specifically because object coordinates are tens of millimetres while image
 * coordinates are hundreds of pixels. Returns `null` when there are fewer than
 * [MIN_CORRESPONDENCES_FOR_HOMOGRAPHY] correspondences or the normal-equations solve is singular.
 */
internal fun solvePlanarHomography(correspondences: List<PlanarCorrespondence>): Array<DoubleArray>? {
    if (correspondences.size < MIN_CORRESPONDENCES_FOR_HOMOGRAPHY) return null

    val (objNorm, tObj) = normalizePoints(correspondences.map { it.objectXMm to it.objectYMm })
    val (imgNorm, tImg) = normalizePoints(correspondences.map { it.imageUPx to it.imageVPx })

    val n = correspondences.size
    val aRows = Array(2 * n) { DoubleArray(8) }
    val bVec = DoubleArray(2 * n)
    for (i in 0 until n) {
        val (x, y) = objNorm[i]
        val (u, v) = imgNorm[i]
        aRows[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y)
        bVec[2 * i] = u
        aRows[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y)
        bVec[2 * i + 1] = v
    }

    // Normal equations: (A^T A) h = A^T b — 8x8, solved via Gaussian elimination.
    val ata = Array(8) { DoubleArray(8) }
    val atb = DoubleArray(8)
    for (row in aRows.indices) {
        for (i in 0 until 8) {
            atb[i] += aRows[row][i] * bVec[row]
            for (j in 0 until 8) {
                ata[i][j] += aRows[row][i] * aRows[row][j]
            }
        }
    }
    val h = solveLinearSystem(ata, atb) ?: return null

    val hNorm =
        arrayOf(
            doubleArrayOf(h[0], h[1], h[2]),
            doubleArrayOf(h[3], h[4], h[5]),
            doubleArrayOf(h[6], h[7], 1.0),
        )

    // Denormalize: H = tImg^-1 * hNorm * tObj.
    val tImgInv = invert3x3(tImg) ?: return null
    return multiply3x3(multiply3x3(tImgInv, hNorm), tObj)
}

/** Hartley normalization: returns the normalized `(x, y)` pairs and the 3x3 similarity transform `T`
 * such that `normalized = T * original` (homogeneous). */
private fun normalizePoints(points: List<Pair<Double, Double>>): Pair<List<Pair<Double, Double>>, Array<DoubleArray>> {
    val n = points.size
    val meanX = points.sumOf { it.first } / n
    val meanY = points.sumOf { it.second } / n
    val meanDist =
        points.sumOf { (x, y) -> sqrt((x - meanX) * (x - meanX) + (y - meanY) * (y - meanY)) } / n
    val scale = if (meanDist > 1e-12) sqrt(2.0) / meanDist else 1.0

    val transform =
        arrayOf(
            doubleArrayOf(scale, 0.0, -scale * meanX),
            doubleArrayOf(0.0, scale, -scale * meanY),
            doubleArrayOf(0.0, 0.0, 1.0),
        )
    val normalized = points.map { (x, y) -> (scale * (x - meanX)) to (scale * (y - meanY)) }
    return normalized to transform
}

private fun multiply3x3(
    a: Array<DoubleArray>,
    b: Array<DoubleArray>,
): Array<DoubleArray> =
    Array(3) { row ->
        DoubleArray(3) { col ->
            (0 until 3).sumOf { k -> a[row][k] * b[k][col] }
        }
    }

private fun invert3x3(m: Array<DoubleArray>): Array<DoubleArray>? {
    val det =
        m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
            m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
            m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
    if (abs(det) < 1e-12) return null
    val invDet = 1.0 / det
    return arrayOf(
        doubleArrayOf(
            (m[1][1] * m[2][2] - m[1][2] * m[2][1]) * invDet,
            (m[0][2] * m[2][1] - m[0][1] * m[2][2]) * invDet,
            (m[0][1] * m[1][2] - m[0][2] * m[1][1]) * invDet,
        ),
        doubleArrayOf(
            (m[1][2] * m[2][0] - m[1][0] * m[2][2]) * invDet,
            (m[0][0] * m[2][2] - m[0][2] * m[2][0]) * invDet,
            (m[0][2] * m[1][0] - m[0][0] * m[1][2]) * invDet,
        ),
        doubleArrayOf(
            (m[1][0] * m[2][1] - m[1][1] * m[2][0]) * invDet,
            (m[0][1] * m[2][0] - m[0][0] * m[2][1]) * invDet,
            (m[0][0] * m[1][1] - m[0][1] * m[1][0]) * invDet,
        ),
    )
}

/**
 * Converts a rotation matrix to its Rodrigues angle-axis vector (`rvec`): direction = rotation axis,
 * magnitude = rotation angle in radians. Standard closed-form conversion; the near-`0` and near-`pi`
 * angle edge cases are each handled explicitly rather than dividing by a near-zero `sin(theta)`.
 */
internal fun rotationMatrixToRodrigues(r: RotationMatrix3): Vec3 {
    val trace = r.m00 + r.m11 + r.m22
    val cosTheta = ((trace - 1.0) / 2.0).coerceIn(-1.0, 1.0)
    val theta = acos(cosTheta)

    if (theta < 1e-9) return Vec3(0.0, 0.0, 0.0)

    if (PI - theta < 1e-6) {
        // theta ~ pi: sin(theta) ~ 0, so the general formula below is numerically unusable. Recover
        // the axis from the symmetric part of R instead: R = I + 2*(axis outer axis) - I (at theta=pi,
        // R = 2*axis*axis^T - I), so axis_i^2 = (R_ii + 1) / 2.
        val axisX = sqrt(((r.m00 + 1.0) / 2.0).coerceAtLeast(0.0))
        val axisY = sqrt(((r.m11 + 1.0) / 2.0).coerceAtLeast(0.0))
        val axisZ = sqrt(((r.m22 + 1.0) / 2.0).coerceAtLeast(0.0))
        // Disambiguate signs from the off-diagonal terms (R_ij = 2*axis_i*axis_j for i != j).
        val signYZ = sign(r.m12).let { if (it == 0.0) 1.0 else it }
        val signXZ = sign(r.m02).let { if (it == 0.0) 1.0 else it }
        val axis = Vec3(axisX, axisY * signYZ, axisZ * signXZ)
        val norm = axis.norm()
        if (norm < 1e-9) return Vec3(0.0, 0.0, 0.0)
        return (axis * (1.0 / norm)) * theta
    }

    val sinTheta = sin(theta)
    val axis =
        Vec3(
            (r.m21 - r.m12) / (2.0 * sinTheta),
            (r.m02 - r.m20) / (2.0 * sinTheta),
            (r.m10 - r.m01) / (2.0 * sinTheta),
        )
    return axis * theta
}

private fun Vec3.normalizedOrNull(): Vec3? {
    val n = norm()
    if (n < 1e-9 || !n.isFinite()) return null
    return Vec3(x / n, y / n, z / n)
}

/** `K^-1 * (a, b, c)^T` for the pinhole `K = [[fx,0,cx],[0,fy,cy],[0,0,1]]` — a plain linear map, not a
 * homogeneous-division point normalization (used only to decompose homography columns, not to project
 * a point; see `FrameContentProjection.kt` for point projection). */
private fun applyInverseK(
    v: Vec3,
    fxPx: Double,
    fyPx: Double,
    cxPx: Double,
    cyPx: Double,
): Vec3 = Vec3((v.x - cxPx * v.z) / fxPx, (v.y - cyPx * v.z) / fyPx, v.z)

/**
 * Solves one pose (rotation + translation from the target's local plane into the camera frame) from
 * [correspondences] via [solvePlanarHomography] followed by the standard `K^-1 H` planar-pose
 * decomposition (task §4's `PHYSICAL_CAMERA_CALIBRATED_DOMAIN` model — see [FrameContentPoseSolution]'s
 * KDoc): scale-normalize the first two homography columns through `K^-1`, cross-product the third axis,
 * Gram-Schmidt-orthogonalize (since the two DLT-derived columns are not perfectly orthonormal by
 * construction), then convert to Rodrigues form. [fxPx]/[fyPx]/[cxPx]/[cyPx] must already be expressed
 * in the exact same pixel domain as every [PlanarCorrespondence.imageUPx]/[imageVPx] — this function
 * performs no domain conversion of its own.
 *
 * Returns `null` when [solvePlanarHomography] fails, the homography is degenerate (near-zero scale), or
 * the resulting camera-frame translation implies the target is behind the camera and cannot be
 * corrected by a sign flip (should not occur for a real handheld shot of a target in front of the
 * device, but never silently fabricated).
 */
internal fun solvePlanarPose(
    correspondences: List<PlanarCorrespondence>,
    fxPx: Double,
    fyPx: Double,
    cxPx: Double,
    cyPx: Double,
): FrameContentPoseSolution? {
    val h = solvePlanarHomography(correspondences) ?: return null

    val h1 = Vec3(h[0][0], h[1][0], h[2][0])
    val h2 = Vec3(h[0][1], h[1][1], h[2][1])
    val h3 = Vec3(h[0][2], h[1][2], h[2][2])

    val r1Raw = applyInverseK(h1, fxPx, fyPx, cxPx, cyPx)
    val r2Raw = applyInverseK(h2, fxPx, fyPx, cxPx, cyPx)
    val tRaw = applyInverseK(h3, fxPx, fyPx, cxPx, cyPx)

    val n1 = r1Raw.norm()
    val n2 = r2Raw.norm()
    if (n1 < 1e-9 || n2 < 1e-9 || !n1.isFinite() || !n2.isFinite()) return null
    var lambda = 2.0 / (n1 + n2)

    var r1 = r1Raw * lambda
    var r2 = r2Raw * lambda
    var t = tRaw * lambda
    if (t.z < 0.0) {
        lambda = -lambda
        r1 = r1Raw * lambda
        r2 = r2Raw * lambda
        t = tRaw * lambda
    }
    if (t.z <= 0.0 || !t.z.isFinite()) return null

    val r1Ortho = r1.normalizedOrNull() ?: return null
    val r2Ortho = (r2 - r1Ortho * (r2.dot(r1Ortho))).normalizedOrNull() ?: return null
    val r3Ortho = r1Ortho.cross(r2Ortho)
    val rotation = RotationMatrix3.fromColumns(r1Ortho, r2Ortho, r3Ortho)
    val rvec = rotationMatrixToRodrigues(rotation)

    val squaredResiduals =
        correspondences.map { c ->
            val objectCam = rotation.apply(Vec3(c.objectXMm, c.objectYMm, 0.0)) + t
            if (objectCam.z <= 1e-6) return@map null
            val predictedU = fxPx * (objectCam.x / objectCam.z) + cxPx
            val predictedV = fyPx * (objectCam.y / objectCam.z) + cyPx
            if (!predictedU.isFinite() || !predictedV.isFinite()) return@map null
            val du = predictedU - c.imageUPx
            val dv = predictedV - c.imageVPx
            du * du + dv * dv
        }
    val validSquaredResiduals = squaredResiduals.filterNotNull()
    if (validSquaredResiduals.isEmpty()) return null
    val rms = sqrt(validSquaredResiduals.sum() / validSquaredResiduals.size)

    return FrameContentPoseSolution(
        rotation = rotation,
        translationMm = t,
        rodriguesRvec = rvec,
        poseSolverRmsResidualPx = rms,
    )
}
