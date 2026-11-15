package com.safesphere.app.testing

import com.safesphere.app.core.crypto.LocalKeyWrapper
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Software stand-in for the Android Keystore (AES-GCM with a key that exists only in this test). */
class FakeKeyWrapper(var available: Boolean = true) : LocalKeyWrapper {
    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    override fun wrap(itemKey: ByteArray): ByteArray {
        if (!available) throw GeneralSecurityException("Keystore unavailable")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(itemKey)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (!available) throw GeneralSecurityException("Keystore unavailable")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wrapped, 0, 12))
        return cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }
}
