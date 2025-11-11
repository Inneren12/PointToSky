package dev.pointtosky.wear.aim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Picker
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.rememberPickerState
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.location.orchestrator.DefaultLocationOrchestrator
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.time.SystemTimeSource
import dev.pointtosky.wear.R
import dev.pointtosky.wear.aim.core.AimController
import dev.pointtosky.wear.aim.core.AimPhase
import dev.pointtosky.wear.aim.core.AimTarget
import dev.pointtosky.wear.aim.core.AimTarget.BodyTarget
import dev.pointtosky.wear.aim.core.AimTarget.EquatorialTarget
import dev.pointtosky.wear.aim.core.DefaultAimController
import dev.pointtosky.wear.aim.offline.offlineStarResolver
import dev.pointtosky.wear.datalayer.AimLaunchRequest
import dev.pointtosky.wear.haptics.HapticEvent
import dev.pointtosky.wear.haptics.HapticPolicy
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import dev.pointtosky.wear.settings.AimIdentifySettingsDataStore
import kotlin.math.abs
import kotlin.math.sign

// Элементы пикера делаем top-level
private data class UiTarget(val label: String, val toAim: AimTarget)

/** Route: создаёт контроллер и рендерит AimScreen. */
@Composable
fun AimRoute(
    orientationRepository: OrientationRepository,
    locationRepository: DefaultLocationOrchestrator,
    externalAim: AimLaunchRequest? = null,
    initialTarget: AimTarget? = null,
) {
    // val appContext = LocalContext.current.applicationContext
    val context = LocalContext.current.applicationContext
    val controller = remember(orientationRepository, locationRepository) {
        DefaultAimController(
            orientation = orientationRepository,
            location = locationRepository,
            time = SystemTimeSource(),
            ephem = SimpleEphemerisComputer(),
            raDecToAltAz = { eq, lstDeg, latDeg ->
                raDecToAltAz(eq, lstDeg, latDeg, applyRefraction = false)
            },
            starResolver = offlineStarResolver(context),
        )
    }
    LaunchedEffect(externalAim?.seq) {
        externalAim?.let { controller.setTarget(it.target) }
    }
    AimScreen(aimController = controller, initialTarget = initialTarget)
}

/** Экран «Найти»: стрелка/шкала, фазы, confidence, picker целей, haptics+a11y. */
@Composable
fun AimScreen(aimController: AimController, initialTarget: AimTarget?) {
    // lifecycle: start/stop
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(aimController, lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> aimController.start()
                Lifecycle.Event.ON_PAUSE -> aimController.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // state
    val state by aimController.state.collectAsStateWithLifecycle()

    // set initial target once
    var appliedInitial by remember { mutableStateOf(false) }
    LaunchedEffect(initialTarget) {
        if (!appliedInitial && initialTarget != null) {
            aimController.setTarget(initialTarget)
            appliedInitial = true
        }
    }

    // Haptics & settings
    val context = LocalContext.current
    val appContext = context.applicationContext
    val settings = remember(appContext) { AimIdentifySettingsDataStore(appContext) }
    val hapticEnabled by settings.aimHapticEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val haptics = remember(appContext) { HapticPolicy(appContext) }
    val lockText = remember { context.getString(R.string.a11y_lock_captured) } // ← НЕ @Composable
    val view = LocalView.current

    var prevPhase by remember { mutableStateOf(state.phase) }
    LaunchedEffect(state.phase, hapticEnabled) {
        when {
            prevPhase != state.phase && state.phase == AimPhase.IN_TOLERANCE -> {
                haptics.play(HapticEvent.ENTER, hapticEnabled)
            }
            prevPhase != state.phase && state.phase == AimPhase.LOCKED -> {
                haptics.play(HapticEvent.LOCK, hapticEnabled)
                view.announceForAccessibility(lockText)
            }
            prevPhase != state.phase && state.phase == AimPhase.SEARCHING -> {
                haptics.play(HapticEvent.LOST, hapticEnabled)
            }
        }
        prevPhase = state.phase
    }

    // visuals + a11y strings
    val azAbs = abs(state.dAzDeg).toFloat()
    val altAbs = abs(state.dAltDeg).toFloat()
    val azRight = sign(state.dAzDeg).toFloat() >= 0f
    val altUp = sign(state.dAltDeg).toFloat() >= 0f
    val arrowCd = stringResource(
        id = R.string.a11y_aim_arrow,
        if (azRight) stringResource(R.string.a11y_right) else stringResource(R.string.a11y_left),
        azAbs,
    )
    val altCd = stringResource(
        id = R.string.a11y_alt_scale,
        if (altUp) stringResource(R.string.a11y_up) else stringResource(R.string.a11y_down),
        altAbs,
    )

    // targets
    val polarisEq = Equatorial(raDeg = 37.95456067, decDeg = 89.26410897)
    val options = remember {
        listOf(
            UiTarget("SUN", BodyTarget(Body.SUN)),
            UiTarget("MOON", BodyTarget(Body.MOON)),
            UiTarget("JUPITER", BodyTarget(Body.JUPITER)),
            UiTarget("SATURN", BodyTarget(Body.SATURN)),
            UiTarget("POLARIS", EquatorialTarget(polarisEq)),
        )
    }
    val initialIndex = remember(initialTarget) { targetIndexFor(initialTarget) }
    val pickerState = rememberPickerState(
        initialNumberOfOptions = options.size,
        initiallySelectedOption = initialIndex,
        repeatItems = true,
    )
    LaunchedEffect(pickerState, options) {
        snapshotFlow { pickerState.selectedOption }.collect { idx ->
            val opt = options[idx % options.size]
            aimController.setTarget(opt.toAim)
            // aim_target_changed {target} (UI-originated)
            LogBus.d(
                tag = "Aim",
                msg = "aim_target_changed",
                payload = mapOf("target" to opt.label),
            )
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("AimRoot"),
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TopBar(
                phase = state.phase,
                confidence = state.confidence.coerceIn(0f, 1f),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AltScaleWithA11y(
                    absAltDeg = altAbs,
                    dirUp = altUp,
                    height = 110.dp,
                    width = 14.dp,
                    contentDesc = altCd,
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = arrowCd },
                ) {
                    TurnArrow(
                        right = azRight,
                        emphasized = state.phase != AimPhase.SEARCHING,
                        size = 150.dp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "│ΔAz│ " + formatDeg(azAbs),
                        style = MaterialTheme.typography.title3,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.width(14.dp))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Target",
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.caption2,
                )
                Picker(
                    state = pickerState,
                    contentDescription = "Aim Target",
                ) { index ->
                    val item = options[index % options.size]
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.title3.copy(fontWeight = FontWeight.Medium),
                        modifier = Modifier
                            .width(180.dp)
                            .semantics { contentDescription = "Target ${item.label}" },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// -------- pieces --------

@Composable
private fun TopBar(phase: AimPhase, confidence: Float) {
    val confPct = (confidence.coerceIn(0f, 1f) * 100).toInt()
    val confCd = stringResource(id = R.string.a11y_confidence, confPct)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhaseBadge(phase)
        ConfidenceIndicator(confidence = confidence, contentDesc = confCd)
    }
}

@Composable
private fun PhaseBadge(phase: AimPhase) {
    val (bg, fg, text) = when (phase) {
        AimPhase.SEARCHING -> Triple(Color(0x334A4A4A), Color(0xFFB0B0B0), "SEARCHING")
        AimPhase.IN_TOLERANCE -> Triple(Color(0x33F4D03F), Color(0xFFF4D03F), "IN")
        AimPhase.LOCKED -> Triple(Color(0x332ECC71), Color(0xFF2ECC71), "LOCKED")
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { contentDescription = "Phase $text" },
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}

@Composable
private fun ConfidenceIndicator(confidence: Float, contentDesc: String) {
    val clamped = confidence.coerceIn(0f, 1f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { contentDescription = contentDesc },
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = clamped,
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp,
            )
            Text(
                text = "${(clamped * 100).toInt()}",
                style = MaterialTheme.typography.caption3,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "conf",
            style = MaterialTheme.typography.caption3,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun AltScale(absAltDeg: Float, dirUp: Boolean, height: Dp, width: Dp) {
    val frac = (absAltDeg / 90f).coerceIn(0f, 1f)
    val track = MaterialTheme.colors.onBackground.copy(alpha = 0.12f)
    val fill = if (dirUp) MaterialTheme.colors.secondary else MaterialTheme.colors.error

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics { /* contentDescription задаётся снаружи */ },
    ) {
        Text(
            text = (if (dirUp) "↑" else "↓") + " │ΔAlt│ " + formatDeg(absAltDeg),
            style = MaterialTheme.typography.caption2,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(6.dp))
                .background(track),
        ) {
            Box(
                modifier = Modifier
                    .align(if (dirUp) Alignment.TopCenter else Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(frac)
                    .background(fill),
            )
        }
    }
}

@Composable
private fun AltScaleWithA11y(absAltDeg: Float, dirUp: Boolean, height: Dp, width: Dp, contentDesc: String) {
    Box(Modifier.semantics { contentDescription = contentDesc }) {
        AltScale(absAltDeg, dirUp, height, width)
    }
}

@Composable
private fun TurnArrow(right: Boolean, emphasized: Boolean, size: Dp) {
    val color = if (emphasized) {
        MaterialTheme.colors.primary
    } else {
        MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
    }
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cw = kotlin.math.min(w, h)
        val arrowLength = (cw * 0.8f)
        val arrowHeight = (cw * 0.35f)
        val centerY = h / 2f
        val startX = (w - arrowLength) / 2f
        val endX = startX + arrowLength

        val path = Path()
        if (right) {
            path.moveTo(startX, centerY - arrowHeight / 2f)
            path.lineTo(endX, centerY)
            path.lineTo(startX, centerY + arrowHeight / 2f)
            path.close()
        } else {
            path.moveTo(endX, centerY - arrowHeight / 2f)
            path.lineTo(startX, centerY)
            path.lineTo(endX, centerY + arrowHeight / 2f)
            path.close()
        }
        drawPath(path = path, color = color)
    }
}

private fun formatDeg(value: Float): String {
    val s = java.lang.String.format(java.util.Locale.US, "%.1f°", value)
    return s.replace(".0°", "°")
}

private fun targetIndexFor(initial: AimTarget?): Int {
    if (initial == null) return 0
    return when (initial) {
        is BodyTarget -> when {
            initial.body == Body.SUN -> 0
            initial.body == Body.MOON -> 1
            initial.body == Body.JUPITER -> 2
            initial.body == Body.SATURN -> 3
            else -> 0
        }
        is EquatorialTarget -> 4 // Polaris
        is AimTarget.StarTarget -> 4 // фолбэк: как экваториальная цель (Polaris slot)
    }
}
