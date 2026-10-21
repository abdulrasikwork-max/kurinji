package com.safesphere.app.feature.emergency.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.core.ui.components.BadgeStatus
import com.safesphere.app.databinding.ItemEmergencyEventBinding
import com.safesphere.app.feature.emergency.domain.EmergencyEvent
import com.safesphere.app.feature.emergency.domain.EmergencyStatus

/** Maps each status to a badge: words and an icon, not only colour. */
private fun EmergencyStatus.badge(): BadgeStatus = when (this) {
    EmergencyStatus.ACTIVE -> BadgeStatus.CONCERN
    EmergencyStatus.RESOLVED -> BadgeStatus.SAFE
    EmergencyStatus.CANCELLED -> BadgeStatus.SAMPLE
}

/** [onClick] (Phase 4D) opens the emergency's details (its Emergency Context Packet). */
class EmergencyEventAdapter(
    private val events: List<EmergencyEvent>,
    private val onClick: ((EmergencyEvent) -> Unit)? = null
) : RecyclerView.Adapter<EmergencyEventAdapter.EventViewHolder>() {

    class EventViewHolder(val binding: ItemEmergencyEventBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = events.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EventViewHolder(
        ItemEmergencyEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val event = events[position]
        val context = holder.itemView.context
        val trigger = context.getString(event.trigger.labelRes)
        val started = EmergencyTimeFormatter.format(event.startedAt)
        val status = context.getString(event.status.labelRes)

        with(holder.binding) {
            eventTrigger.text = trigger
            eventStarted.text = context.getString(R.string.emergency_started_at, started)
            eventStatus.setStatus(event.status.badge(), status)

            val ended = event.endedAt
            eventEnded.isVisible = ended != null
            if (ended != null) {
                eventEnded.text = context.getString(R.string.emergency_ended_at, EmergencyTimeFormatter.format(ended))
            }
            root.contentDescription = context.getString(R.string.cd_emergency_event, trigger, started, status)
            root.isClickable = onClick != null
            root.setOnClickListener(onClick?.let { click -> { click(event) } })
        }
    }
}
