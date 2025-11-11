@file:Suppress("DEPRECATION")

package dev.pointtosky.wear.tile.tonight

import android.content.Context
import android.os.SystemClock
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.DeviceParametersBuilders
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.tiles.material.CompactChip
import androidx.wear.tiles.material.Text
import androidx.wear.tiles.material.layouts.PrimaryLayout
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.time.ZoneRepo
import dev.pointtosky.wear.R
import dev.pointtosky.wear.datalayer.WearMessageBridge
import dev.pointtosky.wear.settings.AimIdentifySettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

open class TonightTileService : TileService() {

    companion object {
        // Инкремент при изменении набора ресурсов (иконки, имена id и т.п.)
        private const val RES_VER = "tonight_v2"
        const val ACTION_OPEN_AIM = "dev.pointtosky.ACTION_OPEN_AIM"
        const val ACTION_OPEN_IDENTIFY = "dev.pointtosky.ACTION_OPEN_IDENTIFY"
        const val EXTRA_TARGET_ID = "targetId"
        private const val PATH_PUSH = "/tile/tonight/push_model"

        private fun iconResIdString(icon: TonightIcon): String = when (icon) {
            TonightIcon.SUN -> "ic_tonight_sun"
            TonightIcon.MOON -> "ic_tonight_moon"
            TonightIcon.JUPITER -> "ic_tonight_jupiter"
            TonightIcon.SATURN -> "ic_tonight_saturn"
            TonightIcon.STAR -> "ic_tonight_star"
            TonightIcon.CONST -> "ic_tonight_const"
        }

        private fun iconAndroidRes(icon: TonightIcon): Int = when (icon) {
            TonightIcon.SUN -> R.drawable.ic_tonight_sun
            TonightIcon.MOON -> R.drawable.ic_tonight_moon
            TonightIcon.JUPITER -> R.drawable.ic_tonight_jupiter
            TonightIcon.SATURN -> R.drawable.ic_tonight_saturn
            TonightIcon.STAR -> R.drawable.ic_tonight_star
            TonightIcon.CONST -> R.drawable.ic_tonight_const
        }
    }

    // S7.C: реальный офлайн‑провайдер с кэшем.
    private val provider: TonightProvider by lazy {
        val zoneRepo = ZoneRepo(this)
        val ephemeris = SimpleEphemerisComputer()
        // StarCatalog здесь опционален → передаём null (провайдер использует дефолтные цели).
        RealTonightProvider(
            this,
            zoneRepo,
            ephemeris,
            null,
        )
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val startRealtime = SystemClock.elapsedRealtime()
        LogBus.event("tile_get_tile_start")
        return try {
            val model = runBlocking { provider.getModel(Instant.now()) }
            TonightTileDebug.update(model)

            // S7.E: Mirroring — отправка короткой модели на телефон
            runBlocking {
                val settings = AimIdentifySettingsDataStore(applicationContext)
                val mirroring = settings.tileMirroringEnabledFlow.first()
                if (mirroring) {
                    val json = buildPushModelJson(model)
                    runCatching {
                        WearMessageBridge.sendToPhone(
                            applicationContext,
                            PATH_PUSH,
                            json.toByteArray(Charsets.UTF_8),
                        )
                    }.onSuccess {
                        LogBus.event(
                            name = "tile_push_model",
                            payload = mapOf("targetsCount" to model.items.size),
                        )
                    }.onFailure { e ->
                        LogBus.event(
                            name = "tile_error",
                            payload = mapOf(
                                "err" to e.toLogMessage(),
                                "stage" to "push_model",
                            ),
                        )
                    }
                }
            }

            // S7.B: используем Material PrimaryLayout с учётом форм-фактора
            val root = buildPrimaryLayoutRoot(
                context = this,
                model = model,
                deviceParams = requestParams.deviceParameters,
            )
            val layout = LayoutElementBuilders.Layout.Builder().setRoot(root).build()
            val entry = TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()
            val timeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(entry).build()
            Futures.immediateFuture(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion(RES_VER)
                    // Резервный периодический апдейт от платформы (на случай промаха воркера)
                    .setFreshnessIntervalMillis(45L * 60_000L)
                    .setTimeline(timeline)
                    .build(),
            )
        } catch (e: JSONException) {
            LogBus.event(
                name = "tile_error",
                payload = mapOf(
                    "err" to e.toLogMessage(),
                    "stage" to "on_tile_request_json",
                ),
            )
            throw e
        } catch (e: SecurityException) {
            LogBus.event(
                name = "tile_error",
                payload = mapOf(
                    "err" to e.toLogMessage(),
                    "stage" to "on_tile_request_security",
                ),
            )
            throw e
        } catch (e: IllegalStateException) {
            LogBus.event(
                name = "tile_error",
                payload = mapOf(
                    "err" to e.toLogMessage(),
                    "stage" to "on_tile_request_state",
                ),
            )
            throw e
        } finally {
            val durationMs = SystemClock.elapsedRealtime() - startRealtime
            LogBus.event("tile_get_tile_end", mapOf("durationMs" to durationMs))
        }
    }

    @Deprecated("Tiles v1 API; kept for backward compatibility. Consider migrating to ProtoLayout.")
    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val res = ResourceBuilders.Resources.Builder().setVersion(RES_VER)
        listOf(
            TonightIcon.SUN,
            TonightIcon.MOON,
            TonightIcon.JUPITER,
            TonightIcon.SATURN,
            TonightIcon.STAR,
            TonightIcon.CONST,
        )
            .forEach { icon ->
                res.addIdToImageMapping(
                    iconResIdString(icon),
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(iconAndroidRes(icon))
                                .build(),
                        ).build(),
                )
            }
        return Futures.immediateFuture(res.build())
    }

    /**
     * Корневой Material-layout для тайла:
     *  • Заголовок: "Сегодня ночью"/"Tonight"
     *  • Контент: 2–3 строки целей (иконка + primary + secondary)
     *  • Чип внизу: "Ещё" → TonightTargetsActivity
     */
    private fun buildPrimaryLayoutRoot(
        context: Context,
        model: TonightTileModel,
        deviceParams: DeviceParametersBuilders.DeviceParameters?,
    ): LayoutElementBuilders.LayoutElement {
        // список элементов
        val listColumn = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.ExpandedDimensionProp.Builder().build())
            .setHeight(DimensionBuilders.WrappedDimensionProp.Builder().build())

        model.items.forEachIndexed { idx, item ->
            if (idx > 0) {
                listColumn.addContent(
                    LayoutElementBuilders.Spacer.Builder()
                        .setHeight(DimensionBuilders.DpProp.Builder().setValue(6f).build())
                        .build(),
                )
            }
            listColumn.addContent(buildTargetRow(context, item))
        }

        val dp = deviceParams
        if (dp == null) {
            // На всякий случай: вернём только контент без PrimaryLayout.
            return listColumn.build()
        }

        val moreClickable = clickableOpenTargetsActivity(context)
        val moreChip = CompactChip.Builder(
            context,
            getString(R.string.tile_more),
            moreClickable,
            dp,
        ).build()

        val title = Text.Builder(context, getString(R.string.tile_tonight_label)).build()

        return PrimaryLayout.Builder(dp)
            .setPrimaryLabelTextContent(title)
            .setContent(listColumn.build())
            .setPrimaryChipContent(moreChip)
            .build()
    }

    /**
     * Один элемент списка: иконка + два текста.
     * Делает строку кликабельной (по желанию можно перевести на no-op).
     */
    private fun buildTargetRow(context: Context, item: TonightTarget): LayoutElementBuilders.LayoutElement {
        val click = clickableOpenAim(context, item.id)
        val subtitle = item.subtitle ?: formatAzAlt(item.azDeg, item.altDeg)

        val row = LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.ExpandedDimensionProp.Builder().build())
            .setHeight(DimensionBuilders.WrappedDimensionProp.Builder().build())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(click).build())

        // Иконка
        row.addContent(
            LayoutElementBuilders.Image.Builder()
                .setResourceId(iconResIdString(item.icon))
                .setWidth(DimensionBuilders.DpProp.Builder().setValue(20f).build())
                .setHeight(DimensionBuilders.DpProp.Builder().setValue(20f).build())
                .build(),
        )
        // Отступ
        row.addContent(
            LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.DpProp.Builder().setValue(8f).build())
                .build(),
        )
        // Тексты
        val textCol = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.ExpandedDimensionProp.Builder().build())
        // Primary (Material Text)
        textCol.addContent(
            Text.Builder(context, item.title).build(),
        )
        if (subtitle != null) {
            textCol.addContent(
                Text.Builder(context, subtitle).build(),
            )
        }
        row.addContent(textCol.build())
        return row.build()
    }

    /** Клик на цель AIM (использует константу ACTION_OPEN_AIM). */
    private fun clickableOpenAim(context: Context, targetId: String?): ModifiersBuilders.Clickable {
        val activity: ActionBuilders.AndroidActivity =
            ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.packageName)
                .setClassName("dev.pointtosky.wear.tile.tonight.TileEntryActivity")
                .build()
        val launch: ActionBuilders.LaunchAction =
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(activity)
                .build()
        return ModifiersBuilders.Clickable.Builder()
            .setOnClick(launch)
            .setId(targetId?.let { "$ACTION_OPEN_AIM:$it" } ?: ACTION_OPEN_AIM)
            .build()
    }

    /** Клик, открывающий список целей TonightTargetsActivity. */
    private fun clickableOpenTargetsActivity(context: Context): ModifiersBuilders.Clickable {
        val activity: ActionBuilders.AndroidActivity =
            ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.packageName)
                .setClassName("dev.pointtosky.wear.tile.tonight.TonightTargetsActivity")
                .build()
        val launch: ActionBuilders.LaunchAction =
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(activity)
                .build()
        return ModifiersBuilders.Clickable.Builder()
            .setId("open_tonight_list")
            .setOnClick(launch)
            .build()
    }

    private fun formatAzAlt(az: Double?, alt: Double?): String? {
        if (az == null || alt == null) return null
        fun fmt(x: Double) = "%1$.0f°".format(x)
        return "Az ${fmt(az)} • Alt ${fmt(alt)}"
    }

    /** Короткий JSON payload для mirroring. */
    private fun buildPushModelJson(model: TonightTileModel): String {
        val root = JSONObject()
        root.put("updatedAt", model.updatedAt.epochSecond)
        val arr = JSONArray()
        model.items.forEach { t ->
            val o = JSONObject()
            o.put("id", t.id)
            o.put("title", t.title)
            if (!t.subtitle.isNullOrBlank()) o.put("subtitle", t.subtitle)
            o.put("icon", t.icon.name)
            arr.put(o)
        }
        root.put("items", arr)
        return root.toString()
    }

    private fun Throwable.toLogMessage(): String = message ?: javaClass.simpleName
}
