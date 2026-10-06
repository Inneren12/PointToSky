package dev.pointtosky.mobile.ar.camera

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * PTS-03 Group D (`internalDebug`-only): Camera2 `LENS_POSE_*` → the body-fixed extrinsic
 * `R_Cphys_from_S(id)` (master recon §14.1), with an honest provenance status.
 *
 * ## Quaternion order and direction are fixed here, once
 * Camera2 reports `LENS_POSE_ROTATION` as four coefficients **`[x, y, z, w]`** (scalar last) describing
 * the rotation **from the Android sensor coordinate system `S` to the camera-aligned frame `Cphys`**
 * (X along the sensor's long side, Y along its short side, Z along the optical axis). The only parser
 * is [parseCamera2LensPoseRotation]; it returns a [Camera2LensPoseRotation] whose matrix is named
 * [Camera2LensPoseRotation.rCphysFromS] and whose inverse is the separately named
 * [Camera2LensPoseRotation.rSFromCphys], so the two directions cannot be swapped silently.
 *
 * Any helper that wants scalar-first `(w, x, y, z)` must go through [camera2XyzwToWxyz] — the one,
 * explicitly named reorder adapter. Feeding the raw array to a `(w, x, y, z)` consumer is exactly the
 * misreading the regression tests prove wrong.
 *
 * ## Not proof of pixel geometry
 * A platform pose says how the physical camera is mounted relative to `S`. It says nothing about the
 * analysis buffer's projection domain (Group B) or distortion state (Group C), and nothing here reads
 * pixels.
 */

/** A 3×3 row-major rotation matrix with an explicit frame pair in every name that holds one. */
internal data class Pts03Matrix3(
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
    fun apply(v: Pts03Vector3): Pts03Vector3 =
        Pts03Vector3(
            m00 * v.x + m01 * v.y + m02 * v.z,
            m10 * v.x + m11 * v.y + m12 * v.z,
            m20 * v.x + m21 * v.y + m22 * v.z,
        )

    fun transpose(): Pts03Matrix3 = Pts03Matrix3(m00, m10, m20, m01, m11, m21, m02, m12, m22)

    fun toRowMajorList(): List<Double> = listOf(m00, m01, m02, m10, m11, m12, m20, m21, m22)
}

internal data class Pts03Vector3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun norm(): Double = sqrt(x * x + y * y + z * z)

    fun dot(o: Pts03Vector3): Double = x * o.x + y * o.y + z * o.z

    fun toList(): List<Double> = listOf(x, y, z)
}

/** Scalar-first quaternion, for consumers that need it. Only ever built by [camera2XyzwToWxyz]. */
internal data class Pts03QuaternionWxyz(
    val w: Double,
    val x: Double,
    val y: Double,
    val z: Double,
)

/** The one reorder adapter from Camera2's `[x, y, z, w]` to a scalar-first quaternion. */
internal fun camera2XyzwToWxyz(xyzw: List<Float>): Pts03QuaternionWxyz {
    require(xyzw.size == 4) { "Camera2 LENS_POSE_ROTATION must have 4 coefficients; had ${xyzw.size}" }
    return Pts03QuaternionWxyz(
        w = xyzw[3].toDouble(),
        x = xyzw[0].toDouble(),
        y = xyzw[1].toDouble(),
        z = xyzw[2].toDouble(),
    )
}

/**
 * Rotation matrix of a unit quaternion (Hamilton convention). The matrix maps a vector expressed in the
 * quaternion's source frame into its destination frame.
 */
internal fun Pts03QuaternionWxyz.toRotationMatrix(): Pts03Matrix3 =
    Pts03Matrix3(
        1 - 2 * (y * y + z * z),
        2 * (x * y - z * w),
        2 * (x * z + y * w),
        2 * (x * y + z * w),
        1 - 2 * (x * x + z * z),
        2 * (y * z - x * w),
        2 * (x * z - y * w),
        2 * (y * z + x * w),
        1 - 2 * (x * x + y * y),
    )

/** A parsed, validated platform pose rotation for one physical camera. */
internal data class Camera2LensPoseRotation(
    val rawXyzw: List<Float>,
    val quaternionWxyz: Pts03QuaternionWxyz,
    /** `R_Cphys_from_S`: maps a vector in the Android sensor frame `S` into the camera-aligned frame `Cphys`. */
    val rCphysFromS: Pts03Matrix3,
) {
    /** `R_S_from_Cphys`: the inverse (transpose) direction. */
    val rSFromCphys: Pts03Matrix3 get() = rCphysFromS.transpose()

    /** The camera-aligned +Z (optical) axis expressed in `S`. */
    val cameraZAxisInS: Pts03Vector3 get() = rSFromCphys.apply(Pts03Vector3(0.0, 0.0, 1.0))

    /** The camera-aligned +X (sensor long side) axis expressed in `S`. */
    val cameraXAxisInS: Pts03Vector3 get() = rSFromCphys.apply(Pts03Vector3(1.0, 0.0, 0.0))

    /** The camera-aligned +Y (sensor short side) axis expressed in `S`. */
    val cameraYAxisInS: Pts03Vector3 get() = rSFromCphys.apply(Pts03Vector3(0.0, 1.0, 0.0))
}

/** Why a reported pose rotation could not be parsed. */
internal enum class Pts03LensPoseRotationDefect {
    ABSENT,
    WRONG_COEFFICIENT_COUNT,
    NON_FINITE_COEFFICIENT,
    NOT_UNIT_NORM,
}

internal sealed interface Camera2LensPoseRotationParse {
    data class Parsed(
        val rotation: Camera2LensPoseRotation,
    ) : Camera2LensPoseRotationParse

    data class Invalid(
        val defect: Pts03LensPoseRotationDefect,
        val detail: String,
    ) : Camera2LensPoseRotationParse
}

/** Unit-norm tolerance: a float32 quaternion from a HAL is unit to ~1e-6; 1e-3 rejects only real defects. */
internal const val PTS03_QUATERNION_UNIT_NORM_TOLERANCE: Double = 1e-3

/**
 * Parses Camera2 `LENS_POSE_ROTATION` **`[x, y, z, w]`** into `R_Cphys_from_S`. A malformed value is
 * reported as [Camera2LensPoseRotationParse.Invalid], never normalised into something plausible.
 */
internal fun parseCamera2LensPoseRotation(xyzw: List<Float>?): Camera2LensPoseRotationParse {
    if (xyzw ==
        null
    ) {
        return Camera2LensPoseRotationParse.Invalid(
            Pts03LensPoseRotationDefect.ABSENT,
            "LENS_POSE_ROTATION not reported",
        )
    }
    if (xyzw.size != 4) {
        return Camera2LensPoseRotationParse.Invalid(
            Pts03LensPoseRotationDefect.WRONG_COEFFICIENT_COUNT,
            "expected 4 coefficients [x,y,z,w]; had ${xyzw.size}",
        )
    }
    if (xyzw.any { !it.isFinite() }) {
        return Camera2LensPoseRotationParse.Invalid(
            Pts03LensPoseRotationDefect.NON_FINITE_COEFFICIENT,
            "coefficients=$xyzw",
        )
    }
    val q = camera2XyzwToWxyz(xyzw)
    val norm = sqrt(q.w * q.w + q.x * q.x + q.y * q.y + q.z * q.z)
    if (abs(norm - 1.0) > PTS03_QUATERNION_UNIT_NORM_TOLERANCE) {
        return Camera2LensPoseRotationParse.Invalid(Pts03LensPoseRotationDefect.NOT_UNIT_NORM, "norm=$norm")
    }
    return Camera2LensPoseRotationParse.Parsed(Camera2LensPoseRotation(xyzw.toList(), q, q.toRotationMatrix()))
}

/** Master recon §14.1 extrinsic provenance statuses for `R_Cphys_from_S(id)`. */
internal enum class Pts03ExtrinsicProvenance {
    CALIBRATED_PLATFORM_POSE,
    PLATFORM_POSE_PRESENT_BUT_UNVERIFIED,
    PLATFORM_POSE_UNUSABLE,
    NOMINAL_FALLBACK,
    OPTICALLY_REFINED,
}

/**
 * An **independent** device-frame check: a measurement that compares the raw Android sensor frame `S`
 * with the camera's optical orientation (for example a star solution paired with a trusted sensor
 * attitude). A printed-target pose is **not** one: it gives the camera's pose relative to the target,
 * which reveals nothing about `S`. PTS-03 has no such check and never constructs this type.
 */
internal data class Pts03IndependentDeviceFrameAgreement(
    val referenceDescription: String,
    val angularDisagreementDeg: Double,
    val toleranceDeg: Double,
)

/** Per-physical-ID Group D evidence. */
internal data class Pts03ExtrinsicEvidence(
    val cameraId: String,
    val rawRotationXyzw: List<Float>?,
    val rawTranslationMeters: List<Float>?,
    val reference: Pts03EnumValue?,
    val rotation: Camera2LensPoseRotation?,
    val rotationDefect: Pts03LensPoseRotationDefect?,
    /** Status of the platform pose itself. */
    val platformPoseStatus: Pts03ExtrinsicProvenance,
    /** What PointToSky's prediction actually uses today — always the nominal axes; PTS-03 changes nothing. */
    val extrinsicInUseByPointToSky: Pts03ExtrinsicProvenance,
    /** Angle between the platform camera +Z axis (in `S`) and the nominal rear optical axis `S` −Z. Informational. */
    val angleCameraZToNominalRearOpticalAxisDeg: Double?,
    val reasons: List<String>,
)

/** `LENS_POSE_REFERENCE` values whose rotation is relative to the Android sensor frame `S`. */
private val REFERENCES_QUALIFYING_S: Set<String> = setOf("PRIMARY_CAMERA", "GYROSCOPE")

/**
 * Classifies one physical camera's platform pose. Without an [independentAgreement] the best possible
 * outcome is [Pts03ExtrinsicProvenance.PLATFORM_POSE_PRESENT_BUT_UNVERIFIED] — a low printed-target
 * reprojection error never upgrades it.
 */
internal fun classifyPts03Extrinsics(
    characteristics: Pts03CameraStaticCharacteristics,
    independentAgreement: Pts03IndependentDeviceFrameAgreement? = null,
): Pts03ExtrinsicEvidence {
    val reasons = mutableListOf<String>()
    val parse = parseCamera2LensPoseRotation(characteristics.lensPoseRotationXyzw)
    val rotation = (parse as? Camera2LensPoseRotationParse.Parsed)?.rotation
    val defect = (parse as? Camera2LensPoseRotationParse.Invalid)?.defect
    val referenceName = characteristics.lensPoseReference?.name

    val status =
        when {
            parse is Camera2LensPoseRotationParse.Invalid -> {
                reasons += "LENS_POSE_ROTATION unusable: ${parse.defect} (${parse.detail})"
                Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE
            }
            referenceName == null -> {
                reasons += "LENS_POSE_REFERENCE not reported: the pose's frame is unqualified"
                Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE
            }
            referenceName !in REFERENCES_QUALIFYING_S -> {
                reasons +=
                    "LENS_POSE_REFERENCE=$referenceName is not a reference relative to the Android sensor frame S"
                Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE
            }
            independentAgreement == null -> {
                reasons +=
                    "syntactically valid pose with reference=$referenceName; no independent device-frame " +
                    "reference compared S with the optical orientation (a printed-target pose cannot), " +
                    "so the pose stays unverified; later star/sensor evidence is required"
                Pts03ExtrinsicProvenance.PLATFORM_POSE_PRESENT_BUT_UNVERIFIED
            }
            independentAgreement.angularDisagreementDeg <= independentAgreement.toleranceDeg -> {
                reasons +=
                    "consistent with ${independentAgreement.referenceDescription} " +
                    "within ${independentAgreement.toleranceDeg} deg"
                Pts03ExtrinsicProvenance.CALIBRATED_PLATFORM_POSE
            }
            else -> {
                reasons +=
                    "inconsistent with ${independentAgreement.referenceDescription}: " +
                    "${independentAgreement.angularDisagreementDeg} deg > ${independentAgreement.toleranceDeg} deg"
                Pts03ExtrinsicProvenance.PLATFORM_POSE_UNUSABLE
            }
        }
    if (characteristics.lensPoseTranslationMeters != null) {
        reasons += "LENS_POSE_TRANSLATION recorded only; irrelevant for directions to stars at infinity"
    }

    val nominalRearOpticalAxis = Pts03Vector3(0.0, 0.0, -1.0)
    val angle =
        rotation?.cameraZAxisInS?.let { z ->
            Math.toDegrees(acos((z.dot(nominalRearOpticalAxis) / z.norm()).coerceIn(-1.0, 1.0)))
        }

    return Pts03ExtrinsicEvidence(
        cameraId = characteristics.cameraId,
        rawRotationXyzw = characteristics.lensPoseRotationXyzw,
        rawTranslationMeters = characteristics.lensPoseTranslationMeters,
        reference = characteristics.lensPoseReference,
        rotation = rotation,
        rotationDefect = defect,
        platformPoseStatus = status,
        extrinsicInUseByPointToSky = Pts03ExtrinsicProvenance.NOMINAL_FALLBACK,
        angleCameraZToNominalRearOpticalAxisDeg = angle,
        reasons = reasons,
    )
}
