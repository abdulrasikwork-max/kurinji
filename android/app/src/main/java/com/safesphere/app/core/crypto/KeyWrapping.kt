package com.safesphere.app.core.crypto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * Seals and opens per-item evidence keys with a key that never leaves secure hardware.
 * A test implementation replaces it on the JVM (the Android Keystore only exists on a device).
 */
interface LocalKeyWrapper {
    /** Throws [GeneralSecurityException] when the device key is unavailable. */
    fun wrap(itemKey: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
}

/**
 * Android Keystore AES-256-GCM "vault master key" (alias [ALIAS]). Created on first use, in StrongBox when the phone
 * has one, otherwise in the TEE / software Keystore. It is non-exportable: even with root access the raw key bytes
 * cannot be copied out on hardware-backed phones.
 *
 * Deliberately NOT bound to the screen lock or biometrics: evidence must still be sealed while the phone is locked in a
 * pocket during an emergency. The cost: whoever unlocks the phone and opens the app can play the evidence (the vault
 * screen says so). Wrapped value = 12-byte IV | ciphertext+tag.
 */
class AndroidKeystoreKeyWrapper(
    /** Phase 11 C1: a separate key (alias [SESSION_ALIAS]) protects the refresh secret; evidence keeps [ALIAS]. */
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
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: throw GeneralSecurityException("Vault key is missing on this phone"),
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

/**
 * The vault recovery key (escrow): the server publishes only the PUBLIC half of an RSA key whose private half is kept
 * offline by the operator. Sealing the item key with it means the uploaded ciphertext stays recoverable if this phone
 * is lost or destroyed, while neither the running server nor a database thief can open it.
 * RSA-OAEP with SHA-256 for both the hash and MGF1 (spelled out: some providers default MGF1 to SHA-1).
 */
class EscrowKey private constructor(val keyId: String, private val publicKey: PublicKey) {

    fun wrap(itemKey: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP)
        return cipher.doFinal(itemKey)
    }

    companion object {
        val OAEP = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        private const val MIN_BITS = 3072

        /**
         * Parses the server's key and checks that its id really is the SHA-256 of the key bytes. If [pinnedKeyId] is
         * set (build config), any other key is refused: a tampered server cannot swap in a key it controls.
         */
        fun parse(publicKeyBase64: String, claimedKeyId: String, pinnedKeyId: String?): EscrowKey {
            val der = Base64.getDecoder().decode(publicKeyBase64)
            val actualId = MessageDigest.getInstance("SHA-256").digest(der).toHex()
            if (actualId != claimedKeyId) throw GeneralSecurityException("Vault key id does not match the key")
            if (!pinnedKeyId.isNullOrBlank() && !pinnedKeyId.equals(actualId, ignoreCase = true)) {
                throw GeneralSecurityException("Vault key is not the one this app trusts")
            }
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
            val bits = (key as java.security.interfaces.RSAPublicKey).modulus.bitLength()
            if (bits < MIN_BITS) throw GeneralSecurityException("Vault key is too weak")
            return EscrowKey(actualId, key)
        }
    }
}
