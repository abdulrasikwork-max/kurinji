package com.safesphere.app.feature.social.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import coil.load
import com.safesphere.app.R
import com.safesphere.app.databinding.ItemPhotoBinding

/**
 * One photo or (Phase 17C) video cell: what to show ([image]: the server's photo or poster address, or before the
 * upload is done the picked photo / the video's poster file on this phone), a status line, and optional actions.
 */
data class PhotoCell(
    val key: String,
    val image: Any?,
    val status: String?,
    val onRemove: (() -> Unit)?,
    /** Phase 16: the photo's own size, so the post page keeps its shape (0 = unknown). */
    val width: Int = 0,
    val height: Int = 0,
    /** Phase 17A: set for an item that could not be added; tapping its status line tries again. */
    val onRetry: (() -> Unit)? = null,
    /** Phase 17C: 0..100 while uploading, -1 while being prepared (no share known yet), null otherwise. */
    val progress: Int? = null,
    /** Phase 17C: "0:42" for a video (the badge), null for a photo. */
    val videoLength: String? = null,
    /** Phase 17C: the post page's video opens the player (on a tap only: nothing ever plays by itself). */
    val onOpen: (() -> Unit)? = null,
    /** Phase 17C: a video (its Remove button says so, also while it is still being prepared). */
    val isVideo: Boolean = false
) {
    /** [image] is a String, Uri or File; its text form identifies it (for DiffUtil). */
    val imageKey: String? get() = image?.toString()
}

/**
 * Phase 13B: photos in the composer (thumbnails with Remove) and in the post view. Loaded by the ONE Coil loader of
 * AppContainer (shared client: the JWT is attached; memory cache only).
 * Phase 16: [large] = the post view: each photo as wide as the screen (a little less when there are more, so the next
 * one peeks in), in the photo's own shape kept between 16:9 and 4:5, swiped one at a time. (13F showed 120 dp
 * thumbnails on the post page; the emulator showed a fixed 4:5 cut the sides off landscape photos.)
 * Phase 17C: a video shows its still with "▶ 0:42"; the composer shows the upload's progress.
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
            holder.binding.progress.layoutParams = holder.binding.progress.layoutParams.apply { this.width = photoWidth }
            (holder.binding.root.layoutParams as ViewGroup.MarginLayoutParams).marginEnd =
                if (itemCount > 1) parent.resources.getDimensionPixelSize(R.dimen.space_xs) else 0
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val cell = getItem(position)
        with(holder.binding) {
            val context = root.context
            val number = (position + 1).toString()
            val total = itemCount.toString()
            image.contentDescription = if (cell.videoLength != null) {
                context.getString(R.string.social_video_description, number, total, cell.videoLength)
            } else {
                context.getString(R.string.social_photo_description, number, total)
            }
            if (large) {
                val ratio = if (cell.width > 0 && cell.height > 0) cell.height.toFloat() / cell.width else 1f
                image.layoutParams = image.layoutParams.apply {
                    height = (width * ratio.coerceIn(LARGE_MIN_RATIO, LARGE_MAX_RATIO)).toInt()
                }
            }
            if (cell.image != null) image.load(cell.image, loader) else image.setImageDrawable(null)
            videoBadge.isVisible = cell.videoLength != null
            videoBadge.text = cell.videoLength.orEmpty()

            val open = cell.onOpen
            image.setOnClickListener(open?.let { o -> android.view.View.OnClickListener { o() } })
            image.isClickable = open != null
            ViewCompat.replaceAccessibilityAction(image, AccessibilityActionCompat.ACTION_CLICK,
                if (open != null) context.getString(R.string.social_video_play) else null, null)

            val share = cell.progress
            progress.isVisible = share != null
            if (share != null) {
                progress.isIndeterminate = share < 0
                if (share >= 0) progress.setProgressCompat(share, true)
            }

            status.text = cell.status
            status.isVisible = cell.status != null
            status.setOnClickListener(cell.onRetry?.let { retry -> android.view.View.OnClickListener { retry() } })
            status.isClickable = cell.onRetry != null // after the listener: setting one makes the view clickable
            removeButton.isVisible = cell.onRemove != null
            removeButton.setText(if (cell.isVideo) R.string.social_remove_video else R.string.social_remove_photo)

            removeButton.setOnClickListener { cell.onRemove?.invoke() }
        }
    }

    /** "Photo 1 of 2" depends on the position and the count: rebind every cell when the count changes (a Remove). */
    override fun onCurrentListChanged(previousList: List<PhotoCell>, currentList: List<PhotoCell>) {
        if (previousList.size != currentList.size) notifyItemRangeChanged(0, currentList.size)
    }

    private companion object {
        const val LARGE_SHARE_WHEN_MANY = 0.88f
        /** Height / width: from 16:9 (landscape) to 4:5 (portrait). */
        const val LARGE_MIN_RATIO = 0.5625f
        const val LARGE_MAX_RATIO = 1.25f
    }

    private object Diff : DiffUtil.ItemCallback<PhotoCell>() {
        override fun areItemsTheSame(old: PhotoCell, new: PhotoCell) = old.key == new.key
        override fun areContentsTheSame(old: PhotoCell, new: PhotoCell) = old.imageKey == new.imageKey &&

            old.status == new.status && old.progress == new.progress && old.videoLength == new.videoLength &&
            (old.onOpen == null) == (new.onOpen == null) && (old.onRetry == null) == (new.onRetry == null)
    }
}
