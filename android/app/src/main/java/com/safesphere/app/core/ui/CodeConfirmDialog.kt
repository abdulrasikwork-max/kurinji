package com.safesphere.app.core.ui

import android.text.InputFilter
import android.text.InputType
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.safesphere.app.R
import com.safesphere.app.core.confirm.CodeRequest
import com.safesphere.app.core.confirm.Confirmation

/**
 * Phase N2: "Enter the code we texted to +91 ••••••3210" over the explanation of one sensitive action (it replaced the
 * password prompts of Phase 10A). Six digits confirm ([onConfirm]); "Send a new code" asks again ([onResend]); closing
 * it changes nothing ([onDismiss]). FLAG_SECURE: the code is nobody else's business, nor is the action.
 * The typed code is passed on once and the field is cleared; nothing is kept.
 */
fun Fragment.showCodeConfirmDialog(
    titleRes: Int,
    messageRes: Int,
    actionRes: Int,
    request: CodeRequest,
    onConfirm: (Confirmation) -> Unit,
    onResend: () -> Unit,
    onDismiss: () -> Unit,
    /** Called whenever the dialog goes away (after any of the above), for the screen's own bookkeeping. */
    onClosed: () -> Unit = {}
): AlertDialog {
    val context = requireContext()
    val field = TextInputEditText(context).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(Confirmation.CODE_LENGTH))
        setAutofillHints("smsOTPCode") // the same hint as the sign-in code field
    }
    val layout = TextInputLayout(context).apply {
        hint = getString(R.string.confirm_code_hint)
        addView(field)
        val padding = resources.getDimensionPixelSize(R.dimen.space_lg)
        setPadding(padding, 0, padding, 0)
    }
    val sentTo = request.phoneHint?.let { "+91 $it" } ?: getString(R.string.confirm_code_your_number)
    val message = getString(messageRes) + "\n\n" + getString(
        if (request.codeSent) R.string.confirm_code_sent_to else R.string.confirm_code_not_sent, sentTo)
    var handled = false
    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(titleRes)
        .setMessage(message)
        .setView(layout)
        .setNegativeButton(R.string.action_cancel, null)
        .setNeutralButton(R.string.action_send_new_code) { _, _ ->
            handled = true
            onResend()
        }
        .setPositiveButton(actionRes) { _, _ ->
            val confirmation = Confirmation.of(request, field.text?.toString())
            field.setText("")
            handled = true
            if (confirmation != null) onConfirm(confirmation) else onDismiss()
        }
        .setOnDismissListener {
            if (!handled) onDismiss()
            onClosed()
        }
        .create()
    dialog.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    dialog.show()
    return dialog
}
