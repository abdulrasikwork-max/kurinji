package com.safesphere.app.feature.account.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.feature.account.domain.PendingDeletion
import com.safesphere.app.feature.account.domain.PendingDeletionStore
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore(name = "account_preferences")

/** The one pending deletion this phone knows about: an account id and a date, nothing else (no name, no e-mail). */
class DataStorePendingDeletionStore(context: Context) : PendingDeletionStore {

    private val dataStore = context.applicationContext.accountDataStore

    override val pending: Flow<PendingDeletion?> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences ->
            val id = preferences[KEY_USER_ID]
            val due = preferences[KEY_DUE_AT]
            if (id != null && due != null) PendingDeletion(id, Instant.ofEpochMilli(due)) else null
        }

    override suspend fun set(value: PendingDeletion) {
        dataStore.edit {
            it[KEY_USER_ID] = value.userId
            it[KEY_DUE_AT] = value.dueAt.toEpochMilli()
        }
    }

    override suspend fun clear(userId: Long?) {
        dataStore.edit { if (userId == null || it[KEY_USER_ID] == userId) it.clear() }
    }

    private companion object {
        val KEY_USER_ID = longPreferencesKey("pending_deletion_user_id")
        val KEY_DUE_AT = longPreferencesKey("pending_deletion_due_at")
    }
}
