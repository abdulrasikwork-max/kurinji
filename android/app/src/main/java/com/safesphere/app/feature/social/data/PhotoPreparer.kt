package com.safesphere.app.feature.social.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Prepares one picked photo for upload; null when the file cannot be read as an image. */
fun interface PhotoSource {
    suspend fun prepare(uri: Uri): ByteArray?
}

/**
 * Phase 13B (design §4.4, Q4): the phone's half of "EXIF stripped twice". A photo picked with the system Photo Picker
 * (no storage permission) is decoded, turned upright from its orientation tag, scaled so its longer side is at most
 * 2048 px, and written as a NEW JPEG (quality 85). Bitmap.compress writes no metadata at all: no GPS position, no time,
 * no device. The server decodes and re-encodes it again regardless. Call off the main thread.
 */
class PhotoPreparer(private val resolver: ContentResolver) : PhotoSource {

    override suspend fun prepare(uri: Uri): ByteArray? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // A bounds-only decode always returns null (it fills [bounds]); only a missing stream means "cannot read".
        val stream = resolver.openInputStream(uri) ?: return null
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        // A damaged or unusual metadata block only loses the orientation, never the photo.
        val rotation = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val upright = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        out.toByteArray()
    }.getOrNull()

    companion object {
        const val MAX_SIDE = 2048
        const val QUALITY = 85

        /** The largest power of two that keeps the decoded image at least [MAX_SIDE] on its longer side. Pure. */
        fun sampleSize(width: Int, height: Int): Int {
            var sample = 1
            val longer = max(width, height)
            while (longer / (sample * 2) >= MAX_SIDE) sample *= 2
            return sample
        }

        /** The size after scaling the longer side down to [MAX_SIDE] (never up). Pure. */
        fun targetSize(width: Int, height: Int): Pair<Int, Int> {
            val longer = max(width, height)
            if (longer <= MAX_SIDE) return width to height
            val scale = MAX_SIDE.toDouble() / longer
            return (width * scale).roundToInt() to (height * scale).roundToInt()
        }
    }
}
