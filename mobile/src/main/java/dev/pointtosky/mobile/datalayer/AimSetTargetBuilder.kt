package dev.pointtosky.mobile.datalayer

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AimTargetKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Утилита для формирования сообщений /aim/set_target на стороне телефона.
 * Сериализацию в bytes и отправку выполняйте через ваш существующий мост (например, MobileBridge).
 */
object AimSetTargetBuilder {

    fun newCid(): String = UUID.randomUUID().toString()

    /** EQUATORIAL: RA/Dec в градусах (J2000). */
    fun buildEquatorial(cid: String = newCid(), eq: Equatorial): AimSetTargetMessage {
        val payload = buildJsonObject {
            put("raDeg", eq.raDeg)
            put("decDeg", eq.decDeg)
        }
        return AimSetTargetMessage(
            cid = cid,
            kind = AimTargetKind.EQUATORIAL,
            payload = payload,
        )
    }

    /** BODY: целимся на тело Солнечной системы. */
    fun buildBody(cid: String = newCid(), body: Body): AimSetTargetMessage {
        val payload = buildJsonObject { put("body", body.name) }
        return AimSetTargetMessage(
            cid = cid,
            kind = AimTargetKind.BODY,
            payload = payload,
        )
    }

    /**
     * STAR: телефон присылает id + уже вычисленные координаты.
     * Если координаты неизвестны, передайте только id — часы попробуют офлайн‑резолв (если настроен).
     */
    fun buildStar(cid: String = newCid(), starId: Int, eq: Equatorial?): AimSetTargetMessage {
        val payload = buildJsonObject {
            put("id", starId.toString())
            eq?.let {
                put("raDeg", it.raDeg)
                put("decDeg", it.decDeg)
            }
        }
        return AimSetTargetMessage(
            cid = cid,
            kind = AimTargetKind.STAR,
            payload = payload,
        )
    }
}
