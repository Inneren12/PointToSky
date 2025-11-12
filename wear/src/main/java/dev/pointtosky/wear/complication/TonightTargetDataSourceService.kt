package dev.pointtosky.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.altAzToRaDec
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.location.prefs.fromContext
import dev.pointtosky.core.time.ZoneRepo
import dev.pointtosky.wear.ACTION_OPEN_AIM
import dev.pointtosky.wear.R
import dev.pointtosky.wear.aim.core.AimTarget
import dev.pointtosky.wear.complication.config.ComplicationPrefsStore
import dev.pointtosky.wear.complication.config.TonightPrefs
import dev.pointtosky.wear.putAimTargetExtras
import dev.pointtosky.wear.tile.tonight.RealTonightProvider
import dev.pointtosky.wear.tile.tonight.TonightIcon
import dev.pointtosky.wear.tile.tonight.TonightProvider
import dev.pointtosky.wear.tile.tonight.TonightTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.roundToInt

class TonightTargetDataSourceService : BaseComplicationDataSourceService() {

    private val zoneRepo by lazy { ZoneRepo(this) }

    private val locationPrefs: LocationPrefs by lazy { LocationPrefs.fromContext(this) }

    private val prefsStore by lazy { ComplicationPrefsStore(applicationContext) }

    private val complicationUpdater by lazy { TonightTargetComplicationUpdater(applicationContext) }

    private val provider: TonightProvider by lazy {
        RealTonightProvider(
            context = this,
            zoneRepo = zoneRepo,
            getLastKnownLocation = { locationPrefs.manualPointFlow.firstOrNull() },
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val prefs = withContext(Dispatchers.IO) { prefsStore.tonightFlow.first() }
        val now = Instant.now()
        val zone = zoneRepo.current()
        val state = runCatching { loadTargetState(prefs, now, zone) }.getOrNull()
        if (state == null) {
            complicationUpdater.schedule(nextMidnight(now, zone))
            return null
        }

        state.nextRefreshAt?.let { complicationUpdater.schedule(it) }

        val tapAction = tapActionForTarget(request.complicationInstanceId, state.aimTarget)

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT ->
                shortTextData(state, tapAction)

            ComplicationType.MONOCHROMATIC_IMAGE ->
                monochromaticImageData(state, tapAction)

            ComplicationType.SMALL_IMAGE ->
                smallImageData(state, tapAction)

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        when (type) {
            ComplicationType.SHORT_TEXT ->
                shortTextData(previewState(), tapAction(PtsComplicationKind.TONIGHT_TARGET))

            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(
                    monochromaticImage(R.drawable.ic_tonight_jupiter),
                    contentDescription(R.string.comp_tonight_target_preview_content_description),
                )
                    .setTapAction(tapAction(PtsComplicationKind.TONIGHT_TARGET))
                    .build()

            ComplicationType.SMALL_IMAGE ->
                SmallImageComplicationData.Builder(
                    smallImage(R.drawable.ic_tonight_jupiter),
                    contentDescription(R.string.comp_tonight_target_preview_content_description),
                )
                    .setTapAction(tapAction(PtsComplicationKind.TONIGHT_TARGET))
                    .build()

            else -> null
        }

    private suspend fun loadTargetState(prefs: TonightPrefs, now: Instant, zone: ZoneId): TargetState? {
        val location = locationPrefs.manualPointFlow.firstOrNull()
        val model = provider.getModel(now)
        val target = selectTarget(model.items, prefs).firstOrNull() ?: return null
        val secondary = target.subtitle ?: target.altDeg?.let { "alt ${it.roundToInt()}°" }
        val contentDescription = buildContentDescription(target.title, secondary)
        val refresh = buildRefreshSchedule(now, target, zone)
        val aimTarget = aimTargetFor(target, location, now)
        return TargetState(
            target = target,
            secondary = secondary,
            contentDescription = contentDescription,
            validTimeRange = refresh.timeRange,
            aimTarget = aimTarget,
            nextRefreshAt = refresh.nextBoundary,
        )
    }

    private fun shortTextData(state: TargetState, tapAction: PendingIntent): ComplicationData {
        val builder = ShortTextComplicationData.Builder(
            text(state.target.title),
            text(state.contentDescription),
        )
            .setMonochromaticImage(monochromaticImage(iconDrawable(state.target.icon)))
            .setValidTimeRange(state.validTimeRange)
            .setTapAction(tapAction)
        state.secondary?.let { secondary ->
            if (secondary.isNotBlank()) {
                builder.setTitle(text(secondary))
            }
        }
        return builder.build()
    }

    private fun monochromaticImageData(state: TargetState, tapAction: PendingIntent): ComplicationData {
        return MonochromaticImageComplicationData.Builder(
            monochromaticImage(iconDrawable(state.target.icon)),
            text(state.contentDescription),
        )
            .setValidTimeRange(state.validTimeRange)
            .setTapAction(tapAction)
            .build()
    }

    private fun smallImageData(state: TargetState, tapAction: PendingIntent): ComplicationData {
        return SmallImageComplicationData.Builder(
            smallImage(iconDrawable(state.target.icon)),
            text(state.contentDescription),
        )
            .setValidTimeRange(state.validTimeRange)
            .setTapAction(tapAction)
            .build()
    }

    private fun tapActionForTarget(
        instanceId: Int,
        aimTarget: AimTarget?,
    ): PendingIntent {
        val intent = mainActivityIntent(PtsComplicationKind.TONIGHT_TARGET).apply {
            action = ACTION_OPEN_AIM
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            aimTarget?.let { putAimTargetExtras(it) }
        }
        val requestCode = PtsComplicationKind.TONIGHT_TARGET.ordinal * 1000 + instanceId
        // Важно: CANCEL_CURRENT гарантирует, что при переходе aimTarget → null
        // новый PendingIntent НЕ унаследует старые EXTRA_AIM_* из предыдущего.
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun aimTargetFor(target: TonightTarget, location: GeoPoint?, now: Instant): AimTarget? {
        val body = runCatching { Body.valueOf(target.id) }.getOrNull()
        return when {
            body != null -> AimTarget.BodyTarget(body)

            location != null && target.azDeg != null && target.altDeg != null -> {
                val lstDeg = lstAt(now, location.lonDeg).lstDeg
                val eq = altAzToRaDec(
                    Horizontal(azDeg = target.azDeg, altDeg = target.altDeg),
                    lstDeg,
                    location.latDeg,
                )
                AimTarget.EquatorialTarget(eq)
            }

            else -> null
        }
    }

    private fun buildRefreshSchedule(now: Instant, target: TonightTarget, zone: ZoneId): RefreshSchedule {
        val midnight = nextMidnight(now, zone)
        val boundaries = buildList {
            target.windowStart?.takeIf { it.isAfter(now) }?.let { add(it) }
            target.windowEnd?.takeIf { it.isAfter(now) }?.let { add(it) }
            midnight.takeIf { it.isAfter(now) }?.let { add(it) }
        }
        val nextBoundary = boundaries.minOrNull()
        val timeRange = nextBoundary?.let { TimeRange.before(it) }
        return RefreshSchedule(timeRange = timeRange, nextBoundary = nextBoundary)
    }

    private fun nextMidnight(now: Instant, zone: ZoneId): Instant {
        val zoned = ZonedDateTime.ofInstant(now, zone)
        val nextDay = zoned.toLocalDate().plusDays(1)
        return nextDay.atStartOfDay(zone).toInstant()
    }

    private fun buildContentDescription(title: String, secondary: String?): String =
        if (secondary.isNullOrBlank()) {
            getString(R.string.comp_tonight_target_content_description, title)
        } else {
            getString(R.string.comp_tonight_target_content_description_with_secondary, title, secondary)
        }

    private fun selectTarget(items: List<TonightTarget>, prefs: TonightPrefs): List<TonightTarget> {
        val limit = prefs.magLimit.toDouble()
        val filtered = items.filter { target ->
            val magnitude = parseMagnitude(target)
            magnitude?.let { it <= limit } ?: true
        }
        if (!prefs.preferPlanets) {
            return filtered
        }
        return filtered.withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<TonightTarget>> { indexed ->
                    if (isPlanet(indexed.value)) 1 else 0
                }.thenBy { indexed -> indexed.index },
            )
            .map { it.value }
    }

    private fun isPlanet(target: TonightTarget): Boolean =
        when (target.icon) {
            TonightIcon.SUN,
            TonightIcon.MOON,
            TonightIcon.JUPITER,
            TonightIcon.SATURN -> true
            else -> false
        }

    private fun parseMagnitude(target: TonightTarget): Double? {
        val regex = Regex("mag\\s*(-?\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
        return sequenceOf(target.subtitle, target.title, target.id)
            .filterNotNull()
            .mapNotNull { value ->
                regex.find(value)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            }
            .firstOrNull()
    }

    private fun previewState(): TargetState {
        val previewLabel = getString(R.string.comp_tonight_target_preview_text)
        val title = previewLabel.substringBefore('•').trim().ifEmpty { previewLabel }
        val secondary = previewLabel.substringAfter('•', "").trim().takeIf { it.isNotEmpty() }
        val fallbackTarget = TonightTarget(
            id = "JUPITER",
            title = title,
            subtitle = secondary,
            icon = TonightIcon.JUPITER,
        )
        val previewTargets = listOf(
            fallbackTarget,
            TonightTarget(
                id = "STAR:Rigel",
                title = "Rigel",
                subtitle = "mag 0.1",
                icon = TonightIcon.STAR,
            ),
        )
        val prefs = TonightPrefs(magLimit = 5.5f, preferPlanets = true)
        val target = selectTarget(previewTargets, prefs).firstOrNull() ?: fallbackTarget
        return TargetState(
            target = target,
            secondary = target.subtitle,
            contentDescription = getString(R.string.comp_tonight_target_preview_content_description),
            validTimeRange = null,
            aimTarget = null,
            nextRefreshAt = null,
        )
    }

    private fun iconDrawable(icon: TonightIcon): Int =
        when (icon) {
            TonightIcon.SUN -> R.drawable.ic_tonight_sun
            TonightIcon.MOON -> R.drawable.ic_tonight_moon
            TonightIcon.JUPITER -> R.drawable.ic_tonight_jupiter
            TonightIcon.SATURN -> R.drawable.ic_tonight_saturn
            TonightIcon.STAR -> R.drawable.ic_tonight_star
            TonightIcon.CONST -> R.drawable.ic_tonight_const
        }

    private data class TargetState(
        val target: TonightTarget,
        val secondary: String?,
        val contentDescription: String,
        val validTimeRange: TimeRange?,
        val aimTarget: AimTarget?,
        val nextRefreshAt: Instant?,
    )

    private data class RefreshSchedule(
        val timeRange: TimeRange?,
        val nextBoundary: Instant?,
    )
}
