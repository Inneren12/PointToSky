package dev.pointtosky.mobile.ar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.identify.angularSeparationDeg
import dev.pointtosky.core.astro.transform.altAzToRaDec
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AimTargetEquatorialPayload
import dev.pointtosky.core.datalayer.AimTargetKind
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.mobile.R
import dev.pointtosky.mobile.datalayer.AimTargetOption
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

@Composable
fun ArRoute(
    catalogRepository: CatalogRepository,
    locationPrefs: LocationPrefs,
    onBack: () -> Unit,
    onSendAimTarget: (AimTargetOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ArViewModel =
        viewModel(
            factory =
                ArViewModelFactory(
                    catalogRepository = catalogRepository,
                    locationPrefs = locationPrefs,
                ),
        )
    val state by viewModel.state.collectAsStateWithLifecycle()

    ArScreen(
        state = state,
        onBack = onBack,
        onSetTarget = { target ->
            val option =
                AimTargetOption(
                    id = "ar-target",
                    label = target.label,
                    buildMessage = { cid ->
                        AimSetTargetMessage(
                            cid = cid,
                            kind = AimTargetKind.EQUATORIAL,
                            payload =
                                JsonCodec.encodeToElement(
                                    AimTargetEquatorialPayload(
                                        raDeg = target.raDeg,
                                        decDeg = target.decDeg,
                                    ),
                                ),
                        )
                    },
                )
            onSendAimTarget(option)
        },
        modifier = modifier,
    )
}

data class ArTarget(
    val raDeg: Double,
    val decDeg: Double,
    val label: String,
)

@Composable
fun ArScreen(
    state: ArUiState,
    onBack: () -> Unit,
    onSetTarget: (ArTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val permission = Manifest.permission.CAMERA
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
        }
    LaunchedEffect(Unit) {
        if (!hasPermission) {
            launcher.launch(permission)
        }
    }

    val rotationFrame = rememberRotationFrame()
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { overlaySize = it },
    ) {
        if (hasPermission) {
            CameraPreview(modifier = Modifier.fillMaxSize())
        } else {
            PermissionRequest(onRequest = { launcher.launch(permission) })
        }

        IconButton(
            onClick = onBack,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .background(color = Color(0x66000000), shape = CircleShape),
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
        }

        when (state) {
            ArUiState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White,
                )
            }

            is ArUiState.Ready -> {
                val overlay =
                    remember(state, rotationFrame, overlaySize) {
                        if (rotationFrame != null && overlaySize != IntSize.Zero) {
                            calculateOverlay(state, rotationFrame, overlaySize)
                        } else {
                            null
                        }
                    }

                Reticle(modifier = Modifier.align(Alignment.Center))

                overlay?.labels?.forEach { label ->
                    ArObjectLabel(
                        data = label,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }

                val targetLabel =
                    overlay?.nearestLabel?.title
                        ?: stringResource(id = R.string.ar_target_fallback_label)

                val target =
                    overlay?.let {
                        ArTarget(
                            raDeg = it.reticleEquatorial.raDeg,
                            decDeg = it.reticleEquatorial.decDeg,
                            label = targetLabel,
                        )
                    }

                if (overlay != null) {
                    InfoPanel(
                        overlay = overlay,
                        targetLabel = targetLabel,
                        onSetTarget = { target?.let(onSetTarget) },
                        modifier =
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(id = R.string.ar_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Text(
            text = stringResource(id = R.string.ar_permission_message),
            modifier = Modifier.padding(top = 16.dp),
            color = Color.White,
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 24.dp)) {
            Text(text = stringResource(id = R.string.ar_permission_request))
        }
    }
}

@Composable
private fun Reticle(modifier: Modifier = Modifier) {
    val strokeWidth = 2.dp
    Canvas(
        modifier =
            modifier
                .size(96.dp),
    ) {
        drawCircle(
            color = Color.White.copy(alpha = 0.6f),
            radius = size.minDimension / 2f,
            style = Stroke(width = strokeWidth.toPx()),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.6f),
            start = Offset(size.width / 2f, 0f),
            end = Offset(size.width / 2f, size.height),
            strokeWidth = strokeWidth.toPx(),
        )
        drawLine(
            color = Color.White.copy(alpha = 0.6f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = strokeWidth.toPx(),
        )
    }
}

@Composable
private fun InfoPanel(
    overlay: OverlayData,
    targetLabel: String,
    onSetTarget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = Locale.getDefault()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(color = Color(0x99000000), shape = RoundedCornerShape(16.dp))
                .padding(16.dp),
    ) {
        Text(
            text =
                stringResource(
                    id = R.string.ar_reticle_alt_az,
                    formatAngle(locale, overlay.reticleHorizontal.altDeg),
                    formatAngle(locale, overlay.reticleHorizontal.azDeg),
                ),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
                stringResource(
                    id = R.string.ar_reticle_ra_dec,
                    formatAngle(locale, overlay.reticleEquatorial.raDeg),
                    formatAngle(locale, overlay.reticleEquatorial.decDeg),
                ),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        overlay.nearestLabel?.let { nearest ->
            Text(
                text =
                    stringResource(
                        id = R.string.ar_nearest_object,
                        nearest.title ?: stringResource(id = R.string.ar_unknown_object),
                        formatAngle(locale, nearest.separationDeg),
                    ),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = onSetTarget,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
        ) {
            Text(text = stringResource(id = R.string.ar_set_target_button, targetLabel))
        }
    }
}

@Composable
private fun ArObjectLabel(
    data: OverlayObject,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val offset =
        remember(data.position, density) {
            val anchorX = with(density) { 80.dp.toPx() }
            val anchorY = with(density) { 48.dp.toPx() }
            IntOffset(
                x = (data.position.x - anchorX).roundToInt(),
                y = (data.position.y - anchorY).roundToInt(),
            )
        }
    Column(
        modifier =
            modifier
                .offset { offset }
                .background(color = Color(0x99000000), shape = RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = data.title ?: stringResource(id = R.string.ar_unknown_object),
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            text =
                stringResource(
                    id = R.string.ar_label_meta,
                    data.magnitude,
                    data.separationDeg,
                ),
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@VisibleForTesting
internal fun calculateOverlay(
    state: ArUiState.Ready,
    frame: RotationFrame,
    viewport: IntSize,
): OverlayData? {
    if (viewport.width == 0 || viewport.height == 0) return null

    val reticleHorizontal = vectorToHorizontal(frame.forwardWorld)
    val reticleEquatorial =
        altAzToRaDec(
            reticleHorizontal,
            lstDeg = state.lstDeg,
            latDeg = state.location.latDeg,
        )
    val worldToDevice = transpose(frame.rotationMatrix)
    val width = viewport.width.toFloat()
    val height = viewport.height.toFloat()
    val halfWidth = width / 2f
    val halfHeight = height / 2f
    val tanHFov = tan(Math.toRadians(HORIZONTAL_FOV_DEG / 2.0))
    val tanVFov = tan(Math.toRadians(VERTICAL_FOV_DEG / 2.0))

    val objects =
        state.stars
            .mapNotNull { star ->
                val horizontal =
                    raDecToAltAz(
                        star.equatorial,
                        lstDeg = state.lstDeg,
                        latDeg = state.location.latDeg,
                        applyRefraction = false,
                    )
                if (horizontal.altDeg < 0.0) return@mapNotNull null
                val worldVec = horizontalToVector(horizontal)
                val deviceVec = multiply(worldToDevice, worldVec)
                // Конвенция, согласованная с RotationFrame и SensorManager:
                // ось Z устройства направлена "из экрана", а "вперёд" = -Z.
                // Значит, объекты "перед камерой" имеют deviceVec.z < 0.
                if (deviceVec[2] >= -0.01f) return@mapNotNull null
                val ndcX = (deviceVec[0] / -deviceVec[2]) / tanHFov
                val ndcY = (deviceVec[1] / -deviceVec[2]) / tanVFov
                val distance = sqrt(ndcX * ndcX + ndcY * ndcY)
                if (distance > MAX_SCREEN_DISTANCE) return@mapNotNull null
                val screenX = halfWidth * (1f + ndcX.toFloat())
                val screenY = halfHeight * (1f - ndcY.toFloat())
                val separation = angularSeparationDeg(reticleEquatorial, star.equatorial)
                OverlayObject(
                    title = star.label,
                    magnitude = star.magnitude,
                    position = Offset(screenX, screenY),
                    distance = distance,
                    separationDeg = separation,
                )
            }.sortedBy { it.separationDeg }
            .take(MAX_LABELS)

    return OverlayData(
        reticleHorizontal = reticleHorizontal,
        reticleEquatorial = reticleEquatorial,
        labels = objects,
        nearestLabel = objects.firstOrNull(),
    )
}

@VisibleForTesting
internal data class OverlayObject(
    val title: String?,
    val magnitude: Double,
    val position: Offset,
    val distance: Double,
    val separationDeg: Double,
)

@VisibleForTesting
internal data class OverlayData(
    val reticleHorizontal: Horizontal,
    val reticleEquatorial: Equatorial,
    val labels: List<OverlayObject>,
    val nearestLabel: OverlayObject?,
)

private fun transpose(matrix: FloatArray): FloatArray {
    val result = FloatArray(9)
    for (i in 0 until 3) {
        for (j in 0 until 3) {
            result[i * 3 + j] = matrix[j * 3 + i]
        }
    }
    return result
}

private fun multiply(
    matrix: FloatArray,
    vector: FloatArray,
): FloatArray {
    val x = matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2]
    val y = matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2]
    val z = matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2]
    return floatArrayOf(x, y, z)
}

private fun horizontalToVector(horizontal: Horizontal): FloatArray {
    val altRad = Math.toRadians(horizontal.altDeg)
    val azRad = Math.toRadians(horizontal.azDeg)
    val cosAlt = cos(altRad)
    return floatArrayOf(
        (cosAlt * sin(azRad)).toFloat(),
        (cosAlt * cos(azRad)).toFloat(),
        sin(altRad).toFloat(),
    )
}

private fun vectorToHorizontal(vector: FloatArray): Horizontal {
    val z = vector[2].toDouble().coerceIn(-1.0, 1.0)
    val altDeg = Math.toDegrees(asin(z))
    var azDeg = Math.toDegrees(atan2(vector[0].toDouble(), vector[1].toDouble()))
    if (azDeg < 0) {
        azDeg += 360.0
    }
    return Horizontal(azDeg = azDeg, altDeg = altDeg)
}

private fun formatAngle(
    locale: Locale,
    value: Double,
): String = String.format(locale, "%.1f", value)

private const val HORIZONTAL_FOV_DEG = 60.0
private const val VERTICAL_FOV_DEG = 45.0
private const val MAX_SCREEN_DISTANCE = 1.2
private const val MAX_LABELS = 5
