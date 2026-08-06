package dev.pointtosky.core.astro.projection.camera.skylog

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Typed [JsonObject] readers for the SKY-1 session-log codec.
 *
 * "Absent" and "null" mean the same thing to every accessor here, matching [skySessionLogJson]'s own
 * `explicitNulls = false` write behaviour: a field the writer omitted and a field it wrote as `null`
 * must not read back differently, or a log written by one version would decode differently from an
 * equivalent one written by another.
 *
 * The `required*` variants throw [IllegalArgumentException], which [parseSkySessionLogLine] catches
 * and turns into a [SkySessionLogLine.Unreadable] — so a malformed line is a reportable outcome, not
 * an exception escaping the parser.
 */

internal fun JsonObject.primitiveOrNull(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.string(key: String): String? = primitiveOrNull(key)?.content

internal fun JsonObject.int(key: String): Int? = primitiveOrNull(key)?.content?.toIntOrNull()

internal fun JsonObject.long(key: String): Long? = primitiveOrNull(key)?.content?.toLongOrNull()

internal fun JsonObject.double(key: String): Double? = primitiveOrNull(key)?.content?.toDoubleOrNull()

internal fun JsonObject.boolean(key: String): Boolean? = primitiveOrNull(key)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

internal fun JsonObject.requiredString(key: String): String =
    string(key) ?: throw IllegalArgumentException("missing \"$key\"")

internal fun JsonObject.requiredInt(key: String): Int =
    int(key) ?: throw IllegalArgumentException("missing or non-integer \"$key\"")

internal fun JsonObject.requiredLong(key: String): Long =
    long(key) ?: throw IllegalArgumentException("missing or non-integer \"$key\"")

internal fun JsonObject.requiredDouble(key: String): Double =
    double(key) ?: throw IllegalArgumentException("missing or non-numeric \"$key\"")

internal fun JsonObject.requiredDoubleList(key: String): List<Double> {
    val array = this[key] as? JsonArray ?: throw IllegalArgumentException("missing or non-array \"$key\"")
    return array.map { element ->
        (element as? JsonPrimitive)?.content?.toDoubleOrNull()
            ?: throw IllegalArgumentException("non-numeric element in \"$key\"")
    }
}

/** @throws IllegalArgumentException when the field is present but names no member of [values]. */
internal fun <E : Enum<E>> JsonObject.enum(
    key: String,
    values: List<E>,
): E? {
    val name = string(key) ?: return null
    return values.firstOrNull { it.name == name }
        ?: throw IllegalArgumentException("unknown \"$key\": $name")
}

internal fun <E : Enum<E>> JsonObject.requiredEnum(
    key: String,
    values: List<E>,
): E = enum(key, values) ?: throw IllegalArgumentException("missing \"$key\"")
