package com.safesphere.app.feature.home.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.databinding.ItemTimelineStepBinding

/** Vertical timeline: the connector line is hidden above the first row and below the last. */
class TimelineAdapter(
    private val steps: List<TimelineStep>
) : RecyclerView.Adapter<TimelineAdapter.StepViewHolder>() {

    class StepViewHolder(val binding: ItemTimelineStepBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = steps.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = StepViewHolder(
        ItemTimelineStepBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: StepViewHolder, position: Int) {
        val step = steps[position]
        with(holder.binding) {
            stepIcon.setImageResource(step.iconRes)
            stepTitle.setText(step.titleRes)
            stepDescription.setText(step.descriptionRes)
            stepBadge.setStatus(step.status)
            lineTop.visibility = if (position == 0) View.INVISIBLE else View.VISIBLE
            lineBottom.visibility = if (position == steps.lastIndex) View.INVISIBLE else View.VISIBLE
        }
    }
}
