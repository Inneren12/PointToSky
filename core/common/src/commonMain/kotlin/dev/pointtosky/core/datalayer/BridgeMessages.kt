package dev.pointtosky.core.datalayer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val DATA_LAYER_PROTOCOL_VERSION: Int = 1

const val PATH_AIM_SET_TARGET: String = "/aim/set_target"
const val PATH_AIM_LOCK_EVENT: String = "/aim/lock_event"
const val PATH_IDENTIFY_RESULT: String = "/identify/result"
const val PATH_CARD_OPEN: String = "/card/open"
const val PATH_TILE_TONIGHT_PUSH_MODEL: String = "/tile/tonight/push_model"
const val PATH_ACK: String = "/ack"

@Serializable
enum class AimTargetKind {
    @SerialName("equatorial")
    EQUATORIAL,

    @SerialName("body")
    BODY,

    @SerialName("star")
    STAR,
}

@Serializable
data class AimSetTargetMessage(
    val v: Int = DATA_LAYER_PROTOCOL_VERSION,
    val cid: String,
    val kind: AimTargetKind,
    val payload: JsonElement,
)

@Serializable
data class AimTargetEquatorialPayload(
    val raDeg: Double,
    val decDeg: Double,
)

@Serializable
data class AimTargetBodyPayload(
    val body: String,
)

@Serializable
data class AimTargetStarPayload(
    val id: String,
)

@Serializable
data class AimLockEventMessage(
    val v: Int = DATA_LAYER_PROTOCOL_VERSION,
    val cid: String,
    val target: JsonElement,
    @SerialName("when") val whenEpochMs: Long,
)

@Serializable
data class IdentifyResultMessage(
    val v: Int = DATA_LAYER_PROTOCOL_VERSION,
    val cid: String,
    val eq: EquatorialDto? = null,
    val best: IdentifyBestMatch,
)

@Serializable
data class IdentifyBestMatch(
    val type: String,
    val name: String,
    val mag: Double? = null,
    val sepDeg: Double? = null,
)

@Serializable
data class EquatorialDto(
    val raDeg: Double,
    val decDeg: Double,
)

@Serializable
data class CardOpenMessage(
    val v: Int = DATA_LAYER_PROTOCOL_VERSION,
    val cid: String,
    @SerialName("object") val obj: JsonElement,
)

@Serializable
data class TileTonightPushModelMessage(
    val v: Int = DATA_LAYER_PROTOCOL_VERSION,
    val cid: String? = null,
    val payload: JsonElement,
)

@Serializable
data class AckMessage(
    val v: Int = DATA_LAYER_PROTOCOL_VERSION,
    val refCid: String,
    val ok: Boolean,
    val err: String? = null,
)
