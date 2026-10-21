package com.safesphere.app.feature.safetycircle.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.core.ui.components.BadgeStatus
import com.safesphere.app.databinding.ItemContactBinding
import com.safesphere.app.feature.safetycircle.domain.SafetyContact

/** One card per trusted person: name, relationship, phone, badges, an on/off switch and a menu. */
class ContactAdapter(
    private val onEdit: (SafetyContact) -> Unit,
    private val onDelete: (SafetyContact) -> Unit,
    private val onEnabledChanged: (SafetyContact, Boolean) -> Unit
) : ListAdapter<SafetyContact, ContactAdapter.ContactViewHolder>(DiffCallback) {

    class ContactViewHolder(val binding: ItemContactBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ContactViewHolder(
        ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val contact = getItem(position)
        val context = holder.itemView.context

        with(holder.binding) {
            initials.text = initialsOf(contact.name)
            contactName.text = contact.name
            contactDetails.text = context.getString(
                R.string.contact_details_format, context.getString(contact.relationship.labelRes), contact.phoneNumber
            )

            primaryBadge.isVisible = contact.isPrimary
            if (contact.isPrimary) primaryBadge.setStatus(BadgeStatus.PRIMARY)
            priorityBadge.setStatus(BadgeStatus.SAMPLE, context.getString(R.string.contact_priority_format, contact.priority))
            disabledBadge.isVisible = !contact.isEnabled
            if (!contact.isEnabled) disabledBadge.setStatus(BadgeStatus.COMING_SOON, context.getString(R.string.badge_disabled))

            // Remove the listener first: setting isChecked in code must not count as a tap.
            enabledSwitch.setOnCheckedChangeListener(null)
            enabledSwitch.isChecked = contact.isEnabled
            enabledSwitch.setOnCheckedChangeListener { _, checked -> onEnabledChanged(contact, checked) }
            enabledSwitch.contentDescription = context.getString(R.string.cd_contact_enabled, contact.name)

            menuButton.contentDescription = context.getString(R.string.cd_contact_menu, contact.name)
            menuButton.setOnClickListener { anchor ->
                PopupMenu(anchor.context, anchor).apply {
                    menuInflater.inflate(R.menu.contact_actions, menu)
                    setOnMenuItemClickListener { item ->
                        when (item.itemId) {
                            R.id.action_edit_contact -> onEdit(contact)
                            R.id.action_delete_contact -> onDelete(contact)
                        }
                        true
                    }
                }.show()
            }

            root.setOnClickListener { onEdit(contact) }
            root.alpha = if (contact.isEnabled) 1f else DISABLED_ALPHA
        }
    }

    private fun initialsOf(name: String): String =
        name.trim().split(" ").filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }

    private object DiffCallback : DiffUtil.ItemCallback<SafetyContact>() {
        override fun areItemsTheSame(old: SafetyContact, new: SafetyContact) = old.id == new.id
        override fun areContentsTheSame(old: SafetyContact, new: SafetyContact) = old == new
    }

    private companion object {
        const val DISABLED_ALPHA = 0.8f
    }
}
