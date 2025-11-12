package dev.pointtosky.mobile.datalayer

import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AimTargetBodyPayload
import dev.pointtosky.core.datalayer.AimTargetEquatorialPayload
import dev.pointtosky.core.datalayer.AimTargetKind
import dev.pointtosky.core.datalayer.JsonCodec

object DemoAimTargets {
    fun list(): List<AimTargetOption> =
        listOf(
            AimTargetOption(
                id = "polaris",
                label = "Polaris",
                buildMessage = { cid ->
                    AimSetTargetMessage(
                        cid = cid,
                        kind = AimTargetKind.EQUATORIAL,
                        payload =
                            JsonCodec.encodeToElement(
                                AimTargetEquatorialPayload(
                                    raDeg = 37.95456067,
                                    decDeg = 89.26410897,
                                ),
                            ),
                    )
                },
            ),
            AimTargetOption(
                id = "sun",
                label = "Sun",
                buildMessage = { cid ->
                    AimSetTargetMessage(
                        cid = cid,
                        kind = AimTargetKind.BODY,
                        payload =
                            JsonCodec.encodeToElement(
                                AimTargetBodyPayload(body = Body.SUN.name),
                            ),
                    )
                },
            ),
        )
}

data class AimTargetOption(
    val id: String,
    val label: String,
    val buildMessage: (cid: String) -> AimSetTargetMessage,
)
