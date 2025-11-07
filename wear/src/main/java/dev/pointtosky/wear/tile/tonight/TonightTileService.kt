package dev.pointtosky.wear.tile.tonight

import android.content.Context
import android.util.Log
import java.time.Instant
import dev.pointtosky.core.time.SystemTimeSource
import dev.pointtosky.core.time.ZoneRepo
import androidx.wear.tiles.TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.DeviceParametersBuilders
import androidx.wear.tiles.material.layouts.PrimaryLayout
import androidx.wear.tiles.material.CompactChip
import androidx.wear.tiles.material.Text
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.pointtosky.wear.R
import dev.pointtosky.wear.datalayer.WearMessageBridge
import dev.pointtosky.wear.settings.AimIdentifySettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

class TonightTileService : TileService() {

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
        val time = SystemTimeSource()
        val zoneRepo = ZoneRepo(this)
        // Локация берётся через Orchestrator в app-слое; для тайла — мягкий лямбда‑геттер.
        // Если интеграции нет, вернётся null и сработает фолбэк (Moon + Vega).
        val getLastKnownLocation: suspend () -> dev.pointtosky.core.location.model.GeoPoint? = { null }
        RealTonightProvider(context = this, timeSource = time, zoneRepo = zoneRepo, getLastKnownLocation = getLastKnownLocation)
    }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val model = runBlocking { provider.getModel(Instant.now()) }
        // S7.E: Mirroring — отправка короткой модели на телефон
        runBlocking {
            val settings = AimIdentifySettingsDataStore(applicationContext)
            val mirroring = settings.tileMirroringEnabledFlow.first()
            if (mirroring) {
                val json = buildPushModelJson(model)
                runCatching {
                    WearMessageBridge.sendToPhone(applicationContext, PATH_PUSH, json.toByteArray(Charsets.UTF_8))
                }.onFailure { e -> Log.w("TonightTile", "push_model failed", e) }
            }
        }

        // S7.B: используем Material PrimaryLayout с учётом форм-фактора
        val root = buildPrimaryLayoutRoot(
            context = this,
            model = model,
            deviceParams = requestParams.deviceParameters
        )
        val layout = LayoutElementBuilders.Layout.Builder().setRoot(root).build()
        val entry = TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()
        val timeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(entry).build()
        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VER)
                // Резервный периодический апдейт от платформы (на случай промаха воркера)
                .setFreshnessIntervalMillis(45L * 60_000L)
                .setTimeline(timeline)
                .build()
        )
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        val res = ResourceBuilders.Resources.Builder().setVersion(RES_VER)
        listOf(TonightIcon.SUN, TonightIcon.MOON, TonightIcon.JUPITER,
            TonightIcon.SATURN, TonightIcon.STAR, TonightIcon.CONST)
            .forEach { icon ->
                res.addIdToImageMapping(
                    iconResIdString(icon),
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(iconAndroidRes(icon))
                                .build()
                        ).build()
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
        deviceParams: DeviceParametersBuilders.DeviceParameters?
    ): LayoutElementBuilders.LayoutElement {
        // список элементов
        val listColumn = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.ExpandedDimensionProp.Builder().build())
            .setHeight(DimensionBuilders.WrappedDimensionProp.Builder().build())

        model.items.take(3).forEachIndexed { idx, item ->
            if (idx > 0) {
                listColumn.addContent(
                    LayoutElementBuilders.Spacer.Builder()
                        .setHeight(DimensionBuilders.DpProp.Builder().setValue(6f).build())
                        .build()
                )
            }
            listColumn.addContent(buildTargetRow(context, item))
        }

        val dp = deviceParams
        if (dp == null) {
            // На всякий случай: вернём только контент без PrimaryLayout.
            return listColumn.build()
        }

        val moreClickable = clickableOpenActivity(
            context = context,
            className = "dev.pointtosky.wear.tile.tonight.TonightTargetsActivity",
            id = "open_tonight_list"
        )
        val moreChip = CompactChip.Builder(
            context,
            getString(R.string.tile_more),
            moreClickable,
            dp
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
        val click = clickableLaunch(context, ACTION_OPEN_AIM, item.id)
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
                .build()
        )
        // Отступ
        row.addContent(
            LayoutElementBuilders.Spacer.Builder()
                .setWidth(DimensionBuilders.DpProp.Builder().setValue(8f).build())
                .build()
        )
        // Тексты
        val textCol = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.ExpandedDimensionProp.Builder().build())
        // Primary (Material Text)
        textCol.addContent(
            Text.Builder(context, item.title).build()
        )
        if (subtitle != null) {
            textCol.addContent(
                Text.Builder(context, subtitle).build()
            )
        }
        row.addContent(textCol.build())
        return row.build()
    }

    private fun clickableLaunch(context: Context, action: String, targetId: String? = null): ModifiersBuilders.Clickable {
        // Минимальный DoD: просто открываем TileEntryActivity (без extras/action), чтобы клик работал стабильно.
        val activity: ActionBuilders.AndroidActivity =
            ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.packageName)
                .setClassName("dev.pointtosky.wear.tile.tonight.TileEntryActivity")
                .build()
        val launch: ActionBuilders.LaunchAction =
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(activity) // ← сюда передаём уже построенный AndroidActivity
                .build()
        return ModifiersBuilders.Clickable.Builder()
            .setOnClick(launch)
            .setId(targetId?.let { "$action:$it" } ?: action) // человекочитаемый id для отладки
            .build()
    }

    /** Клик, открывающий конкретную Activity по полному имени класса. */
    private fun clickableOpenActivity(
        context: Context,
        className: String,
        id: String
    ): ModifiersBuilders.Clickable {
        val activity: ActionBuilders.AndroidActivity =
            ActionBuilders.AndroidActivity.Builder()
                .setPackageName(context.packageName)
                .setClassName(className)
                .build()
        val launch: ActionBuilders.LaunchAction =
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(activity)
                .build()
        return ModifiersBuilders.Clickable.Builder()
            .setId(id)
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
        model.items.take(3).forEach { t ->
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
}
