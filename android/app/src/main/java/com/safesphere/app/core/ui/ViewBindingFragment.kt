package com.safesphere.app.core.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewbinding.ViewBinding

/**
 * Removes the repeated "nullable binding" boilerplate from every Fragment.
 * The binding is only valid between onCreateView and onDestroyView, which avoids memory leaks.
 *
 * IRL (2026-11-12): a screen of the social side ([SocialPalette.appliesTo]) gets a context that wears the social
 * palette, so its views AND everything it opens with that context (sheets, dialogs, list rows) share the colours. Every
 * other screen gets the activity's context untouched: safety screens are exactly as before.
 */
abstract class ViewBindingFragment<VB : ViewBinding>(
    private val inflate: (LayoutInflater, ViewGroup?, Boolean) -> VB
) : Fragment() {

    private var _binding: VB? = null
    protected val binding: VB
        get() = checkNotNull(_binding) { "Binding accessed outside of the view lifecycle" }

    private val wearsSocialPalette = SocialPalette.appliesTo(javaClass.name)
    private var paletteBase: Context? = null
    private var paletteContext: Context? = null

    override fun getContext(): Context? {
        val base = super.getContext() ?: return null
        if (!wearsSocialPalette) return base
        // One wrapper per host context (a new activity after a rotation or a day/night change gets a new one).
        if (paletteBase !== base) {
            paletteBase = base
            paletteContext = SocialPalette.wrap(base)
        }
        return paletteContext
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return if (wearsSocialPalette) inflater.cloneInContext(requireContext()) else inflater
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDetach() {
        super.onDetach()
        paletteBase = null
        paletteContext = null
    }
}
