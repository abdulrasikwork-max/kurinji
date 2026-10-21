package com.safesphere.app.feature.account.ui

import android.content.Context
import com.safesphere.app.R
import com.safesphere.app.feature.account.domain.DeletionNotice
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Words for a pending deletion, shared by the Account screen and Home. Dates in the phone's own time zone. */
object AccountTexts {

    fun deletionBanner(context: Context, notice: DeletionNotice): String = when (notice) {
        is DeletionNotice.Scheduled -> if (notice.requestedAt != null) {
            context.getString(R.string.account_deletion_pending_requested, date(notice.dueAt), date(notice.requestedAt))
        } else {
            context.getString(R.string.account_deletion_pending, date(notice.dueAt))
        }
        DeletionNotice.Soon -> context.getString(R.string.account_deletion_pending_soon)
    }

    fun date(instant: Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(instant)
}
