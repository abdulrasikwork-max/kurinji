package com.safesphere.app.feature.evidence.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.MediaController
import android.widget.PopupMenu
import android.widget.Toast
import android.widget.VideoView
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.safesphere.app.R
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.ScreenLockGate
import com.safesphere.app.core.ui.ViewBindingFragment
import com.safesphere.app.core.ui.collectWhenStarted
import com.safesphere.app.core.ui.showSessionExpiredDialog
import com.safesphere.app.core.ui.toMessage
import com.safesphere.app.databinding.FragmentEvidenceVaultBinding
import com.safesphere.app.feature.emergency.ui.EmergencyTimeFormatter
import com.safesphere.app.feature.evidence.domain.AnchorStatus
import com.safesphere.app.feature.evidence.domain.EvidenceKind
import com.safesphere.app.feature.evidence.domain.PassphraseProblem
import com.safesphere.app.feature.evidence.session.IntegrityResult
import com.safesphere.app.feature.evidence.session.RecoveryResult
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.safesphere.app.feature.evidence.session.LocalProblemReason
import com.safesphere.app.feature.evidence.session.VaultActionResult
import com.safesphere.app.feature.evidence.session.VaultCopyCheck
import java.io.File

/**
 * The Evidence Vault (Phase 5). Lists one emergency's evidence (or all of the person's), opens it in the app only
 * (decrypted into a private temporary file that is deleted when the viewer closes), and offers verify / remove from
 * phone / bring back / delete everywhere. Deleting needs the account password and is refused during an active
 * emergency (server rule). The window is FLAG_SECURE while open: no screenshots, no screen recording, and a blank
 * preview in the recent-apps list.
 */
class EvidenceVaultFragment : ViewBindingFragment<FragmentEvidenceVaultBinding>(FragmentEvidenceVaultBinding::inflate) {

    private val emergencyId: Long get() = arguments?.getLong(ARG_EMERGENCY_ID, -1L) ?: -1L

    private val viewModel by viewModels<EvidenceVaultViewModel> { EvidenceVaultViewModel.factory(appContainer, emergencyId) }

    private val recoveryViewModel by viewModels<EvidenceRecoveryViewModel> { EvidenceRecoveryViewModel.factory(appContainer) }

    private val adapter = EvidenceItemAdapter(onOpen = { viewModel.onOpen(it.clientRef) }, onMore = ::showMenu)
    private var viewer: Dialog? = null
    private var recoveryDialog: Dialog? = null
    private var recoveryRefreshed = false
    private var lastRecoveryMode: RecoveryMode? = null

    /** The phone's own screen lock guards viewing (see VaultLock). */
    private val screenLock = ScreenLockGate(this) { result ->
        askingScreenLock = false
        viewModel.onScreenLockResult(result)
    }

    /** True while Android's screen-lock prompt is open (it may stop this screen; that must not re-lock it). */
    private var askingScreenLock = false
    /** Ask automatically once each time the screen becomes visible; after "cancel" the button asks again. */
    private var askedSinceStart = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.vaultList.adapter = adapter
        binding.vaultRefresh.setOnClickListener { viewModel.refresh() }
        binding.vaultUnlock.setOnClickListener { askScreenLock() }
        binding.vaultSetScreenLock.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
        }

        collectWhenStarted(viewModel.uiState) { state ->
            binding.vaultLockedPanel.isVisible = state.locked
            binding.vaultContent.isVisible = !state.locked
            binding.vaultUnprotected.isVisible = !state.locked && state.unprotected
            if (state.locked) {
                viewer?.dismiss()
                viewer = null
                adapter.submitList(emptyList())
                return@collectWhenStarted
            }
            adapter.submitList(state.rows)
            adapter.busyRef = state.busyRef
            binding.vaultEmpty.isVisible = state.rows.isEmpty()
            val custody = state.custody
            binding.vaultCustody.isVisible = custody != null || state.custodyUnavailable
            binding.vaultCustody.text = when {
                custody == null -> getString(R.string.vault_custody_unavailable)
                custody.intact -> resources.getQuantityString(R.plurals.vault_custody_intact, custody.chainLength.toInt(),
                    custody.chainLength.toInt(), EmergencyTimeFormatter.format(custody.verifiedAt))
                else -> getString(R.string.vault_custody_broken)
            }
            binding.vaultCustodyAnchor.isVisible = custody != null
            binding.vaultCustodyAnchor.text = when (val anchor = custody?.anchor) {
                null -> ""
                AnchorStatus.FirstCheck -> getString(R.string.vault_custody_anchor_first)
                is AnchorStatus.Matches -> getString(R.string.vault_custody_anchor_match, EmergencyTimeFormatter.format(anchor.since))
                is AnchorStatus.Changed -> getString(R.string.vault_custody_anchor_changed, EmergencyTimeFormatter.format(anchor.since))
            }
        }
        collectWhenStarted(viewModel.event) { event ->
            if (event != null) {
                viewModel.onEventHandled()
                handle(event)
            }
        }
        collectWhenStarted(viewModel.sessionExpired) { expired ->
            if (expired) {
                viewModel.onSessionExpiredHandled()
                showSessionExpiredDialog()
            }
        }
        bindRecovery()
    }

    // ---- restore on a new phone (recovery passphrase, docs/PHASE5.md §11) -------------------------------

    private fun bindRecovery() {
        binding.recoverySetUp.setOnClickListener { showRecoveryDialog(RecoveryMode.SET_UP) }
        binding.recoveryRestore.setOnClickListener { showRecoveryDialog(RecoveryMode.RESTORE) }
        binding.recoveryChange.setOnClickListener { showRecoveryDialog(RecoveryMode.CHANGE) }
        binding.recoveryForgot.setOnClickListener { showRecoveryDialog(RecoveryMode.FORGOT) }
        collectWhenStarted(viewModel.uiState) { state ->
            // Refresh the recovery section every time the vault opens.
            if (!state.locked && !recoveryRefreshed) {
                recoveryRefreshed = true
                recoveryViewModel.refresh()
            }
            if (state.locked) recoveryRefreshed = false
        }
        collectWhenStarted(recoveryViewModel.state) { state ->
            binding.recoveryStatus.text = when {
                state.unavailable -> getString(R.string.recovery_status_unavailable)
                !state.statusKnown -> ""
                !state.configured -> getString(R.string.recovery_status_off)
                !state.keyOnPhone -> getString(R.string.recovery_status_on_elsewhere)
                else -> getString(R.string.recovery_status_on, state.updatedAt?.let(EmergencyTimeFormatter::format).orEmpty(),
                    state.protectedItems, state.vaultItems)
            }
            binding.recoveryLastFetched.isVisible = state.configured && state.lastDownloadedAt != null
            binding.recoveryLastFetched.text = state.lastDownloadedAt
                ?.let { getString(R.string.recovery_last_fetched, EmergencyTimeFormatter.format(it)) }.orEmpty()
            binding.recoverySetUp.isVisible = state.canSetUp
            binding.recoveryRestore.isVisible = state.canRestore
            binding.recoveryChange.isVisible = state.canChange
            binding.recoveryForgot.isVisible = state.canChange
            listOf(binding.recoverySetUp, binding.recoveryRestore, binding.recoveryChange, binding.recoveryForgot)
                .forEach { it.isEnabled = !state.busy }
        }
        collectWhenStarted(recoveryViewModel.event) { event ->
            if (event != null) {
                recoveryViewModel.onEventHandled()
                handleRecovery(event)
            }
        }
        collectWhenStarted(recoveryViewModel.sessionExpired) { expired ->
            if (expired) {
                recoveryViewModel.onSessionExpiredHandled()
                showSessionExpiredDialog()
            }
        }
    }

    private enum class RecoveryMode { SET_UP, RESTORE, CHANGE, FORGOT }

    /**
     * One dialog for the four recovery actions. FLAG_SECURE; no autofill; field contents are read as CharArrays and
     * cleared at once. The suggested passphrase is shown only inside this dialog.
     */
    private fun showRecoveryDialog(mode: RecoveryMode) {
        val view = layoutInflater.inflate(R.layout.dialog_recovery_passphrase, null)
        val message = view.findViewById<android.widget.TextView>(R.id.recoveryDialogMessage)
        val suggestionBlock = view.findViewById<View>(R.id.recoverySuggestionBlock)
        val suggestion = view.findViewById<android.widget.TextView>(R.id.recoverySuggestion)
        val current = view.findViewById<TextInputEditText>(R.id.recoveryCurrent)
        val passphrase = view.findViewById<TextInputEditText>(R.id.recoveryPassphrase)
        val confirm = view.findViewById<TextInputEditText>(R.id.recoveryConfirm)
        val password = view.findViewById<TextInputEditText>(R.id.recoveryPassword)
        val (title, text) = when (mode) {
            RecoveryMode.SET_UP -> R.string.recovery_set_up_title to R.string.recovery_set_up_message
            RecoveryMode.RESTORE -> R.string.recovery_restore_title to R.string.recovery_restore_message
            RecoveryMode.CHANGE -> R.string.recovery_change_title to R.string.recovery_change_message
            RecoveryMode.FORGOT -> R.string.recovery_forgot_title to R.string.recovery_forgot_message
        }
        message.setText(text)
        view.findViewById<View>(R.id.recoveryCurrentLayout).isVisible = mode == RecoveryMode.CHANGE
        view.findViewById<View>(R.id.recoveryConfirmLayout).isVisible = mode != RecoveryMode.RESTORE
        val choosing = mode != RecoveryMode.RESTORE
        suggestionBlock.isVisible = choosing
        if (choosing) {
            val show = {
                viewLifecycleOwner.lifecycleScope.launch {
                    val words = recoveryViewModel.suggest()
                    suggestion.text = String(words)
                    words.fill(' ')
                }
            }
            show()
            view.findViewById<View>(R.id.recoveryAnotherSuggestion).setOnClickListener { show() }
            view.findViewById<View>(R.id.recoveryUseSuggestion).setOnClickListener {
                passphrase.setText(suggestion.text)
                confirm.setText(suggestion.text)
            }
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_continue, null)
            .create()
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnShowListener {
            dialog.getButton(Dialog.BUTTON_POSITIVE).setOnClickListener {
                val accountPassword = password.chars()
                if (accountPassword.isEmpty()) {
                    toast(R.string.recovery_password_required)
                    return@setOnClickListener
                }
                lastRecoveryMode = mode
                when (mode) {
                    RecoveryMode.SET_UP -> recoveryViewModel.onSetUp(passphrase.chars(), confirm.chars(), accountPassword)
                    RecoveryMode.RESTORE -> recoveryViewModel.onRestore(passphrase.chars(), accountPassword)
                    RecoveryMode.CHANGE -> recoveryViewModel.onChange(current.chars(), passphrase.chars(), confirm.chars(), accountPassword)
                    RecoveryMode.FORGOT -> recoveryViewModel.onForgot(passphrase.chars(), confirm.chars(), accountPassword)
                }
                listOf(current, passphrase, confirm, password).forEach { it.text?.clear() }
                suggestion.text = ""
                dialog.dismiss()
            }
        }
        dialog.setOnDismissListener {
            listOf(current, passphrase, confirm, password).forEach { it.text?.clear() }
            suggestion.text = ""
            if (recoveryDialog === dialog) recoveryDialog = null
        }
        recoveryDialog = dialog
        dialog.show()
    }

    /** The field's characters as a CharArray (the caller wipes it); the Editable is cleared right after. */
    private fun TextInputEditText.chars(): CharArray {
        val editable = text ?: return CharArray(0)
        return CharArray(editable.length).also { editable.getChars(0, editable.length, it, 0) }
    }

    private fun handleRecovery(event: RecoveryEvent) {
        val text = when (event) {
            is RecoveryEvent.Problem -> getString(
                when (event.problem) {
                    PassphraseProblem.TOO_SHORT -> R.string.recovery_problem_too_short
                    PassphraseProblem.PERSONAL -> R.string.recovery_problem_personal
                    PassphraseProblem.SAME_AS_PASSWORD -> R.string.recovery_problem_same_as_password
                    PassphraseProblem.COMMON -> R.string.recovery_problem_common
                    PassphraseProblem.MISMATCH -> R.string.recovery_problem_mismatch
                }
            )
            is RecoveryEvent.Result -> when (val result = event.result) {
                RecoveryResult.Done -> getString(
                    if (lastRecoveryMode == RecoveryMode.SET_UP) R.string.recovery_done else R.string.recovery_changed
                )
                is RecoveryResult.Restored -> buildString {
                    append(resources.getQuantityString(R.plurals.recovery_restored, result.restored, result.restored))
                    if (result.unreadable > 0) {
                        append("\n\n")
                        append(resources.getQuantityString(R.plurals.recovery_unreadable, result.unreadable, result.unreadable))
                    }
                }
                RecoveryResult.WrongPassphrase -> getString(R.string.recovery_wrong_passphrase)
                RecoveryResult.NotSetUp -> getString(R.string.recovery_not_set_up)
                RecoveryResult.AlreadySetUp -> getString(R.string.recovery_already_set_up)
                RecoveryResult.NoKeyOnPhone -> getString(R.string.recovery_no_key_on_phone)
                RecoveryResult.LocalFailure -> getString(R.string.recovery_local_failure)
                is RecoveryResult.Failed -> result.failure.toMessage(requireContext())
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.section_recovery)
            .setMessage(text)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        if (!askedSinceStart && !askingScreenLock && viewModel.uiState.value.locked) {
            askedSinceStart = true
            askScreenLock()
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

    override fun onDestroyView() {
        screenLock.cancel()
        askingScreenLock = false
        viewer?.dismiss()
        viewer = null
        super.onDestroyView()
    }

    private fun askScreenLock() {
        askingScreenLock = true
        screenLock.ask()
    }

    private fun handle(event: VaultEvent) {
        when (event) {
            is VaultEvent.Open -> openViewer(event.clientRef, event.kind, event.file)
            is VaultEvent.Integrity -> showIntegrity(event.result)
            VaultEvent.OpenFailed -> toast(R.string.vault_open_failed)
            VaultEvent.KeyUnavailable -> toast(R.string.vault_key_unavailable)
            is VaultEvent.Result -> when (val result = event.result) {
                VaultActionResult.Done -> toast(R.string.vault_action_done)
                is VaultActionResult.Failed -> Toast.makeText(requireContext(), result.failure.toMessage(requireContext()), Toast.LENGTH_LONG).show()
                is VaultActionResult.LocalProblem -> toast(
                    when (result.reason) {
                        LocalProblemReason.FILE_DAMAGED -> R.string.vault_verify_failed
                        LocalProblemReason.KEY_UNAVAILABLE -> R.string.vault_key_unavailable
                        LocalProblemReason.NOT_ALLOWED -> R.string.vault_action_not_possible
                        LocalProblemReason.VAULT_COPY_DAMAGED -> R.string.vault_copy_damaged_kept
                    }
                )
            }
        }
    }

    /** Both copies, in words: the phone's (hash recorded at sealing) and the vault's (re-hashed by the server now). */
    private fun showIntegrity(result: IntegrityResult) {
        val lines = buildList {
            when (result.phone) {
                true -> add(getString(R.string.vault_integrity_phone_ok))
                false -> add(getString(R.string.vault_integrity_phone_damaged))
                null -> Unit
            }
            add(
                getString(
                    when (result.vault) {
                        VaultCopyCheck.INTACT -> R.string.vault_integrity_vault_ok
                        VaultCopyCheck.DAMAGED_REUPLOADING -> R.string.vault_integrity_vault_damaged_repairing
                        VaultCopyCheck.DAMAGED -> R.string.vault_integrity_vault_damaged
                        VaultCopyCheck.NOT_UPLOADED -> R.string.vault_integrity_vault_not_uploaded
                        VaultCopyCheck.UNAVAILABLE -> R.string.vault_integrity_vault_unavailable
                    }
                )
            )
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.vault_integrity_title)
            .setMessage(lines.joinToString("\n\n"))
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun showMenu(row: VaultRow, anchor: View) {
        val menu = PopupMenu(requireContext(), anchor)
        if (row.canVerify) menu.menu.add(0, MENU_VERIFY, 0, R.string.action_verify_evidence)
        if (row.canRemoveFromPhone) menu.menu.add(0, MENU_REMOVE, 1, R.string.action_remove_from_phone)
        if (row.canDownload) menu.menu.add(0, MENU_DOWNLOAD, 2, R.string.action_download_evidence)
        if (row.canRetryUpload) menu.menu.add(0, MENU_RETRY, 3, R.string.action_retry_upload)
        if (row.canDelete) menu.menu.add(0, MENU_DELETE, 4, R.string.action_delete_evidence)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_VERIFY -> viewModel.onVerify(row.clientRef)
                MENU_RETRY -> viewModel.onRetryUpload(row.clientRef)
                MENU_REMOVE -> confirmRemove(row)
                MENU_DOWNLOAD -> viewModel.onDownload(row.clientRef)
                MENU_DELETE -> confirmDelete(row)
            }
            true
        }
        menu.show()
    }

    private fun confirmRemove(row: VaultRow) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.vault_remove_title)
            .setMessage(R.string.vault_remove_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_remove_from_phone) { _, _ -> viewModel.onRemoveFromPhone(row.clientRef) }
            .show()
    }

    /** Delete everywhere: explains what happens, then asks for the account password (checked by the server). */
    private fun confirmDelete(row: VaultRow) {
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
            .setTitle(R.string.vault_delete_title)
            .setMessage(R.string.vault_delete_message)
            .setView(layout)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete_evidence) { _, _ ->
                val password = field.text?.toString().orEmpty()
                field.setText("")
                if (password.isNotBlank()) viewModel.onDelete(row.clientRef, password)
            }
            .create()
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
    }

    // ---- viewers: decrypted only into the private viewing folder, deleted on close --------------------

    private fun openViewer(clientRef: String, kind: EvidenceKind, file: File) {
        viewer?.dismiss()
        val dialog = when (kind) {
            EvidenceKind.PHOTO -> photoViewer(file).also { viewModel.onViewerClosed(clientRef) } // decoded into memory: file not needed
            EvidenceKind.AUDIO -> audioViewer(file)
            EvidenceKind.VIDEO -> videoViewer(file)
        } ?: run {
            viewModel.onViewerClosed(clientRef)
            toast(R.string.vault_open_failed)
            return
        }
        dialog.setOnDismissListener {
            dialogsToRelease.remove(dialog)?.invoke() // stop the player first, then delete the decrypted file
            viewModel.onViewerClosed(clientRef)
        }
        dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        viewer = dialog
        dialog.show()
    }

    private fun photoViewer(file: File): Dialog? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > MAX_PHOTO_PX || bounds.outHeight / sample > MAX_PHOTO_PX) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val image = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = getString(R.string.cd_evidence_photo)
        }
        return MaterialAlertDialogBuilder(requireContext())
            .setView(image)
            .setPositiveButton(R.string.action_close, null)
            .create()
    }

    private fun audioViewer(file: File): Dialog? {
        val player = try {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            return null
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.evidence_kind_audio)
            .setMessage(getString(R.string.vault_audio_length, formatElapsed((player.duration / 1000L).coerceAtLeast(0))))
            .setPositiveButton(R.string.action_close, null)
            .setNeutralButton(R.string.action_play_pause, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(Dialog.BUTTON_NEUTRAL).setOnClickListener { if (player.isPlaying) player.pause() else player.start() }
            player.start()
        }
        player.setOnCompletionListener { it.seekTo(0) }
        dialogsToRelease[dialog] = { player.release() }
        return dialog
    }

    private fun videoViewer(file: File): Dialog? {
        val context = requireContext()
        val video = VideoView(context)
        val container = FrameLayout(context).apply { addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)) }
        video.setMediaController(MediaController(context).apply { setAnchorView(video) })
        video.setVideoPath(file.absolutePath)
        video.setOnPreparedListener { video.start() }
        video.contentDescription = getString(R.string.cd_evidence_video)
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(container)
            .setPositiveButton(R.string.action_close, null)
            .create()
        dialogsToRelease[dialog] = { video.stopPlayback() }
        return dialog
    }

    /** Players are released when their dialog closes (before the decrypted file is deleted). */
    private val dialogsToRelease = mutableMapOf<Dialog, () -> Unit>()

    override fun onStop() {
        // Leaving the screen (home button, another app, screen off) closes the viewer, so decrypted evidence never
        // stays open, and locks the vault again. Not when only the screen-lock prompt covers it, and not on rotation.
        viewer?.dismiss()
        viewer = null
        if (!askingScreenLock && !requireActivity().isChangingConfigurations) {
            recoveryDialog?.dismiss() // never leave a passphrase on screen behind the app switcher
            recoveryDialog = null
            recoveryViewModel.onLeftScreen()
            viewModel.onLeftScreen()
            askedSinceStart = false
        }
        super.onStop()
    }

    private fun toast(text: Int) = Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()

    init {
        // Release players before the viewer's file is deleted.
        lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onDestroy(owner: androidx.lifecycle.LifecycleOwner) {
                dialogsToRelease.values.forEach { it() }
                dialogsToRelease.clear()
            }
        })
    }

    companion object {
        const val ARG_EMERGENCY_ID = "emergencyId"
        private const val MAX_PHOTO_PX = 2048
        private const val MENU_VERIFY = 1
        private const val MENU_REMOVE = 2
        private const val MENU_DOWNLOAD = 3
        private const val MENU_DELETE = 4
        private const val MENU_RETRY = 5
    }
}
