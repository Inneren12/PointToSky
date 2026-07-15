package dev.pointtosky.mobile.ar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayIntrinsicsMode

/**
 * CAM-2b (task hardening §3): the session-scoped debug toggle/mode state backing the predicted-star
 * overlay and the shared diagnostic HUD - `internalDebug`-only, never persisted. Every field starts at
 * its documented default and is reset to it whenever a *new debug session* begins - see
 * [rememberPredictedStarDebugControls] - never on every frame or every geometry observation within the
 * same session.
 *
 * "Session" here means CAM-1g's own `cameraGeometryDiagnosticSessionId` (`nextDebugSessionId()`), not
 * every camera rebind or geometry-observation emission: that ID is itself stable for the lifetime of one
 * `CameraGeometryDiagnosticsGate`-enabled composition (e.g. it changes when a tester leaves and
 * re-enters the AR screen, not on every frame). This class is deliberately plain state, not itself
 * `remember`ed - [rememberPredictedStarDebugControls] owns the `remember(sessionId)` keying that makes a
 * fresh instance (and therefore these defaults) replace the previous one on a session change.
 */
class PredictedStarDebugControlsState internal constructor(
    showPredictedStarMarkers: Boolean,
    showPredictedStarPanel: Boolean,
    showLegacyOverlay: Boolean,
    predictedStarIntrinsicsMode: PredictedStarOverlayIntrinsicsMode,
    hudDetailsExpanded: Boolean,
    predictedStarControlsExpanded: Boolean,
) {
    var showPredictedStarMarkers by mutableStateOf(showPredictedStarMarkers)
    var showPredictedStarPanel by mutableStateOf(showPredictedStarPanel)
    var showLegacyOverlay by mutableStateOf(showLegacyOverlay)
    var predictedStarIntrinsicsMode by mutableStateOf(predictedStarIntrinsicsMode)
    var hudDetailsExpanded by mutableStateOf(hudDetailsExpanded)
    var predictedStarControlsExpanded by mutableStateOf(predictedStarControlsExpanded)
}

/**
 * Remembers a [PredictedStarDebugControlsState] keyed to [sessionId]: a brand-new instance - and thus
 * the documented defaults below - replaces the previous one whenever [sessionId] changes, never on every
 * frame or geometry observation within the same session (task hardening §3's "Preferred safe diagnostic
 * policy"). [sessionId] is `0L` and constant for the lifetime of any composition where
 * `CameraGeometryDiagnosticsGate.isEnabled` is false, so this is a permanent no-op there - a
 * release/public build's behavior is exactly as if this function were never called.
 */
@Composable
fun rememberPredictedStarDebugControls(sessionId: Long): PredictedStarDebugControlsState =
    remember(sessionId) {
        PredictedStarDebugControlsState(
            showPredictedStarMarkers = true,
            showPredictedStarPanel = true,
            showLegacyOverlay = true,
            predictedStarIntrinsicsMode = PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS,
            hudDetailsExpanded = false,
            predictedStarControlsExpanded = false,
        )
    }
