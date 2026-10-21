package com.safesphere.app.feature.evidence.data

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.VaultItem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Where evidence lives on the phone (all inside app-private storage; excluded from cloud backup and device transfer by
 * data_extraction_rules.xml, and unreadable by other apps):
 *
 *   files/evidence/            vault: <clientRef>.ssev ciphertext files + index.json (metadata, hashes, sealed keys)
 *   files/evidence-capture/    staging: PLAINTEXT while a part is being recorded or the camera app is open, plus a
 *                              small .json note per capture, so a capture interrupted by a crash is sealed on next start
 *   cache/evidence-view/       PLAINTEXT decrypted for playback only while a viewer is open; swept at every start
 *
 * The staging folder is a "files" folder (not cache) because Android may clear cache folders while the app is not
 * running, which would lose an unsealed recording.
 */
class EvidenceFiles(filesDir: File, cacheDir: File) {
    val vaultDir = File(filesDir, "evidence")
    val captureDir = File(filesDir, "evidence-capture")
    val viewDir = File(cacheDir, "evidence-view")
    val indexFile = File(vaultDir, "index.json")
    /** The new index while it is being written (complete and flushed before it replaces [indexFile]). */
    val indexTempFile = File(vaultDir, "index.json.tmp")
    /** The previous index, kept so one damaged file never loses every item key. */
    val indexBackupFile = File(vaultDir, "index.json.bak")
    /** What this phone last saw of each emergency's chain of custody (no content: sequence numbers and hashes). */
    val custodyAnchorsFile = File(vaultDir, "custody-anchors.json")
    /** Each account's recovery key, sealed by the Android Keystore (restore on a new phone, §11). */
    val recoveryKeysFile = File(vaultDir, "recovery-keys.json")

    fun ensureDirs() {
        vaultDir.mkdirs()
        captureDir.mkdirs()
        viewDir.mkdirs()
    }

    fun vaultFile(clientRef: String) = File(vaultDir, "${checked(clientRef)}.ssev")
    fun downloadFile(clientRef: String) = File(vaultDir, "${checked(clientRef)}.download")
    fun captureFile(clientRef: String, kind: EvidenceKind) = File(captureDir, "${checked(clientRef)}.${kind.extension}")
    fun captureNote(clientRef: String) = File(captureDir, "${checked(clientRef)}.json")
    fun viewFile(clientRef: String, kind: EvidenceKind) = File(viewDir, "${checked(clientRef)}.${kind.extension}")

    /** Names come from UUIDs this app made, but a name is still never trusted to be a safe path. */
    private fun checked(clientRef: String): String {
        require(UUID_PATTERN.matches(clientRef)) { "Invalid evidence reference" }
        return clientRef
    }

    companion object {
        private val UUID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}

/** A capture that has started but is not sealed yet (photo / video in the camera app, or an audio part). */
data class PendingCapture(
    val clientRef: String,
    val userId: Long,
    val emergencyId: Long,
    val kind: EvidenceKind,
    val recordingRef: String?,
    val startedAt: Instant,
    val endedAt: Instant? = null
)

/** JSON with ISO-8601 instants. Gson is already a dependency (Retrofit converter); it also runs in JVM unit tests. */
internal object EvidenceJson {
    val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Instant::class.java, object : TypeAdapter<Instant>() {
            override fun write(out: JsonWriter, value: Instant?) {
                if (value == null) out.nullValue() else out.value(value.toString())
            }

            override fun read(reader: JsonReader): Instant? {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }
                return Instant.parse(reader.nextString())
            }
        })
        .create()
}

/**
 * The local vault index: the ONLY place the sealed item keys live, so losing it would make every item on this phone
 * unreadable. Written crash-safely:
 *  1. the new list goes to index.json.tmp and is flushed to the storage chip (fsync);
 *  2. the current index.json is renamed to index.json.bak (the previous good version);
 *  3. index.json.tmp is renamed to index.json.
 * A crash at any point leaves a complete copy in one of the three files. Reading tries them newest first; a damaged
 * file is never thrown away (kept as index.damaged-<time>.json). Callers serialise access.
 */
class EvidenceIndexFile(private val files: EvidenceFiles) {

    private val listType = object : TypeToken<List<VaultItem>>() {}.type

    fun read(): List<VaultItem> {
        val main = parse(files.indexFile)
        if (main != null) return main
        if (files.indexFile.exists()) {
            files.indexFile.renameTo(File(files.vaultDir, "index.damaged-${System.currentTimeMillis()}.json"))
        }
        // A crash between steps 2 and 3 leaves the newest list in .tmp; otherwise .bak is one change behind.
        val recovered = parse(files.indexTempFile) ?: parse(files.indexBackupFile) ?: return emptyList()
        write(recovered)
        return recovered
    }

    fun write(items: List<VaultItem>) {
        files.vaultDir.mkdirs()
        writeSynced(files.indexTempFile, EvidenceJson.gson.toJson(items, listType).toByteArray(Charsets.UTF_8))
        if (files.indexFile.exists()) replace(files.indexFile, files.indexBackupFile)
        replace(files.indexTempFile, files.indexFile)
    }

    /** Null when the file is missing or unreadable. */
    private fun parse(file: File): List<VaultItem>? {
        if (!file.exists()) return null
        return try {
            val items: List<VaultItem>? = EvidenceJson.gson.fromJson(file.readText(), listType)
            // Gson can leave nulls in fields of damaged entries (it bypasses Kotlin's null checks).
            @Suppress("SENSELESS_COMPARISON")
            items?.filter { it != null && it.clientRef != null && it.clientRef.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** Writes [bytes] and waits until they are on the storage chip, not just in the OS cache. */
        fun writeSynced(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
        }

        fun replace(source: File, target: File) {
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

/**
 * Removes a PLAINTEXT file (a staged capture or a decrypted viewing copy): overwrites it with zeros, flushes, then
 * deletes it. Best effort only: flash storage may keep old blocks elsewhere (wear levelling), so this cannot promise
 * the bytes are gone from the chip. What actually protects them is that the file lived only in app-private storage,
 * only for seconds or minutes, under Android's file-based encryption. Never throws.
 */
object PlaintextWipe {
    private const val BUFFER = 64 * 1024

    fun wipe(file: File) {
        if (!file.exists()) return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val zeros = ByteArray(BUFFER)
                var remaining = raf.length()
                raf.seek(0)
                while (remaining > 0) {
                    val n = minOf(remaining, BUFFER.toLong()).toInt()
                    raf.write(zeros, 0, n)
                    remaining -= n
                }
                raf.fd.sync()
            }
        } catch (e: IOException) {
            // Could not overwrite (e.g. still open elsewhere): deleting is still better than keeping it.
        }
        file.delete()
    }
}
