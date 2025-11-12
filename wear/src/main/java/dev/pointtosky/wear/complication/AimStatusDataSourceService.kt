package dev.pointtosky.wear.complication

import android.app.PendingIntent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import dev.pointtosky.wear.R
import dev.pointtosky.wear.WearIntents.putAimTargetExtras
import dev.pointtosky.wear.aim.core.AimPhase
import dev.pointtosky.wear.complication.config.AimPrefs
import dev.pointtosky.wear.complication.config.ComplicationPrefsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class AimStatusDataSourceService : BaseComplicationDataSourceService() {

    private val repository by lazy { AimStatusRepository(applicationContext) }
    private val formatter by lazy { AimStatusFormatter(this) }
    private val prefsStore by lazy { ComplicationPrefsStore(applicationContext) }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snapshot = repository.read()
        val prefs = withContext(Dispatchers.IO) { prefsStore.aimFlow.first() }
        return dataForType(request.complicationType, snapshot, prefs)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        dataForType(type, previewSnapshot(), AimPrefs(showDelta = true, showPhase = true))

    private fun dataForType(
        type: ComplicationType,
        snapshot: AimStatusSnapshot,
        prefs: AimPrefs,
    ): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT -> shortTextData(snapshot, prefs)
            ComplicationType.MONOCHROMATIC_IMAGE -> monochromaticImageData(snapshot)
            ComplicationType.SMALL_IMAGE -> smallImageData(snapshot)
            ComplicationType.RANGED_VALUE -> rangedValueData(snapshot)
            else -> null
        }

    private fun shortTextData(snapshot: AimStatusSnapshot, prefs: AimPrefs): ComplicationData {
        val textValue = buildShortText(snapshot, prefs)
        val title = if (snapshot.isActive) {
            snapshot.displayTitle(getString(R.string.comp_aim_status_title_default))
        } else {
            getString(R.string.comp_aim_status_title_default)
        }
        val builder = ShortTextComplicationData.Builder(
            text(textValue),
            text(formatter.contentDescription(snapshot)),
        )
            .setTitle(text(title))
            .setTapAction(aimTapAction(snapshot))
        if (prefs.showPhase) {
            builder.setMonochromaticImage(monochromaticImage(iconFor(snapshot)))
        }
        return builder.build()
    }

    private fun buildShortText(snapshot: AimStatusSnapshot, prefs: AimPrefs): String {
        if (!snapshot.isActive) {
            return getString(R.string.comp_aim_status_no_target)
        }
        val parts = mutableListOf<String>()
        parts += formatter.phaseLabel(snapshot.phase)
        if (prefs.showDelta) {
            formatter.shortText(snapshot.dAzDeg, snapshot.dAltDeg)
                ?.replace(' ', '/')
                ?.let { parts += it }
        }
        return parts.joinToString(separator = " ")
    }

    private fun monochromaticImageData(snapshot: AimStatusSnapshot): ComplicationData =
        MonochromaticImageComplicationData.Builder(
            monochromaticImage(iconFor(snapshot)),
            text(formatter.contentDescription(snapshot)),
        )
            .setTapAction(aimTapAction(snapshot))
            .build()

    private fun smallImageData(snapshot: AimStatusSnapshot): ComplicationData {
        val icon = when {
            !snapshot.isActive -> R.drawable.ic_complication_compass
            snapshot.phase == AimPhase.LOCKED -> R.drawable.ic_complication_phase_locked
            snapshot.phase == AimPhase.IN_TOLERANCE -> R.drawable.ic_complication_phase_in_tolerance
            else -> R.drawable.ic_complication_phase_search
        }
        return SmallImageComplicationData.Builder(
            smallImage(icon, SmallImageType.ICON),
            text(formatter.contentDescription(snapshot)),
        )
            .setTapAction(aimTapAction(snapshot))
            .build()
    }

    private fun rangedValueData(snapshot: AimStatusSnapshot): ComplicationData {
        val value = snapshot.closenessPercent() ?: 0f
        val builder = RangedValueComplicationData.Builder(
            value,
            0f,
            100f,
            text(formatter.contentDescription(snapshot)),
        )
            .setText(
                text(
                    when {
                        !snapshot.isActive -> getString(R.string.comp_aim_status_no_target)
                        else -> formatter.shortText(snapshot.dAzDeg, snapshot.dAltDeg)
                            ?: formatter.phaseLabel(snapshot.phase)
                    },
                ),
            )
            .setTapAction(aimTapAction(snapshot))
        builder.setMonochromaticImage(monochromaticImage(iconFor(snapshot)))
        return builder.build()
    }

    private fun iconFor(snapshot: AimStatusSnapshot): Int = when {
        !snapshot.isActive -> R.drawable.ic_complication_compass
        snapshot.phase == AimPhase.LOCKED -> R.drawable.ic_complication_crosshair
        snapshot.phase == AimPhase.IN_TOLERANCE -> R.drawable.ic_complication_crosshair
        snapshot.phase == AimPhase.SEARCHING -> R.drawable.ic_complication_crosshair
        else -> R.drawable.ic_complication_crosshair
    }

    private fun aimTapAction(snapshot: AimStatusSnapshot): PendingIntent =
        PendingIntent.getActivity(
            this,
            PtsComplicationKind.AIM_STATUS.ordinal,
            mainActivityIntent(PtsComplicationKind.AIM_STATUS).apply {
                snapshot.target?.toAimTarget()?.let { putAimTargetExtras(it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun previewSnapshot(): AimStatusSnapshot = AimStatusSnapshot(
        timestampMs = 0L,
        isActive = true,
        dAzDeg = 2.1,
        dAltDeg = 1.0,
        phase = AimPhase.IN_TOLERANCE,
        target = AimStatusTarget(
            kind = AimStatusTargetKind.STAR,
            label = getString(R.string.comp_aim_status_preview_title),
        ),
    )
}

