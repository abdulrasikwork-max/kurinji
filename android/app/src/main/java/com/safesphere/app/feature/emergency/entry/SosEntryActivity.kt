package com.safesphere.app.feature.emergency.entry

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.safesphere.app.MainActivity
import com.safesphere.app.appContainer

/**
 * Phase 13F (F11): the target of the SOS launcher shortcut and the Quick Settings tile. NOT exported (manifest), so only
 * this app's own shortcut (started by the launcher as this app) and tile can open it. It records an in-memory
 * [SosLaunchRequest], opens the app, and closes; MainActivity then opens the SOS screen with the countdown running
 * (cancellable for 5 s, as from the SOS tile). Never visible.
 */
class SosEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContainer.sosLaunchRequest.request(SystemClock.elapsedRealtime())
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
