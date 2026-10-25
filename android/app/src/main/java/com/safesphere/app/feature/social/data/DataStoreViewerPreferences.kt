package com.safesphere.app.feature.social.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.feature.social.domain.ViewerPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.viewerDataStore: DataStore<Preferences> by preferencesDataStore(name = "viewer_preferences")

/** Phase 13E: one switch about the viewer ("Collapse strong language"); nothing about anyone else. */
class DataStoreViewerPreferences(context: Context) : ViewerPreferences {

    private val dataStore = context.applicationContext.viewerDataStore

    override val collapseStrongLanguage: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[KEY_STRONG_LANGUAGE] == true }

    override suspend fun setCollapseStrongLanguage(on: Boolean) {
        dataStore.edit { it[KEY_STRONG_LANGUAGE] = on }
    }

    private companion object {
        val KEY_STRONG_LANGUAGE = booleanPreferencesKey("collapse_strong_language")
    }
}
