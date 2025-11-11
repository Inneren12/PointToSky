package dev.pointtosky.core.datalayer

import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Shared JSON codec for the watch ↔ phone bridge.
 */
object JsonCodec {
    /**
     * Вынесено как @PublishedApi, чтобы public inline-функции могли ссылаться
     * на него без ошибки "Public-API inline function cannot access non-public-API property".
     */
    @PublishedApi
    internal val JSON: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun <T> encode(value: T, serializer: KSerializer<T>): ByteArray {
        val jsonString = JSON.encodeToString(serializer, value)
        return jsonString.encodeToByteArray()
    }

    inline fun <reified T> encode(value: T): ByteArray =
        JSON.encodeToString(value).encodeToByteArray()

    fun <T> decode(bytes: ByteArray, serializer: KSerializer<T>): T =
        JSON.decodeFromString(serializer, bytes.decodeToString())

    inline fun <reified T> decode(bytes: ByteArray): T =
        JSON.decodeFromString(bytes.decodeToString())

    fun <T> encodeToElement(value: T, serializer: KSerializer<T>): JsonElement =
        JSON.encodeToJsonElement(serializer, value)

    inline fun <reified T> encodeToElement(value: T): JsonElement =
        JSON.encodeToJsonElement(value)

    fun <T> decodeFromElement(element: JsonElement, serializer: KSerializer<T>): T =
        JSON.decodeFromJsonElement(serializer, element)

    inline fun <reified T> decodeFromElement(element: JsonElement): T =
        JSON.decodeFromJsonElement(element)
}
