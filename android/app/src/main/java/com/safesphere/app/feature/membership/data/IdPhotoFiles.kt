package com.safesphere.app.feature.membership.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.safesphere.app.feature.social.data.PhotoPreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One photo of the ID card the person just made (or picked), waiting to be prepared. Opaque to the view model. */
interface IdPhotoShot

/** Prepares an [IdPhotoShot] for upload and deletes its file. */
interface IdPhotoSource {
    /** A new JPEG without any metadata (upright, at most 3072 px), or null when it cannot be read as a photo. */
    suspend fun prepare(shot: IdPhotoShot): ByteArray?

    /** Deletes the shot's file, if the app made one (a picked photo stays where the person keeps it). */
    fun discard(shot: IdPhotoShot)
}

/**
 * Phase N3 (A1): the ID photo is taken by the phone's camera app (`ActivityResultContracts.TakePicture`), written into
 * `cache/id-photo/` through a [FileProvider] limited to that one folder (`res/xml/id_photo_paths.xml`), so the app
 * needs NO camera permission and the photo never reaches the gallery. It is re-encoded by [PhotoPreparer] (no
 * location, time or device in it) and the file is deleted right after (sent or not); [sweep] removes leftovers of a
 * closed app. Only without a camera app is the Photo Picker offered instead (then nothing is written by the app).
 */
class IdPhotoFiles(private val context: Context) : IdPhotoSource {

    private val folder: File get() = File(context.cacheDir, FOLDER)

    /** A shot the app owns: the camera app writes [file] through [uri]. */
    class Capture internal constructor(val file: File, val uri: Uri) : IdPhotoShot {
        override fun toString() = "Capture(<hidden>)"
    }

    /** A photo chosen with the Photo Picker (fallback without a camera app); the app writes nothing. */
    class Picked(val uri: Uri) : IdPhotoShot {
        override fun toString() = "Picked(<hidden>)"
    }

    /** A new, empty target for the camera app; null when the cache folder cannot be used. */
    fun newCapture(): Capture? = runCatching {
        val dir = folder.apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        Capture(file, FileProvider.getUriForFile(context, authority(context), file))
    }.getOrNull()

    override suspend fun prepare(shot: IdPhotoShot): ByteArray? = withContext(Dispatchers.IO) {
        val uri = when (shot) {
            is Capture -> if (shot.file.length() > 0) shot.uri else null
            is Picked -> shot.uri
            else -> null
        }
        uri?.let { PhotoPreparer(context.contentResolver).prepare(it) }
    }

    override fun discard(shot: IdPhotoShot) {
        if (shot is Capture) runCatching { shot.file.delete() }
    }

    /** Deletes every ID photo file left in the cache (the app was closed while the camera was open). */
    fun sweep() {
        runCatching { folder.listFiles()?.forEach { it.delete() } }
    }

    companion object {
        const val FOLDER = "id-photo"
        fun authority(context: Context) = "${context.packageName}.idphoto"
    }
}
