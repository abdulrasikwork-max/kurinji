package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentComposeBinding
import com.safesphere.app.feature.social.domain.Audience
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.CommentPolicy
import com.safesphere.app.feature.social.domain.ComposerPolicy
import com.safesphere.app.feature.social.domain.Daypart
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.PostType
import com.safesphere.app.feature.social.domain.Precision

/**
 * Phase 13B: Share (design §9.5). The Share button repeats the audience ("Share with Connections"); an exact place is
 * explained as appearing 1 to 3 hours later; a place near a private place offers only the city or no place.
 */
class ComposeFragment : ViewBindingFragment<FragmentComposeBinding>(FragmentComposeBinding::inflate) {

    private val viewModel by viewModels<ComposeViewModel> { ComposeViewModel.factory(appContainer) }

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ComposerPolicy.MAX_PHOTOS)) { uris ->
            if (uris.isNotEmpty()) viewModel.onPhotosPicked(uris)
        }

    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var typeChips: ChoiceChips<PostType>
    private lateinit var precisionChips: ChoiceChips<Precision>
    private lateinit var cityChips: ChoiceChips<City>
    private lateinit var daypartChips: ChoiceChips<Daypart>
    private lateinit var categoryChips: ChoiceChips<Interest>
    private lateinit var audienceChips: ChoiceChips<Audience>
    private lateinit var commentChips: ChoiceChips<CommentPolicy>
    private var months = emptyList<java.time.YearMonth>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        photoAdapter = PhotoAdapter(appContainer.imageLoader)
        binding.photoList.adapter = photoAdapter
        typeChips = ChoiceChips(binding.typeGroup) { it?.let(viewModel::onType) }
        precisionChips = ChoiceChips(binding.precisionGroup) { it?.let(viewModel::onPrecision) }
        cityChips = ChoiceChips(binding.cityGroup) { it?.let(viewModel::onCity) }
        daypartChips = ChoiceChips(binding.daypartGroup) { viewModel.onDaypart(it) }
        categoryChips = ChoiceChips(binding.categoryGroup) { viewModel.onCategory(it) }
        audienceChips = ChoiceChips(binding.audienceGroup) { it?.let(viewModel::onAudience) }
        commentChips = ChoiceChips(binding.commentGroup) { it?.let(viewModel::onCommentPolicy) }

        binding.bodyInput.doAfterTextChanged { viewModel.onBody(it?.toString().orEmpty()) }
        binding.tipInput.doAfterTextChanged { viewModel.onTip(it?.toString().orEmpty()) }
        binding.addPhotosButton.setOnClickListener {
            pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.choosePlaceButton.setOnClickListener { findNavController().navigate(R.id.placePickerFragment) }
        binding.shareButton.setOnClickListener { viewModel.onShare() }
        binding.approveFirstSwitch.setOnCheckedChangeListener { _, on -> viewModel.onApproveFirst(on) }

        months = ComposerPolicy.monthsOffered(viewModel.currentMonth)
        binding.monthSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
            months.map { ExperienceLabels.monthName(it) })
        binding.monthSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                viewModel.onMonth(months[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        setFragmentResultListener(PlacePickerFragment.RESULT) { _, bundle ->
            when (bundle.getString(PlacePickerFragment.KEY_CHOICE)) {
                PlacePickerFragment.CHOICE_PLACE -> PlacePickerFragment.placeFrom(bundle)?.let(viewModel::onPlaceChosen)
                PlacePickerFragment.CHOICE_CITY -> viewModel.onCityOnly()
                PlacePickerFragment.CHOICE_NONE -> viewModel.onNoPlace()
            }
        }
        collectWhenStarted(viewModel.uiState, ::render)
    }

    private fun render(state: ComposeUiState) {
        val experience = state.type == PostType.EXPERIENCE
        typeChips.show(PostType.entries.map { it to getString(if (it == PostType.EXPERIENCE)
            R.string.social_type_experience else R.string.social_type_ask) }, state.type)
        binding.typeHint.setText(if (experience) R.string.social_type_experience_hint else R.string.social_type_ask_hint)
        binding.bodyLayout.hint = getString(if (experience) R.string.social_body_label_experience else R.string.social_body_label_ask)
        binding.tipLayout.isVisible = experience

        photoAdapter.submitList(state.photos.map { photo ->
            PhotoCell(photo.key, photo.mediaId?.let(appContainer.experienceRepository::photoUrl),
                when {
                    photo.failed -> getString(R.string.social_photo_failed)
                    photo.uploading -> getString(R.string.social_photo_uploading)
                    else -> null
                }) { viewModel.onRemovePhoto(photo.key) }
        })
        binding.addPhotosButton.isEnabled = state.canAddPhotos

        binding.placeText.text = when {
            state.place != null -> state.place.name
            state.precision == Precision.CITY -> getString(R.string.social_where_city_only)
            state.precision == Precision.NONE -> getString(R.string.social_where_no_place)
            else -> getString(R.string.social_where_none)
        }
        val hasPlace = state.place != null
        binding.precisionTitle.isVisible = hasPlace
        binding.precisionGroup.isVisible = hasPlace
        precisionChips.show(state.allowedPrecisions.map { it to getString(ExperienceLabels.of(it)) }, state.precision)
        binding.privateNotice.isVisible = (hasPlace && state.placeIsPrivate) || state.privatePlacesUnknown
        binding.privateNotice.setMessage(getString(if (state.privatePlacesUnknown) R.string.social_private_unknown_notice
            else R.string.social_precision_private_notice))
        val needsCity = state.precision == Precision.CITY
        binding.cityTitle.isVisible = needsCity
        binding.cityGroup.isVisible = needsCity
        cityChips.show(City.entries.map { it to ExperienceLabels.cityName(it) }, state.city)
        binding.delayNotice.isVisible = state.isDelayed

        binding.whenTitle.isVisible = experience
        binding.monthSpinner.isVisible = experience
        val monthIndex = months.indexOf(state.month)
        if (monthIndex >= 0 && binding.monthSpinner.selectedItemPosition != monthIndex) binding.monthSpinner.setSelection(monthIndex)
        daypartChips.show(Daypart.entries.map { it to getString(ExperienceLabels.of(it)) }, state.daypart)
        categoryChips.show(Interest.entries.map { it to getString(SocialLabels.of(it)) }, state.category)

        audienceChips.show(Audience.entries.map { it to getString(ExperienceLabels.of(it)) }, state.audience)
        binding.audienceLine.text = state.audience?.let { getString(ExperienceLabels.lineOf(it)) }.orEmpty()
        binding.audienceLine.isVisible = state.audience != null
        val canTakePerspectives = state.audience != Audience.ONLY_ME
        binding.commentsTitle.isVisible = canTakePerspectives
        binding.commentGroup.isVisible = canTakePerspectives
        commentChips.show(CommentPolicy.entries.map { it to getString(ExperienceLabels.of(it)) }, state.commentPolicy)
        binding.approveFirstSwitch.isVisible = state.canApproveFirst
        if (binding.approveFirstSwitch.isChecked != state.approveFirst) binding.approveFirstSwitch.isChecked = state.approveFirst

        val shown = when (state.precision) {
            Precision.EXACT -> state.place?.name
            Precision.AREA -> state.place?.let { getString(R.string.social_area_line, it.areaCell) }
            Precision.CITY -> state.city?.let(ExperienceLabels::cityName)
            Precision.NONE -> getString(R.string.social_precision_none)
            null -> null
        }
        binding.summaryLine.text = shown?.let { getString(R.string.social_location_summary, it) }.orEmpty()
        binding.summaryLine.isVisible = shown != null

        val issues = buildList {
            if (state.photosPending) add(getString(R.string.social_issue_photos_pending))
            state.issues.forEach { add(getString(ExperienceLabels.of(it))) }
        }
        binding.issuesText.isVisible = state.showIssues && issues.isNotEmpty()
        binding.issuesText.text = issues.joinToString("\n")
        binding.shareButton.text = state.audience?.let { getString(R.string.social_share_with, getString(ExperienceLabels.of(it))) }
            ?: getString(R.string.social_share_choose_audience)
        binding.shareButton.isEnabled = !state.isBusy
        binding.progress.isVisible = state.isBusy

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))

        state.sharedPostId?.let { postId ->
            viewModel.onSharedHandled()
            Toast.makeText(requireContext(), R.string.social_shared, Toast.LENGTH_SHORT).show()
            findNavController().navigate(R.id.postFragment, bundleOf(PostViewModel.ARG_POST_ID to postId),
                androidx.navigation.NavOptions.Builder().setPopUpTo(R.id.composeFragment, true).build())
            return
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }
}
