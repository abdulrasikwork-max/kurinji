package com.safesphere.app.feature.social.data

import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One photo the person chose or took. The screen wraps its address ([UriPick]); tests use their own. N4: photos only
 * (no video anywhere in the app).
 */
interface MediaPick

/** The address of a photo: a Photo Picker result (profile photo) or the app's own camera file (a moment's photo). */
data class UriPick(val uri: Uri) : MediaPick {
    override fun toString() = "UriPick" // the address stays out of logs
}

/** Makes a photo ready for upload, off the main thread. */
interface MediaSource {
    /** A new JPEG without metadata, or null if the item cannot be read as a photo. */
    suspend fun preparePhoto(pick: MediaPick): ByteArray?

    /** Phase 17E: a square profile photo (a new JPEG without metadata), or null if the item cannot be read. */
    suspend fun prepareAvatar(pick: MediaPick): ByteArray?
}

class AndroidMediaSource(
    private val photos: PhotoPreparer,
    private val io: CoroutineDispatcher
) : MediaSource {

    /** One photo is decoded at a time: each may take up to 16 MP (64 MB) plus its 3072 px copy. */
    private val decoding = Mutex()

    override suspend fun preparePhoto(pick: MediaPick): ByteArray? {
        val uri = (pick as? UriPick)?.uri ?: return null
        return decoding.withLock { withContext(io) { photos.prepare(uri) } }
    }

    override suspend fun prepareAvatar(pick: MediaPick): ByteArray? {
        val uri = (pick as? UriPick)?.uri ?: return null
        return decoding.withLock { withContext(io) { photos.prepareAvatar(uri) } }
    }
}
