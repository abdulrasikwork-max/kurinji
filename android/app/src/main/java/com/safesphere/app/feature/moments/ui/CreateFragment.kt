package com.safesphere.app.feature.moments.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentCreateBinding
import com.safesphere.app.feature.moments.data.remote.QuestionDto
import com.safesphere.app.feature.moments.domain.Moment
import com.safesphere.app.feature.moments.domain.MomentAudience
import com.safesphere.app.feature.moments.domain.MomentKind
import com.safesphere.app.feature.social.ui.DraftNudgeDialogs
import java.text.NumberFormat
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/**
 * Phase N4 (A2): Create, opened by the ✚ in the bar (or by "Answer" on today's question). A task screen: toolbar and
 * back, no bottom bar. Opens ready to type. The photo comes from the camera app only (no gallery; the button is hidden
 * on a phone without a camera app). The private nudge (on the phone only) runs before sharing. Leaving with a draft asks
 * first. Not screenshot-protected (D12: only one's own words are on it).
 */
class CreateFragment : ViewBindingFragment<FragmentCreateBinding>(FragmentCreateBinding::inflate) {

    private val viewModel by viewModels<CreateViewModel> { CreateViewModel.factory(appContainer) }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        viewModel.onCameraResult(taken)
    }

    /** The dialog that says what happened to a shared moment; Create closes when it goes. */
    private var resultDialog: androidx.appcompat.app.AlertDialog? = null

    /** The preview is decoded once per photo, not on every redraw. */
    private var previewOf: ByteArray? = null

    private val leaveGuard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = confirmDiscard()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, leaveGuard)
        viewModel.start(questionArgument())
        // Leftover photo files of a closed app (never while the camera app is writing one for this screen).
        if (viewModel.pendingShot == null) appContainer.momentPhotoFiles.sweep()

        binding.kindToggle.check(if (viewModel.uiState.value.kind == MomentKind.WHOS_IN) R.id.kindWhosIn else R.id.kindSay)
        binding.kindToggle.contentDescription = getString(R.string.cd_create_kind)
        binding.kindToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) viewModel.onKind(if (checkedId == R.id.kindWhosIn) MomentKind.WHOS_IN else MomentKind.SAY)
        }
        binding.audienceToggle.contentDescription = getString(R.string.create_audience_label)
        binding.audienceToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) viewModel.onAudience(if (checkedId == R.id.audienceMine) MomentAudience.MY_PEOPLE else MomentAudience.COMMUNITY)
        }
        binding.textInput.doAfterTextChanged { viewModel.onText(it?.toString().orEmpty()) }
        binding.whereInput.doAfterTextChanged { viewModel.onWhere(it?.toString().orEmpty()) }
        binding.removeQuestionButton.setOnClickListener { viewModel.onRemoveQuestion() }
        binding.planTimeButton.setOnClickListener { choosePlanTime() }
        binding.clearPlanTimeButton.setOnClickListener { viewModel.onPlanTime(null) }
        binding.takePhotoButton.setOnClickListener { openCamera() }
        binding.removePhotoButton.setOnClickListener { viewModel.onRemovePhoto() }
        binding.retryPhotoButton.setOnClickListener { viewModel.onRetryUpload() }
        binding.getVerifiedButton.setOnClickListener { findNavController().navigate(R.id.membershipFragment) }
        binding.shareButton.setOnClickListener { share() }
        binding.takePhotoButton.isVisible = appContainer.momentPhotoFiles.hasCameraApp()

        collectWhenStarted(viewModel.uiState, ::render)
        if (savedInstanceState == null) openKeyboard()
    }

    private fun render(state: CreateUiState) {
        if (view == null) return
        val context = requireContext()
        binding.progress.isVisible = state.loading || state.sharing
        binding.membershipBlock.isVisible = state.needsMembership
        binding.draftBlock.isVisible = !state.needsMembership

        binding.questionBlock.isVisible = state.question != null
        state.question?.let { binding.questionText.text = it.text }

        val whosIn = state.kind == MomentKind.WHOS_IN
        binding.textInput.setHint(if (whosIn) R.string.create_hint_whos_in else R.string.create_hint_say)
        binding.planBlock.isVisible = whosIn
        val planAt = viewModel.planAt()
        binding.planTimeButton.text = planAt?.let { MomentCardBinder.planTime(context, it, viewModel.nowInstant()) }
            ?: getString(R.string.create_plan_time_add)
        binding.clearPlanTimeButton.isVisible = planAt != null

        val room = state.roomLeft
        binding.roomLeft.isVisible = room != null
        if (room != null) {
            val number = NumberFormat.getIntegerInstance().format(abs(room))
            binding.roomLeft.text = getString(if (room >= 0) R.string.create_room_left else R.string.create_room_over, number)
        }

        renderPhoto(state)

        val audienceId = when (state.audience) {
            MomentAudience.COMMUNITY -> R.id.audienceAll
            MomentAudience.MY_PEOPLE -> R.id.audienceMine
            null -> View.NO_ID
        }
        if (binding.audienceToggle.checkedButtonId != audienceId && audienceId != View.NO_ID) binding.audienceToggle.check(audienceId)
        binding.audienceHelp.isVisible = state.audience != MomentAudience.COMMUNITY
        binding.audienceHelp.setText(
            if (state.audience == null) R.string.create_audience_needed else R.string.create_audience_mine_help
        )
        binding.newMemberNotice.isVisible = state.showsNewMemberLine
        state.newMemberUntil?.let { until ->
            binding.newMemberNotice.setMessage(getString(R.string.create_new_member, dateTime(context, until)))
        }

        binding.shareButton.isEnabled = state.canShare
        leaveGuard.isEnabled = state.hasDraft && state.shared == null

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(createMessage(failure))
        if (state.photoUnreadable) {
            viewModel.onPhotoUnreadableShown()
            Toast.makeText(context, R.string.create_photo_unreadable, Toast.LENGTH_LONG).show()
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
        val shared = state.shared
        if (shared != null) {
            viewModel.onSharedHandled()
            onShared(shared)
        } else if (state.finished && resultDialog == null) {
            close() // a rotation lost the dialog: never stay on a shared draft
        }
    }

    private fun renderPhoto(state: CreateUiState) {
        val photo = state.photo
        binding.photoPreparing.isVisible = state.preparingPhoto
        binding.photoBlock.isVisible = photo != null
        binding.takePhotoButton.isEnabled = !state.preparingPhoto && !state.sharing
        binding.takePhotoButton.setText(if (photo != null) R.string.create_photo_retake else R.string.create_photo_take)
        if (photo == null) {
            previewOf = null
            binding.photoPreview.setImageDrawable(null)
            return
        }
        if (previewOf !== photo.jpeg) {
            previewOf = photo.jpeg
            val bitmap = preview(photo.jpeg)
            if (bitmap != null) binding.photoPreview.setPhotoSize(bitmap.width, bitmap.height)
            binding.photoPreview.setImageBitmap(bitmap)
        }
        binding.photoStatus.setText(
            when {
                photo.uploaded != null -> R.string.create_photo_ready
                photo.failure != null -> R.string.create_photo_failed
                else -> R.string.create_photo_uploading
            }
        )
        binding.retryPhotoButton.isVisible = photo.failure != null
    }

    /** The camera app writes into the app's cache through the one file provider (no camera permission). */
    private fun openCamera() {
        val capture = appContainer.momentPhotoFiles.newCapture() ?: return
        viewModel.onCameraOpening(capture)
        try {
            takePhoto.launch(capture.uri)
        } catch (e: ActivityNotFoundException) {
            viewModel.onCameraResult(false)
            binding.takePhotoButton.isVisible = false
        }
    }

    private fun choosePlanTime() {
        val start = viewModel.uiState.value.planTime ?: LocalTime.now().plusHours(1).withMinute(0)
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(if (DateFormat.is24HourFormat(requireContext())) TimeFormat.CLOCK_24H else TimeFormat.CLOCK_12H)
            .setHour(start.hour)
            .setMinute(start.minute)
            .setTitleText(R.string.create_plan_time_add)
            .build()
        picker.addOnPositiveButtonClickListener { viewModel.onPlanTime(LocalTime.of(picker.hour, picker.minute)) }
        picker.show(childFragmentManager, "plan_time")
    }

    /** The private nudge first (the words never leave the phone for it); the person can always share. */
    private fun share() {
        val state = viewModel.uiState.value
        if (!state.canShare) return
        hideKeyboard()
        val words = listOf(state.text, if (state.kind == MomentKind.WHOS_IN) state.where else "")
            .filter { it.isNotBlank() }.joinToString("\n")
        if (words.isBlank()) viewModel.onShare()
        else DraftNudgeDialogs.beforeSharing(this, words, onlyMe = false) { viewModel.onShare() }
    }

    /** Today reads again; the person is told what happened (live, waiting for a moderator, or a support line). */
    private fun onShared(moment: Moment) {
        // Today reads again wherever Create was opened from (the bar works on a moment's page and on You too; found in
        // the N4-C walk: a share from a moment's page told only that page). Today is the start, so it is on the stack.
        val nav = findNavController()
        runCatching { nav.getBackStackEntry(R.id.todayFragment) }.getOrNull()?.savedStateHandle?.set(RESULT_SHARED, moment.id)
        val context = requireContext()
        when {
            moment.supportSuggested -> resultDialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.nudge_support_title)
                .setMessage(R.string.nudge_support_message)
                .setNeutralButton(R.string.nudge_support_call) { _, _ -> DraftNudgeDialogs.dialHelpline(this) }
                .setPositiveButton(R.string.create_support_close, null)
                .setOnDismissListener { close() }
                .show()
            moment.hiddenFromOthers -> resultDialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.create_shared_waiting_title)
                .setMessage(moment.notice?.let { MomentCardBinder.noticeText(context, it) } ?: getString(R.string.moment_life_waiting))
                .setPositiveButton(R.string.action_got_it, null)
                .setOnDismissListener { close() }
                .show()
            else -> {
                Toast.makeText(context, R.string.create_shared, Toast.LENGTH_SHORT).show()
                close()
            }
        }
    }

    override fun onDestroyView() {
        // A rotation: the dialog goes with this view (no leaked window); the new view closes Create (finished).
        resultDialog?.setOnDismissListener(null)
        resultDialog?.dismiss()
        resultDialog = null
        super.onDestroyView()
    }

    private fun confirmDiscard() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.create_discard_title)
            .setMessage(R.string.create_discard_message)
            .setPositiveButton(R.string.create_discard) { _, _ ->
                viewModel.onDiscard()
                close()
            }
            .setNegativeButton(R.string.create_keep_writing, null)
            .show()
    }

    /** Leaves Create once (a second render after sharing must not pop the screen below it: N4-C walk finding). */
    private var closed = false

    private fun close() {
        if (view == null || closed) return
        closed = true
        resultDialog = null
        leaveGuard.isEnabled = false
        hideKeyboard()
        findNavController().popBackStack()
    }

    /**
     * N-81: every refusal of POST /api/moments and of the photo upload is one of the server's written rules ("Choose a
     * time within the next 24 hours", "New members can share links after their first week…"), so on THIS screen a 400
     * shows the server's words too; everything else uses the app's own texts.
     */
    private fun createMessage(failure: ApiResult.Failure): String =
        if (failure.error == ApiError.VALIDATION && !failure.message.isNullOrBlank()) failure.message
        else failure.toMessage(requireContext())

    private fun questionArgument() = arguments?.let { args ->
        QuestionDto(args.getString(ARG_QUESTION_ID), args.getString(ARG_QUESTION_TEXT)).toDomain()
    }

    private fun openKeyboard() {
        binding.textInput.requestFocus()
        binding.textInput.post {
            if (view == null) return@post
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(binding.textInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val root = view ?: return
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun dateTime(context: Context, at: java.time.Instant): String {
        val local = at.atZone(ZoneId.systemDefault())
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(local)
        val time = DateFormat.getTimeFormat(context).format(java.util.Date.from(at))
        return "$date, $time"
    }

    /** A screen-sized copy of the prepared photo (the full one stays for the upload only). */
    private fun preview(jpeg: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_SIDE) sample *= 2
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    companion object {
        const val ARG_QUESTION_ID = "questionId"
        const val ARG_QUESTION_TEXT = "questionText"

        /** Set on the screen Create was opened from: the id of the moment just shared (Today reads again). */
        const val RESULT_SHARED = "moment_shared"

        private const val PREVIEW_SIDE = 1080
    }
}
