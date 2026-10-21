package com.safesphere.app.feature.emergency.domain

import androidx.annotation.StringRes
import com.safesphere.app.R
import java.time.Instant

/** Matches the backend. ACTIVE can end as CANCELLED ("false alarm") or RESOLVED ("I'm safe"). */
enum class EmergencyStatus(@StringRes val labelRes: Int) {
    ACTIVE(R.string.emergency_status_active),
    CANCELLED(R.string.emergency_status_cancelled),
    RESOLVED(R.string.emergency_status_resolved);

    companion object {
        fun fromApi(value: String): EmergencyStatus = entries.firstOrNull { it.name == value } ?: ACTIVE
    }
}

/** What started the emergency. Only MANUAL can be created in Phase 3; the rest are reserved for later phases. */
enum class EmergencyTrigger(@StringRes val labelRes: Int) {
    MANUAL(R.string.trigger_manual),
    SHAKE(R.string.trigger_shake),
    VOICE(R.string.trigger_voice),
    DURESS_PIN(R.string.trigger_duress_pin);

    companion object {
        fun fromApi(value: String): EmergencyTrigger = entries.firstOrNull { it.name == value } ?: MANUAL
    }
}

data class EmergencyEvent(
    val id: Long,
    val status: EmergencyStatus,
    val trigger: EmergencyTrigger,
    val startedAt: Instant,
    val endedAt: Instant?,
    /** Phase 10A.4: ended from another sign-in than the one that started it (that needed the account password). */
    val endedElsewhere: Boolean = false
)
