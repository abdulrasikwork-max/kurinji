package com.safesphere.app.feature.auth

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.safesphere.app.core.crypto.LocalKeyWrapper
import com.safesphere.app.feature.auth.data.DataStoreSessionStorage
import com.safesphere.app.feature.auth.domain.AuthSession
import com.safesphere.app.feature.auth.domain.AuthUser
import com.safesphere.app.testing.FakeKeyWrapper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 17J (A14, decision J-8): no token and no refresh secret is ever written to disk in plain text. Runs the real
 * [DataStoreSessionStorage] on a real DataStore file with a software stand-in for the Keystore (JVM, fake Keystore; a real
 * Keystore failure cannot be produced on demand, so the memory-only path is NOT verified on a device).
 */
class SealedAccessTokenTest {

    @get:Rule val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var file: File
    private lateinit var dataStore: DataStore<Preferences>
    private val wrapper = FakeKeyWrapper()
    private val user = AuthUser(7, "Asha", "asha@example.com")
    private val otherUser = AuthUser(8, "Bala", "bala@example.com")

    private val legacyKey = stringPreferencesKey("access_token")
    private val sealedKey = stringPreferencesKey("access_token_sealed")
    private val refreshKey = stringPreferencesKey("refresh_secret_sealed")
    private val generationKey = stringPreferencesKey("token_memory_gen")

    @Before fun setUp() {
        file = folder.newFile("session.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { file }
    }

    @After fun tearDown() {
        scope.cancel()
    }

    private fun storage(w: LocalKeyWrapper = wrapper) = DataStoreSessionStorage(dataStore, w)

    private fun raw(): Preferences = runBlocking { dataStore.data.first() }

    /** The whole DataStore file as text: a secret must not appear in it, however it is stored. */
    private fun fileText(): String = String(file.readBytes(), Charsets.ISO_8859_1)

    private fun assertNoSecretOnDisk(vararg secrets: String) {
        val text = fileText()
        secrets.forEach { assertFalse("'$it' found in the session file", text.contains(it)) }
        assertNull("the plain token key must not exist", raw()[legacyKey])
    }

    private class CountingWrapper(private val inner: FakeKeyWrapper) : LocalKeyWrapper {
        var unwraps = 0
        override fun wrap(itemKey: ByteArray) = inner.wrap(itemKey)
        override fun unwrap(wrapped: ByteArray): ByteArray { unwraps++; return inner.unwrap(wrapped) }
    }

    /** Fails the first [failures] wrap calls, then works. */
    private class FlakyWrapper(private val inner: FakeKeyWrapper, private var failures: Int) : LocalKeyWrapper {
        override fun wrap(itemKey: ByteArray): ByteArray {
            if (failures > 0) { failures--; throw java.security.GeneralSecurityException("busy") }
            return inner.wrap(itemKey)
        }
        override fun unwrap(wrapped: ByteArray) = inner.unwrap(wrapped)
    }

    // ---- sealed (normal) ------------------------------------------------------------------------------------------

    @Test fun `the access token is not stored as plain text`() = runBlocking {
        val s = storage()
        s.save(AuthSession("header.payload.signature", user, refreshToken = "refresh-1"))
        val prefs = raw()
        assertNull(prefs[legacyKey])
        val sealed = prefs[sealedKey]
        assertNotNull(sealed)
        assertFalse(sealed!!.contains("header.payload"))
        assertNull(prefs[generationKey])
        assertNoSecretOnDisk("header.payload.signature", "refresh-1")
        assertEquals("header.payload.signature", s.session.first()?.token)
        assertEquals("refresh-1", s.session.first()?.refreshToken)
    }

    @Test fun `replaceToken and clearIfToken keep working although every seal looks different`() = runBlocking {
        val s = storage()
        s.save(AuthSession("t1", user))
        assertFalse(s.replaceToken("wrong", AuthSession("x", user)))
        assertTrue(s.replaceToken("t1", AuthSession("t2", user)))
        assertFalse(s.replaceToken("t1", AuthSession("t3", user)))
        assertTrue(s.replaceToken("t2", AuthSession("t3", user)))
        assertEquals("t3", s.session.first()?.token)

        assertNull(s.markNeedsSignIn("old"))
        assertEquals(true, s.markNeedsSignIn("t3")?.needsSignIn)
        assertTrue(s.session.first()!!.needsSignIn)

        assertNull(s.clearIfToken("t1"))
        assertNotNull(s.session.first())
        assertNotNull(s.clearIfToken("t3"))
        assertNull(s.session.first())
    }

    @Test fun `replacing the token changes the sealed text and the old token no longer matches`() = runBlocking {
        val s = storage()
        s.save(AuthSession("t1", user))
        val first = raw()[sealedKey]
        assertTrue(s.replaceToken("t1", AuthSession("t2", user)))
        assertNotEquals(first, raw()[sealedKey])
        assertNull(s.clearIfToken("t1"))
    }

    @Test fun `the Keystore is not called again for every request`() = runBlocking {
        val counting = CountingWrapper(wrapper)
        val s = storage(counting)
        s.save(AuthSession("t1", user, refreshToken = "r1"))
        repeat(50) { s.session.first() }
        assertEquals("both secrets were sealed by this instance: nothing to open again", 0, counting.unwraps)
        val fresh = storage(counting)
        repeat(50) { fresh.session.first() }
        assertEquals("one opening per secret, then the cache", 2, counting.unwraps)
    }

    @Test fun `a busy Keystore that answers the second time still seals`() = runBlocking {
        val s = storage(FlakyWrapper(wrapper, failures = 1))
        s.save(AuthSession("tok-secret-1", user, refreshToken = "ref-secret-1"))
        assertNull("a transient failure must not push anyone into memory-only mode", raw()[generationKey])
        assertNotNull(raw()[sealedKey])
        assertNoSecretOnDisk("tok-secret-1", "ref-secret-1")
        assertEquals("tok-secret-1", storage().session.first()?.token)
    }

    // ---- a token the Keystore cannot OPEN ----------------------------------------------------------------------------

    @Test fun `a token the Keystore cannot open does not sign the person out`() = runBlocking {
        val s = storage()
        s.save(AuthSession("t1", user, refreshToken = "r1"))
        val fresh = storage()          // no in-memory cache, as after a process start
        wrapper.available = false
        val read = fresh.session.first()
        assertNotNull("the session must not vanish", read)
        assertEquals("", read!!.token)
        assertEquals(user, read.user)
        assertTrue(read.needsSignIn)
        assertNull(read.refreshToken)
        // Keeping the record is not a write: an update of the unreadable session leaves the sealed secrets alone, and
        // when the key is back the session is whole again.
        fresh.update { it?.copy(needsSignIn = false) }
        wrapper.available = true
        val back = storage().session.first()
        assertEquals("t1", back?.token)
        assertEquals("r1", back?.refreshToken)
    }

    // ---- the Keystore refuses to SEAL: memory-only, never plain ------------------------------------------------------

    @Test fun `when sealing is refused nothing secret reaches the disk and the session still works in this process`() = runBlocking {
        wrapper.available = false
        val s = storage()
        s.save(AuthSession("tok-secret-1", user, refreshToken = "ref-secret-1"))
        assertNoSecretOnDisk("tok-secret-1", "ref-secret-1")
        assertNull(raw()[sealedKey])
        assertNull(raw()[refreshKey])
        assertNotNull(raw()[generationKey])
        val live = s.session.first()!!
        assertEquals("tok-secret-1", live.token)
        assertEquals("ref-secret-1", live.refreshToken)
        assertFalse(live.needsSignIn)
    }

    @Test fun `in memory-only mode replaceToken, markNeedsSignIn and clearIfToken work against the memory token`() = runBlocking {
        wrapper.available = false
        val s = storage()
        s.save(AuthSession("mem-tok-1", user, refreshToken = "mem-ref-1"))
        assertFalse(s.replaceToken("wrong", AuthSession("x", user)))
        assertTrue(s.replaceToken("mem-tok-1", AuthSession("mem-tok-2", user, refreshToken = "mem-ref-2")))
        assertTrue(s.replaceAfterRefresh("mem-ref-2", AuthSession("mem-tok-3", user, refreshToken = "mem-ref-3")))
        assertFalse(s.replaceAfterRefresh("mem-ref-2", AuthSession("mem-tok-4", user, refreshToken = "mem-ref-4")))
        assertEquals("mem-tok-3", s.session.first()?.token)
        assertNull(s.markNeedsSignIn("mem-tok-1"))
        assertEquals(true, s.markNeedsSignIn("mem-tok-3")?.needsSignIn)
        assertNoSecretOnDisk("mem-tok-1", "mem-tok-2", "mem-tok-3", "mem-ref-1", "mem-ref-2", "mem-ref-3")
        assertNull(s.clearIfToken("mem-tok-1"))
        assertNotNull(s.clearIfToken("mem-tok-3"))
        assertNull(s.session.first())
    }

    @Test fun `a renewal in memory-only mode reaches whoever collects the session`() = runBlocking {
        wrapper.available = false
        val s = storage()
        s.save(AuthSession("t1", user))
        val seen = CopyOnWriteArrayList<String>()
        val job = scope.launch { s.session.collect { seen.add(it?.token ?: "<null>") } }
        withTimeout(5_000) { while (seen.lastOrNull() != "t1") delay(10) }
        assertTrue(s.replaceToken("t1", AuthSession("t2", user)))
        // Nothing else on disk changed: only the generation id makes the flow emit (video headers and the SOS screen
        // read the session this way).
        withTimeout(5_000) { while (seen.lastOrNull() != "t2") delay(10) }
        job.cancel()
        assertEquals("t2", seen.last())
    }

    @Test fun `after a restart a memory-only session is the same person with an empty token and sign in to sync`() = runBlocking {
        wrapper.available = false
        storage().save(AuthSession("tok-secret-1", user, refreshToken = "ref-secret-1"))
        val restarted = storage().session.first()
        assertNotNull("the record is kept", restarted)
        assertEquals(user, restarted!!.user)
        assertEquals("", restarted.token)
        assertTrue(restarted.needsSignIn)
        assertNull(restarted.refreshToken)
        // the Keystore coming back does not bring the old secrets back: they were never stored
        wrapper.available = true
        assertEquals("", storage().session.first()?.token)
        assertNoSecretOnDisk("tok-secret-1", "ref-secret-1")
    }

    @Test fun `a refused seal for another account never lets the first account's token be read`() = runBlocking {
        storage().save(AuthSession("token-of-A", user, refreshToken = "refresh-of-A"))
        assertNotNull(raw()[sealedKey])
        wrapper.available = false
        val s = storage()
        s.save(AuthSession("token-of-B", otherUser, refreshToken = "refresh-of-B"))
        assertNull("account A's sealed secrets are removed in the same edit", raw()[sealedKey])
        assertNull(raw()[refreshKey])
        wrapper.available = true
        val fresh = storage().session.first()!!
        assertEquals(otherUser, fresh.user)
        assertEquals("", fresh.token)
        assertNull(fresh.refreshToken)
        assertNoSecretOnDisk("token-of-A", "token-of-B", "refresh-of-A", "refresh-of-B")
    }

    @Test fun `when the Keystore returns the next write seals again and the memory-only marker goes`() = runBlocking {
        wrapper.available = false
        val s = storage()
        s.save(AuthSession("t1", user, refreshToken = "r1"))
        assertNotNull(raw()[generationKey])
        wrapper.available = true
        assertTrue(s.replaceToken("t1", AuthSession("t2", user, refreshToken = "r2")))
        assertNull(raw()[generationKey])
        assertNotNull(raw()[sealedKey])
        assertNotNull(raw()[refreshKey])
        val fresh = storage().session.first()!!
        assertEquals("t2", fresh.token)
        assertEquals("r2", fresh.refreshToken)
        assertFalse(fresh.needsSignIn)
    }

    // ---- a plain token left by a version before 17J ---------------------------------------------------------------------

    private suspend fun writeLegacy(token: String) {
        dataStore.edit {
            it[legacyKey] = token
            it[longPreferencesKey("user_id")] = 7
            it[stringPreferencesKey("user_name")] = "Asha"
            it[stringPreferencesKey("user_email")] = "asha@example.com"
        }
    }

    @Test fun `a plain token from an older version is sealed once at the first read`() = runBlocking {
        writeLegacy("old-plain-token")
        val s = storage()
        assertEquals("old-plain-token", s.session.first()?.token)
        val prefs = raw()
        assertNull("the plain copy must be gone", prefs[legacyKey])
        assertNotNull(prefs[sealedKey])
        assertNoSecretOnDisk("old-plain-token")
        assertEquals("old-plain-token", storage().session.first()?.token)
    }

    @Test fun `a plain token from an older version is removed even when the Keystore refuses to seal it`() = runBlocking {
        writeLegacy("old-plain-token")
        wrapper.available = false
        val s = storage()
        assertEquals("it keeps working in this process", "old-plain-token", s.session.first()?.token)
        assertNoSecretOnDisk("old-plain-token")
        assertNull(raw()[sealedKey])
        assertNotNull(raw()[generationKey])
        val restarted = storage().session.first()!!
        assertEquals("", restarted.token)
        assertTrue(restarted.needsSignIn)
        assertEquals(user, restarted.user)
    }

    // ---- source guard ---------------------------------------------------------------------------------------------------

    @Test fun `the session storage source never assigns the plain token key`() {
        val source = File("src/main/java/com/safesphere/app/feature/auth/data/DataStoreSessionStorage.kt")
            .readText(Charsets.UTF_8)
        assertFalse("a plain token must never be written",
            Regex("""\[KEY_LEGACY_PLAIN_TOKEN\]\s*=(?!=)""").containsMatchIn(source))
        assertFalse(Regex("""\.set\(\s*KEY_LEGACY_PLAIN_TOKEN""").containsMatchIn(source))
        assertFalse("the old plain key name must not come back under another constant",
            Regex("""stringPreferencesKey\("access_token"\)""").findAll(source).count() > 1)
        // the only reads of the plain key are the one-time migration; read() never touches it
        val readFunction = source.substringAfter("private fun read(").substringBefore("private fun signInNeeded")
        assertFalse(readFunction.contains("KEY_LEGACY_PLAIN_TOKEN"))
    }
}
