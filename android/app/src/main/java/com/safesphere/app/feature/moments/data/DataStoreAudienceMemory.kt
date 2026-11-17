package com.safesphere.app.feature.moments.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.feature.moments.domain.AudienceMemory
import com.safesphere.app.feature.moments.domain.MomentAudience
import java.io.IOException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

private val Context.momentChoicesDataStore: DataStore<Preferences> by preferencesDataStore(name = "moment_choices")

/**
 * Phase N4 (A2, D5): the audience the person chose last time, on THIS phone, per account. App-private, not backed up
 * (`dataExtractionRules` exclude everything); it is the person's own choice, nothing about anyone else. Forgotten
 * with the account's other on-phone choices when the account is deleted ([forget]).
 */
class DataStoreAudienceMemory(
    context: Context,
    private val currentUserId: suspend () -> Long?
) : AudienceMemory {

    private val dataStore = context.applicationContext.momentChoicesDataStore

    override suspend fun last(): MomentAudience? {
        val userId = currentUserId() ?: return null
        val saved = dataStore.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
            .first()[key(userId)]
        return MomentAudience.entries.firstOrNull { it.name == saved }
    }

    override suspend fun remember(audience: MomentAudience) {
        val userId = currentUserId() ?: return
        runCatching { dataStore.edit { it[key(userId)] = audience.name } }
    }

    suspend fun forget(userId: Long) {
        runCatching { dataStore.edit { it.remove(key(userId)) } }
    }

    private fun key(userId: Long) = stringPreferencesKey("audience_$userId")
}
