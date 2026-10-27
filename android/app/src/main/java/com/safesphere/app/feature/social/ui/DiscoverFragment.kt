package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.location.LocationAccess
import com.safesphere.app.core.ui.Haptics
import com.safesphere.app.core.ui.LocationAsk
import com.safesphere.app.core.ui.LocationSettings
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentDiscoverBinding
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.DiscoverItem
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import com.safesphere.app.feature.social.domain.Post

/**
 * Phase 13C, rebuilt in Phase 16 (docs/PHASE16.md): Discover, the first tab, as one social feed ([DiscoverFeed]):
 * open it and there is something to look at, react to (save with the butterfly, open the comments) and explore (Near
 * me, categories, places). It loads more while scrolling and then ends ("You're all caught up", U3). Search finds
 * places by name and categories by their words, never people.
 */
class DiscoverFragment : ViewBindingFragment<FragmentDiscoverBinding>(FragmentDiscoverBinding::inflate), FeedActions {

    private val viewModel by viewModels<DiscoverViewModel> { DiscoverViewModel.factory(appContainer) }

    // Granted: the fix is read. Refused: the same call answers "needs permission" at once (no dialog), with the button
    // that can still help (ask again, or open settings after a permanent refusal).
    private val locationAsk = LocationAsk(this) { viewModel.onNearMe() }

    private lateinit var feedAdapter: DiscoverFeedAdapter
    private val placeResults = PersonRowAdapter()
    private val categoryResults = PersonRowAdapter()
    private val nearResults = PersonRowAdapter()
    private lateinit var cityChips: ChoiceChips<City>
    private var cityPickerOpen = false
    private var searchOpen = false
    private var nearOpen = false
    private var retryNearOnReturn = false
    private var ageRequiredWhenLeft = false
    private var collapseStrongLanguage = false

    /** Back closes the search or "Near me" panel first. */
    private val closePanel = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            searchOpen = false
            nearOpen = false
            viewModel.onCloseNearby()
            render(viewModel.uiState.value)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, closePanel)
        feedAdapter = DiscoverFeedAdapter(appContainer.imageLoader, appContainer.experienceRepository::photoUrl, this)
        binding.feed.adapter = feedAdapter
        binding.feed.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val manager = recyclerView.layoutManager as LinearLayoutManager
                if (manager.findLastVisibleItemPosition() >= feedAdapter.itemCount - NEAR_END_ROWS) viewModel.onNearEnd()
            }
        })
        binding.searchPlaceList.adapter = placeResults
        binding.searchCategoryList.adapter = categoryResults
        binding.nearList.adapter = nearResults
        cityChips = ChoiceChips(binding.cityGroup) { city ->
            if (city != null) {
                cityPickerOpen = false
                viewModel.onCity(city)
                binding.feed.scrollToPosition(0)
            }
        }
        binding.cityButton.setOnClickListener { chooseCity() }
        binding.searchToggle.setOnClickListener {
            searchOpen = !searchOpen
            nearOpen = false
            if (searchOpen) binding.searchInput.requestFocus()
            render(viewModel.uiState.value)
        }
        binding.retryButton.setOnClickListener { viewModel.reload() }
        binding.closeNearButton.setOnClickListener {
            nearOpen = false
            viewModel.onCloseNearby()
            render(viewModel.uiState.value)
        }
        binding.searchInput.doAfterTextChanged { viewModel.onQuery(it?.toString().orEmpty()) }
        binding.searchInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) viewModel.onSearch()
            action == EditorInfo.IME_ACTION_SEARCH
        }
        binding.searchButton.setOnClickListener { viewModel.onSearch() }
        binding.clearSearchButton.setOnClickListener {
            binding.searchInput.setText("")
            viewModel.onClearSearch()
        }
        collectWhenStarted(viewModel.uiState, ::render)
        collectWhenStarted(appContainer.viewerPreferences.collapseStrongLanguage) { on ->
            if (on != collapseStrongLanguage) {
                collapseStrongLanguage = on
                render(viewModel.uiState.value)
            }
        }
        viewModel.start()
    }

    override fun onResume() {
        super.onResume()
        if (retryNearOnReturn) {
            retryNearOnReturn = false
            viewModel.onNearMe()
        }
        // Phase 17A: the age was confirmed while this screen was away: what the server refused before is allowed now.
        if (ageRequiredWhenLeft && !appContainer.ageGate.required.value) viewModel.reload()
        ageRequiredWhenLeft = false
    }

    override fun onPause() {
        super.onPause()
        ageRequiredWhenLeft = appContainer.ageGate.required.value
    }

    private fun render(state: DiscoverUiState) {
        if (view == null) return
        feedAdapter.submitList(DiscoverFeed.build(state).map { FeedRow(it, collapseStrongLanguage) })

        val city = state.city
        binding.cityButton.text = city?.let(ExperienceLabels::cityName) ?: getString(R.string.discover_masthead_no_city)
        binding.cityButton.contentDescription = city?.let {
            getString(R.string.discover_city_description, ExperienceLabels.cityName(it))
        } ?: getString(R.string.discover_masthead_no_city)
        cityChips.show(City.entries.filter { it != City.OTHER }.map { it to ExperienceLabels.cityName(it) }, city)
        binding.cityBlock.isVisible = (city == null && state.needsCity) || cityPickerOpen

        binding.panel.isVisible = searchOpen || nearOpen
        binding.searchPanel.isVisible = searchOpen
        binding.nearPanel.isVisible = nearOpen
        closePanel.isEnabled = searchOpen || nearOpen
        binding.searchToggle.contentDescription = getString(if (searchOpen) R.string.discover_search_close
            else R.string.discover_search_open)
        renderSearch(state)
        renderNear(state)

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        binding.retryButton.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.savedNow) {
            viewModel.onSavedShown()
            binding.feed.announceForAccessibility(getString(R.string.butterfly_saved_announcement))
            Haptics.confirm(binding.feed)
        }
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    private fun renderSearch(state: DiscoverUiState) {
        val query = state.query.trim()
        val categories = if (query.length < DiscoverViewModel.SEARCH_MIN) emptyList() else Interest.entries.filter {
            getString(SocialLabels.of(it)).contains(query, ignoreCase = true) || it.name.contains(query.replace(' ', '_'), ignoreCase = true)
        }
        categoryResults.submitList(categories.map { interest ->
            val label = getString(SocialLabels.of(interest))
            PersonRow(key = interest.name, title = label, subtitle = "", onOpen = { category(interest) },
                openDescription = getString(R.string.discover_open_category, label))
        })
        placeResults.submitList(state.places.map { placeRow(it) })
        binding.searchBlock.isVisible = state.searched || categories.isNotEmpty()
        binding.searchCategoriesTitle.isVisible = categories.isNotEmpty()
        binding.searchCategoryList.isVisible = categories.isNotEmpty()
        binding.searchNoPlaces.isVisible = state.searched && state.places.isEmpty()
        binding.clearSearchButton.isVisible = state.query.isNotEmpty() || state.searched
        binding.searchButton.isEnabled = query.length >= DiscoverViewModel.SEARCH_MIN && !state.searching
    }

    /** Phase 16: "Near me" results, or one notice with the one button that fixes it. */
    private fun renderNear(state: DiscoverUiState) {
        binding.nearProgress.isVisible = state.nearbyBusy
        val nearby = state.nearby
        nearResults.submitList(nearby.orEmpty().map { placeRow(it) })
        binding.nearList.isVisible = !nearby.isNullOrEmpty()
        binding.nearEmpty.isVisible = nearby != null && nearby.isEmpty()
        val notice = state.nearNotice
        binding.nearNotice.isVisible = notice != null
        val button = binding.nearActionButton
        button.isVisible = notice != null
        when (notice) {
            NearNotice.NEEDS_PERMISSION -> if (locationAsk.permanentlyDenied) {
                binding.nearNotice.setMessage(getString(R.string.social_location_denied_settings))
                button.setText(R.string.location_action_settings)
                button.setOnClickListener { retryNearOnReturn = true; LocationSettings.openAppSettings(this) }
            } else {
                binding.nearNotice.setMessage(getString(R.string.discover_near_permission))
                button.setText(R.string.action_allow_location)
                button.setOnClickListener { locationAsk.ask() }
            }
            NearNotice.LOCATION_OFF -> {
                binding.nearNotice.setMessage(getString(R.string.discover_near_location_off))
                button.setText(R.string.location_action_turn_on)
                button.setOnClickListener { retryNearOnReturn = true; LocationSettings.openLocationSettings(this) }
            }
            NearNotice.NOT_FOUND -> {
                binding.nearNotice.setMessage(getString(R.string.discover_near_not_found))
                button.setText(R.string.location_action_retry)
                button.setOnClickListener { viewModel.onNearMe() }
            }
            null -> Unit
        }
    }

    private fun placeRow(place: Place): PersonRow {
        val details = listOfNotNull(getString(SocialLabels.of(place.category)), place.city?.let(ExperienceLabels::cityName))
        return PersonRow(key = place.publicId, title = place.name, subtitle = details.joinToString(" · "),
            onOpen = { findNavController().navigate(R.id.postCollectionFragment,
                bundleOf(PostCollectionViewModel.ARG_PLACE_ID to place.publicId)) },
            openDescription = getString(R.string.discover_open_place, place.name))
    }

    // ---- FeedActions ----

    override fun openPost(post: Post, toComments: Boolean) = findNavController().navigate(R.id.postFragment,
        bundleOf(PostViewModel.ARG_POST_ID to post.publicId, PostViewModel.ARG_TO_COMMENTS to toComments))

    override fun save(post: Post) = viewModel.onSave(post)

    override fun why(item: DiscoverItem) {
        val lines = item.reasons.joinToString("\n") { "• " + getString(DiscoverTexts.of(it)) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discover_why_title)
            .setMessage(lines + "\n\n" + getString(R.string.discover_why_footer))
            .setPositiveButton(R.string.action_got_it, null)
            .show()
    }

    override fun nearMe() {
        nearOpen = true
        searchOpen = false
        if (appContainer.screenLocationProvider.access() == LocationAccess.NONE && !locationAsk.permanentlyDenied) {
            render(viewModel.uiState.value)
            locationAsk.ask()
        } else {
            viewModel.onNearMe()
        }
    }

    override fun category(interest: Interest) = findNavController().navigate(R.id.postCollectionFragment,
        bundleOf(PostCollectionViewModel.ARG_CATEGORY to interest.name,
            PostCollectionViewModel.ARG_CITY to viewModel.uiState.value.city?.name))

    override fun share() = findNavController().navigate(R.id.composeFragment)

    override fun findPeople() = findNavController().navigate(R.id.connectionsFragment, null, navOptions {
        launchSingleTop = true
    })

    override fun chooseCity() {
        cityPickerOpen = !cityPickerOpen
        render(viewModel.uiState.value)
    }

    private companion object {
        /** Start loading the next page this many rows before the end. */
        const val NEAR_END_ROWS = 4
    }
}
