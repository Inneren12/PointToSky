package dev.pointtosky.wear.datalayer

import android.content.Context
import android.content.Intent
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AimTargetBodyPayload
import dev.pointtosky.core.datalayer.AimTargetEquatorialPayload
import dev.pointtosky.core.datalayer.AimTargetKind
import dev.pointtosky.core.datalayer.AppOpenMessage
import dev.pointtosky.core.datalayer.AppOpenScreen
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.wear.MainActivity
import dev.pointtosky.wear.ACTION_OPEN_AIM
import dev.pointtosky.wear.ACTION_OPEN_IDENTIFY
import dev.pointtosky.wear.EXTRA_AIM_BODY
import dev.pointtosky.wear.EXTRA_AIM_DEC_DEG
import dev.pointtosky.wear.EXTRA_AIM_RA_DEG
import dev.pointtosky.wear.EXTRA_AIM_TARGET_KIND
import dev.pointtosky.wear.aim.core.AimTarget
import dev.pointtosky.wear.tile.tonight.TonightTargetsActivity
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Лёгкий фасад для событий запуска Aim на часах.
 * Больше не зависит от старого ReliableDataLayerBridge; используется BridgeV1ListenerService,
 * который вызывает [handleAimSetTargetJson] при сообщении /aim/set_target.
 */
object WearBridge {
    private val aimRequests = MutableSharedFlow<AimLaunchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val aimSequence = AtomicLong(0L)

    private val appOpenRequests = MutableSharedFlow<AppOpenRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val appOpenSequence = AtomicLong(0L)

    fun aimLaunches(): Flow<AimLaunchRequest> = aimRequests.asSharedFlow()

    fun appOpens(): Flow<AppOpenRequest> = appOpenRequests.asSharedFlow()

    fun emitAimTarget(target: AimTarget) {
        val seq = aimSequence.incrementAndGet()
        aimRequests.tryEmit(AimLaunchRequest(seq = seq, cid = "", target = target))
    }

    fun emitAppOpen(screen: AppOpenScreen, target: AimTarget?) {
        val seq = appOpenSequence.incrementAndGet()
        appOpenRequests.tryEmit(
            AppOpenRequest(
                seq = seq,
                cid = "",
                screen = screen,
                target = target,
            ),
        )
    }

    fun handleAimSetTargetMessage(context: Context, message: AimSetTargetMessage) {
        val target = parseAimTarget(message.kind, message.payload)
        if (target != null) {
            val seq = aimSequence.incrementAndGet()
            aimRequests.tryEmit(AimLaunchRequest(seq = seq, cid = message.cid, target = target))
            launchAim(context, target)
        } else {
            LogBus.event(
                "dl_error",
                mapOf("err" to "unknown_target", "kind" to message.kind.name),
            )
        }
    }

    fun handleAppOpenMessage(context: Context, message: AppOpenMessage) {
        val target = message.target?.let { parseAimTarget(it.kind, it.payload) }
        val seq = appOpenSequence.incrementAndGet()
        appOpenRequests.tryEmit(
            AppOpenRequest(
                seq = seq,
                cid = message.cid,
                screen = message.screen,
                target = target,
            ),
        )

        when (message.screen) {
            AppOpenScreen.AIM -> {
                if (target != null) {
                    launchAim(context, target)
                } else {
                    openAim(context)
                }
            }
            AppOpenScreen.IDENTIFY -> openIdentify(context)
            AppOpenScreen.TILE -> openTileTargets(context)
        }
    }

    fun launchAim(context: Context, target: AimTarget) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_AIM
            when (target) {
                is AimTarget.EquatorialTarget -> {
                    putExtra(EXTRA_AIM_TARGET_KIND, "equatorial")
                    putExtra(EXTRA_AIM_RA_DEG, target.eq.raDeg)
                    putExtra(EXTRA_AIM_DEC_DEG, target.eq.decDeg)
                }
                is AimTarget.BodyTarget -> {
                    putExtra(EXTRA_AIM_TARGET_KIND, "body")
                    putExtra(EXTRA_AIM_BODY, target.body.name)
                }
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openAim(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_AIM
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openIdentify(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_IDENTIFY
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun openTileTargets(context: Context) {
        val intent = Intent(context, TonightTargetsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun parseAimTarget(kind: AimTargetKind, payload: kotlinx.serialization.json.JsonElement): AimTarget? {
        return when (kind) {
            AimTargetKind.EQUATORIAL -> {
                runCatching {
                    val dto = JsonCodec.decodeFromElement<AimTargetEquatorialPayload>(payload)
                    AimTarget.EquatorialTarget(Equatorial(dto.raDeg, dto.decDeg))
                }.getOrNull()
            }
            AimTargetKind.BODY -> {
                val bodyName = runCatching {
                    JsonCodec.decodeFromElement<AimTargetBodyPayload>(payload)
                }.getOrNull()?.body ?: return null
                runCatching { Body.valueOf(bodyName) }.getOrNull()?.let { AimTarget.BodyTarget(it) }
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

data class AppOpenRequest(
    val seq: Long,
    val cid: String,
    val screen: AppOpenScreen,
    val target: AimTarget?,
)
