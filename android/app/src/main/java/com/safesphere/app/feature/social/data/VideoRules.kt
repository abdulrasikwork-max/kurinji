package com.safesphere.app.feature.social.data

import com.safesphere.app.feature.social.domain.ComposerPolicy

/** Phase 17C: why a picked photo or video cannot be shared as it is (the composer offers Remove, never Retry). */
enum class MediaRefusal {
    /** Over 60 seconds. */
    TOO_LONG,
    /** Over 50 MB (re-packing never makes a video smaller). */
    TOO_LARGE,
    /** A picture or sound format the phone cannot re-pack into MP4 (for example some HDR or Opus recordings). */
    UNSUPPORTED,
    /** The file could not be read as a photo or a video. */
    UNREADABLE,
    /** The server checked it and refused it. */
    NOT_ACCEPTED,
    /** The server's daily limit for this kind of upload was reached. */
    DAILY_LIMIT
}

/**
 * Phase 17C (decision D-6): the phone's rules for re-packing a video with MediaMuxer, kept pure so they are tested on
 * the JVM. Only the FIRST picture track and the FIRST sound track are ever copied: some phones keep a position, motion
 * data or a timed-metadata track beside them, and those are simply never read. A sound track the muxer cannot write
 * refuses the video rather than silently dropping its sound.
 */
object VideoRules {

    /** One track of the picked file, as MediaExtractor describes it. */
    data class Track(val index: Int, val mime: String)

    sealed interface Choice {
        data class Copy(val picture: Int, val sound: Int?) : Choice
        data class Refuse(val reason: MediaRefusal) : Choice
    }

    /** MediaMuxer MPEG_4 picture formats (AV1 from Android 14). */
    fun pictureFormats(sdk: Int): Set<String> = buildSet {
        addAll(listOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp"))
        if (sdk >= 34) add("video/av01")
    }

    /** MediaMuxer MPEG_4 sound format the app relies on (AAC, what phone cameras record). */
    val SOUND_FORMATS = setOf("audio/mp4a-latm")

    fun chooseTracks(tracks: List<Track>, sdk: Int): Choice {
        val picture = tracks.firstOrNull { it.mime.startsWith("video/") } ?: return Choice.Refuse(MediaRefusal.UNREADABLE)
        if (picture.mime !in pictureFormats(sdk)) return Choice.Refuse(MediaRefusal.UNSUPPORTED)
        val sound = tracks.firstOrNull { it.mime.startsWith("audio/") }
        if (sound != null && sound.mime !in SOUND_FORMATS) return Choice.Refuse(MediaRefusal.UNSUPPORTED)
        return Choice.Copy(picture.index, sound?.index)
    }

    /** Null when the length is fine. An unknown length cannot be checked, so it is refused. */
    fun checkLength(durationMs: Long?): MediaRefusal? = when {
        durationMs == null || durationMs <= 0 -> MediaRefusal.UNREADABLE
        durationMs > ComposerPolicy.MAX_VIDEO_DURATION_MS -> MediaRefusal.TOO_LONG
        else -> null
    }

    /** Null when the size is fine (or unknown: the re-packed file is measured again). */
    fun checkSize(bytes: Long?): MediaRefusal? =
        if (bytes != null && bytes > ComposerPolicy.MAX_VIDEO_BYTES) MediaRefusal.TOO_LARGE else null

    /** MediaMuxer accepts only 0, 90, 180 and 270 degrees. */
    fun orientationHint(rotation: Int?): Int {
        val r = ((rotation ?: 0) % 360 + 360) % 360
        return if (r in setOf(0, 90, 180, 270)) r else 0
    }

    /** The size as the video plays (a picture recorded on its side is shown upright). */
    fun displaySize(width: Int, height: Int, rotation: Int?): Pair<Int, Int> =
        if (orientationHint(rotation) % 180 == 90) height to width else width to height

    /** MediaExtractor sample flags to MediaCodec buffer flags for MediaMuxer: key frame and partial frame. */
    fun bufferFlags(sampleFlags: Int): Int {
        var flags = 0
        if (sampleFlags and SAMPLE_FLAG_SYNC != 0) flags = flags or BUFFER_FLAG_KEY_FRAME
        if (sampleFlags and SAMPLE_FLAG_PARTIAL_FRAME != 0) flags = flags or BUFFER_FLAG_PARTIAL_FRAME
        return flags
    }

    /** "0:42", "1:00": how long a video is, in minutes and seconds (rounded down, at least 0:01). */
    fun durationLabel(durationMs: Long): String {
        val seconds = (durationMs / 1000).coerceAtLeast(1)
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    // The platform constants (MediaExtractor / MediaCodec), copied so this object stays pure.
    private const val SAMPLE_FLAG_SYNC = 1
    private const val SAMPLE_FLAG_PARTIAL_FRAME = 4
    private const val BUFFER_FLAG_KEY_FRAME = 1
    private const val BUFFER_FLAG_PARTIAL_FRAME = 8
}
