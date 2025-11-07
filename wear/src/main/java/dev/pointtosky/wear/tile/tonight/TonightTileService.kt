package dev.pointtosky.wear.tile.tonight

import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TimelineBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.ActionBuilders
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.pointtosky.wear.R
import kotlinx.coroutines.runBlocking
import java.time.Instant

class TonightTileService : TileService() {

    companion object {
        private const val RES_VER = "tonight_v1"
        const val ACTION_OPEN_AIM = "dev.pointtosky.ACTION_OPEN_AIM"
        const val ACTION_OPEN_IDENTIFY = "dev.pointtosky.ACTION_OPEN_IDENTIFY"
        const val EXTRA_TARGET_ID = "targetId"

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

    private val provider: TonightProvider by lazy { StubTonightProvider(this) }

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val model = runBlocking { provider.getModel(Instant.now()) }
        val root = buildRootColumn(this, model)
        val layout = LayoutElementBuilders.Layout.Builder().setRoot(root).build()
        val entry = TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build()
        val timeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(entry).build()
        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RES_VER)
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

    private fun buildRootColumn(context: Context, model: TonightTileModel): LayoutElementBuilders.Column {
        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.ExpandedDimensionProp.Builder().build())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.DpProp.Builder().setValue(8f).build())
                            .setEnd(DimensionBuilders.DpProp.Builder().setValue(8f).build())
                            .setTop(DimensionBuilders.DpProp.Builder().setValue(6f).build())
                            .setBottom(DimensionBuilders.DpProp.Builder().setValue(6f).build())
                            .build()
                    )
                    .build()
        )
        // Заголовок
        col.addContent(
            LayoutElementBuilders.Text.Builder()
                .setText(getString(R.string.tile_tonight_label))
                .build()
        )

        // 2–3 карточки
        model.items.take(3).forEach { item ->
            col.addContent(buildTargetChip(context, item))
        }

        // "Ещё" → открываем Identify
        col.addContent(
            LayoutElementBuilders.Box.Builder()
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(clickableLaunch(context, ACTION_OPEN_IDENTIFY))
                        .build()
                )
                .addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText(getString(R.string.tile_more))
                        .build()
                )
                .build()
        )

        return col.build()
    }

    private fun buildTargetChip(context: Context, item: TonightTarget): LayoutElementBuilders.LayoutElement {
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
        textCol.addContent(
            LayoutElementBuilders.Text.Builder().setText(item.title).build()
        )
        if (subtitle != null) {
            textCol.addContent(
                LayoutElementBuilders.Text.Builder().setText(subtitle).build()
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

    private fun formatAzAlt(az: Double?, alt: Double?): String? {
        if (az == null || alt == null) return null
        fun fmt(x: Double) = "%1$.0f°".format(x)
        return "Az ${fmt(az)} • Alt ${fmt(alt)}"
    }
}
