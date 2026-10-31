package com.safesphere.app.feature.social.ui

import android.content.Context
import androidx.annotation.StringRes
import com.safesphere.app.R
import com.safesphere.app.feature.social.data.MediaRefusal

/** Phase 17C: the composer's words for where each photo or video is (short: they sit under a 120 dp thumbnail). */
object ComposeMediaTexts {

    fun status(context: Context, item: ComposeMedia): String? = when (item.stage) {
        MediaStage.PREPARING -> context.getString(
            if (item.isVideo) R.string.social_video_preparing else R.string.social_photo_preparing)
        MediaStage.UPLOADING -> context.getString(R.string.social_media_uploading) // the bar shows how much
        MediaStage.FINISHING -> context.getString(R.string.social_video_finishing)
        MediaStage.READY -> null
        MediaStage.FAILED -> context.getString(R.string.social_photo_failed)
        MediaStage.REFUSED -> context.getString(refusal(item.refusal))
    }

    /** The progress bar: the share while sending, moving (-1) while preparing or finishing, none otherwise. */
    fun progress(item: ComposeMedia): Int? = when (item.stage) {
        MediaStage.PREPARING, MediaStage.FINISHING -> -1
        MediaStage.UPLOADING -> percent(item)
        else -> null
    }

    @StringRes
    fun refusal(reason: MediaRefusal?): Int = when (reason) {
        MediaRefusal.TOO_LONG -> R.string.social_media_too_long
        MediaRefusal.TOO_LARGE -> R.string.social_media_too_large
        MediaRefusal.UNSUPPORTED -> R.string.social_media_unsupported
        MediaRefusal.DAILY_LIMIT -> R.string.social_media_daily_limit
        MediaRefusal.NOT_ACCEPTED -> R.string.social_media_not_accepted
        MediaRefusal.UNREADABLE, null -> R.string.social_media_unreadable
    }

    private fun percent(item: ComposeMedia) = (item.progress * 100).toInt().coerceIn(0, 100)
}
