package com.safesphere.app.core.push

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import com.safesphere.app.MainActivity
import com.safesphere.app.appContainer

/**
 * N7 (D34): the target of every IRL notification tap. NOT exported (manifest), so only IRL's own PendingIntents reach
 * it; the two extras are validated again ([PushTarget.from]) and anything else is ignored. It records an in-memory
 * [PushOpenRequest], brings IRL to the front and closes; MainActivity opens the screen once a signed-in screen shows.
 * Opening tells nobody anything (no read receipt). Never visible.
 */
class NotificationRouterActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PushTarget.from(intent?.getStringExtra(PushTarget.EXTRA_TARGET), intent?.getStringExtra(PushTarget.EXTRA_ID))
            ?.let { appContainer.pushOpenRequest.request(it, SystemClock.elapsedRealtime()) }
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
