package dev.pointtosky.wear.datalayer

import android.content.Context
import android.content.Intent
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AimTargetBodyPayload
import dev.pointtosky.core.datalayer.AimTargetEquatorialPayload
import dev.pointtosky.core.datalayer.AimTargetKind
import dev.pointtosky.core.datalayer.DATA_LAYER_PROTOCOL_VERSION
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_AIM_SET_TARGET
import dev.pointtosky.core.datalayer.ReliableDataLayerBridge
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.wear.ACTION_OPEN_AIM
import dev.pointtosky.wear.EXTRA_AIM_BODY
import dev.pointtosky.wear.EXTRA_AIM_DEC_DEG
import dev.pointtosky.wear.EXTRA_AIM_RA_DEG
import dev.pointtosky.wear.EXTRA_AIM_TARGET_KIND
import dev.pointtosky.wear.aim.core.AimTarget
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object WearBridge {
    @Volatile
    private var instance: ReliableDataLayerBridge? = null

    private val aimRequests = MutableSharedFlow<AimLaunchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val aimSequence = AtomicLong(0L)

    fun get(context: Context): ReliableDataLayerBridge {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    fun aimLaunches(): Flow<AimLaunchRequest> = aimRequests.asSharedFlow()
    fun emitAimTarget(target: AimTarget) {
        val seq = aimSequence.incrementAndGet()
        aimRequests.tryEmit(
            AimLaunchRequest(
                seq = seq,
                cid = "",
                target = target,
            ),
        )
    }

    private fun create(context: Context): ReliableDataLayerBridge {
        val bridge = ReliableDataLayerBridge(
            context = context,
            logger = { name, payload -> LogBus.event(name, payload) },
        )
        bridge.registerHandler(PATH_AIM_SET_TARGET) { envelope ->
            val message = runCatching {
                JsonCodec.decode<AimSetTargetMessage>(envelope.bytes)
            }.getOrNull() ?: return@registerHandler false
            if (message.v != DATA_LAYER_PROTOCOL_VERSION) return@registerHandler false
            val target = parseAimTarget(message) ?: return@registerHandler false
            val seq = aimSequence.incrementAndGet()
            aimRequests.tryEmit(
                AimLaunchRequest(
                    seq = seq,
                    cid = message.cid,
                    target = target,
                ),
            )
            launchAim(context, target)
            true
        }
        return bridge
    }

    private fun launchAim(context: Context, target: AimTarget) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_AIM
            putExtra(EXTRA_AIM_TARGET_KIND, when (target) {
                is AimTarget.EquatorialTarget -> "equatorial"
                is AimTarget.BodyTarget -> "body"
            })
            when (target) {
                is AimTarget.EquatorialTarget -> {
                    putExtra(EXTRA_AIM_RA_DEG, target.eq.raDeg)
                    putExtra(EXTRA_AIM_DEC_DEG, target.eq.decDeg)
                }
                is AimTarget.BodyTarget -> {
                    putExtra(EXTRA_AIM_BODY, target.body.name)
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun parseAimTarget(message: AimSetTargetMessage): AimTarget? {
        return when (message.kind) {
            AimTargetKind.EQUATORIAL -> {
                val payload = runCatching {
                    JsonCodec.decode<AimTargetEquatorialPayload>(message.payload)
                }.getOrNull() ?: return null
                AimTarget.EquatorialTarget(
                    Equatorial(raDeg = payload.raDeg, decDeg = payload.decDeg),
                )
            }
            AimTargetKind.BODY -> {
                val payload = runCatching {
                    JsonCodec.decode<AimTargetBodyPayload>(message.payload)
                }.getOrNull() ?: return null
                val body = runCatching { Body.valueOf(payload.body) }.getOrNull() ?: return null
                AimTarget.BodyTarget(body)
            }
            AimTargetKind.STAR -> null
        }
    }
}

data class AimLaunchRequest(
    val seq: Long,
    val cid: String,
    val target: AimTarget,
)
