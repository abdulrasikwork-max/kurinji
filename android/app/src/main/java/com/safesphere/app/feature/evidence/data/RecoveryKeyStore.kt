package com.safesphere.app.feature.evidence.data

import com.google.gson.reflect.TypeToken
import com.safesphere.app.core.crypto.LocalKeyWrapper
import com.safesphere.app.core.crypto.RecoveryKeyCrypto
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * What [com.safesphere.app.feature.evidence.session.EvidenceVault] needs from the recovery key: wrapping a new item's
 * key for the person's recovery key, offline, without asking for the passphrase.
 */
interface RecoveryKeys {
    fun hasKey(userId: Long): Boolean

    /** (rkVersion, 60-byte wrap) of [itemKey] for [userId]'s recovery key, or null when this phone holds none. */
    fun wrapItemKey(userId: Long, clientRef: String, ciphertextSha256: String, itemKey: ByteArray): Pair<Int, ByteArray>?

    companion object {
        val NONE = object : RecoveryKeys {
            override fun hasKey(userId: Long) = false
            override fun wrapItemKey(userId: Long, clientRef: String, ciphertextSha256: String, itemKey: ByteArray) = null
        }
    }
}

/**
 * Each account's recovery key (RK) on this phone, sealed by the same non-exportable Android Keystore key as the item
 * keys (so it is exactly as protected as the evidence keys themselves; nothing new is exposed). Kept across logout,
 * like the items, so a person who signs in again keeps protecting new evidence. File written like the index: fsync'd
 * temp file, then an atomic rename. Never logged.
 */
class RecoveryKeyStore(
    private val files: EvidenceFiles,
    private val keyWrapper: LocalKeyWrapper,
    private val random: SecureRandom = SecureRandom()
) : RecoveryKeys {

    private data class Entry(val userId: Long, val rkVersion: Int, val sealedKey: String)

    private val listType = object : TypeToken<List<Entry>>() {}.type

    @Synchronized
    override fun hasKey(userId: Long): Boolean = read().any { it.userId == userId }

    @Synchronized
    fun rkVersion(userId: Long): Int? = read().firstOrNull { it.userId == userId }?.rkVersion

    /** Runs [block] with [userId]'s recovery key (wiped afterwards); null when there is none or it cannot be opened. */
    @Synchronized
    fun <T> withKey(userId: Long, block: (rkVersion: Int, key: ByteArray) -> T): T? {
        val entry = read().firstOrNull { it.userId == userId } ?: return null
        val key = try {
            keyWrapper.unwrap(Base64.getDecoder().decode(entry.sealedKey))
        } catch (e: Exception) {
            return null
        }
        try {
            return block(entry.rkVersion, key)
        } finally {
            key.fill(0)
        }
    }

    /** Stores (or replaces) [userId]'s recovery key, sealed by the Keystore. Throws when the Keystore fails. */
    @Synchronized
    fun put(userId: Long, rkVersion: Int, key: ByteArray) {
        val sealed = Base64.getEncoder().encodeToString(keyWrapper.wrap(key))
        write(read().filterNot { it.userId == userId } + Entry(userId, rkVersion, sealed))
    }

    override fun wrapItemKey(userId: Long, clientRef: String, ciphertextSha256: String, itemKey: ByteArray): Pair<Int, ByteArray>? =
        withKey(userId) { version, rk -> version to RecoveryKeyCrypto.wrapItemKey(rk, itemKey, clientRef, ciphertextSha256, random) }

    private fun read(): List<Entry> = try {
        if (!files.recoveryKeysFile.exists()) emptyList()
        else EvidenceJson.gson.fromJson<List<Entry>>(files.recoveryKeysFile.readText(), listType).orEmpty()
    } catch (e: Exception) {
        emptyList() // a damaged file only means "no recovery key here": the person can restore it with the passphrase
    }

    private fun write(entries: List<Entry>) {
        files.vaultDir.mkdirs()
        val temp = File(files.vaultDir, "recovery-keys.json.tmp")
        EvidenceIndexFile.writeSynced(temp, EvidenceJson.gson.toJson(entries, listType).toByteArray(Charsets.UTF_8))
        EvidenceIndexFile.replace(temp, files.recoveryKeysFile)
    }
}
