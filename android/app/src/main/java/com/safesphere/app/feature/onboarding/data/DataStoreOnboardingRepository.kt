package com.safesphere.app.feature.onboarding.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.feature.onboarding.domain.OnboardingRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

// There must be only one DataStore instance per file name, hence the top-level delegate.
private val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "onboarding_preferences"
)

/** Stores a single boolean. Nothing personal is saved here. */
class DataStoreOnboardingRepository(context: Context) : OnboardingRepository {

    private val dataStore = context.applicationContext.onboardingDataStore

    override val isOnboardingCompleted: Flow<Boolean> = dataStore.data
        .catch { error ->
            // A corrupted or unreadable file should behave like "first launch", not crash the app.
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[KEY_ONBOARDING_COMPLETED] ?: false }

    override suspend fun markOnboardingCompleted() {
        dataStore.edit { preferences -> preferences[KEY_ONBOARDING_COMPLETED] = true }
    }

    private companion object {
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    }
}
