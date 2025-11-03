package dev.pointtosky.core.astro.math

import kotlin.math.sqrt

/**
 * Immutable 3D vector backed by [Double] values.
 *
 * Components are unitless unless specified by the caller.
 */
data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    /**
     * Returns the Euclidean norm (length) of this vector.
     */
    val length: Double get() = sqrt(x * x + y * y + z * z)

    /**
     * Returns a normalized copy of this vector.
     *
     * @throws IllegalStateException if the vector has zero length.
     */
    fun normalized(): Vec3 {
        val len = length
        check(len > 0.0) { "Cannot normalize a zero vector" }
        val inv = 1.0 / len
        return Vec3(x * inv, y * inv, z * inv)
    }

    /**
     * Computes the dot product of this vector with [other].
     */
    infix fun dot(other: Vec3): Double = x * other.x + y * other.y + z * other.z

    /**
     * Computes the cross product of this vector with [other].
     */
    infix fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    /**
     * Multiplies this vector by a scalar [factor].
     */
    operator fun times(factor: Double): Vec3 = Vec3(x * factor, y * factor, z * factor)

    /**
     * Divides this vector by a scalar [divisor].
     */
    operator fun div(divisor: Double): Vec3 = Vec3(x / divisor, y / divisor, z / divisor)

    /**
     * Adds this vector to [other].
     */
    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    /**
     * Subtracts [other] from this vector.
     */
    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
}

/**
 * 3 × 3 matrix represented by column vectors.
 *
 * @property c0 First column vector.
 * @property c1 Second column vector.
 * @property c2 Third column vector.
 */
data class Mat3(
    val c0: Vec3,
    val c1: Vec3,
    val c2: Vec3,
) {
    /**
     * Multiplies this matrix by [vector], treating the columns as the basis vectors.
     */
    operator fun times(vector: Vec3): Vec3 = Vec3(
        c0.x * vector.x + c1.x * vector.y + c2.x * vector.z,
        c0.y * vector.x + c1.y * vector.y + c2.y * vector.z,
        c0.z * vector.x + c1.z * vector.y + c2.z * vector.z,
    )

    /**
     * Multiplies this matrix with another 3 × 3 [other] matrix.
     */
    operator fun times(other: Mat3): Mat3 = Mat3(
        this * Vec3(other.c0.x, other.c0.y, other.c0.z),
        this * Vec3(other.c1.x, other.c1.y, other.c1.z),
        this * Vec3(other.c2.x, other.c2.y, other.c2.z),
    )

    /**
     * Returns the transpose of this matrix.
     */
    fun transpose(): Mat3 = Mat3(
        Vec3(c0.x, c1.x, c2.x),
        Vec3(c0.y, c1.y, c2.y),
        Vec3(c0.z, c1.z, c2.z),
    )

    companion object {
        /**
         * Identity 3 × 3 matrix.
         */
        val IDENTITY: Mat3 = Mat3(
            Vec3(1.0, 0.0, 0.0),
            Vec3(0.0, 1.0, 0.0),
            Vec3(0.0, 0.0, 1.0),
        )

        /**
         * Builds a matrix from row vectors.
         */
        fun fromRows(r0: Vec3, r1: Vec3, r2: Vec3): Mat3 = Mat3(
            Vec3(r0.x, r1.x, r2.x),
            Vec3(r0.y, r1.y, r2.y),
            Vec3(r0.z, r1.z, r2.z),
        )
    }
}
