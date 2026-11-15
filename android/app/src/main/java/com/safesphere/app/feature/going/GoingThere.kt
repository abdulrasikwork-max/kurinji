package com.safesphere.app.feature.going

import android.content.Intent
import android.view.LayoutInflater
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.safesphere.app.R
import com.safesphere.app.databinding.SheetGoingThereBinding

/**
 * Phase 13F (docs/PHASE13F_PLAN.md F9, F4): "Going there?", the bridge from reading to going. A BRIDGE ONLY: it imports
 * neither the social nor the safety code (GoingThereTest). The opener passes a place label; the safety tools are reached by navigation with the place name filled in, nothing more.
 *
 * - Let someone know (Heads-up, F4): the person's own share sheet with a note they can edit. No location, no link;
 *   Kurinji sends and keeps nothing.
 * - Walk with check-ins: the Safe Walk screen with the place filled in. Nothing starts by itself.
 */
object GoingThere {

    /** What the sheet is about: a place name the person can read, or null when the post shows no place. */
    data class Target(val placeLabel: String?)

    fun show(fragment: Fragment, target: Target) {
        val context = fragment.requireContext()
        val dialog = BottomSheetDialog(context)
        val sheet = SheetGoingThereBinding.inflate(LayoutInflater.from(context))
        val place = target.placeLabel?.takeIf { it.isNotBlank() }
        sheet.placeLine.text = place?.let { context.getString(R.string.going_place_line, it) }
            ?: context.getString(R.string.going_no_place_line)

        sheet.tellRow.setOnClickListener {
            dialog.dismiss()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, headsUpText(context, place))
            }
            fragment.startActivity(Intent.createChooser(send, context.getString(R.string.going_tell)))
        }
        sheet.walkRow.setOnClickListener {
            dialog.dismiss()
            fragment.findNavController().navigate(R.id.safeWalkFragment, bundleOf(ARG_DESTINATION_LABEL to place))
        }
        dialog.setContentView(sheet.root)
        dialog.show()
    }

    /** The editable note: the place name at most (never a location or a link), and a promise to message back. */
    fun headsUpText(context: android.content.Context, place: String?): String =
        if (place != null) context.getString(R.string.going_heads_up, place) else context.getString(R.string.going_heads_up_no_place)

    // The Safe Walk screen's argument names (nav_graph.xml), by value: the bridge never imports safety code.
    private const val ARG_DESTINATION_LABEL = "destinationLabel"
}
