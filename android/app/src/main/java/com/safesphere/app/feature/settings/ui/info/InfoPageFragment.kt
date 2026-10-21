package com.safesphere.app.feature.settings.ui.info

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.safesphere.app.BuildConfig
import com.safesphere.app.R
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.databinding.FragmentInfoPageBinding

/**
 * A simple read-only text page. The toolbar title comes from the destination label in nav_graph.xml.
 * Terms and Privacy Policy are placeholders until real documents are written.
 */
abstract class InfoPageFragment(
    @StringRes private val bodyRes: Int,
    private val showVersion: Boolean = false
) : ViewBindingFragment<FragmentInfoPageBinding>(FragmentInfoPageBinding::inflate) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.infoBody.setText(bodyRes)
        binding.versionText.isVisible = showVersion
        binding.versionText.text = getString(R.string.about_version_format, BuildConfig.VERSION_NAME)
    }
}

class AboutFragment : InfoPageFragment(R.string.about_body, showVersion = true)

class TermsFragment : InfoPageFragment(R.string.terms_body)

class PrivacyPolicyFragment : InfoPageFragment(R.string.privacy_policy_body)
