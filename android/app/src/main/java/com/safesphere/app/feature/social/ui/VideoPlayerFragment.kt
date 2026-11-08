package com.safesphere.app.feature.social.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.MediaController
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentVideoPlayerBinding

/**
 * Phase 17C (decision D-6): plays one video with the platform VideoView (no new library) and its standard controls.
 * Opened only by a tap on a video; nothing in the feed or on the post page ever plays by itself. The player streams
 * from the server with byte ranges and the current sign-in header; nothing is written to the phone (Q3). A moderator's
 * case video ([ARG_CASE_ID]) uses the moderators' route and keeps the window FLAG_SECURE, like the case screen.
 */
class VideoPlayerFragment : ViewBindingFragment<FragmentVideoPlayerBinding>(FragmentVideoPlayerBinding::inflate) {

    private val caseId: String? get() = arguments?.getString(ARG_CASE_ID)?.takeIf { it.isNotBlank() }

    private val viewModel by viewModels<VideoPlayerViewModel> {
        val mediaId = arguments?.getString(ARG_MEDIA_ID).orEmpty()
        val case = caseId
        val url = if (case != null) appContainer.contentModerationRepository.videoUrl(case, mediaId)
        else appContainer.experienceRepository.videoUrl(mediaId)
        viewModelFactory { initializer { VideoPlayerViewModel(appContainer.experienceRepository, url) } }
    }

    /** The address was given to this view's player (once per view, or again after "Try again"). */
    private var opened = false

    /** This view's player is ready to play. */
    private var prepared = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val controls = MediaController(requireContext())
        controls.setAnchorView(binding.videoFrame)
        binding.video.setMediaController(controls)
        binding.video.setOnPreparedListener {
            prepared = true
            binding.progress.isVisible = false
            binding.video.seekTo(viewModel.positionMs)
            if (viewModel.playWhenReady) binding.video.start() else controls.show(0)
        }
        binding.video.setOnCompletionListener {
            viewModel.onFinished()
            controls.show(0)
        }
        binding.video.setOnErrorListener { _, _, _ ->
            viewModel.onPlaybackError()
            true // handled: our own words instead of the platform dialog
        }
        binding.tryAgainButton.setOnClickListener {
            opened = false
            prepared = false
            viewModel.onTryAgain()
        }
        collectWhenStarted(viewModel.state, ::render)
    }

    private fun render(state: VideoPlayerViewModel.State) {
        binding.progress.isVisible = state is VideoPlayerViewModel.State.Checking ||
            (state is VideoPlayerViewModel.State.Ready && !prepared)
        binding.problem.isVisible = state is VideoPlayerViewModel.State.Problem
        binding.tryAgainButton.isVisible = state is VideoPlayerViewModel.State.Problem
        binding.video.isVisible = state !is VideoPlayerViewModel.State.Problem
        when (state) {
            is VideoPlayerViewModel.State.Problem -> {
                binding.problem.setText(state.message)
                binding.video.stopPlayback()
            }
            VideoPlayerViewModel.State.Ready -> if (!opened) {
                opened = true
                // The platform player has its own connection: it gets the current sign-in header (after the check
                // above renewed it if needed), for this playback only.
                binding.video.setVideoURI(Uri.parse(viewModel.url), appContainer.videoHeaders(viewModel.url))
            }
            VideoPlayerViewModel.State.Checking -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        if (caseId != null) requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        val playing = binding.video.isPlaying
        viewModel.onLeft(binding.video.currentPosition, keepPlaying = playing && requireActivity().isChangingConfigurations)
        binding.video.pause()
        if (caseId != null) requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onDestroyView() {
        binding.video.stopPlayback()
        opened = false
        prepared = false
        super.onDestroyView()
    }


    companion object {
        const val ARG_MEDIA_ID = "mediaId"
        const val ARG_CASE_ID = "caseId"
    }
}
