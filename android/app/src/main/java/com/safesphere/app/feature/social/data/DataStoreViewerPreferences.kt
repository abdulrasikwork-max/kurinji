package com.safesphere.app.feature.social.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.feature.social.domain.ViewerPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.viewerDataStore: DataStore<Preferences> by preferencesDataStore(name = "viewer_preferences")

/** Phase 13E: a switch about the viewer ("Collapse strong language"); 17G-3: which account agreed to smart understanding. */
class DataStoreViewerPreferences(context: Context) : ViewerPreferences {

    private val dataStore = context.applicationContext.viewerDataStore

    override val collapseStrongLanguage: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_STRONG_LANGUAGE] == true }

    override suspend fun setCollapseStrongLanguage(on: Boolean) {
        dataStore.edit { it[KEY_STRONG_LANGUAGE] = on }
    }

    override val smartUnderstandingAccount: Flow<Long?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_SMART_ACCOUNT] }

    override suspend fun setSmartUnderstandingAccount(userId: Long?) {
        dataStore.edit { if (userId == null) it.remove(KEY_SMART_ACCOUNT) else it[KEY_SMART_ACCOUNT] = userId }
    }

    override val homeCity: Flow<String?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_HOME_CITY] }

    override suspend fun setHomeCity(value: String?) {
        dataStore.edit { if (value == null) it.remove(KEY_HOME_CITY) else it[KEY_HOME_CITY] = value }
    }

    private companion object {
        val KEY_HOME_CITY = stringPreferencesKey("home_city")
        val KEY_SMART_ACCOUNT = longPreferencesKey("smart_understanding_account")
        val KEY_STRONG_LANGUAGE = booleanPreferencesKey("collapse_strong_language")
    }
}
