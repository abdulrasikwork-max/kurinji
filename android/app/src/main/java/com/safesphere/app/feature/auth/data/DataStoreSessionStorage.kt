package com.safesphere.app.feature.auth.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safesphere.app.core.crypto.AndroidKeystoreKeyWrapper
import com.safesphere.app.core.crypto.LocalKeyWrapper
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.feature.auth.domain.SessionStorage
import java.io.IOException
import java.time.Instant
import java.util.Base64
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
 * Phase 11 C1: the refresh secret is stored ENCRYPTED (AES-GCM with a non-exportable Android Keystore key, alias
 * [AndroidKeystoreKeyWrapper.SESSION_ALIAS]); if it cannot be decrypted (key gone) it reads as missing, and the person is
 * asked to sign in again when the access token expires. The "sign in to sync" mark is kept too. Every change that
 * concerns tokens is ONE DataStore edit, so the access token and the refresh secret are always replaced together.
 *
 * Remaining limitation (A14, partly closed): the 24 h access token itself is still plain in DataStore (private to the
 * app, excluded from backups). Backups are disabled in the manifest (allowBackup=false).
 */
class DataStoreSessionStorage(
    context: Context,
    private val secretWrapper: LocalKeyWrapper = AndroidKeystoreKeyWrapper(AndroidKeystoreKeyWrapper.SESSION_ALIAS)
) : SessionStorage {

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

    /** One atomic edit: saved only while the stored refresh secret is still [usedRefreshToken] for the same account. */
    override suspend fun replaceAfterRefresh(usedRefreshToken: String, replacement: AuthSession): Boolean {
        var replaced = false
        dataStore.edit { preferences ->
            val current = read(preferences)
            if (current != null && current.refreshToken == usedRefreshToken && current.user.id == replacement.user.id) {
                write(preferences, replacement)
                replaced = true
            }
        }
        return replaced
    }

    /** One atomic edit: [transform] sees what is stored now, and its result is written in the same transaction. */
    override suspend fun update(transform: (AuthSession?) -> AuthSession?): AuthSession? {
        var result: AuthSession? = null
        dataStore.edit { preferences ->
            val next = transform(read(preferences))
            if (next == null) preferences.clear() else write(preferences, next)
            result = next
        }
        return result
    }

    /** One atomic edit: the mark is set only while the stored token is still [token]. */
    override suspend fun markNeedsSignIn(token: String?): AuthSession? {
        var marked: AuthSession? = null
        dataStore.edit { preferences ->
            val current = read(preferences)
            if (current != null && (token == null || current.token == token)) {
                preferences[KEY_NEEDS_SIGN_IN] = true
                marked = current.copy(needsSignIn = true)
            }
        }
        return marked
    }

    private fun write(preferences: MutablePreferences, session: AuthSession) {
        preferences[KEY_TOKEN] = session.token
        preferences[KEY_USER_ID] = session.user.id
        preferences[KEY_USER_NAME] = session.user.name
        preferences[KEY_USER_EMAIL] = session.user.email
        preferences.putOrRemove(KEY_DELETION_REQUESTED, session.user.deletionRequestedAt)
        preferences.putOrRemove(KEY_DELETION_DUE, session.user.deletionDueAt)
        val sealed = session.refreshToken?.let(::seal)
        if (sealed != null) preferences[KEY_REFRESH_SEALED] = sealed else preferences.remove(KEY_REFRESH_SEALED)
        if (session.needsSignIn) preferences[KEY_NEEDS_SIGN_IN] = true else preferences.remove(KEY_NEEDS_SIGN_IN)
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
                ),
                refreshToken = preferences[KEY_REFRESH_SEALED]?.let(::open),
                needsSignIn = preferences[KEY_NEEDS_SIGN_IN] == true
            )
        } else {
            null
        }
    }

    /** Null when the Keystore refuses (the secret is then simply not kept: sign-in needed after expiry). */
    private fun seal(secret: String): String? = try {
        Base64.getEncoder().encodeToString(secretWrapper.wrap(secret.toByteArray(Charsets.US_ASCII)))
    } catch (e: Exception) {
        null
    }

    /** Null when it cannot be opened (Keystore key gone, damaged value): the same as no refresh secret. */
    private fun open(sealed: String): String? = try {
        String(secretWrapper.unwrap(Base64.getDecoder().decode(sealed)), Charsets.US_ASCII)
    } catch (e: Exception) {
        null
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
        val KEY_REFRESH_SEALED = stringPreferencesKey("refresh_secret_sealed")
        val KEY_NEEDS_SIGN_IN = booleanPreferencesKey("needs_sign_in")
    }
}
