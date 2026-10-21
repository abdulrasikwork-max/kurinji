package com.safesphere.app.feature.emergency.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyRecord
import com.safesphere.app.feature.emergency.domain.ActiveEmergencyStore
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.activeEmergencyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "active_emergency"
)

/**
 * App-private DataStore file (not backed up: allowBackup=false). Holds only ids and a start time:
 * locations are never written to disk on the phone.
 */
class DataStoreActiveEmergencyStore(context: Context) : ActiveEmergencyStore {

    private val dataStore = context.applicationContext.activeEmergencyDataStore

    override suspend fun load(): ActiveEmergencyRecord? {
        val preferences = dataStore.data
            .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()
        val userId = preferences[KEY_USER_ID] ?: return null
        val emergencyId = preferences[KEY_EMERGENCY_ID] ?: return null
        val startedAt = preferences[KEY_STARTED_AT_MILLIS] ?: return null
        return ActiveEmergencyRecord(userId, emergencyId, Instant.ofEpochMilli(startedAt))
    }

    override suspend fun save(record: ActiveEmergencyRecord) {
        dataStore.edit { preferences ->
            preferences[KEY_USER_ID] = record.userId
            preferences[KEY_EMERGENCY_ID] = record.emergencyId
            preferences[KEY_STARTED_AT_MILLIS] = record.startedAt.toEpochMilli()
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_USER_ID = longPreferencesKey("user_id")
        val KEY_EMERGENCY_ID = longPreferencesKey("emergency_id")
        val KEY_STARTED_AT_MILLIS = longPreferencesKey("started_at_millis")
    }
}
