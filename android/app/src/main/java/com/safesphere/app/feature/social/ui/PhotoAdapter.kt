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
data class PhotoCell(
    val key: String,
    val url: String?,
    val status: String?,
    val onRemove: (() -> Unit)?,
    /** Phase 16: the photo's own size, so the post page keeps its shape (0 = unknown). */
    val width: Int = 0,
    val height: Int = 0
)

/**
 * Phase 13B: photos in the composer (thumbnails with Remove) and in the post view. Loaded by the ONE Coil loader of
 * AppContainer (shared client: the JWT is attached; memory cache only).
 * Phase 16: [large] = the post view: each photo as wide as the screen (a little less when there are more, so the next
 * one peeks in), in the photo's own shape kept between 16:9 and 4:5, swiped one at a time. (13F showed 120 dp
 * thumbnails on the post page; the emulator showed a fixed 4:5 cut the sides off landscape photos.)
 */
class PhotoAdapter(private val loader: ImageLoader, private val large: Boolean = false) :
    ListAdapter<PhotoCell, PhotoAdapter.Holder>(Diff) {

    class Holder(val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val holder = Holder(ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        if (large) {
            val width = parent.width.takeIf { it > 0 } ?: parent.resources.displayMetrics.widthPixels
            val photoWidth = if (itemCount > 1) (width * LARGE_SHARE_WHEN_MANY).toInt() else width
            holder.binding.image.layoutParams = holder.binding.image.layoutParams.apply { this.width = photoWidth }
            (holder.binding.root.layoutParams as ViewGroup.MarginLayoutParams).marginEnd =
                if (itemCount > 1) parent.resources.getDimensionPixelSize(R.dimen.space_xs) else 0
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val cell = getItem(position)
        with(holder.binding) {
            val context = root.context
            image.contentDescription = context.getString(R.string.social_photo_description,
                (position + 1).toString(), itemCount.toString())
            if (large) {
                val ratio = if (cell.width > 0 && cell.height > 0) cell.height.toFloat() / cell.width else 1f
                image.layoutParams = image.layoutParams.apply {
                    height = (width * ratio.coerceIn(LARGE_MIN_RATIO, LARGE_MAX_RATIO)).toInt()
                }
            }
            if (cell.url != null) image.load(cell.url, loader) else image.setImageDrawable(null)
            status.text = cell.status
            status.isVisible = cell.status != null
            removeButton.isVisible = cell.onRemove != null
            removeButton.setOnClickListener { cell.onRemove?.invoke() }
        }
    }

    private companion object {
        const val LARGE_SHARE_WHEN_MANY = 0.88f
        /** Height / width: from 16:9 (landscape) to 4:5 (portrait). */
        const val LARGE_MIN_RATIO = 0.5625f
        const val LARGE_MAX_RATIO = 1.25f
    }

    private object Diff : DiffUtil.ItemCallback<PhotoCell>() {
        override fun areItemsTheSame(old: PhotoCell, new: PhotoCell) = old.key == new.key
        override fun areContentsTheSame(old: PhotoCell, new: PhotoCell) = old.url == new.url && old.status == new.status
    }
}
