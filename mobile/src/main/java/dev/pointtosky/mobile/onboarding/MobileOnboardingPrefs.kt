package dev.pointtosky.mobile.onboarding

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

interface MobileOnboardingPrefs {
    val acceptedFlow: Flow<Boolean>

    suspend fun setAccepted(accepted: Boolean)

    companion object
}

private class MobileOnboardingPrefsDataStore(
    private val dataStore: DataStore<Preferences>,
) : MobileOnboardingPrefs {

    override val acceptedFlow: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences -> preferences[ACCEPTED_KEY] ?: false }

    override suspend fun setAccepted(accepted: Boolean) {
        dataStore.edit { preferences ->
            preferences[ACCEPTED_KEY] = accepted
        }
    }

    companion object {
        private val ACCEPTED_KEY = booleanPreferencesKey("onboarding.accepted")
    }
}

private val Context.mobileOnboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mobile_onboarding",
)

fun MobileOnboardingPrefs.Companion.from(context: Context): MobileOnboardingPrefs {
    return MobileOnboardingPrefsDataStore(context.applicationContext.mobileOnboardingDataStore)
}
