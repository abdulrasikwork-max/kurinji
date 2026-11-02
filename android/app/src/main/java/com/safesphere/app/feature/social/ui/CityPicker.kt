package com.safesphere.app.feature.social.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemCityRowBinding
import com.safesphere.app.databinding.SheetCityPickerBinding
import com.safesphere.app.feature.social.domain.Cities
import com.safesphere.app.feature.social.domain.City

/**
 * Phase 17E (D-5): one city picker for Discover, the composer ("which city?") and the profile. The catalog is bundled
 * and searched on the phone (names, other names like "Madras", states), so typing a city tells nobody anything. With
 * [allowOther] the list ends with "Another city" (a CITY post may name one; a list or a profile cannot).
 */
object CityPicker {

    fun show(fragment: Fragment, current: City?, allowOther: Boolean, title: Int = R.string.city_picker_title,
             onPick: (City) -> Unit) {
        val context = fragment.requireContext()
        val dialog = BottomSheetDialog(context)
        val binding = SheetCityPickerBinding.inflate(LayoutInflater.from(context))
        binding.title.setText(title)
        val adapter = Adapter(current) { city ->
            dialog.dismiss()
            onPick(city)
        }
        binding.list.adapter = adapter
        fun refresh(query: String) {
            val found = Cities.search(query, limit = 100)
            adapter.submitList(if (allowOther) found + Cities.other else found)
            binding.empty.isVisible = found.isEmpty()
        }
        binding.query.doAfterTextChanged { refresh(it?.toString().orEmpty()) }
        refresh("")
        dialog.setContentView(binding.root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    private class Adapter(private val current: City?, private val onPick: (City) -> Unit) :
        ListAdapter<City, Adapter.Holder>(Diff) {

        class Holder(val binding: ItemCityRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCityRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val city = getItem(position)
            val b = holder.binding
            val context = b.root.context
            val name = if (city.isOther) context.getString(R.string.city_other) else city.name
            b.name.text = name
            b.state.text = city.state ?: context.getString(R.string.city_other_note)
            val chosen = city.code == current?.code
            b.chosen.isVisible = chosen
            b.root.contentDescription = listOfNotNull(name, city.state,
                if (chosen) context.getString(R.string.city_picker_chosen) else null).joinToString(", ")
            b.root.setOnClickListener { onPick(city) }
        }

        private object Diff : DiffUtil.ItemCallback<City>() {
            override fun areItemsTheSame(old: City, new: City) = old.code == new.code
            override fun areContentsTheSame(old: City, new: City) = old == new
        }
    }
}
