package dev.pointtosky.core.astro.math

import dev.pointtosky.core.astro.units.degToRad
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable right-handed 3D Cartesian vector with double precision components.
 * All angles and distances are expressed in SI units unless noted otherwise.
 */
public data class Vector3(
    val x: Double,
    val y: Double,
    val z: Double,
) {

    /**
     * Euclidean length of the vector.
     */
    public val magnitude: Double = sqrt(x * x + y * y + z * z)

    /**
     * Returns a vector scaled by the provided factor.
     */
    public fun scale(factor: Double): Vector3 = Vector3(x * factor, y * factor, z * factor)

    /**
     * Computes the dot product between this vector and [other].
     */
    public infix fun dot(other: Vector3): Double = x * other.x + y * other.y + z * other.z

    /**
     * Computes the cross product between this vector and [other].
     */
    public infix fun cross(other: Vector3): Vector3 = Vector3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    /**
     * Returns a unit vector pointing in the same direction.
     *
     * @throws IllegalStateException if the vector has zero magnitude.
     */
    public fun normalized(): Vector3 {
        require(magnitude != 0.0) { "Cannot normalize a zero-length vector" }
        val invMag = 1.0 / magnitude
        return scale(invMag)
    }

    /**
     * Adds [other] to this vector component-wise.
     */
    public operator fun plus(other: Vector3): Vector3 = Vector3(x + other.x, y + other.y, z + other.z)

    /**
     * Subtracts [other] from this vector component-wise.
     */
    public operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)
}

/**
 * Constructs a [Vector3] from spherical coordinates expressed in degrees.
 *
 * @param azimuthDeg Azimuth angle in degrees, measured from the +X axis toward +Y (`0°..360°`).
 * @param elevationDeg Elevation angle in degrees above the X-Y plane (`-90°..+90°`).
 * @param radius Radial distance from the origin.
 */
public fun vectorFromSphericalDegrees(azimuthDeg: Double, elevationDeg: Double, radius: Double = 1.0): Vector3 {
    val azRad = degToRad(azimuthDeg)
    val elRad = degToRad(elevationDeg)
    val cosEl = cos(elRad)
    return Vector3(
        radius * cosEl * cos(azRad),
        radius * cosEl * sin(azRad),
        radius * sin(elRad),
    )
}
