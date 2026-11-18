package com.safesphere.app.feature.safetycircle.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.databinding.FragmentCircleSosBinding
import com.safesphere.app.feature.safetycircle.data.CircleSos
import com.safesphere.app.feature.safetycircle.data.CircleSosStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * N7 (D36): SOS from your people. The facts of the text (who, when, the map link the texts carried, where it stands),
 * Call them and Call 112 (the phone's dialer; nothing is called by itself). Read while visible; nothing stored.
 */
class CircleSosFragment : ViewBindingFragment<FragmentCircleSosBinding>(FragmentCircleSosBinding::inflate) {

    private val viewModel: CircleSosViewModel by viewModels { CircleSosViewModel.factory(appContainer) }

    private val emergencyId: Long get() = requireArguments().getLong(ARG_EMERGENCY_ID)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.call112Button.setOnClickListener { dial("112") }
        collectWhenStarted(viewModel.state, ::render)
    }

    override fun onStart() {
        super.onStart()
        viewModel.watch(emergencyId)
        appContainer.pushTray.cancel("circle-sos-$emergencyId")
    }

    override fun onStop() {
        viewModel.stop()
        super.onStop()
    }

    private fun render(state: CircleSosState) {
        binding.progress.isVisible = state is CircleSosState.Loading
        val sos = (state as? CircleSosState.Shown)?.sos
        binding.sosCard.isVisible = sos != null
        binding.locationLine.isVisible = sos != null
        binding.mapButton.isVisible = sos?.mapLink != null
        binding.callButton.isVisible = sos?.phoneE164 != null
        val message = when (state) {
            CircleSosState.Gone -> getString(R.string.circle_sos_gone)
            CircleSosState.Failed -> getString(R.string.circles_load_failed)
            is CircleSosState.Shown -> if (state.stale) getString(R.string.circles_load_failed) else null
            CircleSosState.Loading -> null
        }
        binding.notice.isVisible = message != null
        message?.let { binding.notice.setMessage(it) }
        if (sos != null) show(sos)
    }

    private fun show(sos: CircleSos) {
        binding.sosTitle.text = getString(R.string.circle_sos_title, sos.name)
        binding.sosStarted.text = getString(R.string.circle_sos_started, time(sos.startedAt))
        binding.sosStatus.text = when (sos.status) {
            CircleSosStatus.ACTIVE -> getString(R.string.circle_sos_status_active)
            CircleSosStatus.SAFE -> getString(R.string.circle_sos_status_safe, sos.name)
            CircleSosStatus.FALSE_ALARM -> getString(R.string.circle_sos_status_false_alarm, sos.name)
            CircleSosStatus.ENDED -> getString(R.string.circle_sos_status_ended)
        }
        binding.sosLate.isVisible = sos.sentLate
        binding.locationLine.text = if (sos.mapLink != null && sos.locationAt != null)
            getString(R.string.circle_sos_location, time(sos.locationAt)) else getString(R.string.circle_sos_no_location)
        binding.mapButton.setOnClickListener {
            sos.mapLink?.let { link -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) } }
        }
        binding.callButton.text = getString(R.string.circle_sos_call, sos.name)
        binding.callButton.setOnClickListener { sos.phoneE164?.let(::dial) }
    }

    private fun dial(number: String) {
        runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))) }
    }

    private fun time(at: Instant): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(at)

    companion object {
        const val ARG_EMERGENCY_ID = "emergencyId"

        fun args(emergencyId: Long) = bundleOf(ARG_EMERGENCY_ID to emergencyId)
    }
}
