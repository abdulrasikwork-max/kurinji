package com.safesphere.app.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals and opens small secrets (the session's refresh secret and access token, the key of the private places file)
 * with a key that never leaves secure hardware.
 * A test implementation replaces it on the JVM (the Android Keystore only exists on a device).
 */
interface LocalKeyWrapper {
    /** Throws [GeneralSecurityException] when the device key is unavailable. */
    fun wrap(itemKey: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * Android Keystore AES-256-GCM key (alias [ALIAS]; the name is from the retired Evidence Vault and stays, because the
 * private places file on existing phones is sealed with it). Created on first use, in StrongBox when the phone
 * has one, otherwise in the TEE / software Keystore. It is non-exportable: even with root access the raw key bytes
 * cannot be copied out on hardware-backed phones.
 *
 * Deliberately NOT bound to the screen lock or biometrics: the session must open while the phone is locked in a pocket
 * during an emergency. Wrapped value = 12-byte IV | ciphertext+tag.
 */
class AndroidKeystoreKeyWrapper(
    /** Phase 11 C1: a separate key (alias [SESSION_ALIAS]) protects the session; the places file keeps [ALIAS]. */
    private val alias: String = ALIAS
) : LocalKeyWrapper {

    override fun wrap(itemKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        return cipher.iv + cipher.doFinal(itemKey)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.size <= IV_BYTES) throw GeneralSecurityException("Wrapped key is too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: throw GeneralSecurityException("Device key is missing on this phone"),
            GCMParameterSpec(128, wrapped, 0, IV_BYTES))
        return cipher.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES)
    }

    @Synchronized
    private fun masterKey(): SecretKey = existingKey() ?: create()

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        return store.getKey(alias, null) as? SecretKey
    }

    private fun create(): SecretKey {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return generate(strongBox = true)
            } catch (e: StrongBoxUnavailableException) {
                // No secure element on this phone: the TEE-backed Keystore is used instead.
            }
        }
        return generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "safesphere_evidence_vault_v1"
        const val SESSION_ALIAS = "safesphere_session_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
    }
}

