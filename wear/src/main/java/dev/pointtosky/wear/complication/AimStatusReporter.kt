package dev.pointtosky.wear.complication

import android.content.Context
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.wear.R
import dev.pointtosky.wear.aim.core.AimPhase
import dev.pointtosky.wear.aim.core.AimState
import dev.pointtosky.wear.aim.core.AimTarget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AimStatusReporter {
    suspend fun onTargetChanged(
        target: AimTarget?,
        label: String? = null,
    )

    suspend fun onState(state: AimState)

    suspend fun onInactive()

    suspend fun onToleranceChanged(
        azDeg: Double,
        altDeg: Double,
    )

    companion object {
        val NoOp: AimStatusReporter =
            object : AimStatusReporter {
                override suspend fun onTargetChanged(
                    target: AimTarget?,
                    label: String?,
                ) = Unit

                override suspend fun onState(state: AimState) = Unit

                override suspend fun onInactive() = Unit

                override suspend fun onToleranceChanged(
                    azDeg: Double,
                    altDeg: Double,
                ) = Unit
            }
    }
}

class PersistentAimStatusReporter(
    private val context: Context,
    private val repository: AimStatusRepository = AimStatusRepository(context),
    private val updater: AimStatusComplicationUpdater = AimStatusComplicationUpdater(context),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val persistIntervalMs: Long = 15_000L,
) : AimStatusReporter {
    private val mutex = Mutex()
    private var cached: AimStatusSnapshot? = null
    private var lastPersistMs: Long = 0L
    private var lastPhase: AimPhase? = null

    override suspend fun onTargetChanged(
        target: AimTarget?,
        label: String?,
    ) {
        mutex.withLock {
            val base = ensureSnapshot()
            val now = clock()
            val snapshot =
                base.copy(
                    timestampMs = now,
                    target = target?.let { snapshotTarget(it, label) },
                )
            if (snapshot != cached) {
                cached = snapshot
                repository.write(snapshot)
                updater.requestUpdate(force = true)
            }
        }
    }

    override suspend fun onState(state: AimState) {
        mutex.withLock {
            val previousPhase = lastPhase
            if (state.phase == AimPhase.LOCKED && previousPhase != AimPhase.LOCKED) {
                updater.requestUpdate(force = true)
            }
            if (previousPhase == AimPhase.LOCKED && state.phase != AimPhase.LOCKED) {
                updater.requestUpdate(force = true)
            } else if (previousPhase != state.phase && state.phase != AimPhase.LOCKED) {
                updater.requestUpdate(force = false)
            }
            lastPhase = state.phase

            val now = clock()
            val shouldPersist = now - lastPersistMs >= persistIntervalMs || previousPhase != state.phase
            if (!shouldPersist) return

            val base = ensureSnapshot()
            val snapshot =
                base.copy(
                    timestampMs = now,
                    isActive = true,
                    dAzDeg = state.dAzDeg,
                    dAltDeg = state.dAltDeg,
                    phase = state.phase,
                )
            if (snapshot != cached) {
                cached = snapshot
                repository.write(snapshot)
            }
            lastPersistMs = now
        }
    }

    override suspend fun onInactive() {
        mutex.withLock {
            val base = ensureSnapshot()
            if (!base.isActive && base.dAzDeg == null && base.dAltDeg == null && base.phase == null) return
            val now = clock()
            val snapshot =
                base.copy(
                    timestampMs = now,
                    isActive = false,
                    dAzDeg = null,
                    dAltDeg = null,
                    phase = null,
                )
            cached = snapshot
            repository.write(snapshot)
            updater.requestUpdate(force = true)
            lastPersistMs = now
            lastPhase = null
        }
    }

    override suspend fun onToleranceChanged(
        azDeg: Double,
        altDeg: Double,
    ) {
        mutex.withLock {
            val base = ensureSnapshot()
            val now = clock()
            val snapshot =
                base.copy(
                    timestampMs = now,
                    toleranceAzDeg = azDeg,
                    toleranceAltDeg = altDeg,
                )
            if (snapshot != cached) {
                cached = snapshot
                repository.write(snapshot)
            }
        }
    }

    private suspend fun ensureSnapshot(): AimStatusSnapshot {
        val current = cached
        if (current != null) return current
        val stored = repository.read()
        cached = stored
        return stored
    }

    private fun snapshotTarget(
        target: AimTarget,
        providedLabel: String?,
    ): AimStatusTarget {
        val label = providedLabel?.takeIf { it.isNotBlank() } ?: defaultLabel(target)
        return when (target) {
            is AimTarget.BodyTarget ->
                AimStatusTarget(
                    kind = AimStatusTargetKind.BODY,
                    label = label,
                    bodyName = target.body.name,
                )
            is AimTarget.StarTarget ->
                AimStatusTarget(
                    kind = AimStatusTargetKind.STAR,
                    label = label,
                    starId = target.starId,
                    raDeg = target.eq?.raDeg,
                    decDeg = target.eq?.decDeg,
                )
            is AimTarget.EquatorialTarget ->
                AimStatusTarget(
                    kind = AimStatusTargetKind.EQUATORIAL,
                    label = label,
                    raDeg = target.eq.raDeg,
                    decDeg = target.eq.decDeg,
                )
        }
    }

    private fun defaultLabel(target: AimTarget): String =
        when (target) {
            is AimTarget.BodyTarget ->
                when (target.body) {
                    Body.SUN -> context.getString(R.string.comp_aim_status_target_sun)
                    Body.MOON -> context.getString(R.string.comp_aim_status_target_moon)
                    Body.JUPITER -> context.getString(R.string.comp_aim_status_target_jupiter)
                    Body.SATURN -> context.getString(R.string.comp_aim_status_target_saturn)
                    else -> target.body.name.lowercase().replaceFirstChar { it.uppercase() }
                }
            is AimTarget.StarTarget -> context.getString(R.string.comp_aim_status_target_star)
            is AimTarget.EquatorialTarget -> context.getString(R.string.comp_aim_status_target_custom)
        }
}
