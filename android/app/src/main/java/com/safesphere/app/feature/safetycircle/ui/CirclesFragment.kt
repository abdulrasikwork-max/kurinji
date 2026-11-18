package com.safesphere.app.feature.safetycircle.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentCirclesBinding
import com.safesphere.app.databinding.ItemCircleBinding
import com.safesphere.app.feature.safetycircle.data.CircleRequest

/**
 * N7 (D36): Safety Circles you're in (You → Safety). A request says who added you and what accepting means; the
 * owner learns nothing from Not now or Don't ask again. A circle you're in can be left at any time.
 */
class CirclesFragment : ViewBindingFragment<FragmentCirclesBinding>(FragmentCirclesBinding::inflate) {

    private val viewModel: CirclesViewModel by viewModels { CirclesViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        collectWhenStarted(viewModel.state, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.load()
        // N7 (D34): reading the requests removes their notification on this phone.
        appContainer.pushTray.cancel("circle-requests")
    }

    private fun render(state: CirclesState) {
        binding.progress.isVisible = state.loading
        binding.emptyText.isVisible = !state.loading && state.problem != CirclesState.Problem.LOAD &&
            state.requests.isEmpty() && state.circles.isEmpty()
        binding.requestsHeader.isVisible = state.requests.isNotEmpty()
        binding.circlesHeader.isVisible = state.circles.isNotEmpty()
        val message = when (state.problem) {
            CirclesState.Problem.LOAD -> getString(R.string.circles_load_failed)
            CirclesState.Problem.ACTION -> getString(R.string.circles_action_failed)
            null -> null
        }
        binding.notice.isVisible = message != null
        message?.let { binding.notice.setMessage(it) }

        binding.requestsList.removeAllViews()
        for (r in state.requests) {
            row(binding.requestsList, getString(R.string.circles_request_line, r.ownerName), state.busy,
                R.string.circles_accept to { viewModel.onAccept(r.id) },
                R.string.circles_not_now to { viewModel.onNotNow(r.id) },
                R.string.circles_dont_ask to { viewModel.onDontAskAgain(r.id) })
        }
        binding.circlesList.removeAllViews()
        for (c in state.circles) {
            row(binding.circlesList, getString(R.string.circles_in_line, c.ownerName), state.busy,
                R.string.circles_leave to { confirmLeave(c) }, null, null)
        }
    }

    private fun row(parent: android.widget.LinearLayout, line: String, busy: Boolean,
                    first: Pair<Int, () -> Unit>, second: Pair<Int, () -> Unit>?, third: Pair<Int, () -> Unit>?) {
        val item = ItemCircleBinding.inflate(layoutInflater, parent, false)
        item.circleLine.text = line
        listOf(item.primaryButton to first, item.secondButton to second, item.thirdButton to third).forEach { (button, action) ->
            button.isVisible = action != null
            if (action != null) {
                button.setText(action.first)
                button.isEnabled = !busy
                button.setOnClickListener { action.second() }
            }
        }
        parent.addView(item.root)
    }

    private fun confirmLeave(circle: CircleRequest) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.circles_leave_title, circle.ownerName))
            .setMessage(R.string.circles_leave_message)
            .setPositiveButton(R.string.circles_leave) { _, _ -> viewModel.onLeave(circle.id) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
