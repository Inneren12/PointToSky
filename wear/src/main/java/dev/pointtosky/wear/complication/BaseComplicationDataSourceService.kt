package dev.pointtosky.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.pointtosky.wear.ACTION_OPEN_AIM
import dev.pointtosky.wear.ACTION_OPEN_IDENTIFY
import dev.pointtosky.wear.MainActivity

abstract class BaseComplicationDataSourceService : SuspendingComplicationDataSourceService() {
    protected fun icon(
        @DrawableRes id: Int,
    ): Icon = Icon.createWithResource(this, id)

    protected fun monochromaticImage(
        @DrawableRes id: Int,
    ): MonochromaticImage = MonochromaticImage.Builder(icon(id)).build()

    protected fun smallImage(
        @DrawableRes id: Int,
        type: SmallImageType = SmallImageType.ICON,
    ): SmallImage = SmallImage.Builder(icon(id), type).build()

    protected fun text(
        @StringRes id: Int,
        vararg args: Any,
    ): ComplicationText = PlainComplicationText.Builder(getString(id, *args)).build()

    protected fun text(value: CharSequence): ComplicationText = PlainComplicationText.Builder(value).build()

    protected fun contentDescription(
        @StringRes id: Int,
        vararg args: Any,
    ): ComplicationText = text(id, *args)

    protected fun tapAction(kind: PtsComplicationKind): PendingIntent =
        PendingIntent.getActivity(
            this,
            kind.ordinal,
            mainActivityIntent(kind),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    protected fun mainActivityIntent(kind: PtsComplicationKind): Intent =
        Intent(this, MainActivity::class.java).apply {
            action =
                when (kind) {
                    PtsComplicationKind.AIM_STATUS -> ACTION_OPEN_AIM
                    PtsComplicationKind.TONIGHT_TARGET -> ACTION_OPEN_IDENTIFY
                }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_COMPLICATION_KIND, kind.name)
        }

    protected fun readPrefs(
        @Suppress("UNUSED_PARAMETER") request: ComplicationRequest,
    ): ComplicationPrefs = ComplicationPrefs()

    companion object {
        private const val EXTRA_COMPLICATION_KIND = "extra_pts_complication_kind"
    }
}
