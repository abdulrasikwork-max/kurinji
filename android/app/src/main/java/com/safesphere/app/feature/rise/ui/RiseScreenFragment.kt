package com.safesphere.app.feature.rise.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.viewbinding.ViewBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.ItemRiseCardBinding
import com.safesphere.app.feature.rise.domain.RiseJournal
import com.safesphere.app.feature.rise.domain.RiseRefusal

/**
 * What every Rise screen shares:
 * - FLAG_SECURE while it is shown (no screenshots, no recents preview of the person's words);
 * - it counts as "inside Rise" for [com.safesphere.app.feature.rise.session.RiseLock];
 * - secondary screens show content only when Rise is open ([RiseScreen.Ready]); locked, signed out or unreadable ->
 *   back to the Rise tab, which shows the lock, the intro or the problem.
 */
abstract class RiseScreenFragment<VB : ViewBinding>(
    inflate: (LayoutInflater, ViewGroup?, Boolean) -> VB
) : ViewBindingFragment<VB>(inflate) {

    protected val viewModel by viewModels<RiseViewModel> { RiseViewModel.factory(appContainer) }

    override fun onStart() {
        super.onStart()
        viewModel.onRiseVisible()
    }

    override fun onStop() {
        viewModel.onRiseHidden()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    /** For secondary screens: draws [render] only while Rise is open. */
    protected fun collectReady(render: (RiseJournal) -> Unit) {
        collectWhenStarted(viewModel.screen) { screen ->
            when (screen) {
                is RiseScreen.Ready -> render(screen.journal)
                RiseScreen.Loading -> Unit
                else -> backToRise()
            }
        }
        collectWhenStarted(viewModel.events) { event ->
            if (event is RiseEvent.Refused) toast(messageFor(event.reason))
        }
    }

    protected fun backToRise() {
        if (!findNavController().popBackStack(R.id.riseFragment, false)) findNavController().navigate(R.id.riseFragment)
    }

    protected fun toast(@StringRes text: Int) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    @StringRes
    protected fun messageFor(reason: RiseRefusal): Int = when (reason) {
        RiseRefusal.NO_STEPS -> R.string.rise_editor_no_steps
        RiseRefusal.TOO_MANY_GOALS -> R.string.rise_editor_too_many
        else -> R.string.rise_not_saved
    }

    /** "Tell someone": the phone's SMS app with an editable text and NO number; nothing is recorded or sent by Rise. */
    protected fun tellSomeone(@StringRes text: Int?) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
        if (text != null) intent.putExtra("sms_body", getString(text))
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.rise_sms_unavailable)
        }
    }

    /** Filter chips from a closed list (labels are string resources). */
    protected fun <T> ChipGroup.fill(items: List<T>, label: (T) -> Int, selected: Set<T>, onChange: (T, Boolean) -> Unit) {
        removeAllViews()
        items.forEach { item ->
            val chip = (LayoutInflater.from(context).inflate(R.layout.item_rise_chip, this, false) as Chip).apply {
                setText(label(item))
                isCheckable = true
                isChecked = item in selected
                setOnCheckedChangeListener { _, checked -> onChange(item, checked) }
            }
            addView(chip)
        }
    }

    /** One card row; the whole card is one TalkBack sentence. */
    protected fun LinearLayout.addCard(title: String, subtitle: String? = null, body: String? = null,
                                       action: String? = null, onAction: (() -> Unit)? = null, onClick: (() -> Unit)? = null) {
        val card = ItemRiseCardBinding.inflate(LayoutInflater.from(context), this, false)
        card.cardTitle.text = title
        card.cardSubtitle.isVisible = subtitle != null
        card.cardSubtitle.text = subtitle
        card.cardBody.isVisible = body != null
        card.cardBody.text = body
        card.cardAction.isVisible = action != null
        card.cardAction.text = action
        card.cardAction.setOnClickListener { onAction?.invoke() }
        if (onClick != null) {
            card.root.setOnClickListener { onClick() }
            card.root.isClickable = true
        }
        card.root.contentDescription = listOfNotNull(title, subtitle, body).joinToString(". ")
        addView(card.root)
    }
}
