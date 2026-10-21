package com.safesphere.app.feature.evidence.ui

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemEvidenceBinding
import com.safesphere.app.feature.emergency.ui.EmergencyTimeFormatter
import com.safesphere.app.feature.evidence.domain.EvidenceKind

/** Rows of the Evidence Vault. Taps go back to the screen; the adapter holds no state of its own. */
class EvidenceItemAdapter(
    private val onOpen: (VaultRow) -> Unit,
    private val onMore: (VaultRow, android.view.View) -> Unit
) : ListAdapter<VaultRow, EvidenceItemAdapter.Holder>(DIFF) {

    var busyRef: String? = null
        set(value) {
            if (field == value) return
            val previous = field
            field = value
            // Only the rows whose buttons change are redrawn.
            currentList.forEachIndexed { index, row -> if (row.clientRef == previous || row.clientRef == value) notifyItemChanged(index) }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemEvidenceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemEvidenceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: VaultRow) {
            val context = binding.root.context
            binding.evidenceTitle.text = context.getString(
                R.string.evidence_row_title, context.getString(kindLabel(row.kind)), EmergencyTimeFormatter.format(row.capturedStartedAt)
            )
            val details = buildList {
                if (row.kind == EvidenceKind.AUDIO || row.kind == EvidenceKind.VIDEO) add(formatElapsed(row.duration.seconds.coerceAtLeast(0)))
                add(Formatter.formatShortFileSize(context, row.sizeBytes))
                if (row.escrowed) add(context.getString(R.string.evidence_row_recoverable))
            }
            binding.evidenceDetails.text = details.joinToString(" · ")
            binding.evidenceStatus.text = when (row.status) {
                RowStatus.UPLOADING -> context.getString(R.string.evidence_status_uploading, row.uploadPercent)
                else -> context.getString(statusLabel(row.status))
            }
            val busy = busyRef == row.clientRef
            binding.evidenceOpen.isVisible = row.canOpen
            binding.evidenceOpen.isEnabled = !busy
            binding.evidenceOpen.setOnClickListener { onOpen(row) }
            binding.evidenceMore.isVisible = row.canVerify || row.canRemoveFromPhone || row.canDownload || row.canDelete || row.canRetryUpload
            binding.evidenceMore.isEnabled = !busy
            binding.evidenceMore.setOnClickListener { onMore(row, it) }
            binding.evidenceOpen.contentDescription = context.getString(R.string.cd_open_evidence, binding.evidenceTitle.text)
        }
    }

    companion object {
        fun kindLabel(kind: EvidenceKind?): Int = when (kind) {
            EvidenceKind.AUDIO -> R.string.evidence_kind_audio
            EvidenceKind.PHOTO -> R.string.evidence_kind_photo
            EvidenceKind.VIDEO -> R.string.evidence_kind_video
            null -> R.string.evidence_kind_unknown
        }

        fun statusLabel(status: RowStatus): Int = when (status) {
            RowStatus.ON_PHONE -> R.string.evidence_status_on_phone
            RowStatus.UPLOADING -> R.string.evidence_status_on_phone
            RowStatus.VERIFIED_IN_VAULT -> R.string.evidence_status_verified
            RowStatus.ONLY_IN_VAULT -> R.string.evidence_status_only_in_vault
            RowStatus.UPLOAD_REFUSED -> R.string.evidence_status_refused
            RowStatus.DELETED -> R.string.evidence_status_deleted
            RowStatus.OTHER_PHONE -> R.string.evidence_status_other_phone
            RowStatus.DAMAGED_IN_VAULT -> R.string.evidence_status_damaged_in_vault
        }

        private val DIFF = object : DiffUtil.ItemCallback<VaultRow>() {
            override fun areItemsTheSame(oldItem: VaultRow, newItem: VaultRow) = oldItem.clientRef == newItem.clientRef
            override fun areContentsTheSame(oldItem: VaultRow, newItem: VaultRow) = oldItem == newItem
        }
    }
}
