package dev.pointtosky.mobile.ar.camera

/**
 * `internalDebug`-only. CAM-2c dual-basis diagnostic: a coherent snapshot of the **CameraX-opened
 * logical camera** behind one physical-binding attempt (recon
 * `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md` §2.3: under `setPhysicalCameraId(...)`,
 * CameraX 1.4.2 keeps the logical camera open and constructs the sensor-to-buffer matrix from *its*
 * active array — so the dual-basis diagnostic needs this camera's own characteristics alongside, and
 * strictly separate from, the selected physical camera's).
 *
 * ## Provenance rules (task §3)
 * The logical parent is identified **only** from the `CameraInfo` CameraX handed back from the same
 * `bindToLifecycle` call this attempt used — never by numeric ID ordering, never by
 * `DEFAULT_BACK_CAMERA` assumption, never by matching focal lengths or dimensions:
 * - When the bound `CameraInfo`'s own Camera2 ID differs from the requested physical ID and the
 *   requested ID appears among its declared `getPhysicalCameraInfos()` (the
 *   [PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO] shape), the bound `CameraInfo`
 *   **is** the opened logical parent — its characteristics are read directly
 *   ([OpenedLogicalCameraProvenance.BOUND_CAMERA_INFO_IS_OPENED_LOGICAL_PARENT]).
 * - When the bound `CameraInfo` is itself the requested physical camera
 *   ([PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL]), no separate logical parent
 *   `CameraInfo` was ever identified — the result is a typed [Unavailable], never a guess.
 */
internal sealed interface OpenedLogicalCameraSnapshotResolution {
    /** The opened logical camera was coherently identified from this attempt's own bound
     * `CameraInfo`, and its characteristics were read from exactly that `CameraInfo`. */
    data class Captured(
        val snapshot: CameraCharacteristicsSnapshot,
        val provenance: OpenedLogicalCameraProvenance,
    ) : OpenedLogicalCameraSnapshotResolution

    /** The opened logical parent could not be coherently identified (or its characteristics read) for
     * this attempt — a typed absence the diagnostic and exports surface explicitly. The dual-basis
     * assessment then runs with only the physical candidate basis; nothing is substituted. */
    data class Unavailable(val reason: String) : OpenedLogicalCameraSnapshotResolution
}

/** Exactly how the opened logical camera was identified. One value exists today — recorded
 * explicitly so exports never leave the identification method implicit. */
internal enum class OpenedLogicalCameraProvenance {
    /** The `CameraInfo` returned by this attempt's own `bindToLifecycle` call is the opened logical
     * parent (its own Camera2 ID differs from the requested physical ID, and the requested ID is
     * among its declared physical children). Characteristics were read from that exact
     * `CameraInfo` via `Camera2CharacteristicsSource`. */
    BOUND_CAMERA_INFO_IS_OPENED_LOGICAL_PARENT,
}

/**
 * `internalDebug`-only. The joint result of one binding attempt's identity resolution: the existing
 * physical-camera binding verification ([binding], unchanged semantics) plus the opened logical
 * camera's own snapshot resolution ([openedLogicalCamera]) — captured from the **same** bound
 * `CameraInfo` in the same callback, so the two can never come from different sessions/generations.
 * The two snapshots are deliberately separate fields with separate types: the logical snapshot is
 * never merged into, or substituted for, the physical one (task §4).
 */
internal data class DualBasisBindingResolution(
    val binding: PhysicalCameraBindingResolution,
    val openedLogicalCamera: OpenedLogicalCameraSnapshotResolution,
)
