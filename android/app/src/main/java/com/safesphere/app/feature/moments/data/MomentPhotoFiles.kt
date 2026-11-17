package com.safesphere.app.feature.moments.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.safesphere.app.core.media.CameraFiles
import com.safesphere.app.feature.social.data.PhotoPreparer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One photo the camera app just wrote for Create, waiting to be prepared. Opaque to the view model. */
interface MomentShot {
    /** A short name that survives the app being closed while the camera app is open (no path, no content). */
    val name: String
}

/** Prepares a [MomentShot] for upload and deletes its file. */
interface MomentPhotoSource {
    /** A new JPEG without any metadata (upright, at most 3072 px), or null when it cannot be read as a photo. */
    suspend fun prepare(shot: MomentShot): ByteArray?

    /** Deletes the shot's file. */
    fun discard(shot: MomentShot)

    /** The shot with this [MomentShot.name] again (after the app was closed while the camera app was open). */
    fun shotNamed(name: String): MomentShot?
}

/**
 * Phase N4 (A2, D2 / N-43): a moment's photo is taken NOW, with the phone's camera app
 * (`ActivityResultContracts.TakePicture`). The camera app writes into `cache/moment-photo/` through the app's one
 * [FileProvider], whose paths name only this folder and the ID-photo folder (`res/xml/camera_paths.xml`). So the app
 * needs NO camera permission, never reads the gallery, and the photo never reaches the gallery. It is re-encoded by
 * [PhotoPreparer] (no location, time or device in it) and the file is deleted right after; the prepared copy lives in
 * Create's memory only. [sweep] removes leftovers of a closed app. Without a camera app the button is hidden.
 */
class MomentPhotoFiles(private val context: Context) : MomentPhotoSource {

    private val folder: File get() = File(context.cacheDir, FOLDER)

    /** One photo is decoded at a time (each may take up to 64 MB while it is prepared). */
    private val decoding = Mutex()

    /** A shot the app owns: the camera app writes [file] through [uri]. */
    class Capture internal constructor(val file: File, val uri: Uri) : MomentShot {
        override val name: String get() = file.name
        override fun toString() = "Capture(<hidden>)"
    }

    /** True when some app on this phone can take a photo for us. */
    fun hasCameraApp(): Boolean =
        runCatching { Intent(MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(context.packageManager) != null }
            .getOrDefault(false)

    /** A new, empty target for the camera app; null when the cache folder cannot be used. */
    fun newCapture(): Capture? = runCatching {
        val dir = folder.apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        Capture(file, FileProvider.getUriForFile(context, CameraFiles.authority(context), file))
    }.getOrNull()

    override fun shotNamed(name: String): MomentShot? {
        if (!NAME.matches(name)) return null
        val file = File(folder, name)
        return runCatching { Capture(file, FileProvider.getUriForFile(context, CameraFiles.authority(context), file)) }.getOrNull()
    }

    override suspend fun prepare(shot: MomentShot): ByteArray? {
        val capture = shot as? Capture ?: return null
        return decoding.withLock {
            withContext(Dispatchers.IO) {
                if (capture.file.length() > 0) PhotoPreparer(context.contentResolver).prepare(capture.uri) else null
            }
        }
    }

    override fun discard(shot: MomentShot) {
        if (shot is Capture) runCatching { shot.file.delete() }
    }

    /** Deletes every moment photo file left in the cache (the app was closed while the camera was open). */
    fun sweep() {
        runCatching { folder.listFiles()?.forEach { it.delete() } }
    }

    companion object {
        const val FOLDER = "moment-photo"
        private val NAME = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg$")
    }
}
