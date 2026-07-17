package dev.pointtosky.mobile.ar.camera

/**
 * CAM-2c physical-camera provenance experiment (`internalDebug` only). Camera2/CameraX's own
 * `UnsupportedLogicalMultiCameraMapping` guard (`AnalysisBufferIntrinsicsResolver.kt`) exists because
 * a logical camera's *own* `CameraCharacteristics` cannot be trusted to describe whichever physical
 * sensor actually produced a given `ImageAnalysis` buffer. This file does not weaken that guard - the
 * ordinary/implicit session path (no explicit physical binding) still hits it exactly as before. What
 * this file adds is a *narrow, additive, explicitly-verified* path: when a caller has bound a specific
 * physical camera via `CameraSelector.Builder().setPhysicalCameraId(...)` (CameraX >= 1.4.0-beta01)
 * and read `CameraCharacteristics` from *that exact physical Camera2 ID* (never the logical camera's
 * aggregated characteristics), the resulting snapshot's own `cameraId` is the physical ID and its own
 * `isLogicalMultiCamera` is expected `false` - a genuine physical sub-camera is not itself a logical
 * multi-camera - so it simply does not trigger the existing guard at all. This file's only job is to
 * *prove* that identity before treating the snapshot as trustworthy, and to name every way that proof
 * can fail with a typed result instead of a guess.
 */

/** How a physical-camera binding was established. Exactly one method exists at CameraX 1.4.2. */
enum class PhysicalCameraBindingMethod {
    /** `CameraSelector.Builder.setPhysicalCameraId(String)`, confirmed present in the pinned
     * `androidx.camera:camera-camera2:1.4.2` API surface (`javap` inspection of the resolved AAR;
     * see `docs/camera_coordinate_calibration_contract.md` for the exact evidence). */
    CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
}

/** How confident this codebase is that [PhysicalCameraProvenance.physicalCameraId] is the camera that
 * actually produced the characteristics (and, by construction of the bind, the analyzed frames) this
 * session uses. Exactly one level exists today - CAM-2c integration never accepts less. */
enum class PhysicalCameraProvenanceConfidence {
    /**
     * Either the `CameraInfo` CameraX handed back from binding is itself backed by the requested
     * physical camera ([PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL]), or the bound
     * logical camera's own `CameraInfo.getPhysicalCameraInfos()` set contains a physical `CameraInfo`
     * whose `Camera2CameraInfo.from(it).cameraId` exactly equals the requested physical camera ID
     * ([PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO]). Either way,
     * `CameraCharacteristics` were read directly from *that* physical `CameraInfo` (never a logical
     * camera's) - confirming the snapshot's own `cameraId` matches and its own `isLogicalMultiCamera`
     * reads `false`. This is a same-session, characteristics-identity check, not a live/per-frame
     * guarantee - see `PhysicalCameraProvenance`'s own KDoc "Scope" note.
     */
    VERIFIED_BY_CHARACTERISTICS_IDENTITY,
}

/**
 * Which `CameraInfo` shape a verified physical binding's characteristics were actually read from
 * (fix for a correctness gap: `resolvePhysicalCameraBindingFromCameraInfo` previously assumed the
 * `CameraInfo` CameraX hands back from binding is always the *logical* camera's own, and searched only
 * its `physicalCameraInfos` — CameraX does not document that the returned `CameraInfo` is never itself
 * already the physical one). Never inferred from ID ordering — always the exact identity comparison
 * [selectPhysicalCameraInfoSource] performs.
 */
enum class PhysicalCameraBindingSource {
    /** `Camera2CameraInfo.from(boundCameraInfo).cameraId` already equals the requested physical
     * candidate — the bound `CameraInfo` itself is backed by the physical sensor. No separate logical
     * `CameraInfo` was ever identified in this shape, so [PhysicalCameraProvenance.logicalCameraId] is
     * `null` for this source (never fabricated as equal to the physical ID). */
    BOUND_CAMERA_INFO_IS_PHYSICAL,

    /** The bound `CameraInfo` is the logical camera's own, and the requested physical candidate was
     * found among its declared `getPhysicalCameraInfos()` set — the classic logical-multi-camera shape
     * this experiment was originally built around. */
    MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
}

/**
 * One coherent provenance tuple (task §5) tying a CAM-2c `AnalysisBuffer` resolution to a single,
 * explicitly selected, and characteristics-verified physical Camera2 sensor.
 *
 * [logicalCameraId] is **nullable and never fabricated**: it is the bound logical camera's own Camera2
 * ID when [bindingSource] is [PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO] and
 * that ID could actually be read; it is `null` — genuinely unavailable, never silently substituted with
 * [physicalCameraId] — whenever [bindingSource] is [PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL]
 * (no separate logical `CameraInfo` was ever identified in that shape) or the logical ID simply could
 * not be read. A caller/report must distinguish "logical camera ID known" from "logical camera ID
 * unavailable" — never present a fabricated equality as if it were observed.
 *
 * **Scope - session-scoped identity, not a live per-frame guarantee.** CameraX 1.4.2's `CameraInfo`
 * exposes `getPhysicalCameraInfos()` as a static `Set`, not an observable/`LiveData` of the *currently
 * active* physical camera, and no capture-result-level physical-camera-identity callback exists in
 * this codebase's pinned CameraX version (confirmed by `javap` inspection of the resolved API jar -
 * no such method exists on `CameraInfo`, `Camera`, or `Camera2CameraInfo`). This [PhysicalCameraProvenance]
 * therefore proves identity *once*, at bind time, from the characteristics this resolution actually
 * used - it is not a proof that the same physical sensor produced every frame across the session's
 * entire lifetime. The physical-camera-binding experiment *mitigates* this practically (task §9) by
 * fixing the zoom ratio and never enabling extensions/effects for the experiment session - not a proof
 * of frame-level stability, a documented, honest limitation instead; it does not claim to prevent every
 * possible OEM physical-camera switch, only to remove the one trigger (zoom) this codebase can control.
 */
data class PhysicalCameraProvenance(
    val logicalCameraId: String?,
    val physicalCameraId: String,
    val bindingMethod: PhysicalCameraBindingMethod,
    val bindingSource: PhysicalCameraBindingSource,
    val confidence: PhysicalCameraProvenanceConfidence,
)

/** Typed outcome of establishing (and verifying) a physical-camera binding. No variant here is ever
 * silently substituted for another - a caller/test can always tell exactly why a physical-camera
 * session did not reach [Bound]. */
sealed interface PhysicalCameraBindingResolution {
    /** Binding succeeded and its identity was proven: [physicalCharacteristicsSnapshot] was read
     * directly from the requested physical camera's own `CameraInfo`, its own `cameraId` matches
     * [PhysicalCameraProvenance.physicalCameraId], and it does not itself report
     * `isLogicalMultiCamera`. This is the *only* snapshot CAM-2c integration is allowed to resolve
     * intrinsics from for a physical-camera session (task §5/§6). */
    data class Bound(
        val provenance: PhysicalCameraProvenance,
        val physicalCharacteristicsSnapshot: CameraCharacteristicsSnapshot,
    ) : PhysicalCameraBindingResolution

    /** `ProcessCameraProvider.bindToLifecycle` with an explicit physical [androidx.camera.core.CameraSelector]
     * threw, or the resulting `CameraInfo` was never obtained. [reason] is a short, non-device-specific
     * code (see `CameraPreview.kt`'s `CameraBindFailureReason`), never a raw exception message. */
    data class PhysicalCameraBindingUnavailable(val reason: String) : PhysicalCameraBindingResolution

    /** The bound logical camera's `getPhysicalCameraInfos()` set does not contain any physical
     * `CameraInfo` whose own Camera2 ID equals the requested candidate - the `CameraSelector`-level
     * bind "succeeded", but this codebase cannot find the physical `CameraInfo` needed to read that
     * candidate's own characteristics, so its identity is unverified rather than proven. */
    data object PhysicalCameraIdentityUnverified : PhysicalCameraBindingResolution

    /** A physical `CameraInfo` matching the requested ID was found, but reading its characteristics
     * either failed, reported a different `cameraId` than requested, or itself reported
     * `isLogicalMultiCamera = true` (never expected for a genuine physical sub-camera - if it happens,
     * this codebase does not trust the characteristics enough to treat them as a proven, single-sensor
     * source). [actualCameraId] is `null` when the snapshot itself could not be read at all. */
    data class PhysicalCameraCharacteristicsMismatch(
        val expectedPhysicalCameraId: String,
        val actualCameraId: String?,
    ) : PhysicalCameraBindingResolution
}

/**
 * Pure, testable core of the physical-camera provenance check (task §5/§6): given the logical
 * camera's own Camera2 ID (if known — `null` when genuinely unavailable, never fabricated), the
 * requested physical camera ID, [bindingSource] (which `CameraInfo` shape the caller matched — see
 * [PhysicalCameraBindingSource]), and the [CameraCharacteristicsSnapshot] read directly from the
 * physical `CameraInfo` matching that ID (or `null` if no such `CameraInfo` could be found or read),
 * returns the exact typed outcome. No Android/CameraX types appear in this function's signature, so it
 * is unit-testable with fake snapshots - the real-Android glue that finds the matching physical
 * `CameraInfo` and reads its characteristics lives in `resolvePhysicalCameraBindingFromCameraInfo`
 * (`PhysicalCameraBindingExperiment.kt`), which delegates every actual verification decision here.
 */
internal fun verifyPhysicalCameraProvenance(
    logicalCameraId: String?,
    requestedPhysicalCameraId: String,
    physicalCameraInfoFound: Boolean,
    physicalCharacteristicsSnapshot: CameraCharacteristicsSnapshot?,
    bindingSource: PhysicalCameraBindingSource,
): PhysicalCameraBindingResolution {
    if (!physicalCameraInfoFound) {
        return PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified
    }
    if (physicalCharacteristicsSnapshot == null) {
        return PhysicalCameraBindingResolution.PhysicalCameraCharacteristicsMismatch(
            expectedPhysicalCameraId = requestedPhysicalCameraId,
            actualCameraId = null,
        )
    }
    if (physicalCharacteristicsSnapshot.cameraId != requestedPhysicalCameraId ||
        physicalCharacteristicsSnapshot.isLogicalMultiCamera
    ) {
        return PhysicalCameraBindingResolution.PhysicalCameraCharacteristicsMismatch(
            expectedPhysicalCameraId = requestedPhysicalCameraId,
            actualCameraId = physicalCharacteristicsSnapshot.cameraId,
        )
    }
    return PhysicalCameraBindingResolution.Bound(
        provenance =
            PhysicalCameraProvenance(
                logicalCameraId = logicalCameraId,
                physicalCameraId = requestedPhysicalCameraId,
                bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
                bindingSource = bindingSource,
                confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
            ),
        physicalCharacteristicsSnapshot = physicalCharacteristicsSnapshot,
    )
}

/** Typed, pure decision of which `CameraInfo` shape [selectPhysicalCameraInfoSource] matched — the
 * real-Android glue in `resolvePhysicalCameraBindingFromCameraInfo` acts on this to decide which
 * `CameraInfo` to read characteristics from. */
internal sealed interface PhysicalCameraInfoSelection {
    /** [PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL]: read characteristics from the bound
     * `CameraInfo` directly. */
    data object UseBoundCameraInfoDirectly : PhysicalCameraInfoSelection

    /** [PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO]: read characteristics from
     * the nested physical `CameraInfo` matching the requested ID among the bound (logical)
     * `CameraInfo`'s own declared candidates. */
    data object UseDeclaredPhysicalCameraInfo : PhysicalCameraInfoSelection

    /** Neither shape matched — the bound `CameraInfo` is not itself the requested physical camera, and
     * none of its declared physical candidates are either. */
    data object NoMatch : PhysicalCameraInfoSelection
}

/**
 * Pure decision (fix for a correctness gap — task's "make bound CameraInfo resolution robust to both
 * CameraX shapes"): given the bound `CameraInfo`'s own Camera2 ID (if resolvable — `null` if it could
 * not be read), the requested physical camera ID, and the Camera2 IDs of every `CameraInfo` the bound
 * one declares via `getPhysicalCameraInfos()` (each already resolved to its own ID by the caller —
 * this function takes only plain `String`s, no Android/CameraX types, so it is unit-testable without
 * mocking `CameraInfo`/`Camera2CameraInfo`), determines which shape applies:
 *
 * - **Shape A** ([PhysicalCameraInfoSelection.UseBoundCameraInfoDirectly]): the bound `CameraInfo`
 *   itself is already backed by the requested physical camera.
 * - **Shape B** ([PhysicalCameraInfoSelection.UseDeclaredPhysicalCameraInfo]): the bound `CameraInfo`
 *   is the logical camera's own, and the requested ID is among its declared physical candidates.
 * - Neither ([PhysicalCameraInfoSelection.NoMatch]): the requested ID cannot be attributed to either
 *   shape.
 *
 * Shape A is checked first — an exact identity match on the bound `CameraInfo`'s own ID is never
 * ambiguous with a nested-candidate match, and checking it first means a device that (hypothetically)
 * reports itself as its own declared physical candidate still resolves to the more direct shape A
 * reading rather than an unnecessary nested one. Never infers correctness from ID ordering — both
 * checks are exact string-equality comparisons against real, previously-read Camera2 IDs.
 */
internal fun selectPhysicalCameraInfoSource(
    boundCameraInfoCamera2Id: String?,
    requestedPhysicalCameraId: String,
    declaredPhysicalCameraInfoIds: List<String>,
): PhysicalCameraInfoSelection =
    when {
        boundCameraInfoCamera2Id == requestedPhysicalCameraId -> PhysicalCameraInfoSelection.UseBoundCameraInfoDirectly
        declaredPhysicalCameraInfoIds.contains(requestedPhysicalCameraId) -> PhysicalCameraInfoSelection.UseDeclaredPhysicalCameraInfo
        else -> PhysicalCameraInfoSelection.NoMatch
    }
