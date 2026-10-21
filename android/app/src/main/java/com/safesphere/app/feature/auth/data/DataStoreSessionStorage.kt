package com.safesphere.app.feature.auth.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.SessionStorage
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "session_preferences"
)

/**
 * Keeps the JWT and basic profile data (id, name, email; since Phase 10A also a pending deletion's dates) so the user
 * stays signed in after the app closes. The password is never stored.
 *
 * Development-phase limitation: DataStore is private to the app but NOT encrypted. A later phase
 * should encrypt the token with a key held in the Android Keystore. Backups are disabled in the
 * manifest (allowBackup=false) so this file is not copied to cloud backups.
 */
class DataStoreSessionStorage(context: Context) : SessionStorage {

    private val dataStore = context.applicationContext.sessionDataStore

    override val session: Flow<AuthSession?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::read)

    override suspend fun save(session: AuthSession) {
        dataStore.edit { preferences -> write(preferences, session) }
    }

    /** One atomic edit: the old token is compared and the new session written in the same DataStore transaction. */
    override suspend fun replaceToken(oldToken: String, replacement: AuthSession): Boolean {
        var replaced = false
        dataStore.edit { preferences ->
            if (preferences[KEY_TOKEN] == oldToken) {
                write(preferences, replacement)
                replaced = true
            }
        }
        return replaced
    }

    private fun write(preferences: MutablePreferences, session: AuthSession) {
        preferences[KEY_TOKEN] = session.token
        preferences[KEY_USER_ID] = session.user.id
        preferences[KEY_USER_NAME] = session.user.name
        preferences[KEY_USER_EMAIL] = session.user.email
        preferences.putOrRemove(KEY_DELETION_REQUESTED, session.user.deletionRequestedAt)
        preferences.putOrRemove(KEY_DELETION_DUE, session.user.deletionDueAt)
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    /** One atomic edit: the token is compared and the session cleared inside the same DataStore transaction. */
    override suspend fun clearIfToken(token: String?): AuthSession? {
        var cleared: AuthSession? = null
        dataStore.edit { preferences ->
            val current = read(preferences)
            if (current != null && (token == null || current.token == token)) {
                cleared = current
                preferences.clear()
            }
        }
        return cleared
    }

    private fun read(preferences: Preferences): AuthSession? {
        val token = preferences[KEY_TOKEN]
        val id = preferences[KEY_USER_ID]
        val name = preferences[KEY_USER_NAME]
        val email = preferences[KEY_USER_EMAIL]
        // Only a complete record counts as a session.
        return if (token != null && id != null && name != null && email != null) {
            AuthSession(
                token,
                AuthUser(
                    id, name, email,
                    deletionRequestedAt = preferences[KEY_DELETION_REQUESTED]?.let(Instant::ofEpochMilli),
                    deletionDueAt = preferences[KEY_DELETION_DUE]?.let(Instant::ofEpochMilli)
                )
            )
        } else {
            null
        }
    }

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<Long>, value: Instant?) {
        if (value == null) remove(key) else this[key] = value.toEpochMilli()
    }

    private companion object {
        val KEY_TOKEN = stringPreferencesKey("access_token")
        val KEY_USER_ID = longPreferencesKey("user_id")
        val KEY_USER_NAME = stringPreferencesKey("user_name")
        val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        val KEY_DELETION_REQUESTED = longPreferencesKey("deletion_requested_at")
        val KEY_DELETION_DUE = longPreferencesKey("deletion_due_at")
    }
}
