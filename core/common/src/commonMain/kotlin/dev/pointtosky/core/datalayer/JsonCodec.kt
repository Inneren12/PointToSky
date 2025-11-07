package dev.pointtosky.core.datalayer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Shared JSON codec for the watch ↔ phone bridge.
 */
object JsonCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun <T> encode(value: T, serializer: KSerializer<T>): ByteArray {
        val jsonString = json.encodeToString(serializer, value)
        return jsonString.encodeToByteArray()
    }

    inline fun <reified T> encode(value: T): ByteArray =
        json.encodeToString(value).encodeToByteArray()

    fun <T> decode(bytes: ByteArray, serializer: KSerializer<T>): T =
        json.decodeFromString(serializer, bytes.decodeToString())

    inline fun <reified T> decode(bytes: ByteArray): T =
        json.decodeFromString(bytes.decodeToString())

    fun <T> encodeToElement(value: T, serializer: KSerializer<T>): JsonElement =
        json.encodeToJsonElement(serializer, value)

    inline fun <reified T> encodeToElement(value: T): JsonElement =
        json.encodeToJsonElement(value)

    fun <T> decodeFromElement(element: JsonElement, serializer: KSerializer<T>): T =
        json.decodeFromJsonElement(serializer, element)

    inline fun <reified T> decodeFromElement(element: JsonElement): T =
        json.decodeFromJsonElement(element)
}
