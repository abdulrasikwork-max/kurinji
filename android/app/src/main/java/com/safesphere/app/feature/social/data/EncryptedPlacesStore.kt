package com.safesphere.app.feature.social.data

import com.google.gson.Gson
import com.safesphere.app.core.crypto.LocalKeyWrapper
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.PrivatePlace
import com.safesphere.app.feature.social.domain.PrivatePlaceKind
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the person keeps about places on this phone (Q9). Never uploaded. */
data class PlacesData(
    val privatePlaces: List<PrivatePlace> = emptyList(),
    /** Up to [RECENT_MAX] places the person used, newest first (public places, kept for "Places I used"). */
    val recent: List<Place> = emptyList(),
    /** The audience of the last post (design §4.1: remembered after the first post; never preselected before). */
    val lastAudience: Audience? = null
) {
    override fun toString() = "PlacesData(<hidden>)"

    companion object {
        const val RECENT_MAX = 20
    }
}

sealed interface PlacesLoad {
    data object Empty : PlacesLoad
    data class Loaded(val data: PlacesData) : PlacesLoad

    /** The file exists but cannot be opened (damaged, or the phone's key is gone). Never overwritten silently. */
    data object Unreadable : PlacesLoad
}

interface PlacesStore {
    suspend fun load(userId: Long): PlacesLoad
    suspend fun save(userId: Long, data: PlacesData)

    /** Removes every account's file (sign-out and account switch, Q9). */
    suspend fun clearAll()
}

/**
 * Phase 13B (Q9): private places and recently used places, encrypted on the phone exactly like Rise
 * (EncryptedFileRiseStore): AES-256-GCM under a fresh random key per save, that key sealed by the phone's Keystore key
 * ([LocalKeyWrapper]); the associated data `SSPLACES1|<accountId>` binds the file to the account, so another account
 * cannot read it even with the file renamed. Written atomically (temp file, fsync, rename).
 *
 * Format: "SSPLACES1" | u16 length | wrapped key | 12-byte IV | ciphertext + tag. Inside: JSON of kinds, cells and
 * public places. Blocking file and Keystore work: call it off the main thread.
 */
class EncryptedPlacesStore(
    private val directory: File,
    private val keyWrapper: LocalKeyWrapper,
    private val random: SecureRandom = SecureRandom()
) : PlacesStore {

    private val gson = Gson()

    private fun fileFor(userId: Long) = File(directory, "places-$userId.bin")

    override suspend fun load(userId: Long): PlacesLoad {
        val file = fileFor(userId)
        if (!file.exists()) return PlacesLoad.Empty
        return try {
            PlacesLoad.Loaded(open(userId, file.readBytes()))
        } catch (e: GeneralSecurityException) {
            PlacesLoad.Unreadable
        } catch (e: RuntimeException) {
            PlacesLoad.Unreadable // damaged JSON or a truncated file
        }
    }

    override suspend fun save(userId: Long, data: PlacesData) {
        directory.mkdirs()
        val bytes = seal(userId, data)
        val temp = File(directory, "places-$userId.bin.tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        val main = fileFor(userId)
        if (main.exists()) main.delete()
        if (!temp.renameTo(main)) throw java.io.IOException("Could not save places")
    }

    override suspend fun clearAll() {
        directory.listFiles()?.forEach { file ->
            if (file.name.startsWith("places-")) {
                // Overwrite before deleting (best effort: flash storage may keep old blocks; the key is gone anyway).
                runCatching { file.writeBytes(ByteArray(file.length().toInt())) }
                file.delete()
            }
        }
    }

    internal fun seal(userId: Long, data: PlacesData): ByteArray {
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(userId))
            val plain = gson.toJson(StoredPlaces.from(data)).toByteArray(Charsets.UTF_8)
            val sealed = cipher.doFinal(plain)
            plain.fill(0)
            val wrapped = keyWrapper.wrap(key)
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { stream ->
                stream.write(MAGIC)
                stream.writeShort(wrapped.size)
                stream.write(wrapped)
                stream.write(iv)
                stream.write(sealed)
            }
            return out.toByteArray()
        } finally {
            key.fill(0)
        }
    }

    internal fun open(userId: Long, bytes: ByteArray): PlacesData {
        if (bytes.size < MAGIC.size + 2) throw GeneralSecurityException("Too short")
        val buffer = ByteBuffer.wrap(bytes)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) throw GeneralSecurityException("Not a places file")
        val wrappedSize = buffer.short.toInt() and 0xFFFF
        if (buffer.remaining() < wrappedSize + IV_BYTES + 16) throw GeneralSecurityException("Too short")
        val wrapped = ByteArray(wrappedSize).also(buffer::get)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val sealed = ByteArray(buffer.remaining()).also(buffer::get)
        val key = keyWrapper.unwrap(wrapped)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(userId))
            val plain = cipher.doFinal(sealed)
            val stored = gson.fromJson(String(plain, Charsets.UTF_8), StoredPlaces::class.java)
            plain.fill(0)
            return stored.toDomain()
        } finally {
            key.fill(0)
        }
    }

    private fun aad(userId: Long): ByteArray = "SSPLACES1|$userId".toByteArray(Charsets.UTF_8)

    private companion object {
        val MAGIC = "SSPLACES1".toByteArray(Charsets.US_ASCII)
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

// ---- the JSON inside the encrypted file (an unknown code from a newer app is dropped) ---------------------------

private data class StoredPlaces(
    val version: Int? = null,
    val privatePlaces: List<StoredPrivate>? = null,
    val recent: List<StoredPlace>? = null,
    val lastAudience: String? = null
) {
    fun toDomain() = PlacesData(
        privatePlaces = privatePlaces.orEmpty().mapNotNull { p ->
            val kind = PrivatePlaceKind.entries.firstOrNull { it.name == p.kind } ?: PrivatePlaceKind.OTHER
            PrivatePlace(p.id ?: return@mapNotNull null, kind, p.cell ?: return@mapNotNull null)
        },
        recent = recent.orEmpty().mapNotNull { r ->
            Place(
                r.publicId ?: return@mapNotNull null, r.name ?: return@mapNotNull null,
                Interest.fromCode(r.category ?: return@mapNotNull null) ?: return@mapNotNull null,
                r.cell ?: return@mapNotNull null, r.areaCell ?: return@mapNotNull null
            )
        }.take(PlacesData.RECENT_MAX),
        lastAudience = Audience.entries.firstOrNull { it.name == lastAudience }
    )

    companion object {
        fun from(data: PlacesData) = StoredPlaces(
            version = 1,
            privatePlaces = data.privatePlaces.map { StoredPrivate(it.id, it.kind.name, it.cell) },
            recent = data.recent.take(PlacesData.RECENT_MAX).map {
                StoredPlace(it.publicId, it.name, it.category.name, it.cell, it.areaCell)
            },
            lastAudience = data.lastAudience?.name
        )
    }
}

private data class StoredPrivate(val id: String? = null, val kind: String? = null, val cell: String? = null)

private data class StoredPlace(
    val publicId: String? = null, val name: String? = null, val category: String? = null, val cell: String? = null,
    val areaCell: String? = null
)
