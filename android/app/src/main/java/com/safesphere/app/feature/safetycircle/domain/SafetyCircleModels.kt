package com.safesphere.app.feature.safetycircle.domain

import androidx.annotation.StringRes
import com.safesphere.app.R

/** How a trusted person is related to the user. Matches the backend enum names exactly. */
enum class Relationship(@StringRes val labelRes: Int) {
    FAMILY(R.string.relationship_family),
    PARTNER(R.string.relationship_partner),
    FRIEND(R.string.relationship_friend),
    COLLEAGUE(R.string.relationship_colleague),
    NEIGHBOR(R.string.relationship_neighbor),
    OTHER(R.string.relationship_other);

    companion object {
        /** Unknown values from a newer backend become OTHER instead of crashing the app. */
        fun fromApi(value: String): Relationship =
            entries.firstOrNull { it.name == value } ?: OTHER
    }
}

/** One trusted person in the user's Safety Circle. */
data class SafetyContact(
    val id: Long,
    val name: String,
    val phoneNumber: String,
    val email: String?,
    val relationship: Relationship,
    val priority: Int,
    val isPrimary: Boolean,
    val isEnabled: Boolean
) {
    /** The editable fields, used to send an update that changes only one thing (e.g. enabled). */
    fun toInput() = ContactInput(name, phoneNumber, email, relationship, priority, isPrimary, isEnabled)
}

/** What the user can edit. There is no user id here: the server knows who is calling from the token. */
data class ContactInput(
    val name: String,
    val phoneNumber: String,
    val email: String?,
    val relationship: Relationship,
    val priority: Int,
    val isPrimary: Boolean,
    val isEnabled: Boolean
)
