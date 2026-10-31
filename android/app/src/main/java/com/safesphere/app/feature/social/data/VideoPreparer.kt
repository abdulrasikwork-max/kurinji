package com.safesphere.app.feature.social.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Phase 17C: a picked video, re-packed on this phone and ready to upload (both files are in the app's cache). */
data class PreparedVideo(
    val file: File,
    /** The poster frame as a new JPEG (no metadata), also written to [posterFile] for the composer's preview. */
    val poster: ByteArray,
    val posterFile: File,
    val durationMs: Long,
    val width: Int,
    val height: Int
) {
    override fun toString() = "PreparedVideo(durationMs=$durationMs, ${width}x$height)" // no paths in logs

    override fun equals(other: Any?) = other is PreparedVideo && other.file == file
    override fun hashCode() = file.hashCode()
}

sealed interface VideoOutcome {
    data class Ready(val video: PreparedVideo) : VideoOutcome
    data class Refused(val reason: MediaRefusal) : VideoOutcome
}

/**
 * Phase 17C (decision D-6): the phone's half of "no metadata in a video". A video picked with the system Photo Picker
 * (no storage permission) is checked (at most 60 s and 50 MB), and its FIRST picture track and FIRST sound track are
 * copied sample by sample into a NEW MP4 with the platform MediaMuxer: nothing is decoded or re-encoded, so the quality
 * is exactly the camera's. MediaMuxer is never given a location (setLocation is never called) and other tracks
 * (positions, motion, timed metadata) are never read; Mp4Scrubber then zeroes the muxer's own "meta" box. A still
 * frame becomes the poster (a new JPEG, like PhotoPreparer's). The original file is never changed. Call off the main
 * thread. Platform-only (MediaExtractor / MediaMuxer), so it is verified on the emulator; its rules are VideoRules.
 */
class VideoPreparer(private val context: Context, private val workDir: File) {

    fun prepare(uri: Uri): VideoOutcome {
        VideoRules.checkSize(declaredSize(uri))?.let { return VideoOutcome.Refused(it) }
        workDir.mkdirs()
        val name = UUID.randomUUID().toString()
        val out = File(workDir, "$name.mp4")
        val posterFile = File(workDir, "$name.jpg")
        return try {
            val info = describe(uri) ?: return VideoOutcome.Refused(MediaRefusal.UNREADABLE)
            VideoRules.checkLength(info.durationMs)?.let { return VideoOutcome.Refused(it) }
            val poster = info.poster ?: return VideoOutcome.Refused(MediaRefusal.UNREADABLE)
            when (val refusal = remux(uri, out, info.rotation)) {
                null -> Unit
                else -> {
                    out.delete()
                    return VideoOutcome.Refused(refusal)
                }
            }
            Mp4Scrubber.scrub(out)
            if (Mp4Scrubber.hasMetadata(out)) {
                out.delete()
                return VideoOutcome.Refused(MediaRefusal.UNSUPPORTED)
            }
            VideoRules.checkSize(out.length())?.let {
                out.delete()
                return VideoOutcome.Refused(it)
            }
            posterFile.writeBytes(poster)
            val (width, height) = VideoRules.displaySize(info.width, info.height, info.rotation)
            VideoOutcome.Ready(PreparedVideo(out, poster, posterFile, info.durationMs!!, width, height))
        } catch (e: Exception) {
            out.delete()
            posterFile.delete()
            VideoOutcome.Refused(if (e is Mp4Scrubber.NotMp4) MediaRefusal.UNSUPPORTED else MediaRefusal.UNREADABLE)
        }
    }

    /** Deletes the work files of a prepared video (after sharing, on Remove, when the composer closes). */
    fun discard(video: PreparedVideo) {
        video.file.delete()
        video.posterFile.delete()
    }

    private class Info(val durationMs: Long?, val rotation: Int, val width: Int, val height: Int, val poster: ByteArray?)

    /** Length, rotation, size and a poster frame. The location the file may carry is never read. */
    private fun describe(uri: Uri): Info? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (VideoRules.checkLength(duration) != null) return Info(duration, rotation, width, height, null)
            val atUs = minOf(1_000_000L, (duration ?: 0L) * 500) // 1 s in, or the middle of a shorter video
            val frame = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            return Info(duration, rotation, width, height, frame?.let(::posterJpeg))
        } catch (e: Exception) {
            return null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** The frame (already upright: the retriever applies the rotation) as a new JPEG, at most [POSTER_SIDE] px. */
    private fun posterJpeg(frame: Bitmap): ByteArray {
        val scale = POSTER_SIDE.toFloat() / max(frame.width, frame.height)
        val sized = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt().coerceAtLeast(1),
            (frame.height * scale).roundToInt().coerceAtLeast(1), true) else frame
        val out = ByteArrayOutputStream()
        sized.compress(Bitmap.CompressFormat.JPEG, POSTER_QUALITY, out)
        if (sized !== frame) sized.recycle()
        frame.recycle()
        return out.toByteArray()
    }

    /** Copies the chosen tracks into [out]; null on success, else why it cannot be shared. */
    private fun remux(uri: Uri, out: File, rotation: Int): MediaRefusal? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            val tracks = (0 until extractor.trackCount).map { i ->
                VideoRules.Track(i, extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty())
            }
            val choice = VideoRules.chooseTracks(tracks, Build.VERSION.SDK_INT)
            if (choice is VideoRules.Choice.Refuse) return choice.reason
            choice as VideoRules.Choice.Copy
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            m.setOrientationHint(VideoRules.orientationHint(rotation))
            val mapping = HashMap<Int, Int>()
            var bufferSize = MIN_BUFFER
            for (source in listOfNotNull(choice.picture, choice.sound)) {
                val format = extractor.getTrackFormat(source)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = max(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                mapping[source] = m.addTrack(format)
                extractor.selectTrack(source)
            }
            m.start()
            val buffer = ByteBuffer.allocateDirect(bufferSize.coerceAtMost(MAX_BUFFER))
            val info = MediaCodec.BufferInfo()
            var written = 0L
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val target = mapping[extractor.sampleTrackIndex]
                if (target != null) {
                    info.set(0, size, extractor.sampleTime, VideoRules.bufferFlags(extractor.sampleFlags))
                    m.writeSampleData(target, buffer, info)
                    written += size
                    if (written > com.safesphere.app.feature.social.domain.ComposerPolicy.MAX_VIDEO_BYTES) {
                        return MediaRefusal.TOO_LARGE
                    }
                }
                extractor.advance()
            }
            m.stop()
            return null
        } catch (e: Exception) {
            return MediaRefusal.UNSUPPORTED
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /** The size the picker reports, if it does (a much larger file is refused before any work). */
    private fun declaredSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()

    companion object {
        const val POSTER_SIDE = 1600
        const val POSTER_QUALITY = 88
        private const val MIN_BUFFER = 4 * 1024 * 1024
        private const val MAX_BUFFER = 16 * 1024 * 1024

        /** Deletes work files a previous run of the app left behind (process death before Share or Remove). */
        fun sweep(workDir: File) {
            workDir.listFiles()?.forEach { it.delete() }
        }
    }
}
