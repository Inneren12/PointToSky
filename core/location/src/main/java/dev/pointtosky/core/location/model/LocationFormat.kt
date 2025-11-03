package dev.pointtosky.core.location.model

import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.floor

fun formatLatitudeDms(latDeg: Double): String =
    formatDms(latDeg, positiveSuffix = "N", negativeSuffix = "S")

fun formatLongitudeDms(lonDeg: Double): String =
    formatDms(lonDeg, positiveSuffix = "E", negativeSuffix = "W")

fun formatGeoPointDms(point: GeoPoint): String =
    "${formatLatitudeDms(point.latDeg)} ${formatLongitudeDms(point.lonDeg)}"

private fun formatDms(value: Double, positiveSuffix: String, negativeSuffix: String): String {
    val suffix = if (value >= 0.0) positiveSuffix else negativeSuffix
    val absolute = value.absoluteValue
    val degrees = floor(absolute).toInt()
    val minutesFull = (absolute - degrees) * 60.0
    val minutes = floor(minutesFull).toInt()
    val seconds = (minutesFull - minutes) * 60.0
    return String.format(Locale.US, "%d°%02d'%06.3f\"%s", degrees, minutes, seconds, suffix)
}
