package com.safesphere.app.feature.onboarding.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.core.ui.components.ButterflyDrawable
import com.safesphere.app.databinding.ItemOnboardingPageBinding

class OnboardingPagerAdapter(
    private val pages: List<OnboardingPage>
) : RecyclerView.Adapter<OnboardingPagerAdapter.PageViewHolder>() {

    class PageViewHolder(val binding: ItemOnboardingPageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount() = pages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = PageViewHolder(
        ItemOnboardingPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        with(holder.binding) {
            if (page.butterfly) {
                pageIcon.setImageDrawable(ButterflyDrawable.reacted(pageIcon.context, 72))
            } else {
                pageIcon.setImageResource(page.iconRes)
            }
            pageIcon.imageTintList = if (page.tinted && !page.butterfly) android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(pageIcon, androidx.appcompat.R.attr.colorPrimary)
            ) else null
            pageTitle.setText(page.titleRes)
            pageDescription.setText(page.descriptionRes)
        }
    }
}
