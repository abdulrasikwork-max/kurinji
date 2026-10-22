package com.safesphere.app.core.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.safesphere.app.R

/**
 * Asks for the phone's OWN screen lock (PIN, pattern, password, or a strong biometric) with the platform APIs, so no
 * new library is needed: BiometricPrompt with device-credential fallback on Android 10+, the Keyguard confirmation
 * screen on Android 8 and 9. The app never sees the PIN or the fingerprint; Android only answers "confirmed" or not.
 *
 * Create it as a Fragment field (it registers an activity-result launcher, which must happen before the fragment
 * starts). Phase 5 (Evidence Vault); moved to core/ui in Phase 10B so Rise can use it too (no behaviour change).
 */
class ScreenLockGate(
    private val fragment: Fragment,
    @StringRes private val titleRes: Int = R.string.vault_unlock_title,
    @StringRes private val subtitleRes: Int = R.string.vault_unlock_subtitle,
    private val onResult: (Result) -> Unit
) {

    enum class Result { CONFIRMED, CANCELLED, NO_SCREEN_LOCK }

    private val legacyLauncher = fragment.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onResult(if (it.resultCode == Activity.RESULT_OK) Result.CONFIRMED else Result.CANCELLED)
    }

    private var cancellation: CancellationSignal? = null

    fun hasScreenLock(): Boolean =
        (fragment.requireContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    fun ask() {
        val context = fragment.requireContext()
        if (!hasScreenLock()) {
            onResult(Result.NO_SCREEN_LOCK)
            return
        }
        val title = context.getString(titleRes)
        val subtitle = context.getString(subtitleRes)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> prompt(
                BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    )
                    .build()
            )
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> prompt(
                @Suppress("DEPRECATION")
                BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle).setDeviceCredentialAllowed(true).build()
            )
            else -> {
                val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                @Suppress("DEPRECATION")
                val intent = keyguard.createConfirmDeviceCredentialIntent(title, subtitle)
                if (intent == null) onResult(Result.NO_SCREEN_LOCK) else legacyLauncher.launch(intent)
            }
        }
    }

    /** Called when the screen goes away, so a prompt never outlives it. */
    fun cancel() {
        cancellation?.cancel()
        cancellation = null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun prompt(prompt: BiometricPrompt) {
        val signal = CancellationSignal().also { cancellation = it }
        prompt.authenticate(signal, fragment.requireContext().mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                cancellation = null
                onResult(Result.CONFIRMED)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                cancellation = null
                onResult(Result.CANCELLED) // cancelled, too many tries, or the screen went away
            }
            // onAuthenticationFailed = one wrong finger: Android keeps the prompt open, nothing to do.
        })
    }
}
