package com.safesphere.app.feature.social.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemPhotoBinding

/** One photo cell: the server photo to show (null while it uploads), a status line, and an optional remove action. */
data class PhotoCell(val key: String, val url: String?, val status: String?, val onRemove: (() -> Unit)?)

/**
 * Phase 13B: photos in the composer (thumbnails with Remove) and in the post view. Loaded by the ONE Coil loader of
 * AppContainer (shared client: the JWT is attached; memory cache only).
 */
class PhotoAdapter(private val loader: ImageLoader) : ListAdapter<PhotoCell, PhotoAdapter.Holder>(Diff) {

    class Holder(val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val cell = getItem(position)
        with(holder.binding) {
            val context = root.context
            image.contentDescription = context.getString(R.string.social_photo_description,
                (position + 1).toString(), itemCount.toString())
            if (cell.url != null) image.load(cell.url, loader) else image.setImageDrawable(null)
            status.text = cell.status
            status.isVisible = cell.status != null
            removeButton.isVisible = cell.onRemove != null
            removeButton.setOnClickListener { cell.onRemove?.invoke() }
        }
    }

    private object Diff : DiffUtil.ItemCallback<PhotoCell>() {
        override fun areItemsTheSame(old: PhotoCell, new: PhotoCell) = old.key == new.key
        override fun areContentsTheSame(old: PhotoCell, new: PhotoCell) = old.url == new.url && old.status == new.status
    }
}
