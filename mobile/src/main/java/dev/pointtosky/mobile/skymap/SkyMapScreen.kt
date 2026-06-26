package dev.pointtosky.mobile.skymap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.visibility.Bortle
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.mobile.R
import dev.pointtosky.mobile.location.DeviceLocationRepository
import dev.pointtosky.mobile.render.BvColor
import dev.pointtosky.mobile.visibility.BortleSource
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
fun SkyMapRoute(
    catalogRepository: CatalogRepository,
    locationPrefs: LocationPrefs,
    onBack: () -> Unit,
    onOpenCard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val deviceLocationRepository = remember { DeviceLocationRepository(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, deviceLocationRepository) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    deviceLocationRepository.onPermissionChanged()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val viewModel: SkyMapViewModel =
        viewModel(
            factory =
                SkyMapViewModelFactory(
                    catalogRepository = catalogRepository,
                    locationPrefs = locationPrefs,
                    deviceLocationRepository = deviceLocationRepository,
                    context = context.applicationContext,
                ),
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    SkyMapScreen(
        state = state,
        onBack = onBack,
        onOpenCard = onOpenCard,
        onVisibilityFilterToggle = viewModel::setVisibilityFilterEnabled,
        onBortleChange = viewModel::setBortle,
        onBortleSourceChange = viewModel::setBortleSource,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkyMapScreen(
    state: SkyMapState,
    onBack: () -> Unit,
    onOpenCard: () -> Unit,
    onVisibilityFilterToggle: (Boolean) -> Unit,
    onBortleChange: (Bortle) -> Unit,
    onBortleSourceChange: (BortleSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.sky_map_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            SkyMapState.Loading ->
                Box(
                    modifier =
                        Modifier
                            .padding(padding)
                            .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            is SkyMapState.Ready ->
                SkyMapContent(
                    state = state,
                    onOpenCard = onOpenCard,
                    onVisibilityFilterToggle = onVisibilityFilterToggle,
                    onBortleChange = onBortleChange,
                    onBortleSourceChange = onBortleSourceChange,
                    modifier =
                        Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .background(colorScheme.surface),
                )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkyMapContent(
    state: SkyMapState.Ready,
    onOpenCard: () -> Unit,
    onVisibilityFilterToggle: (Boolean) -> Unit,
    onBortleChange: (Bortle) -> Unit,
    onBortleSourceChange: (BortleSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }

    val formatter =
        remember {
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.getDefault())
        }
    val timeText =
        remember(state.instant) {
            formatter.format(state.instant.atZone(ZoneId.systemDefault()))
        }

    val baseStarPositions =
        remember(state.stars, canvasSize) {
            state.stars.mapNotNull { star ->
                projectBase(star.horizontal, canvasSize)?.let { star to it }
            }
        }
    val baseConstellationPositions =
        remember(state.constellations, canvasSize) {
            state.constellations.map { constellation ->
                constellation.polygons.map { polygon ->
                    polygon.map { point -> projectBase(point, canvasSize) }
                }
            }
        }
    val tapRadiusPx = with(density) { 24.dp.toPx() }

    // Dark sky palette so B−V star colors read well (independent of the app's light Material theme).
    val skyDiskColor = Color(0xFF0E1430)
    val skySurroundColor = Color(0xFF05070F)
    val gridColor = Color.White.copy(alpha = 0.12f)
    val constellationColor = Color(0xFF7FA8D9).copy(alpha = 0.5f)
    val highlightColor = MaterialTheme.colorScheme.tertiary

    val selectedStar = state.stars.firstOrNull { it.id == selectedId }

    Box(modifier = modifier) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(skySurroundColor)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(state.stars, scale, offset) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(0.6f, 5f)
                            val scaleChange = newScale / scale
                            val newOffset = (offset + centroid) * scaleChange - centroid + pan
                            scale = newScale
                            offset = newOffset
                        }
                    }.pointerInput(baseStarPositions, tapRadiusPx) {
                        detectTapGestures { tap ->
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val tapBase = (tap - center - offset) / scale
                            val hitRadius = tapRadiusPx / scale
                            val nearest =
                                baseStarPositions.minByOrNull { (_, base) ->
                                    (base - tapBase).getDistanceSquared()
                                }
                            if (nearest != null && (nearest.second - tapBase).getDistance() <= hitRadius) {
                                selectedId = nearest.first.id
                                onOpenCard()
                            }
                        }
                    },
        ) {
            drawSkyBackground(scale, offset, skyDiskColor)
            drawAltitudeGrid(scale, offset, gridColor)
            drawConstellations(baseConstellationPositions, scale, offset, constellationColor)
            drawStars(
                baseStarPositions,
                selectedId,
                highlightColor,
                scale,
                offset,
                state.visibilityFilterEnabled,
                state.limitingMag,
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopEnd,
            ) {
                Surface(tonalElevation = 2.dp) {
                    val latText = formatCoordinate(state.location.latDeg, isLat = true)
                    val lonText = formatCoordinate(state.location.lonDeg, isLat = false)
                    val resolvedText =
                        when {
                            state.locationResolved && state.locationSource == SkyMapViewModel.LocationSource.MANUAL ->
                                stringResource(id = R.string.sky_map_location_manual)
                            state.locationResolved && state.locationSource == SkyMapViewModel.LocationSource.DEVICE ->
                                stringResource(id = R.string.sky_map_location_device)
                            else -> stringResource(id = R.string.sky_map_location_default)
                        }
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = stringResource(id = R.string.sky_map_timestamp, timeText))
                        Spacer(modifier = Modifier.size(4.dp))
                        Text(text = stringResource(id = R.string.sky_map_location_label, latText, lonText))
                        Text(
                            text = resolvedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(id = R.string.ar_visibility_filter_title),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = state.visibilityFilterEnabled,
                                onCheckedChange = onVisibilityFilterToggle,
                            )
                        }
                        if (state.limitingMag != null) {
                            Text(
                                text = stringResource(
                                    id = R.string.ar_visibility_readout,
                                    String.format(Locale.US, "%.1f", state.limitingMag),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        if (state.visibilityFilterEnabled && !state.lightPollutionAvailable) {
                            Spacer(modifier = Modifier.size(4.dp))
                            val bortleNumber = state.bortle.ordinal + 1
                            Text(
                                text = stringResource(id = R.string.ar_sky_darkness_title, bortleNumber),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Slider(
                                value = bortleNumber.toFloat(),
                                onValueChange = {
                                    onBortleChange(Bortle.entries[(it.toInt() - 1).coerceIn(0, 8)])
                                },
                                valueRange = 1f..9f,
                                steps = 7,
                            )
                        } else if (state.visibilityFilterEnabled) {
                            Spacer(modifier = Modifier.size(4.dp))
                            val sources = BortleSource.entries
                            val sourceLabels = listOf(
                                stringResource(id = R.string.ar_bortle_source_auto),
                                stringResource(id = R.string.ar_bortle_source_manual),
                            )
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                sources.forEachIndexed { index, source ->
                                    SegmentedButton(
                                        selected = state.bortleSource == source,
                                        onClick = { onBortleSourceChange(source) },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = sources.size,
                                        ),
                                        label = {
                                            Text(
                                                text = sourceLabels[index],
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        },
                                    )
                                }
                            }
                            if (state.bortleSource == BortleSource.AUTO) {
                                if (state.autoBortle != null) {
                                    Text(
                                        text = stringResource(
                                            id = R.string.ar_bortle_auto_detected,
                                            state.autoBortle.ordinal + 1,
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                } else {
                                    Text(
                                        text = stringResource(id = R.string.ar_bortle_auto_unavailable),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                    val bortleNumber = state.bortle.ordinal + 1
                                    Text(
                                        text = stringResource(id = R.string.ar_sky_darkness_title, bortleNumber),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Slider(
                                        value = bortleNumber.toFloat(),
                                        onValueChange = {
                                            onBortleChange(Bortle.entries[(it.toInt() - 1).coerceIn(0, 8)])
                                        },
                                        valueRange = 1f..9f,
                                        steps = 7,
                                    )
                                }
                            } else {
                                val bortleNumber = state.bortle.ordinal + 1
                                Text(
                                    text = stringResource(id = R.string.ar_sky_darkness_title, bortleNumber),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Slider(
                                    value = bortleNumber.toFloat(),
                                    onValueChange = {
                                        onBortleChange(Bortle.entries[(it.toInt() - 1).coerceIn(0, 8)])
                                    },
                                    valueRange = 1f..9f,
                                    steps = 7,
                                )
                            }
                        }
                    }
                }
            }

            selectedStar?.let { star ->
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = star.label ?: stringResource(id = R.string.sky_map_unnamed_star),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        star.constellation?.let { code ->
                            Text(
                                text = stringResource(id = R.string.sky_map_constellation, code),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = stringResource(id = R.string.sky_map_magnitude, star.magnitude),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text =
                                stringResource(
                                    id = R.string.sky_map_alt_az,
                                    String.format(Locale.US, "%.1f", star.horizontal.altDeg),
                                    String.format(Locale.US, "%.1f", star.horizontal.azDeg),
                                ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawSkyBackground(
    scale: Float,
    offset: Offset,
    color: Color,
) {
    val radius = min(size.width, size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f) + offset
    drawCircle(
        color = color,
        radius = radius * scale,
        center = center,
    )
}

private fun DrawScope.drawAltitudeGrid(
    scale: Float,
    offset: Offset,
    color: Color,
) {
    val radius = min(size.width, size.height) / 2f * scale
    val center = Offset(size.width / 2f, size.height / 2f) + offset
    val altitudes = listOf(0.0, 30.0, 60.0, 75.0)
    altitudes.forEach { alt ->
        val r = radius * ((90.0 - alt) / 90.0).toFloat()
        drawCircle(
            color = color,
            radius = r,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
    val azimuthStep = 45
    for (az in 0 until 360 step azimuthStep) {
        val theta = az * PI / 180.0
        val direction =
            Offset(
                x = (sin(theta) * radius).toFloat(),
                y = (-cos(theta) * radius).toFloat(),
            )
        drawLine(
            color = color,
            start = center,
            end = center + direction,
            strokeWidth = 1.dp.toPx(),
        )
    }
}

private fun DrawScope.drawConstellations(
    baseConstellationPositions: List<List<List<Offset?>>>,
    scale: Float,
    offset: Offset,
    color: Color,
) {
    val path = Path()
    val stroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val center = Offset(size.width / 2f, size.height / 2f)
    baseConstellationPositions.forEach { polygons ->
        polygons.forEach { polygon ->
            path.reset()
            var started = false
            polygon.forEach { base ->
                if (base != null) {
                    val position = center + base * scale + offset
                    if (!started) {
                        path.moveTo(position.x, position.y)
                        started = true
                    } else {
                        path.lineTo(position.x, position.y)
                    }
                } else {
                    started = false
                }
            }
            if (!path.isEmpty) {
                drawPath(path = path, color = color, style = stroke)
            }
        }
    }
}

private fun DrawScope.drawStars(
    baseStarPositions: List<Pair<ProjectedStar, Offset>>,
    selectedId: Int?,
    selectedColor: Color,
    scale: Float,
    offset: Offset,
    visibilityEnabled: Boolean = false,
    limitingMag: Double? = null,
) {
    val baseRadius = 4.dp.toPx()
    val center = Offset(size.width / 2f, size.height / 2f)

    baseStarPositions.forEach { (star, base) ->
        val projected = center + base * scale + offset
        val brightness = (STAR_BASE_MAG - star.magnitude).toFloat().coerceAtLeast(0.3f)
        val radius = baseRadius * (0.4f + 0.3f * brightness)
        val visAlpha = if (!visibilityEnabled || limitingMag == null || star.magnitude <= limitingMag) {
            1f
        } else {
            (1f - ((star.magnitude - limitingMag) / VIS_FADE_RANGE).toFloat()).coerceIn(VIS_MIN_ALPHA, 1f)
        }
        val alpha = ((0.55f + 0.18f * brightness) * visAlpha).coerceIn(0f, 1f)
        drawCircle(
            color = BvColor.toColor(star.bv).copy(alpha = alpha),
            radius = radius,
            center = projected,
        )
        if (star.id == selectedId) {
            drawCircle(
                color = selectedColor,
                radius = radius * 1.6f,
                center = projected,
                style = Stroke(width = radius * 0.4f),
            )
        }
    }
}

private fun projectBase(
    horizontal: Horizontal,
    size: IntSize,
): Offset? {
    if (size.width == 0 || size.height == 0) return null
    val radius = min(size.width, size.height) / 2f
    val azRad = Math.toRadians(horizontal.azDeg)
    val normalized = ((90.0 - horizontal.altDeg) / 90.0).toFloat()
    return Offset(
        x = sin(azRad).toFloat() * normalized * radius,
        y = -cos(azRad).toFloat() * normalized * radius,
    )
}

private fun formatCoordinate(
    value: Double,
    isLat: Boolean,
): String {
    val hemi =
        if (isLat) {
            if (value >= 0) "N" else "S"
        } else {
            if (value >= 0) "E" else "W"
        }
    val absValue = abs(value)
    return String.format(Locale.US, "%.2f° %s", absValue, hemi)
}

private const val STAR_BASE_MAG = 4.0
private const val VIS_FADE_RANGE = 2.0
private const val VIS_MIN_ALPHA = 0.12f
