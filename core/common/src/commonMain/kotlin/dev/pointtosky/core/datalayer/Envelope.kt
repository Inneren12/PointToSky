package dev.pointtosky.core.datalayer

/**
 * Transport envelope for protocol messages.
 */
data class Envelope(
    val path: String,
    val bytes: ByteArray,
    val cid: String,
    val timestampMs: Long,
)
