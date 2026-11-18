package com.safesphere.app.feature.going

import android.content.Intent
import android.view.LayoutInflater
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.safesphere.app.R
import com.safesphere.app.databinding.SheetGoingThereBinding
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Phase 13F (F9, F4) and N6 (D24, N-108): "Let someone know · On my way", the bridge from a plan to going. A BRIDGE
 * ONLY: it imports neither the social nor the safety code (GoingThereTest). The opener passes the plan's "where" and
 * time; the safety tool is reached by navigation with the "where" filled in, nothing more.
 *
 * - Let someone know: the person's own share sheet with a note they can edit: the "where" and the time at most. No
 *   location, no link, never the plan's words or anyone's name; IRL sends and keeps nothing.
 * - On my way: the reminder screen with the "where" filled in (editable). Nothing starts by itself.
 */
object GoingThere {

    /** What the sheet is about: the place as the plan says it, and the plan's time; either may be missing. */
    data class Target(val placeLabel: String?, val at: Instant? = null)

    fun show(fragment: Fragment, target: Target) {
        val context = fragment.requireContext()
        val dialog = BottomSheetDialog(context)
        val sheet = SheetGoingThereBinding.inflate(LayoutInflater.from(context))
        val place = target.placeLabel?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_PLACE)
        sheet.placeLine.text = place?.let { context.getString(R.string.going_place_line, it) }
            ?: context.getString(R.string.going_no_place_line)

        sheet.tellRow.setOnClickListener {
            dialog.dismiss()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, headsUpText(context, place, target.at?.let(::clock)))
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

    /** The editable note: the place and the time at most (never a location or a link), and a promise to message back. */
    fun headsUpText(context: android.content.Context, place: String?, time: String? = null): String = when {
        place != null && time != null -> context.getString(R.string.going_heads_up_at, place, time)
        place != null -> context.getString(R.string.going_heads_up, place)
        time != null -> context.getString(R.string.going_heads_up_no_place_at, time)
        else -> context.getString(R.string.going_heads_up_no_place)
    }

    private fun clock(at: Instant): String =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(at)

    /** The "where" of a plan is short; the On my way label holds at most 100 characters. */
    private const val MAX_PLACE = 100

    // The On my way screen's argument name (nav_graph.xml), by value: the bridge never imports safety code.
    private const val ARG_DESTINATION_LABEL = "destinationLabel"
}
