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
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "session_preferences"
)

/**
 * Keeps the JWT and basic profile data (id, name, email; since Phase 10A also a pending deletion's dates) so the user
 * stays signed in after the app closes. The password is never stored.
 *
 * RULE (Phase 17J, decision J-8): **no token or refresh secret is EVER written to disk in plain text.** Both are sealed
 * (AES-GCM with a non-exportable Android Keystore key, alias [AndroidKeystoreKeyWrapper.SESSION_ALIAS]) or not stored.
 *
 *  - Normal case: the access token is sealed (key `access_token_sealed`) and so is the refresh secret. Every comparison of
 *    tokens uses the OPENED value (AES-GCM gives every seal a fresh IV). The last opened pair is cached in memory, so
 *    requests do not call the Keystore.
 *  - Sealing is tried twice. If the Keystore still refuses, the session runs MEMORY-ONLY: the token and the refresh
 *    secret live in this process (so SOS sync, renewals and refreshes keep working for as long as the app runs) and the
 *    disk gets only the person's record plus a random, non-secret generation id (`token_memory_gen`) that ties the
 *    memory to exactly that disk state. Every sealed secret is removed in the same edit.
 *  - After a restart (or whenever the memory does not match), the session is the existing safe state: the same
 *    person, an EMPTY token, "sign in to sync", nothing deleted. SOS keeps working locally (it needs the person's
 *    record, not the token); the next sign-in writes a real, sealed session.
 *  - The old plain `access_token` key is never read as a token and never written. A plain token left by a version
 *    before 17J is handled once at the first read ([migrateLegacyToken]): sealed, or moved to memory, and the plain
 *    key removed in the same edit.
 *  - If the Keystore cannot OPEN a sealed token (key gone), the same safe state applies. An update of such a session
 *    leaves the stored secrets alone (the Keystore may only be down for a moment).
 * Backups are disabled in the manifest (allowBackup=false) and the data-extraction rules exclude everything.
 */
class DataStoreSessionStorage internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretWrapper: LocalKeyWrapper
) : SessionStorage {

    constructor(
        context: Context,
        secretWrapper: LocalKeyWrapper = AndroidKeystoreKeyWrapper(AndroidKeystoreKeyWrapper.SESSION_ALIAS)
    ) : this(context.applicationContext.sessionDataStore, secretWrapper)

    /** Last sealed text and what it opened to (one pair per kind of secret), so requests do not call the Keystore. */
    private class Opened(val sealed: String, val plain: String)

    /** The secrets of a memory-only session, tied to the generation id written to disk in the same edit. */
    private class MemoryOnly(val generation: String, val token: String, val refresh: String?)

    @Volatile private var openedToken: Opened? = null
    @Volatile private var openedRefresh: Opened? = null
    @Volatile private var memoryOnly: MemoryOnly? = null

    @Volatile private var migrated = false

    override val session: Flow<AuthSession?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .onStart { migrateLegacyToken() }
        .map(::read)

    /**
     * A plain token written by a version before 17J: sealed once, or (if the Keystore refuses) moved to memory. Either
     * way the plain key is removed in the SAME edit. If the edit fails (IOException) the plain key stays on disk but is
     * never read as a token ([read]); the next collection tries again.
     */
    private suspend fun migrateLegacyToken() {
        if (migrated) return
        try {
            dataStore.edit { preferences ->
                val plain = preferences[KEY_LEGACY_PLAIN_TOKEN]
                if (plain != null) {
                    preferences.remove(KEY_LEGACY_PLAIN_TOKEN)
                    if (plain.isNotEmpty() && preferences[KEY_TOKEN_SEALED] == null &&
                        preferences[KEY_TOKEN_MEMORY_GEN] == null
                    ) {
                        val sealed = seal(plain)
                        if (sealed != null) {
                            preferences[KEY_TOKEN_SEALED] = sealed
                            openedToken = Opened(sealed, plain)
                        } else {
                            val oldRefresh = preferences[KEY_REFRESH_SEALED]?.let(::open)
                            enterMemoryOnly(preferences, plain, oldRefresh)
                        }
                    }
                }
            }
            migrated = true
        } catch (e: IOException) {
            // The next read tries again.
        }
    }

    override suspend fun save(session: AuthSession) {
        dataStore.edit { preferences -> write(preferences, session) }
    }

    /** One atomic edit: the old token is compared and the new session written in the same DataStore transaction. */
    override suspend fun replaceToken(oldToken: String, replacement: AuthSession): Boolean {
        var replaced = false
        dataStore.edit { preferences ->
            if (read(preferences)?.token == oldToken) {
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
            if (next == null) {
                preferences.clear()
                forgetMemory()
            } else {
                write(preferences, next)
            }
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
        // A plain token is never kept, whatever else happens.
        preferences.remove(KEY_LEGACY_PLAIN_TOKEN)
        preferences[KEY_USER_ID] = session.user.id
        preferences[KEY_USER_NAME] = session.user.name
        preferences[KEY_USER_EMAIL] = session.user.email
        preferences.putOrRemove(KEY_DELETION_REQUESTED, session.user.deletionRequestedAt)
        preferences.putOrRemove(KEY_DELETION_DUE, session.user.deletionDueAt)
        // An empty token is the "could not be opened / signed-in-later" state: the stored (unreadable) secrets are left
        // alone (the Keystore may only be unavailable for a moment); a new sign-in writes real ones.
        if (session.token.isNotEmpty()) writeSecrets(preferences, session.token, session.refreshToken)
        if (session.needsSignIn) preferences[KEY_NEEDS_SIGN_IN] = true else preferences.remove(KEY_NEEDS_SIGN_IN)
    }

    /** Sealed when the Keystore works (token AND refresh secret), otherwise memory-only; never plain text. */
    private fun writeSecrets(preferences: MutablePreferences, token: String, refresh: String?) {
        val sealedToken = sealCached(token, openedToken) { openedToken = it }
        val sealedRefresh = refresh?.let { sealCached(it, openedRefresh) { o -> openedRefresh = o } }
        if (sealedToken != null && (refresh == null || sealedRefresh != null)) {
            preferences[KEY_TOKEN_SEALED] = sealedToken
            if (sealedRefresh != null) preferences[KEY_REFRESH_SEALED] = sealedRefresh else preferences.remove(KEY_REFRESH_SEALED)
            preferences.remove(KEY_TOKEN_MEMORY_GEN)
            memoryOnly = null
        } else {
            enterMemoryOnly(preferences, token, refresh)
        }
    }

    /**
     * The Keystore refused: nothing secret goes to disk. The secrets are held in memory under a fresh generation id; the id
     * is the only thing written (a new one every time, so the flow emits even when nothing else changed). Every sealed or
     * plain secret of an earlier state is removed in this edit: a stale refresh secret replayed would end the session.
     */
    private fun enterMemoryOnly(preferences: MutablePreferences, token: String, refresh: String?) {
        val generation = UUID.randomUUID().toString()
        memoryOnly = MemoryOnly(generation, token, refresh)
        preferences.remove(KEY_LEGACY_PLAIN_TOKEN)
        preferences.remove(KEY_TOKEN_SEALED)
        preferences.remove(KEY_REFRESH_SEALED)
        preferences[KEY_TOKEN_MEMORY_GEN] = generation
        openedToken = null
        openedRefresh = null
    }

    private fun forgetMemory() {
        memoryOnly = null
        openedToken = null
        openedRefresh = null
    }

    override suspend fun clear() {
        dataStore.edit { it.clear() }
        forgetMemory()
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
        if (cleared != null) forgetMemory()
        return cleared
    }

    private fun read(preferences: Preferences): AuthSession? {
        val id = preferences[KEY_USER_ID]
        val name = preferences[KEY_USER_NAME]
        val email = preferences[KEY_USER_EMAIL]
        // Only a complete person record counts as a session (a token is not needed to keep the person signed in).
        if (id == null || name == null || email == null) return null
        val user = AuthUser(
            id, name, email,
            deletionRequestedAt = preferences[KEY_DELETION_REQUESTED]?.let(Instant::ofEpochMilli),
            deletionDueAt = preferences[KEY_DELETION_DUE]?.let(Instant::ofEpochMilli)
        )
        val needsSignIn = preferences[KEY_NEEDS_SIGN_IN] == true
        val generation = preferences[KEY_TOKEN_MEMORY_GEN]
        if (generation != null) {
            // Memory-only mode: only the memory of THIS process for THIS generation counts. Never a sealed value (it may
            // belong to an earlier account) and never the plain legacy key.
            val memory = memoryOnly
            return if (memory != null && memory.generation == generation) {
                AuthSession(memory.token, user, refreshToken = memory.refresh, needsSignIn = needsSignIn)
            } else {
                signInNeeded(user)
            }
        }
        val sealedToken = preferences[KEY_TOKEN_SEALED] ?: return signInNeeded(user)
        val token = openCached(sealedToken, openedToken) { openedToken = it } ?: return signInNeeded(user)
        val refresh = preferences[KEY_REFRESH_SEALED]?.let { openCached(it, openedRefresh) { o -> openedRefresh = o } }
        return AuthSession(token, user, refreshToken = refresh, needsSignIn = needsSignIn)
    }

    /** The person's record is kept, the token reads as empty and the session says "sign in to sync". Nothing is deleted. */
    private fun signInNeeded(user: AuthUser) = AuthSession("", user, refreshToken = null, needsSignIn = true)

    /** Null when the Keystore refuses (tried twice: a busy secure element often answers the second time). */
    private fun seal(secret: String): String? {
        repeat(SEAL_ATTEMPTS) {
            try {
                return Base64.getEncoder().encodeToString(secretWrapper.wrap(secret.toByteArray(Charsets.US_ASCII)))
            } catch (e: Exception) {
                // try again, then give up
            }
        }
        return null
    }

    /** Reuses the stored text when [secret] is what it already holds (no new IV, no Keystore call). */
    private fun sealCached(secret: String, cache: Opened?, remember: (Opened) -> Unit): String? {
        if (cache != null && cache.plain == secret) return cache.sealed
        val sealed = seal(secret) ?: return null
        remember(Opened(sealed, secret))
        return sealed
    }

    /** Null when it cannot be opened (Keystore key gone, damaged value): the same as no secret. */
    private fun open(sealed: String): String? = try {
        String(secretWrapper.unwrap(Base64.getDecoder().decode(sealed)), Charsets.US_ASCII)
    } catch (e: Exception) {
        null
    }

    private fun openCached(sealed: String, cache: Opened?, remember: (Opened) -> Unit): String? {
        if (cache != null && cache.sealed == sealed) return cache.plain
        val plain = open(sealed) ?: return null
        remember(Opened(sealed, plain))
        return plain
    }

    private fun MutablePreferences.putOrRemove(key: Preferences.Key<Long>, value: Instant?) {
        if (value == null) remove(key) else this[key] = value.toEpochMilli()
    }

    private companion object {
        const val SEAL_ATTEMPTS = 2

        /** Written by versions before 17J ONLY. Read by [migrateLegacyToken] and removed; never assigned a value. */
        val KEY_LEGACY_PLAIN_TOKEN = stringPreferencesKey("access_token")
        val KEY_TOKEN_SEALED = stringPreferencesKey("access_token_sealed")
        val KEY_TOKEN_MEMORY_GEN = stringPreferencesKey("token_memory_gen")
        val KEY_USER_ID = longPreferencesKey("user_id")
        val KEY_USER_NAME = stringPreferencesKey("user_name")
        val KEY_USER_EMAIL = stringPreferencesKey("user_email")
        val KEY_DELETION_REQUESTED = longPreferencesKey("deletion_requested_at")
        val KEY_DELETION_DUE = longPreferencesKey("deletion_due_at")
        val KEY_REFRESH_SEALED = stringPreferencesKey("refresh_secret_sealed")
        val KEY_NEEDS_SIGN_IN = booleanPreferencesKey("needs_sign_in")
    }
}
