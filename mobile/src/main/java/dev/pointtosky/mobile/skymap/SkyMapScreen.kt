package dev.pointtosky.mobile.skymap

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.mobile.R
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
    val viewModel: SkyMapViewModel = viewModel(
        factory = SkyMapViewModelFactory(
            catalogRepository = catalogRepository,
            locationPrefs = locationPrefs,
            context = context.applicationContext,
        )
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    SkyMapScreen(
        state = state,
        onBack = onBack,
        onOpenCard = onOpenCard,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkyMapScreen(
    state: SkyMapState,
    onBack: () -> Unit,
    onOpenCard: () -> Unit,
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
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        when (state) {
            SkyMapState.Loading -> Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            is SkyMapState.Ready -> SkyMapContent(
                state = state,
                onOpenCard = onOpenCard,
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(colorScheme.surface),
            )
        }
    }
}

@Composable
private fun SkyMapContent(
    state: SkyMapState.Ready,
    onOpenCard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by rememberSaveable { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }

    val formatter = remember {
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.US)
    }
    val timeText = remember(state.instant) {
        formatter.format(state.instant.atZone(ZoneId.systemDefault()))
    }

    val starPositions = remember(state.stars, canvasSize, scale, offset) {
        state.stars.mapNotNull { star ->
            val position = projectToCanvas(star.horizontal, canvasSize, scale, offset)
            if (position != null) star to position else null
        }
    }
    val tapRadiusPx = with(density) { 24.dp.toPx() }

    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val constellationColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    val starColor = Color.White
    val highlightColor = MaterialTheme.colorScheme.tertiary

    val selectedStar = state.stars.firstOrNull { it.id == selectedId }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .onSizeChanged { canvasSize = it }
                .pointerInput(state.stars, scale, offset) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(0.6f, 5f)
                        val scaleChange = newScale / scale
                        val newOffset = (offset + centroid) * scaleChange - centroid + pan
                        scale = newScale
                        offset = newOffset
                    }
                }
                .pointerInput(starPositions, tapRadiusPx) {
                    detectTapGestures { tap ->
                        val nearest = starPositions.minByOrNull { (_, position) ->
                            (position - tap).getDistanceSquared()
                        }
                        if (nearest != null) {
                            val distance = (nearest.second - tap).getDistance()
                            if (distance <= tapRadiusPx) {
                                selectedId = nearest.first.id
                                onOpenCard()
                            }
                        }
                    }
                }
        ) {
            drawSkyBackground(scale, offset, backgroundColor)
            drawAltitudeGrid(scale, offset, gridColor)
            drawConstellations(state.constellations, scale, offset, constellationColor)
            drawStars(starPositions, selectedId, starColor, highlightColor)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.TopEnd
            ) {
                Surface(tonalElevation = 2.dp) {
                    val latText = formatCoordinate(state.location.latDeg, isLat = true)
                    val lonText = formatCoordinate(state.location.lonDeg, isLat = false)
                    val resolvedText = if (state.locationResolved) {
                        stringResource(id = R.string.sky_map_location_manual)
                    } else {
                        stringResource(id = R.string.sky_map_location_default)
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
                    }
                }
            }

            selectedStar?.let { star ->
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = star.label ?: stringResource(id = R.string.sky_map_unnamed_star),
                            style = MaterialTheme.typography.titleMedium
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
                            text = stringResource(
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

private fun DrawScope.drawSkyBackground(scale: Float, offset: Offset, color: Color) {
    val radius = min(size.width, size.height) / 2f
    val center = Offset(size.width / 2f, size.height / 2f) + offset
    drawCircle(
        color = color,
        radius = radius * scale,
        center = center,
    )
}

private fun DrawScope.drawAltitudeGrid(scale: Float, offset: Offset, color: Color) {
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
        val direction = Offset(
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
    constellations: List<ConstellationProjection>,
    scale: Float,
    offset: Offset,
    color: Color,
) {
    val path = Path()
    val stroke = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    constellations.forEach { constellation ->
        constellation.polygons.forEach { polygon ->
            path.reset()
            var started = false
            polygon.forEach { point ->
                val position = projectToCanvas(point, size.toIntSize(), scale, offset)
                if (position != null) {
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
    starPositions: List<Pair<ProjectedStar, Offset>>,
    selectedId: Int?,
    starColor: Color,
    selectedColor: Color,
) {
    val baseRadius = 4.dp.toPx()

    starPositions.forEach { (star, projected) ->
        val brightness = (STAR_BASE_MAG - star.magnitude).toFloat().coerceAtLeast(0.3f)
        val radius = baseRadius * (0.4f + 0.3f * brightness)
        drawCircle(
            color = starColor,
            radius = radius,
            center = projected,
        )
        if (star.id == selectedId) {
            drawCircle(
                color = selectedColor,
                radius = radius * 1.6f,
                center = projected,
                style = Stroke(width = radius * 0.4f)
            )
        }
    }
}

private fun projectToCanvas(horizontal: Horizontal, size: IntSize, scale: Float, offset: Offset): Offset? {
    if (size.width == 0 || size.height == 0) return null
    val radius = min(size.width, size.height) / 2f
    val azRad = Math.toRadians(horizontal.azDeg)
    val normalized = ((90.0 - horizontal.altDeg) / 90.0).toFloat()
    val x = sin(azRad).toFloat() * normalized * radius * scale + offset.x
    val y = -cos(azRad).toFloat() * normalized * radius * scale + offset.y
    val center = Offset(size.width / 2f, size.height / 2f)
    return center + Offset(x, y)
}

private fun Offset.getDistance(): Float = kotlin.math.sqrt(x * x + y * y)

private fun Offset.getDistanceSquared(): Float = x * x + y * y

private fun formatCoordinate(value: Double, isLat: Boolean): String {
    val hemi = if (isLat) {
        if (value >= 0) "N" else "S"
    } else {
        if (value >= 0) "E" else "W"
    }
    val absValue = abs(value)
    return String.format(Locale.US, "%.2f° %s", absValue, hemi)
}

private const val STAR_BASE_MAG = 4.0
