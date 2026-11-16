package com.safesphere.app.feature.account.ui

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showCodeConfirmDialog
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentAccountBinding
import com.safesphere.app.feature.account.domain.DeletionNotice

/**
 * Account (Phase 10A, reworked in N2). The real name can be corrected (N-24). Signing out the other phones (N-26),
 * deleting the account (it waits 7 days; the banner shows the date) and cancelling that are confirmed with a fresh code
 * texted to the account's own number (N-7: no password any more). The window is FLAG_SECURE while it is open (codes,
 * and the deletion state is nobody else's business).
 */
class AccountFragment : ViewBindingFragment<FragmentAccountBinding>(FragmentAccountBinding::inflate) {

    private val viewModel by viewModels<AccountViewModel> { AccountViewModel.factory(appContainer) }
    private var codeDialog: AlertDialog? = null
    private var nameFilled = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        nameFilled = savedInstanceState != null
        binding.saveNameButton.setOnClickListener {
            binding.nameLayout.error = null
            viewModel.onSaveName(binding.nameInput.text?.toString().orEmpty())
        }
        binding.signOutOthersButton.setOnClickListener { viewModel.onAskCode(ConfirmedAction.SIGN_OUT_OTHERS) }
        binding.deleteAccountButton.setOnClickListener { viewModel.onAskCode(ConfirmedAction.DELETE) }
        binding.cancelDeletionButton.setOnClickListener { viewModel.onAskCode(ConfirmedAction.CANCEL_DELETION) }

        collectWhenStarted(viewModel.name) { name ->
            // Filled once from the account; the person's own typing is never overwritten. N3: a locked name cannot be
            // typed, so it always shows the account's name (a verifier's correction arrives after the first fill).
            if ((!nameFilled || viewModel.nameLocked.value) && name != null && binding.nameInput.text?.toString() != name) {
                binding.nameInput.setText(name)
                nameFilled = true
            }
        }
        // N3 (N-24): after the college ID check the name is read-only here; an IRL admin corrects it.
        collectWhenStarted(viewModel.nameLocked) { locked ->
            binding.nameLayout.isEnabled = !locked
            binding.saveNameButton.isVisible = !locked
            binding.nameLayout.helperText = if (locked) getString(R.string.membership_name_locked) else null
        }
        collectWhenStarted(viewModel.deletion, ::renderDeletion)
        collectWhenStarted(viewModel.busy) { busy ->
            binding.progress.isVisible = busy
            listOf(binding.saveNameButton, binding.signOutOthersButton, binding.deleteAccountButton,
                binding.cancelDeletionButton).forEach { it.isEnabled = !busy }
        }
        collectWhenStarted(viewModel.codeNeeded) { needed ->
            if (needed != null) {
                viewModel.onCodeDialogShown()
                askCode(needed)
            }
        }
        collectWhenStarted(viewModel.message) { message ->
            if (message != null) {
                show(message)
                viewModel.onMessageShown()
            }
        }
        collectWhenStarted(viewModel.sessionExpired) { expired ->
            if (expired) {
                viewModel.onSessionExpiredHandled()
                showSessionExpiredDialog()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.refreshNameLock()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
    }

    override fun onDestroyView() {
        codeDialog?.dismiss()
        codeDialog = null
        super.onDestroyView()
    }

    private fun renderDeletion(notice: DeletionNotice?) {
        binding.deletionNotice.isVisible = notice != null
        binding.cancelDeletionButton.isVisible = notice != null
        binding.deleteAccountButton.isVisible = notice == null
        if (notice != null) {
            val text = AccountTexts.deletionBanner(requireContext(), notice)
            binding.deletionNotice.setMessage(text)
            binding.deletionNotice.contentDescription = text
        }
    }

    private fun askCode(needed: CodeNeeded) {
        val (title, message, action) = when (needed.action) {
            ConfirmedAction.DELETE -> Triple(R.string.account_delete_confirm_title,
                R.string.account_delete_confirm_message, R.string.account_delete_confirm_action)
            ConfirmedAction.CANCEL_DELETION -> Triple(R.string.account_cancel_deletion,
                R.string.account_cancel_deletion_message, R.string.account_cancel_deletion)
            ConfirmedAction.SIGN_OUT_OTHERS -> Triple(R.string.account_sign_out_others,
                R.string.account_sign_out_others_message, R.string.account_sign_out_others_action)
        }
        codeDialog?.dismiss()
        codeDialog = showCodeConfirmDialog(title, message, action, needed.request,
            onConfirm = { viewModel.onConfirm(needed.action, it) },
            onResend = { viewModel.onAskCode(needed.action) },
            onDismiss = {})
    }

    private fun show(message: AccountMessage) {
        when (message) {
            AccountMessage.NameSaved -> toast(getString(R.string.account_name_saved))
            is AccountMessage.NameInvalid -> binding.nameLayout.error = message.error.toMessage(requireContext())
            AccountMessage.OthersSignedOut -> toast(getString(R.string.account_others_signed_out))
            is AccountMessage.DeletionScheduled -> toast(getString(R.string.account_deletion_requested,
                AccountTexts.date(message.dueAt)))
            AccountMessage.DeletionCancelled -> toast(getString(R.string.account_deletion_cancelled))
            is AccountMessage.Failed -> toast(message.failure.toMessage(requireContext()))
        }
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    }
}
