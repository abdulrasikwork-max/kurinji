package com.safesphere.app.feature.social.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.safesphere.app.R
import com.safesphere.app.databinding.SheetWentBinding
import com.safesphere.app.feature.social.domain.Visit

/**
 * Phase 17G-2 (U5): "How was it?" after "I went". An optional 1 to 5 as five large numbered buttons (each with the spoken label
 * "Rate 3 out of 5"; the chosen one is a lime fill AND says "your rating": never colour alone), then the choice to share the person's
 * own experience (the composer), take a rating or the visit back, or just close. Shows only the person's OWN visit; nothing here
 * knows anyone else's. The screen calls [update] whenever its state changes so the open sheet always shows what the server said.
 */
class WentSheet(
    private val fragment: Fragment,
    private val onRate: (Int) -> Unit,
    private val onClearRating: () -> Unit,
    private val onNotWent: () -> Unit,
    private val onShare: () -> Unit
) {
    private var dialog: BottomSheetDialog? = null
    private var binding: SheetWentBinding? = null
    private val buttons = mutableListOf<MaterialButton>()

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show(visit: Visit?) {
        if (isShowing) {
            update(visit)
            return
        }
        val context = fragment.requireContext()
        val sheet = SheetWentBinding.inflate(LayoutInflater.from(context))
        ViewCompat.setAccessibilityHeading(sheet.wentTitle, true)
        buttons.clear()
        for (value in Visit.MIN..Visit.MAX) {
            val button = LayoutInflater.from(context).inflate(R.layout.item_rating_choice, sheet.ratingRow, false) as MaterialButton
            button.text = context.getString(R.string.went_rate_number, value.toString())
            button.setOnClickListener { onRate(value) }
            sheet.ratingRow.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (value > Visit.MIN) marginStart = context.resources.getDimensionPixelSize(R.dimen.space_xs)
            })
            buttons += button
        }
        sheet.clearRatingButton.setOnClickListener { onClearRating() }
        sheet.shareButton.setOnClickListener {
            dismiss()
            onShare()
        }
        sheet.notWentButton.setOnClickListener {
            dismiss()
            onNotWent()
        }
        sheet.doneButton.setOnClickListener { dismiss() }
        val created = BottomSheetDialog(context)
        created.setContentView(sheet.root)
        created.setOnDismissListener {
            dialog = null
            binding = null
        }
        dialog = created
        binding = sheet
        update(visit)
        created.show()
    }

    /** Shows the person's own visit: which number is theirs, and whether there is a rating to take back. */
    fun update(visit: Visit?) {
        val sheet = binding ?: return
        val context = fragment.context ?: return
        val rating = visit?.rating
        buttons.forEachIndexed { index, button ->
            val value = index + Visit.MIN
            val chosen = value == rating
            button.contentDescription = context.getString(if (chosen) R.string.went_rate_chosen else R.string.went_rate_description, value.toString())
            if (chosen) {
                button.backgroundTintList = ColorStateList.valueOf(MaterialColors.getColor(button, R.attr.colorLime))
                button.setTextColor(MaterialColors.getColor(button, R.attr.colorOnLime))
            } else {
                button.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
                button.setTextColor(MaterialColors.getColor(button, com.google.android.material.R.attr.colorOnSurface))
            }
        }
        sheet.ratingStatus.text = if (rating == null) context.getString(R.string.went_status_none)
        else context.getString(R.string.went_status_rated, rating.toString())
        sheet.clearRatingButton.visibility = if (rating == null) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** While a call runs the sheet cannot be double-tapped. */
    fun setBusy(busy: Boolean) {
        val sheet = binding ?: return
        buttons.forEach { it.isEnabled = !busy }
        sheet.clearRatingButton.isEnabled = !busy
        sheet.shareButton.isEnabled = !busy
        sheet.notWentButton.isEnabled = !busy
    }

    fun dismiss() {
        dialog?.dismiss()
    }
}
