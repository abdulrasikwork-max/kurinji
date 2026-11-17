package com.safesphere.app.feature.moments.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemMomentCardBinding
import com.safesphere.app.databinding.ItemMomentNoteBinding
import com.safesphere.app.databinding.ItemMomentSectionBinding
import com.safesphere.app.feature.moments.domain.Moment

/** One row of a person's moments (You, someone's profile). */
sealed interface MomentListRow {
    data class Section(@StringRes val title: Int) : MomentListRow
    data class Note(@StringRes val text: Int) : MomentListRow
    /** [open] = a covered moment the reader chose to read. */
    data class Card(val moment: Moment, val open: Boolean) : MomentListRow
}

/**
 * Builds the two short lists of a profile: what is on Today now, and what the person keeps. Pure, unit tested. No
 * count is shown anywhere; a section without moments says so in one line (own profile) or is left out (someone else's:
 * an empty section would only say what the viewer cannot see).
 */
object ProfileMomentRows {

    /** The person's own You tab. */
    fun own(current: List<Moment>, kept: List<Moment>, opened: Set<String> = emptySet()): List<MomentListRow> {
        val rows = ArrayList<MomentListRow>()
        rows += MomentListRow.Section(R.string.you_moments_now)
        if (current.isEmpty()) rows += MomentListRow.Note(R.string.you_moments_now_empty)
        current.forEach { rows += MomentListRow.Card(it, it.id in opened) }
        rows += MomentListRow.Section(R.string.you_moments_kept)
        if (kept.isEmpty()) rows += MomentListRow.Note(R.string.you_moments_kept_empty)
        // A kept moment that is still live is already in the first list.
        val shown = current.mapTo(HashSet()) { it.id }
        kept.filterNot { it.id in shown }.forEach { rows += MomentListRow.Card(it, it.id in opened) }
        if (kept.isNotEmpty() && kept.all { it.id in shown }) rows += MomentListRow.Note(R.string.you_moments_kept_above)
        return rows
    }

    /** Someone else's profile: only what the viewer may see. */
    fun of(live: List<Moment>, kept: List<Moment>, opened: Set<String>): List<MomentListRow> {
        val rows = ArrayList<MomentListRow>()
        if (live.isNotEmpty()) {
            rows += MomentListRow.Section(R.string.profile_moments_now)
            live.forEach { rows += MomentListRow.Card(it, it.id in opened) }
        }
        val shown = live.mapTo(HashSet()) { it.id }
        val rest = kept.filterNot { it.id in shown }
        if (rest.isNotEmpty()) {
            rows += MomentListRow.Section(R.string.profile_moments_kept)
            rest.forEach { rows += MomentListRow.Card(it, it.id in opened) }
        }
        if (rows.isEmpty()) rows += MomentListRow.Note(R.string.profile_moments_empty)
        return rows
    }
}

/** Phase N4: a person's moments under their profile. Tapping a card opens the moment; a covered one uncovers first. */
class MomentListAdapter(
    private val images: MomentImages,
    private val onOpen: (Moment) -> Unit,
    private val onUncover: (Moment) -> Unit,
    /** The list already sits inside a padded page: the cards lose their own side margins. */
    private val flush: Boolean = true
) : ListAdapter<MomentListRow, RecyclerView.ViewHolder>(Diff) {

    private class CardHolder(val binding: ItemMomentCardBinding) : RecyclerView.ViewHolder(binding.root)
    private class SectionHolder(val binding: ItemMomentSectionBinding) : RecyclerView.ViewHolder(binding.root)
    private class NoteHolder(val binding: ItemMomentNoteBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is MomentListRow.Card -> CARD
        is MomentListRow.Section -> SECTION
        is MomentListRow.Note -> NOTE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            CARD -> CardHolder(ItemMomentCardBinding.inflate(inflater, parent, false).also { card ->
                if (flush) (card.root.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    it.marginStart = 0
                    it.marginEnd = 0
                }
            })
            SECTION -> SectionHolder(ItemMomentSectionBinding.inflate(inflater, parent, false))
            else -> NoteHolder(ItemMomentNoteBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is MomentListRow.Card -> {
                val b = (holder as CardHolder).binding
                MomentCardBinder.bind(b, row.moment, row.open, images)
                b.content.setOnClickListener {
                    if (MomentCardBinder.covered(row.moment, row.open)) onUncover(row.moment) else onOpen(row.moment)
                }
            }
            is MomentListRow.Section -> (holder as SectionHolder).binding.sectionTitle.setText(row.title)
            is MomentListRow.Note -> (holder as NoteHolder).binding.noteText.setText(row.text)
        }
    }

    private object Diff : DiffUtil.ItemCallback<MomentListRow>() {
        override fun areItemsTheSame(old: MomentListRow, new: MomentListRow) = when {
            old is MomentListRow.Card && new is MomentListRow.Card -> old.moment.id == new.moment.id
            else -> old == new
        }

        override fun areContentsTheSame(old: MomentListRow, new: MomentListRow) = old == new
    }

    private companion object {
        const val CARD = 0
        const val SECTION = 1
        const val NOTE = 2
    }
}
