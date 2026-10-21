package com.safesphere.app.feature.account.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentAccountBinding
import com.safesphere.app.feature.account.domain.DeletionNotice
import com.safesphere.app.feature.account.domain.PasswordProblem

/**
 * Account and security (Phase 10A). Changing the password ends every other session; deleting the account waits
 * 7 days (the banner shows the date; cancelling needs the password). The window is FLAG_SECURE while it is open
 * (password fields, and the deletion state is nobody else's business).
 */
class AccountFragment : ViewBindingFragment<FragmentAccountBinding>(FragmentAccountBinding::inflate) {

    private val viewModel by viewModels<AccountViewModel> { AccountViewModel.factory(appContainer) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.changePasswordButton.setOnClickListener {
            val current = binding.currentPasswordInput.text?.toString().orEmpty()
            val new = binding.newPasswordInput.text?.toString().orEmpty()
            val confirm = binding.confirmPasswordInput.text?.toString().orEmpty()
            viewModel.onChangePassword(current, new, confirm)
        }
        binding.deleteAccountButton.setOnClickListener {
            askPassword(R.string.account_delete_confirm_title, R.string.account_delete_confirm_message,
                R.string.account_delete_confirm_action) { viewModel.onRequestDeletion(it) }
        }
        binding.cancelDeletionButton.setOnClickListener {
            askPassword(R.string.account_cancel_deletion, R.string.account_cancel_deletion_message,
                R.string.account_cancel_deletion) { viewModel.onCancelDeletion(it) }
        }

        collectWhenStarted(viewModel.deletion, ::renderDeletion)
        collectWhenStarted(viewModel.busy) { busy ->
            binding.progress.isVisible = busy
            binding.changePasswordButton.isEnabled = !busy
            binding.deleteAccountButton.isEnabled = !busy
            binding.cancelDeletionButton.isEnabled = !busy
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

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onPause()
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

    private fun show(message: AccountMessage) {
        when (message) {
            AccountMessage.PasswordChanged -> {
                clearPasswordFields()
                toast(getString(R.string.account_password_changed))
            }
            is AccountMessage.DeletionScheduled -> toast(getString(R.string.account_deletion_requested,
                AccountTexts.date(message.dueAt)))
            AccountMessage.DeletionCancelled -> toast(getString(R.string.account_deletion_cancelled))
            AccountMessage.PasswordRequired -> toast(getString(R.string.account_password_required))
            is AccountMessage.Invalid -> showProblem(message.problem)
            is AccountMessage.Failed -> toast(message.failure.toMessage(requireContext()))
        }
    }

    private fun showProblem(problem: PasswordProblem) {
        binding.currentPasswordLayout.error = null
        binding.newPasswordLayout.error = null
        binding.confirmPasswordLayout.error = null
        when (problem) {
            PasswordProblem.CURRENT_MISSING -> binding.currentPasswordLayout.error = getString(R.string.error_field_required)
            PasswordProblem.TOO_SHORT -> binding.newPasswordLayout.error =
                getString(R.string.error_password_too_short, com.safesphere.app.feature.account.domain.AccountPolicy.MIN_PASSWORD_LENGTH)
            PasswordProblem.TOO_LONG -> binding.newPasswordLayout.error = getString(R.string.account_password_too_long)
            PasswordProblem.MISMATCH -> binding.confirmPasswordLayout.error = getString(R.string.error_passwords_do_not_match)
            PasswordProblem.SAME_AS_CURRENT -> binding.newPasswordLayout.error = getString(R.string.account_password_same)
        }
    }

    private fun clearPasswordFields() {
        listOf(binding.currentPasswordInput, binding.newPasswordInput, binding.confirmPasswordInput).forEach { it.setText("") }
        listOf(binding.currentPasswordLayout, binding.newPasswordLayout, binding.confirmPasswordLayout).forEach { it.error = null }
    }

    /** A password prompt over an explanation; the typed password goes to [onConfirm] and the field is cleared. */
    private fun askPassword(titleRes: Int, messageRes: Int, actionRes: Int, onConfirm: (String) -> Unit) {
        val context = requireContext()
        val field = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val layout = TextInputLayout(context).apply {
            hint = getString(R.string.label_password)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(field)
            val padding = resources.getDimensionPixelSize(R.dimen.space_lg)
            setPadding(padding, 0, padding, 0)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(actionRes) { _, _ ->
                val password = field.text?.toString().orEmpty()
                field.setText("")
                onConfirm(password)
            }
            .create()
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    private fun toast(text: String) {
        Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
    }
}
