package dev.pointtosky.core.location.remote

import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import org.json.JSONException
import org.json.JSONObject

private const val KEY_LAT = "lat"
private const val KEY_LON = "lon"
private const val KEY_ACCURACY = "accuracyM"
private const val KEY_TIME = "timeMs"
private const val KEY_PROVIDER = "provider"
private const val KEY_FRESH_TTL = "freshTtlMs"

const val PATH_LOCATION_REQUEST_ONE = "/location/request_one"
const val PATH_LOCATION_RESPONSE_ONE = "/location/response_one"
const val DATA_ITEM_LAST_FIX = "/location/last_fix"

/**
 * Payload for foreground initiated single location request from watch to phone.
 */
data class LocationRequestPayload(
    val freshTtlMs: Long,
) {
    fun toByteArray(): ByteArray = JSONObject()
        .put(KEY_FRESH_TTL, freshTtlMs)
        .toString()
        .encodeToByteArray()

    companion object {
        fun fromBytes(bytes: ByteArray): LocationRequestPayload? {
            return runCatching {
                val json = JSONObject(bytes.decodeToString())
                LocationRequestPayload(
                    freshTtlMs = json.optLong(KEY_FRESH_TTL, 0L),
                )
            }.getOrNull()
        }
    }
}

data class LocationResponsePayload(
    val fix: LocationFix,
    val rawProvider: String?,
) {
    fun toByteArray(): ByteArray {
        val json = JSONObject()
            .put(KEY_LAT, fix.point.latDeg)
            .put(KEY_LON, fix.point.lonDeg)
            .put(KEY_TIME, fix.timeMs)
            .put(KEY_PROVIDER, rawProvider ?: ProviderType.REMOTE_PHONE.name)
        if (fix.accuracyM != null) {
            json.put(KEY_ACCURACY, fix.accuracyM)
        }
        return json.toString().encodeToByteArray()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): LocationResponsePayload? {
            return runCatching {
                val json = JSONObject(bytes.decodeToString())
                val lat = json.getDouble(KEY_LAT)
                val lon = json.getDouble(KEY_LON)
                val timeMs = json.getLong(KEY_TIME)
                val accuracy = if (json.has(KEY_ACCURACY)) json.getDouble(KEY_ACCURACY).toFloat() else null
                val provider = json.optString(KEY_PROVIDER, ProviderType.REMOTE_PHONE.name)
                val point = GeoPoint(latDeg = lat, lonDeg = lon)
                val fix = LocationFix(
                    point = point,
                    timeMs = timeMs,
                    accuracyM = accuracy,
                    provider = ProviderType.REMOTE_PHONE,
                )
                LocationResponsePayload(fix = fix, rawProvider = provider)
            }.getOrElse { error ->
                if (error is JSONException) null else throw error
            }
        }
    }
}
