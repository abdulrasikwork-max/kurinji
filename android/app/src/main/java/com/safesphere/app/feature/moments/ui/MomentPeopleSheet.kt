package com.safesphere.app.feature.moments.ui

import android.view.LayoutInflater
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemMomentSectionBinding
import com.safesphere.app.databinding.ItemPersonRowBinding
import com.safesphere.app.databinding.SheetMomentPeopleBinding
import com.safesphere.app.feature.moments.domain.PlanPeople
import com.safesphere.app.feature.moments.domain.PlanPerson
import com.safesphere.app.feature.social.ui.CommunityLine

/** What the author can do with one person in the sheet (D16: no decline exists). */
enum class PersonAction { ADD, SET_ASIDE, BRING_BACK, REMOVE }

data class PeopleSection(@StringRes val title: Int, val rows: List<PeopleRow>)

data class PeopleRow(val person: PlanPerson, val actions: List<PersonAction>)

/** Pure, unit tested: the sheet's sections in the order that matters, empty ones left out. */
object PeopleSections {

    /** [author] gets every list; anyone else (someone in the plan) only who is in. [open] = Add is still possible. */
    fun of(people: PlanPeople, author: Boolean, open: Boolean): List<PeopleSection> {
        val out = ArrayList<PeopleSection>()
        fun add(@StringRes title: Int, list: List<PlanPerson>, actions: (PlanPerson) -> List<PersonAction>) {
            if (list.isNotEmpty()) out += PeopleSection(title, list.map { PeopleRow(it, if (it.ref == null) emptyList() else actions(it)) })
        }
        if (!author) {
            add(R.string.response_people_in, people.inPlan) { emptyList() }
            return out
        }
        add(R.string.response_people_asking, people.asking) {
            if (open) listOf(PersonAction.SET_ASIDE, PersonAction.ADD) else listOf(PersonAction.SET_ASIDE)
        }
        add(R.string.response_people_in, people.inPlan) { listOf(PersonAction.REMOVE) }
        add(R.string.response_people_set_aside, people.setAside) { listOf(PersonAction.BRING_BACK) }
        add(R.string.response_people_say_more, people.sayMore) { emptyList() }
        add(R.string.response_people_same, people.same) { emptyList() }
        return out
    }

    @StringRes
    fun label(action: PersonAction): Int = when (action) {
        PersonAction.ADD -> R.string.response_people_add
        PersonAction.SET_ASIDE -> R.string.response_people_not_now
        PersonAction.BRING_BACK -> R.string.response_people_bring_back
        PersonAction.REMOVE -> R.string.response_people_remove
    }
}

/**
 * The people bottom sheet of a moment's page. It only shows what the view model holds and hands each tap back; it is
 * redrawn on every state. Opened with the fragment's (themed) context.
 */
class MomentPeopleSheet(
    private val fragment: Fragment,
    private val images: MomentImages,
    private val onAction: (PersonAction, PlanPerson) -> Unit,
    private val onClosed: () -> Unit
) {
    private var dialog: BottomSheetDialog? = null
    private var sheet: SheetMomentPeopleBinding? = null

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        if (isShowing) return
        val context = fragment.requireContext()
        val binding = SheetMomentPeopleBinding.inflate(LayoutInflater.from(context))
        val d = BottomSheetDialog(context)
        d.setContentView(binding.root)
        binding.doneButton.setOnClickListener { d.dismiss() }
        d.setOnDismissListener {
            dialog = null
            sheet = null
            onClosed()
        }
        dialog = d
        sheet = binding
        d.show()
    }

    fun dismiss() = dialog?.dismiss()

    fun render(people: PlanPeople?, loading: Boolean, busy: Boolean, author: Boolean, open: Boolean) {
        val binding = sheet ?: return
        val context = binding.root.context
        binding.hint.setText(if (author) R.string.response_people_hint else R.string.response_people_hint_member)
        binding.progress.isVisible = loading || busy
        val sections = people?.let { PeopleSections.of(it, author, open) }.orEmpty()
        binding.empty.isVisible = people != null && sections.isEmpty()
        binding.lists.removeAllViews()
        val inflater = LayoutInflater.from(context)
        for (section in sections) {
            val title = ItemMomentSectionBinding.inflate(inflater, binding.lists, false)
            title.sectionTitle.setText(section.title)
            binding.lists.addView(title.root)
            for (row in section.rows) {
                val item = ItemPersonRowBinding.inflate(inflater, binding.lists, false)
                val person = row.person
                val photo = if (person.profileId != null && person.photo != null)
                    images.personPhotoUrl(person.profileId, person.photo) else null
                item.avatar.isVisible = true
                item.avatar.set(person.name, person.name, photo, images.loader)
                item.title.text = person.name
                val line = listOfNotNull(CommunityLine.of(person.programme, person.year),
                    if (person.myPeople) context.getString(R.string.moment_audience_my_people) else null)
                item.subtitle.isVisible = line.isNotEmpty()
                item.subtitle.text = line.joinToString(" · ")
                val buttons = listOf(item.secondaryButton, item.primaryButton)
                // The main action sits on the right (primary), a second one beside it.
                val actions = if (row.actions.size == 1) listOf(null, row.actions[0]) else row.actions.map { it }
                buttons.forEachIndexed { i, button ->
                    val action = actions.getOrNull(i)
                    button.isVisible = action != null
                    button.isEnabled = !busy
                    if (action != null) {
                        button.setText(PeopleSections.label(action))
                        button.contentDescription = context.getString(PeopleSections.label(action)) + ", " + person.name
                        button.setOnClickListener { act(action, person) }
                    }
                }
                item.buttons.isVisible = row.actions.isNotEmpty()
                binding.lists.addView(item.root)
            }
        }
    }

    /** Remove is said once, plainly, before it happens (they cannot ask again). */
    private fun act(action: PersonAction, person: PlanPerson) {
        if (action != PersonAction.REMOVE) return onAction(action, person)
        val context = fragment.requireContext()
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.response_people_remove_title, person.name))
            .setMessage(R.string.response_people_remove_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.response_people_remove) { _, _ -> onAction(action, person) }
            .show()
    }
}
