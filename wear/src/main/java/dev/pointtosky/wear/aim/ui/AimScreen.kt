package dev.pointtosky.wear.aim.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import androidx.wear.tooling.preview.Devices
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.time.SystemTimeSource
import dev.pointtosky.core.location.orchestrator.DefaultLocationOrchestrator
import dev.pointtosky.wear.aim.core.AimController
import dev.pointtosky.wear.aim.core.AimPhase
import dev.pointtosky.wear.aim.core.AimTarget
import dev.pointtosky.wear.aim.core.AimTarget.BodyTarget
import dev.pointtosky.wear.aim.core.AimTarget.EquatorialTarget
import dev.pointtosky.wear.aim.core.DefaultAimController
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.snapshotFlow
import kotlin.math.abs
import kotlin.math.sign

/**
 * Route-компоновка: создаёт контроллер и рендерит AimScreen.
 * Без новых зависимостей: используем SystemTimeSource + SimpleEphemerisComputer.
 */
@Composable
fun AimRoute(
    orientationRepository: OrientationRepository,
    locationRepository: DefaultLocationOrchestrator,
    initialTarget: AimTarget? = null,
) {
    val controller = remember(orientationRepository, locationRepository) {
        DefaultAimController(
            orientation = orientationRepository,
            location = locationRepository,
            time = SystemTimeSource(),
            ephem = SimpleEphemerisComputer(),
            raDecToAltAz = { eq, lstDeg, latDeg -> raDecToAltAz(eq, lstDeg, latDeg, applyRefraction = false) }
        )
    }
    AimScreen(aimController = controller, initialTarget = initialTarget)
}

/**
 * Экран «Найти» (S6.B):
 *  - Большая стрелка (знак dAz показывает направление, текстом показываем │ΔAz│)
 *  - Вертикальная шкала │ΔAlt│ (стрелка ↑/↓ по знаку)
 *  - Бейдж фазы (SEARCHING / IN / LOCKED)
 *  - Индикатор confidence (0..1)
 *  - Нижний Picker целей: SUN / MOON / JUPITER / SATURN / POLARIS
 *  - Хаптик: ENTER→короткий, LOCK→тройной, LOST→средний (выключаемый toggle)
 */
@Composable
fun AimScreen(
    aimController: AimController,
    initialTarget: AimTarget?
) {
    // start()/stop() по onResume/onPause
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

    // Cостояние контроллера
    val state by aimController.state.collectAsStateWithLifecycle()

    // Разовая установка initialTarget (если задан)
    var appliedInitial by remember { mutableStateOf(false) }
    LaunchedEffect(initialTarget) {
        if (!appliedInitial && initialTarget != null) {
            aimController.setTarget(initialTarget)
            appliedInitial = true
        }
    }

    // Хаптик с отключаемым флагом
    var hapticEnabled by remember { mutableStateOf(true) }
    val haptic = LocalHapticFeedback.current
    var prevPhase by remember { mutableStateOf(state.phase) }
    LaunchedEffect(state.phase, hapticEnabled) {
        if (!hapticEnabled) {
            prevPhase = state.phase
            return@LaunchedEffect
        }
        when {
            prevPhase != state.phase && state.phase == AimPhase.IN_TOLERANCE -> {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) // ENTER
            }
            prevPhase != state.phase && state.phase == AimPhase.LOCKED -> {
                repeat(3) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) // LOCK (тройной)
                    delay(70)
                }
            }
            prevPhase != state.phase && state.phase == AimPhase.SEARCHING -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress) // LOST
            }
        }
        prevPhase = state.phase
    }

    // Читаемые величины
    val azAbs = abs(state.dAzDeg).toFloat()
    val altAbs = abs(state.dAltDeg).toFloat()
    val azRight = sign(state.dAzDeg).toFloat() >= 0f
    val altUp = sign(state.dAltDeg).toFloat() >= 0f

    // Меню целей
    data class UiTarget(val label: String, val toAim: AimTarget)
    val polarisEq = Equatorial(raDeg = 37.95456067, decDeg = 89.26410897) // Polaris (J2000)
    val options = remember {
        listOf(
            UiTarget("SUN", BodyTarget(Body.SUN)),
            UiTarget("MOON", BodyTarget(Body.MOON)),
            UiTarget("JUPITER", BodyTarget(Body.JUPITER)),
            UiTarget("SATURN", BodyTarget(Body.SATURN)),
            UiTarget("POLARIS", EquatorialTarget(polarisEq)),
        )
    }
    val initialIndex = remember(initialTarget) { targetIndexFor(initialTarget, options) }
    val pickerState = rememberPickerState(
        initialNumberOfOptions = options.size,
        initiallySelectedOption = initialIndex,
        repeatItems = true
    )
    // Прокрутка коронкой -> setTarget
    LaunchedEffect(pickerState, options) {
        snapshotFlow { pickerState.selectedOption }.collect { idx ->
            val opt = options[idx % options.size]
            aimController.setTarget(opt.toAim)
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("AimRoot"),
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) }
    ) { inset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inset)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Верхняя полоса: фаза, confidence, toggle хаптика
            TopBar(
                phase = state.phase,
                confidence = state.confidence.coerceIn(0f, 1f),
                hapticEnabled = hapticEnabled,
                onToggleHaptic = { hapticEnabled = it }
            )

            // Центр: шкала |ΔAlt| и большая стрелка |ΔAz|
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AltScale(
                    absAltDeg = altAbs,
                    dirUp = altUp,
                    height = 110.dp,
                    width = 14.dp,
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f)
                ) {
                    TurnArrow(
                        right = azRight,
                        emphasized = state.phase != AimPhase.SEARCHING,
                        size = 150.dp
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "│ΔAz│ " + formatDeg(azAbs),
                        style = MaterialTheme.typography.title3,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.width(14.dp)) // баланс с шириной шкалы
            }

            // Низ: Picker целей
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Target",
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.caption2
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
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// --------- составные элементы ----------

@Composable
private fun TopBar(
    phase: AimPhase,
    confidence: Float,
    hapticEnabled: Boolean,
    onToggleHaptic: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PhaseBadge(phase)
        ConfidenceIndicator(confidence)
        ToggleChip(
            checked = hapticEnabled,
            onCheckedChange = onToggleHaptic,
            label = { Text("Haptic") },
            toggleControl = {}, // без иконок, чтобы не тянуть доп. пакеты
        )
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
            .semantics { contentDescription = "Phase $text" }
    ) {
        Text(text = text, color = fg, style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun ConfidenceIndicator(confidence: Float) {
    val clamped = confidence.coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = clamped,
                modifier = Modifier.size(28.dp),
                strokeWidth = 3.dp
            )
            Text(text = "${(clamped * 100).toInt()}", style = MaterialTheme.typography.caption3)
        }
        Spacer(Modifier.height(2.dp))
        Text("conf", style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f))
    }
}

@Composable
private fun AltScale(absAltDeg: Float, dirUp: Boolean, height: Dp, width: Dp) {
    val frac = (absAltDeg / 90f).coerceIn(0f, 1f)
    val track = MaterialTheme.colors.onBackground.copy(alpha = 0.12f)
    val fill = if (dirUp) MaterialTheme.colors.secondary else MaterialTheme.colors.error

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = (if (dirUp) "↑" else "↓") + " │ΔAlt│ " + formatDeg(absAltDeg),
            style = MaterialTheme.typography.caption2
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(6.dp))
                .background(track)
        ) {
            Box(
                modifier = Modifier
                    .align(if (dirUp) Alignment.TopCenter else Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(frac)
                    .background(fill)
            )
        }
    }
}

@Composable
private fun TurnArrow(right: Boolean, emphasized: Boolean, size: Dp) {
    val color = if (emphasized) MaterialTheme.colors.primary else MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
    Canvas(modifier = Modifier.size(size)) {
        val w = size.width
        val h = size.height
        val cw = kotlin.math.min(w.value, h.value)
        val arrowLength = (cw * 0.8f)
        val arrowHeight = (cw * 0.35f)

        // drawScope size.* — пиксели:
        val W = this.size.width
        val H = this.size.height
        val centerY = H / 2f
        val startX = (W - arrowLength) / 2f
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
    val s = String.format("%.1f°", value)
    return s.replace(".0°", "°")
}

private fun targetIndexFor(initial: AimTarget?, options: List<UiTarget>): Int {
    if (initial == null) return 0
    return when (initial) {
        is BodyTarget -> when (initial.body) {
            Body.SUN -> 0
            Body.MOON -> 1
            Body.JUPITER -> 2
            Body.SATURN -> 3
            else -> 0
        }
        is EquatorialTarget -> 4 // Polaris
    }
}

// ------- Preview с фейковым контроллером -------
@androidx.compose.ui.tooling.preview.Preview(device = Devices.WEAR_OS_SMALL_ROUND, showSystemUi = true)
@androidx.compose.ui.tooling.preview.Preview(device = Devices.WEAR_OS_SQUARE, showSystemUi = true)
@Composable
private fun Preview_Aim() {
    val fake = remember { FakeAimController() }
    MaterialTheme {
        AimScreen(aimController = fake, initialTarget = BodyTarget(Body.JUPITER))
    }
}

private class FakeAimController : AimController {
    private val _state = MutableStateFlow(
        dev.pointtosky.wear.aim.core.AimState(
            current = dev.pointtosky.core.astro.coord.Horizontal(0.0, 0.0),
            target = dev.pointtosky.core.astro.coord.Horizontal(0.0, 0.0),
            dAzDeg = -23.4,
            dAltDeg = 15.8,
            phase = AimPhase.SEARCHING,
            confidence = 0.42f,
        )
    )
    override val state: StateFlow<dev.pointtosky.wear.aim.core.AimState> = _state
    override fun setTarget(target: AimTarget) {}
    override fun setTolerance(t: dev.pointtosky.wear.aim.core.AimTolerance) {}
    override fun setHoldToLockMs(ms: Long) {}
    override fun start() {}
    override fun stop() {}
}
