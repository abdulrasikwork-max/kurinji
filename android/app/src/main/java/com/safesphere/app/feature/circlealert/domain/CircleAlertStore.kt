package com.safesphere.app.feature.circlealert.domain

/**
 * Everything the circle alerts keep on the phone, for ONE signed-in user (cleared on logout):
 * - [autoSms]: the person's opt-in for automatic SMS (off by default);
 * - [recipients]: enabled Safety Circle contacts (name + number), so an SMS can go out without internet;
 * - [memory]: which alerts were already sent (no duplicates after a restart);
 * - [deliveries]: recent attempts, for the screens (newest last, capped);
 * - [pendingReports]: attempts the server has not confirmed yet (status reports, no number, no text).
 * No location is ever stored here.
 */
data class CircleAlertSnapshot(
    val userId: Long,
    val autoSms: Boolean = false,
    val recipients: List<CircleRecipient> = emptyList(),
    val memory: AlertMemory = AlertMemory(),
    val deliveries: List<CircleDelivery> = emptyList(),
    val pendingReports: List<CircleDelivery> = emptyList()
) {
    override fun toString(): String =
        "CircleAlertSnapshot(user=$userId, auto=$autoSms, recipients=${recipients.size}, pending=${pendingReports.size})"
}

interface CircleAlertStore {
    suspend fun load(): CircleAlertSnapshot?
    suspend fun save(snapshot: CircleAlertSnapshot)
    suspend fun clear()
}
