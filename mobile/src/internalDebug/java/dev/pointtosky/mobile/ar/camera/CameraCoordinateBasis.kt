package dev.pointtosky.mobile.ar.camera

/**
 * `internalDebug`-only. CAM-2c dual-basis diagnostic (see `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md`):
 * an **explicit coordinate-basis identity** — which camera, in which role, in which coordinate space,
 * with which full rectangle — so no diagnostic in this slice can ever repeat the ambiguity the recon
 * flagged in `SensorToBufferDomainProof.ProvenActiveArrayLocal`'s name ("active-array-local" without
 * saying *whose* active array). Every dual-basis assessment, report line, and JSON field that mentions
 * a source rectangle carries one of these, never a bare width/height or an unqualified
 * "ACTIVE_ARRAY_LOCAL" label.
 *
 * This is a diagnostic identity model only: nothing here feeds [SensorToBufferDomainProof], and no
 * production resolution path consumes it.
 */

/** Which role the camera behind a [CameraCoordinateBasis] plays in one experiment binding attempt.
 * The recon's §2.3 finding is exactly why this distinction must be explicit: under
 * `CameraSelector.Builder.setPhysicalCameraId(...)`, CameraX 1.4.2 still opens the **logical** camera
 * (streams are routed per-`OutputConfiguration`), so the camera whose active array CameraX's own
 * matrix construction reads ([OPENED_LOGICAL_CAMERA]) is *not* the camera whose characteristics the
 * CAM-2c physical path resolves intrinsics from ([SELECTED_PHYSICAL_CAMERA]). */
internal enum class CameraBasisRole {
    /** The camera CameraX actually opened for this binding attempt — the one whose
     * `SENSOR_INFO_ACTIVE_ARRAY_SIZE` rect `CameraUseCaseAdapter.calculateSensorToBufferTransformMatrix`
     * (CameraX 1.4.2, recon §2.1–§2.2) reads via `getSensorRect()`. On the Pixel 9 experiment this is
     * expected to be logical camera `"0"` even when a physical candidate was requested. */
    OPENED_LOGICAL_CAMERA,

    /** The explicitly selected physical sub-camera this experiment requested via
     * `setPhysicalCameraId(...)` and whose own characteristics
     * `resolvePhysicalCameraBindingFromCameraInfo` verified. Its active array is a *candidate* source
     * basis for the observed matrix — never assumed equal to the opened logical camera's. */
    SELECTED_PHYSICAL_CAMERA,
}

/** Which Camera2 coordinate space a [CameraCoordinateBasis.rect] lives in. Only rect-bearing spaces
 * this diagnostic actually captures are listed; each value names the space precisely so a report can
 * never say just "active array" without saying which representation. */
internal enum class CameraBasisCoordinateSpace {
    /** `SENSOR_INFO_ACTIVE_ARRAY_SIZE` as reported — a `Rect` positioned relative to the full pixel
     * array, **including** its native `left`/`top` offsets. This is the representation CameraX 1.4.2's
     * matrix construction consumes (`new RectF(fullSensorRect)`, recon §2.2), so it is the space every
     * dual-basis assessment in this slice evaluates. */
    ACTIVE_ARRAY_NATIVE,

    /** The same active-array rectangle re-originated to `(0, 0)` (offsets removed) — the space
     * `resolveAnalysisBufferIntrinsics`'s K-composition works in. Not directly captured by the
     * dual-basis diagnostic (it derives local width/height from [ACTIVE_ARRAY_NATIVE] instead), listed
     * so reports can name the distinction explicitly instead of conflating the two. */
    ACTIVE_ARRAY_LOCAL,

    /** `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` as reported (native position). A distinct space
     * from [ACTIVE_ARRAY_NATIVE] whenever geometric distortion correction is active. */
    PRE_CORRECTION_ACTIVE_ARRAY_NATIVE,

    /** `SENSOR_INFO_PIXEL_ARRAY_SIZE` — the full sensor pixel grid, origin `(0, 0)` by definition. */
    PIXEL_ARRAY,
}

/** Where a [CameraCoordinateBasis]'s metadata was actually read from — recorded so a report never has
 * to guess which read produced a rect. */
internal enum class CameraBasisMetadataSource {
    /** Read from the bound `CameraInfo` CameraX handed back from `bindToLifecycle` (via
     * `Camera2CharacteristicsSource`) — the opened camera's own characteristics. */
    BOUND_CAMERA_INFO_CHARACTERISTICS,

    /** Read from a nested physical `CameraInfo` found in the bound camera's declared
     * `getPhysicalCameraInfos()` set (via `Camera2CharacteristicsSource` on that nested info). */
    DECLARED_PHYSICAL_CAMERA_INFO_CHARACTERISTICS,

    /** Constructed directly in a unit test from fixture values — never a real device read. */
    TEST_FIXTURE,
}

/**
 * `internalDebug`-only. A plain, bounded rectangle in whichever [CameraBasisCoordinateSpace] its
 * owning [CameraCoordinateBasis] names — full native coordinates including `left`/`top` offsets,
 * never silently re-originated.
 */
internal data class CameraBasisRect(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
) {
    init {
        require(rightPx > leftPx && bottomPx > topPx) {
            "CameraBasisRect must be ordered and non-empty; was [$leftPx,$topPx — $rightPx,$bottomPx]"
        }
    }

    val widthPx: Int get() = rightPx - leftPx
    val heightPx: Int get() = bottomPx - topPx
}

/**
 * `internalDebug`-only. One fully-qualified coordinate-basis identity: camera ID + role + coordinate
 * space + the full native rect + where the metadata came from. The dual-basis diagnostic constructs
 * exactly two of these per assessed frame — one [CameraBasisRole.OPENED_LOGICAL_CAMERA], one
 * [CameraBasisRole.SELECTED_PHYSICAL_CAMERA] — and every derived number stays attached to its basis.
 */
internal data class CameraCoordinateBasis(
    val cameraId: String,
    val cameraRole: CameraBasisRole,
    val coordinateSpace: CameraBasisCoordinateSpace,
    val rect: CameraBasisRect,
    val metadataSource: CameraBasisMetadataSource,
) {
    /** Deterministic, fully-qualified label — never an unqualified space name. */
    fun label(): String =
        "${cameraRole.name}(cameraId=$cameraId, space=${coordinateSpace.name}, " +
            "rect=[${rect.leftPx},${rect.topPx} — ${rect.rightPx},${rect.bottomPx}], " +
            "${rect.widthPx}x${rect.heightPx}, source=${metadataSource.name})"
}

/**
 * Builds the [CameraBasisCoordinateSpace.ACTIVE_ARRAY_NATIVE] basis for [snapshot] in [cameraRole],
 * or `null` when the snapshot's active-array rect is missing/degenerate — a typed absence the caller
 * must surface explicitly (e.g. as `INSUFFICIENT_INPUT`), never silently substitute.
 */
internal fun activeArrayNativeBasisOrNull(
    snapshot: CameraCharacteristicsSnapshot?,
    cameraRole: CameraBasisRole,
    metadataSource: CameraBasisMetadataSource,
): CameraCoordinateBasis? {
    val left = snapshot?.activeArrayLeftPx ?: return null
    val top = snapshot.activeArrayTopPx ?: return null
    val right = snapshot.activeArrayRightPx ?: return null
    val bottom = snapshot.activeArrayBottomPx ?: return null
    val cameraId = snapshot.cameraId ?: return null
    if (right <= left || bottom <= top) return null
    return CameraCoordinateBasis(
        cameraId = cameraId,
        cameraRole = cameraRole,
        coordinateSpace = CameraBasisCoordinateSpace.ACTIVE_ARRAY_NATIVE,
        rect = CameraBasisRect(leftPx = left, topPx = top, rightPx = right, bottomPx = bottom),
        metadataSource = metadataSource,
    )
}
