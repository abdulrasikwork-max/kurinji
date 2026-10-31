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

/**
 * Phase 13B (design §4.4, Q4): the phone's half of "EXIF stripped twice". A photo picked with the system Photo Picker
 * (no storage permission) is decoded, turned upright from its orientation tag, scaled so its longer side is at most
 * [MAX_SIDE] px, and written as a NEW JPEG. Bitmap.compress writes no metadata at all: no GPS position, no time,
 * no device. The server decodes and re-encodes it again regardless. Call off the main thread.
 * Phase 17C: 3072 px and quality 92 (was 2048 px / 85: visibly soft on large screens); if a very detailed photo would
 * pass the server's 5 MB limit, the next lower quality is used ([QUALITIES]).
 */
class PhotoPreparer(private val resolver: ContentResolver) {

    /** One picked photo, ready for upload; null when the file cannot be read as an image. */
    fun prepare(uri: Uri): ByteArray? = runCatching {
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
        var jpeg = ByteArray(0)
        for (quality in QUALITIES) {
            val out = ByteArrayOutputStream()
            upright.compress(Bitmap.CompressFormat.JPEG, quality, out)
            jpeg = out.toByteArray()
            if (jpeg.size <= MAX_BYTES) break
        }
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
        jpeg.takeIf { it.size <= MAX_BYTES }
    }.getOrNull()

    companion object {
        const val MAX_SIDE = 3072
        const val QUALITY = 92

        /** Quality 92 first; lower only when the photo would not fit the server's limit. */
        val QUALITIES = intArrayOf(QUALITY, 85, 75)

        /** The server's limit for one photo (5 MiB). */
        const val MAX_BYTES = 5 * 1024 * 1024


        /**
         * Phase 17C: at most 16 million pixels are decoded (64 MB of memory), so a 50-200 MP camera photo cannot run the
         * app out of memory; such a photo may then come out a little under [MAX_SIDE].
         */
        const val MAX_DECODED_PIXELS = 16_000_000L

        /**
         * The largest power of two that keeps the decoded image at least [MAX_SIDE] on its longer side, then doubled
         * until it fits [MAX_DECODED_PIXELS]. Pure.
         */
        fun sampleSize(width: Int, height: Int): Int {
            var sample = 1
            val longer = max(width, height)
            while (longer / (sample * 2) >= MAX_SIDE) sample *= 2
            while ((width / sample).toLong() * (height / sample) > MAX_DECODED_PIXELS) sample *= 2
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
