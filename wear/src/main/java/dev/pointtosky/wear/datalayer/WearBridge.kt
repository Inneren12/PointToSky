package dev.pointtosky.wear.datalayer

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.TaskStackBuilder
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AimTargetBodyPayload
import dev.pointtosky.core.datalayer.AimTargetEquatorialPayload
import dev.pointtosky.core.datalayer.AimTargetKind
import dev.pointtosky.core.datalayer.AimTargetStarPayload
import dev.pointtosky.core.datalayer.AppOpenMessage
import dev.pointtosky.core.datalayer.AppOpenScreen
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_AIM_SET_TARGET
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.wear.aim.core.AimTarget
import dev.pointtosky.wear.buildOpenAimIntent
import dev.pointtosky.wear.buildOpenIdentifyIntent
import dev.pointtosky.wear.datalayer.WearBridge.handleAimSetTargetJson
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
    private val aimRequests =
        MutableSharedFlow<AimLaunchRequest>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val aimSequence = AtomicLong(0L)

    private val appOpenRequests =
        MutableSharedFlow<AppOpenRequest>(
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

    fun emitAppOpen(
        screen: AppOpenScreen,
        target: AimTarget?,
    ) {
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

    // --- Back-compat: алиасы для старых вызовов ---
    fun handleAimSetTargetJson(
        context: Context,
        json: String,
    ) {
        val msg =
            runCatching {
                JsonCodec.decode<AimSetTargetMessage>(json.toByteArray(Charsets.UTF_8))
            }.getOrElse { t ->
                LogBus.event(
                    "dl_error",
                    mapOf(
                        "err" to (t.message ?: t::class.java.simpleName),
                        "path" to PATH_AIM_SET_TARGET,
                    ),
                )
                return
            }
        handleAimSetTargetMessage(context, msg)
    }

    fun handleAimSetTargetJson(
        context: Context,
        bytes: ByteArray,
    ) {
        val msg =
            runCatching { JsonCodec.decode<AimSetTargetMessage>(bytes) }
                .getOrElse { t ->
                    LogBus.event(
                        "dl_error",
                        mapOf(
                            "err" to (t.message ?: t::class.java.simpleName),
                            "path" to PATH_AIM_SET_TARGET,
                        ),
                    )
                    return
                }
        handleAimSetTargetMessage(context, msg)
    }

    fun handleAimSetTargetMessage(
        context: Context,
        message: AimSetTargetMessage,
    ) {
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

    fun handleAppOpenMessage(
        context: Context,
        message: AppOpenMessage,
    ) {
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

    fun launchAim(
        context: Context,
        target: AimTarget,
    ) {
        val intent = buildOpenAimIntent(context, target)
        startActivitySafe(context, intent)
    }

    private fun openAim(context: Context) {
        val intent = buildOpenAimIntent(context, null)
        startActivitySafe(context, intent)
    }

    private fun openIdentify(context: Context) {
        val intent = buildOpenIdentifyIntent(context)
        startActivitySafe(context, intent)
    }

    private fun openTileTargets(context: Context) {
        val intent = Intent(context, TonightTargetsActivity::class.java)
        startActivitySafe(context, intent)
    }

    private fun startActivitySafe(
        context: Context,
        intent: Intent,
    ) {
        if (context is Activity) {
            context.startActivity(intent)
            return
        }

        // Запускаем через стек и PendingIntent — без прямого NEW_TASK/CLEAR_TOP
        val stack =
            TaskStackBuilder.create(context).apply {
                // если в манифесте задан parentActivityName, сформирует корректный back stack
                addNextIntentWithParentStack(intent)
            }
        val pi =
            stack.getPendingIntent(
                0,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        try {
            pi?.send()
        } catch (_: PendingIntent.CanceledException) {
            // опционально: лог, фолбек на уведомление
        }
    }

    private fun parseAimTarget(
        kind: AimTargetKind,
        payload: kotlinx.serialization.json.JsonElement,
    ): AimTarget? {
        return when (kind) {
            AimTargetKind.EQUATORIAL -> {
                runCatching {
                    val dto = JsonCodec.decodeFromElement<AimTargetEquatorialPayload>(payload)
                    AimTarget.EquatorialTarget(Equatorial(dto.raDeg, dto.decDeg))
                }.getOrNull()
            }
            AimTargetKind.BODY -> {
                val bodyName =
                    runCatching {
                        JsonCodec.decodeFromElement<AimTargetBodyPayload>(payload)
                    }.getOrNull()?.body ?: return null
                runCatching { Body.valueOf(bodyName) }.getOrNull()?.let { AimTarget.BodyTarget(it) }
            }
            AimTargetKind.STAR -> {
                val dto =
                    runCatching {
                        JsonCodec.decodeFromElement<AimTargetStarPayload>(payload)
                    }.getOrNull() ?: return null
                val idInt = dto.id.toIntOrNull() ?: return null
                // локальные переменные — без смарт‑каста публичных свойств из другого модуля
                val ra = dto.raDeg
                val dec = dto.decDeg
                val eq = if (ra != null && dec != null) Equatorial(ra, dec) else null
                AimTarget.StarTarget(idInt, eq)
            }
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
