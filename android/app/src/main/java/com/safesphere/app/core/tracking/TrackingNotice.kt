package com.safesphere.app.core.tracking

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * What the tracking notification should say. Text resources only: the notification never contains a
 * location or a destination, because it can be visible on the lock screen.
 */
data class TrackingNotice(@StringRes val title: Int, @StringRes val text: Int)

/**
 * Whatever needs the tracking foreground service publishes its notice; null means "nothing to track".
 * [currentNotice] is read synchronously when the service starts; [notices] is observed afterwards.
 */
interface TrackingNoticeSource {
    fun currentNotice(): TrackingNotice?
    val notices: Flow<TrackingNotice?>
}

/**
 * The ONE source the service reads: the first non-null notice wins, in priority order (an active emergency
 * before a Safe Walk). When every source is null the service stops itself.
 */
class CompositeTrackingNoticeSource(private val sources: List<TrackingNoticeSource>) : TrackingNoticeSource {

    override fun currentNotice(): TrackingNotice? = sources.firstNotNullOfOrNull { it.currentNotice() }

    override val notices: Flow<TrackingNotice?> =
        combine(sources.map { it.notices }) { values -> values.firstOrNull { it != null } }.distinctUntilChanged()
}
