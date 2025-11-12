package dev.pointtosky.wear.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.datasource.ComplicationRequest

class TonightTargetDataSourceService : BaseComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = null

    override fun getPreviewData(type: ComplicationType): ComplicationData? = null
}
