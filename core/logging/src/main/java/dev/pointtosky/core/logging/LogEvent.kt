package dev.pointtosky.core.logging

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    EVENT
}

data class LogEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
    val eventName: String? = null,
    val payload: Map<String, Any?> = emptyMap(),
    val thread: ThreadSnapshot,
    val process: ProcessSnapshot,
    val device: DeviceInfo,
    val error: ThrowableSnapshot? = null
) {
    fun toJsonLine(json: Json = defaultJson): String {
        val element = buildJsonObject {
            put("timestamp", timestamp)
            put("isoTimestamp", ISO_FORMATTER.format(Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.UTC)))
            put("level", level.name)
            put("tag", tag)
            put("message", message)
            eventName?.let { put("event", it) }
            put("thread", thread.toJson())
            put("process", process.toJson())
            put("device", device.toJson())
            if (payload.isNotEmpty()) {
                put("payload", PayloadConverter.toJson(payload))
            }
            error?.let { put("error", it.toJson()) }
        }
        return json.encodeToString(JsonObject.serializer(), element)
    }

    companion object {
        private val ISO_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME

        internal val defaultJson: Json = Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

data class ThreadSnapshot(
    val name: String,
    val id: Long,
    val isMainThread: Boolean
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("id", id)
        put("isMain", isMainThread)
    }
}

data class ProcessSnapshot(
    val pid: Int,
    val processName: String
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("pid", pid)
        put("name", processName)
    }
}

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val sdkInt: Int,
    val appVersionName: String,
    val appVersionCode: Long,
    val packageName: String,
    val isDebug: Boolean,
    val diagnosticsEnabled: Boolean,
    val extras: Map<String, Any?> = emptyMap()
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("manufacturer", manufacturer)
        put("model", model)
        put("osVersion", osVersion)
        put("sdkInt", sdkInt)
        put("appVersionName", appVersionName)
        put("appVersionCode", appVersionCode)
        put("packageName", packageName)
        put("isDebug", isDebug)
        put("diagnosticsEnabled", diagnosticsEnabled)
        if (extras.isNotEmpty()) {
            put("extras", PayloadConverter.toJson(extras))
        }
    }

    companion object {
        fun from(
            context: Context,
            isDebug: Boolean,
            diagnosticsEnabled: Boolean = isDebug,
            extras: Map<String, Any?> = emptyMap()
        ): DeviceInfo {
            val packageManager = context.packageManager
            val packageName = context.packageName
            val packageInfo = runCatching {
                packageManager.getPackageInfoCompat(packageName)
            }.getOrNull()
            val versionName = packageInfo?.versionName ?: "0.0"
            val versionCode = if (packageInfo != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) packageInfo.longVersionCode else packageInfo.versionCode.toLong()
            } else {
                0L
            }
            return DeviceInfo(
                manufacturer = Build.MANUFACTURER.orEmptyFallback("unknown"),
                model = Build.MODEL.orEmptyFallback("unknown"),
                osVersion = Build.VERSION.RELEASE.orEmptyFallback("unknown"),
                sdkInt = Build.VERSION.SDK_INT,
                appVersionName = versionName,
                appVersionCode = versionCode,
                packageName = packageName,
                isDebug = isDebug,
                diagnosticsEnabled = diagnosticsEnabled,
                extras = extras.toMap()
            )
        }

        private fun PackageManager.getPackageInfoCompat(packageName: String) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                getPackageInfo(packageName, 0)
            }

        private fun String?.orEmptyFallback(fallback: String): String = this?.takeIf { it.isNotBlank() } ?: fallback
    }
}

data class ThrowableSnapshot(
    val type: String,
    val message: String?,
    val stackTrace: String
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("type", type)
        message?.let { put("message", it) }
        put("stacktrace", stackTrace)
    }

    companion object {
        fun from(throwable: Throwable): ThrowableSnapshot = ThrowableSnapshot(
            type = throwable::class.java.name,
            message = throwable.message,
            stackTrace = throwable.stackTraceToString()
        )
    }
}

internal object PayloadConverter {
    fun toJson(payload: Map<String, Any?>): JsonElement = buildJsonObject {
        payload.entries.sortedBy { it.key }.forEach { (key, value) ->
            put(key, value.toJsonElement())
        }
    }

    private fun Any?.toJsonElement(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Enum<*> -> JsonPrimitive(this.name)
        is Map<*, *> -> buildJsonObject {
            @Suppress("UNCHECKED_CAST")
            (this@toJsonElement as Map<String, Any?>).forEach { (k, v) ->
                put(k, v.toJsonElement())
            }
        }
        is Iterable<*> -> buildJsonArray {
            this@toJsonElement.forEach { add(it.toJsonElement()) }
        }
        is Array<*> -> buildJsonArray {
            this@toJsonElement.forEach { add(it.toJsonElement()) }
        }
        else -> JsonPrimitive(this.toString())
    }
}
