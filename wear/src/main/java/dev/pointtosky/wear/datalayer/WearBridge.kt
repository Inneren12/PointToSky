package dev.pointtosky.wear.datalayer

import android.content.Context
import android.content.Intent
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.wear.MainActivity
import dev.pointtosky.wear.ACTION_OPEN_AIM
import dev.pointtosky.wear.EXTRA_AIM_BODY
import dev.pointtosky.wear.EXTRA_AIM_DEC_DEG
import dev.pointtosky.wear.EXTRA_AIM_RA_DEG
import dev.pointtosky.wear.EXTRA_AIM_TARGET_KIND
import dev.pointtosky.wear.aim.core.AimTarget
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
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

    fun aimLaunches(): Flow<AimLaunchRequest> = aimRequests.asSharedFlow()

    fun emitAimTarget(target: AimTarget) {
        val seq = aimSequence.incrementAndGet()
        aimRequests.tryEmit(AimLaunchRequest(seq = seq, cid = "", target = target))
    }

    /**
     * Вызывается из BridgeV1 (watch‑side) при получении /aim/set_target.
     * Поддерживает минимальный JSON (v=1, kind, payload).
     */
    fun handleAimSetTargetJson(context: Context, payloadBytes: ByteArray, cid: String) {
        runCatching {
            val obj = JSONObject(payloadBytes.decodeToString())
            val kind = obj.optString("kind")
            val payload = obj.optJSONObject("payload") ?: JSONObject()
            val target: AimTarget? = when (kind) {
                "equatorial" -> {
                    val ra = payload.optDouble("raDeg")
                    val dec = payload.optDouble("decDeg")
                    AimTarget.EquatorialTarget(Equatorial(ra, dec))
                }
                "body" -> {
                    val body = payload.optString("body")
                    runCatching { Body.valueOf(body) }.getOrNull()?.let { AimTarget.BodyTarget(it) }
                }
                else -> null
            }
            if (target != null) {
                val seq = aimSequence.incrementAndGet()
                aimRequests.tryEmit(AimLaunchRequest(seq = seq, cid = cid, target = target))
                launchAim(context, target)
            } else {
                LogBus.event("dl_error", mapOf("err" to "unknown_target", "kind" to kind))
            }
        }.onFailure { e ->
            LogBus.event("dl_error", mapOf("err" to (e.message ?: e::class.java.simpleName)))
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
}

data class AimLaunchRequest(
    val seq: Long,
    val cid: String,
    val target: AimTarget,
)
