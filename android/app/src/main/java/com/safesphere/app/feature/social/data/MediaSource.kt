package com.safesphere.app.feature.social.data

import android.content.ContentResolver
import android.net.Uri
import com.safesphere.app.feature.social.domain.MediaKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Phase 17C: one item the person picked. The screen wraps the picker's address ([UriPick]); tests use their own.
 * [preview] is what the composer shows before the upload is done (the picked photo itself; a video's poster comes
 * from its preparation).
 */
interface MediaPick {
    val kind: MediaKind
    val preview: Any?
}

/** A picker result, its kind told by the provider's type ("video/…"), never by the file name. */
data class UriPick(val uri: Uri, override val kind: MediaKind) : MediaPick {
    override val preview: Any? get() = if (kind == MediaKind.PHOTO) uri else null
    override fun toString() = "UriPick($kind)" // the address stays out of logs

    companion object {
        fun of(uri: Uri, resolver: ContentResolver): UriPick =
            UriPick(uri, if (resolver.getType(uri)?.startsWith("video/") == true) MediaKind.VIDEO else MediaKind.PHOTO)
    }
}

/** Phase 17C: makes picked items ready for upload, off the main thread (replaces 13B's PhotoSource). */
interface MediaSource {
    /** A new JPEG without metadata, or null if the item cannot be read as a photo. */
    suspend fun preparePhoto(pick: MediaPick): ByteArray?

    /** Phase 17E: a square profile photo (a new JPEG without metadata), or null if the item cannot be read. */
    suspend fun prepareAvatar(pick: MediaPick): ByteArray?

    suspend fun prepareVideo(pick: MediaPick): VideoOutcome

    /** Deletes a prepared video's work files. */
    fun discard(video: PreparedVideo)
}

class AndroidMediaSource(
    private val photos: PhotoPreparer,
    private val videos: VideoPreparer,
    private val io: CoroutineDispatcher
) : MediaSource {

    /**
     * One photo is decoded at a time: each may take up to 16 MP (64 MB) plus its 3072 px copy, so six picked at once
     * must not be decoded side by side. Their uploads still run in parallel.
     */
    private val decoding = Mutex()

    override suspend fun preparePhoto(pick: MediaPick): ByteArray? {
        val uri = (pick as? UriPick)?.uri ?: return null
        return decoding.withLock { withContext(io) { photos.prepare(uri) } }
    }


    override suspend fun prepareAvatar(pick: MediaPick): ByteArray? {
        val uri = (pick as? UriPick)?.uri ?: return null
        return decoding.withLock { withContext(io) { photos.prepareAvatar(uri) } }
    }

    override suspend fun prepareVideo(pick: MediaPick): VideoOutcome {
        val uri = (pick as? UriPick)?.uri ?: return VideoOutcome.Refused(MediaRefusal.UNREADABLE)
        return withContext(io) { videos.prepare(uri) }
    }

    override fun discard(video: PreparedVideo) = videos.discard(video)
}
