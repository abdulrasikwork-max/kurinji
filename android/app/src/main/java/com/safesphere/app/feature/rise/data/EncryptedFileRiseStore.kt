package com.safesphere.app.feature.rise.data

import com.google.gson.Gson
import com.safesphere.app.core.crypto.LocalKeyWrapper
import com.safesphere.app.feature.rise.domain.Attempt
import com.safesphere.app.feature.rise.domain.Expectation
import com.safesphere.app.feature.rise.domain.Goal
import com.safesphere.app.feature.rise.domain.Outcome
import com.safesphere.app.feature.rise.domain.RiseDomain
import com.safesphere.app.feature.rise.domain.RiseJournal
import com.safesphere.app.feature.rise.domain.SelfDoubt
import com.safesphere.app.feature.rise.domain.Step
import com.safesphere.app.feature.rise.domain.Support
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What reading an account's Rise file gave. */
sealed interface RiseLoad {
    data object Empty : RiseLoad
    data class Loaded(val journal: RiseJournal) : RiseLoad

    /** The file exists but cannot be opened (damaged, or the phone's key is gone). Never overwritten silently. */
    data object Unreadable : RiseLoad
}

/** Rise keeps ONE file per account on this phone; nothing is ever sent to the server. */
interface RiseStore {
    suspend fun load(userId: Long): RiseLoad
    suspend fun save(userId: Long, journal: RiseJournal)

    /** Removes the account's Rise file and its backup (the person's choice, or the account was deleted). */
    suspend fun delete(userId: Long)
}

/**
 * The account's Rise record, encrypted with AES-256-GCM under a fresh random key per save; that key is sealed by the
 * phone's Keystore key ([LocalKeyWrapper], the same non-exportable key as the Evidence Vault). The associated data
 * binds the file to the account (`SSRISE1|<accountId>`), so another account on the same phone cannot read it even
 * with the file renamed. Written atomically (temp file, fsync, rename) with the previous version kept as `.bak`.
 *
 * Format: "SSRISE1" | u16 length | wrapped key | 12-byte IV | ciphertext + tag. Inside: JSON of codes, dates and the
 * person's own words. Nothing else (no times of day, no places, no contacts).
 * This class does blocking file and Keystore work: call it off the main thread (RiseJournalHolder uses an IO dispatcher).
 */
class EncryptedFileRiseStore(
    private val directory: File,
    private val keyWrapper: LocalKeyWrapper,
    private val random: SecureRandom = SecureRandom()
) : RiseStore {

    private val gson = Gson()

    private fun fileFor(userId: Long) = File(directory, "rise-$userId.bin")
    private fun backupFor(userId: Long) = File(directory, "rise-$userId.bin.bak")

    override suspend fun load(userId: Long): RiseLoad {
        val main = fileFor(userId)
        val backup = backupFor(userId)
        if (!main.exists() && !backup.exists()) return RiseLoad.Empty
        for (file in listOf(main, backup)) {
            if (!file.exists()) continue
            val journal = try {
                open(userId, file.readBytes())
            } catch (e: GeneralSecurityException) {
                null
            } catch (e: RuntimeException) {
                null // damaged JSON or a truncated file
            }
            if (journal != null) return RiseLoad.Loaded(journal)
        }
        return RiseLoad.Unreadable
    }

    override suspend fun save(userId: Long, journal: RiseJournal) {
        directory.mkdirs()
        val bytes = seal(userId, journal)
        val main = fileFor(userId)
        val temp = File(directory, "rise-$userId.bin.tmp")
        FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (main.exists()) {
            val backup = backupFor(userId)
            backup.delete()
            main.renameTo(backup)
        }
        if (!temp.renameTo(main)) throw java.io.IOException("Could not save Rise")
    }

    override suspend fun delete(userId: Long) {
        listOf(fileFor(userId), backupFor(userId), File(directory, "rise-$userId.bin.tmp")).forEach { file ->
            if (file.exists()) {
                // Overwrite before deleting (best effort: flash storage may keep old blocks; the key is gone anyway).
                runCatching { file.writeBytes(ByteArray(file.length().toInt())) }
                file.delete()
            }
        }
    }

    // ---- format ---------------------------------------------------------------------------------------------

    internal fun seal(userId: Long, journal: RiseJournal): ByteArray {
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(userId))
            val plain = gson.toJson(StoredJournal.from(journal)).toByteArray(Charsets.UTF_8)
            val sealed = cipher.doFinal(plain)
            plain.fill(0)
            val wrapped = keyWrapper.wrap(key)
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { data ->
                data.write(MAGIC)
                data.writeShort(wrapped.size)
                data.write(wrapped)
                data.write(iv)
                data.write(sealed)
            }
            return out.toByteArray()
        } finally {
            key.fill(0)
        }
    }

    internal fun open(userId: Long, bytes: ByteArray): RiseJournal {
        val buffer = ByteBuffer.wrap(bytes)
        if (bytes.size < MAGIC.size + 2) throw GeneralSecurityException("Too short")
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        if (!magic.contentEquals(MAGIC)) throw GeneralSecurityException("Not a Rise file")
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
            val stored = gson.fromJson(String(plain, Charsets.UTF_8), StoredJournal::class.java)
            plain.fill(0)
            return stored.toDomain()
        } finally {
            key.fill(0)
        }
    }

    private fun aad(userId: Long): ByteArray = "SSRISE1|$userId".toByteArray(Charsets.UTF_8)

    private companion object {
        val MAGIC = "SSRISE1".toByteArray(Charsets.US_ASCII)
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

// ---- the JSON inside the encrypted file (codes as names; an unknown code from a newer app is dropped) ---------------

private data class StoredJournal(
    val version: Int? = null,
    val goals: List<StoredGoal>? = null,
    val introSeen: Boolean? = null,
    val quietMode: Boolean? = null,
    val lockWithScreenLock: Boolean? = null,
    val nextId: Long? = null
) {
    fun toDomain(): RiseJournal {
        val goals = goals.orEmpty().mapNotNull { it.toDomain() }
        val highest = goals.flatMap { g -> listOf(g.id) + g.steps.flatMap { s -> listOf(s.id) + s.attempts.map { it.id } } }.maxOrNull() ?: 0
        return RiseJournal(
            goals = goals,
            introSeen = introSeen == true,
            quietMode = quietMode == true,
            lockWithScreenLock = lockWithScreenLock == true,
            nextId = maxOf(nextId ?: 1, highest + 1)
        )
    }

    companion object {
        fun from(journal: RiseJournal) = StoredJournal(
            version = 1,
            goals = journal.goals.map(StoredGoal::from),
            introSeen = journal.introSeen,
            quietMode = journal.quietMode,
            lockWithScreenLock = journal.lockWithScreenLock,
            nextId = journal.nextId
        )
    }
}

private data class StoredGoal(
    val id: Long? = null, val domain: String? = null, val words: String? = null, val steps: List<StoredStep>? = null,
    val createdOn: String? = null, val aside: Boolean? = null
) {
    fun toDomain(): Goal? {
        return Goal(
            id = id ?: return null,
            domain = enumOrNull<RiseDomain>(domain) ?: RiseDomain.OTHER,
            words = words.orEmpty(),
            steps = steps.orEmpty().mapNotNull { it.toDomain() },
            createdOn = createdOn?.let(LocalDate::parse) ?: return null,
            aside = aside == true
        )
    }

    companion object {
        fun from(goal: Goal) = StoredGoal(goal.id, goal.domain.name, goal.words, goal.steps.map(StoredStep::from),
            goal.createdOn.toString(), goal.aside)
    }
}

private data class StoredStep(val id: Long? = null, val words: String? = null, val attempts: List<StoredAttempt>? = null) {
    fun toDomain(): Step? {
        return Step(id ?: return null, words.orEmpty(), attempts.orEmpty().mapNotNull { it.toDomain() })
    }

    companion object {
        fun from(step: Step) = StoredStep(step.id, step.words, step.attempts.map(StoredAttempt::from))
    }
}

private data class StoredAttempt(
    val id: Long? = null, val startedOn: String? = null, val expectation: String? = null, val doubts: List<String>? = null,
    val safetyConcern: Boolean? = null, val supports: List<String>? = null, val outcome: String? = null,
    val endedOn: String? = null, val first: Boolean? = null
) {
    fun toDomain(): Attempt? {
        return Attempt(
            id = id ?: return null,
            startedOn = startedOn?.let(LocalDate::parse) ?: return null,
            expectation = enumOrNull<Expectation>(expectation),
            doubts = doubts.orEmpty().mapNotNull { enumOrNull<SelfDoubt>(it) }.toSet(),
            safetyConcern = safetyConcern == true,
            supports = supports.orEmpty().mapNotNull { enumOrNull<Support>(it) }.toSet(),
            // A finished try with an unknown outcome from a newer app must not look "open": read it as not OK, never proof.
            outcome = if (outcome == null) null else enumOrNull<Outcome>(outcome) ?: Outcome.NOT_OK,
            endedOn = endedOn?.let(LocalDate::parse),
            first = first == true
        )
    }

    companion object {
        fun from(attempt: Attempt) = StoredAttempt(
            attempt.id, attempt.startedOn.toString(), attempt.expectation?.name, attempt.doubts.map { it.name },
            attempt.safetyConcern, attempt.supports.map { it.name }, attempt.outcome?.name, attempt.endedOn?.toString(), attempt.first
        )
    }
}

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    if (name == null) null else enumValues<T>().firstOrNull { it.name == name }
