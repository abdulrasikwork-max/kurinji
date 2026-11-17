package com.safesphere.app.feature.moments.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentTodayBinding
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.People
import com.safesphere.app.navigation.TabReselect

/**
 * Phase N4: Today, the first tab: the last 24 hours of the community, newest first, with today's question pinned and an
 * end. Two views: MIET, or My people. It only reads and navigates; nothing here is counted, ranked or kept on the
 * phone. Screenshots are blocked while it is on the screen (D12: other people's words and photos).
 */
class TodayFragment : ViewBindingFragment<FragmentTodayBinding>(FragmentTodayBinding::inflate), TabReselect {

    private val viewModel by viewModels<TodayViewModel> { TodayViewModel.factory(appContainer) }
    private lateinit var adapter: MomentCardAdapter

    /** Covered moments the reader chose to read; forgotten when the screen is left for good. */
    private val opened = HashSet<String>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = MomentCardAdapter(momentImages(), onOpen = ::open, onUncover = { moment ->
            opened += moment.id
            render(viewModel.uiState.value)
        }, onAnswer = { question ->
            findNavController().navigate(R.id.createFragment, bundleOf(CreateFragment.ARG_QUESTION_ID to question.id,
                CreateFragment.ARG_QUESTION_TEXT to question.text))
        })
        binding.wall.adapter = adapter
        binding.wall.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as LinearLayoutManager
                if (manager.findLastVisibleItemPosition() >= adapter.itemCount - NEAR_END_ROWS) viewModel.onNearEnd()
            }
        })
        binding.peopleToggle.check(if (viewModel.uiState.value.people == People.MINE) R.id.peopleMine else R.id.peopleAll)
        binding.peopleToggle.contentDescription = getString(R.string.cd_today_people)
        binding.peopleToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) viewModel.onPeople(if (checkedId == R.id.peopleMine) People.MINE else People.ALL)
        }
        binding.retryButton.setOnClickListener { viewModel.refresh() }
        binding.getVerifiedButton.setOnClickListener { findNavController().navigate(R.id.membershipFragment) }
        // A moment was deleted on its own page: it leaves the wall at once.
        findNavController().currentBackStackEntry?.savedStateHandle?.getLiveData<String>(MomentFragment.RESULT_REMOVED)
            ?.observe(viewLifecycleOwner) { id -> viewModel.onRemoved(id) }
        // A2: a moment was just shared from Create: read the wall again from the top.
        findNavController().currentBackStackEntry?.savedStateHandle?.let { handle ->
            handle.getLiveData<String>(CreateFragment.RESULT_SHARED).observe(viewLifecycleOwner) { id ->
                if (id != null) {
                    handle.remove<String>(CreateFragment.RESULT_SHARED)
                    binding.wall.scrollToPosition(0)
                    viewModel.refresh()
                }
            }
        }
        collectWhenStarted(viewModel.uiState, ::render)
        viewModel.start()
    }

    override fun onResume() {
        super.onResume()
        secure(true)
        viewModel.onShown()
    }

    override fun onPause() {
        super.onPause()
        secure(false)
    }

    /** The Today tab tapped again: back to the top and read again. */
    override fun onTabReselected() {
        if (view == null) return
        binding.wall.scrollToPosition(0)
        binding.todayHead.setExpanded(true)
        viewModel.refresh()
    }

    private fun open(moment: Moment) {
        findNavController().navigate(R.id.momentFragment, bundleOf(MomentFragment.ARG_ID to moment.id))
    }

    private fun render(state: TodayUiState) {
        if (view == null) return
        adapter.submitList(TodayWall.rows(state, opened))
        binding.peopleToggle.isVisible = !state.needsMembership
        binding.membershipBlock.isVisible = state.needsMembership
        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        binding.retryButton.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private companion object {
        /** Ask for the next page this many rows before the end. */
        const val NEAR_END_ROWS = 4
    }
}

/** D12: no screenshots or screen recording while other people's moments are on the screen. */
internal fun Fragment.secure(on: Boolean) {
    val window = activity?.window ?: return
    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

/** The pictures of moments and people, through the one image loader. */
internal fun Fragment.momentImages(): MomentImages {
    val repository = appContainer.momentRepository
    return MomentImages(appContainer.imageLoader, repository::photoUrl, repository::personPhotoUrl)
}
