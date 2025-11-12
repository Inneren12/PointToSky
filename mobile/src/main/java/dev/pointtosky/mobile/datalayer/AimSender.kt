package dev.pointtosky.mobile.datalayer

import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AimTargetBodyPayload
import dev.pointtosky.core.datalayer.AimTargetEquatorialPayload
import dev.pointtosky.core.datalayer.AimTargetKind
import dev.pointtosky.core.datalayer.AimTargetStarPayload
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_AIM_SET_TARGET

/**
 * Формирование и отправка сообщений /aim/set_target c телефона.
 * Телефон сразу присылает уже вычисленные RA/Dec (предпочтительно).
 *
 * Использует актуальный API:
 *   MobileBridge.Sender.send(path) { cid -> ByteArray }
 */
object AimSender {
    fun sendEquatorial(
        bridge: MobileBridge.Sender,
        raDeg: Double,
        decDeg: Double,
    ): MobileBridge.Sender.Ack? {
        return bridge.send(PATH_AIM_SET_TARGET) { cid ->
            val msg =
                AimSetTargetMessage(
                    cid = cid,
                    kind = AimTargetKind.EQUATORIAL,
                    payload = JsonCodec.encodeToElement(AimTargetEquatorialPayload(raDeg, decDeg)),
                )
            JsonCodec.encode(msg)
        }
    }

    fun sendBody(
        bridge: MobileBridge.Sender,
        bodyName: String,
    ): MobileBridge.Sender.Ack? {
        return bridge.send(PATH_AIM_SET_TARGET) { cid ->
            val msg =
                AimSetTargetMessage(
                    cid = cid,
                    kind = AimTargetKind.BODY,
                    payload = JsonCodec.encodeToElement(AimTargetBodyPayload(bodyName)),
                )
            JsonCodec.encode(msg)
        }
    }

    fun sendStar(
        bridge: MobileBridge.Sender,
        id: Int,
        raDeg: Double,
        decDeg: Double,
    ): MobileBridge.Sender.Ack? {
        return bridge.send(PATH_AIM_SET_TARGET) { cid ->
            val msg =
                AimSetTargetMessage(
                    cid = cid,
                    kind = AimTargetKind.STAR,
                    payload =
                        JsonCodec.encodeToElement(
                            AimTargetStarPayload(id = id.toString(), raDeg = raDeg, decDeg = decDeg),
                        ),
                )
            JsonCodec.encode(msg)
        }
    }
}
