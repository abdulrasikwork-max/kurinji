package com.safesphere.app.feature.circlealert.domain

/**
 * Everything the Safety Circle part keeps on the phone, for ONE signed-in user (cleared on logout):
 * - [recipients]: enabled Safety Circle contacts (name + number), so the person can text them from the SMS app even
 *   without internet (N6: the phone never texts anyone by itself);
 * - [deliveries]: recent hand-sent attempts (the SMS app was opened), for the screens (newest last, capped);
 * - [pendingReports]: attempts the server has not recorded yet (status reports, no number, no text).
 * No location is ever stored here. N6: the old automatic-SMS switch and alert memory are no longer read; the next save
 * writes the snapshot without them (the one-time clean-up of what 4D kept).
 */
data class CircleAlertSnapshot(
    val userId: Long,
    val recipients: List<CircleRecipient> = emptyList(),
    val deliveries: List<CircleDelivery> = emptyList(),
    val pendingReports: List<CircleDelivery> = emptyList()
) {
    override fun toString(): String =
        "CircleAlertSnapshot(user=$userId, recipients=${recipients.size}, pending=${pendingReports.size})"
}

interface CircleAlertStore {
    suspend fun load(): CircleAlertSnapshot?
    suspend fun save(snapshot: CircleAlertSnapshot)
    suspend fun clear()
}
