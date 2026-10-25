package com.safesphere.app.feature.social.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentDiscoverBinding
import com.safesphere.app.feature.social.domain.City
import com.safesphere.app.feature.social.domain.DiscoverItem
import com.safesphere.app.feature.social.domain.EditionSectionKind
import com.safesphere.app.feature.social.domain.Interest
import com.safesphere.app.feature.social.domain.Place
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Phase 13C, redesigned in 13F (docs/PHASE13F_PLAN.md F14): Discover, the first tab. The Edition reads like a small
 * weekly for one city: a masthead ("This week in Chennai"), short sections of postcards and question slips, and an end
 * ("Go make your own"). From your connections ends too. Every Edition card answers "Why am I seeing this?". Search
 * finds places by name and categories by their words, never people. [DiscoverViewModel] is unchanged.
 */
class DiscoverFragment : ViewBindingFragment<FragmentDiscoverBinding>(FragmentDiscoverBinding::inflate) {

    private val viewModel by viewModels<DiscoverViewModel> { DiscoverViewModel.factory(appContainer) }

    private lateinit var cityAdapter: ExperienceCardAdapter
    private lateinit var asksAdapter: ExperienceCardAdapter
    private lateinit var themeAdapter: ExperienceCardAdapter
    private lateinit var connectionsAdapter: ExperienceCardAdapter
    private val placeResults = PersonRowAdapter()
    private val categoryResults = PersonRowAdapter()
    private lateinit var cityChips: ChoiceChips<City>
    private var cityPickerOpen = false
    private var searchOpen = false
    private var collapseStrongLanguage = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val loader = appContainer.imageLoader
        val photoUrl = appContainer.experienceRepository::photoUrl
        cityAdapter = ExperienceCardAdapter(loader, photoUrl)
        asksAdapter = ExperienceCardAdapter(loader, photoUrl)
        themeAdapter = ExperienceCardAdapter(loader, photoUrl)
        connectionsAdapter = ExperienceCardAdapter(loader, photoUrl)
        binding.citySectionList.adapter = cityAdapter
        binding.asksSectionList.adapter = asksAdapter
        binding.themeSectionList.adapter = themeAdapter
        binding.connectionsList.adapter = connectionsAdapter
        binding.searchPlaceList.adapter = placeResults
        binding.searchCategoryList.adapter = categoryResults
        cityChips = ChoiceChips(binding.cityGroup) { city ->
            if (city != null) {
                cityPickerOpen = false
                viewModel.onCity(city)
            }
        }
        binding.cityTitle.setOnClickListener {
            cityPickerOpen = !cityPickerOpen
            render(viewModel.uiState.value)
        }
        binding.searchToggle.setOnClickListener {
            searchOpen = !searchOpen
            if (searchOpen) binding.searchInput.requestFocus()
            render(viewModel.uiState.value)
        }
        binding.modeTabs.setTabs(listOf(getString(R.string.discover_tab_edition), getString(R.string.discover_tab_connections)),
            selected = 0) { index -> viewModel.onMode(if (index == 1) DiscoverMode.CONNECTIONS else DiscoverMode.EDITION) }
        binding.connectionsShowMore.setOnClickListener { viewModel.onShowMoreConnections() }
        binding.editionShareButton.setOnClickListener { findNavController().navigate(R.id.composeFragment) }
        binding.editionCocoonButton.setOnClickListener {
            findNavController().navigate(R.id.cocoonFragment, null, navOptions {
                launchSingleTop = true
                restoreState = true
                popUpTo(R.id.discoverFragment) { saveState = true }
            })
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
        Interest.entries.forEach { interest ->
            binding.categoryGroup.addView(Chip(requireContext()).apply {
                text = getString(SocialLabels.of(interest))
                isCheckable = false
                contentDescription = getString(R.string.discover_open_category, text)
                setOnClickListener { openCategory(interest) }
            })
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

    private fun render(state: DiscoverUiState) {
        val edition = state.mode == DiscoverMode.EDITION
        if (binding.modeTabs.selectedIndex != (if (edition) 0 else 1)) {
            binding.modeTabs.setTabs(listOf(getString(R.string.discover_tab_edition),
                getString(R.string.discover_tab_connections)), if (edition) 0 else 1) { index ->
                viewModel.onMode(if (index == 1) DiscoverMode.CONNECTIONS else DiscoverMode.EDITION)
            }
        }
        binding.editionBlock.isVisible = edition
        binding.connectionsBlock.isVisible = !edition
        binding.progress.isVisible = state.editionLoading || state.connectionsLoading || state.searching

        renderMasthead(state)

        val e = state.edition
        if (e != null) {
            binding.citySectionTitle.text = getString(R.string.discover_section_city, ExperienceLabels.cityName(e.city))
        }
        section(e?.sections?.firstOrNull { it.kind == EditionSectionKind.CITY }?.items, binding.citySectionTitle,
            binding.citySectionList, cityAdapter)
        section(e?.sections?.firstOrNull { it.kind == EditionSectionKind.ASKS }?.items, binding.asksSectionTitle,
            binding.asksSectionList, asksAdapter)
        section(e?.sections?.firstOrNull { it.kind == EditionSectionKind.THEME }?.items, binding.themeSectionTitle,
            binding.themeSectionList, themeAdapter)
        binding.editionEmpty.isVisible = e != null && e.isEmpty
        binding.editionEndCard.isVisible = e != null && !e.isEmpty

        connectionsAdapter.submitList(state.connections.map { row(it, withWhy = false) })
        binding.connectionsEmpty.isVisible = state.connectionsLoaded && state.connections.isEmpty()
        binding.connectionsShowMore.isVisible = state.connectionsHasMore
        binding.connectionsShowMore.isEnabled = !state.connectionsLoading
        binding.connectionsEnd.isVisible = state.connectionsLoaded && state.connections.isNotEmpty() && !state.connectionsHasMore

        renderSearch(state)

        val failure = state.failure
        binding.errorNotice.isVisible = failure != null
        if (failure != null) binding.errorNotice.setMessage(failure.toMessage(requireContext()))
        if (state.sessionExpired) {
            viewModel.onSessionExpiredHandled()
            showSessionExpiredDialog()
        }
    }

    /** "This week in / Chennai / Week of 19 Oct 2026 · Theme: Hidden gems". The city chips open from the city's name. */
    private fun renderMasthead(state: DiscoverUiState) {
        val city = state.city
        binding.cityTitle.text = city?.let(ExperienceLabels::cityName) ?: getString(R.string.discover_masthead_no_city)
        binding.cityTitle.contentDescription = city?.let {
            getString(R.string.discover_city_description, ExperienceLabels.cityName(it))
        } ?: getString(R.string.discover_masthead_no_city)
        cityChips.show(City.entries.filter { it != City.OTHER }.map { it to ExperienceLabels.cityName(it) }, city)
        binding.cityBlock.isVisible = city == null || cityPickerOpen
        binding.cityHint.isVisible = state.needsCity && city == null

        val week = state.edition?.weekOf
            ?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
        val theme = state.edition?.theme
        binding.mastheadSub.isVisible = week != null
        binding.mastheadSub.text = when {
            week == null -> ""
            theme != null -> getString(R.string.discover_masthead_sub_theme, week, getString(SocialLabels.of(theme)))
            else -> getString(R.string.discover_masthead_sub, week)
        }
    }

    private fun renderSearch(state: DiscoverUiState) {
        binding.searchPanel.isVisible = searchOpen
        binding.searchToggle.contentDescription = getString(if (searchOpen) R.string.discover_search_close
            else R.string.discover_search_open)
        val query = state.query.trim()
        val categories = if (query.length < DiscoverViewModel.SEARCH_MIN) emptyList() else Interest.entries.filter {
            getString(SocialLabels.of(it)).contains(query, ignoreCase = true) || it.name.contains(query.replace(' ', '_'), ignoreCase = true)
        }
        categoryResults.submitList(categories.map { interest ->
            val label = getString(SocialLabels.of(interest))
            PersonRow(key = interest.name, title = label, subtitle = "", onOpen = { openCategory(interest) },
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

    private fun section(items: List<DiscoverItem>?, title: View, list: RecyclerView, adapter: ExperienceCardAdapter) {
        val shown = !items.isNullOrEmpty()
        title.isVisible = shown
        list.isVisible = shown
        adapter.submitList(items.orEmpty().map { row(it, withWhy = true) })
    }

    private fun row(item: DiscoverItem, withWhy: Boolean): CardRow {
        val card = ExperienceCards.of(item.post, item.reasons, collapseStrongLanguage)
        return CardRow(card, onOpen = { openPost(item.post.publicId) },
            onWhy = if (withWhy && item.reasons.isNotEmpty()) ({ explain(item) }) else null)
    }

    private fun placeRow(place: Place): PersonRow {
        val details = listOfNotNull(getString(SocialLabels.of(place.category)), place.city?.let(ExperienceLabels::cityName))
        return PersonRow(key = place.publicId, title = place.name, subtitle = details.joinToString(" · "),
            onOpen = { findNavController().navigate(R.id.postCollectionFragment,
                bundleOf(PostCollectionViewModel.ARG_PLACE_ID to place.publicId)) },
            openDescription = getString(R.string.discover_open_place, place.name))
    }

    private fun explain(item: DiscoverItem) {
        val lines = item.reasons.joinToString("\n") { "• " + getString(DiscoverTexts.of(it)) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.discover_why_title)
            .setMessage(lines + "\n\n" + getString(R.string.discover_why_footer))
            .setPositiveButton(R.string.action_got_it, null)
            .show()
    }

    private fun openPost(postId: String) =
        findNavController().navigate(R.id.postFragment, bundleOf(PostViewModel.ARG_POST_ID to postId))

    private fun openCategory(interest: Interest) = findNavController().navigate(R.id.postCollectionFragment,
        bundleOf(PostCollectionViewModel.ARG_CATEGORY to interest.name,
            PostCollectionViewModel.ARG_CITY to viewModel.uiState.value.city?.name))
}
