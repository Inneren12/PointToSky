package dev.pointtosky.wear.complication

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.aimStatusDataStore: DataStore<Preferences> by preferencesDataStore(name = "aim_status")

class AimStatusRepository(
    private val context: Context,
) {
    private val dataStore = context.aimStatusDataStore

    suspend fun read(): AimStatusSnapshot =
        dataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }.map { prefs ->
                if (!prefs.contains(keyTimestamp)) {
                    AimStatusSnapshot.EMPTY
                } else {
                    AimStatusSnapshot(
                        timestampMs = prefs[keyTimestamp] ?: 0L,
                        isActive = prefs[keyIsActive] ?: false,
                        dAzDeg = prefs[keyDeltaAz],
                        dAltDeg = prefs[keyDeltaAlt],
                        phase =
                            prefs[keyPhase]?.let {
                                runCatching {
                                    dev.pointtosky.wear.aim.core.AimPhase.valueOf(
                                        it,
                                    )
                                }.getOrNull()
                            },
                        target =
                            prefs[keyTargetKind]?.let { kindName ->
                                runCatching { AimStatusTargetKind.valueOf(kindName) }.getOrNull()?.let { kind ->
                                    AimStatusTarget(
                                        kind = kind,
                                        label = prefs[keyTargetLabel],
                                        bodyName = prefs[keyTargetBody],
                                        starId = prefs[keyTargetStarId],
                                        raDeg = prefs[keyTargetRa],
                                        decDeg = prefs[keyTargetDec],
                                    )
                                }
                            },
                        toleranceAzDeg = prefs[keyToleranceAz] ?: AimStatusSnapshot.EMPTY.toleranceAzDeg,
                        toleranceAltDeg = prefs[keyToleranceAlt] ?: AimStatusSnapshot.EMPTY.toleranceAltDeg,
                    )
                }
            }.first()

    suspend fun write(snapshot: AimStatusSnapshot) {
        dataStore.edit { prefs ->
            prefs[keyTimestamp] = snapshot.timestampMs
            prefs[keyIsActive] = snapshot.isActive
            snapshot.dAzDeg?.let { prefs[keyDeltaAz] = it } ?: prefs.remove(keyDeltaAz)
            snapshot.dAltDeg?.let { prefs[keyDeltaAlt] = it } ?: prefs.remove(keyDeltaAlt)
            snapshot.phase?.let { prefs[keyPhase] = it.name } ?: prefs.remove(keyPhase)
            val target = snapshot.target
            if (target != null) {
                prefs[keyTargetKind] = target.kind.name
                target.label?.let { prefs[keyTargetLabel] = it } ?: prefs.remove(keyTargetLabel)
                target.bodyName?.let { prefs[keyTargetBody] = it } ?: prefs.remove(keyTargetBody)
                target.starId?.let { prefs[keyTargetStarId] = it } ?: prefs.remove(keyTargetStarId)
                target.raDeg?.let { prefs[keyTargetRa] = it } ?: prefs.remove(keyTargetRa)
                target.decDeg?.let { prefs[keyTargetDec] = it } ?: prefs.remove(keyTargetDec)
            } else {
                prefs.remove(keyTargetKind)
                prefs.remove(keyTargetLabel)
                prefs.remove(keyTargetBody)
                prefs.remove(keyTargetStarId)
                prefs.remove(keyTargetRa)
                prefs.remove(keyTargetDec)
            }
            prefs[keyToleranceAz] = snapshot.toleranceAzDeg
            prefs[keyToleranceAlt] = snapshot.toleranceAltDeg
        }
    }

    companion object {
        private val keyTimestamp = longPreferencesKey("aim.status.timestamp")
        private val keyIsActive = booleanPreferencesKey("aim.status.active")
        private val keyDeltaAz = doublePreferencesKey("aim.status.deltaAz")
        private val keyDeltaAlt = doublePreferencesKey("aim.status.deltaAlt")
        private val keyPhase = stringPreferencesKey("aim.status.phase")
        private val keyToleranceAz = doublePreferencesKey("aim.status.tolAz")
        private val keyToleranceAlt = doublePreferencesKey("aim.status.tolAlt")
        private val keyTargetKind = stringPreferencesKey("aim.status.target.kind")
        private val keyTargetLabel = stringPreferencesKey("aim.status.target.label")
        private val keyTargetBody = stringPreferencesKey("aim.status.target.body")
        private val keyTargetStarId = intPreferencesKey("aim.status.target.starId")
        private val keyTargetRa = doublePreferencesKey("aim.status.target.ra")
        private val keyTargetDec = doublePreferencesKey("aim.status.target.dec")
    }
}
