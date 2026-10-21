package com.safesphere.app.feature.evidence.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.StatFs
import com.safesphere.app.feature.evidence.domain.RecordingPolicy
import java.io.File

/** Writes one audio part to a file. A fake replaces it in JVM tests (MediaRecorder only exists on a device). */
interface AudioSegmentWriter {
    /** Starts writing into [file]; [onError] is called if the microphone is lost meanwhile (another app, revoked). */
    fun start(file: File, onError: () -> Unit)

    /** Finishes the part. Returns false when nothing usable was recorded. Always releases the microphone. */
    fun stop(): Boolean
}

/** How much space is left for evidence. */
fun interface StorageSpace {
    fun freeBytes(): Long
}

class AndroidStorageSpace(private val dir: File) : StorageSpace {
    override fun freeBytes(): Long = try {
        StatFs(dir.absolutePath).availableBytes
    } catch (e: IllegalArgumentException) {
        Long.MAX_VALUE // cannot tell: do not block evidence for that
    }
}

/**
 * MediaRecorder writing AAC in an ADTS stream. ADTS has a small header per audio frame and no index at the end of the
 * file (unlike MP4), so a part cut off by a crash or a killed process is still playable up to the last frame, and the
 * crash recovery can seal it as evidence. Mono, speech quality ([RecordingPolicy]).
 */
class MediaRecorderSegmentWriter(private val context: Context) : AudioSegmentWriter {

    private var recorder: MediaRecorder? = null

    override fun start(file: File, onError: () -> Unit) {
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(RecordingPolicy.SAMPLE_RATE_HZ)
            created.setAudioEncodingBitRate(RecordingPolicy.BIT_RATE)
            created.setOutputFile(file.absolutePath)
            created.setOnErrorListener { _, _, _ -> onError() }
            created.prepare()
            created.start()
            recorder = created
        } catch (e: Exception) {
            created.release()
            throw e
        }
    }

    override fun stop(): Boolean {
        val current = recorder ?: return false
        recorder = null
        return try {
            current.stop()
            true
        } catch (e: RuntimeException) {
            // Thrown when stop() comes right after start() with no audio data: the part is empty.
            false
        } finally {
            current.release()
        }
    }
}
