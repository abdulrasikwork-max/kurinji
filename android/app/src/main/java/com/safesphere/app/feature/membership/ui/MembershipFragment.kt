package com.safesphere.app.feature.membership.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentMembershipBinding
import com.safesphere.app.feature.membership.data.IdPhotoFiles
import com.safesphere.app.feature.membership.domain.CommunityStatus
import com.safesphere.app.feature.membership.domain.MembershipState
import com.safesphere.app.feature.membership.domain.OutcomeKind
import com.safesphere.app.feature.membership.domain.VerificationMethod
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Phase N3 (A1): "Get verified" and the person's own membership (docs/PHASE_N3.md). The person chooses ONE way: in
 * person (default; a short code shown here, read by a verifier who holds the ID card) or a photo of the ID card (an
 * admin decides). FLAG_SECURE while open (the code, the typed roll number). The ID photo comes from the camera app
 * (no camera permission; see [IdPhotoFiles]); only without a camera app is the Photo Picker offered.
 */
class MembershipFragment : ViewBindingFragment<FragmentMembershipBinding>(FragmentMembershipBinding::inflate) {

    private val viewModel by viewModels<MembershipViewModel> { MembershipViewModel.factory(appContainer) }

    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val shot = viewModel.pendingShot
        when {
            shot == null -> Unit // the app was closed meanwhile: the file is swept at the next open
            taken -> viewModel.onPhotoTaken(shot)
            else -> viewModel.onPhotoCancelled()
        }
    }

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onPhotoTaken(IdPhotoFiles.Picked(uri))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Leftover ID photo files of a closed app (never while the camera app is writing one for this screen).
        if (viewModel.pendingShot == null) appContainer.idPhotoFiles.sweep()

        binding.methodGroup.setOnCheckedChangeListener { _, checked ->
            viewModel.onMethod(if (checked == R.id.idPhotoOption) VerificationMethod.ID_PHOTO else VerificationMethod.IN_PERSON)
        }
        binding.rollInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                binding.rollLayout.error = null
            }
        })
        binding.programmeInput.setOnClickListener { chooseProgramme() }
        binding.yearInput.setOnClickListener { chooseYear() }
        binding.askButton.setOnClickListener { viewModel.onAsk(binding.rollInput.text?.toString().orEmpty()) }
        binding.newCodeButton.setOnClickListener { viewModel.onNewCode() }
        binding.photoButton.setOnClickListener { takeIdPhoto() }
        binding.withdrawButton.setOnClickListener { confirmWithdraw() }
        binding.retryButton.setOnClickListener { viewModel.load() }

        collectWhenStarted(viewModel.uiState, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.load()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    private fun render(state: MembershipUiState) {
        val status = state.status
        binding.progress.isVisible = state.loading || state.busy ||
            state.photo == PhotoStep.PREPARING || state.photo == PhotoStep.UPLOADING
        renderOutcome(status)
        renderMember(status)

        val canAsk = status != null && (status.state == MembershipState.NONE || status.state == MembershipState.ENDED)
        binding.formBlock.isVisible = canAsk
        if (canAsk) renderForm(state)
        binding.pendingBlock.isVisible = status?.state == MembershipState.PENDING
        if (status?.state == MembershipState.PENDING) renderPending(state, status)

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        binding.retryButton.isVisible = failure != null && status == null && !state.loading

        state.notice?.let {
            toast(when (it) {
                MembershipNotice.CODE_RENEWED -> R.string.membership_code_renewed
                MembershipNotice.PHOTO_SENT -> R.string.membership_photo_thanks
                MembershipNotice.WITHDRAWN -> R.string.membership_withdrawn
            })
            if (it == MembershipNotice.WITHDRAWN) binding.rollInput.setText("")
            viewModel.onNoticeShown()
        }
        when (state.photo) {
            PhotoStep.UNREADABLE -> { toast(R.string.membership_photo_unreadable); viewModel.onPhotoStepShown() }
            PhotoStep.FAILED -> { toast(R.string.membership_photo_failed); viewModel.onPhotoStepShown() }
            else -> Unit
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    /** A refusal, an end or a ban: told to the person, with what still works. */
    private fun renderOutcome(status: CommunityStatus?) {
        val outcome = status?.outcome
        val text = when {
            status == null -> null
            status.state == MembershipState.BANNED -> getString(R.string.membership_banned,
                getString(MembershipLabels.ban(outcome?.reason))) + "\n" + getString(R.string.membership_safety_unchanged)
            status.state == MembershipState.ENDED -> getString(
                if (outcome?.reason == "GRADUATED") R.string.membership_ended_graduated else R.string.membership_ended_removed)
            status.state == MembershipState.NONE && outcome?.kind == OutcomeKind.REFUSED ->
                getString(R.string.membership_refused, getString(MembershipLabels.refusal(outcome.reason)))
            else -> null
        }
        binding.outcomeNotice.isVisible = text != null
        if (text != null) binding.outcomeNotice.setMessage(text)
    }

    private fun renderMember(status: CommunityStatus?) {
        val member = status?.takeIf { it.state == MembershipState.MEMBER }
        binding.memberBlock.isVisible = member != null
        val info = member?.membership
        if (member != null) {
            binding.memberLine.text = getString(R.string.membership_member_line,
                member.communityName ?: getString(R.string.membership_community_fallback))
            binding.memberDetail.isVisible = info != null
            binding.memberDetail.text = info?.let {
                getString(R.string.membership_member_detail, it.programmeLabel, it.graduationYear)
            }.orEmpty()
            val until = info?.endsAt?.let(MembershipDates::lastDay)
            binding.memberUntil.isVisible = until != null
            binding.memberUntil.text = until?.let { getString(R.string.membership_member_until, it) }.orEmpty()
        }
        val correction = status?.nameCorrection
        binding.nameCorrectionLine.isVisible = correction != null
        binding.nameCorrectionLine.text = correction?.let { getString(R.string.membership_name_corrected, it.from) }.orEmpty()
        binding.nameLockedLine.isVisible = status?.nameLocked == true
    }

    private fun renderForm(state: MembershipUiState) {
        val checked = if (state.method == VerificationMethod.ID_PHOTO) R.id.idPhotoOption else R.id.inPersonOption
        if (binding.methodGroup.checkedRadioButtonId != checked) binding.methodGroup.check(checked)
        binding.methodDetail.setText(if (state.method == VerificationMethod.ID_PHOTO) R.string.membership_way_photo_detail
            else R.string.membership_way_in_person_detail)
        binding.rollLayout.error = if (state.rollProblem) getString(R.string.membership_roll_problem) else null
        val programme = state.programme?.label.orEmpty()
        if (binding.programmeInput.text.toString() != programme) binding.programmeInput.setText(programme)
        binding.programmeLayout.error = if (state.programmeProblem) getString(R.string.membership_programme_problem) else null
        val year = state.year?.toString().orEmpty()
        if (binding.yearInput.text.toString() != year) binding.yearInput.setText(year)
        binding.yearLayout.error = if (state.yearProblem) getString(R.string.membership_year_problem) else null
        binding.askButton.isEnabled = !state.busy && !state.loading && state.programmes.isNotEmpty()
    }

    private fun renderPending(state: MembershipUiState, status: CommunityStatus) {
        val request = status.request
        val inPerson = request?.method == VerificationMethod.IN_PERSON
        val photo = request?.method == VerificationMethod.ID_PHOTO
        binding.pendingTitle.setText(when {
            inPerson -> R.string.membership_pending_in_person
            photo && request?.photoUploaded == true -> R.string.membership_pending_photo_sent_title
            photo -> R.string.membership_pending_photo_title
            else -> R.string.membership_pending_title
        })
        binding.pendingDetail.text = when {
            request == null -> ""
            inPerson -> getString(R.string.membership_pending_in_person_detail, request.programmeLabel, request.graduationYear)
            request.photoUploaded -> getString(R.string.membership_photo_sent)
            else -> getString(R.string.membership_pending_photo_needed)
        }
        // In person: the code (memory only) or, when it ran out or the app was reopened, a button for a new one.
        val code = state.code
        binding.codeText.isVisible = inPerson && code != null
        binding.codeText.text = code.orEmpty()
        binding.codeText.contentDescription = code?.let { getString(R.string.membership_code_description, it.toList().joinToString(" ")) }
        binding.codeHint.isVisible = inPerson
        binding.codeHint.text = if (code != null) resources.getQuantityString(R.plurals.membership_code_minutes,
            state.codeMinutes.toInt(), state.codeMinutes.toInt()) else getString(R.string.membership_code_gone)
        binding.newCodeButton.isVisible = inPerson
        binding.newCodeButton.isEnabled = !state.busy
        // By photo: take it (again) until an admin decides.
        val photoBusy = state.photo == PhotoStep.PREPARING || state.photo == PhotoStep.UPLOADING
        binding.photoButton.isVisible = photo
        binding.photoButton.isEnabled = !photoBusy && !state.busy
        binding.photoButton.setText(when {
            state.photo == PhotoStep.PREPARING -> R.string.membership_photo_preparing
            state.photo == PhotoStep.UPLOADING -> R.string.membership_photo_uploading
            request?.photoUploaded == true -> R.string.membership_retake_photo
            else -> R.string.membership_take_photo
        })
        val until = request?.expiresAt?.let(::date)
        binding.requestUntil.isVisible = until != null
        binding.requestUntil.text = until?.let { getString(R.string.membership_request_until, it) }.orEmpty()
        binding.withdrawButton.isEnabled = !state.busy && !photoBusy
    }

    private fun chooseProgramme() {
        val programmes = viewModel.uiState.value.programmes
        if (programmes.isEmpty()) return
        val labels = programmes.map { it.label }.toTypedArray()
        val selected = programmes.indexOf(viewModel.uiState.value.programme)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.membership_programme_label)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                viewModel.onProgramme(programmes[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun chooseYear() {
        val programme = viewModel.uiState.value.programme
        if (programme == null) {
            binding.yearLayout.error = getString(R.string.membership_year_first)
            return
        }
        val years = programme.years
        val selected = years.indexOf(viewModel.uiState.value.year)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.membership_year_label)
            .setSingleChoiceItems(years.map(Int::toString).toTypedArray(), selected) { dialog, which ->
                viewModel.onYear(years[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** The camera app writes into the app's cache (no camera permission); without one, the Photo Picker. */
    private fun takeIdPhoto() {
        val capture = appContainer.idPhotoFiles.newCapture()
        if (capture != null) {
            viewModel.pendingShot = capture
            try {
                takePhoto.launch(capture.uri)
                return
            } catch (e: ActivityNotFoundException) {
                viewModel.onPhotoCancelled()
            }
        }
        toast(R.string.membership_no_camera)
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun confirmWithdraw() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.membership_withdraw_confirm_title)
            .setMessage(R.string.membership_withdraw_confirm_body)
            .setPositiveButton(R.string.membership_withdraw) { _, _ -> viewModel.onWithdraw() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun date(instant: java.time.Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).format(instant.atZone(ZoneId.systemDefault()))

    private fun toast(text: Int) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
}
