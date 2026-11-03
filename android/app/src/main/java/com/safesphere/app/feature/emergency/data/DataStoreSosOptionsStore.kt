package com.safesphere.app.feature.emergency.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.feature.emergency.domain.AutoAudioChoice
import com.safesphere.app.feature.emergency.domain.SosOptions
import com.safesphere.app.feature.emergency.domain.SosOptionsStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.sosOptionsDataStore: DataStore<Preferences> by preferencesDataStore(name = "sos_options")

/** App-private (not backed up). Keys carry the account id, so two accounts on one phone keep separate choices. */
class DataStoreSosOptionsStore(context: Context) : SosOptionsStore {

    private val dataStore = context.applicationContext.sosOptionsDataStore

    override fun options(userId: Long): Flow<SosOptions> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { preferences ->
            SosOptions(
                shakeOn = preferences[shakeKey(userId)] ?: false,
                autoAudio = preferences[audioKey(userId)]?.let { name -> AutoAudioChoice.entries.firstOrNull { it.name == name } }
                    ?: AutoAudioChoice.NOT_ASKED
            )
        }
        .distinctUntilChanged()

    override suspend fun setShake(userId: Long, on: Boolean) {
        dataStore.edit { it[shakeKey(userId)] = on }
    }

    override suspend fun setAutoAudio(userId: Long, choice: AutoAudioChoice) {
        dataStore.edit { it[audioKey(userId)] = choice.name }
    }

    override suspend fun lastRecordedEmergency(userId: Long): Long? =
        dataStore.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()[recordedKey(userId)]

    override suspend fun markRecordingStarted(userId: Long, emergencyId: Long) {
        dataStore.edit { it[recordedKey(userId)] = emergencyId }
    }

    override suspend fun forget(userId: Long) {
        dataStore.edit {
            it.remove(shakeKey(userId))
            it.remove(audioKey(userId))
            it.remove(recordedKey(userId))
        }
    }

    private fun shakeKey(userId: Long) = booleanPreferencesKey("shake_$userId")
    private fun audioKey(userId: Long) = stringPreferencesKey("auto_audio_$userId")
    private fun recordedKey(userId: Long) = longPreferencesKey("last_recorded_emergency_$userId")
}
