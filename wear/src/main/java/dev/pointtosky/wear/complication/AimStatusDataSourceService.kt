package dev.pointtosky.wear.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import dev.pointtosky.wear.R

class AimStatusDataSourceService : BaseComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        dataForType(request.complicationType)

    override fun getPreviewData(type: ComplicationType): ComplicationData? = dataForType(type)

    private fun dataForType(type: ComplicationType): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> shortTextData()
            ComplicationType.MONOCHROMATIC_IMAGE -> monochromaticImageData()
            ComplicationType.SMALL_IMAGE -> smallImageData()
            ComplicationType.RANGED_VALUE -> rangedValueData()
            else -> null
        }

    private fun shortTextData(): ComplicationData =
        ShortTextComplicationData.Builder(
            text(R.string.comp_aim_status_preview_text),
            contentDescription(R.string.comp_aim_status_preview_content_description),
        )
            .setTitle(text(R.string.comp_aim_status_preview_title))
            .setMonochromaticImage(monochromaticImage(R.drawable.ic_tonight_star))
            .setTapAction(tapAction(PtsComplicationKind.AIM_STATUS))
            .build()

    private fun monochromaticImageData(): ComplicationData =
        MonochromaticImageComplicationData.Builder(
            monochromaticImage(R.drawable.ic_tonight_star),
            contentDescription(R.string.comp_aim_status_preview_content_description),
        )
            .setTapAction(tapAction(PtsComplicationKind.AIM_STATUS))
            .build()

    private fun smallImageData(): ComplicationData =
        SmallImageComplicationData.Builder(
            smallImage(R.drawable.ic_tonight_star, SmallImageType.ICON),
            contentDescription(R.string.comp_aim_status_preview_content_description),
        )
            .setTapAction(tapAction(PtsComplicationKind.AIM_STATUS))
            .build()

    private fun rangedValueData(): ComplicationData =
        RangedValueComplicationData.Builder(
            /* value = */ 0.5f,
            /* min = */ 0f,
            /* max = */ 1f,
            contentDescription(R.string.comp_aim_status_preview_content_description),
        )
            .setText(text(R.string.comp_aim_status_preview_text))
            .setTapAction(tapAction(PtsComplicationKind.AIM_STATUS))
            .build()
}
